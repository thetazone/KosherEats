import SwiftUI

// Fallback for the rare case where an authenticated user's role is not seller
// or admin. After the role-scoped-uniqueness work (migration 019, Phase 2),
// every sign-in flow on this app sends role="seller" and the backend either
// finds or creates the seller-side account — so a real first-time sign-in
// here will never land on this view.
//
// The only way to hit this screen now is a stale token in Keychain from
// before Phase 2 shipped (the user signed in when the seller app still
// created consumer accounts). Sign out → sign back in resolves it because
// the new sign-in path creates the seller account.
struct SellerOnboardingView: View {
    @EnvironmentObject var authVM: AuthViewModel

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            VStack(spacing: 24) {
                Spacer().frame(height: 60)

                ZStack {
                    Circle()
                        .fill(Color.kePrimary.opacity(0.15))
                        .frame(width: 104, height: 104)
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 48))
                        .foregroundColor(.kePrimary)
                }

                VStack(spacing: 10) {
                    Text("This account isn't a seller")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.keTextPrimary)
                        .multilineTextAlignment(.center)

                    Text("Sign out and sign back in to create a seller account on this number, or use a different account.")
                        .font(.body)
                        .foregroundColor(.keTextSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }

                Button {
                    authVM.logout()
                } label: {
                    Text("Sign out")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Color.kePrimary)
                        .cornerRadius(12)
                }
                .padding(.horizontal, 24)

                Spacer()
            }
            .adaptiveContentWidth(520)
        }
    }
}
