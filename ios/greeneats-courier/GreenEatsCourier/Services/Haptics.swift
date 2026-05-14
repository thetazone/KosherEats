import UIKit

/// Tiny haptics helper — mirrors the consumer / seller apps so every target
/// uses the same API. Centralizes all taptic calls for easy global toggling.
enum Haptics {
    static func notify(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        let gen = UINotificationFeedbackGenerator()
        gen.prepare()
        gen.notificationOccurred(type)
    }

    static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        let gen = UIImpactFeedbackGenerator(style: style)
        gen.prepare()
        gen.impactOccurred()
    }

    static func success() { notify(.success) }
    static func warning() { notify(.warning) }
    static func error() { notify(.error) }
}
