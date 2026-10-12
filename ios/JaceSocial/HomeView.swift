import SwiftUI

/// For now: your friends with what they're doing. (Chats, servers and the rest come next.)
struct HomeView: View {
    @EnvironmentObject var session: Session
    @State private var data: FriendsData?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if let e = error {
                    Text(e).foregroundStyle(JaceColor.red)
                } else if let d = data {
                    if !d.incoming.isEmpty {
                        Section("Friend requests — \(d.incoming.count)") {
                            ForEach(d.incoming) { PersonRow(person: $0, line: "Wants to be friends") }
                        }
                    }
                    let online = d.friends.filter { $0.online }
                    let offline = d.friends.filter { !$0.online }
                    Section("Online — \(online.count)") { ForEach(online) { PersonRow(person: $0, line: describe($0)) } }
                    Section("Offline — \(offline.count)") { ForEach(offline) { PersonRow(person: $0, line: "Offline") } }
                } else {
                    Text("Loading…").foregroundStyle(JaceColor.muted)
                }
            }
            .scrollContentBackground(.hidden)
            .background(JaceColor.bg)
            .navigationTitle("Friends")
            .toolbar {
                Button { Task { await session.signOut() } } label: { Image(systemName: "rectangle.portrait.and.arrow.right") }
                    .accessibilityLabel("Sign out")
            }
            .refreshable { await load() }
            .task { await load() }
        }
    }

    func load() async {
        do {
            data = try await session.api.friends()
            error = nil
        } catch let e as ApiError where e.status == 401 {
            session.expired()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct PersonRow: View {
    let person: Person
    let line: String

    var body: some View {
        HStack(spacing: 12) {
            Avatar(person: person, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(person.shownName).fontWeight(.semibold).lineLimit(1)
                Text(line).font(.footnote).foregroundStyle(JaceColor.muted).lineLimit(1)
            }
            Spacer()
            if let n = person.unread, n > 0 {
                Text("\(n)").font(.caption2.bold()).padding(.horizontal, 7).padding(.vertical, 2).background(JaceColor.red, in: Capsule())
            }
        }
        .listRowBackground(JaceColor.bg)
    }
}

struct Avatar: View {
    let person: Person
    let size: CGFloat

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            AsyncImage(url: person.avatar_url.flatMap(URL.init(string:))) { img in
                img.resizable().scaledToFill()
            } placeholder: {
                ZStack { JaceColor.panel; Text(person.name.prefix(1).uppercased()).bold() }
            }
            .frame(width: size, height: size)
            .clipShape(Circle())
            Circle().fill(JaceColor.status(person.online ? person.status : "offline"))
                .frame(width: size / 3.5, height: size / 3.5)
                .overlay(Circle().stroke(JaceColor.bg, lineWidth: 2))
        }
    }
}

/// "Playing Minecraft 1.21.1", "building", or "Online" (like the website)
func describe(_ p: Person) -> String {
    if let a = p.activity, a.type == "playing" { return "Playing Minecraft \(a.version ?? "")".trimmingCharacters(in: .whitespaces) }
    if let a = p.activity, a.type == "hosting" { return "Hosting a world" }
    if let s = p.custom_status, !s.isEmpty { return [p.status_emoji, s].compactMap { $0 }.joined(separator: " ") }
    switch p.status {
    case "idle": return "Idle"
    case "dnd": return "Do not disturb"
    default: return "Online"
    }
}
