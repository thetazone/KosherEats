import SwiftUI

/// Post-delivery rating prompt. Presented automatically from OrderTrackingView
/// the first time an order transitions to `.delivered` and no rating has been
/// recorded yet. The consumer picks 1–5 stars and optionally leaves a short
/// comment; on submit the courier's aggregate rating on the backend
/// recomputes from the full set of ratings on that courier.
struct CourierRatingSheet: View {
    let orderId: String
    let courierFirstName: String
    let onSubmitted: (Int) -> Void
    let onDismiss: () -> Void

    @State private var stars: Int = 5
    @State private var comment: String = ""
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                VStack(spacing: Theme.spacingLG) {
                    VStack(spacing: Theme.spacingSM) {
                        Image(systemName: "hands.clap.fill")
                            .font(.system(size: 48))
                            .foregroundColor(.kePrimary)
                        Text("How was \(courierFirstName)?")
                            .font(.title2.bold())
                            .foregroundColor(.keTextPrimary)
                        Text("Your rating stays anonymous and helps couriers keep doing great work.")
                            .font(.subheadline)
                            .foregroundColor(.keTextSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, Theme.spacingLG)

                    starRow

                    TextField("Leave a comment (optional)", text: $comment, axis: .vertical)
                        .lineLimit(3...5)
                        .keTextField()

                    if let err = errorMessage {
                        Text(err)
                            .font(.caption)
                            .foregroundColor(.keError)
                    }

                    Button {
                        Task { await submit() }
                    } label: {
                        if isSubmitting {
                            ProgressView().tint(.keTextOnAccent)
                        } else {
                            Text("Submit rating")
                        }
                    }
                    .buttonStyle(KEPrimaryButtonStyle(isEnabled: !isSubmitting))
                    .disabled(isSubmitting)

                    Spacer()
                }
                .padding(Theme.spacingMD)
            }
            .navigationTitle("Rate your courier")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Skip") {
                        onDismiss()
                    }
                    .foregroundColor(.keTextMuted)
                }
            }
        }
    }

    private var starRow: some View {
        HStack(spacing: 8) {
            ForEach(1...5, id: \.self) { i in
                Button {
                    Haptics.impact(.light)
                    stars = i
                } label: {
                    Image(systemName: i <= stars ? "star.fill" : "star")
                        .font(.system(size: 36))
                        .foregroundColor(i <= stars ? .keWarning : .keTextMuted)
                }
            }
        }
    }

    private func submit() async {
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }
        do {
            try await APIService.shared.rateCourier(
                orderId: orderId,
                stars: stars,
                comment: comment.trimmingCharacters(in: .whitespacesAndNewlines),
            )
            Haptics.success()
            onSubmitted(stars)
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}
