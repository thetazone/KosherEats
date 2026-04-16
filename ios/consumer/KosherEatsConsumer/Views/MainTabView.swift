import SwiftUI

struct MainTabView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var cartVM: CartViewModel
    @State private var selectedTab = 0
    @State private var pendingTrackingOrderId: String?
    @State private var pendingDetailOrderId: String?
    @State private var showLoginSheet = false
    // Latches when the user taps "Not now" on ProfileCompletionSheet. Without
    // this the sheet would re-present immediately because needsProfileCompletion
    // is still true on the user record. Resets per app launch — checkout flow
    // can re-prompt by setting needsProfileCompletion when it actually matters.
    @State private var profileSheetDismissed = false
    // First-launch welcome: show LoginView once, unless the user is already
    // signed in. Dismissing the sheet (sign-in, register, or "Continue as
    // Guest") flips this flag so we don't nag on subsequent launches.
    @AppStorage("hasSeenWelcome") private var hasSeenWelcome = false

    var body: some View {
        TabView(selection: $selectedTab) {
            HomeView()
                .tabItem { Image(systemName: "house.fill") }
                .tag(0)

            NearbyMapView()
                .tabItem { Image(systemName: "map.fill") }
                .tag(1)

            SearchView()
                .tabItem { Image(systemName: "magnifyingglass") }
                .tag(2)

            Group {
                if authVM.isAuthenticated {
                    OrdersListView()
                } else {
                    AuthRequiredView(title: "Your Orders", message: "Sign in to view your order history and track deliveries.") {
                        showLoginSheet = true
                    }
                }
            }
            .tabItem { Image(systemName: "cart.fill") }
            .badge(cartVM.itemCount)
            .tag(3)

            Group {
                if authVM.isAuthenticated {
                    ProfileView()
                } else {
                    AuthRequiredView(title: "Profile", message: "Sign in to manage your profile, addresses, and preferences.") {
                        showLoginSheet = true
                    }
                }
            }
            .tabItem { Image(systemName: "person.fill") }
            .tag(4)
        }
        .sheet(isPresented: $showLoginSheet, onDismiss: {
            // Any path out of the welcome sheet counts as "seen" — signed in,
            // registered, or chose to browse as a guest.
            hasSeenWelcome = true
        }) {
            LoginView()
                .environmentObject(authVM)
        }
        .sheet(isPresented: Binding(
            // Triggered when an authenticated user is still missing core
            // profile data — e.g. Apple sign-in where `fullName` was nil or
            // the email is a @privaterelay.appleid.com forwarder. Cleared
            // automatically once the PUT /user/profile response fills those in,
            // or when the user taps "Not now" (consumer-only escape hatch).
            get: { authVM.isAuthenticated && authVM.needsProfileCompletion && !profileSheetDismissed },
            set: { newValue in
                if !newValue { profileSheetDismissed = true }
            }
        )) {
            ProfileCompletionSheet()
                .environmentObject(authVM)
        }
        .tint(.kePrimary)
        .onAppear {
            configureTabBarAppearance()
            // First launch: present the welcome/login sheet. Skip for users
            // who already have a session (e.g. reinstall + restored keychain).
            if !hasSeenWelcome && !authVM.isAuthenticated {
                showLoginSheet = true
            }
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
            selectedTab = 3
        }
        .onReceive(NotificationCenter.default.publisher(for: .navigateToOrderTracking)) { note in
            if let id = note.userInfo?["order_id"] as? String {
                selectedTab = 3
                pendingTrackingOrderId = id
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .navigateToOrderDetail)) { note in
            if let id = note.userInfo?["order_id"] as? String {
                selectedTab = 3
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
        }
        .task {
            await vm.loadRestaurants()
        }
    }
}

// MARK: - Auth Required Placeholder

struct AuthRequiredView: View {
    let title: String
    let message: String
    let onSignIn: () -> Void

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                VStack(spacing: 20) {
                    Spacer()

                    Image(systemName: "person.crop.circle.badge.questionmark")
                        .font(.system(size: 64))
                        .foregroundColor(.keTextMuted)

                    Text(message)
                        .font(.body)
                        .foregroundColor(.keTextSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)

                    Button(action: onSignIn) {
                        Text("Sign In")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(width: 200, height: 48)
                            .background(Color.kePrimary)
                            .cornerRadius(Theme.cornerRadiusMedium)
                    }

                    Spacer()
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.large)
        }
    }
}
