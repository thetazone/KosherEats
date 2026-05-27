import UIKit

/// Same haptic helper as the consumer app. Centralizes all taptic calls so
/// we can flip them off globally later if needed. Respects the system
/// "Reduce Motion" accessibility setting — when enabled, all haptics are
/// silently skipped so sensitive users aren't surprised by unexpected vibrations.
enum Haptics {
    /// Returns `true` when haptics should be suppressed (the user has
    /// enabled Reduce Motion in system accessibility settings).
    private static var isSuppressed: Bool {
        UIAccessibility.isReduceMotionEnabled
    }

    /// Success / warning / error signals — used on key milestone moments
    /// like "order accepted", "item saved", "upload failed".
    static func notify(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        guard !isSuppressed else { return }
        let gen = UINotificationFeedbackGenerator()
        gen.prepare()
        gen.notificationOccurred(type)
    }

    /// Light / medium / heavy impact — used on casual actions like toggling
    /// availability, tapping a category chip, or opening a sheet.
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
