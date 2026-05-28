import SwiftUI
import UIKit

extension Color {
    // MARK: - Brand
    static let kePrimary = Color(hex: "F97316")
    static let kePrimaryLight = Color(hex: "FB923C")
    static let kePrimaryDark = Color(hex: "EA580C")

    // MARK: - Backgrounds
    static let keBackground = dynamic(dark: "0A0A0A", light: "FAFAFA")
    static let keBackgroundElevated = dynamic(dark: "171717", light: "FFFFFF")
    /// Alias kept for existing call-sites that reference `keSurface`.
    static let keSurface = keBackgroundElevated
    static let keCard = dynamic(dark: "262626", light: "F1F1F1")
    static let keCardHover = dynamic(dark: "333333", light: "E5E5E5")

    // MARK: - Text
    static let keTextPrimary = dynamic(dark: "FFFFFF", light: "0A0A0A")
    static let keTextSecondary = dynamic(dark: "D4D4D4", light: "262626")
    static let keTextTertiary = dynamic(dark: "A3A3A3", light: "525252")
    static let keTextMuted = dynamic(dark: "737373", light: "737373")
    static let keTextOnAccent = Color.white

    // MARK: - Borders / Dividers
    static let keBorder = dynamic(dark: "404040", light: "E5E5E5")
    static let keDivider = dynamic(dark: "3F3F3F", light: "E5E5E5")

    // MARK: - Status
    static let keSuccess = Color(hex: "22C55E")
    static let keWarning = Color(hex: "EAB308")
    static let keError = Color(hex: "EF4444")

    private static func dynamic(dark: String, light: String) -> Color {
        Color(
            UIColor { trait in
                trait.userInterfaceStyle == .dark
                    ? UIColor(hex: dark)
                    : UIColor(hex: light)
            }
        )
    }

    /// Delegates to the single `UIColor(hex:)` implementation to avoid
    /// duplicating the hex-parsing logic.
    init(hex: String) {
        self.init(UIColor(hex: hex))
    }
}

extension UIColor {
    convenience init(hex: String) {
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
            red: CGFloat(r) / 255,
            green: CGFloat(g) / 255,
            blue: CGFloat(b) / 255,
            alpha: CGFloat(a) / 255
        )
    }
}
