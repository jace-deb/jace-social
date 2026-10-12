import SwiftUI

/// Signed in: tabs for chats, servers and you; chats open on top.
struct MainView: View {
    @EnvironmentObject var session: Session
    @StateObject private var store: Store
    @State private var chatPath = NavigationPath()
    @State private var serverPath = NavigationPath()
    @State private var tab = 0

    init(session: Session) {
        _store = StateObject(wrappedValue: Store(session: session))
    }

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack(path: $chatPath) {
                ChatsView()
                    .navigationDestination(for: ChatRoute.self) { r in ChatView(target: r.target, title: r.title) }
            }
            .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }
            .tag(0)
            NavigationStack(path: $serverPath) {
                ServersView()
                    .navigationDestination(for: ServerRoute.self) { r in ServerView(id: r.id, title: r.name) }
                    .navigationDestination(for: ChatRoute.self) { r in ChatView(target: r.target, title: r.title) }
            }
            .tabItem { Label("Servers", systemImage: "server.rack") }
            .tag(1)
            NavigationStack { YouView() }
                .tabItem { Label("You", systemImage: "person.crop.circle") }
                .tag(2)
        }
        .environmentObject(store)
        .task {
            await store.start()
            // test builds: -jaceOpen dm:<uuid>:<title>, ch:<id>:<title> or server:<id>:<name> opens that (CI screenshots)
            #if DEBUG
            if let open = UserDefaults.standard.string(forKey: "jaceOpen") {
                let parts = open.split(separator: ":", maxSplits: 2).map(String.init)
                if parts.count == 3 {
                    if parts[0] == "server" { tab = 1; serverPath.append(ServerRoute(id: parts[1], name: parts[2])) }
                    else { chatPath.append(ChatRoute(target: parts[0] == "dm" ? .direct(parts[1]) : .channel(parts[1]), title: parts[2])) }
                }
            }
            #endif
        }
        .onDisappear { store.stop() }
    }
}

struct ChatRoute: Hashable {
    let target: ChatTarget
    let title: String
}

/// Your conversations: friends (direct messages) and group chats, unread first.
struct ChatsView: View {
    @EnvironmentObject var store: Store

    var body: some View {
        List {
            if let e = store.error { Text(e).foregroundStyle(JaceColor.red) }
            if let f = store.friends {
                if !f.incoming.isEmpty {
                    Section("Friend requests — \(f.incoming.count)") {
                        ForEach(f.incoming) { PersonRow(person: $0, line: "Wants to be friends") }
                    }
                }
                if !store.groups.isEmpty {
                    Section("Group chats") {
                        ForEach(store.groups) { g in
                            NavigationLink(value: ChatRoute(target: .channel(g.id), title: g.name)) { GroupRow(group: g) }
                                .listRowBackground(JaceColor.bg)
                        }
                    }
                }
                Section("Direct messages") {
                    ForEach(f.friends.sorted(by: dmOrder)) { p in
                        NavigationLink(value: ChatRoute(target: .direct(p.uuid), title: p.shownName)) {
                            PersonRow(person: p, line: p.online ? describe(p) : "Offline")
                        }
                        .listRowBackground(JaceColor.bg)
                    }
                }
            } else {
                Text("Loading…").foregroundStyle(JaceColor.muted)
            }
        }
        .scrollContentBackground(.hidden)
        .background(JaceColor.bg)
        .navigationTitle("Chats")
        .refreshable { await store.refreshAll() }
    }

    func dmOrder(_ a: Person, _ b: Person) -> Bool {
        if (a.unread ?? 0) != (b.unread ?? 0) { return (a.unread ?? 0) > (b.unread ?? 0) }
        if a.online != b.online { return a.online }
        return a.shownName.lowercased() < b.shownName.lowercased()
    }
}

struct GroupRow: View {
    let group: Group
    var body: some View {
        HStack(spacing: 12) {
            ZStack { Circle().fill(JaceColor.panel); Text(group.name.prefix(1).uppercased()).bold() }.frame(width: 44, height: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(group.name).fontWeight(.semibold).lineLimit(1)
                Text("\(group.members.count) members").font(.footnote).foregroundStyle(JaceColor.muted)
            }
            Spacer()
            if group.unread > 0 { UnreadBadge(n: group.unread) }
        }
    }
}

struct UnreadBadge: View {
    let n: Int
    var body: some View {
        Text("\(n)").font(.caption2.bold()).padding(.horizontal, 7).padding(.vertical, 2).background(JaceColor.red, in: Capsule())
    }
}

/// Your servers. (Opening one, with its channels, comes in the next part.)
struct ServersView: View {
    @EnvironmentObject var store: Store
    var body: some View {
        List {
            if store.servers.isEmpty { Text("You're not in any servers yet").foregroundStyle(JaceColor.muted) }
            ForEach(store.servers) { s in
                NavigationLink(value: ServerRoute(id: s.id, name: s.name)) { HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 16).fill(JaceColor.panel)
                        Text(s.name.split(separator: " ").compactMap { $0.first }.prefix(2).map(String.init).joined()).bold()
                    }
                    .frame(width: 48, height: 48)
                    Text(s.name).fontWeight(.semibold)
                    Spacer()
                    if s.unread > 0 { UnreadBadge(n: s.unread) }
                } }
                .listRowBackground(JaceColor.bg)
            }
        }
        .scrollContentBackground(.hidden)
        .background(JaceColor.bg)
        .navigationTitle("Servers")
    }
}

/// You: your profile and status. (Settings and editing come later.)
struct YouView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var session: Session
    var body: some View {
        VStack(spacing: 10) {
            if let m = store.me {
                Avatar(person: Person(uuid: m.uuid, name: m.name, online: true, status: m.status, avatar_url: m.avatar_url), size: 88)
                Text(m.display_name ?? m.name).font(.title2.bold())
            }
            Button("Sign out") { Task { store.stop(); await session.signOut() } }.buttonStyle(.bordered)
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .background(JaceColor.bg)
        .navigationTitle("You")
    }
}
