import SwiftUI
import UIKit

/// Color tokens driven by the system-wide `userInterfaceStyle`. Each token
/// maps to a (dark, light) pair; the dark palette is the original brand
/// treatment, the light palette is its inverse. `UIColor.init(dynamicProvider:)`
/// re-evaluates whenever the trait collection changes, so a single token
/// flips automatically with the user's system appearance setting.
extension Color {
    // MARK: - Brand (same in both modes — keep the orange punch)
    static let kePrimary = Color(hex: "F97316")
    static let kePrimaryLight = Color(hex: "FB923C")
    static let kePrimaryDark = Color(hex: "EA580C")

    // MARK: - Backgrounds (dark ↔ light inverse)
    static let keBackground = dynamic(dark: "0A0A0A", light: "FAFAFA")
    static let keBackgroundElevated = dynamic(dark: "171717", light: "FFFFFF")
    static let keCard = dynamic(dark: "262626", light: "F1F1F1")
    static let keCardHover = dynamic(dark: "333333", light: "E5E5E5")

    // MARK: - Text
    // Primary flips black↔white; secondary/tertiary keep their hierarchy
    // by walking the inverse grey ramp in each mode.
    static let keTextPrimary = dynamic(dark: "FFFFFF", light: "0A0A0A")
    static let keTextSecondary = dynamic(dark: "D4D4D4", light: "262626")
    static let keTextTertiary = dynamic(dark: "A3A3A3", light: "525252")
    static let keTextMuted = dynamic(dark: "737373", light: "737373")
    static let keTextOnAccent = Color.white

    // MARK: - Status (punch through in both modes)
    static let keSuccess = Color(hex: "22C55E")
    static let keWarning = Color(hex: "EAB308")
    static let keError = Color(hex: "EF4444")

    // MARK: - Kashrus Type Badges
    static let keMeat = Color(hex: "EF4444")
    static let keDairy = Color(hex: "3B82F6")
    static let kePareve = Color(hex: "22C55E")

    // MARK: - Divider
    static let keDivider = dynamic(dark: "3F3F3F", light: "E5E5E5")

    private static func dynamic(dark: String, light: String) -> Color {
        Color(
            UIColor { trait in
                trait.userInterfaceStyle == .dark
                    ? UIColor(hex: dark)
                    : UIColor(hex: light)
            },
        )
    }
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

extension UIColor {
    fileprivate convenience init(hex: String) {
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
