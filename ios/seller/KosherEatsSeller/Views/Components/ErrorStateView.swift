import SwiftUI

/// Unified error state for the seller app. Mirrors the consumer version so
/// both apps look and feel the same on failure.
struct ErrorStateView: View {
    let message: String
    var symbol: String = "wifi.exclamationmark"
    var onRetry: (() -> Void)?

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: symbol)
                .font(.system(size: 48))
                .foregroundColor(.keError)

            VStack(spacing: 6) {
                Text("Something went wrong")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                Text(message)
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 24)

            if let onRetry = onRetry {
                Button {
                    Haptics.impact(.light)
                    onRetry()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.clockwise")
                        Text("Try again")
                    }
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(Color.kePrimary)
                    .cornerRadius(12)
                }
                .frame(width: 220)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}
