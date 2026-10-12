import SwiftUI

/// Channels in the order the website shows them: ones outside a category first, then each category with its own.
struct ChannelGroup: Identifiable {
    let category: Channel?
    let channels: [Channel]
    var id: String { category?.id ?? "" }
}

func channelLayout(_ channels: [Channel]) -> [ChannelGroup] {
    let sorted = channels.sorted { $0.position < $1.position }
    let categories = sorted.filter { $0.kind == "category" }
    let loose = sorted.filter { c in c.kind != "category" && (c.parent_id == nil || !categories.contains { $0.id == c.parent_id }) }
    var out: [ChannelGroup] = []
    if !loose.isEmpty { out.append(ChannelGroup(category: nil, channels: loose)) }
    for c in categories { out.append(ChannelGroup(category: c, channels: sorted.filter { $0.parent_id == c.id && $0.kind != "category" })) }
    return out
}

func channelIcon(_ kind: String) -> String {
    switch kind { case "voice": return "speaker.wave.2.fill"; case "announcement": return "megaphone.fill"; default: return "number" }
}

struct ServerRoute: Hashable {
    let id: String
    let name: String
}

/// A server: its channels by category, who's in voice, and its members.
struct ServerView: View {
    @EnvironmentObject var store: Store
    let id: String
    let title: String
    @State private var detail: ServerDetail?
    @State private var error: String?

    var body: some View {
        List {
            if let e = error { Text(e).foregroundStyle(JaceColor.red) }
            if let d = detail {
                if let about = d.server.description, !about.isEmpty {
                    Text(about).foregroundStyle(JaceColor.muted).listRowBackground(JaceColor.bg)
                }
                ForEach(channelLayout(d.channels)) { group in
                    Section(group.category?.name ?? "") {
                        ForEach(group.channels) { c in channelRow(c, d).listRowBackground(JaceColor.bg) }
                    }
                }
                // DM unread counts don't belong in a member list
                let members = d.members.map { p -> Person in var p = p; p.unread = nil; return p }.sorted { ($0.online ? 0 : 1, $0.shownName.lowercased()) < ($1.online ? 0 : 1, $1.shownName.lowercased()) }
                Section("Members — \(members.count)") {
                    ForEach(members) { p in PersonRow(person: p, line: p.online ? describe(p) : "Offline").listRowBackground(JaceColor.bg) }
                }
            } else if error == nil {
                Text("Loading…").foregroundStyle(JaceColor.muted)
            }
        }
        .scrollContentBackground(.hidden)
        .background(JaceColor.bg)
        .navigationTitle(detail?.server.name ?? title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: id) { await load() }
        .refreshable { await load() }
        .onReceive(store.live.events) { e in
            if ["servers", "voice"].contains(e.event) || (e.event == "channel" && e.str("server_id") == id) { Task { await load() } }
        }
    }

    @ViewBuilder
    func channelRow(_ c: Channel, _ d: ServerDetail) -> some View {
        let unread = c.unread ?? 0
        let inVoice = (d.voice ?? []).filter { $0.channel_id == c.id }.compactMap { v in d.members.first { $0.uuid == v.uuid } }
        let row = VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Image(systemName: channelIcon(c.kind)).foregroundStyle(JaceColor.muted).frame(width: 20)
                VStack(alignment: .leading, spacing: 1) {
                    Text(c.name).fontWeight(unread > 0 ? .bold : .regular)
                        .foregroundStyle(unread > 0 || c.kind == "voice" ? JaceColor.text : JaceColor.muted)
                    if let t = c.topic, !t.isEmpty { Text(t).font(.caption).foregroundStyle(JaceColor.muted).lineLimit(1) }
                }
                Spacer()
                if unread > 0 { UnreadBadge(n: unread) }
            }
            ForEach(inVoice) { p in
                HStack(spacing: 8) {
                    Avatar(person: p, size: 24)
                    Text(p.shownName).font(.subheadline)
                }
                .padding(.leading, 30)
            }
        }
        if c.kind == "voice" { row }
        else { NavigationLink(value: ChatRoute(target: .channel(c.id), title: "#\(c.name)")) { row } }
    }

    func load() async {
        do { detail = try await store.session.api.server(id); error = nil }
        catch let e as ApiError where e.status == 401 { store.session.expired() }
        catch { self.error = error.localizedDescription }
    }
}
