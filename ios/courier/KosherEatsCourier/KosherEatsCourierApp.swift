import SwiftUI

@main
struct KosherEatsCourierApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var auth = AuthViewModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(auth)
                .preferredColorScheme(.dark)
                .task(id: auth.isAuthenticated) {
                    // Only prompt for push auth once we have an account — the
                    // iOS permission prompt should appear in context of the
                    // user actually signing in, not at cold launch.
                    if auth.isAuthenticated {
                        await PushNotifications.shared.requestAuthorization()
                        await PushNotifications.shared.registerPendingTokenIfPossible()
                    }
                }
        }
    }
}
