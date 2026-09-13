import UIKit

/// Tiny haptics helper so the rest of the app doesn't have to remember which
/// `UI...FeedbackGenerator` type to instantiate. Keeping all the haptic calls
/// centralized also makes it trivial to flip them off globally if we ever
/// want a "reduce motion / haptics" toggle. Respects the system "Reduce
/// Motion" accessibility setting — when enabled, all haptics are silently
/// skipped so sensitive users aren't surprised by unexpected vibrations.
///
/// The whole surface is `@MainActor` because everything it touches already is:
/// `UIAccessibility.isReduceMotionEnabled` and every `UIFeedbackGenerator`
/// subclass are main-actor isolated in the UIKit SDK, and a feedback generator
/// must be prepared and fired on the main thread anyway. Annotating the enum
/// (rather than silencing each call) keeps that requirement visible to callers
/// — all of which are SwiftUI views or @MainActor view models today, so no call
/// site needs a hop. A genuinely off-main caller must now hop explicitly, which
/// is the correct thing for it to do.
@MainActor
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
