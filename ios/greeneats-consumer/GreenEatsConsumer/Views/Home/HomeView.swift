import SwiftUI

struct HomeView: View {
    @EnvironmentObject var vm: RestaurantStore
    @EnvironmentObject var cartVM: CartViewModel
    @State private var showKosherFilter = false
    @State private var searchText = ""
    @State private var selectedCuisine: String?
    @State private var kosherFilters = KosherFilters()

    var body: some View {
        NavigationStack {
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

                        // Featured Section
                        if !vm.featuredRestaurants.isEmpty {
                            featuredSection
                        }

                        // Favorites Section
                        if !vm.favoriteRestaurants.isEmpty {
                            favoritesSection
                        }

                        // All Restaurants
                        allRestaurantsSection
                    }
                    .padding(.bottom, 100) // space for cart button
                }
                .safeAreaPadding(.top, Theme.spacingSM)
            }
            .navigationBarHidden(true)
            .task {
                await vm.ensureRestaurantsLoaded()
                await cartVM.loadCart()
            }
            .refreshable {
                Haptics.impact(.light)
                await vm.refreshRestaurants()
            }
            .sheet(isPresented: $showKosherFilter) {
                KosherFilterSheet(
                    isPresented: $showKosherFilter,
                    allRestaurants: vm.restaurants,
                    currentFilters: kosherFilters,
                    onApply: { kosherFilters = $0 },
                )
            }
        }
    }

    private var filteredRestaurants: [Restaurant] {
        vm.filteredRestaurants(
            searchText: searchText,
            selectedCuisine: selectedCuisine,
            kosherFilters: kosherFilters
        )
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("GreenEats")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.kePrimary)
                    Text("Delivering vegan, done right")
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
            TextField("Search restaurants...", text: $searchText)
                .foregroundColor(.keTextPrimary)
                .autocorrectionDisabled()
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
                        isSelected: selectedCuisine == cuisine || (cuisine == "All" && selectedCuisine == nil)
                    ) {
                        if cuisine == "All" {
                            selectedCuisine = nil
                        } else {
                            selectedCuisine = selectedCuisine == cuisine ? nil : cuisine
                        }
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
                    ForEach(vm.featuredRestaurants) { restaurant in
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
                ForEach(vm.favoriteRestaurants) { restaurant in
                    NavigationLink(destination: RestaurantDetailView(restaurantID: restaurant.id)) {
                        RestaurantCardView(
                            restaurant: restaurant,
                            isFavorite: true,
                            onToggleFavorite: { Task { await vm.toggleFavorite(restaurant.id) } }
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

            if vm.isLoading {
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
                                onToggleFavorite: { Task { await vm.toggleFavorite(restaurant.id) } }
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
                KosherBadge(certification: restaurant.kosherCertification, size: .small)
                    .padding(8)
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
