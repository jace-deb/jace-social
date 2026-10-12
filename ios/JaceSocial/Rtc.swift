import AVFoundation
import Foundation
import WebRTC

/// WebRTC for calls and voice rooms: one factory for the app, microphone and camera tracks, and
/// descriptions sent whole once ICE gathering is done (no trickle), like the website
/// (server/lib/calls.ts, voice.ts) and Jace Launcher.
enum Rtc {
    static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
    }()

    static func config(_ ice: [[String: Any]]) -> RTCConfiguration {
        let c = RTCConfiguration()
        c.iceServers = ice.compactMap { s in
            let urls = (s["urls"] as? [String]) ?? ((s["urls"] as? String).map { [$0] } ?? [])
            guard !urls.isEmpty else { return nil }
            return RTCIceServer(urlStrings: urls, username: s["username"] as? String, credential: s["credential"] as? String)
        }
        c.sdpSemantics = .unifiedPlan
        return c
    }

    static func constraints() -> RTCMediaConstraints { RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil) }

    /// The microphone (iOS does echo cancelling in voice chat mode).
    static func mic() -> RTCAudioTrack {
        factory.audioTrack(with: factory.audioSource(with: constraints()), trackId: "mic-\(UUID().uuidString)")
    }

    /// Calls sound: voice chat mode, on the speaker when there's video (or you asked for it).
    static func audioOn(speaker: Bool) {
        let s = RTCAudioSession.sharedInstance()
        s.lockForConfiguration()
        try? s.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .allowBluetoothA2DP])
        try? s.setActive(true)
        try? s.overrideOutputAudioPort(speaker ? .speaker : .none)
        s.unlockForConfiguration()
    }
    static func setSpeaker(_ on: Bool) {
        let s = RTCAudioSession.sharedInstance()
        s.lockForConfiguration()
        try? s.overrideOutputAudioPort(on ? .speaker : .none)
        s.unlockForConfiguration()
    }
    static func audioOff() {
        let s = RTCAudioSession.sharedInstance()
        s.lockForConfiguration()
        try? s.setActive(false)
        s.unlockForConfiguration()
    }

    /// Your camera being sent: its track, the id of its stream (the receiver tells camera and screen
    /// apart by it), and how to stop it.
    final class Camera {
        let track: RTCVideoTrack
        let streamId = "s-\(UUID().uuidString.prefix(12))"
        private let capturer: RTCCameraVideoCapturer

        init?(front: Bool = true) {
            let devices = RTCCameraVideoCapturer.captureDevices()
            guard let device = devices.first(where: { $0.position == (front ? .front : .back) }) ?? devices.first else { return nil }
            let source = Rtc.factory.videoSource()
            capturer = RTCCameraVideoCapturer(delegate: source)
            // the format closest to 640x360 (fine for several people at once)
            let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
            guard let format = formats.min(by: { a, b in
                let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription), db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
                return abs(Int(da.width) - 640) + abs(Int(da.height) - 360) < abs(Int(db.width) - 640) + abs(Int(db.height) - 360)
            }) else { return nil }
            let fps = min(24, Int(format.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 24))
            capturer.startCapture(with: device, format: format, fps: fps)
            track = Rtc.factory.videoTrack(with: source, trackId: "video-\(UUID().uuidString)")
        }

        func stop() { capturer.stopCapture() }
    }
}

extension RTCPeerConnection {
    private func make(offer: Bool) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<RTCSessionDescription, Error>) in
            let done: (RTCSessionDescription?, Error?) -> Void = { d, e in
                if let d { k.resume(returning: d) } else { k.resume(throwing: e ?? ApiError(status: 0, message: "Couldn't start the call")) }
            }
            if offer { self.offer(for: Rtc.constraints(), completionHandler: done) } else { self.answer(for: Rtc.constraints(), completionHandler: done) }
        }
    }

    private func set(local: Bool, _ d: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            let done: (Error?) -> Void = { e in if let e { k.resume(throwing: e) } else { k.resume() } }
            if local { self.setLocalDescription(d, completionHandler: done) } else { self.setRemoteDescription(d, completionHandler: done) }
        }
    }

    func setRemote(_ type: RTCSdpType, _ sdp: String) async throws { try await set(local: false, RTCSessionDescription(type: type, sdp: sdp)) }
    func rollback() async throws { try await set(local: true, RTCSessionDescription(type: .rollback, sdp: "")) }

    /// Make an offer or answer, set it, wait (up to 5 s) for every ICE candidate, then the full description.
    func describe(offer: Bool) async throws -> String {
        let d = try await make(offer: offer)
        try await set(local: true, d)
        for _ in 0..<100 where iceGatheringState != .complete { try? await Task.sleep(nanoseconds: 50_000_000) }
        guard let sdp = localDescription?.sdp else { throw ApiError(status: 0, message: "Couldn't start the call") }
        return sdp
    }
}

/// Camera and screen both arrive as video, so each description says which stream is which in one
/// extra line (server/lib/media.ts). Also "a=x-jace-move" (a call moving to another device) and
/// "a=x-jace-noyield" (from Jace Launcher, which can't take back an offer).
enum Sdp {
    static let tagLine = "a=x-jace-streams:"

    struct Read { var sdp: String; var camera: String?; var screen: String?; var moving = false; var noYield = false }

    static func tag(_ sdp: String, camera: String?, screen: String?) -> String {
        let parts = [camera.map { "camera=\($0)" }, screen.map { "screen=\($0)" }].compactMap { $0 }
        if parts.isEmpty { return sdp }
        var s = sdp
        while s.hasSuffix("\n") || s.hasSuffix("\r") { s.removeLast() }
        return s + "\r\n" + tagLine + parts.joined(separator: ",") + "\r\n"
    }

    static func read(_ sdp: String) -> Read {
        var r = Read(sdp: "")
        let lines = sdp.components(separatedBy: "\n").map { $0.hasSuffix("\r") ? String($0.dropLast()) : $0 }.filter { line in
            if line == "a=x-jace-move" { r.moving = true; return false }
            if line == "a=x-jace-noyield" { r.noYield = true; return false }
            if line.hasPrefix(tagLine) {
                for part in line.dropFirst(tagLine.count).split(separator: ",") {
                    let kv = part.split(separator: "=", maxSplits: 1).map { String($0).trimmingCharacters(in: .whitespaces) }
                    guard kv.count == 2, !kv[1].isEmpty else { continue }
                    if kv[0] == "camera" { r.camera = kv[1] }
                    if kv[0] == "screen" { r.screen = kv[1] }
                }
                return false
            }
            return !line.hasPrefix("a=x-jace-")
        }
        r.sdp = lines.joined(separator: "\r\n")
        return r
    }
}
