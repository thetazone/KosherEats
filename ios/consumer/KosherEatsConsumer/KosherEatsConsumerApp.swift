import SwiftUI
import GoogleSignIn
import FacebookCore

@main
struct KosherEatsConsumerApp: App {
    @StateObject private var authVM = AuthViewModel()
    @StateObject private var cartVM = CartViewModel()

    init() {
        ApplicationDelegate.shared.application(UIApplication.shared, didFinishLaunchingWithOptions: nil)
    }

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
