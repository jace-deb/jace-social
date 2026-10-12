import SwiftUI

/// Create a server, or join one with an invite code or link.
struct AddServerView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    @State private var name = ""
    @State private var code: String
    init(code: String) { _code = State(initialValue: code) }

    var body: some View {
        Form {
            Section {
                TextField("Server name", text: $name.max(48))
                Button("Create") {
                    let n = name.trimmingCharacters(in: .whitespaces)
                    Task {
                        await store.act {
                            let id = try await $0.createServer(n)
                            await store.refreshServers()
                            router.back()
                            router.go(.server(id, n))
                        }
                    }
                }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            } header: { Text("Create a server") } footer: { Text("A place for you and your friends, with text channels, voice rooms, roles and bots.") }
            .rowBg()
            Section("Join a server") {
                TextField("Invite code or link", text: $code).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Join") { router.back(); router.go(.invite(inviteCode(code))) }.disabled(code.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .rowBg()
        }
        .jaceList()
        .navigationTitle("Add a server")
    }
}

/// An invite: which server it's for, and Join.
struct InviteView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    let code: String
    @State private var preview: InvitePreview?
    @State private var error: String?

    var body: some View {
        Form {
            if let e = error { Text(e).foregroundStyle(JaceColor.red) }
            if let p = preview {
                Section {
                    HStack(spacing: 14) {
                        IconPic(name: p.server.name, url: p.server.icon_url, size: 60)
                        VStack(alignment: .leading) {
                            Text("You've been invited to join").font(.footnote).foregroundStyle(JaceColor.muted)
                            Text(p.server.name).font(.title3.bold())
                            Text("\(p.server.online ?? 0) online · \(p.server.members ?? 0) members").font(.footnote).foregroundStyle(JaceColor.muted)
                        }
                    }
                    if let d = p.server.description { Text(d) }
                    Button(p.joined == true ? "Open server" : "Join \(p.server.name)") {
                        Task {
                            await store.act {
                                let r = try await $0.joinInvite(code)
                                await store.refreshServers()
                                router.back()
                                router.go(.server(r.server.id, r.server.name))
                            }
                        }
                    }
                }
                .rowBg()
            }
        }
        .jaceList()
        .navigationTitle("Invite")
        .task { do { preview = try await store.api.invite(code) } catch { self.error = error.localizedDescription } }
    }
}

/// A server's onboarding when you join it: rules to agree to, questions that give roles, then the welcome screen.
struct ServerOnboardingView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    let d: ServerDetail
    let done: () -> Void
    @State private var agreed = false
    @State private var answers: [[Int]] = []
    @State private var step = ""

    var body: some View {
        let s = d.server
        let questions = s.onboarding ?? []
        Form {
            Section {
                HStack(spacing: 12) {
                    IconPic(name: s.name, url: s.icon_url, size: 48)
                    VStack(alignment: .leading) { Text(s.name).font(.headline); if let x = s.description { Text(x).font(.footnote).foregroundStyle(JaceColor.muted) } }
                }
            }
            .rowBg()
            switch step {
            case "rules":
                Section("Server rules") {
                    Text(rich(s.rules ?? "", who: { _ in nil }))
                    Toggle("I've read and agree to the rules", isOn: $agreed).tint(JaceColor.green)
                    Button("Continue") { if questions.isEmpty { submit() } else { step = "questions" } }.disabled(!agreed)
                }
                .rowBg()
            case "questions":
                ForEach(questions.indices, id: \.self) { i in
                    Section {
                        ForEach(questions[i].options.indices, id: \.self) { k in
                            let on = answers.count > i && answers[i].contains(k)
                            Button {
                                while answers.count <= i { answers.append([]) }
                                if questions[i].multiple { if on { answers[i].removeAll { $0 == k } } else { answers[i].append(k) } } else { answers[i] = [k] }
                            } label: {
                                HStack { Text("\(questions[i].options[k].emoji ?? "") \(questions[i].options[k].label)").foregroundStyle(JaceColor.text); Spacer(); if on { Image(systemName: "checkmark").foregroundStyle(JaceColor.green) } }
                            }
                        }
                    } header: { Text(questions[i].question) } footer: { if questions[i].multiple { Text("Pick any that fit") } }
                    .rowBg()
                }
                Section { Button("Finish") { submit() } }.rowBg()
            default:
                Section {
                    if let w = s.welcome?.message, !w.isEmpty { Text(w) }
                    ForEach(s.welcome?.channels ?? [], id: \.id) { w in
                        if let c = d.channels.first(where: { $0.id == w.id }) {
                            Button {
                                done()
                                router.go(.chat(.channel(c.id), "#\(c.name)"))
                            } label: {
                                HStack { Text((w.emoji?.isEmpty == false ? w.emoji : nil) ?? "#"); VStack(alignment: .leading) { Text("#\(c.name)").bold().foregroundStyle(JaceColor.text); if let x = w.description { Text(x).font(.footnote).foregroundStyle(JaceColor.muted) } } }
                            }
                        }
                    }
                    Button("Let's go") { done() }
                }
                .rowBg()
            }
        }
        .jaceList()
        .navigationTitle("Welcome to \(s.name)")
        .interactiveDismissDisabled(step != "welcome")
        .onAppear {
            if step.isEmpty { step = (s.rules?.isEmpty == false) ? "rules" : questions.isEmpty ? "welcome" : "questions" }
            if answers.isEmpty { answers = questions.map { _ in [] } }
        }
    }

    func submit() {
        let rulesEmpty = d.server.rules?.isEmpty != false
        Task { if await store.act({ try await $0.finishOnboarding(d.server.id, answers: answers, agreed: agreed || rulesEmpty) }) { step = "welcome" } }
    }
}

/// The first-time walkthrough for new accounts: your name and picture, the look, friends, servers.
struct WelcomeView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    @Environment(\.dismiss) private var dismiss
    @State private var step = 0
    @State private var name = ""
    @State private var friend = ""
    @State private var sent: [String] = []
    let steps = ["You", "Look", "Friends", "Servers"]

    var body: some View {
        Form {
            if let me = store.me {
                Section { HStack { ForEach(steps.indices, id: \.self) { i in Text(steps[i]).fontWeight(i == step ? .bold : .regular).foregroundStyle(i == step ? JaceColor.green : JaceColor.muted) } } }.rowBg()
                switch step {
                case 0:
                    Section {
                        Text("Welcome to Jace Social, \(me.name)!").font(.title3.bold())
                        Text("Friends, chat, servers and voice, with Minecraft built in. Let's set you up. It takes a minute.").foregroundStyle(JaceColor.muted)
                        HStack(spacing: 14) {
                            Avatar(person: me.asPerson, size: 64)
                            PhotoButton(onPick: { p in Task { if await store.act({ try await $0.setAvatar(p.data, type: p.type) }) { await store.refreshMe() } } }) { Text("Choose a picture") }
                                .buttonStyle(.borderless)
                        }
                        TextField("Display name (what friends see)", text: $name.max(32))
                    }
                    .rowBg()
                case 1:
                    Section("Make it yours") {
                        ForEach(THEMES, id: \.0) { id, label in
                            Button { store.saveSettings(["theme": id]) } label: {
                                HStack { Text(label).foregroundStyle(JaceColor.text); Spacer(); if (me.prefs.theme ?? "dark") == id { Image(systemName: "checkmark").foregroundStyle(JaceColor.green) } }
                            }
                        }
                        Swatches(selected: me.prefs.accent ?? "#3ddc84") { store.saveSettings(["accent": orNull($0)]) }
                        Text("You can change all of this later in You → Appearance.").font(.footnote).foregroundStyle(JaceColor.muted)
                    }
                    .rowBg()
                case 2:
                    Section("Find your friends") {
                        Text("Type a friend's Minecraft or Jace username to send them a request. It works even if they haven't joined yet.").foregroundStyle(JaceColor.muted)
                        HStack {
                            TextField("Username", text: $friend.max(33)).textInputAutocapitalization(.never).autocorrectionDisabled()
                            Button("Send") {
                                let n = friend.trimmingCharacters(in: .whitespaces)
                                Task { if await store.act({ _ = try await $0.addFriend(n) }) { sent.append(n); friend = ""; await store.refreshFriends() } }
                            }
                            .disabled(friend.trimmingCharacters(in: .whitespaces).isEmpty).buttonStyle(.borderless)
                        }
                        ForEach(sent, id: \.self) { Text("✓ Request sent to \($0)").foregroundStyle(JaceColor.green) }
                    }
                    .rowBg()
                default:
                    Section("Join a server") {
                        Text("Servers are places for groups: text channels, voice rooms, roles and bots. Make one for your friends, or join one with an invite link.").foregroundStyle(JaceColor.muted)
                        Button("Create or join a server") { finish(); router.open(tab: 1, .addServer("")) }
                    }
                    .rowBg()
                }
                Section {
                    HStack {
                        if step > 0 { Button("Back") { step -= 1 }.buttonStyle(.borderless) }
                        Spacer()
                        Button("Skip") { finish() }.buttonStyle(.borderless)
                        Button(step == steps.count - 1 ? "Finish" : "Next") {
                            if step == 0 && name.trimmingCharacters(in: .whitespaces) != (me.display_name ?? "") { store.updateMe(["display_name": name.trimmingCharacters(in: .whitespaces)]) }
                            if step == steps.count - 1 { finish() } else { step += 1 }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .rowBg()
            }
        }
        .jaceList()
        .navigationTitle("Welcome")
        .onAppear { name = store.me?.display_name ?? "" }
    }

    func finish() {
        store.updateMe(["onboarded": true])
        dismiss()
    }
}
