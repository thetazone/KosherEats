import SwiftUI

extension Color {
    static let kePrimary = Color(hex: "F97316")
    static let keBackground = Color(hex: "0A0A0A")
    static let keSurface = Color(hex: "171717")
    static let keCard = Color(hex: "262626")
    static let keTextPrimary = Color.white
    static let keTextSecondary = Color(hex: "D4D4D4")
    static let keTextMuted = Color(hex: "737373")
    static let keBorder = Color(hex: "404040")
    static let keSuccess = Color(hex: "22C55E")
    static let keWarning = Color(hex: "EAB308")
    static let keError = Color(hex: "EF4444")

    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 6:
            (a, r, g, b) = (255, (int >> 16) & 0xFF, (int >> 8) & 0xFF, int & 0xFF)
        case 8:
            (a, r, g, b) = ((int >> 24) & 0xFF, (int >> 16) & 0xFF, (int >> 8) & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (255, 0, 0, 0)
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}
