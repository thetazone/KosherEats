import Foundation
import SwiftUI

/// Filter state for the kosher filter sheet. Each field narrows the result
/// set — empty Set / false means "no constraint", not "exclude everything".
struct KosherFilters: Equatable {
    var certifications: Set<KosherCertification> = []
    var glattOnly: Bool = false
    var cholovYisroelOnly: Bool = false
    var pasYisroelOnly: Bool = false

    var isActive: Bool {
        !certifications.isEmpty || glattOnly || cholovYisroelOnly || pasYisroelOnly
    }

    /// Count of active filters — drives the "3" badge on the filter button.
    var activeCount: Int {
        var n = certifications.count
        if glattOnly { n += 1 }
        if cholovYisroelOnly { n += 1 }
        if pasYisroelOnly { n += 1 }
        return n
    }
}

@MainActor
class HomeViewModel: ObservableObject {
    @Published var restaurants: [Restaurant] = []
    @Published var filteredRestaurants: [Restaurant] = []
    @Published var featuredRestaurants: [Restaurant] = []
    @Published var favoriteIDs: Set<String> = []
    @Published var searchText = ""
    @Published var selectedCuisine: String?
    @Published var kosherFilters = KosherFilters()
    @Published var isLoading = false
    @Published var errorMessage: String?

    var favoriteRestaurants: [Restaurant] {
        restaurants.filter { favoriteIDs.contains($0.id) }
    }

    let cuisineFilters = [
        "All", "Israeli", "Middle Eastern", "Pizza", "Sushi",
        "Deli", "Steakhouse", "Chinese", "Mexican", "Bakery", "Cafe"
    ]

    private let api = APIService.shared

    func loadRestaurants() async {
        isLoading = true
        errorMessage = nil

        do {
            restaurants = try await api.listRestaurants()
            featuredRestaurants = Array(
                restaurants
                    .filter { $0.isOpen && $0.isActive }
                    .sorted { $0.rating > $1.rating }
                    .prefix(5)
            )
            applyFilters()
            await loadFavorites()
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func loadFavorites() async {
        if let ids = try? await APIService.shared.listFavoriteIDs() {
            favoriteIDs = Set(ids)
        }
    }

    func toggleFavorite(_ restaurantID: String) async {
        if favoriteIDs.contains(restaurantID) {
            favoriteIDs.remove(restaurantID)
            try? await APIService.shared.removeFavorite(restaurantID: restaurantID)
        } else {
            favoriteIDs.insert(restaurantID)
            try? await APIService.shared.addFavorite(restaurantID: restaurantID)
        }
    }

    func search() async {
        guard !searchText.trimmingCharacters(in: .whitespaces).isEmpty else {
            applyFilters()
            return
        }

        isLoading = true
        do {
            let results = try await api.searchRestaurants(query: searchText)
            filteredRestaurants = results
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    func selectCuisine(_ cuisine: String) {
        if cuisine == "All" {
            selectedCuisine = nil
        } else {
            selectedCuisine = selectedCuisine == cuisine ? nil : cuisine
        }
        applyFilters()
    }

    /// Applies an updated kosher filter set and refreshes the list.
    func setKosherFilters(_ filters: KosherFilters) {
        kosherFilters = filters
        applyFilters()
    }

    func clearKosherFilters() {
        kosherFilters = KosherFilters()
        applyFilters()
    }

    /// Made internal so callers (like the filter sheet's "preview X results"
    /// footer) can compute counts against the current filter set.
    func applyFilters() {
        var results = restaurants.filter { $0.isActive }

        if let cuisine = selectedCuisine {
            results = results.filter { restaurant in
                restaurant.cuisineType.contains { $0.localizedCaseInsensitiveContains(cuisine) }
            }
        }

        if !searchText.isEmpty {
            results = results.filter {
                $0.name.localizedCaseInsensitiveContains(searchText) ||
                $0.description.localizedCaseInsensitiveContains(searchText)
            }
        }

        // Kosher-specific filters. All of these are AND-combined so the user
        // can build a precise query like "Glatt + Cholov Yisroel + OU or cRc".
        if !kosherFilters.certifications.isEmpty {
            results = results.filter { kosherFilters.certifications.contains($0.kosherCertification) }
        }
        if kosherFilters.glattOnly {
            results = results.filter { $0.isGlattKosher }
        }
        if kosherFilters.cholovYisroelOnly {
            results = results.filter { $0.isCholovYisroel }
        }
        if kosherFilters.pasYisroelOnly {
            results = results.filter { $0.isPasYisroel }
        }

        filteredRestaurants = results
    }
}
