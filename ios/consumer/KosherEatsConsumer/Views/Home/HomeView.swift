import SwiftUI

struct HomeView: View {
    @StateObject private var vm = HomeViewModel()
    @EnvironmentObject var cartVM: CartViewModel
    @State private var showKosherFilter = false

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
                Haptics.impact(.light)
                await vm.loadRestaurants()
            }
            .sheet(isPresented: $showKosherFilter) {
                KosherFilterSheet(
                    isPresented: $showKosherFilter,
                    allRestaurants: vm.restaurants,
                    currentFilters: vm.kosherFilters,
                    onApply: { vm.setKosherFilters($0) },
                )
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
                    .foregroundColor(vm.kosherFilters.isActive ? .kePrimary : .keTextSecondary)

                if vm.kosherFilters.activeCount > 0 {
                    Text("\(vm.kosherFilters.activeCount)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.white)
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
