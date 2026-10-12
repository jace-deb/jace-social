import SwiftUI

/// The website's themes (server/app/globals.css and lib/theme.ts): Dark, Midnight, Forest and Light,
/// with your accent color. Settings -> Appearance changes them for every device.
enum JaceColor {
    struct Palette { var bg, bg2, panel, text, muted, gray: Color; var light = false }
    static let dark = Palette(bg: hex("#111317"), bg2: hex("#16181d"), panel: hex("#1c1f26"), text: hex("#e6e8ec"), muted: hex("#8b919c"), gray: hex("#6b717c"))
    static let palettes: [String: Palette] = [
        "dark": dark,
        "midnight": Palette(bg: hex("#000000"), bg2: hex("#07080a"), panel: hex("#0e1013"), text: hex("#e6e8ec"), muted: hex("#8b919c"), gray: hex("#6b717c")),
        "forest": Palette(bg: hex("#0e1512"), bg2: hex("#121b17"), panel: hex("#17221d"), text: hex("#e6e8ec"), muted: hex("#8ea398"), gray: hex("#6b717c")),
        "light": Palette(bg: hex("#e9ebef"), bg2: hex("#f2f3f5"), panel: hex("#ffffff"), text: hex("#1d2127"), muted: hex("#5e6672"), gray: hex("#8a919b"), light: true),
    ]
    private(set) static var current = dark
    private(set) static var accent = hex("#3ddc84")
    private(set) static var onAccent = hex("#0c1410")

    static var bg: Color { current.bg }
    static var bg2: Color { current.bg2 }
    static var panel: Color { current.panel }
    static var text: Color { current.text }
    static var muted: Color { current.muted }
    static var gray: Color { current.gray }
    static var green: Color { accent }
    static var onGreen: Color { onAccent }
    static let red = hex("#e0605a")
    static let yellow = hex("#e0b44a")
    static var light: Bool { current.light }

    /// Use these settings; -> a key that changes when the look does (the app redraws with it).
    @discardableResult
    static func apply(_ s: Settings?, systemDark: Bool) -> String {
        var theme = s?.theme ?? "dark"
        if theme == "system" { theme = systemDark ? "dark" : "light" }
        current = palettes[theme] ?? dark
        let a = s?.accent.flatMap { $0.range(of: "^#[0-9a-fA-F]{6}$", options: .regularExpression) != nil ? $0 : nil } ?? "#3ddc84"
        accent = hex(a)
        // readable text on the accent (dark on light accents, white on dark ones)
        let n = Int(a.dropFirst(), radix: 16) ?? 0
        let (r, g, b) = ((n >> 16) & 255, (n >> 8) & 255, n & 255)
        onAccent = (r * 299 + g * 587 + b * 114) / 1000 > 140 ? hex("#0c1410") : .white
        return "\(theme)\(a)\(s?.font_scale ?? 1)"
    }

    static func status(_ s: String) -> Color {
        switch s {
        case "online": return green
        case "idle": return yellow
        case "dnd": return red
        default: return gray
        }
    }
}

let ACCENTS = ["#3ddc84", "#4f8fd6", "#b07cf0", "#e0605a", "#e0b44a", "#ef7fb4", "#3cc8d8", "#f08a3c"]
let THEMES = [("dark", "Dark"), ("midnight", "Midnight"), ("forest", "Forest"), ("light", "Light"), ("system", "Match my phone")]

func hex(_ s: String?, _ fallback: Color = Color(red: 0x3d / 255, green: 0xdc / 255, blue: 0x84 / 255)) -> Color {
    guard let s, s.range(of: "^#[0-9a-fA-F]{6}$", options: .regularExpression) != nil, let n = Int(s.dropFirst(), radix: 16) else { return fallback }
    return Color(red: Double((n >> 16) & 255) / 255, green: Double((n >> 8) & 255) / 255, blue: Double(n & 255) / 255)
}

/// Text size from Settings -> Appearance (80% to 140%).
func dynamicSize(_ scale: Double?) -> DynamicTypeSize {
    switch scale ?? 1 {
    case ..<0.9: return .small
    case ..<1.0: return .medium
    case ..<1.1: return .large
    case ..<1.2: return .xLarge
    case ..<1.3: return .xxLarge
    default: return .xxxLarge
    }
}
