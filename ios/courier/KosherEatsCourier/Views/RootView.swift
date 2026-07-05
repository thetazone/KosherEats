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
            } else if let err = auth.profileError {
                // Profile load failed with a non-401 error (network blip, Fly
                // cold-start outliving our retry, etc.). Without this branch
                // we'd sit on an infinite spinner — let the courier retry or
                // sign out.
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 40))
                        .foregroundColor(.keWarning)
                    Text("Couldn't load your profile")
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                    Text(err)
                        .font(.footnote)
                        .foregroundColor(.keTextSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    Button("Try again") { Task { await auth.loadProfile() } }
                        .buttonStyle(.borderedProminent)
                        .tint(.kePrimary)
                    Button("Sign out") { auth.logout() }
                        .foregroundColor(.keTextSecondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.keBackground.ignoresSafeArea())
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
