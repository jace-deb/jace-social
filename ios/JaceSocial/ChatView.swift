import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

private let quickReactions = ["👍", "❤️", "😂", "😮", "😢", "🎉"]

/// A file being sent: uploading, then its attachment.
private struct Upload: Identifiable {
    let id = UUID()
    let name: String
    let image: Bool
    var att: Attachment?
}

/// One conversation: a friend, a group chat or a server channel.
struct ChatView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    let target: ChatTarget
    let title: String
    @State private var messages: [Message] = []
    @State private var replies: [String: ReplyPreview] = [:]
    @State private var people: [String: Person] = [:]
    @State private var perms: Int?
    @State private var error: String?
    @State private var loaded = false
    @State private var olderDone = false
    @State private var text = ""
    @State private var replyTo: Message?
    @State private var editing: Message?
    @State private var lastTyping = Date.distantPast
    @State private var commands: [Command] = []
    @State private var pins: [Message]?
    @State private var uploads: [Upload] = []
    @State private var mentionTokens: [String: String] = [:]      // "@Steve" -> "<@uuid>"
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var importing = false
    @State private var deleting: Message?
    @FocusState private var focused: Bool

    var api: Api { store.session.api }
    var group: Group? { if case .channel(let id) = target { return store.groups.first { $0.id == id } }; return nil }
    var isServer: Bool { if case .channel = target { return group == nil }; return false }
    var settings: Settings { store.me?.prefs ?? Settings() }
    func can(_ p: Int) -> Bool { perms.map { P.has($0, p) } ?? true }

    var body: some View {
        VStack(spacing: 0) {
            if let e = error { Text(e).font(.footnote).foregroundStyle(JaceColor.red).padding(.horizontal) }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if !olderDone && !messages.isEmpty {
                            ProgressView().frame(maxWidth: .infinity).padding().task { await loadOlder() }
                        }
                        if loaded && messages.isEmpty {
                            Text("No messages yet - say hi!").foregroundStyle(JaceColor.muted).padding(24)
                        }
                        ForEach(Array(messages.enumerated()), id: \.element.id) { i, m in
                            let prev = i > 0 ? messages[i - 1] : nil
                            let mine = m.sender == store.me?.uuid
                            MessageRow(message: m, sender: who(m.sender), grouped: grouped(prev, m),
                                       reply: m.reply_to.flatMap { replies[String($0)] }, who: who, mine: mine, settings: settings, me: store.me?.uuid,
                                       canPin: !isDirect && (group != nil || can(P.MANAGE_MESSAGES)), canDelete: mine || (isServer && can(P.MANAGE_MESSAGES)),
                                       onReply: { replyTo = m; editing = nil; focused = true },
                                       onEdit: { editing = m; replyTo = nil; text = m.body; focused = true },
                                       onDelete: { deleting = m },
                                       onPin: { on in Task { if await store.act({ try await $0.pin(m.id, on) }) { store.show(on ? "Pinned" : "Unpinned"); await loadNewest() } } },
                                       onReact: { e in Task { await react(m, e) } },
                                       onProfile: { if let s = m.sender { router.go(.profile(s, nil)) } })
                            .id(m.id)
                        }
                    }
                    .padding(.vertical, 8)
                }
                .onChange(of: messages.last?.id) { id in if let id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } } }
            }
            suggestionsList
            composer
        }
        .background(JaceColor.bg)
        .environment(\.openURL, OpenURLAction { url in store.open(url); return .handled })
        .navigationTitle(group?.name ?? title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Button {
                    switch target {
                    case .direct(let u): router.go(.profile(u, nil))
                    case .channel(let c): if group != nil { router.go(.group(c)) }
                    }
                } label: {
                    VStack(spacing: 0) {
                        Text(group?.name ?? title).font(.headline).lineLimit(1).foregroundStyle(JaceColor.text)
                        if let names = store.typing[target.key], !names.isEmpty {
                            Text("\(names.sorted().joined(separator: ", ")) \(names.count == 1 ? "is" : "are") typing…")
                                .font(.caption2).foregroundStyle(JaceColor.muted)
                        }
                    }
                }
            }
            ToolbarItemGroup(placement: .primaryAction) {
                if case .direct(let u) = target { Button { store.calls.call(u, name: title) } label: { Image(systemName: "phone") }.accessibilityLabel("Call") }
                if let g = group {
                    Button {
                        Task { await store.voice.join(me: store.me?.uuid ?? "", channel: g.id, server: nil, name: g.name) }
                        router.go(.voice)
                    } label: { Image(systemName: "phone") }.accessibilityLabel("Group call")
                }
                if case .channel(let c) = target {
                    Button {
                        Task { do { let p = try await api.pins(c); people.merge(p.people ?? [:]) { $1 }; pins = p.messages } catch { store.show(error.localizedDescription) } }
                    } label: { Image(systemName: "pin") }.accessibilityLabel("Pinned messages")
                }
            }
        }
        .task(id: target) {
            await loadNewest()
            if case .channel(let c) = target { commands = (try? await api.commands(c)) ?? [] }
        }
        .onReceive(store.live.events) { e in
            let here: Bool
            switch target {
            case .direct(let u): here = ["message", "dm_update"].contains(e.event) && (e.str("from") == u || e.str("with") == u)
            case .channel(let c): here = e.event == "channel" && e.str("channel_id") == c
            }
            if here { Task { await loadNewest() } }
        }
        .sheet(isPresented: Binding(get: { pins != nil }, set: { if !$0 { pins = nil } })) {
            NavigationStack {
                List {
                    if pins?.isEmpty == true { Text("Nothing pinned yet. Long-press a message and choose Pin.").foregroundStyle(JaceColor.muted) }
                    ForEach(pins ?? []) { m in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(who(m.sender)?.shownName ?? "Someone").bold()
                            Text(rich(m.body, who: who))
                            Text(timeOf(m.created_at, settings)).font(.caption).foregroundStyle(JaceColor.muted)
                        }
                        .rowBg()
                    }
                }
                .jaceList()
                .navigationTitle("📌 Pinned messages")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { pins = nil } } }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { r in
            if case .success(let urls) = r {
                for u in urls.prefix(10 - uploads.count) {
                    let access = u.startAccessingSecurityScopedResource()
                    defer { if access { u.stopAccessingSecurityScopedResource() } }
                    guard let d = try? Data(contentsOf: u) else { continue }
                    let type = UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                    upload(name: u.lastPathComponent, type: type, data: d, w: nil, h: nil)
                }
            }
        }
        .onChange(of: photoItems) { items in
            guard !items.isEmpty else { return }
            Task {
                for it in items {
                    if let d = try? await it.loadTransferable(type: Data.self), let img = UIImage(data: d) {
                        let jpg = img.jpegData(compressionQuality: 0.85) ?? d
                        upload(name: "photo-\(Int(Date().timeIntervalSince1970)).jpg", type: "image/jpeg", data: jpg, w: Int(img.size.width * img.scale), h: Int(img.size.height * img.scale))
                    }
                }
                photoItems = []
            }
        }
        .confirmationDialog("Delete this message?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("Delete", role: .destructive) { if let m = deleting { Task { await delete(m) } } }
        }
    }

    var isDirect: Bool { if case .direct = target { return true }; return false }

    // suggestions while typing: @people, /commands
    var suggestions: [(String, () -> Void)] {
        if let r = text.range(of: "(^|\\s)@[\\p{L}\\p{N}_.-]{0,32}$", options: .regularExpression) {
            let word = text[r].trimmingCharacters(in: .whitespaces)
            let q = word.dropFirst().lowercased()
            let everyone = Array((Array(people.values) + (store.friends?.friends ?? []) + (group?.members ?? []))
                .reduce(into: [String: Person]()) { $0[$1.uuid] = $1 }.values).filter { $0.uuid != store.me?.uuid }
            let matches = everyone.filter { q.isEmpty || $0.shownName.lowercased().contains(q) || $0.name.lowercased().contains(q) }.prefix(6)
            return matches.map { p in
                ("@\(p.shownName)", {
                    let label = "@\(p.shownName)"
                    let start = text.range(of: word, options: .backwards)?.lowerBound ?? text.endIndex
                    text = String(text[..<start]) + label + " "
                    mentionTokens[label] = "<@\(p.uuid)>"
                })
            }
        }
        if !isDirect, text.range(of: "^/[a-z0-9_-]*$", options: .regularExpression) != nil {
            let q = String(text.dropFirst())
            return commands.filter { $0.name.hasPrefix(q) }.prefix(12).map { c in ("/\(c.name) — \(c.description ?? c.bot_name ?? "")", { text = "/\(c.name) " }) }
        }
        return []
    }

    @ViewBuilder
    private var suggestionsList: some View {
        let s = suggestions
        if !s.isEmpty {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(s.indices, id: \.self) { i in
                        Button { s[i].1() } label: { Text(s[i].0).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 10) }
                            .foregroundStyle(JaceColor.text)
                    }
                }
            }
            .frame(maxHeight: 200)
            .background(JaceColor.bg2)
        } else if text == "/" && commands.isEmpty && !isDirect {
            Text("No commands here yet. Add a bot like Jace: server menu → Server settings → Bots").font(.footnote).foregroundStyle(JaceColor.muted).padding(8)
        }
    }

    private var composer: some View {
        VStack(spacing: 0) {
            if let banner = editing.map({ _ in "Editing your message" }) ?? replyTo.map({ "Replying to \(who($0.sender)?.shownName ?? "someone")" }) {
                HStack {
                    Text(banner).font(.footnote).foregroundStyle(JaceColor.muted)
                    Spacer()
                    Button { replyTo = nil; if editing != nil { editing = nil; text = "" } } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Cancel")
                }
                .padding(.horizontal).padding(.vertical, 6).background(JaceColor.panel)
            }
            if !uploads.isEmpty {
                ScrollView(.horizontal) {
                    HStack {
                        ForEach(uploads) { u in
                            HStack(spacing: 4) {
                                Image(systemName: u.image ? "photo" : "paperclip")
                                Text(u.name).lineLimit(1)
                                if u.att == nil { ProgressView().scaleEffect(0.7) }
                                else { Button { uploads.removeAll { $0.id == u.id } } label: { Image(systemName: "xmark") } }
                            }
                            .font(.footnote).padding(.horizontal, 10).padding(.vertical, 6).background(JaceColor.panel, in: Capsule())
                        }
                    }
                    .padding(.horizontal, 8)
                }
                .padding(.top, 6)
            }
            HStack(alignment: .bottom, spacing: 8) {
                if editing == nil {
                    Menu {
                        PhotosPicker(selection: $photoItems, maxSelectionCount: 10, matching: .images) { Label("Photos", systemImage: "photo") }
                        Button { importing = true } label: { Label("Files", systemImage: "doc") }
                    } label: { Image(systemName: "plus.circle").font(.system(size: 26)).foregroundStyle(JaceColor.muted) }
                    .accessibilityLabel("Attach files")
                }
                let field = SwiftUI.Group {
                    if settings.send_on_enter != false {
                        TextField("Message \(group?.name ?? title)", text: $text).submitLabel(.send).onSubmit { Task { await send() } }
                    } else {
                        TextField("Message \(group?.name ?? title)", text: $text, axis: .vertical).lineLimit(1...6)
                    }
                }
                field
                    .focused($focused)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(JaceColor.panel, in: RoundedRectangle(cornerRadius: 20))
                    .onChange(of: text) { t in
                        if !t.trimmingCharacters(in: .whitespaces).isEmpty && Date().timeIntervalSince(lastTyping) > 4 {
                            lastTyping = Date()
                            Task { await api.typing(target) }
                        }
                    }
                Button { Task { await send() } } label: { Image(systemName: "arrow.up.circle.fill").font(.system(size: 30)) }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !uploads.contains { $0.att != nil })
                    .accessibilityLabel("Send")
            }
            .padding(8)
        }
    }

    func who(_ uuid: String?) -> Person? { uuid.flatMap { people[$0] ?? store.person($0) } }

    func grouped(_ prev: Message?, _ m: Message) -> Bool {
        guard let p = prev, p.sender == m.sender, p.kind != "system", m.kind != "system",
              let a = parseTime(p.created_at), let b = parseTime(m.created_at) else { return false }
        return b.timeIntervalSince(a) < 300
    }

    func upload(name: String, type: String, data: Data, w: Int?, h: Int?) {
        guard can(P.ATTACH) else { return store.show("You can't attach files here") }
        guard data.count <= 10 * 1024 * 1024 else { return store.show("\(name) is bigger than 10 MB") }
        let u = Upload(name: name, image: type.hasPrefix("image/"))
        uploads.append(u)
        Task {
            do {
                let att = try await api.upload(name: name, type: type, data: data, width: w, height: h)
                if let i = uploads.firstIndex(where: { $0.id == u.id }) { uploads[i].att = att }
            } catch {
                store.show("Couldn't upload \(name): \(error.localizedDescription)")
                uploads.removeAll { $0.id == u.id }
            }
        }
    }

    func loadNewest() async {
        do {
            let page = try await api.messages(target)
            let first = page.messages.first?.id ?? 0
            var byId = Dictionary(uniqueKeysWithValues: messages.filter { $0.id < first }.map { ($0.id, $0) })
            for m in page.messages { byId[m.id] = m }
            messages = byId.values.sorted { $0.id < $1.id }
            replies.merge(page.replies ?? [:]) { $1 }
            people.merge(page.people ?? [:]) { $1 }
            perms = page.perms
            if page.messages.count < 50 { olderDone = true }
            error = nil
            await api.markRead(target)
        } catch let e as ApiError where e.status == 401 {
            store.session.expired()
        } catch {
            self.error = error.localizedDescription
        }
        loaded = true
    }

    func loadOlder() async {
        guard let oldest = messages.first?.id, let page = try? await api.messages(target, before: oldest) else { return }
        if page.messages.isEmpty { olderDone = true; return }
        messages = (page.messages + messages).reduce(into: [Int: Message]()) { $0[$1.id] = $1 }.values.sorted { $0.id < $1.id }
        replies.merge(page.replies ?? [:]) { $1 }
        people.merge(page.people ?? [:]) { $1 }
    }

    func send() async {
        var body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        for (label, token) in mentionTokens { body = body.replacingOccurrences(of: label, with: token) }
        if uploads.contains(where: { $0.att == nil }) { return store.show("Wait for the files to finish uploading") }
        let files = uploads.compactMap { $0.att }
        guard !body.isEmpty || !files.isEmpty else { return }
        if body.hasPrefix("/"), editing == nil, case .channel(let ch) = target {
            let name = String(body.dropFirst().split(separator: " ").first ?? "")
            if let c = commands.first(where: { $0.name == name }) {
                text = ""
                let rest = String(body.dropFirst(name.count + 1)).trimmingCharacters(in: .whitespaces)
                let args = c.options?.first.map { [$0.name: rest] } ?? [:]
                if let missing = c.options?.first(where: { $0.required == true && (args[$0.name] ?? "").isEmpty }) { return store.show("Fill in \(missing.name)") }
                if await store.act({ try await $0.runCommand(ch, c, args: args) }) { await loadNewest() }
                return
            }
        }
        let edit = editing, reply = replyTo
        text = ""; editing = nil; replyTo = nil; uploads = []; mentionTokens = [:]
        do {
            if let e = edit {
                try await api.edit(target, e.id, body)
                if let i = messages.firstIndex(where: { $0.id == e.id }) { messages[i].body = body; messages[i].edited_at = "now" }
            } else {
                let m = try await api.send(target, body, replyTo: reply?.id, attachments: files)
                if !messages.contains(where: { $0.id == m.id }) { messages.append(m) }
                if let r = reply { replies[String(r.id)] = ReplyPreview(id: r.id, sender: r.sender, body: r.body) }
            }
        } catch {
            self.error = error.localizedDescription
            text = body
        }
    }

    func delete(_ m: Message) async {
        do {
            try await api.delete(target, m.id)
            messages.removeAll { $0.id == m.id }
        } catch { self.error = error.localizedDescription }
    }

    func react(_ m: Message, _ emoji: String) async {
        let mine = m.reactions?.first(where: { $0.emoji == emoji })?.me ?? false
        do { try await api.react(target, m.id, emoji, on: !mine) } catch { self.error = error.localizedDescription; return }
        guard let i = messages.firstIndex(where: { $0.id == m.id }) else { return }
        var rs = messages[i].reactions ?? []
        let count = (rs.first(where: { $0.emoji == emoji })?.count ?? 0) + (mine ? -1 : 1)
        rs.removeAll { $0.emoji == emoji }
        if count > 0 { rs.append(Reaction(emoji: emoji, count: count, me: !mine)) }
        messages[i].reactions = rs
    }
}

struct MessageRow: View {
    @EnvironmentObject var store: Store
    let message: Message
    let sender: Person?
    let grouped: Bool
    let reply: ReplyPreview?
    let who: (String?) -> Person?
    let mine: Bool
    let settings: Settings
    let me: String?
    let canPin: Bool
    let canDelete: Bool
    let onReply: () -> Void, onEdit: () -> Void, onDelete: () -> Void
    let onPin: (Bool) -> Void
    let onReact: (String) -> Void
    let onProfile: () -> Void

    var body: some View {
        let compact = settings.compact == true
        let avatars = settings.show_avatars != false && !compact
        let pinged = !mine && me != nil && (message.mentions ?? []).contains(me!)
        if message.kind == "system" {
            Text("→ \(message.body)").font(.footnote).foregroundStyle(JaceColor.muted).padding(.horizontal, 16).padding(.vertical, 6)
        } else {
            HStack(alignment: .top, spacing: 10) {
                if avatars {
                    if grouped { Color.clear.frame(width: 40, height: 1) }
                    else { Avatar(person: sender ?? Person(uuid: message.sender ?? "", name: "?"), size: 40).onTapGesture(perform: onProfile) }
                }
                VStack(alignment: .leading, spacing: 3) {
                    if let r = reply {
                        Text("↪ \(who(r.sender)?.shownName ?? "Someone"): \(r.body.isEmpty ? "(attachment)" : plain(r.body))")
                            .font(.footnote).foregroundStyle(JaceColor.muted).lineLimit(1)
                    }
                    if !grouped || compact {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            if compact { Text(timeOf(message.created_at, settings)).font(.caption2).foregroundStyle(JaceColor.muted) }
                            Text(sender?.shownName ?? "Someone").fontWeight(.semibold).onTapGesture(perform: onProfile)
                            if sender?.is_bot == true { BotTag() }
                            if !compact { Text(timeOf(message.created_at, settings)).font(.caption2).foregroundStyle(JaceColor.muted) }
                            if message.pinned_at != nil { Text("📌").font(.caption2) }
                        }
                    }
                    if !message.body.isEmpty {
                        (Text(rich(message.body, who: who, me: me)) + Text(message.edited_at != nil ? " (edited)" : "").foregroundColor(JaceColor.muted))
                            .tint(JaceColor.green)
                    }
                    ForEach(message.attachments ?? [], id: \.self) { AttachmentView(attachment: $0) }
                    if settings.show_embeds != false {
                        ForEach(message.embeds ?? [], id: \.self) { e in
                            if e.title != nil || e.description != nil || e.image != nil {
                                VStack(alignment: .leading, spacing: 2) {
                                    if let s = e.site { Text(s).font(.caption2).foregroundStyle(JaceColor.muted) }
                                    if let t = e.title { Text(t).fontWeight(.semibold).foregroundStyle(JaceColor.green) }
                                    if let d = e.description { Text(d).font(.footnote).lineLimit(3) }
                                    if let img = e.image, let u = URL(string: img) {
                                        AsyncImage(url: u) { (i: Image) in i.resizable().scaledToFit() } placeholder: { JaceColor.panel }
                                            .frame(maxWidth: 240, maxHeight: 180).clipShape(RoundedRectangle(cornerRadius: 6))
                                    }
                                }
                                .padding(10).background(JaceColor.panel, in: RoundedRectangle(cornerRadius: 8))
                                .onTapGesture { if let u = URL(string: e.url) { store.open(u) } }
                            }
                        }
                    }
                    if let rs = message.reactions, !rs.isEmpty {
                        HStack(spacing: 4) {
                            ForEach(rs, id: \.emoji) { r in
                                Button { onReact(r.emoji) } label: { Text("\(r.emoji) \(r.count)").font(.footnote) }
                                    .padding(.horizontal, 7).padding(.vertical, 2)
                                    .background(r.me ? JaceColor.green.opacity(0.18) : JaceColor.panel, in: RoundedRectangle(cornerRadius: 8))
                                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(r.me ? JaceColor.green : .clear))
                                    .buttonStyle(.plain)
                            }
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12).padding(.top, grouped || compact ? 1 : 10).padding(.bottom, 1)
            .background(pinged ? JaceColor.yellow.opacity(0.12) : .clear)
            .contentShape(Rectangle())
            .contextMenu {
                ControlGroup { ForEach(quickReactions, id: \.self) { e in Button(e) { onReact(e) } } }
                Button { onReply() } label: { Label("Reply", systemImage: "arrowshape.turn.up.left") }
                Button { UIPasteboard.general.string = message.body; store.show("Copied") } label: { Label("Copy text", systemImage: "doc.on.doc") }
                if canPin { Button { onPin(message.pinned_at == nil) } label: { Label(message.pinned_at == nil ? "Pin" : "Unpin", systemImage: "pin") } }
                if mine { Button { onEdit() } label: { Label("Edit", systemImage: "pencil") } }
                if canDelete { Button(role: .destructive) { onDelete() } label: { Label("Delete", systemImage: "trash") } }
                if settings.developer == true { Button { UIPasteboard.general.string = String(message.id) } label: { Label("Copy message id", systemImage: "number") } }
            }
        }
    }
}

struct AttachmentView: View {
    @Environment(\.openURL) private var openURL
    let attachment: Attachment

    var body: some View {
        if let u = URL(string: attachment.url) {
            if attachment.type.hasPrefix("image/") {
                AsyncImage(url: u) { (image: Image) in
                    image.resizable().scaledToFit()
                } placeholder: {
                    JaceColor.panel
                }
                .frame(maxWidth: 260, maxHeight: 260)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .onTapGesture { UIApplication.shared.open(u) }
            } else {
                Button("📎 \(attachment.name) (\((attachment.size ?? 0) / 1024) KB)") { UIApplication.shared.open(u) }
                    .foregroundStyle(JaceColor.green).buttonStyle(.plain)
            }
        }
    }
}

/// A message without its formatting marks, for one-line previews.
func plain(_ body: String) -> String {
    body.replacingOccurrences(of: "(\\*\\*|__|~~|[*`])", with: "", options: .regularExpression)
}

/// The website's message formatting, the common part: **bold**, *italic*, `code`, ~~strike~~, links, @mentions.
func rich(_ body: String, who: (String?) -> Person?, me: String? = nil) -> AttributedString {
    // @mentions first (<@uuid> -> @Name), then Markdown, which also makes bare links clickable
    var text = body
    if let re = try? NSRegularExpression(pattern: "<@([0-9a-f]{32})>") {
        for m in re.matches(in: body, range: NSRange(body.startIndex..., in: body)).reversed() {
            if let r = Range(m.range, in: text), let idr = Range(m.range(at: 1), in: body) {
                text.replaceSubrange(r, with: "**@\(who(String(body[idr]))?.shownName ?? "someone")**")
            }
        }
    }
    let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
    var out = (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
    // bare links
    if let re = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) {
        let plain = String(out.characters)
        for m in re.matches(in: plain, range: NSRange(plain.startIndex..., in: plain)) {
            guard let url = m.url, let r = Range(m.range, in: plain),
                  let lo = AttributedString.Index(r.lowerBound, within: out), let hi = AttributedString.Index(r.upperBound, within: out)
            else { continue }
            out[lo..<hi].link = url
        }
    }
    return out
}

/// The server's timestamps ("2026-10-12T12:00:00.123+00:00" or "...Z")
func parseTime(_ iso: String) -> Date? {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let d = f.date(from: iso) { return d }
    f.formatOptions = [.withInternetDateTime]
    return f.date(from: iso)
}

func timeOf(_ iso: String, _ settings: Settings? = nil) -> String {
    guard let d = parseTime(iso) else { return "" }
    if settings?.time_format == "24h" {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: d)
    }
    return d.formatted(date: .omitted, time: .shortened)
}
