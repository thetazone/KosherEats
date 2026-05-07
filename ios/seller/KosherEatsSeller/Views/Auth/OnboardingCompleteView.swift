import SwiftUI

struct OnboardingCompleteView: View {
    let onContinue: () -> Void

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
                }

                VStack(spacing: 10) {
                    Text("You're all set!")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.keTextPrimary)

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
        }
    }
}
