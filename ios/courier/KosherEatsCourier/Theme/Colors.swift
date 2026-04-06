import SwiftUI

// Mirrors ios/consumer and ios/seller so branding stays consistent.
extension Color {
    // Brand
    static let kePrimary = Color(hex: "F97316")
    static let kePrimaryLight = Color(hex: "FB923C")
    static let kePrimaryDark = Color(hex: "EA580C")

    // Backgrounds
    static let keBackground = Color(hex: "0A0A0A")
    static let keBackgroundElevated = Color(hex: "171717")
    static let keCard = Color(hex: "262626")
    static let keCardHover = Color(hex: "333333")

    // Text
    static let keTextPrimary = Color.white
    static let keTextSecondary = Color(hex: "D4D4D4")
    static let keTextTertiary = Color(hex: "A3A3A3")
    static let keTextMuted = Color(hex: "737373")

    // Status
    static let keSuccess = Color(hex: "22C55E")
    static let keWarning = Color(hex: "EAB308")
    static let keError = Color(hex: "EF4444")

    static let keDivider = Color(hex: "3F3F3F")
}

extension Color {
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
