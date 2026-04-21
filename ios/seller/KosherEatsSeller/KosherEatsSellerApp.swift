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

    var body: some Scene {
        WindowGroup {
            Group {
                if authVM.isAuthenticated {
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
