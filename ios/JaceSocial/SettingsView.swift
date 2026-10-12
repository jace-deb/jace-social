import SwiftUI

/// Settings -> My profile, Status, Appearance, Notifications, Links & privacy, Advanced, Accounts.
struct SettingsView: View {
    let tab: String
    var body: some View {
        switch tab {
        case "profile": ProfileSettings()
        case "status": StatusSettings()
        case "appearance": AppearanceSettings()
        case "notifications": NotificationSettings()
        case "links": LinkSettings()
        case "advanced": AdvancedSettings()
        default: AccountSettings()
        }
    }
}

private struct ProfileSettings: View {
    @EnvironmentObject var store: Store
    @State private var name = ""
    @State private var pronouns = ""
    @State private var bio = ""
    @State private var accent = "#3ddc84"
    @State private var links: [Link] = []
    @State private var loaded = false
    @State private var busy = false

    var body: some View {
        Form {
            if let me = store.me {
                Section {
                    ZStack(alignment: .bottomTrailing) {
                        AsyncImage(url: me.banner_url.flatMap(URL.init(string:))) { $0.resizable().scaledToFill() } placeholder: { hex(accent) }
                            .frame(height: 90).frame(maxWidth: .infinity).clipShape(RoundedRectangle(cornerRadius: 10))
                        HStack {
                            PhotoButton(onPick: { p in Task { if await store.act({ try await $0.setBanner(p.data, type: p.type) }) { await store.refreshMe() } } }) {
                                Text("Change banner").padding(6).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                            }
                            if me.banner_url != nil {
                                Button("Remove") { Task { if await store.act({ try await $0.removeBanner() }) { await store.refreshMe() } } }
                                    .padding(6).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                            }
                        }
                        .padding(8)
                    }
                    HStack(spacing: 14) {
                        Avatar(person: me.asPerson, size: 64)
                        VStack(alignment: .leading, spacing: 6) {
                            PhotoButton(onPick: { p in Task { if await store.act({ try await $0.setAvatar(p.data, type: p.type) }) { await store.refreshMe() } } }) { Text("Change picture") }
                            if let a = me.avatar_url, !a.contains("mc-heads.net") {
                                Button("Remove picture") { Task { if await store.act({ try await $0.removeAvatar() }) { await store.refreshMe() } } }
                            }
                        }
                        .buttonStyle(.borderless)
                    }
                }
                .rowBg()
                Section("Display name") { TextField(me.minecraft_name ?? me.jace_name ?? "", text: $name.max(32)) }.rowBg()
                Section("Pronouns") { TextField("", text: $pronouns.max(24)) }.rowBg()
                Section("About me") { TextField("", text: $bio.max(300), axis: .vertical).lineLimit(3...8) }.rowBg()
                Section("Profile color") { Swatches(selected: accent) { accent = $0 ?? "#3ddc84" } }.rowBg()
                Section("Links (up to 5)") {
                    ForEach(links.indices, id: \.self) { i in
                        HStack {
                            TextField("Name", text: Binding(get: { links[i].label }, set: { links[i].label = String($0.prefix(32)) })).frame(maxWidth: 110)
                            TextField("https://…", text: Binding(get: { links[i].url }, set: { links[i].url = String($0.prefix(300)) }))
                                .textInputAutocapitalization(.never).keyboardType(.URL)
                            Button { links.remove(at: i) } label: { Image(systemName: "xmark") }.buttonStyle(.borderless)
                        }
                    }
                    if links.count < 5 { Button("+ Add link") { links.append(Link()) } }
                }
                .rowBg()
                Section {
                    Button(busy ? "Saving…" : "Save profile") {
                        busy = true
                        Task {
                            await store.act {
                                store.me = try await $0.updateMe(["display_name": name, "pronouns": pronouns, "bio": bio, "accent_color": accent,
                                    "links": links.filter { !$0.url.trimmingCharacters(in: .whitespaces).isEmpty }.map { ["label": $0.label, "url": $0.url] }])
                                store.show("Saved!")
                            }
                            busy = false
                        }
                    }
                    .disabled(busy)
                }
                .rowBg()
            }
        }
        .jaceList()
        .navigationTitle("My profile")
        .onAppear {
            guard !loaded, let me = store.me else { return }
            loaded = true
            name = me.display_name ?? ""; pronouns = me.pronouns ?? ""; bio = me.bio ?? ""
            accent = me.accent_color ?? "#3ddc84"; links = (me.links ?? []).isEmpty ? [Link()] : me.links ?? []
        }
    }
}

extension Binding where Value == String {
    /// At most n characters.
    func max(_ n: Int) -> Binding<String> { Binding(get: { wrappedValue }, set: { wrappedValue = String($0.prefix(n)) }) }
}

private struct StatusSettings: View {
    @EnvironmentObject var store: Store
    @State private var custom = ""
    @State private var emoji = ""
    var body: some View {
        Form {
            Section {
                ForEach([("online", "Show that you're around"), ("idle", "Show that you're away"), ("dnd", "No notification pop-ups"),
                         ("invisible", "You'll look offline, but can still use everything")], id: \.0) { s, sub in
                    Button { store.updateMe(["status": s]) } label: {
                        HStack(spacing: 12) {
                            Circle().fill(JaceColor.status(s == "invisible" ? "offline" : s)).frame(width: 14, height: 14)
                            VStack(alignment: .leading) { Text(statusLabel(s)).fontWeight(.semibold).foregroundStyle(JaceColor.text); Text(sub).font(.footnote).foregroundStyle(JaceColor.muted) }
                            Spacer()
                            if store.me?.status == s { Image(systemName: "checkmark").foregroundStyle(JaceColor.green) }
                        }
                    }
                }
            }
            .rowBg()
            Section("Custom status") {
                HStack { TextField("😀", text: $emoji.max(16)).frame(width: 50); TextField("What's up?", text: $custom.max(80)) }
                Button("Set") { store.updateMe(["custom_status": custom, "status_emoji": emoji], saved: "Status set") }
                Button("Clear") { custom = ""; emoji = ""; store.updateMe(["custom_status": NSNull(), "status_emoji": NSNull()]) }
            }
            .rowBg()
        }
        .jaceList()
        .navigationTitle("Status")
        .onAppear { custom = store.me?.custom_status ?? ""; emoji = store.me?.status_emoji ?? "" }
    }
}

private struct AppearanceSettings: View {
    @EnvironmentObject var store: Store
    @State private var scale = 1.0
    var body: some View {
        let s = store.me?.prefs ?? Settings()
        Form {
            Section("Theme") {
                ForEach(THEMES, id: \.0) { id, label in
                    Button { store.saveSettings(["theme": id]) } label: {
                        HStack { Text(label).foregroundStyle(JaceColor.text); Spacer(); if (s.theme ?? "dark") == id { Image(systemName: "checkmark").foregroundStyle(JaceColor.green) } }
                    }
                }
            }
            .rowBg()
            Section("Accent color") { Swatches(selected: s.accent ?? "#3ddc84") { store.saveSettings(["accent": orNull($0)]) } }.rowBg()
            Section("Text size: \(Int(scale * 100))%") {
                Slider(value: $scale, in: 0.8...1.4, step: 0.05, onEditingChanged: { editing in if !editing { store.saveSettings(["font_scale": scale]) } })
            }
            .rowBg()
            Section {
                ToggleRow(label: "Compact messages", desc: "Fit more messages on screen", isOn: s.compact == true) { store.saveSettings(["compact": $0]) }
                ToggleRow(label: "Show pictures next to messages", isOn: s.show_avatars != false) { store.saveSettings(["show_avatars": $0]) }
                ToggleRow(label: "Show link previews", isOn: s.show_embeds != false) { store.saveSettings(["show_embeds": $0]) }
                ToggleRow(label: "Less motion", desc: "Turn off animations", isOn: s.reduce_motion == true) { store.saveSettings(["reduce_motion": $0]) }
                ToggleRow(label: "24-hour clock", isOn: s.time_format == "24h") { store.saveSettings(["time_format": $0 ? "24h" : "12h"]) }
                ToggleRow(label: "Return sends the message", desc: "Off: Return makes a new line, and you tap Send", isOn: s.send_on_enter != false) { store.saveSettings(["send_on_enter": $0]) }
            }
            .rowBg()
        }
        .jaceList()
        .navigationTitle("Appearance")
        .onAppear { scale = store.me?.prefs.font_scale ?? 1 }
    }
}

private struct NotificationSettings: View {
    @EnvironmentObject var store: Store
    var body: some View {
        let s = store.me?.prefs ?? Settings()
        Form {
            Section("Notify me about") {
                ForEach([("all", "All messages"), ("mentions", "Only direct messages, @mentions and replies"), ("none", "Nothing")], id: \.0) { v, label in
                    Button { store.saveSettings(["notify": v]) } label: {
                        HStack { Text(label).foregroundStyle(JaceColor.text); Spacer(); if (s.notify ?? "all") == v { Image(systemName: "checkmark").foregroundStyle(JaceColor.green) } }
                    }
                }
            }
            .rowBg()
            Section {
                ToggleRow(label: "Pop-up notifications", desc: "When Jace Social isn't the app you're using", isOn: s.desktop_notifications != false) { store.saveSettings(["desktop_notifications": $0]) }
                ToggleRow(label: "Sounds", desc: "For messages", isOn: s.sounds != false) { store.saveSettings(["sounds": $0]) }
            } footer: { Text("Do Not Disturb (in your status) turns notifications off. Long-press a chat to mute just that one.") }
            .rowBg()
        }
        .jaceList()
        .navigationTitle("Notifications")
    }
}

private struct LinkSettings: View {
    @EnvironmentObject var store: Store
    @State private var domain = ""
    var body: some View {
        let s = store.me?.prefs ?? Settings()
        let trusted = s.trusted_domains ?? []
        Form {
            Section {
                ToggleRow(label: "Warn me before opening links", desc: "Shows where a link really goes before you leave Jace Social", isOn: s.link_warning != false) {
                    store.saveSettings(["link_warning": $0])
                }
            }
            .rowBg()
            Section {
                if trusted.isEmpty { Text("None yet. You can trust a site from the warning, too.").foregroundStyle(JaceColor.muted) }
                ForEach(trusted, id: \.self) { d in
                    HStack { Text(d); Spacer(); Button { store.saveSettings(["trusted_domains": trusted.filter { $0 != d }]) } label: { Image(systemName: "xmark") }.buttonStyle(.borderless) }
                }
                HStack {
                    TextField("example.com", text: $domain).textInputAutocapitalization(.never).keyboardType(.URL)
                    Button("Trust") {
                        var d = domain.trimmingCharacters(in: .whitespaces).lowercased()
                        for p in ["https://", "http://"] where d.hasPrefix(p) { d.removeFirst(p.count) }
                        d = String(d.split(separator: "/").first ?? "")
                        if !d.isEmpty { store.saveSettings(["trusted_domains": Array(Set(trusted + [d]))]) }
                        domain = ""
                    }
                    .disabled(domain.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } header: { Text("Trusted sites (no warning)") } footer: { Text("Link previews are fetched by the Jace Social server, so websites you see previews of don't get your IP address.") }
            .rowBg()
        }
        .jaceList()
        .navigationTitle("Links & privacy")
    }
}

private struct AdvancedSettings: View {
    @EnvironmentObject var store: Store
    var body: some View {
        Form {
            Section {
                ToggleRow(label: "Developer mode", desc: "Show buttons to copy ids of messages, people, channels and servers (useful for bots)",
                          isOn: store.me?.prefs.developer == true) { store.saveSettings(["developer": $0]) }
                if store.me?.prefs.developer == true, let id = store.me?.uuid {
                    Text("Your id: \(id)").font(.footnote.monospaced()).textSelection(.enabled)
                    Button("Copy") { UIPasteboard.general.string = id; store.show("Copied") }
                }
            }
            .rowBg()
        }
        .jaceList()
        .navigationTitle("Advanced")
    }
}

private struct AccountSettings: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var session: Session
    @State private var note = ""
    @State private var device: DeviceCode?
    @State private var linkURL: URL?
    @State private var unlink = false

    var body: some View {
        Form {
            Section {
                ForEach(session.accounts) { a in
                    Button {
                        guard a.uuid != store.me?.uuid else { return }
                        store.stop(); session.switchTo(a.uuid)
                    } label: {
                        HStack {
                            Avatar(person: Person(uuid: a.uuid, name: a.name, online: true, status: "online", avatar_url: a.avatar_url, is_bot: a.is_bot), size: 36)
                            VStack(alignment: .leading) {
                                HStack { Text(a.name).fontWeight(.semibold).foregroundStyle(JaceColor.text); if a.is_bot == true { BotTag() } }
                                Text(a.uuid == store.me?.uuid ? "Signed in now" : "Tap to switch").font(.footnote).foregroundStyle(JaceColor.muted)
                            }
                            Spacer()
                            if a.uuid != store.me?.uuid { Button { session.forget(a.uuid) } label: { Image(systemName: "xmark") }.buttonStyle(.borderless) }
                        }
                    }
                }
                Button("+ Add another account") { store.stop(); session.addAnother() }
            } header: { Text("Switch accounts") } footer: { Text("Accounts you've signed in with on this phone, and bots you switched to.") }
            .rowBg()
            if store.me?.is_bot != true, let me = store.me {
                Section {
                    HStack {
                        VStack(alignment: .leading) { Text("Jace").bold(); Text(me.jace_linked == true ? "@\(me.jace_name ?? "")" : "Not linked").font(.footnote).foregroundStyle(JaceColor.muted) }
                        Spacer()
                        if me.jace_linked == true {
                            if me.minecraft_linked == true { Button("Unlink") { unlink = true }.buttonStyle(.borderless) }
                        } else { Button("Link Jace") { linkJace() }.buttonStyle(.borderless) }
                    }
                    HStack {
                        VStack(alignment: .leading) { Text("Minecraft").bold(); Text(me.minecraft_linked == true ? me.minecraft_name ?? "" : "Not linked").font(.footnote).foregroundStyle(JaceColor.muted) }
                        Spacer()
                        if me.minecraft_linked != true && device == nil { Button("Link Minecraft") { linkMinecraft() }.buttonStyle(.borderless) }
                    }
                    if let d = device {
                        Text("Go to \(d.verification_uri) and enter this code:")
                        Text(d.user_code).font(.title.monospaced().bold()).textSelection(.enabled)
                        Button("Copy code and open") {
                            UIPasteboard.general.string = d.user_code
                            if let u = URL(string: d.verification_uri) { UIApplication.shared.open(u) }
                        }
                        Button("Cancel", role: .cancel) { device = nil }
                    }
                    if !note.isEmpty { Text(note).foregroundStyle(JaceColor.muted) }
                } header: { Text("Linked accounts") } footer: { Text("Link both to sign in either way. Your friends, chats and servers stay with you.") }
                .rowBg()
            }
        }
        .jaceList()
        .navigationTitle("Accounts")
        .sheet(item: $linkURL) { SafariView(url: $0) }
        .confirmationDialog("Unlink your Jace account?", isPresented: $unlink, titleVisibility: .visible) {
            Button("Unlink", role: .destructive) { Task { if await store.act({ try await $0.unlinkJace() }) { await store.refreshMe() } } }
        } message: { Text("You'll sign in with Minecraft only.") }
    }

    func linkJace() {
        Task {
            do {
                let start = try await store.api.linkJace()
                linkURL = URL(string: start.url)
                note = "Finish in the browser…"
                for _ in 0..<150 {
                    try await Task.sleep(nanoseconds: 2_000_000_000)
                    if try await store.api.pollLinkJace(start) { linkURL = nil; await store.refreshMe(); note = "Jace linked!"; return }
                }
                note = "Linking timed out - try again"
            } catch { note = ""; store.show(error.localizedDescription) }
        }
    }

    func linkMinecraft() {
        Task {
            do {
                let d = try await store.api.startLinkMinecraft()
                device = d
                while device == d {
                    try await Task.sleep(nanoseconds: UInt64(d.interval ?? 5) * 1_000_000_000)
                    if try await store.api.pollLinkMinecraft(d.ticket) { device = nil; await store.refreshMe(); note = "Minecraft linked!" }
                }
            } catch { device = nil; store.show(error.localizedDescription) }
        }
    }
}
