import AVFoundation
import Combine
import Foundation
import WebRTC

struct RoomState: Equatable {
    var channelId: String?
    var serverId: String?
    var channelName = ""
    var participants: [Participant] = []
    var muted = false
    var deafened = false
    var noMic = false
    var connecting = false
    var camera = false
    var canVideo = true
    var speaker = true
    /// who has their camera on / is sharing their screen (you included): uuid -> video
    var cameras: [String: RTCVideoTrack] = [:]
    var screens: [String: RTCVideoTrack] = [:]
}

/// Voice rooms (server voice channels and calls in group chats): everyone connects to everyone (a
/// mesh, fine up to ~8 people). The one who joins starts the connections. If two people change
/// something at once, the smaller id gives way. See server/lib/voice.ts.
@MainActor
final class Voice: NSObject, ObservableObject {
    private final class Peer {
        let uuid: String
        var pc: RTCPeerConnection!
        var audio: RTCAudioTrack?
        var cameraSender: RTCRtpSender?
        var camera: String?             // which of their streams is the camera / screen
        var screen: String?
        var making = false
        var pending = false
        init(uuid: String) { self.uuid = uuid }
    }

    @Published private(set) var state = RoomState()
    private let api: Api
    var onError: (String) -> Void = { _ in }
    var onActive: (Bool) -> Void = { _ in }
    private var me = ""
    private var peers: [String: Peer] = [:]
    private var mic: RTCAudioTrack?
    private var cam: Rtc.Camera?
    private var ice: [[String: Any]] = []
    private var beat: Task<Void, Never>?

    init(api: Api) { self.api = api }

    func join(me myUuid: String, channel: String, server: String?, name: String) async {
        if state.channelId == channel { return }
        if state.channelId != nil { await leave() }
        me = myUuid
        state = RoomState(channelId: channel, serverId: server, channelName: name, connecting: true)
        onActive(true)
        do {
            ice = try await api.ice()
            let room = try await api.voiceJoin(channel)
            Rtc.audioOn(speaker: true)
            if await AVCaptureDevice.requestAccess(for: .audio) {
                let m = Rtc.mic()
                m.isEnabled = room.can_speak != false
                mic = m
            }
            state.participants = room.participants
            state.muted = room.can_speak == false
            state.connecting = false
            state.canVideo = room.can_stream != false
            state.noMic = mic == nil
            // the one who joins connects to everyone already here
            for p in room.participants where p.uuid != me { await connect(p.uuid) }
            beat = Task { while !Task.isCancelled { try? await Task.sleep(nanoseconds: 20_000_000_000); await sendState([:]) } }
        } catch {
            onError(error.localizedDescription)
            await leave()
        }
    }

    func leave() async {
        let id = state.channelId
        beat?.cancel(); beat = nil
        for uuid in Array(peers.keys) { drop(uuid) }
        mic = nil
        cam?.stop(); cam = nil
        state = RoomState()
        Rtc.audioOff()
        onActive(false)
        if let id { await api.voiceLeave(id) }
    }

    func toggleMute() async {
        state.muted.toggle()
        mic?.isEnabled = !state.muted && !state.deafened
        await sendState(["muted": state.muted])
    }

    func toggleDeafen() async {
        state.deafened.toggle()
        for p in peers.values { p.audio?.isEnabled = !state.deafened }
        mic?.isEnabled = !state.deafened && !state.muted
        await sendState(["deafened": state.deafened])
    }

    func toggleSpeaker() {
        state.speaker.toggle()
        Rtc.setSpeaker(state.speaker)
    }

    /// Turn your camera on or off for everyone in the room.
    func toggleCamera() async {
        guard state.channelId != nil else { return }
        if let c = cam {
            for (uuid, p) in peers {
                if let s = p.cameraSender { p.pc.removeTrack(s) }
                p.cameraSender = nil
                await renegotiate(uuid)
            }
            c.stop(); cam = nil
            state.camera = false
            state.cameras[me] = nil
            return
        }
        guard let c = Rtc.Camera() else { return onError("Couldn't turn on your camera - check that it's allowed in Settings") }
        cam = c
        state.camera = true
        state.cameras[me] = c.track
        for (uuid, p) in peers {
            p.cameraSender = p.pc.add(c.track, streamIds: [c.streamId])
            await renegotiate(uuid)
        }
    }

    /// Live "voice" event: who's in the room changed.
    func refresh() async {
        guard let id = state.channelId else { return }
        guard let participants = try? await api.voiceRoom(id) else { return }
        if !participants.contains(where: { $0.uuid == me }) { await leave(); return }      // kicked or timed out
        for uuid in Array(peers.keys) where !participants.contains(where: { $0.uuid == uuid }) { drop(uuid) }
        state.participants = participants
    }

    /// Live "voice_signal" event: an offer or answer from someone in the room.
    func onSignal(_ e: LiveEvent) async {
        guard e.str("channel_id") == state.channelId, let from = e.str("from"), let sid = e.int("id") else { return }
        do {
            let signal = try await api.readVoiceSignal(sid)
            let r = Sdp.read(signal.sdp ?? "")
            if signal.kind == "offer" {
                let isNew = peers[from] == nil
                let peer = try peers[from] ?? newPeer(from)
                guard let pc = peer.pc else { return }
                if pc.signalingState != .stable || peer.making {
                    // both changed something at once: the bigger id keeps its offer, the smaller one gives way
                    if me > from && !r.noYield { return }
                    if pc.signalingState == .haveLocalOffer { try await pc.rollback() }
                    peer.pending = true
                }
                peer.camera = r.camera; peer.screen = r.screen
                try await pc.setRemote(.offer, r.sdp)
                if r.camera == nil { state.cameras[from] = nil }
                if r.screen == nil { state.screens[from] = nil }
                let sdp = Sdp.tag(try await pc.describe(offer: false), camera: cam?.streamId, screen: nil)
                guard let channel = state.channelId else { return }
                try await api.voiceSignal(channel, to: from, kind: "answer", sdp: sdp)
                // they joined while our camera was on, or our own change gave way: offer ours now
                if (isNew && cam != nil) || peer.pending {
                    peer.pending = false
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    await renegotiate(from)
                }
            } else if let peer = peers[from], peer.pc.signalingState == .haveLocalOffer {
                if r.camera != nil { peer.camera = r.camera }
                if r.screen != nil { peer.screen = r.screen }
                try await peer.pc.setRemote(.answer, r.sdp)
                if peer.pending { peer.pending = false; await renegotiate(from) }
            }
        } catch {
            NSLog("voice signal: %@", error.localizedDescription)
        }
    }

    // -- internals
    private func newPeer(_ uuid: String) throws -> Peer {
        let peer = Peer(uuid: uuid)
        guard let pc = Rtc.factory.peerConnection(with: Rtc.config(ice), constraints: Rtc.constraints(), delegate: self) else {
            throw ApiError(status: 0, message: "Couldn't connect")
        }
        peer.pc = pc
        if let m = mic { pc.add(m, streamIds: ["mic"]) } else {
            let recv = RTCRtpTransceiverInit()
            recv.direction = .recvOnly
            pc.addTransceiver(of: .audio, init: recv)
        }
        if let c = cam { peer.cameraSender = pc.add(c.track, streamIds: [c.streamId]) }
        peers[uuid] = peer
        return peer
    }

    private func connect(_ uuid: String) async {
        guard peers[uuid] == nil, (try? newPeer(uuid)) != nil else { return }
        await renegotiate(uuid)
    }

    /// Send our current camera / mic setup to one person. One change at a time.
    private func renegotiate(_ uuid: String) async {
        guard let peer = peers[uuid], let channel = state.channelId else { return }
        if peer.making || peer.pc.signalingState != .stable { peer.pending = true; return }
        peer.making = true
        defer { peer.making = false }
        do {
            let sdp = Sdp.tag(try await peer.pc.describe(offer: true), camera: cam?.streamId, screen: nil)
            try await api.voiceSignal(channel, to: uuid, kind: "offer", sdp: sdp)
        } catch { NSLog("voice offer: %@", error.localizedDescription) }
    }

    private func drop(_ uuid: String) {
        guard let p = peers.removeValue(forKey: uuid) else { return }
        p.pc.close()
        state.cameras[uuid] = nil
        state.screens[uuid] = nil
    }

    private func sendState(_ fields: [String: Any]) async {
        guard let id = state.channelId else { return }
        do { try await api.voiceState(id, fields) } catch { onError(error.localizedDescription) }
    }

    fileprivate func peer(for pc: RTCPeerConnection) -> Peer? { peers.values.first { $0.pc === pc } }

    fileprivate func added(_ track: RTCMediaStreamTrack, stream: String?, on pc: RTCPeerConnection) {
        guard let peer = peer(for: pc) else { return }
        if let a = track as? RTCAudioTrack { peer.audio = a; a.isEnabled = !state.deafened }
        else if let v = track as? RTCVideoTrack {
            if let stream, stream == peer.camera { state.cameras[peer.uuid] = v } else { state.screens[peer.uuid] = v }
        }
    }

    fileprivate func failed(_ pc: RTCPeerConnection) {
        guard let peer = peer(for: pc) else { return }
        drop(peer.uuid)
        Task { await connect(peer.uuid) }      // try once more
    }
}

extension Voice: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        if newState == .failed { Task { @MainActor in self.failed(peerConnection) } }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
        guard let track = rtpReceiver.track else { return }
        let sid = mediaStreams.first?.streamId
        Task { @MainActor in self.added(track, stream: sid, on: peerConnection) }
    }
}
