import SwiftUI

struct OnboardingCompleteView: View {
    let onContinue: () -> Void

    @State private var showContent = false
    @State private var checkmarkScale: CGFloat = 0.5

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            VStack(spacing: 24) {
                Spacer()

                ZStack {
                    Circle()
                        .fill(Color.kePrimary.opacity(0.15))
                        .frame(width: 104, height: 104)
                    Image(systemName: "checkmark.seal.fill")
                        .font(.system(size: 48))
                        .foregroundColor(.kePrimary)
                        .scaleEffect(checkmarkScale)
                }
                .accessibilityHidden(true)

                VStack(spacing: 10) {
                    Text("You're all set!")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.keTextPrimary)
                        .accessibilityAddTraits(.isHeader)

                    Text("Your restaurant has been submitted for review. We'll notify you once it's approved and ready to go live.")
                        .font(.body)
                        .foregroundColor(.keTextSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }

                Button {
                    onContinue()
                } label: {
                    Text("Go to Dashboard")
                        .font(.headline)
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Color.kePrimary)
                        .cornerRadius(14)
                }
                .padding(.horizontal, 24)

                Spacer()
            }
            .adaptiveContentWidth(520)
            .opacity(showContent ? 1 : 0)
            .offset(y: showContent ? 0 : 20)
        }
        .onAppear {
            withAnimation(.spring(response: 0.5, dampingFraction: 0.6).delay(0.1)) {
                checkmarkScale = 1.0
            }
            withAnimation(.easeOut(duration: 0.4).delay(0.15)) {
                showContent = true
            }
        }
    }
}
