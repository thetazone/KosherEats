import SwiftUI
import GoogleSignIn

@main
struct KosherEatsSellerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var authVM = AuthViewModel()
    // Latches when the reviewer/user taps "Not now" on
    // ProfileCompletionSheet so we don't re-present it on every render.
    // TEMPORARY — tied to the App Review skip button; remove both together.
    @State private var profileSheetDismissed = false
    @Environment(\.scenePhase) private var scenePhase

    // DEBUG-only screenshot harness: launching with `-keOnboardingPreview` boots
    // the seller onboarding wizard directly (bypassing auth); `-keOnboardingStep
    // <case>` jumps to a step (see SellerOnboardingFlow). Always false in release.
    private var isOnboardingPreview: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-keOnboardingPreview")
        #else
        return false
        #endif
    }

    // DEBUG-only: `-keMenuImportPreview` boots the Menu tab with a seeded
    // in-progress import so the status banner can be screenshotted. Release: false.
    private var isMenuImportPreview: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-keMenuImportPreview")
        #else
        return false
        #endif
    }

    // DEBUG-only: `-kePasswordResetPreview` shows the password-reset screen.
    private var isPasswordResetPreview: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.arguments.contains("-kePasswordResetPreview")
        #else
        return false
        #endif
    }

    // DEBUG-only: `-keAutoLoginEmail <email> -keAutoLoginPassword <pw>` signs in
    // with email/password on launch, so a demo/screenshot sim lands straight on
    // the authenticated dashboard without anyone typing credentials. Never
    // compiled into release.
    private var autoLoginCredentials: (email: String, password: String)? {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        guard let ei = args.firstIndex(of: "-keAutoLoginEmail"), ei + 1 < args.count,
              let pi = args.firstIndex(of: "-keAutoLoginPassword"), pi + 1 < args.count else {
            return nil
        }
        return (args[ei + 1], args[pi + 1])
        #else
        return nil
        #endif
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if isOnboardingPreview {
                    SellerOnboardingFlow(onComplete: { _ in })
                        .environmentObject(authVM)
                } else if isMenuImportPreview {
                    MenuManagementView(previewVM: .previewImporting())
                        .environmentObject(authVM)
                } else if isPasswordResetPreview {
                    PasswordResetHarness()
                } else if authVM.isAuthenticated {
                    if authVM.hasSellerAccess {
                        SellerRootGate()
                            .environmentObject(authVM)
                    } else {
                        // Authenticated via Apple/Google but role != seller.
                        // Rare after Phase 2 (each app's auth call now creates
                        // a role-scoped account), but kept for safety.
                        SellerOnboardingView()
                            .environmentObject(authVM)
                    }
                } else {
                    SellerLoginView()
                        .environmentObject(authVM)
                }
            }
            .preferredColorScheme(.dark)
            .sheet(isPresented: Binding(
                // Post-Apple-sign-in capture of first/last/email/phone when
                // Apple returned a nil `fullName` or a @privaterelay forwarder.
                // Closes automatically when the PUT /user/profile response
                // flips `needsProfileCompletion` to false, or when the user
                // taps "Not now" (reviewer escape hatch — see the TEMPORARY
                // note in ProfileCompletionSheet).
                get: { authVM.isAuthenticated && authVM.needsProfileCompletion && !profileSheetDismissed },
                set: { newValue in
                    if !newValue { profileSheetDismissed = true }
                }
            )) {
                ProfileCompletionSheet()
                    .environmentObject(authVM)
                    .presentationDetents([.medium, .large])
            }
            .task(id: authVM.isAuthenticated) {
                if authVM.isAuthenticated {
                    await PushNotifications.shared.requestAuthorization()
                    await PushNotifications.shared.registerPendingTokenIfPossible()
                }
            }
            .task {
                // DEBUG demo harness: sign in automatically when launched with
                // -keAutoLoginEmail/-keAutoLoginPassword. login() overwrites any
                // stale Keychain token, so it's safe even if a prior session for
                // a different account was restored by AuthViewModel.init().
                if let creds = autoLoginCredentials, !authVM.isAuthenticated {
                    await authVM.login(email: creds.email, password: creds.password)
                }
            }
            .onOpenURL { url in
                GIDSignIn.sharedInstance.handle(url)
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    UNUserNotificationCenter.current().setBadgeCount(0)
                }
            }
        }
    }
}

#if DEBUG
/// Faithful preview of the reset flow: a stand-in sign-in screen that presents
/// the reset sheet, so Close + the post-reset auto-dismiss behave like
/// production (where PasswordResetView is a sheet over EmailAuthView).
private struct PasswordResetHarness: View {
    @State private var showSheet = true
    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()
            VStack(spacing: 16) {
                Text("Sign-in screen (preview)")
                    .foregroundColor(.keTextSecondary)
                Button("Forgot password?") { showSheet = true }
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.kePrimary)
            }
        }
        .sheet(isPresented: $showSheet) {
            NavigationStack { PasswordResetView(email: "") }
                .presentationDetents([.medium, .large])
        }
    }
}
#endif
