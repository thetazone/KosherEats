import SwiftUI

// Entry screen that mirrors the UberEats / DoorDash driver onramp:
// big hero promise, "Sign up to deliver" primary, "I already have an account" secondary.
struct AuthLandingView: View {
    @State private var showSignup = false
    @State private var showLogin = false

    var body: some View {
        NavigationStack {
            VStack(spacing: Theme.spacingLG) {
                Spacer()

                VStack(spacing: Theme.spacingMD) {
                    Image(systemName: "box.truck.fill")
                        .font(.system(size: 72))
                        .foregroundStyle(Color.kePrimary)

                    Text("Deliver with KosherEats")
                        .font(.largeTitle.bold())
                        .foregroundColor(.keTextPrimary)
                        .multilineTextAlignment(.center)

                    Text("Set your own schedule. Earn on every drop.\nSign up in minutes.")
                        .font(.body)
                        .foregroundColor(.keTextSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal, Theme.spacingLG)

                Spacer()

                VStack(spacing: Theme.spacingMD) {
                    Button("Get started") { showSignup = true }
                        .buttonStyle(KEPrimaryButtonStyle())

                    Button("I already have an account") { showLogin = true }
                        .buttonStyle(KESecondaryButtonStyle())
                }
                .padding(.horizontal, Theme.spacingLG)
                .padding(.bottom, Theme.spacingLG)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.keBackground.ignoresSafeArea())
            .navigationDestination(isPresented: $showSignup) { SignupView() }
            .navigationDestination(isPresented: $showLogin) { LoginView() }
        }
    }
}
