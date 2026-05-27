import SwiftUI
import Combine

struct MainTabView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var cartVM: CartViewModel
    @EnvironmentObject var router: AppRouter
    @StateObject private var restaurantVM = RestaurantStore()
    @StateObject private var keyboard = KeyboardObserver()
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
        TabView(selection: $router.selectedTab) {
            // Icon-only tabs per design: bottom nav reads as a row of glyphs,
            // no labels. Each .tabItem uses just an Image instead of a Label
            // so the title slot stays empty.
            HomeView()
                .environmentObject(restaurantVM)
                .tabItem { Image(systemName: "house.fill") }
                .tag(AppTab.home)

            NearbyMapView()
                .environmentObject(restaurantVM)
                .tabItem { Image(systemName: "map.fill") }
                .tag(AppTab.nearby)

            DealsView()
                .environmentObject(restaurantVM)
                .tabItem { Image(systemName: "tag.fill") }
                .tag(AppTab.deals)

            // Cart slot — the "what's happening now" tab. Currently maps to
            // OrdersListView's Active segment (in-flight deliveries). Past
            // orders moved into Profile → My Orders. A future iteration can
            // make this tab show the pre-checkout cart contents inline so the
            // floating cart button can go away entirely.
            Group {
                if authVM.isAuthenticated {
                    OrdersListView(
                        pendingTrackingOrderId: $router.pendingTrackingOrderId,
                        pendingDetailOrderId: $router.pendingDetailOrderId
                    )
                } else {
                    AuthRequiredView(title: "Cart", message: "Sign in to track your active deliveries and review past orders.") {
                        showLoginSheet = true
                    }
                }
            }
            .tabItem { Image(systemName: "cart.fill") }
            .tag(AppTab.orders)

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
            .tag(AppTab.profile)
        }
        .sheet(isPresented: $showLoginSheet, onDismiss: {
            // Any path out of the welcome sheet counts as "seen" — signed in,
            // registered, or chose to browse as a guest.
            hasSeenWelcome = true
        }) {
            LoginView()
                .environmentObject(authVM)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
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
                .presentationDetents([.medium, .large])
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
                    .padding(.bottom, keyboard.height > 0 ? keyboard.height + 8 : 56)
                    .animation(.easeOut(duration: 0.25), value: keyboard.height)
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

@MainActor
private final class KeyboardObserver: ObservableObject {
    @Published private(set) var height: CGFloat = 0

    private var cancellables = Set<AnyCancellable>()

    init(notificationCenter: NotificationCenter = .default) {
        let changeFrame = notificationCenter.publisher(for: UIResponder.keyboardWillChangeFrameNotification)
        let willHide = notificationCenter.publisher(for: UIResponder.keyboardWillHideNotification)

        changeFrame
            .merge(with: willHide)
            .compactMap(Self.keyboardHeight(from:))
            .receive(on: RunLoop.main)
            .sink { [weak self] height in
                self?.height = height
            }
            .store(in: &cancellables)
    }

    private static func keyboardHeight(from notification: Notification) -> CGFloat? {
        if notification.name == UIResponder.keyboardWillHideNotification {
            return 0
        }
        guard let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else {
            return nil
        }
        // Use overlap with the screen instead of raw frame.height so split,
        // floating, and undocked keyboards don't push the cart button too far.
        return max(UIScreen.main.bounds.maxY - frame.minY, 0)
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
                Text(String(localized: "View Cart"))
                    .font(.system(size: 16, weight: .semibold))
                Spacer()
                Text("\(itemCount) item\(itemCount == 1 ? "" : "s")")
                    .font(.system(size: 14, weight: .medium))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Color.white.opacity(0.2))
                    .cornerRadius(12)
            }
            .foregroundColor(.keTextOnAccent)
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .background(Color.kePrimary)
            .cornerRadius(Theme.cornerRadiusLarge)
            .shadow(color: Color.kePrimary.opacity(0.4), radius: 12, y: 4)
        }
        .padding(.horizontal, Theme.spacingMD)
        .accessibilityLabel("View Cart, \(itemCount) item\(itemCount == 1 ? "" : "s")")
        .sheet(isPresented: $showCart) {
            CartView()
        }
    }
}

// MARK: - Search Tab View

struct SearchView: View {
    @EnvironmentObject var vm: RestaurantStore
    @State private var searchText = ""
    @State private var searchResults: [Restaurant]? = nil
    @State private var isSearching = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                VStack(spacing: 0) {
                    // Search Bar
                    HStack(spacing: 12) {
                        Image(systemName: "magnifyingglass")
                            .foregroundColor(.keTextMuted)
                        TextField(String(localized: "Search restaurants, cuisines..."), text: $searchText)
                            .foregroundColor(.keTextPrimary)
                            .autocorrectionDisabled()
                            .onSubmit {
                                Task { await runSearch() }
                            }
                        if !searchText.isEmpty {
                            Button {
                                searchText = ""
                                searchResults = nil
                                errorMessage = nil
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

                    if isSearching || vm.isLoading {
                        Spacer()
                        ProgressView().tint(.kePrimary)
                        Spacer()
                    } else if let errorMessage {
                        Spacer()
                        VStack(spacing: 12) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .font(.system(size: 48))
                                .foregroundColor(.keError)
                            Text(errorMessage)
                                .font(.body)
                                .foregroundColor(.keTextSecondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 24)
                        }
                        Spacer()
                    } else if visibleRestaurants.isEmpty {
                        Spacer()
                        VStack(spacing: 12) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 48))
                                .foregroundColor(.keTextMuted)
                            Text(String(localized: "Search for your favorite\nkosher restaurants"))
                                .font(.body)
                                .foregroundColor(.keTextSecondary)
                                .multilineTextAlignment(.center)
                        }
                        Spacer()
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 12) {
                                ForEach(visibleRestaurants) { restaurant in
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
            await vm.ensureRestaurantsLoaded()
        }
    }

    private var visibleRestaurants: [Restaurant] {
        searchResults
            ?? vm.filteredRestaurants(
                searchText: "",
                selectedCuisine: nil,
                kosherFilters: KosherFilters()
            )
    }

    private func runSearch() async {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            searchResults = nil
            errorMessage = nil
            return
        }

        isSearching = true
        errorMessage = nil
        defer { isSearching = false }

        do {
            searchResults = try await vm.searchRestaurants(query: query)
        } catch {
            errorMessage = error.localizedDescription
            searchResults = nil
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
                            .foregroundColor(.keTextOnAccent)
                            .frame(maxWidth: 320, minHeight: 48)
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
