import SwiftUI

@main
struct KosherEatsSellerApp: App {
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
        }
    }
}
