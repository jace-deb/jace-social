import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import WebRTC

/// Every screen you can open on top of a tab.
enum Route: Hashable {
    case chat(ChatTarget, String)
    case server(String, String)
    case profile(String, String?)
    case serverSettings(String)
    case channelSettings(String, String)
    case group(String)
    case newGroup
    case addServer(String)
    case invite(String)
    case settings(String)
    case bots
    case botCode(String)
    case botBlocks(String)
    case botActivity(String, String?)
    case voice
    case welcome
}

/// Navigation: the tab you're on and what's open on top of each tab.
@MainActor
final class Router: ObservableObject {
    @Published var tab = 0
    @Published var paths = [NavigationPath(), NavigationPath(), NavigationPath()]
    func go(_ r: Route) { paths[tab].append(r) }
    func back() { if !paths[tab].isEmpty { paths[tab].removeLast() } }
    func open(tab t: Int, _ r: Route? = nil) {
        tab = t
        paths[t] = NavigationPath()
        if let r { paths[t].append(r) }
    }
}

/// A video (camera or screen) from WebRTC.
struct VideoView: UIViewRepresentable {
    let track: RTCVideoTrack
    var mirror = false
    var fill = false

    func makeUIView(context: Context) -> RTCMTLVideoView {
        let v = RTCMTLVideoView()
        v.videoContentMode = fill ? .scaleAspectFill : .scaleAspectFit
        v.clipsToBounds = true
        if mirror { v.transform = CGAffineTransform(scaleX: -1, y: 1) }
        track.add(v)
        context.coordinator.track = track
        return v
    }

    func updateUIView(_ v: RTCMTLVideoView, context: Context) {
        if context.coordinator.track !== track {
            context.coordinator.track?.remove(v)
            track.add(v)
            context.coordinator.track = track
        }
    }

    static func dismantleUIView(_ v: RTCMTLVideoView, coordinator: Coordinator) { coordinator.track?.remove(v) }
    func makeCoordinator() -> Coordinator { Coordinator() }
    final class Coordinator { var track: RTCVideoTrack? }
}

/// A picture (or initials) for a server, group or bot.
struct IconPic: View {
    let name: String
    let url: String?
    var size: CGFloat = 48
    var round = false
    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { $0.resizable().scaledToFill() } placeholder: {
            ZStack { JaceColor.panel; Text(name.split(separator: " ").compactMap { $0.first }.prefix(2).map(String.init).joined().uppercased()).bold() }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: round ? size / 2 : size / 3))
    }
}

struct BotTag: View {
    var body: some View {
        Text("BOT").font(.caption2.bold()).foregroundStyle(JaceColor.onGreen).padding(.horizontal, 4).padding(.vertical, 1)
            .background(JaceColor.green, in: RoundedRectangle(cornerRadius: 4))
    }
}

/// The accent color choices (the website's).
struct Swatches: View {
    let selected: String?
    var withNone = false
    let pick: (String?) -> Void
    var body: some View {
        HStack(spacing: 10) {
            if withNone {
                Circle().fill(JaceColor.panel).frame(width: 30, height: 30)
                    .overlay(Circle().stroke(selected == nil ? JaceColor.text : .clear, lineWidth: 2)).onTapGesture { pick(nil) }
            }
            ForEach(ACCENTS, id: \.self) { c in
                Circle().fill(hex(c)).frame(width: 30, height: 30)
                    .overlay(Circle().stroke(selected?.lowercased() == c ? JaceColor.text : .clear, lineWidth: 2))
                    .onTapGesture { pick(c) }
            }
        }
    }
}

/// A picked photo: its bytes as JPEG or PNG, and its size.
struct PickedImage { let data: Data; let type: String; let width: Int; let height: Int }

/// "Choose a picture" (avatars, icons, banners, attachments).
struct PhotoButton<Label: View>: View {
    let onPick: (PickedImage) -> Void
    @ViewBuilder let label: () -> Label
    @State private var item: PhotosPickerItem?
    var body: some View {
        PhotosPicker(selection: $item, matching: .images) { label() }
            .onChange(of: item) { it in
                guard let it else { return }
                Task {
                    if let d = try? await it.loadTransferable(type: Data.self), let img = UIImage(data: d) {
                        let isPng = it.supportedContentTypes.contains(.png)
                        let out = isPng ? (img.pngData() ?? d) : (img.jpegData(compressionQuality: 0.85) ?? d)
                        onPick(PickedImage(data: out, type: isPng ? "image/png" : "image/jpeg", width: Int(img.size.width * img.scale), height: Int(img.size.height * img.scale)))
                    }
                    item = nil
                }
            }
    }
}

/// An on/off setting with its explanation.
struct ToggleRow: View {
    let label: String
    var desc: String? = nil
    let isOn: Bool
    let set: (Bool) -> Void
    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: set)) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).fontWeight(.semibold)
                if let d = desc, !d.isEmpty { Text(d).font(.footnote).foregroundStyle(JaceColor.muted) }
            }
        }
        .tint(JaceColor.green)
    }
}

extension View {
    /// The app's list look: its own background, rows on the theme's colors.
    func jaceList() -> some View { scrollContentBackground(.hidden).background(JaceColor.bg) }
    func rowBg() -> some View { listRowBackground(JaceColor.panel) }
}

/// "2 min", "3 h 5 min", "4 days" (since an ISO time).
func since(_ iso: String?) -> String {
    guard let iso, let t = parseTime(iso) else { return "" }
    let s = max(0, Int(Date().timeIntervalSince(t)))
    if s < 60 { return "just now" }
    if s < 3600 { return "\(s / 60) min" }
    if s < 86400 { return "\(s / 3600) h \((s % 3600) / 60) min" }
    return "\(s / 86400) days"
}

func statusLabel(_ s: String) -> String {
    switch s { case "idle": return "Idle"; case "dnd": return "Do Not Disturb"; case "invisible": return "Invisible"; case "offline": return "Offline"; default: return "Online" }
}

let inviteBase = "https://jace-social.vercel.app/"

/// The code from an invite link, or the code itself.
func inviteCode(_ text: String) -> String {
    var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if let i = t.firstIndex(where: { $0 == "?" || $0 == "#" }) { t = String(t[..<i]) }
    while t.hasSuffix("/") { t.removeLast() }
    return t.split(separator: "/").last.map(String.init) ?? t
}
