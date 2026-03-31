import SwiftUI

struct MainTabView: View {
    @EnvironmentObject var authVM: AuthViewModel

    var body: some View {
        TabView {
            DashboardView()
                .tabItem {
                    Label("Dashboard", systemImage: "square.grid.2x2.fill")
                }

            SellerOrdersView()
                .tabItem {
                    Label("Orders", systemImage: "list.clipboard.fill")
                }

            MenuManagementView()
                .tabItem {
                    Label("Menu", systemImage: "menucard.fill")
                }

            RestaurantSettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
        }
        .tint(.kePrimary)
    }
}
