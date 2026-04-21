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
            await loadFavorites()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadFavorites() async {
        do {
            let ids = try await api.listFavoriteIDs()
            favoriteIDs = Set(ids)
            errorMessage = nil
        } catch let APIError.httpError(code, _) where code == 404 {
            // "No favorites yet" is a valid empty state, not a banner-worthy
            // failure on the home screen.
            favoriteIDs = []
            errorMessage = nil
        } catch {
            errorMessage = "Couldn't refresh favorites. " +
                ((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }

    func toggleFavorite(_ restaurantID: String) async {
        guard !togglingIDs.contains(restaurantID) else { return }
        togglingIDs.insert(restaurantID)
        defer { togglingIDs.remove(restaurantID) }
        let wasPresent = favoriteIDs.contains(restaurantID)
        if wasPresent {
            favoriteIDs.remove(restaurantID)
        } else {
            favoriteIDs.insert(restaurantID)
        }
        do {
            if wasPresent {
                try await api.removeFavorite(restaurantID: restaurantID)
            } else {
                try await api.addFavorite(restaurantID: restaurantID)
            }
            errorMessage = nil
        } catch {
            if wasPresent {
                favoriteIDs.insert(restaurantID)
            } else {
                favoriteIDs.remove(restaurantID)
            }
            errorMessage = error.localizedDescription
        }
    }

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
