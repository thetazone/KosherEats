import SwiftUI

@main
struct KosherEatsConsumerApp: App {
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
        }
    }
}
