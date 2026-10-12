import SwiftUI

private let slowmodes = [(0, "Off"), (5, "5 seconds"), (10, "10 seconds"), (30, "30 seconds"), (60, "1 minute"), (300, "5 minutes"),
                         (900, "15 minutes"), (3600, "1 hour"), (21600, "6 hours")]

/// Channel settings: name, topic, slowmode, category, user limit (voice), and permissions for roles
/// or members (allow / inherit / deny).
struct ChannelSettingsView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    let serverId: String
    let channelId: String
    @State private var d: ServerDetail?
    @State private var tab = "General"
    @State private var name = ""
    @State private var topic = ""
    @State private var slowmode = 0
    @State private var limit = 0.0
    @State private var parent = ""
    @State private var loaded = false
    @State private var deleting = false

    var body: some View {
        Form {
            if let d, let c = d.channels.first(where: { $0.id == channelId }) {
                if P.has(d.can, P.MANAGE_ROLES) {
                    Picker("", selection: $tab) { Text("General").tag("General"); Text("Permissions").tag("Permissions") }.pickerStyle(.segmented)
                }
                if tab == "General" {
                    Section {
                        TextField("Name", text: $name.max(48))
                        if c.type != "category" && c.type != "voice" {
                            TextField("Topic", text: $topic.max(200), axis: .vertical).lineLimit(1...4)
                            Picker("Slowmode", selection: $slowmode) { ForEach(slowmodes, id: \.0) { Text($0.1).tag($0.0) } }
                        }
                        if c.type == "voice" {
                            Text("User limit: \(Int(limit) == 0 ? "none" : "\(Int(limit))")")
                            Slider(value: $limit, in: 0...8, step: 1)
                        }
                        if c.type != "category" {
                            Picker("Category", selection: $parent) {
                                Text("No category").tag("")
                                ForEach(d.channels.filter { $0.type == "category" }) { Text($0.name).tag($0.id) }
                            }
                        }
                        Button("Save") {
                            var f: [String: Any] = ["name": name, "topic": topic, "slowmode": slowmode, "user_limit": Int(limit)]
                            if c.type != "category" { f["parent_id"] = orNull(parent.isEmpty ? nil : parent) }
                            Task { if await store.act({ try await $0.updateChannel(c.id, f) }) { store.show("Saved"); await load() } }
                        }
                        Button("Delete \(c.type == "category" ? "category" : "channel")", role: .destructive) { deleting = true }
                    }
                    .rowBg()
                    .onAppear {
                        guard !loaded else { return }
                        loaded = true
                        name = c.name; topic = c.topic ?? ""; slowmode = c.slowmode ?? 0; limit = Double(c.user_limit ?? 0); parent = c.parent_id ?? ""
                    }
                } else {
                    ChannelPermissions(d: d, channel: c) { Task { await load() } }
                }
            } else {
                Text("Loading…").foregroundStyle(JaceColor.muted)
            }
        }
        .jaceList()
        .navigationTitle("Channel settings")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .confirmationDialog("Delete it?", isPresented: $deleting, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { Task { if await store.act({ try await $0.deleteChannel(channelId) }) { router.back() } } }
        } message: { Text("A channel's messages are deleted too; a category's channels stay.") }
    }

    func load() async { await store.act { d = try await $0.server(serverId) } }
}

private struct ChannelPermissions: View {
    @EnvironmentObject var store: Store
    let d: ServerDetail
    let channel: Channel
    let reload: () -> Void
    @State private var sel = 0
    @State private var draft: (Int, Int)?

    struct Target: Hashable { let type: String; let id: String; let label: String }

    var body: some View {
        let overrides = (d.overrides ?? []).filter { $0.channel_id == channel.id }
        if let everyone = d.allRoles.first(where: { $0.everyone }) {
            let targets: [Target] = {
                var t = overrides.map { o in
                    o.target_type == "role" ? Target(type: "role", id: o.target_id, label: d.allRoles.first { $0.id == o.target_id }?.name ?? "deleted role")
                        : Target(type: "member", id: o.target_id, label: d.members.first { $0.uuid == o.target_id }?.shownName ?? "someone")
                }
                if !t.contains(where: { $0.type == "role" && $0.id == everyone.id }) { t.insert(Target(type: "role", id: everyone.id, label: "@everyone"), at: 0) }
                return t
            }()
            let target = targets[min(sel, targets.count - 1)]
            let current = overrides.first { $0.target_type == target.type && $0.target_id == target.id }
            let shown = draft ?? (current?.allow ?? 0, current?.deny ?? 0)
            let ev = overrides.first { $0.target_type == "role" && $0.target_id == everyone.id }
            Section {
                ToggleRow(label: "Private", desc: "Only the roles and members below can see it", isOn: (ev?.deny ?? 0) & P.VIEW != 0) { on in
                    let deny = on ? (ev?.deny ?? 0) | P.VIEW : (ev?.deny ?? 0) & ~P.VIEW
                    Task { if await store.act({ try await $0.setOverride(channel.id, "role", everyone.id, allow: (ev?.allow ?? 0) & ~P.VIEW, deny: deny) }) { reload() } }
                }
                Picker("Who", selection: Binding(get: { min(sel, targets.count - 1) }, set: { sel = $0; draft = nil })) {
                    ForEach(targets.indices, id: \.self) { i in Text((targets[i].type == "role" ? "● " : "👤 ") + targets[i].label).tag(i) }
                }
                Menu("＋ Add role or member") {
                    ForEach(d.allRoles.filter { r in !r.everyone && !targets.contains { $0.id == r.id } }) { r in
                        Button(r.name) { add("role", r.id, targets.count) }
                    }
                    ForEach(d.members.filter { m in !targets.contains { $0.id == m.uuid } }) { m in
                        Button("👤 \(m.shownName)") { add("member", m.uuid, targets.count) }
                    }
                }
            }
            .rowBg()
            Section {
                PermissionList(value: 0, editable: d.isOwner ? P.ALL : d.can, only: { i in
                    switch channel.type {
                    case "voice": return [P.VIEW, P.CONNECT, P.SPEAK, P.STREAM, P.MANAGE_CHANNELS, P.MANAGE_ROLES].contains(i.bit)
                    case "category": return true
                    default: return ![P.CONNECT, P.SPEAK, P.STREAM].contains(i.bit)
                    }
                }, tri: (shown.0, shown.1, { a, dn in draft = (a, dn) }))
                Button("Save") {
                    guard let dr = draft else { return }
                    let (a, dn) = dr
                    Task { if await store.act({ try await $0.setOverride(channel.id, target.type, target.id, allow: a, deny: dn) }) { draft = nil; reload() } }
                }
                .disabled(draft == nil)
                if !(target.type == "role" && target.id == everyone.id) && overrides.contains(where: { $0.target_id == target.id }) {
                    Button("Remove", role: .destructive) {
                        Task { if await store.act({ try await $0.removeOverride(channel.id, target.type, target.id) }) { sel = 0; reload() } }
                    }
                }
            }
            .rowBg()
        }
    }

    func add(_ type: String, _ id: String, _ index: Int) {
        Task { if await store.act({ try await $0.setOverride(channel.id, type, id, allow: 0, deny: 0) }) { sel = index; reload() } }
    }
}
