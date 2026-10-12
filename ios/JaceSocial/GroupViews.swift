import SwiftUI

/// Start a group chat with up to 9 friends.
struct NewGroupView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    @State private var name = ""
    @State private var picked: [String] = []
    var body: some View {
        Form {
            Section { TextField("Group name (optional)", text: $name.max(48)) } footer: { Text("Pick up to 9 friends.") }.rowBg()
            Section {
                let friends = (store.friends?.friends ?? []).sorted { $0.shownName.lowercased() < $1.shownName.lowercased() }
                if friends.isEmpty { Text("Add some friends first.").foregroundStyle(JaceColor.muted) }
                ForEach(friends) { p in
                    Button {
                        if let i = picked.firstIndex(of: p.uuid) { picked.remove(at: i) } else if picked.count < 9 { picked.append(p.uuid) }
                    } label: {
                        HStack { PersonRow(person: Person(uuid: p.uuid, name: p.name, online: p.online, status: p.status, avatar_url: p.avatar_url, display_name: p.display_name), line: p.online ? describe(p) : "Offline")
                            Image(systemName: picked.contains(p.uuid) ? "checkmark.circle.fill" : "circle").foregroundStyle(JaceColor.green) }
                    }
                }
            }
            .rowBg()
            Section {
                Button("Create group") {
                    Task {
                        await store.act {
                            let id = try await $0.createGroup(name.trimmingCharacters(in: .whitespaces), picked)
                            await store.refreshGroups()
                            router.back()
                            router.go(.chat(.channel(id), name.isEmpty ? "Group chat" : name))
                        }
                    }
                }
                .disabled(picked.isEmpty)
            }
            .rowBg()
        }
        .jaceList()
        .navigationTitle("New group chat")
    }
}

/// A group chat's settings: rename, picture, members (add friends, remove people as the owner), call, mute, leave.
struct GroupSettingsView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    let id: String
    @State private var renaming = false
    @State private var newName = ""
    @State private var leaving = false
    @State private var removing: Person?

    var body: some View {
        Form {
            if let g = store.groups.first(where: { $0.id == id }) {
                let me = store.me?.uuid
                let muted = store.me?.prefs.muted ?? []
                Section {
                    HStack(spacing: 12) {
                        IconPic(name: g.name, url: g.icon_url, size: 60, round: true)
                        VStack(alignment: .leading, spacing: 8) {
                            PhotoButton(onPick: { p in Task { if await store.act({ try await $0.setGroupIcon(id, p.data, type: p.type) }) { await store.refreshGroups() } } }) { Text("Change picture") }
                            Button("Rename") { newName = g.name; renaming = true }
                        }
                        .buttonStyle(.borderless)
                    }
                    ToggleRow(label: "Mute this group", desc: "No notifications for it (mentions still come through)", isOn: muted.contains(id)) { on in
                        store.saveSettings(["muted": on ? muted + [id] : muted.filter { $0 != id }])
                    }
                    Button("📞 Start or join the group call") {
                        Task { await store.voice.join(me: me ?? "", channel: id, server: nil, name: g.name) }
                        router.go(.voice)
                    }
                }
                .rowBg()
                Section("Members — \(g.members.count)") {
                    ForEach(g.members) { m in
                        HStack {
                            NavigationLink(value: Route.profile(m.uuid, nil)) {
                                PersonRow(person: m, line: m.uuid == g.owner ? "👑 Owner" : m.online ? describe(m) : "Offline")
                            }
                            if me == g.owner && m.uuid != me { Button("Remove") { removing = m }.foregroundStyle(JaceColor.red).buttonStyle(.borderless) }
                        }
                    }
                    let addable = (store.friends?.friends ?? []).filter { f in !g.members.contains { $0.uuid == f.uuid } }
                    if !addable.isEmpty && g.members.count < 10 {
                        Menu("＋ Add a friend") {
                            ForEach(addable) { f in Button(f.shownName) { Task { if await store.act({ try await $0.addToGroup(id, f.uuid) }) { await store.refreshGroups() } } } }
                        }
                    }
                }
                .rowBg()
                Section { Button("Leave group", role: .destructive) { leaving = true } }.rowBg()
            } else {
                Text("Loading…").foregroundStyle(JaceColor.muted)
            }
        }
        .jaceList()
        .navigationTitle(store.groups.first { $0.id == id }?.name ?? "Group chat")
        .alert("Rename the group", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Rename") { Task { if await store.act({ try await $0.renameGroup(id, newName) }) { await store.refreshGroups() } } }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Leave this group?", isPresented: $leaving, titleVisibility: .visible) {
            Button("Leave", role: .destructive) {
                Task { if await store.act({ try await $0.removeFromGroup(id, store.me?.uuid ?? "") }) { await store.refreshGroups(); router.open(tab: 0) } }
            }
        }
        .confirmationDialog("Remove \(removing?.shownName ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                if let m = removing { Task { if await store.act({ try await $0.removeFromGroup(id, m.uuid) }) { await store.refreshGroups() } } }
            }
        }
    }
}
