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
final class RestaurantStore: ObservableObject {
    @Published var restaurants: [Restaurant] = []
    @Published var featuredRestaurants: [Restaurant] = []
    @Published var favoriteIDs: Set<String> = []
    @Published var isLoading = true
    @Published var errorMessage: String?

    var favoriteRestaurants: [Restaurant] {
        restaurants.filter { favoriteIDs.contains($0.id) }
    }

    let cuisineFilters = [
        "All", "Israeli", "Middle Eastern", "Pizza", "Sushi",
        "Deli", "Steakhouse", "Chinese", "Mexican", "Bakery", "Cafe"
    ]

    private let api = APIService.shared
    private var togglingIDs: Set<String> = []
    private var hasLoadedRestaurants = false
    private var loadTask: Task<Void, Never>?

    func ensureRestaurantsLoaded() async {
        if let loadTask {
            await loadTask.value
            return
        }
        guard !hasLoadedRestaurants else { return }
        await refreshRestaurants()
    }

    func refreshRestaurants() async {
        if let loadTask {
            await loadTask.value
            return
        }

        let task = Task { @MainActor in
            await self.loadRestaurants()
        }
        loadTask = task
        await task.value
    }

    private func loadRestaurants() async {
        isLoading = true
        errorMessage = nil
        defer {
            isLoading = false
            loadTask = nil
        }

        do {
            restaurants = try await api.listRestaurants()
            featuredRestaurants = Array(
                restaurants
                    .filter { $0.isOpen && $0.isActive }
                    .sorted { $0.rating > $1.rating }
                    .prefix(5)
            )
            hasLoadedRestaurants = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // Favorites are temporarily disabled — the /favorites/ids endpoint was
    // returning a shape iOS couldn't decode, surfacing a red error banner on
    // the home screen. Kept as no-ops so existing call sites (heart buttons
    // on RestaurantCardView) still compile; bring the API calls back when
    // the favorites response is fixed.
    func loadFavorites() async {}

    func toggleFavorite(_ restaurantID: String) async {}

    func searchRestaurants(query: String) async throws -> [Restaurant] {
        let results = try await api.searchRestaurants(query: query)
        return filteredRestaurants(
            searchText: query,
            selectedCuisine: nil,
            kosherFilters: KosherFilters(),
            source: results
        )
    }

    func filteredRestaurants(
        searchText: String,
        selectedCuisine: String?,
        kosherFilters: KosherFilters,
        source: [Restaurant]? = nil
    ) -> [Restaurant] {
        let source = source ?? restaurants
        var results = source.filter { $0.isActive }

        if let cuisine = selectedCuisine {
            results = results.filter { restaurant in
                restaurant.cuisineType.contains { $0.localizedCaseInsensitiveContains(cuisine) }
            }
        }

        let trimmedSearch = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedSearch.isEmpty {
            results = results.filter {
                $0.name.localizedCaseInsensitiveContains(trimmedSearch) ||
                $0.description.localizedCaseInsensitiveContains(trimmedSearch)
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

        return results
    }
}
