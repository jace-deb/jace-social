import SwiftUI

private let quickReactions = ["👍", "❤️", "😂", "😮", "😢", "🎉"]

/// One conversation: a friend, a group chat or a server channel.
struct ChatView: View {
    @EnvironmentObject var store: Store
    let target: ChatTarget
    let title: String
    @State private var messages: [Message] = []
    @State private var replies: [String: ReplyPreview] = [:]
    @State private var people: [String: Person] = [:]
    @State private var error: String?
    @State private var loaded = false
    @State private var olderDone = false
    @State private var text = ""
    @State private var replyTo: Message?
    @State private var editing: Message?
    @State private var lastTyping = Date.distantPast
    @FocusState private var focused: Bool

    var api: Api { store.session.api }

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
                            MessageRow(message: m, sender: who(m.sender), grouped: grouped(prev, m),
                                       reply: m.reply_to.flatMap { replies[String($0)] }, who: who,
                                       mine: m.sender == store.me?.uuid,
                                       onReply: { replyTo = m; editing = nil; focused = true },
                                       onEdit: { editing = m; replyTo = nil; text = m.body; focused = true },
                                       onDelete: { Task { await delete(m) } },
                                       onReact: { e in Task { await react(m, e) } })
                            .id(m.id)
                        }
                    }
                    .padding(.vertical, 8)
                }
                .onChange(of: messages.last?.id) { id in if let id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } } }
            }
            composer
        }
        .background(JaceColor.bg)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(title).font(.headline).lineLimit(1)
                    if let names = store.typing[target.key], !names.isEmpty {
                        Text("\(names.sorted().joined(separator: ", ")) \(names.count == 1 ? "is" : "are") typing…")
                            .font(.caption2).foregroundStyle(JaceColor.muted)
                    }
                }
            }
        }
        .task(id: target) { await loadNewest() }
        .onReceive(store.live.events) { e in
            let here: Bool
            switch target {
            case .direct(let u): here = ["message", "dm_update"].contains(e.event) && (e.str("from") == u || e.str("with") == u)
            case .channel(let c): here = e.event == "channel" && e.str("channel_id") == c
            }
            if here { Task { await loadNewest() } }
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
            HStack(alignment: .bottom, spacing: 8) {
                TextField("Message \(title)", text: $text, axis: .vertical)
                    .lineLimit(1...6)
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
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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

    func loadNewest() async {
        do {
            let page = try await api.messages(target)
            let first = page.messages.first?.id ?? 0
            var byId = Dictionary(uniqueKeysWithValues: messages.filter { $0.id < first }.map { ($0.id, $0) })
            for m in page.messages { byId[m.id] = m }
            messages = byId.values.sorted { $0.id < $1.id }
            replies.merge(page.replies ?? [:]) { $1 }
            people.merge(page.people ?? [:]) { $1 }
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
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        let edit = editing, reply = replyTo
        text = ""; editing = nil; replyTo = nil
        do {
            if let e = edit {
                try await api.edit(target, e.id, body)
                if let i = messages.firstIndex(where: { $0.id == e.id }) { messages[i].body = body; messages[i].edited_at = "now" }
            } else {
                let m = try await api.send(target, body, replyTo: reply?.id)
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
    let message: Message
    let sender: Person?
    let grouped: Bool
    let reply: ReplyPreview?
    let who: (String?) -> Person?
    let mine: Bool
    let onReply: () -> Void, onEdit: () -> Void, onDelete: () -> Void
    let onReact: (String) -> Void

    var body: some View {
        if message.kind == "system" {
            Text("→ \(message.body)").font(.footnote).foregroundStyle(JaceColor.muted).padding(.horizontal, 16).padding(.vertical, 6)
        } else {
            HStack(alignment: .top, spacing: 10) {
                if grouped { Color.clear.frame(width: 40, height: 1) }
                else { Avatar(person: sender ?? Person(uuid: message.sender ?? "", name: "?"), size: 40) }
                VStack(alignment: .leading, spacing: 3) {
                    if let r = reply {
                        Text("↪ \(who(r.sender)?.shownName ?? "Someone"): \(r.body.isEmpty ? "(attachment)" : r.body)")
                            .font(.footnote).foregroundStyle(JaceColor.muted).lineLimit(1)
                    }
                    if !grouped {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(sender?.shownName ?? "Someone").fontWeight(.semibold)
                            if sender?.is_bot == true { Text("BOT").font(.caption2.bold()).foregroundStyle(JaceColor.green) }
                            Text(timeOf(message.created_at)).font(.caption2).foregroundStyle(JaceColor.muted)
                        }
                    }
                    if !message.body.isEmpty {
                        (Text(rich(message.body, who: who)) + Text(message.edited_at != nil ? " (edited)" : "").foregroundColor(JaceColor.muted))
                            .tint(JaceColor.green)
                    }
                    ForEach(message.attachments ?? [], id: \.self) { AttachmentView(attachment: $0) }
                    ForEach(message.embeds ?? [], id: \.self) { e in
                        if e.title != nil || e.description != nil {
                            VStack(alignment: .leading, spacing: 2) {
                                if let s = e.site { Text(s).font(.caption2).foregroundStyle(JaceColor.muted) }
                                if let t = e.title { Text(t).fontWeight(.semibold).foregroundStyle(JaceColor.green) }
                                if let d = e.description { Text(d).font(.footnote).lineLimit(3) }
                            }
                            .padding(10).background(JaceColor.panel, in: RoundedRectangle(cornerRadius: 8))
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
            .padding(.horizontal, 12).padding(.top, grouped ? 1 : 10).padding(.bottom, 1)
            .contentShape(Rectangle())
            .contextMenu {
                ControlGroup { ForEach(quickReactions, id: \.self) { e in Button(e) { onReact(e) } } }
                Button { onReply() } label: { Label("Reply", systemImage: "arrowshape.turn.up.left") }
                Button { UIPasteboard.general.string = message.body } label: { Label("Copy text", systemImage: "doc.on.doc") }
                if mine {
                    Button { onEdit() } label: { Label("Edit", systemImage: "pencil") }
                    Button(role: .destructive) { onDelete() } label: { Label("Delete", systemImage: "trash") }
                }
            }
        }
    }
}

struct AttachmentView: View {
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
            } else {
                SwiftUI.Link("📎 \(attachment.name)", destination: u).foregroundStyle(JaceColor.green)
            }
        }
    }
}

/// The website's message formatting, the common part: **bold**, *italic*, `code`, ~~strike~~, links, @mentions.
func rich(_ body: String, who: (String?) -> Person?) -> AttributedString {
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

func timeOf(_ iso: String) -> String {
    guard let d = parseTime(iso) else { return "" }
    return d.formatted(date: .omitted, time: .shortened)
}
