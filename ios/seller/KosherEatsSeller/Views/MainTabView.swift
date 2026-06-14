import SwiftUI

struct MainTabView: View {
    @EnvironmentObject var authVM: AuthViewModel

    /// Stable tab identifiers so a push-driven deep link can switch tabs by
    /// tag rather than relying on whatever was last selected. The raw values
    /// double as the `.tag` for each tab in the `TabView(selection:)` binding.
    private enum Tab: Int {
        case dashboard, orders, menu, deals, settings
    }

    @State private var selectedTab: Tab = .dashboard

    var body: some View {
        TabView(selection: $selectedTab) {
            DashboardView()
                .tabItem {
                    Label("Dashboard", systemImage: "square.grid.2x2.fill")
                }
                .accessibilityIdentifier("tab_dashboard")
                .tag(Tab.dashboard)

            SellerOrdersView()
                .tabItem {
                    Label("Orders", systemImage: "list.clipboard.fill")
                }
                .accessibilityIdentifier("tab_orders")
                .tag(Tab.orders)

            MenuManagementView()
                .tabItem {
                    Label("Menu", systemImage: "menucard.fill")
                }
                .accessibilityIdentifier("tab_menu")
                .tag(Tab.menu)

            DealsView()
                .tabItem {
                    Label("Deals", systemImage: "tag.fill")
                }
                .accessibilityIdentifier("tab_deals")
                .tag(Tab.deals)

            RestaurantSettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
                .accessibilityIdentifier("tab_settings")
                .tag(Tab.settings)
        }
        .tint(.kePrimary)
        // When the seller taps an order push, AppDelegate posts
        // `.orderDeepLinkRequested`; jump to the Orders tab so SellerOrdersView
        // (which also observes the signal) can push the specific ticket.
        // Without this the push would land the seller on whatever tab was last
        // open, mirroring Android's launch-intent routing to Screen.OrderDetail.
        .onReceive(NotificationCenter.default.publisher(for: .orderDeepLinkRequested)) { _ in
            selectedTab = .orders
        }
    }
}
