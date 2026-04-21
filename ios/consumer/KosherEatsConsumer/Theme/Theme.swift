import SwiftUI

struct Theme {
    // MARK: - Corner Radii
    static let cornerRadiusSmall: CGFloat = 8
    static let cornerRadiusMedium: CGFloat = 12
    static let cornerRadiusLarge: CGFloat = 16
    static let cornerRadiusXL: CGFloat = 24

    // MARK: - Spacing
    static let spacingXS: CGFloat = 4
    static let spacingSM: CGFloat = 8
    static let spacingMD: CGFloat = 16
    static let spacingLG: CGFloat = 24
    static let spacingXL: CGFloat = 32

    // MARK: - Shadows
    static let shadowRadius: CGFloat = 8
    static let shadowColor = Color.black.opacity(0.3)
}

// MARK: - View Modifiers

struct KECardModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(Color.keCard)
            .cornerRadius(Theme.cornerRadiusMedium)
    }
}

struct KEPrimaryButtonStyle: ButtonStyle {
    var isEnabled: Bool = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(.keTextOnAccent)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(
                RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                    .fill(isEnabled ? Color.kePrimary : Color.keTextMuted)
            )
            .scaleEffect(configuration.isPressed ? 0.97 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: configuration.isPressed)
    }
}

struct KESecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(.kePrimary)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(
                RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                    .stroke(Color.kePrimary, lineWidth: 1.5)
            )
            .scaleEffect(configuration.isPressed ? 0.97 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: configuration.isPressed)
    }
}

struct KETextFieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding()
            .background(Color.keCard)
            .cornerRadius(Theme.cornerRadiusMedium)
            .foregroundColor(.keTextPrimary)
            .accentColor(.kePrimary)
    }
}

extension View {
    func keCard() -> some View {
        modifier(KECardModifier())
    }

    func keTextField() -> some View {
        modifier(KETextFieldStyle())
    }
}
