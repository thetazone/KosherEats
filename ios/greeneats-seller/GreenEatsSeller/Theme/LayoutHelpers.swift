import SwiftUI

// MARK: - Adaptive content width
//
// The seller app was designed at iPhone widths. On iPad the same ScrollView
// content stretches edge-to-edge, which makes cards and forms look visually
// lost. This modifier caps the content to a sensible max-width and centers
// it on iPad (`regular` horizontal size class) while leaving iPhone untouched.
//
// Usage:
//     ScrollView { VStack { ... } }.adaptiveContentWidth()
//     ScrollView { Form { ... } }.adaptiveContentWidth(600)

struct AdaptiveContentWidth: ViewModifier {
    @Environment(\.horizontalSizeClass) private var sizeClass
    let maxWidth: CGFloat

    func body(content: Content) -> some View {
        content
            .frame(maxWidth: sizeClass == .regular ? maxWidth : .infinity)
            .frame(maxWidth: .infinity)
    }
}

extension View {
    /// Caps content width on iPad, no-op on iPhone.
    /// - Parameter maxWidth: The cap on regular-width devices. Default 720
    ///   comfortably fits order cards and form fields without stretching.
    func adaptiveContentWidth(_ maxWidth: CGFloat = 720) -> some View {
        modifier(AdaptiveContentWidth(maxWidth: maxWidth))
    }
}

// MARK: - Size-class helpers
//
// Lightweight accessor so views can branch on iPad vs iPhone without each
// one re-declaring `@Environment(\.horizontalSizeClass)`. Used inline:
//
//     if SizeClass.isRegular(h: sizeClass) { ... }

enum SizeClass {
    static func isRegular(h sizeClass: UserInterfaceSizeClass?) -> Bool {
        sizeClass == .regular
    }
}
