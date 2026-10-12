import Combine
import Foundation

/// A live event from Jace Social: call, channel, command, dm_update, friends, groups, message,
/// presence, servers, typing, voice, voice_signal (see docs/native-apps.md).
struct LiveEvent {
    let event: String
    let payload: [String: Any]
    func str(_ key: String) -> String? { payload[key] as? String }
    func int(_ key: String) -> Int? { (payload[key] as? NSNumber)?.intValue ?? (payload[key] as? String).flatMap { Int($0) } }
    func bool(_ key: String) -> Bool { (payload[key] as? NSNumber)?.boolValue ?? false }
}

/// Supabase Realtime over a websocket (the Phoenix protocol, like Jace Launcher's social_live.py):
/// join our private channel and pass its broadcasts on. Reconnects by itself.
final class Live {
    let events = PassthroughSubject<LiveEvent, Never>()
    private var task: URLSessionWebSocketTask?
    private var running = false
    private var ref = 0
    private var heartbeat: Timer?

    func start(_ realtime: Realtime, inbox: String) {
        stop()
        guard !realtime.url.isEmpty, !realtime.key.isEmpty, !inbox.isEmpty else { return }
        running = true
        let base = realtime.url.replacingOccurrences(of: "https://", with: "wss://").replacingOccurrences(of: "http://", with: "ws://")
        let key = realtime.key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? realtime.key
        guard let url = URL(string: "\(base)/realtime/v1/websocket?apikey=\(key)&vsn=1.0.0") else { return }
        connect(url, topic: "realtime:\(inbox)")
    }

    func stop() {
        running = false
        heartbeat?.invalidate()
        heartbeat = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    private func connect(_ url: URL, topic: String) {
        let t = URLSession.shared.webSocketTask(with: url)
        task = t
        t.resume()
        send(topic, "phx_join", ["config": ["broadcast": ["ack": false, "self": false], "presence": ["key": ""], "private": false]])
        heartbeat?.invalidate()
        heartbeat = Timer.scheduledTimer(withTimeInterval: 25, repeats: true) { [weak self] _ in self?.send("phoenix", "heartbeat", [:]) }
        receive(t, url: url, topic: topic)
    }

    private func receive(_ t: URLSessionWebSocketTask, url: URL, topic: String) {
        t.receive { [weak self] result in
            guard let self, self.task === t else { return }
            switch result {
            case .success(let msg):
                if case .string(let text) = msg, let data = text.data(using: .utf8),
                   let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                   obj["event"] as? String == "broadcast", let inner = obj["payload"] as? [String: Any],
                   let event = inner["event"] as? String {
                    let payload = inner["payload"] as? [String: Any] ?? [:]
                    DispatchQueue.main.async { self.events.send(LiveEvent(event: event, payload: payload)) }
                }
                self.receive(t, url: url, topic: topic)
            case .failure:
                guard self.running else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in   // reconnect
                    guard let self, self.running else { return }
                    self.connect(url, topic: topic)
                }
            }
        }
    }

    private func send(_ topic: String, _ event: String, _ payload: [String: Any]) {
        ref += 1
        let msg: [String: Any] = ["topic": topic, "event": event, "payload": payload, "ref": String(ref), "join_ref": "1"]
        if let data = try? JSONSerialization.data(withJSONObject: msg), let text = String(data: data, encoding: .utf8) {
            task?.send(.string(text)) { _ in }
        }
    }
}
