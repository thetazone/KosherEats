import SwiftUI
import GoogleSignIn

@main
struct KosherEatsConsumerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var authVM = AuthViewModel()
    @StateObject private var cartVM = CartViewModel()

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(authVM)
                .environmentObject(cartVM)
                .task {
                    // Request location permission immediately on launch
                    LocationManager.shared.requestLocationPermission()
                    // Request push notification permission immediately on launch
                    await PushNotifications.shared.requestAuthorization()
                }
                .task(id: authVM.isAuthenticated) {
                    // Register push token with backend once authenticated
                    if authVM.isAuthenticated {
                        await PushNotifications.shared.registerPendingTokenIfPossible()
                    }
                }
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
        }
    }
}
