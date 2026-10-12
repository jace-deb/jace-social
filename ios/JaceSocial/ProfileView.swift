import SwiftUI

/// Someone's profile; opened from a server, also their roles and what you can do to them there.
struct ProfileView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    let uuid: String
    let serverId: String?
    @State private var p: FullProfile?
    @State private var detail: ServerDetail?
    @State private var removing = false

    var body: some View {
        List {
            if let p {
                let member = detail?.members.first { $0.uuid == uuid }
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        ZStack(alignment: .bottomLeading) {
                            AsyncImage(url: p.banner_url.flatMap(URL.init(string:))) { $0.resizable().scaledToFill() } placeholder: { hex(p.accent_color, hex("#2a4a37")) }
                                .frame(height: 110).frame(maxWidth: .infinity).clipped()
                            Avatar(person: p.asPerson, size: 84).offset(x: 14, y: 40)
                        }
                        .padding(.bottom, 40)
                        HStack {
                            Text(member?.nickname.flatMap { $0.isEmpty ? nil : $0 } ?? (p.display_name?.isEmpty == false ? p.display_name! : p.name)).font(.title2.bold())
                            if p.is_bot == true { BotTag() }
                        }
                        .padding(.horizontal, 14)
                        Text([p.jace_name.map { "@\($0)" }, p.minecraft_name.map { "⛏ \($0)" }, p.pronouns].compactMap { $0 }.joined(separator: " · "))
                            .font(.footnote).foregroundStyle(JaceColor.muted).padding(.horizontal, 14)
                        Text(statusLabel(p.status ?? "offline") + (p.custom_status.map { " · \(p.status_emoji ?? "") \($0)" } ?? "")
                             + (p.online != true && p.last_seen != nil ? " · last seen \(since(p.last_seen)) ago" : ""))
                            .font(.subheadline).padding(.horizontal, 14).padding(.bottom, 8)
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(JaceColor.bg)
                }
                if p.online == true, let a = p.activity, a.type != "app" { Section { ActivityCard(a: a) }.rowBg() }
                if let bio = p.bio, !bio.isEmpty { Section("About me") { Text(bio) }.rowBg() }
                if let links = p.links, !links.isEmpty {
                    Section("Links") {
                        ForEach(links, id: \.url) { l in
                            Button("🔗 \(l.label.isEmpty ? l.url : l.label)") { if let u = URL(string: l.url) { store.open(u) } }
                        }
                    }
                    .rowBg()
                }
                if let d = detail, let m = member, let me = store.me {
                    let roles = d.allRoles.filter { (m.roles ?? []).contains($0.id) }.sorted { $0.position > $1.position }
                    if !roles.isEmpty {
                        Section("Roles") { ForEach(roles) { r in HStack { Circle().fill(hex(r.color, JaceColor.gray)).frame(width: 10, height: 10); Text(r.name) } } }.rowBg()
                    }
                    Section { MemberActions(d: d, member: m, me: me) { Task { await load() } } }.rowBg()
                }
                if let ms = p.mutual_servers, !ms.isEmpty {
                    Section { Text("Servers you share: \(ms.compactMap { $0.name }.joined(separator: ", "))").font(.footnote).foregroundStyle(JaceColor.muted) }.rowBg()
                }
                Section {
                    if p.is_bot == true && (p.bot_owner == store.me?.uuid || (detail.map { P.has($0.can, P.MANAGE_SERVER) } ?? false)) {
                        Button("📜 See everything it does") { router.go(.botActivity(p.uuid, p.bot_owner == store.me?.uuid ? nil : detail?.server.id)) }
                    }
                    if p.is_you != true && p.is_bot != true {
                        if p.is_friend == true {
                            Button("Message") { router.go(.chat(.direct(uuid), p.display_name ?? p.name)) }
                            Button("📞 Call") { store.calls.call(uuid, name: p.display_name ?? p.name) }
                            Button("Remove friend", role: .destructive) { removing = true }
                        } else {
                            Button("Add friend") { Task { if await store.act({ try await $0.addFriend(uuid: uuid) }) { store.show("Friend request sent"); await store.refreshFriends(); await load() } } }
                        }
                    }
                }
                .rowBg()
            } else {
                Text("Loading…").foregroundStyle(JaceColor.muted)
            }
        }
        .jaceList()
        .navigationTitle(p?.name ?? "Profile")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: uuid) { await load() }
        .confirmationDialog("Remove \(p?.name ?? "") from your friends?", isPresented: $removing, titleVisibility: .visible) {
            Button("Remove", role: .destructive) { Task { if await store.act({ try await $0.removeFriend(uuid) }) { await store.refreshFriends(); await load() } } }
        }
    }

    func load() async {
        await store.act { p = try await $0.profile(uuid) }
        if let s = serverId { await store.act { detail = try await $0.server(s) } }
    }
}

/// Rich presence: what they're playing (like the website's activity card).
struct ActivityCard: View {
    let a: Activity
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(a.type == "launcher" ? "JACE LAUNCHER" : "PLAYING MINECRAFT").font(.caption.bold()).foregroundStyle(JaceColor.muted)
            Text(a.type == "launcher" ? "Jace Launcher" : ["Minecraft", a.version, a.loader.flatMap { $0 == "vanilla" ? nil : "(\($0))" }].compactMap { $0 }.joined(separator: " ")).bold()
            ForEach(lines, id: \.self) { Text($0).font(.footnote).foregroundStyle(JaceColor.muted) }
        }
    }
    var lines: [String] {
        var l: [String] = []
        switch a.type {
        case "hosting": l.append(a.world.map { "Hosting \"\($0)\"" } ?? "Hosting a world")
        case "playing":
            if let s = a.server { l.append("On \(s)") } else if let w = a.world { l.append("In \"\(w)\"") }
            if let m = a.modpack { l.append(m) }
        case "launcher": l.append("In Jace Launcher")
        default: break
        }
        if let s = a.started_at { l.append("for \(since(s))") }
        return l
    }
}

/// Roles, nickname, timeout, kick, ban and handing the server over, for one member.
struct MemberActions: View {
    @EnvironmentObject var store: Store
    let d: ServerDetail
    let member: Person
    let me: Me
    let changed: () -> Void
    @State private var dialog: String?
    @State private var text = ""

    var sid: String { d.server.id }
    var isOwner: Bool { member.uuid == d.server.owner }
    var above: Bool {
        let theirTop = isOwner ? 1e9 : Double(d.allRoles.filter { (member.roles ?? []).contains($0.id) }.map { $0.position }.max() ?? 0)
        return d.isOwner || (member.uuid != me.uuid && theirTop < d.topRole)
    }
    var timedOut: Bool { member.timeout_until.flatMap(parseTime).map { $0 > Date() } ?? false }

    var body: some View {
        let assignable = d.allRoles.filter { !$0.everyone && (d.isOwner || Double($0.position) < d.topRole) }.sorted { $0.position > $1.position }
        let mine = member.roles ?? []
        VStack(alignment: .leading, spacing: 12) {
        if P.has(d.can, P.MANAGE_ROLES) && (above || member.uuid == me.uuid) && !assignable.isEmpty {
            Text("Roles (tap to give or take)").font(.caption).foregroundStyle(JaceColor.muted)
            ForEach(assignable) { r in
                Button {
                    let roles = mine.contains(r.id) ? mine.filter { $0 != r.id } : mine + [r.id]
                    run { try await $0.updateMember(sid, ["uuid": member.uuid, "roles": roles]) }
                } label: {
                    HStack { Circle().fill(hex(r.color, JaceColor.gray)).frame(width: 10, height: 10); Text(r.name).foregroundStyle(JaceColor.text); Spacer(); Image(systemName: mine.contains(r.id) ? "checkmark" : "plus") }
                }
            }
        }
        if member.uuid == me.uuid ? P.has(d.can, P.CHANGE_NICK) : (P.has(d.can, P.MANAGE_NICKS) && above) {
            Button("Nickname") { text = member.nickname ?? ""; dialog = "nick" }
        }
        if member.uuid != me.uuid && above && !isOwner {
            if P.has(d.can, P.TIMEOUT) {
                if timedOut { Button("End timeout") { run { try await $0.updateMember(sid, ["uuid": member.uuid, "timeout_until": NSNull()]) } } }
                else {
                    Menu("Time out…") {
                        ForEach([(1, "1 minute"), (5, "5 minutes"), (10, "10 minutes"), (60, "1 hour"), (1440, "1 day"), (10080, "1 week")], id: \.0) { m, label in
                            Button(label) {
                                let until = ISO8601DateFormatter().string(from: Date().addingTimeInterval(Double(m) * 60))
                                run { try await $0.updateMember(sid, ["uuid": member.uuid, "timeout_until": until]) }
                            }
                        }
                    }
                }
            }
            if P.has(d.can, P.KICK) { Button("Kick", role: .destructive) { dialog = "kick" } }
            if P.has(d.can, P.BAN) { Button("Ban", role: .destructive) { text = ""; dialog = "ban" } }
        }
        if d.isOwner && member.uuid != me.uuid && member.is_bot != true { Button("Make owner") { dialog = "owner" } }
        if timedOut, let t = member.timeout_until { Text("Timed out until \(timeOf(t))").font(.footnote).foregroundStyle(JaceColor.muted) }
        }
        .buttonStyle(.borderless)
            .alert("Nickname in this server", isPresented: binding("nick")) {
                TextField("Empty removes it", text: $text)
                Button("Save") { run { try await $0.updateMember(sid, ["uuid": member.uuid, "nickname": text]) } }
                Button("Cancel", role: .cancel) {}
            }
            .alert("Kick \(member.name)?", isPresented: binding("kick")) {
                Button("Kick", role: .destructive) { run { try await $0.removeMember(sid, member.uuid) } }
                Button("Cancel", role: .cancel) {}
            } message: { Text("They can join again with an invite.") }
            .alert("Ban \(member.name)?", isPresented: binding("ban")) {
                TextField("Reason (optional)", text: $text)
                Button("Ban", role: .destructive) { run { try await $0.ban(sid, member.uuid, reason: text) } }
                Button("Cancel", role: .cancel) {}
            } message: { Text("They won't be able to come back. Their messages from the last 7 days are deleted too.") }
            .alert("Give \(d.server.name) to \(member.name)?", isPresented: binding("owner")) {
                Button("Make owner", role: .destructive) { run { try await $0.updateMember(sid, ["uuid": member.uuid, "role": "owner"]) } }
                Button("Cancel", role: .cancel) {}
            } message: { Text("You'll stop being the owner.") }
    }

    func binding(_ name: String) -> Binding<Bool> { Binding(get: { dialog == name }, set: { if !$0 { dialog = nil } }) }
    func run(_ work: @escaping (Api) async throws -> Void) { Task { if await store.act(work) { changed() } } }
}
