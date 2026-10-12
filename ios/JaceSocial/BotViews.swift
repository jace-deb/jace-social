import SwiftUI
import WebKit

/// My bots: make bots, get their token, switch to them, build them with blocks or code, see what they did.
struct BotsView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var session: Session
    @EnvironmentObject var router: Router
    @State private var bots: [Bot]?
    @State private var name = ""
    @State private var token: String?
    @State private var confirm: (String, Bot)?

    var body: some View {
        Form {
            Section {
                HStack {
                    TextField("New bot's name", text: $name.max(32))
                    Button("Create") {
                        let n = name.trimmingCharacters(in: .whitespaces)
                        Task { await store.act { token = try await $0.createBot(n); name = "" }; await load() }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty).buttonStyle(.borderless)
                }
            } footer: {
                Text("Bots are accounts you own. Build one with blocks (no code), write its code here and Jace Social runs it for you, or program it anywhere with its token and the Jace Social API. You can also switch to a bot and chat as it.")
            }
            .rowBg()
            if let t = token {
                Section {
                    Text(t).font(.footnote.monospaced()).textSelection(.enabled)
                    Button("Copy") { UIPasteboard.general.string = t; store.show("Copied") }
                    Button("Done") { token = nil }
                } header: { Text("Bot token") } footer: { Text("Copy it now - it's only shown once. Anyone with it can act as your bot, so keep it secret.") }
                .rowBg()
            }
            if let bots {
                if bots.isEmpty { Text("No bots yet.").foregroundStyle(JaceColor.muted) }
                ForEach(bots) { b in
                    Section {
                        HStack(spacing: 12) {
                            PhotoButton(onPick: { p in Task { if await store.act({ try await $0.setBotAvatar(b.uuid, p.data, type: p.type) }) { await load() } } }) {
                                Avatar(person: b.asPerson, size: 48)
                            }
                            .buttonStyle(.borderless)
                            VStack(alignment: .leading) {
                                HStack { Text(b.name).bold(); BotTag() }
                                Text("\(b.has_code == true ? "Runs its code on Jace Social" : b.has_program == true ? "Made with blocks" : "Uses the API (or nothing yet)") · \(b.bot_public == true ? "Anyone can add it" : "Only you can add it")")
                                    .font(.caption).foregroundStyle(JaceColor.muted)
                            }
                        }
                        BioField(bot: b) { bio in Task { if await store.act({ try await $0.updateBot(b.uuid, ["bio": bio]) }) { await load() } } }
                        Button("🧩 Edit blocks") { router.go(.botBlocks(b.uuid)) }
                        Button("💻 \(b.has_code == true ? "Edit code" : "Write code")") { router.go(.botCode(b.uuid)) }
                        Button("📜 Activity") { router.go(.botActivity(b.uuid, nil)) }
                        Button("Switch to this bot") { confirm = ("switch", b) }
                        Button("New token") { confirm = ("token", b) }
                        ToggleRow(label: "Public", desc: "Anyone can add it to their server", isOn: b.bot_public == true) { on in patch(b, ["bot_public": on]) }
                        ToggleRow(label: "On", desc: "Off: it doesn't answer anything", isOn: b.bot_enabled != false) { on in patch(b, ["bot_enabled": on]) }
                        Button("Delete", role: .destructive) { confirm = ("delete", b) }
                    }
                    .rowBg()
                }
            } else { Text("Loading…").foregroundStyle(JaceColor.muted) }
            Section("Programming a bot with the API (run it yourself)") {
                Text("Send requests to \(Api.base.absoluteString) with the header Authorization: Bearer <bot token>. The bot uses the same API as the app: read and send messages in /channels/{id}/messages, react with /reactions, and so on. Live events (new messages, and \"command\" when someone uses one of its slash commands) arrive on the bot's private Realtime channel: GET /me gives its name and key.")
                    .font(.footnote)
            }
            .rowBg()
        }
        .jaceList()
        .navigationTitle("My bots")
        .task { await load() }
        .confirmationDialog(confirmTitle, isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible, presenting: confirm) { c in
            switch c.0 {
            case "switch":
                Button("Switch") {
                    Task {
                        await store.act {
                            let s = try await $0.botSession(c.1.uuid)
                            session.remember(SavedAccount(uuid: s.uuid, name: s.name, avatar_url: s.avatar_url, token: s.token, is_bot: true))
                            store.stop()
                            session.switchTo(s.uuid)
                        }
                    }
                }
            case "token": Button("New token") { Task { await store.act { token = try await $0.botToken(c.1.uuid) } } }
            default: Button("Delete", role: .destructive) { Task { if await store.act({ try await $0.deleteBot(c.1.uuid) }) { await load() } } }
            }
        } message: { c in
            Text(c.0 == "switch" ? "You can switch back from You → Switch accounts." : c.0 == "token" ? "The old one stops working right away." : "It leaves every server.")
        }
    }

    var confirmTitle: String {
        guard let c = confirm else { return "" }
        switch c.0 { case "switch": return "Switch to \(c.1.name)?"; case "token": return "Make a new token?"; default: return "Delete \(c.1.name)?" }
    }

    func load() async { await store.act { bots = try await $0.bots() } }
    func patch(_ b: Bot, _ f: [String: Any]) { Task { if await store.act({ try await $0.updateBot(b.uuid, f) }) { await load() } } }
}

private struct BioField: View {
    let bot: Bot
    let save: (String) -> Void
    @State private var bio = ""
    var body: some View {
        HStack {
            TextField("What does it do?", text: $bio.max(300), axis: .vertical)
            if bio != (bot.bio ?? "") { Button("Save") { save(bio) }.buttonStyle(.borderless) }
        }
        .onAppear { bio = bot.bio ?? "" }
    }
}

private let starterCode = """
// This runs on Jace Social's server whenever something happens where your bot is.
// It can reply, send messages, react, give roles and remember things (storage).

bot.on("message", (msg) => {
  if (msg.text.toLowerCase() === "hi bot") msg.reply("Hi " + msg.author.name + "! 👋");
});

bot.command("count", "Count up by one", (cmd) => {
  const n = (storage.get("count") || 0) + 1;
  storage.set("count", n);
  cmd.reply("The count is now " + n);
});

bot.on("join", (member) => {
  member.reply("Welcome, " + member.author.name + "!");
});
"""

private let codeHelp = """
bot.on("message", (msg) => { ... })   someone wrote in a chat the bot is in
bot.on("join", (member) => { ... })    someone joined the server
bot.command("name", "What it does", [{ name: "option", required: true }], (cmd, args) => { ... })

msg / cmd / member have:
  .text  .author.id  .author.name  .channel.id  .channel.name  .server
  .reply(text)  .react("👍")  .delete()  .giveRole("Role")  .takeRole("Role")
bot.send("channel-name", text)
storage.get(key)  storage.set(key, value)  storage.delete(key)  storage.keys()
console.log(...)  shows in the bot's Activity

Limits per event: 300 ms of work, 5 messages, 32 MB of memory. No internet access, files or timers.
"""

/// Write a bot in JavaScript; Jace Social runs it (in a sandbox) when something happens. Try it first.
struct BotCodeView: View {
    @EnvironmentObject var store: Store
    let id: String
    @State private var code: String?
    @State private var saved = ""
    @State private var status = ""
    @State private var tryText = "hi bot"
    @State private var result: [String: Any]?
    @State private var removing = false

    var body: some View {
        Form {
            if let c = code {
                if !status.isEmpty { Text(status).foregroundStyle(status.hasPrefix("⚠") ? JaceColor.red : JaceColor.green).rowBg() }
                Section {
                    TextEditor(text: Binding(get: { c }, set: { code = $0 }))
                        .font(.system(size: 13, design: .monospaced)).frame(minHeight: 320)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                }
                .rowBg()
                Section {
                    HStack {
                        TextField("A message, or /command", text: $tryText).textInputAutocapitalization(.never)
                        Button("Run") { Task { do { result = try await store.api.tryBotCode(id, c, tryText) } catch { status = "⚠ \(error.localizedDescription)" } } }
                            .buttonStyle(.borderless)
                    }
                    if let r = result { TryResultView(r: r) }
                } header: { Text("Try it") } footer: { Text("Runs the code above for a pretend message (or a /command) without saving or sending anything.") }
                .rowBg()
                Section("What your code can use") { Text(codeHelp).font(.system(size: 11, design: .monospaced)) }.rowBg()
                if !saved.isEmpty { Section { Button("Remove code", role: .destructive) { removing = true } }.rowBg() }
            } else { Text("Loading…").foregroundStyle(JaceColor.muted) }
        }
        .jaceList()
        .navigationTitle("Bot code")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(code != saved ? "Save" : "Saved") {
                    guard let c = code else { return }
                    Task {
                        do {
                            let cmds = try await store.api.saveBotCode(id, c)
                            saved = c
                            status = "Saved ✓" + (cmds.isEmpty ? "" : " Commands: \(cmds.map { "/" + $0 }.joined(separator: ", ")).")
                        } catch { status = "⚠ \(error.localizedDescription)" }
                    }
                }
                .disabled(code == nil || code == saved)
            }
        }
        .task {
            do { let c = try await store.api.botCode(id); code = c ?? starterCode; saved = c ?? "" }
            catch { code = starterCode; store.show(error.localizedDescription) }
        }
        .confirmationDialog("Remove this code? The bot stops running it.", isPresented: $removing, titleVisibility: .visible) {
            Button("Remove", role: .destructive) { Task { if await store.act({ _ = try await $0.saveBotCode(id, nil) }) { saved = ""; status = "Code removed" } } }
        }
    }
}

private struct TryResultView: View {
    let r: [String: Any]
    var body: some View {
        let actions = r["actions"] as? [[String: Any]] ?? []
        VStack(alignment: .leading, spacing: 4) {
            if let e = r["error"] as? String { Text("⚠ \(e)").foregroundStyle(JaceColor.red) }
            if actions.isEmpty && r["error"] == nil { Text("It wouldn't do anything.").foregroundStyle(JaceColor.muted) }
            ForEach(actions.indices, id: \.self) { i in Text(describe(actions[i])) }
            ForEach(((r["storage"] as? [String: Any]) ?? [:]).sorted { $0.key < $1.key }, id: \.key) { k, v in
                Text("💾 " + (v is NSNull ? "Forget \"\(k)\"" : "Save \"\(k)\" = \(v)"))
            }
            ForEach((r["logs"] as? [String]) ?? [], id: \.self) { Text("› \($0)").foregroundStyle(JaceColor.muted) }
        }
        .font(.footnote)
    }
    func describe(_ a: [String: Any]) -> String {
        let t = a["t"] as? String ?? "", s = { (k: String) in a[k] as? String ?? "" }
        switch t {
        case "reply": return "💬 Reply: \(s("text"))"
        case "send": return "💬 Send in #\(s("channel")): \(s("text"))"
        case "react": return "😀 React with \(s("emoji"))"
        case "give_role": return "🏷️ Give the role \(s("role"))"
        case "take_role": return "🏷️ Take the role \(s("role"))"
        case "delete_message": return "🗑️ Delete the message"
        default: return t
        }
    }
}

/// The block editor: Blockly is JavaScript with no native version, so it's the website's /bot-blocks
/// page in a web view (the one part of the app that isn't native).
struct BotBlocksView: View {
    @EnvironmentObject var session: Session
    @EnvironmentObject var router: Router
    let id: String
    var body: some View {
        let site = Api.base.absoluteString.replacingOccurrences(of: "/api/v1", with: "")
        // the token goes in the #fragment, which is never sent to a server
        WebPage(url: URL(string: "\(site)/bot-blocks?bot=\(id)#token=\(session.token ?? "")")!) { router.back() }
            .navigationTitle("Bot blocks")
            .navigationBarTitleDisplayMode(.inline)
            .ignoresSafeArea(edges: .bottom)
    }
}

private struct WebPage: UIViewRepresentable {
    let url: URL
    let close: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(close: close) }
    func makeUIView(context: Context) -> WKWebView {
        let v = WKWebView()
        v.navigationDelegate = context.coordinator
        v.load(URLRequest(url: url))
        return v
    }
    func updateUIView(_ v: WKWebView, context: Context) {}
    final class Coordinator: NSObject, WKNavigationDelegate {
        let close: () -> Void
        init(close: @escaping () -> Void) { self.close = close }
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if action.request.url?.scheme == "jacesocial" { decisionHandler(.cancel); DispatchQueue.main.async { self.close() }; return }
            decisionHandler(.allow)
        }
    }
}

/// Everything a bot did, newest first.
struct BotActivityView: View {
    @EnvironmentObject var store: Store
    let id: String
    let server: String?
    @State private var entries: [BotLog] = []
    @State private var channels: [String: String] = [:]
    @State private var servers: [String: String] = [:]
    @State private var more = true
    @State private var error: String?
    let icons = ["event": "📥", "message": "💬", "react": "😀", "role": "🏷️", "delete": "🗑️", "storage": "💾", "log": "›", "error": "⚠️", "command": "⌨️"]

    var body: some View {
        List {
            Text("\(server != nil ? "In this server, " : "")newest first. Kept for 14 days.").font(.footnote).foregroundStyle(JaceColor.muted)
            if let e = error { Text(e).foregroundStyle(JaceColor.red) }
            if error == nil && entries.isEmpty { Text("Nothing yet.").foregroundStyle(JaceColor.muted) }
            ForEach(entries) { e in
                HStack(alignment: .top) {
                    Text(icons[e.kind] ?? "•").frame(width: 26)
                    VStack(alignment: .leading) {
                        Text(e.detail).foregroundStyle(e.kind == "error" ? JaceColor.red : JaceColor.text)
                        Text([timeOf(e.at) + " " + String(e.at.prefix(10)), server == nil ? e.server_id.flatMap { servers[$0] } : nil,
                              e.channel_id.flatMap { channels[$0] }.map { "#\($0)" }].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(JaceColor.muted)
                    }
                }
                .rowBg()
            }
            if more && !entries.isEmpty { Button("Show older") { Task { await load(before: entries.last?.id) } } }
        }
        .jaceList()
        .navigationTitle("📜 What it did")
        .task { await load() }
    }

    func load(before: Int? = nil) async {
        do {
            let r = try await store.api.botActivity(id, server: server, before: before)
            entries = before != nil ? entries + r.entries : r.entries
            channels.merge(r.channels ?? [:]) { $1 }
            servers.merge(r.servers ?? [:]) { $1 }
            more = r.entries.count == 100
        } catch { self.error = error.localizedDescription }
    }
}
