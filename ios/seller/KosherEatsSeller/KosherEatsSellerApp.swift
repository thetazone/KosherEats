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
                    NavigationStack { PasswordResetView(email: "you@restaurant.com") }
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
