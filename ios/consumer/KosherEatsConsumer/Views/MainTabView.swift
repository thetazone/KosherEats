import SwiftUI

struct MainTabView: View {
    @EnvironmentObject var cartVM: CartViewModel
    @State private var selectedTab = 0
    @State private var pendingTrackingOrderId: String?
    @State private var pendingDetailOrderId: String?

    var body: some View {
        TabView(selection: $selectedTab) {
            HomeView()
                .tabItem {
                    Image(systemName: "house.fill")
                    Text("Home")
                }
                .tag(0)

            SearchView()
                .tabItem {
                    Image(systemName: "magnifyingglass")
                    Text("Search")
                }
                .tag(1)

            OrdersListView()
                .tabItem {
                    Image(systemName: "list.bullet.rectangle")
                    Text("Orders")
                }
                .tag(2)

            ProfileView()
                .tabItem {
                    Image(systemName: "person.fill")
                    Text("Profile")
                }
                .tag(3)
        }
        .tint(.kePrimary)
        .onAppear {
            configureTabBarAppearance()
        }
        .overlay(alignment: .bottom) {
            if !cartVM.isEmpty {
                CartFloatingButton(itemCount: cartVM.itemCount)
                    .padding(.bottom, 56)
            }
        }
        // App-wide navigation event listeners. Checkout completion and push
        // notification taps all land here — this is the one place in the app
        // that owns cross-tab navigation.
        .onReceive(NotificationCenter.default.publisher(for: .navigateToOrdersTab)) { _ in
            selectedTab = 2
        }
        .onReceive(NotificationCenter.default.publisher(for: .navigateToOrderTracking)) { note in
            if let id = note.userInfo?["order_id"] as? String {
                selectedTab = 2
                pendingTrackingOrderId = id
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .navigateToOrderDetail)) { note in
            if let id = note.userInfo?["order_id"] as? String {
                selectedTab = 2
                pendingDetailOrderId = id
            }
        }
    }

    private func configureTabBarAppearance() {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor(Color.keBackgroundElevated)
        appearance.stackedLayoutAppearance.normal.iconColor = UIColor(Color.keTextMuted)
        appearance.stackedLayoutAppearance.normal.titleTextAttributes = [.foregroundColor: UIColor(Color.keTextMuted)]
        appearance.stackedLayoutAppearance.selected.iconColor = UIColor(Color.kePrimary)
        appearance.stackedLayoutAppearance.selected.titleTextAttributes = [.foregroundColor: UIColor(Color.kePrimary)]
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }
}

// MARK: - Cart Floating Button

struct CartFloatingButton: View {
    let itemCount: Int
    @State private var showCart = false

    var body: some View {
        Button {
            showCart = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "cart.fill")
                    .font(.system(size: 18, weight: .semibold))
                Text("View Cart")
                    .font(.system(size: 16, weight: .semibold))
                Spacer()
                Text("\(itemCount) item\(itemCount == 1 ? "" : "s")")
                    .font(.system(size: 14, weight: .medium))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Color.white.opacity(0.2))
                    .cornerRadius(12)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .background(Color.kePrimary)
            .cornerRadius(Theme.cornerRadiusLarge)
            .shadow(color: Color.kePrimary.opacity(0.4), radius: 12, y: 4)
        }
        .padding(.horizontal, Theme.spacingMD)
        .sheet(isPresented: $showCart) {
            CartView()
        }
    }
}

// MARK: - Search Tab View

struct SearchView: View {
    @StateObject private var vm = HomeViewModel()
    @State private var searchText = ""

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                VStack(spacing: 0) {
                    // Search Bar
                    HStack(spacing: 12) {
                        Image(systemName: "magnifyingglass")
                            .foregroundColor(.keTextMuted)
                        TextField("Search restaurants, cuisines...", text: $searchText)
                            .foregroundColor(.keTextPrimary)
                            .autocorrectionDisabled()
                            .onSubmit {
                                vm.searchText = searchText
                                Task { await vm.search() }
                            }
                        if !searchText.isEmpty {
                            Button {
                                searchText = ""
                                vm.searchText = ""
                                Task { await vm.loadRestaurants() }
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(.keTextMuted)
                            }
                        }
                    }
                    .padding()
                    .background(Color.keCard)
                    .cornerRadius(Theme.cornerRadiusMedium)
                    .padding(.horizontal)
                    .padding(.top, 8)

                    if vm.filteredRestaurants.isEmpty && !vm.isLoading {
                        Spacer()
                        VStack(spacing: 12) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 48))
                                .foregroundColor(.keTextMuted)
                            Text("Search for your favorite\nkosher restaurants")
                                .font(.body)
                                .foregroundColor(.keTextSecondary)
                                .multilineTextAlignment(.center)
                        }
                        Spacer()
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 12) {
                                ForEach(vm.filteredRestaurants) { restaurant in
                                    NavigationLink(destination: RestaurantDetailView(restaurantID: restaurant.id)) {
                                        RestaurantCardView(restaurant: restaurant)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding()
                        }
                    }
                }
            }
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.large)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
        .task {
            await vm.loadRestaurants()
        }
    }
}
