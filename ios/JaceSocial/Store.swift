import Combine
import Foundation

/// What the app shows everywhere: you, your friends, group chats and servers. Loaded from the
/// API and kept current by live events.
@MainActor
final class Store: ObservableObject {
    let session: Session
    let live = Live()
    @Published var me: Me?
    @Published var friends: FriendsData?
    @Published var groups: [Group] = []
    @Published var servers: [ServerItem] = []
    @Published var error: String?
    /// who's typing where: chat key -> names
    @Published var typing: [String: Set<String>] = [:]
    private var bag = Set<AnyCancellable>()

    init(session: Session) {
        self.session = session
        live.events.sink { [weak self] e in self?.handle(e) }.store(in: &bag)
    }

    func start() async {
        await guarded { self.me = try await self.session.api.me() }
        if let m = me { live.start(m.realtime, inbox: m.inbox) }
        await refreshAll()
    }

    func stop() { live.stop() }

    func refreshAll() async {
        async let a: () = refreshFriends()
        async let b: () = refreshGroups()
        async let c: () = refreshServers()
        _ = await (a, b, c)
    }
    func refreshFriends() async { await guarded { self.friends = try await self.session.api.friends() } }
    func refreshGroups() async { await guarded { self.groups = try await self.session.api.groups() } }
    func refreshServers() async { await guarded { self.servers = try await self.session.api.servers() } }

    func person(_ uuid: String?) -> Person? {
        guard let uuid else { return nil }
        if let p = friends?.friends.first(where: { $0.uuid == uuid }) { return p }
        for g in groups { if let p = g.members.first(where: { $0.uuid == uuid }) { return p } }
        if let m = me, m.uuid == uuid {
            return Person(uuid: m.uuid, name: m.name, online: true, status: m.status, avatar_url: m.avatar_url, display_name: m.display_name)
        }
        return nil
    }

    private func handle(_ e: LiveEvent) {
        switch e.event {
        case "friends", "presence", "message", "dm_update": Task { await refreshFriends() }
        case "groups": Task { await refreshGroups() }
        case "servers": Task { await refreshServers() }
        case "channel": Task { if e.str("server_id") == nil { await refreshGroups() } else { await refreshServers() } }
        case "typing":
            guard let name = e.str("name"),
                  let key = e.str("channel_id").map({ "ch:\($0)" }) ?? e.str("from").map({ "dm:\($0)" }) else { return }
            typing[key, default: []].insert(name)
            Task {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                typing[key]?.remove(name)
            }
        default: break
        }
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
