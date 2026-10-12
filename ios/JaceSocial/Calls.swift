import AVFoundation
import Combine
import Foundation
import WebRTC

struct CallInfo: Equatable {
    var state = "idle"              // idle | calling | ringing | in-call
    var peer = ""
    var peerName = ""
    var muted = false
    var startedAt: Date?
    var noMic = false
    var speaker = false
    var camera: RTCVideoTrack?
    var remoteCamera: RTCVideoTrack?
    var remoteScreen: RTCVideoTrack?
}

/// Voice calls with a friend: the same signalling as the website and Jace Launcher (POST /calls
/// offer / answer / hangup / renegotiate / reanswer, live "call" events), one connection, audio plus
/// camera added mid-call, and their camera and screen shown. See server/lib/calls.ts.
@MainActor
final class Calls: NSObject, ObservableObject {
    @Published private(set) var info = CallInfo()
    private let api: Api
    var onEnded: (String) -> Void = { _ in }
    var onIncoming: (String) -> Void = { _ in }
    var onActive: (Bool) -> Void = { _ in }
    private var callId = ""
    private var offerSdp = ""
    private var pc: RTCPeerConnection?
    private var mic: RTCAudioTrack?
    private var cam: Rtc.Camera?
    private var camSender: RTCRtpSender?
    private var remoteCameraId: String?
    private var making = false
    private var pending = false
    private var callerSide = false
    private var moving = false
    private var timeout: Task<Void, Never>?
    private var ring: Ringer?

    init(api: Api) { self.api = api }

    func call(_ uuid: String, name: String) {
        guard info.state == "idle" else { return onEnded("You're already in a call") }
        let id = Self.newCallId()
        callId = id
        callerSide = true
        info = CallInfo(state: "calling", peer: uuid, peerName: name)
        onActive(true)
        ring = Ringer(incoming: false)
        Task {
            do {
                let pc = try await newPeer(id)
                let sdp = try await pc.describe(offer: true)
                guard callId == id else { return }
                try await api.callSignal(to: uuid, callId: id, kind: "offer", sdp: sdp)
                startTimeout()
            } catch {
                if callId == id { end(error.localizedDescription) }
            }
        }
    }

    /// Take over a call we're in on another device (same call id; their side swaps connections without ringing).
    func takeOver(_ uuid: String, name: String, id: String) {
        guard info.state == "idle", id.range(of: "^[A-Za-z0-9_-]{8,64}$", options: .regularExpression) != nil else { return }
        callId = id
        callerSide = true
        moving = true
        info = CallInfo(state: "calling", peer: uuid, peerName: name.isEmpty ? "A friend" : name)
        onActive(true)
        Task {
            do {
                let pc = try await newPeer(id)
                let recv = RTCRtpTransceiverInit()
                recv.direction = .recvOnly
                pc.addTransceiver(of: .video, init: recv)
                pc.addTransceiver(of: .video, init: recv)
                pc.addTransceiver(of: .audio, init: recv)
                var sdp = try await pc.describe(offer: true)
                while sdp.hasSuffix("\n") || sdp.hasSuffix("\r") { sdp.removeLast() }
                sdp += "\r\na=x-jace-move\r\n"
                guard callId == id else { return }
                try await api.callSignal(to: uuid, callId: id, kind: "offer", sdp: sdp)
                timeout = Task { try? await Task.sleep(nanoseconds: 20_000_000_000); if moving && info.state == "calling" { end("Couldn't move the call here - it's still going on on your other device") } }
            } catch {
                if callId == id { end(error.localizedDescription) }
            }
        }
    }

    func answer() {
        guard info.state == "ringing" else { return }
        let peer = info.peer, id = callId
        stopRing()
        timeout?.cancel()
        info.state = "in-call"
        info.startedAt = Date()
        Task {
            do {
                let pc = try await newPeer(id)
                let r = Sdp.read(offerSdp)
                remoteCameraId = r.camera
                try await pc.setRemote(.offer, r.sdp)
                let sdp = try await pc.describe(offer: false)
                guard callId == id else { return }
                try await api.callSignal(to: peer, callId: id, kind: "answer", sdp: sdp)
            } catch {
                if callId == id { hangUp(error.localizedDescription) }
            }
        }
    }

    func hangUp(_ reason: String = "") {
        guard info.state != "idle" else { return }
        let peer = info.peer, id = callId
        Task { try? await api.callSignal(to: peer, callId: id, kind: "hangup") }
        end(reason)
    }

    func toggleMute() {
        info.muted.toggle()
        mic?.isEnabled = !info.muted
    }

    func toggleSpeaker() {
        info.speaker.toggle()
        Rtc.setSpeaker(info.speaker)
    }

    /// Turn your camera on or off (adds or removes a video track, then renegotiates).
    func toggleCamera() {
        guard let pc, info.state == "in-call" else { return }
        if let c = cam {
            if let s = camSender { pc.removeTrack(s) }
            c.stop(); cam = nil; camSender = nil
            info.camera = nil
        } else {
            guard let c = Rtc.Camera() else { return onEnded("Couldn't turn on your camera - check that it's allowed in Settings") }
            cam = c
            camSender = pc.add(c.track, streamIds: [c.streamId])
            info.camera = c.track
            info.speaker = true
            Rtc.setSpeaker(true)
        }
        renegotiate()
    }

    private func renegotiate() {
        guard let pc else { return }
        if making || pc.signalingState != .stable { pending = true; return }
        making = true
        Task {
            defer { making = false }
            do {
                let sdp = Sdp.tag(try await pc.describe(offer: true), camera: cam?.streamId, screen: nil)
                try await api.callSignal(to: info.peer, callId: callId, kind: "renegotiate", sdp: sdp)
            } catch { onEnded(error.localizedDescription) }
        }
    }

    /// A live "call" event: {id, kind, call_id, from, name}.
    func onSignal(_ e: LiveEvent) {
        guard let kind = e.str("kind"), let sid = e.int("id") else { return }
        let eventCall = e.str("call_id") ?? ""
        Task { await handle(kind, eventCall, sid, e.str("name")) }
    }

    private func handle(_ kind: String, _ eventCall: String, _ sid: Int, _ name: String?) async {
        let i = info
        if kind == "offer" {
            guard let signal = try? await api.readCallSignal(sid), let sdp = signal.sdp, let sender = signal.sender else { return }   // expired
            if i.state == "in-call" && eventCall == callId && sender == i.peer { await replace(sdp); return }
            if Sdp.read(sdp).moving { return }             // someone else's call moving between devices
            if i.state != "idle" { try? await api.callSignal(to: sender, callId: eventCall, kind: "hangup"); return }   // busy
            callId = eventCall
            offerSdp = sdp
            info = CallInfo(state: "ringing", peer: sender, peerName: (signal.name?.isEmpty == false ? signal.name : name) ?? "A friend")
            onActive(true)
            ring = Ringer(incoming: true)
            startTimeout()
            onIncoming(info.peerName)
            return
        }
        guard eventCall == callId else { return }
        do {
            switch kind {
            case "answer" where i.state == "calling":
                stopRing(); timeout?.cancel(); moving = false
                info.state = "in-call"; info.startedAt = Date()
                let r = Sdp.read(try await api.readCallSignal(sid).sdp ?? "")
                remoteCameraId = r.camera
                try await pc?.setRemote(.answer, r.sdp)
            case "renegotiate":
                guard let pc else { return }
                let r = Sdp.read(try await api.readCallSignal(sid).sdp ?? "")
                if pc.signalingState != .stable || making {
                    // both changed something at once: the caller keeps its offer, the one who answered gives way
                    if i.state == "in-call" && callerSide && !r.noYield { return }
                    if pc.signalingState == .haveLocalOffer { try await pc.rollback() }
                    pending = true
                }
                remoteCameraId = r.camera
                try await pc.setRemote(.offer, r.sdp)
                if r.camera == nil { info.remoteCamera = nil }
                if r.screen == nil { info.remoteScreen = nil }
                let sdp = Sdp.tag(try await pc.describe(offer: false), camera: cam?.streamId, screen: nil)
                try await api.callSignal(to: i.peer, callId: callId, kind: "reanswer", sdp: sdp)
                if pending { pending = false; try? await Task.sleep(nanoseconds: 300_000_000); renegotiate() }
            case "reanswer" where pc?.signalingState == .haveLocalOffer:
                try await pc?.setRemote(.answer, try await api.readCallSignal(sid).sdp ?? "")
                if pending { pending = false; renegotiate() }
            case "answer" where i.state == "in-call" && !moving:
                end("Moved the call to another device")
            case "hangup":
                end(i.state == "in-call" ? "\(i.peerName) hung up" : i.state == "calling" ? "\(i.peerName) didn't pick up" : "")
            default: break
            }
        } catch {
            if kind == "answer" { hangUp(error.localizedDescription) }
        }
    }

    /// Answer a moved call on a new connection, keeping our camera going.
    private func replace(_ offer: String) async {
        let id = callId
        let old = pc
        let r = Sdp.read(offer)
        remoteCameraId = r.camera
        making = false; pending = false; callerSide = false
        info.remoteCamera = nil; info.remoteScreen = nil
        do {
            let pc = try await newPeer(id)
            old?.close()
            try await pc.setRemote(.offer, r.sdp)
            if let c = cam { camSender = pc.add(c.track, streamIds: [c.streamId]) }
            let sdp = Sdp.tag(try await pc.describe(offer: false), camera: cam?.streamId, screen: nil)
            guard callId == id else { return }
            try await api.callSignal(to: info.peer, callId: id, kind: "answer", sdp: sdp)
        } catch {
            if callId == id { hangUp(error.localizedDescription) }
        }
    }

    private func newPeer(_ id: String) async throws -> RTCPeerConnection {
        let ice = try await api.ice()
        guard callId == id else { throw ApiError(status: 0, message: "Call ended") }
        Rtc.audioOn(speaker: info.speaker)
        guard let pc = Rtc.factory.peerConnection(with: Rtc.config(ice), constraints: Rtc.constraints(), delegate: self) else {
            throw ApiError(status: 0, message: "Couldn't start the call")
        }
        self.pc = pc
        let allowed = await AVCaptureDevice.requestAccess(for: .audio)
        if allowed {
            let m = mic ?? Rtc.mic()
            mic = m
            m.isEnabled = !info.muted
            pc.add(m, streamIds: ["mic"])
        } else {
            let recv = RTCRtpTransceiverInit()
            recv.direction = .recvOnly
            pc.addTransceiver(of: .audio, init: recv)
        }
        info.noMic = !allowed
        return pc
    }

    private func startTimeout() {
        timeout?.cancel()
        timeout = Task {
            try? await Task.sleep(nanoseconds: 45_000_000_000)
            guard !Task.isCancelled else { return }
            if info.state == "calling" { hangUp("No answer") } else if info.state == "ringing" { end("") }
        }
    }

    private func stopRing() { ring?.stop(); ring = nil }

    private func end(_ reason: String) {
        timeout?.cancel(); timeout = nil
        stopRing()
        pc?.close(); pc = nil
        mic = nil
        cam?.stop(); cam = nil; camSender = nil
        remoteCameraId = nil
        making = false; pending = false; callerSide = false; moving = false
        callId = ""; offerSdp = ""
        info = CallInfo()
        Rtc.audioOff()
        onActive(false)
        if !reason.isEmpty { onEnded(reason) }
    }

    private static func newCallId() -> String {
        var b = [UInt8](repeating: 0, count: 12)
        _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        return Data(b).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

extension Calls: RTCPeerConnectionDelegate {
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
        if newState == .failed { Task { @MainActor in self.hangUp("Couldn't connect the call") } }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
        guard let track = rtpReceiver.track as? RTCVideoTrack else { return }
        let sid = mediaStreams.first?.streamId
        Task { @MainActor in
            if let sid, sid == self.remoteCameraId { self.info.remoteCamera = track } else { self.info.remoteScreen = track }
        }
    }
}

/// Ringing: the phone's sound for a call coming in, soft beeps while calling out.
final class Ringer {
    private var timer: Timer?
    init(incoming: Bool) {
        let play = { AudioServicesPlaySystemSound(incoming ? 1005 : 1016) }
        play()
        timer = Timer.scheduledTimer(withTimeInterval: incoming ? 2 : 3, repeats: true) { _ in play() }
        if incoming { AudioServicesPlaySystemSound(kSystemSoundID_Vibrate) }
    }
    func stop() { timer?.invalidate(); timer = nil }
}
