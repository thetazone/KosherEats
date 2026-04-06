import SwiftUI
import GoogleSignIn
import FacebookCore

@main
struct KosherEatsSellerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var authVM = AuthViewModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if authVM.isAuthenticated {
                    MainTabView()
                        .environmentObject(authVM)
                } else {
                    SellerLoginView()
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
                ApplicationDelegate.shared.application(
                    UIApplication.shared,
                    open: url,
                    sourceApplication: nil,
                    annotation: UIApplication.OpenURLOptionsKey.annotation
                )
            }
        }
    }
}
