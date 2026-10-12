import SwiftUI

/// Channels in the order the website shows them: ones outside a category first, then each category with its own.
struct ChannelGroup: Identifiable {
    let category: Channel?
    let channels: [Channel]
    var id: String { category?.id ?? "" }
}

func channelLayout(_ channels: [Channel]) -> [ChannelGroup] {
    let sorted = channels.sorted { $0.order < $1.order }
    let categories = sorted.filter { $0.type == "category" }
    let loose = sorted.filter { c in c.type != "category" && (c.parent_id == nil || !categories.contains { $0.id == c.parent_id }) }
    var out: [ChannelGroup] = []
    if !loose.isEmpty { out.append(ChannelGroup(category: nil, channels: loose)) }
    for c in categories { out.append(ChannelGroup(category: c, channels: sorted.filter { $0.parent_id == c.id && $0.type != "category" })) }
    return out
}

func channelIcon(_ kind: String) -> String {
    switch kind { case "voice": return "speaker.wave.2.fill"; case "announcement": return "megaphone.fill"; default: return "number" }
}

/// A server: its channels by category, who's in voice, and its members; settings, invites and more in the menu.
struct ServerView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    let id: String
    let title: String
    @State private var detail: ServerDetail?
    @State private var error: String?
    @State private var newChannel: String?
    @State private var name = ""
    @State private var leaving = false
    @State private var onboarding = false

    var body: some View {
        let muted = store.me?.prefs.muted ?? []
        List {
            if let e = error { Text(e).foregroundStyle(JaceColor.red) }
            if let d = detail {
                if let about = d.server.description, !about.isEmpty {
                    Text(about).foregroundStyle(JaceColor.muted).listRowBackground(JaceColor.bg)
                }
                ForEach(channelLayout(d.channels)) { group in
                    Section {
                        ForEach(group.channels) { c in channelRow(c, d, muted: muted).listRowBackground(JaceColor.bg) }
                    } header: {
                        if let cat = group.category {
                            Text(cat.name).contextMenu {
                                if P.has(d.can, P.MANAGE_CHANNELS) { Button("Category settings") { router.go(.channelSettings(id, cat.id)) } }
                            }
                        }
                    }
                }
                let members = d.members.map { p -> Person in var p = p; p.unread = nil; return p }
                    .sorted { ($0.online ? 0 : 1, $0.shownName.lowercased()) < ($1.online ? 0 : 1, $1.shownName.lowercased()) }
                Section("Members — \(members.count)") {
                    ForEach(members) { p in
                        NavigationLink(value: Route.profile(p.uuid, id)) { PersonRow(person: p, line: p.online ? describe(p) : "Offline") }
                            .listRowBackground(JaceColor.bg)
                    }
                }
            } else if error == nil {
                Text("Loading…").foregroundStyle(JaceColor.muted)
            }
        }
        .jaceList()
        .navigationTitle(detail?.server.name ?? title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let d = detail {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        if let code = d.server.vanity ?? d.server.invite_code {
                            ShareLink(item: URL(string: inviteBase + code)!, message: Text("Join \(d.server.name) on Jace Social")) { Label("Invite people", systemImage: "person.badge.plus") }
                        }
                        if P.has(d.can, P.MANAGE_CHANNELS) {
                            Button { name = ""; newChannel = "text" } label: { Label("Create text channel", systemImage: "number") }
                            Button { name = ""; newChannel = "voice" } label: { Label("Create voice channel", systemImage: "speaker.wave.2") }
                            Button { name = ""; newChannel = "announcement" } label: { Label("Create announcement channel", systemImage: "megaphone") }
                            Button { name = ""; newChannel = "category" } label: { Label("Create category", systemImage: "folder") }
                        }
                        Button { router.go(.serverSettings(id)) } label: { Label("Server settings", systemImage: "gear") }
                        Button { toggleMute(id) } label: { Label(muted.contains(id) ? "Unmute server" : "Mute server", systemImage: "bell.slash") }
                        if store.me?.prefs.developer == true { Button { UIPasteboard.general.string = id } label: { Label("Copy server id", systemImage: "doc.on.doc") } }
                        if !d.isOwner { Button(role: .destructive) { leaving = true } label: { Label("Leave server", systemImage: "rectangle.portrait.and.arrow.right") } }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
        }
        .task(id: id) { await load() }
        .refreshable { await load() }
        .onReceive(store.live.events) { e in
            if e.event == "servers" && e.str("server_id") == id && ["deleted", "removed"].contains(e.str("kind") ?? "") { router.back(); return }
            if ["servers", "voice"].contains(e.event) || (e.event == "channel" && e.str("server_id") == id) { Task { await load() } }
        }
        .alert("New \(newChannel == "category" ? "category" : "channel")", isPresented: Binding(get: { newChannel != nil }, set: { if !$0 { newChannel = nil } })) {
            TextField("Name", text: $name)
            Button("Create") {
                let kind = newChannel ?? "text"
                Task { if await store.act({ try await $0.createChannel(id, name, kind: kind) }) { await load() } }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Leave \(detail?.server.name ?? "")?", isPresented: $leaving, titleVisibility: .visible) {
            Button("Leave", role: .destructive) {
                Task { if await store.act({ try await $0.removeMember(id, store.me?.uuid ?? "") }) { await store.refreshServers(); router.back() } }
            }
        }
        .sheet(isPresented: $onboarding) {
            if let d = detail { NavigationStack { ServerOnboardingView(d: d) { onboarding = false; Task { await load() } } }.environmentObject(store).environmentObject(router) }
        }
    }

    @ViewBuilder
    func channelRow(_ c: Channel, _ d: ServerDetail, muted: [String]) -> some View {
        let unread = c.unread ?? 0
        let here = store.voice.state.channelId == c.id
        let inVoice = (d.voice ?? []).filter { $0.channel_id == c.id }.compactMap { v in d.members.first { $0.uuid == v.uuid } }
        let row = VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Image(systemName: channelIcon(c.type)).foregroundStyle(JaceColor.muted).frame(width: 20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(c.name).fontWeight(unread > 0 || here ? .bold : .regular)
                        .foregroundStyle(here ? JaceColor.green : unread > 0 || c.type == "voice" ? JaceColor.text : JaceColor.muted)
                    if let t = c.topic, !t.isEmpty { Text(t).font(.caption).foregroundStyle(JaceColor.muted).lineLimit(1) }
                }
                Spacer()
                if muted.contains(c.id) { Image(systemName: "bell.slash").foregroundStyle(JaceColor.muted) }
                if unread > 0 { UnreadBadge(n: unread) }
            }
            ForEach(inVoice) { p in
                HStack(spacing: 8) { Avatar(person: p, size: 24); Text(p.shownName).font(.subheadline) }.padding(.leading, 30)
            }
        }
        SwiftUI.Group {
            if c.type == "voice" {
                Button {
                    if !here { Task { await store.voice.join(me: store.me?.uuid ?? "", channel: c.id, server: id, name: c.name) } }
                    router.go(.voice)
                } label: { row }
            } else {
                NavigationLink(value: Route.chat(.channel(c.id), "#\(c.name)")) { row }
            }
        }
        .contextMenu {
            Button { toggleMute(c.id) } label: { Label(muted.contains(c.id) ? "Unmute channel" : "Mute channel", systemImage: "bell.slash") }
            if P.has(d.can, P.MANAGE_CHANNELS) { Button { router.go(.channelSettings(id, c.id)) } label: { Label("Channel settings", systemImage: "gear") } }
            if store.me?.prefs.developer == true { Button { UIPasteboard.general.string = c.id } label: { Label("Copy channel id", systemImage: "doc.on.doc") } }
        }
    }

    func toggleMute(_ key: String) {
        let muted = store.me?.prefs.muted ?? []
        store.saveSettings(["muted": muted.contains(key) ? muted.filter { $0 != key } : muted + [key]])
    }

    func load() async {
        do {
            let d = try await store.api.server(id)
            detail = d
            error = nil
            if d.onboarded == false { onboarding = true }
        } catch let e as ApiError where e.status == 401 { store.session.expired() }
        catch { self.error = error.localizedDescription }
    }
}
