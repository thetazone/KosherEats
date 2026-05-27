import SwiftUI

struct MainTabView: View {
    @EnvironmentObject var authVM: AuthViewModel

    var body: some View {
        TabView {
            DashboardView()
                .tabItem {
                    Label("Dashboard", systemImage: "square.grid.2x2.fill")
                }
                .accessibilityIdentifier("tab_dashboard")

            SellerOrdersView()
                .tabItem {
                    Label("Orders", systemImage: "list.clipboard.fill")
                }
                .accessibilityIdentifier("tab_orders")

            MenuManagementView()
                .tabItem {
                    Label("Menu", systemImage: "menucard.fill")
                }
                .accessibilityIdentifier("tab_menu")

            DealsView()
                .tabItem {
                    Label("Deals", systemImage: "tag.fill")
                }
                .accessibilityIdentifier("tab_deals")

            RestaurantSettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
                .accessibilityIdentifier("tab_settings")
        }
        .tint(.kePrimary)
    }
}
