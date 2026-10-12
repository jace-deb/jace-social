import SwiftUI

// The website's dark theme (server/app/globals.css)
enum JaceColor {
    static let bg = Color(red: 0x11 / 255, green: 0x13 / 255, blue: 0x17 / 255)
    static let panel = Color(red: 0x1c / 255, green: 0x1f / 255, blue: 0x26 / 255)
    static let text = Color(red: 0xe6 / 255, green: 0xe8 / 255, blue: 0xec / 255)
    static let muted = Color(red: 0x8b / 255, green: 0x91 / 255, blue: 0x9c / 255)
    static let green = Color(red: 0x3d / 255, green: 0xdc / 255, blue: 0x84 / 255)
    static let red = Color(red: 0xe0 / 255, green: 0x60 / 255, blue: 0x5a / 255)
    static let yellow = Color(red: 0xe0 / 255, green: 0xb4 / 255, blue: 0x4a / 255)
    static let gray = Color(red: 0x6b / 255, green: 0x71 / 255, blue: 0x7c / 255)

    static func status(_ s: String) -> Color {
        switch s {
        case "online": return green
        case "idle": return yellow
        case "dnd": return red
        default: return gray
        }
    }
}
