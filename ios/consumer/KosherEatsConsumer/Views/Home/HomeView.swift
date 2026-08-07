import SwiftUI

struct HomeView: View {
    @EnvironmentObject var vm: RestaurantStore
    @EnvironmentObject var cartVM: CartViewModel
    @EnvironmentObject var router: AppRouter
    @EnvironmentObject var authVM: AuthViewModel
    @State private var showKosherFilter = false
    @State private var searchText = ""
    @State private var kosherFilters = KosherFilters()
    @State private var isFilterTransitioning = false
    @State private var deepLinkPath: [String] = []
    // Presented when a signed-out user taps a preview listing's Request
    // control — same auth-gate pattern as checkout.
    @State private var showLoginSheet = false

    // Server-backed search. While the user is typing we debounce by 300ms and
    // hit GET /restaurants/search, which matches the whole catalog by name OR
    // cuisine — unlike the local-only name/description filter that only sees the
    // first 50 restaurants from GET /restaurants. nil means "not searching"
    // (show the curated home list); an empty array means "searched, no matches".
    @State private var searchResults: [Restaurant]?
    @State private var isSearching = false
    @State private var searchTask: Task<Void, Never>?

    var body: some View {
        NavigationStack(path: $deepLinkPath) {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: Theme.spacingLG) {
                        // Header
                        headerSection

                        // Search Bar
                        searchBar

                        // Cuisine Filters
                        cuisineFilterRow

                        if let errorMessage = vm.errorMessage, !vm.restaurants.isEmpty {
                            InlineErrorBanner(
                                message: errorMessage,
                                onRetry: { Task { await vm.refreshRestaurants() } }
                            )
                            .padding(.horizontal)
                        }

                        // Curated home sections are hidden while searching so the
                        // results list isn't buried under unrelated carousels.
                        if !isSearchActive {
                            // Featured Section
                            if !visibleFeaturedRestaurants.isEmpty {
                                featuredSection
                            }

                            // Favorites Section
                            if !visibleFavoriteRestaurants.isEmpty {
                                favoritesSection
                            }
                        }

                        // All Restaurants
                        allRestaurantsSection
                    }
                    .padding(.bottom, 100) // space for cart button
                }
                .safeAreaPadding(.top, Theme.spacingSM)
            }
            .navigationBarHidden(true)
            .navigationDestination(for: String.self) { id in
                RestaurantDetailView(restaurantID: id)
            }
            .onChange(of: router.pendingRestaurantId) { _, id in
                if let id {
                    deepLinkPath = [id]
                    router.pendingRestaurantId = nil
                }
            }
            .task {
                await vm.ensureRestaurantsLoaded()
                await cartVM.loadCart()
            }
            .refreshable {
                Haptics.impact(.light)
                await vm.refreshRestaurants()
            }
            .sheet(isPresented: $showLoginSheet) {
                LoginView(dismissLabel: "Back")
                    .environmentObject(authVM)
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showKosherFilter) {
                KosherFilterSheet(
                    isPresented: $showKosherFilter,
                    allRestaurants: vm.restaurants,
                    currentFilters: kosherFilters,
                    onApply: { newFilters in
                        isFilterTransitioning = true
                        kosherFilters = newFilters
                        // Re-run an active search so server results reflect the
                        // newly-applied kosher constraints, not the ones in
                        // effect when the query was first typed.
                        if isSearchActive {
                            scheduleSearch(for: searchText)
                        }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                            withAnimation(.easeOut(duration: 0.2)) {
                                isFilterTransitioning = false
                            }
                        }
                    },
                )
            }
        }
    }

    /// Whether the user is actively running a text search. When true the home
    /// shows server search results in place of the curated home sections.
    private var isSearchActive: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var filteredRestaurants: [Restaurant] {
        // While searching, drive the list off the server response (whole catalog,
        // matched by name OR cuisine) and only narrow it locally by the cuisine
        // chip — the kosher filters were already applied server-side via
        // searchRestaurants(query:kosherFilters:).
        if isSearchActive {
            let results = searchResults ?? []
            guard let cuisine = vm.selectedCuisine else { return results }
            return results.filter { restaurant in
                restaurant.cuisineType.contains { $0.localizedCaseInsensitiveContains(cuisine) }
            }
        }
        // The cuisine chip is applied server-side (?cuisine=), so the local
        // pass is only a safety net over an already-filtered list.
        return vm.filteredRestaurants(
            searchText: "",
            selectedCuisine: vm.selectedCuisine,
            kosherFilters: kosherFilters
        )
    }

    /// Auth-gated Request toggle for preview listings — mirrors the checkout
    /// gate: signed-out users get the login sheet instead of a failed call.
    /// After the store reconciles with the server, any active search-results
    /// copy of the row is updated too so the card doesn't snap back.
    private func handleRequestTap(_ restaurantID: String) {
        guard authVM.isAuthenticated else {
            showLoginSheet = true
            return
        }
        Haptics.impact(.light)
        Task {
            guard let result = await vm.toggleRequest(restaurantID) else { return }
            if var results = searchResults,
               let idx = results.firstIndex(where: { $0.id == restaurantID }) {
                results[idx].requestedByMe = result.requested
                results[idx].requestCount = result.requestCount
                searchResults = results
            }
        }
    }

    /// Featured carousel, gated through the active kosher filters so the curated
    /// row never surfaces restaurants the user has filtered out.
    private var visibleFeaturedRestaurants: [Restaurant] {
        guard kosherFilters.isActive else { return vm.featuredRestaurants }
        return vm.featuredRestaurants.filter { matchesKosherFilters($0) }
    }

    /// Favorites list, gated through the active kosher filters for the same
    /// reason as the featured carousel.
    private var visibleFavoriteRestaurants: [Restaurant] {
        guard kosherFilters.isActive else { return vm.favoriteRestaurants }
        return vm.favoriteRestaurants.filter { matchesKosherFilters($0) }
    }

    /// Mirror of the kosher predicate applied in RestaurantStore.filteredRestaurants
    /// so the curated home sections honour the same constraints as the main list.
    private func matchesKosherFilters(_ restaurant: Restaurant) -> Bool {
        if !kosherFilters.certifications.isEmpty,
           !kosherFilters.certifications.contains(restaurant.kosherCertification) {
            return false
        }
        if kosherFilters.glattOnly, !restaurant.isGlattKosher { return false }
        if kosherFilters.cholovYisroelOnly, !restaurant.isCholovYisroel { return false }
        if kosherFilters.pasYisroelOnly, !restaurant.isPasYisroel { return false }
        return true
    }

    // MARK: - Search (server-backed, debounced)

    /// Debounce keystrokes by 300ms, then query the server. Mirrors Android's
    /// HomeViewModel.search. An empty query clears the search state and returns
    /// the user to the curated home list.
    private func scheduleSearch(for query: String) {
        searchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            searchTask = nil
            isSearching = false
            searchResults = nil
            return
        }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            isSearching = true
            defer { isSearching = false }
            do {
                let results = try await vm.searchRestaurants(query: trimmed, kosherFilters: kosherFilters)
                if Task.isCancelled { return }
                searchResults = results
            } catch {
                if Task.isCancelled { return }
                // Keep whatever results we had rather than wiping the list on a
                // transient failure; the All Restaurants empty-state covers a
                // genuinely empty first search.
                searchResults = searchResults ?? []
            }
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "KosherEats"))
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.kePrimary)
                    Text(String(localized: "Delivering kosher, done right"))
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                }
                Spacer()
                kosherFilterButton
            }
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    /// Filter button with a live count badge matching the UberEats pattern.
    /// Tapping opens the kosher filter sheet; the badge glows if any filters
    /// are currently applied so the user always knows the list is filtered.
    private var kosherFilterButton: some View {
        Button {
            showKosherFilter = true
        } label: {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "line.3.horizontal.decrease.circle.fill")
                    .font(.system(size: 32))
                    .foregroundColor(kosherFilters.isActive ? .kePrimary : .keTextSecondary)

                if kosherFilters.activeCount > 0 {
                    Text("\(kosherFilters.activeCount)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.keTextOnAccent)
                        .frame(minWidth: 16, minHeight: 16)
                        .background(Color.kePrimary)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.keBackground, lineWidth: 2))
                        .offset(x: 4, y: -4)
                }
            }
        }
    }

    // MARK: - Search Bar

    private var searchBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.keTextMuted)
            TextField(String(localized: "Search restaurants, cuisines..."), text: $searchText)
                .foregroundColor(.keTextPrimary)
                .autocorrectionDisabled()
                .onChange(of: searchText) { _, newValue in
                    scheduleSearch(for: newValue)
                }
            if !searchText.isEmpty {
                Button {
                    searchText = ""
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
    }

    // MARK: - Cuisine Filters

    private var cuisineFilterRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(vm.cuisineFilters, id: \.self) { cuisine in
                    CuisineChip(
                        title: cuisine,
                        isSelected: vm.selectedCuisine == cuisine || (cuisine == "All" && vm.selectedCuisine == nil)
                    ) {
                        // Server-side filter: selecting a chip refetches with
                        // ?cuisine=<tag>; "All" (or re-tapping) clears it. The
                        // store's isLoading drives the loading state.
                        let next = (cuisine == "All" || vm.selectedCuisine == cuisine) ? nil : cuisine
                        Task { await vm.selectCuisine(next) }
                    }
                }
            }
            .padding(.horizontal)
        }
    }

    // MARK: - Featured

    private var featuredSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Featured")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.keTextPrimary)
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(visibleFeaturedRestaurants) { restaurant in
                        NavigationLink(destination: RestaurantDetailView(restaurantID: restaurant.id)) {
                            FeaturedRestaurantCard(restaurant: restaurant)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    // MARK: - Favorites

    private var favoritesSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Favorites")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.keTextPrimary)
                .padding(.horizontal)

            LazyVStack(spacing: 12) {
                ForEach(visibleFavoriteRestaurants) { restaurant in
                    NavigationLink(destination: RestaurantDetailView(restaurantID: restaurant.id)) {
                        RestaurantCardView(
                            restaurant: restaurant,
                            isFavorite: true,
                            onToggleFavorite: { Task { await vm.toggleFavorite(restaurant.id) } },
                            onToggleRequest: { handleRequestTap(restaurant.id) }
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
        }
    }

    // MARK: - All Restaurants

    private var allRestaurantsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("All Restaurants")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.keTextPrimary)
                .padding(.horizontal)

            if vm.isLoading || isFilterTransitioning || isSearching {
                ProgressView()
                    .tint(.kePrimary)
                    .frame(maxWidth: .infinity, minHeight: 200)
            } else if let errorMessage = vm.errorMessage, vm.restaurants.isEmpty {
                ErrorStateView(
                    message: errorMessage,
                    onRetry: { Task { await vm.refreshRestaurants() } }
                )
                .frame(minHeight: 280)
            } else if filteredRestaurants.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "fork.knife.circle")
                        .font(.system(size: 48))
                        .foregroundColor(.keTextMuted)
                    Text("No restaurants found")
                        .font(.body)
                        .foregroundColor(.keTextSecondary)
                }
                .frame(maxWidth: .infinity, minHeight: 200)
            } else {
                LazyVStack(spacing: 12) {
                    ForEach(filteredRestaurants) { restaurant in
                        NavigationLink(destination: RestaurantDetailView(restaurantID: restaurant.id)) {
                            RestaurantCardView(
                                restaurant: restaurant,
                                isFavorite: vm.favoriteIDs.contains(restaurant.id),
                                onToggleFavorite: { Task { await vm.toggleFavorite(restaurant.id) } },
                                onToggleRequest: { handleRequestTap(restaurant.id) }
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }
}

// MARK: - Cuisine Chip

struct CuisineChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(isSelected ? .keTextOnAccent : .keTextSecondary)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(
                    RoundedRectangle(cornerRadius: 20)
                        .fill(isSelected ? Color.kePrimary : Color.keCard)
                )
        }
    }
}

// MARK: - Featured Restaurant Card

struct FeaturedRestaurantCard: View {
    let restaurant: Restaurant

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack(alignment: .topTrailing) {
                RemoteImage(url: restaurant.coverImageURL ?? restaurant.imageURL)
                    .frame(width: 240, height: 140)
                    .cornerRadius(Theme.cornerRadiusMedium)
                    .overlay(
                        RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                            .fill(LinearGradient(
                                colors: [.clear, .black.opacity(0.3)],
                                startPoint: .top,
                                endPoint: .bottom,
                            )),
                    )
                if restaurant.hasKosherCertification {
                    KosherBadge(certification: restaurant.kosherCertification, size: .small)
                        .padding(8)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(restaurant.name)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                    .lineLimit(1)

                HStack(spacing: 8) {
                    HStack(spacing: 3) {
                        Image(systemName: "star.fill")
                            .font(.system(size: 11))
                            .foregroundColor(.kePrimary)
                        Text(restaurant.ratingFormatted)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.keTextPrimary)
                    }
                    Text(restaurant.deliveryTimeFormatted)
                        .font(.system(size: 13))
                        .foregroundColor(.keTextSecondary)
                    Text(restaurant.deliveryFeeFormatted)
                        .font(.system(size: 13))
                        .foregroundColor(.keTextSecondary)
                }
            }
            .padding(.horizontal, 4)
        }
        .frame(width: 240)
    }
}
