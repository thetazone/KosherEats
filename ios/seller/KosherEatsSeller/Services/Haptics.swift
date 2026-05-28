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

    // Reuse generator instances so `prepare()` actually warms the Taptic
    // Engine between calls instead of allocating + preparing + firing in
    // the same runloop tick (which defeats the latency benefit).
    private static let notificationGen = UINotificationFeedbackGenerator()
    private static let selectionGen = UISelectionFeedbackGenerator()
    private static let lightImpactGen = UIImpactFeedbackGenerator(style: .light)
    private static let mediumImpactGen = UIImpactFeedbackGenerator(style: .medium)
    private static let heavyImpactGen = UIImpactFeedbackGenerator(style: .heavy)

    /// Success / warning / error signals — used on key milestone moments
    /// like "order accepted", "item saved", "upload failed".
    static func notify(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        guard !isSuppressed else { return }
        notificationGen.notificationOccurred(type)
        notificationGen.prepare()
    }

    /// Light / medium / heavy impact — used on casual actions like toggling
    /// availability, tapping a category chip, or opening a sheet.
    static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        guard !isSuppressed else { return }
        let gen: UIImpactFeedbackGenerator
        switch style {
        case .light:  gen = lightImpactGen
        case .medium: gen = mediumImpactGen
        case .heavy:  gen = heavyImpactGen
        @unknown default: gen = lightImpactGen
        }
        gen.impactOccurred()
        gen.prepare()
    }

    /// Selection tick — the lightest haptic, used when the user changes a
    /// picker value or scrolls through a segmented control.
    static func selection() {
        guard !isSuppressed else { return }
        selectionGen.selectionChanged()
        selectionGen.prepare()
    }

    /// Convenience selectors for the common cases the app cares about.
    static func success() { notify(.success) }
    static func warning() { notify(.warning) }
    static func error()   { notify(.error) }
}
