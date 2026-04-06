import SwiftUI

// RootView decides which top-level flow the user sees:
// - Not authenticated      -> Login / Signup
// - Authenticated, not approved -> Onboarding flow (resumed at the right step)
// - Authenticated, approved     -> Dashboard (the working courier experience)
struct RootView: View {
    @EnvironmentObject var auth: AuthViewModel

    var body: some View {
        Group {
            if !auth.isAuthenticated {
                AuthLandingView()
            } else if let profile = auth.profile {
                if profile.onboardingStatus == .approved {
                    DashboardView()
                } else {
                    OnboardingFlowView(profile: profile)
                }
            } else {
                // Loading profile after login
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .kePrimary))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.keBackground.ignoresSafeArea())
            }
        }
        .background(Color.keBackground.ignoresSafeArea())
    }
}
