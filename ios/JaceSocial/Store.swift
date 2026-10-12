import Combine
import Foundation
import UIKit
import UserNotifications

/// What the app shows everywhere: you, your friends, group chats and servers, calls and voice.
/// Loaded from the API and kept current by live events.
@MainActor
final class Store: ObservableObject {
    let session: Session
    let live = Live()
    let calls: Calls
    let voice: Voice
    @Published var me: Me?
    @Published var friends: FriendsData?
    @Published var groups: [Group] = []
    @Published var servers: [ServerItem] = []
    @Published var error: String?
    /// who's typing where: chat key -> names
    @Published var typing: [String: Set<String>] = [:]
    /// A short message at the bottom ("Saved", errors from actions, a call ending).
    @Published var toast: String?
    /// A link waiting for "Open it?" (the link warning).
    @Published var pendingLink: URL?
    /// A newer version of the app, when there is one; askUpdate: offer it now.
    @Published var update: Release?
    @Published var askUpdate: Release?
    private var bag = Set<AnyCancellable>()
    private var heartbeat: Task<Void, Never>?
    private var toastTask: Task<Void, Never>?

    init(session: Session) {
        self.session = session
        calls = Calls(api: session.api)
        voice = Voice(api: session.api)
        calls.onEnded = { [weak self] in self?.show($0) }
        calls.onIncoming = { [weak self] name in self?.notify(title: "\(name) is calling", body: "Tap to answer", important: true, call: true) }
        voice.onError = { [weak self] in self?.show($0) }
        live.events.sink { [weak self] e in self?.handle(e) }.store(in: &bag)
        // calls and voice changes redraw what shows them
        calls.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &bag)
        voice.objectWillChange.sink { [weak self] in self?.objectWillChange.send() }.store(in: &bag)
    }

    var api: Api { session.api }

    func start() async {
        await guarded { self.me = try await self.session.api.me() }
        if let m = me { live.start(m.realtime, inbox: m.inbox); session.remember(m) }
        heartbeat = Task { while !Task.isCancelled { await api.presence(); try? await Task.sleep(nanoseconds: 120_000_000_000) } }
        // ask about notifications without waiting for the answer (loading carries on meanwhile);
        // test builds signed in by CI don't ask, so the question doesn't cover the screenshots
        var ask = true
        #if DEBUG
        if UserDefaults.standard.string(forKey: "jaceToken") != nil { ask = false }
        #endif
        if ask { Task { _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) } }
        Task { try? await Task.sleep(nanoseconds: 3_000_000_000); _ = await checkUpdate(auto: true) }
        await refreshAll()
    }

    func stop() {
        calls.hangUp()
        Task { await voice.leave() }
        heartbeat?.cancel()
        let api = self.api
        Task.detached { await api.offline() }
        live.stop()
    }

    func show(_ text: String) {
        toast = text
        toastTask?.cancel()
        toastTask = Task { try? await Task.sleep(nanoseconds: 4_000_000_000); if !Task.isCancelled { toast = nil } }
    }

    func refreshAll() async {
        async let a: () = refreshFriends()
        async let b: () = refreshGroups()
        async let c: () = refreshServers()
        _ = await (a, b, c)
    }
    func refreshMe() async { await guarded { self.me = try await self.session.api.me() } }
    func refreshFriends() async { await guarded { self.friends = try await self.session.api.friends() } }
    func refreshGroups() async { await guarded { self.groups = try await self.session.api.groups() } }
    func refreshServers() async { await guarded { self.servers = try await self.session.api.servers() } }

    func person(_ uuid: String?) -> Person? {
        guard let uuid else { return nil }
        if let f = friends, let p = (f.friends + f.incoming + f.outgoing).first(where: { $0.uuid == uuid }) { return p }
        for g in groups { if let p = g.members.first(where: { $0.uuid == uuid }) { return p } }
        if let m = me, m.uuid == uuid { return m.asPerson }
        return nil
    }

    private func handle(_ e: LiveEvent) {
        switch e.event {
        case "friends":
            Task { await refreshFriends() }
            if e.str("kind") == "request" { notify(title: "Friend request", body: "\(e.str("name") ?? "Someone") wants to be friends", important: true) }
        case "presence", "dm_update": Task { await refreshFriends() }
        case "message":
            Task { await refreshFriends() }
            notify(title: e.str("name") ?? "A friend", body: "Sent you a message", important: true, mute: [e.str("from")].compactMap { $0 })
            clearTyping(e.str("from").map { "dm:\($0)" }, e.str("name"))
        case "groups": Task { await refreshGroups() }
        case "servers": Task { await refreshServers() }
        case "channel":
            let server = e.str("server_id")
            Task { if server == nil { await refreshGroups() } else { await refreshServers() } }
            if !e.bool("edited"), !e.bool("deleted"), !e.bool("reacted"), let name = e.str("name"), e.str("from") != me?.uuid {
                let mentioned = e.bool("mentioned")
                notify(title: name, body: mentioned ? "Mentioned you" : server != nil ? "New message in a server" : "New message in a group",
                       important: mentioned || server == nil, mute: [e.str("channel_id"), server].compactMap { $0 })
            }
            clearTyping(e.str("channel_id").map { "ch:\($0)" }, e.str("name"))
        case "typing":
            guard let name = e.str("name"),
                  let key = e.str("channel_id").map({ "ch:\($0)" }) ?? e.str("from").map({ "dm:\($0)" }) else { return }
            typing[key, default: []].insert(name)
            Task {
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                typing[key]?.remove(name)
            }
        case "voice": Task { await voice.refresh() }
        case "voice_signal": Task { await voice.onSignal(e) }
        case "call": calls.onSignal(e)
        default: break
        }
    }

    private func clearTyping(_ key: String?, _ name: String?) {
        guard let key, let name else { return }
        typing[key]?.remove(name)
    }

    /// A notification while the app is in the background, following your settings like the website:
    /// Do Not Disturb, "only mentions", muted chats, sounds.
    func notify(title: String, body: String, important: Bool, mute: [String] = [], call: Bool = false) {
        guard UIApplication.shared.applicationState != .active || call else { return }
        let s = me?.prefs ?? Settings()
        if !call {
            if me?.status == "dnd" || s.notify == "none" { return }
            if s.notify == "mentions" && !important { return }
            if !important && mute.contains(where: { (s.muted ?? []).contains($0) }) { return }
            if s.desktop_notifications == false { return }
        }
        guard UIApplication.shared.applicationState != .active else { return }
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        if s.sounds != false || call { c.sound = .default }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }

    /// Run something that changes data: errors show as a toast. -> did it work
    @discardableResult
    func act(_ work: (Api) async throws -> Void) async -> Bool {
        do { try await work(api); return true }
        catch let e as ApiError where e.status == 401 { session.expired(); return false }
        catch { show(error.localizedDescription); return false }
    }

    /// Accept, decline, cancel or remove (shows errors like the rest), then reload the friends list.
    func friendAction(_ work: @escaping (Api) async throws -> Void) {
        Task { if await act(work) { await refreshFriends() } }
    }

    /// Change your profile, status or settings (PATCH /me) and show it right away.
    func updateMe(_ fields: [String: Any], saved: String? = nil) {
        Task { await act { self.me = try await $0.updateMe(fields); if let s = saved { self.show(s) } } }
    }

    /// Settings that follow you (only the ones in `patch` change).
    func saveSettings(_ patch: [String: Any]) {
        if var m = me, var o = (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(m.prefs))) as? [String: Any] {
            for (k, v) in patch { o[k] = v is NSNull ? nil : v }
            if let d = try? JSONSerialization.data(withJSONObject: o), let s = try? JSONDecoder().decode(Settings.self, from: d) { m.settings = s; me = m }
        }
        updateMe(["settings": patch])
    }

    /// Open a link, first saying where it really goes (Settings -> Links & privacy).
    func open(_ url: URL) { pendingLink = url }

    // --- updates (the phone app's own version)
    var autoUpdate: Bool {
        get { UserDefaults.standard.object(forKey: "jace.autoUpdate") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "jace.autoUpdate") }
    }

    /// -> a message for "Check for updates" (nil when there's a new version to offer)
    func checkUpdate(auto: Bool = false) async -> String? {
        if auto && !autoUpdate { return nil }
        guard let latest = try? await api.latestRelease() else { return "Couldn't check for updates - try again later" }
        let current = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        guard Self.newer(latest.version, than: current) else { update = nil; return "You're up to date" }
        update = latest
        if !auto || UserDefaults.standard.string(forKey: "jace.askedAbout") != latest.version {
            UserDefaults.standard.set(latest.version, forKey: "jace.askedAbout")
            askUpdate = latest
        }
        return nil
    }

    static func newer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }, y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<3 where (x.count > i ? x[i] : 0) != (y.count > i ? y[i] : 0) { return (x.count > i ? x[i] : 0) > (y.count > i ? y[i] : 0) }
        return false
    }

    private func guarded(_ work: @escaping () async throws -> Void) async {
        do {
            try await work()
            error = nil
        } catch let e as ApiError where e.status == 401 {
            session.expired()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
