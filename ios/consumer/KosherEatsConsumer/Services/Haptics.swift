import UIKit

/// Tiny haptics helper so the rest of the app doesn't have to remember which
/// `UI...FeedbackGenerator` type to instantiate. Keeping all the haptic calls
/// centralized also makes it trivial to flip them off globally if we ever
/// want a "reduce motion / haptics" toggle. Respects the system "Reduce
/// Motion" accessibility setting — when enabled, all haptics are silently
/// skipped so sensitive users aren't surprised by unexpected vibrations.
enum Haptics {
    /// Returns `true` when haptics should be suppressed (the user has
    /// enabled Reduce Motion in system accessibility settings).
    private static var isSuppressed: Bool {
        UIAccessibility.isReduceMotionEnabled
    }

    /// Success / warning / error signals — used on key milestone moments
    /// like "order placed", "added to cart", "payment failed".
    static func notify(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        guard !isSuppressed else { return }
        let gen = UINotificationFeedbackGenerator()
        gen.prepare()
        gen.notificationOccurred(type)
    }

    /// Light / medium / heavy impact — used on casual actions like toggling
    /// a filter chip, stepping quantity, or tapping a card.
    static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        guard !isSuppressed else { return }
        let gen = UIImpactFeedbackGenerator(style: style)
        gen.prepare()
        gen.impactOccurred()
    }

    /// Selection tick — the lightest haptic, used when the user changes a
    /// picker value or scrolls through a segmented control.
    static func selection() {
        guard !isSuppressed else { return }
        let gen = UISelectionFeedbackGenerator()
        gen.prepare()
        gen.selectionChanged()
    }

    /// Convenience selectors for the common cases the app cares about.
    static func success() { notify(.success) }
    static func warning() { notify(.warning) }
    static func error()   { notify(.error) }
}
