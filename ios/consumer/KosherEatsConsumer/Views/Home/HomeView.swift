import SwiftUI

struct HomeView: View {
    @StateObject private var vm = HomeViewModel()
    @EnvironmentObject var cartVM: CartViewModel

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

                        // Featured Section
                        if !vm.featuredRestaurants.isEmpty {
                            featuredSection
                        }

                        // All Restaurants
                        allRestaurantsSection
                    }
                    .padding(.bottom, 100) // space for cart button
                }
            }
            .navigationBarHidden(true)
            .task {
                await vm.loadRestaurants()
                await cartVM.loadCart()
            }
            .refreshable {
                await vm.loadRestaurants()
            }
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("KosherEats")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.kePrimary)
                    Text("Delivering kosher, done right")
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                }
                Spacer()
                Image(systemName: "mappin.circle.fill")
                    .font(.system(size: 32))
                    .foregroundColor(.kePrimary)
            }
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    // MARK: - Search Bar

    private var searchBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.keTextMuted)
            TextField("Search restaurants...", text: $vm.searchText)
                .foregroundColor(.keTextPrimary)
                .autocorrectionDisabled()
                .onSubmit {
                    Task { await vm.search() }
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
                        vm.selectCuisine(cuisine)
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
            } else if vm.filteredRestaurants.isEmpty {
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
                    ForEach(vm.filteredRestaurants) { restaurant in
                        NavigationLink(destination: RestaurantDetailView(restaurantID: restaurant.id)) {
                            RestaurantCardView(restaurant: restaurant)
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
                .foregroundColor(isSelected ? .white : .keTextSecondary)
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
            // Image placeholder
            ZStack {
                RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                    .fill(
                        LinearGradient(
                            colors: [.kePrimary.opacity(0.3), .kePrimaryDark.opacity(0.5)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 240, height: 140)

                VStack {
                    Image(systemName: "fork.knife")
                        .font(.system(size: 32))
                        .foregroundColor(.kePrimary)
                }

                // Certification badge
                VStack {
                    HStack {
                        Spacer()
                        KosherBadge(certification: restaurant.kosherCertification, size: .small)
                            .padding(8)
                    }
                    Spacer()
                }
            }
            .frame(width: 240, height: 140)

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
