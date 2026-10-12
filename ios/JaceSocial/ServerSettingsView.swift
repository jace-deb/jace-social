import SwiftUI

/// Server settings: overview, roles and permissions, members, invites and the custom link, onboarding,
/// bans and bots (each only when your permissions allow it), and leaving or deleting the server.
struct ServerSettingsView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    let id: String
    @State private var d: ServerDetail?
    @State private var tab = ""
    @State private var deleting = false
    @State private var leaving = false
    @State private var typed = ""

    var body: some View {
        Form {
            if let d {
                let p = d.can
                let tabs = [("Overview", P.has(p, P.MANAGE_SERVER)), ("Roles", P.has(p, P.MANAGE_ROLES)),
                            ("Members", P.has(p, P.KICK) || P.has(p, P.BAN) || P.has(p, P.MANAGE_ROLES) || P.has(p, P.MANAGE_NICKS) || P.has(p, P.TIMEOUT)),
                            ("Invites", P.has(p, P.INVITE) || P.has(p, P.MANAGE_SERVER)), ("Onboarding", P.has(p, P.MANAGE_SERVER)),
                            ("Bans", P.has(p, P.BAN)), ("Bots", P.has(p, P.MANAGE_BOTS))].filter { $0.1 }.map { $0.0 }
                if tabs.isEmpty { Text("You don't have permission to change this server's settings.").foregroundStyle(JaceColor.muted) }
                else {
                    Picker("", selection: $tab) { ForEach(tabs, id: \.self) { Text($0).tag($0) } }
                        .pickerStyle(.menu).onAppear { if !tabs.contains(tab) { tab = tabs[0] } }
                }
                switch tab {
                case "Overview": OverviewTab(d: d, reload: reload)
                case "Roles": RolesTab(d: d, reload: reload)
                case "Members": MembersTab(d: d, reload: reload)
                case "Invites": InvitesTab(d: d, reload: reload)
                case "Onboarding": OnboardingTab(d: d, reload: reload)
                case "Bans": BansTab(d: d)
                case "Bots": BotsTab(d: d, reload: reload)
                default: EmptyView()
                }
                Section {
                    if d.isOwner { Button("Delete server", role: .destructive) { typed = ""; deleting = true } }
                    else { Button("Leave server", role: .destructive) { leaving = true } }
                }
                .rowBg()
            } else {
                Text("Loading…").foregroundStyle(JaceColor.muted)
            }
        }
        .jaceList()
        .navigationTitle(d?.server.name ?? "Server settings")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: id) { await load() }
        .alert("Delete \(d?.server.name ?? "") forever?", isPresented: $deleting) {
            TextField("Type the server name", text: $typed)
            Button("Delete", role: .destructive) {
                guard typed == d?.server.name else { return store.show("The name didn't match") }
                Task { if await store.act({ try await $0.deleteServer(id) }) { await store.refreshServers(); router.open(tab: 1) } }
            }
            Button("Cancel", role: .cancel) {}
        } message: { Text("Type the server name to delete it and everything in it.") }
        .confirmationDialog("Leave \(d?.server.name ?? "")?", isPresented: $leaving, titleVisibility: .visible) {
            Button("Leave", role: .destructive) {
                Task { if await store.act({ try await $0.removeMember(id, store.me?.uuid ?? "") }) { await store.refreshServers(); router.open(tab: 1) } }
            }
        }
    }

    func load() async { await store.act { d = try await $0.server(id) } }
    func reload() { Task { await load() } }
}

private struct OverviewTab: View {
    @EnvironmentObject var store: Store
    let d: ServerDetail
    let reload: () -> Void
    @State private var name = ""
    @State private var desc = ""
    @State private var accent: String?
    @State private var system = ""
    @State private var loaded = false

    var body: some View {
        let s = d.server
        let texts = d.channels.filter { $0.type == "text" || $0.type == "announcement" }
        Section("Overview") {
            ZStack(alignment: .bottomTrailing) {
                AsyncImage(url: s.banner_url.flatMap(URL.init(string:))) { $0.resizable().scaledToFill() } placeholder: { hex(s.accent_color, JaceColor.panel) }
                    .frame(height: 90).frame(maxWidth: .infinity).clipShape(RoundedRectangle(cornerRadius: 10))
                HStack {
                    PhotoButton(onPick: { p in Task { if await store.act({ try await $0.setServerBanner(s.id, p.data, type: p.type) }) { reload() } } }) {
                        Text("Change banner").padding(6).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                    }
                    if s.banner_url != nil {
                        Button("Remove") { Task { if await store.act({ try await $0.removeServerBanner(s.id) }) { reload() } } }
                            .padding(6).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                    }
                }
                .padding(8).buttonStyle(.borderless)
            }
            HStack(spacing: 12) {
                IconPic(name: s.name, url: s.icon_url, size: 56)
                PhotoButton(onPick: { p in Task { if await store.act({ try await $0.setServerIcon(s.id, p.data, type: p.type) }) { reload() } } }) { Text("Change icon") }
                    .buttonStyle(.borderless)
            }
            TextField("Server name", text: $name.max(48))
            TextField("Description", text: $desc.max(300), axis: .vertical).lineLimit(2...5)
            Text("Accent color").font(.footnote).foregroundStyle(JaceColor.muted)
            Swatches(selected: accent, withNone: true) { accent = $0 }
            Picker("Join messages go to", selection: $system) {
                Text("Nowhere").tag("")
                ForEach(texts) { Text("#\($0.name)").tag($0.id) }
            }
            Button("Save") {
                Task {
                    if await store.act({ try await $0.updateServer(s.id, ["name": name, "description": desc, "accent_color": orNull(accent), "system_channel": orNull(system.isEmpty ? nil : system)]) }) {
                        store.show("Saved"); reload()
                    }
                }
            }
        }
        .rowBg()
        .onAppear {
            guard !loaded else { return }
            loaded = true
            name = s.name; desc = s.description ?? ""; accent = s.accent_color; system = s.system_channel ?? ""
        }
    }
}

/// The permission switches (or deny / inherit / allow for channel overrides).
struct PermissionList: View {
    let value: Int
    let editable: Int
    var only: ((P.Info) -> Bool)? = nil
    var tri: (allow: Int, deny: Int, set: (Int, Int) -> Void)? = nil
    var onChange: (Int) -> Void = { _ in }

    var body: some View {
        ForEach(P.groups, id: \.0) { group, items in
            let shown = items.filter { (only?($0) ?? true) && (tri == nil || $0.channel) }
            if !shown.isEmpty {
                Text(group.uppercased()).font(.caption.bold()).foregroundStyle(JaceColor.muted)
                ForEach(shown, id: \.bit) { info in
                    let can = editable & info.bit != 0
                    if let tri {
                        HStack {
                            VStack(alignment: .leading) { Text(info.name).fontWeight(.semibold); if !info.desc.isEmpty { Text(info.desc).font(.caption).foregroundStyle(JaceColor.muted) } }
                            Spacer()
                            let cur = tri.allow & info.bit != 0 ? "allow" : tri.deny & info.bit != 0 ? "deny" : "inherit"
                            ForEach([("deny", "xmark"), ("inherit", "slash.circle"), ("allow", "checkmark")], id: \.0) { v, icon in
                                Button {
                                    var a = tri.allow & ~info.bit, dn = tri.deny & ~info.bit
                                    if v == "allow" { a |= info.bit }
                                    if v == "deny" { dn |= info.bit }
                                    tri.set(a, dn)
                                } label: {
                                    Image(systemName: icon).frame(width: 30, height: 30)
                                        .background(cur == v ? (v == "allow" ? JaceColor.green : v == "deny" ? JaceColor.red : JaceColor.gray) : JaceColor.bg2,
                                                    in: RoundedRectangle(cornerRadius: 7))
                                        .foregroundStyle(cur == v ? Color.white : JaceColor.muted)
                                }
                                .buttonStyle(.borderless).disabled(!can)
                            }
                        }
                    } else {
                        ToggleRow(label: info.name, desc: info.desc, isOn: value & info.bit == info.bit) { on in onChange(on ? value | info.bit : value & ~info.bit) }
                            .disabled(!can)
                    }
                }
            }
        }
    }
}

private struct RolesTab: View {
    @EnvironmentObject var store: Store
    let d: ServerDetail
    let reload: () -> Void
    @State private var selected: String?
    @State private var draft: Role?
    @State private var creating = false
    @State private var deleting = false
    @State private var newName = "new role"

    var roles: [Role] { d.allRoles.sorted { $0.position > $1.position } }
    func canEdit(_ r: Role) -> Bool { d.isOwner || r.everyone || Double(r.position) < d.topRole }

    var body: some View {
        Section {
            Button("＋ Create role") { newName = "new role"; creating = true }
            ForEach(roles) { r in
                Button { selected = r.id; draft = r } label: {
                    HStack {
                        Circle().fill(hex(r.color, JaceColor.gray)).frame(width: 12, height: 12)
                        Text("\(r.icon ?? "") \(r.name)".trimmingCharacters(in: .whitespaces)).foregroundStyle(JaceColor.text)
                        Spacer()
                        Text("\(r.everyone ? d.members.count : d.members.filter { ($0.roles ?? []).contains(r.id) }.count)").foregroundStyle(JaceColor.muted)
                        if r.id == (draft?.id ?? selected) { Image(systemName: "checkmark").foregroundStyle(JaceColor.green) }
                    }
                }
                .swipeActions {
                    if !r.everyone && canEdit(r) {
                        Button("▲") { move(r.id, -1) }
                        Button("▼") { move(r.id, 1) }
                    }
                }
            }
        } header: { Text("Roles") } footer: { Text("People get every permission from all their roles. Higher roles can manage the ones below them. Swipe a role to move it.") }
        .rowBg()
        .onAppear { if draft == nil, let r = roles.first(where: { !$0.everyone }) ?? roles.first { draft = r; selected = r.id } }
        if let r = draft {
            Section(r.everyone ? "@everyone" : "Edit \(r.name)") {
                if !canEdit(r) { Text("This role is the same as or above your highest role, so you can't change it.").foregroundStyle(JaceColor.muted) }
                if !r.everyone {
                    TextField("Role name", text: Binding(get: { draft?.name ?? "" }, set: { draft?.name = String($0.prefix(32)) }))
                    Swatches(selected: draft?.color, withNone: true) { draft?.color = $0 }
                    TextField("Icon (an emoji)", text: Binding(get: { draft?.icon ?? "" }, set: { draft?.icon = $0.isEmpty ? nil : String($0.prefix(16)) }))
                    ToggleRow(label: "Show members with this role separately", isOn: draft?.hoist == true) { draft?.hoist = $0 }
                    ToggleRow(label: "Anyone can @mention this role", isOn: draft?.mentionable == true) { draft?.mentionable = $0 }
                }
                PermissionList(value: draft?.perms ?? 0, editable: canEdit(r) ? (d.isOwner ? P.ALL : d.can) : 0) { draft?.permissions = $0 }
                Button("Save role") {
                    guard let x = draft else { return }
                    Task {
                        if await store.act({ try await $0.updateRole(d.server.id, x.id, ["name": x.name, "color": orNull(x.color), "icon": orNull(x.icon),
                                                                              "hoist": x.hoist == true, "mentionable": x.mentionable == true, "permissions": x.perms]) }) {
                            store.show("Saved"); reload()
                        }
                    }
                }
                .disabled(!canEdit(r))
                if !r.everyone && canEdit(r) { Button("Delete role", role: .destructive) { deleting = true } }
            }
            .rowBg()
        }
        EmptyView()
            .alert("New role", isPresented: $creating) {
                TextField("Role name", text: $newName)
                Button("Create") {
                    Task {
                        await store.act { let rid = try await $0.createRole(d.server.id, newName, color: ACCENTS[roles.count % ACCENTS.count]); selected = rid; draft = nil }
                        reload()
                    }
                }
                Button("Cancel", role: .cancel) {}
            }
            .confirmationDialog("Delete the \(draft?.name ?? "") role?", isPresented: $deleting, titleVisibility: .visible) {
                Button("Delete", role: .destructive) { if let x = draft { Task { if await store.act({ try await $0.deleteRole(d.server.id, x.id) }) { draft = nil; reload() } } } }
            }
    }

    func move(_ id: String, _ dir: Int) {
        var order = roles.filter { !$0.everyone && canEdit($0) }.map { $0.id }
        guard let i = order.firstIndex(of: id), i + dir >= 0, i + dir < order.count else { return }
        order.swapAt(i, i + dir)
        Task { if await store.act({ try await $0.reorderRoles(d.server.id, order) }) { reload() } }
    }
}

private struct MembersTab: View {
    @EnvironmentObject var store: Store
    let d: ServerDetail
    let reload: () -> Void
    @State private var q = ""
    var body: some View {
        Section("Members (\(d.members.count))") {
            TextField("Search members", text: $q)
            ForEach(d.members.filter { q.isEmpty || "\($0.nickname ?? "") \($0.name)".lowercased().contains(q.lowercased()) }.sorted { $0.name.lowercased() < $1.name.lowercased() }) { m in
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Avatar(person: m, size: 32)
                        Text(m.nickname.flatMap { $0.isEmpty ? nil : $0 } ?? m.name).bold()
                        if m.bot { BotTag() }
                        if m.uuid == d.server.owner { Text("👑") }
                    }
                    if let me = store.me { MemberActions(d: d, member: m, me: me, changed: reload) }
                }
            }
        }
        .rowBg()
    }
}

private struct InvitesTab: View {
    @EnvironmentObject var store: Store
    let d: ServerDetail
    let reload: () -> Void
    @State private var code: String?
    @State private var vanity = ""
    @State private var renew = false
    @State private var loaded = false
    var body: some View {
        let s = d.server
        Section("Invite link") {
            if let c = code {
                Text(inviteBase + c).textSelection(.enabled)
                ShareLink(item: URL(string: inviteBase + c)!, message: Text("Join \(s.name) on Jace Social")) { Label("Share", systemImage: "square.and.arrow.up") }
                Button("Copy") { UIPasteboard.general.string = inviteBase + c; store.show("Copied") }
                Button("New link") { renew = true }
            } else { Text("You can't create invites in this server.").foregroundStyle(JaceColor.muted) }
        }
        .rowBg()
        .onAppear { if !loaded { loaded = true; code = s.invite_code; vanity = s.vanity ?? "" } }
        .confirmationDialog("Make a new link? The old one will stop working.", isPresented: $renew, titleVisibility: .visible) {
            Button("New link") { Task { await store.act { code = try await $0.newInvite(s.id) } } }
        }
        if P.has(d.can, P.MANAGE_SERVER) {
            Section {
                HStack {
                    Text("jace-social.vercel.app/").foregroundStyle(JaceColor.muted)
                    TextField("yourserver", text: Binding(get: { vanity }, set: { vanity = String($0.lowercased().filter { $0.isLetter || $0.isNumber || $0 == "-" }.prefix(32)) }))
                        .textInputAutocapitalization(.never)
                }
                Button("Save custom link") { Task { if await store.act({ try await $0.updateServer(s.id, ["vanity": vanity]) }) { store.show("Saved"); reload() } } }
                ToggleRow(label: "Pause invites", desc: "Nobody new can join for now", isOn: s.invites_paused == true) { on in
                    Task { if await store.act({ try await $0.updateServer(s.id, ["invites_paused": on]) }) { reload() } }
                }
            } header: { Text("Custom link") } footer: { Text("3-32 lowercase letters, numbers and dashes. It works like an invite that never changes.") }
            .rowBg()
        }
    }
}

private struct OnboardingTab: View {
    @EnvironmentObject var store: Store
    let d: ServerDetail
    let reload: () -> Void
    @State private var rules = ""
    @State private var welcome = ""
    @State private var channels: [WelcomeChannel] = []
    @State private var questions: [OnboardingQuestion] = []
    @State private var loaded = false

    var body: some View {
        let roles = d.allRoles.filter { !$0.everyone }.sorted { $0.position > $1.position }
        let texts = d.channels.filter { $0.type != "category" && $0.type != "voice" }
        Section {
            TextField("1. Be kind\n2. No spam", text: $rules.max(2000), axis: .vertical).lineLimit(3...10)
        } header: { Text("Rules") } footer: { Text("What new members see when they join: the rules to agree to, questions whose answers give them roles, and a welcome screen.") }
        .rowBg()
        Section("Welcome message") { TextField("Welcome! Start in #general.", text: $welcome.max(300), axis: .vertical).lineLimit(2...4) }.rowBg()
        Section("Suggested channels") {
            ForEach(channels.indices, id: \.self) { i in
                HStack {
                    TextField("👋", text: Binding(get: { channels[i].emoji ?? "" }, set: { channels[i].emoji = String($0.prefix(16)) })).frame(width: 40)
                    Picker("", selection: Binding(get: { channels[i].id }, set: { channels[i].id = $0 })) { ForEach(texts) { Text("#\($0.name)").tag($0.id) } }.labelsHidden()
                    TextField("What it's for", text: Binding(get: { channels[i].description ?? "" }, set: { channels[i].description = String($0.prefix(60)) }))
                    Button { channels.remove(at: i) } label: { Image(systemName: "xmark") }.buttonStyle(.borderless)
                }
            }
            if channels.count < 5, let first = texts.first { Button("＋ Add channel") { channels.append(WelcomeChannel(id: first.id, description: "", emoji: "")) } }
        }
        .rowBg()
        ForEach(questions.indices, id: \.self) { qi in
            Section("Question \(qi + 1)") {
                HStack {
                    TextField("What do you play?", text: Binding(get: { questions[qi].question }, set: { questions[qi].question = String($0.prefix(100)) }))
                    Button { questions.remove(at: qi) } label: { Image(systemName: "xmark") }.buttonStyle(.borderless)
                }
                ToggleRow(label: "People can pick more than one", isOn: questions[qi].multiple) { questions[qi].multiple = $0 }
                ForEach(questions[qi].options.indices, id: \.self) { oi in
                    VStack(alignment: .leading) {
                        HStack {
                            TextField("⛏", text: Binding(get: { questions[qi].options[oi].emoji ?? "" }, set: { questions[qi].options[oi].emoji = String($0.prefix(16)) })).frame(width: 40)
                            TextField("Answer", text: Binding(get: { questions[qi].options[oi].label }, set: { questions[qi].options[oi].label = String($0.prefix(50)) }))
                            Button { questions[qi].options.remove(at: oi) } label: { Image(systemName: "xmark") }.buttonStyle(.borderless)
                        }
                        Menu("Roles: \(roles.filter { questions[qi].options[oi].role_ids.contains($0.id) }.map { $0.name }.joined(separator: ", ").ifEmpty("none"))") {
                            ForEach(roles) { r in
                                Button {
                                    var ids = questions[qi].options[oi].role_ids
                                    if ids.contains(r.id) { ids.removeAll { $0 == r.id } } else { ids.append(r.id) }
                                    questions[qi].options[oi].role_ids = ids
                                } label: { Label(r.name, systemImage: questions[qi].options[oi].role_ids.contains(r.id) ? "checkmark" : "") }
                            }
                        }
                        .font(.footnote)
                    }
                }
                if questions[qi].options.count < 10 { Button("＋ Add answer") { questions[qi].options.append(OnboardingOption()) } }
            }
            .rowBg()
        }
        Section {
            if questions.count < 5 { Button("＋ Add question") { questions.append(OnboardingQuestion(options: [OnboardingOption()])) } }
            Button("Save onboarding") {
                let qs: [[String: Any]] = questions.filter { !$0.question.trimmingCharacters(in: .whitespaces).isEmpty }.map { q in
                    ["question": q.question, "multiple": q.multiple,
                     "options": q.options.filter { !$0.label.trimmingCharacters(in: .whitespaces).isEmpty }.map { o in ["label": o.label, "emoji": orNull(o.emoji), "role_ids": o.role_ids] as [String: Any] }]
                }
                let wcs: [[String: Any]] = channels.map { ["id": $0.id, "description": $0.description ?? "", "emoji": $0.emoji ?? ""] }
                Task {
                    if await store.act({ try await $0.updateServer(d.server.id, ["rules": rules, "welcome": ["message": welcome, "channels": wcs] as [String: Any], "onboarding": qs]) }) {
                        store.show("Saved"); reload()
                    }
                }
            }
        }
        .rowBg()
        .onAppear {
            guard !loaded else { return }
            loaded = true
            rules = d.server.rules ?? ""; welcome = d.server.welcome?.message ?? ""
            channels = d.server.welcome?.channels ?? []; questions = d.server.onboarding ?? []
        }
    }
}

extension String {
    func ifEmpty(_ other: String) -> String { isEmpty ? other : self }
}

private struct BansTab: View {
    @EnvironmentObject var store: Store
    let d: ServerDetail
    @State private var bans: [Ban]?
    var body: some View {
        Section("Bans") {
            if let bans {
                if bans.isEmpty { Text("Nobody is banned.").foregroundStyle(JaceColor.muted) }
                ForEach(bans) { b in
                    HStack {
                        Avatar(person: b.person ?? Person(uuid: b.uuid, name: "?"), size: 32)
                        VStack(alignment: .leading) {
                            Text(b.person?.name ?? b.uuid).bold()
                            Text("\(b.reason ?? "No reason given") · \(String((b.created_at ?? "").prefix(10)))").font(.caption).foregroundStyle(JaceColor.muted)
                        }
                        Spacer()
                        Button("Unban") { Task { if await store.act({ try await $0.unban(d.server.id, b.uuid) }) { await load() } } }.buttonStyle(.borderless)
                    }
                }
            } else { Text("Loading…").foregroundStyle(JaceColor.muted) }
        }
        .rowBg()
        .task { await load() }
    }
    func load() async { await store.act { bans = try await $0.bans(d.server.id) } }
}

private struct BotsTab: View {
    @EnvironmentObject var store: Store
    let d: ServerDetail
    let reload: () -> Void
    @State private var q = ""
    @State private var found: [Person] = []
    @State private var mine: [Person] = []
    var body: some View {
        let inServer = Set(d.members.map { $0.uuid })
        Section {
            if !mine.isEmpty {
                Text("Your bots").font(.caption.bold()).foregroundStyle(JaceColor.muted)
                ForEach(mine) { row($0, added: inServer.contains($0.uuid)) }
            }
            TextField("Search public bots", text: $q)
            ForEach(found) { row($0, added: inServer.contains($0.uuid)) }
        } header: { Text("Bots") } footer: { Text("Bots join as members. Give them roles to let them do more (like giving roles or deleting messages).") }
        .rowBg()
        .task { mine = ((try? await store.api.bots()) ?? []).map { $0.asPerson } }
        .task(id: q) {
            try? await Task.sleep(nanoseconds: 250_000_000)
            do { found = try await store.api.directory(q) } catch { store.show(error.localizedDescription) }
        }
    }

    func row(_ b: Person, added: Bool) -> some View {
        HStack {
            Avatar(person: b, size: 32)
            VStack(alignment: .leading) { HStack { Text(b.name).bold(); BotTag() }; if let bio = b.bio { Text(bio).font(.caption).foregroundStyle(JaceColor.muted) } }
            Spacer()
            if added { Text("Added").foregroundStyle(JaceColor.muted) }
            else { Button("Add") { Task { if await store.act({ try await $0.addBot(d.server.id, b.uuid) }) { reload() } } }.buttonStyle(.borderless) }
        }
    }
}
