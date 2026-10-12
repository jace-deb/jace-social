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
    @State private var adding = false
    @State private var removing: Person?

    var body: some View {
        List {
            if let e = store.error { Text(e).foregroundStyle(JaceColor.red) }
            if let f = store.friends {
                if !f.incoming.isEmpty {
                    Section("Friend requests — \(f.incoming.count)") {
                        ForEach(f.incoming) { p in
                            HStack {
                                PersonRow(person: p, line: "Wants to be friends")
                                Button { store.friendAction { try await $0.respondFriend(p.uuid, accept: true) } } label: {
                                    Image(systemName: "checkmark.circle.fill").font(.title2).foregroundStyle(JaceColor.green)
                                }
                                .accessibilityLabel("Accept")
                                Button { store.friendAction { try await $0.respondFriend(p.uuid, accept: false) } } label: {
                                    Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(JaceColor.muted)
                                }
                                .accessibilityLabel("Decline")
                            }
                            .buttonStyle(.borderless)
                            .listRowBackground(JaceColor.bg)
                        }
                    }
                }
                if !f.outgoing.isEmpty {
                    Section("Sent requests — \(f.outgoing.count)") {
                        ForEach(f.outgoing) { p in
                            HStack {
                                PersonRow(person: p, line: "Waiting for them to accept")
                                Button("Cancel") { store.friendAction { try await $0.removeFriend(p.uuid) } }
                                    .foregroundStyle(JaceColor.muted)
                            }
                            .buttonStyle(.borderless)
                            .listRowBackground(JaceColor.bg)
                        }
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
                        .contextMenu {
                            Button(role: .destructive) { removing = p } label: { Label("Remove friend", systemImage: "person.badge.minus") }
                        }
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
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { adding = true } label: { Image(systemName: "person.badge.plus") }.accessibilityLabel("Add friend")
            }
        }
        .sheet(isPresented: $adding) { AddFriendView().environmentObject(store) }
        .confirmationDialog("Remove \(removing?.shownName ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible, presenting: removing) { p in
            Button("Remove friend", role: .destructive) { store.friendAction { try await $0.removeFriend(p.uuid) } }
        } message: { _ in Text("You won't be friends any more. Your messages stay.") }
    }

    func dmOrder(_ a: Person, _ b: Person) -> Bool {
        if (a.unread ?? 0) != (b.unread ?? 0) { return (a.unread ?? 0) > (b.unread ?? 0) }
        if a.online != b.online { return a.online }
        return a.shownName.lowercased() < b.shownName.lowercased()
    }
}

/// Send a friend request by Minecraft or Jace name.
struct AddFriendView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var busy = false
    @State private var result: String?
    @State private var failed = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Username", text: $name)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($focused).submitLabel(.send).onSubmit { Task { await send() } }
                        .onChange(of: name) { _ in result = nil }
                } footer: { Text("Their Minecraft username, or @name for a Jace account.") }
                if let r = result { Text(r).foregroundStyle(failed ? JaceColor.red : JaceColor.green) }
            }
            .scrollContentBackground(.hidden)
            .background(JaceColor.bg)
            .navigationTitle("Add a friend")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send") { Task { await send() } }.disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || busy)
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium])
    }

    func send() async {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let status = try await store.session.api.addFriend(n)
            result = status == "accepted" ? "You're now friends with \(n)!" : "Friend request sent to \(n)."
            failed = false
            name = ""
            await store.refreshFriends()
        } catch {
            result = error.localizedDescription
            failed = true
        }
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
