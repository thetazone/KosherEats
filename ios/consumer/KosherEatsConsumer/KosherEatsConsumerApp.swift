import SwiftUI
import GoogleSignIn

@main
struct KosherEatsConsumerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var authVM = AuthViewModel()
    @StateObject private var cartVM = CartViewModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if authVM.isAuthenticated {
                    MainTabView()
                        .environmentObject(authVM)
                        .environmentObject(cartVM)
                } else {
                    LoginView()
                        .environmentObject(authVM)
                }
            }
            .preferredColorScheme(.dark)
            .task(id: authVM.isAuthenticated) {
                if authVM.isAuthenticated {
                    await PushNotifications.shared.requestAuthorization()
                    await PushNotifications.shared.registerPendingTokenIfPossible()
                }
            }
            .onOpenURL { url in
                GIDSignIn.sharedInstance.handle(url)
            }
        }
    }
}
