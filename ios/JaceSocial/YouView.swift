import SwiftUI

/// You: your profile card, status, settings, accounts, bots, updates and signing out.
struct YouView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var session: Session
    @EnvironmentObject var router: Router
    @State private var checking = false
    @State private var signOut = false

    var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "" }

    var body: some View {
        List {
            if let m = store.me {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        ZStack(alignment: .bottomLeading) {
                            AsyncImage(url: m.banner_url.flatMap(URL.init(string:))) { $0.resizable().scaledToFill() } placeholder: { hex(m.accent_color, JaceColor.panel) }
                                .frame(height: 90).frame(maxWidth: .infinity).clipped()
                            Avatar(person: m.asPerson, size: 72).offset(x: 12, y: 36)
                        }
                        .padding(.bottom, 36)
                        Text(m.shownName).font(.title2.bold())
                        Text([m.jace_name.map { "@\($0)" }, m.minecraft_name.map { "⛏ \($0)" }, m.pronouns].compactMap { $0 }.joined(separator: " · "))
                            .font(.footnote).foregroundStyle(JaceColor.muted)
                        let custom = [m.status_emoji, m.custom_status].compactMap { $0 }.joined(separator: " ")
                        if !custom.isEmpty { Text(custom) }
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(JaceColor.bg)
                }
            }
            Section {
                row("My profile", "person", .settings("profile"))
                row("Status: \(statusLabel(store.me?.status ?? "online"))", "circle.fill", .settings("status"))
            }
            Section {
                row("Appearance", "paintpalette", .settings("appearance"))
                row("Notifications", "bell", .settings("notifications"))
                row("Links & privacy", "link", .settings("links"))
                row("Advanced", "gearshape", .settings("advanced"))
            }
            Section {
                row(store.me?.is_bot == true ? "Switch accounts" : "Accounts", "arrow.left.arrow.right", .settings("accounts"))
                if store.me?.is_bot != true {
                    row("My bots", "cpu", .bots)
                    row("Welcome tour", "hand.wave", .welcome)
                }
            }
            Section {
                Text("Jace Social \(version)").foregroundStyle(JaceColor.muted)
                if let u = store.update {
                    Button("Update to \(u.version)") { if let url = URL(string: u.page) { UIApplication.shared.open(url) } }
                } else {
                    Button(checking ? "Checking…" : "Check for updates") {
                        checking = true
                        Task { if let msg = await store.checkUpdate() { store.show(msg) }; checking = false }
                    }
                    .disabled(checking)
                }
                ToggleRow(label: "Check for updates on startup", isOn: store.autoUpdate) { store.autoUpdate = $0; store.objectWillChange.send() }
                Button("Sign out", role: .destructive) { signOut = true }
            }
            .rowBg()
        }
        .jaceList()
        .navigationTitle("You")
        .confirmationDialog("Sign out?", isPresented: $signOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { store.stop(); await session.signOut() } }
        } message: { Text("You can sign back in with Jace or Minecraft.") }
    }

    func row(_ label: String, _ icon: String, _ r: Route) -> some View {
        NavigationLink(value: r) { Label(label, systemImage: icon) }.rowBg()
    }
}
