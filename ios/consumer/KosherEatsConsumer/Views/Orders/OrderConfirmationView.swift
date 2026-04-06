import SwiftUI

/// OrderConfirmationView is the celebratory screen shown after a successful
/// checkout. This is a critical "I made a good decision" moment in the
/// delivery app funnel — UberEats / DoorDash invest heavily here.
///
/// Shows: big checkmark animation, order ID, ETA, restaurant name,
/// then two CTAs: "Track Order" (deep-link to live map) and "Done"
/// (return to home).
struct OrderConfirmationView: View {
    let order: Order
    var onDone: () -> Void
    var onTrack: () -> Void

    @State private var showCheckmark = false

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            VStack(spacing: Theme.spacingLG) {
                Spacer()

                checkmark
                    .scaleEffect(showCheckmark ? 1.0 : 0.5)
                    .opacity(showCheckmark ? 1.0 : 0)
                    .animation(.spring(response: 0.55, dampingFraction: 0.65), value: showCheckmark)

                VStack(spacing: Theme.spacingSM) {
                    Text("Order placed!")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.keTextPrimary)
                    Text("Your order from \(order.restaurantName) is on its way.")
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                        .multilineTextAlignment(.center)
                }

                etaCard

                Spacer()
                Spacer()

                VStack(spacing: Theme.spacingSM) {
                    Button {
                        onTrack()
                    } label: {
                        HStack {
                            Image(systemName: "location.circle.fill")
                            Text("Track your order")
                        }
                    }
                    .buttonStyle(KEPrimaryButtonStyle())

                    Button {
                        onDone()
                    } label: {
                        Text("Done")
                    }
                    .buttonStyle(KESecondaryButtonStyle())
                }
                .padding(.horizontal, Theme.spacingLG)
                .padding(.bottom, Theme.spacingLG)
            }
        }
        .navigationBarBackButtonHidden(true)
        .onAppear {
            // Slight delay so the animation reads as a "moment" rather than
            // snapping in with the rest of the view.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                showCheckmark = true
            }
            // Haptic win feedback.
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
    }

    private var checkmark: some View {
        ZStack {
            Circle()
                .fill(Color.keSuccess.opacity(0.15))
                .frame(width: 140, height: 140)
            Circle()
                .fill(Color.keSuccess.opacity(0.3))
                .frame(width: 100, height: 100)
            Image(systemName: "checkmark")
                .font(.system(size: 56, weight: .bold))
                .foregroundColor(.keSuccess)
        }
    }

    private var etaCard: some View {
        VStack(spacing: 8) {
            Text("Estimated arrival")
                .font(.caption)
                .foregroundColor(.keTextTertiary)
            Text(etaText)
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.kePrimary)
            Text("Order #\(order.id.prefix(8))")
                .font(.caption)
                .foregroundColor(.keTextMuted)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .padding(.horizontal, Theme.spacingLG)
    }

    private var etaText: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: order.estDeliveryTime)
    }
}
