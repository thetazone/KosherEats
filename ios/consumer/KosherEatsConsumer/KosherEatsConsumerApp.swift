import SwiftUI
import GoogleSignIn

@main
struct KosherEatsConsumerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var authVM = AuthViewModel()
    @StateObject private var cartVM = CartViewModel()
    @StateObject private var appRouter = AppRouter.shared

    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(authVM)
                .environmentObject(cartVM)
                .environmentObject(appRouter)
                .task {
                    // Request location permission immediately on launch
                    LocationManager.shared.requestLocationPermission()
                    // Request push notification permission immediately on launch
                    await PushNotifications.shared.requestAuthorization()
                }
                .task(id: authVM.isAuthenticated) {
                    if authVM.isAuthenticated {
                        await cartVM.syncLocalCartIfNeeded()
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
