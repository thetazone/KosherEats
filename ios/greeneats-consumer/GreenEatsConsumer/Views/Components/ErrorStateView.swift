import SwiftUI

/// Unified error state for list/detail views. Single visual vocabulary
/// (icon + headline + description + retry button) so every failure in the
/// app looks and feels the same.
///
/// Pattern: if a ViewModel has both `isLoading == false` and a non-nil
/// `errorMessage` and no data, show this instead of a spinner or empty state.
struct ErrorStateView: View {
    let message: String
    var symbol: String = "wifi.exclamationmark"
    var onRetry: (() -> Void)?

    var body: some View {
        VStack(spacing: Theme.spacingMD) {
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
            .padding(.horizontal, Theme.spacingLG)

            if let onRetry = onRetry {
                Button {
                    Haptics.impact(.light)
                    onRetry()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.clockwise")
                        Text("Try again")
                    }
                }
                .buttonStyle(KEPrimaryButtonStyle())
                .frame(width: 220)
                .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

/// Smaller inline error bar for cases where we have stale data already
/// showing and just want to signal a refresh failure (e.g. pull-to-refresh
/// failed but the last-good list is still visible).
struct InlineErrorBanner: View {
    let message: String
    var onRetry: (() -> Void)?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundColor(.keError)
            Text(message)
                .font(.caption)
                .foregroundColor(.keTextSecondary)
                .lineLimit(2)
            Spacer()
            if let onRetry = onRetry {
                Button("Retry") {
                    Haptics.impact(.light)
                    onRetry()
                }
                .font(.caption.bold())
                .foregroundColor(.kePrimary)
            }
        }
        .padding(12)
        .background(Color.keError.opacity(0.12))
        .overlay(
            RoundedRectangle(cornerRadius: Theme.cornerRadiusSmall)
                .stroke(Color.keError.opacity(0.3), lineWidth: 1),
        )
        .cornerRadius(Theme.cornerRadiusSmall)
    }
}
