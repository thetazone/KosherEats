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
    /// Server-side cuisine filter (GET /restaurants/?cuisine=<tag>). nil means
    /// "All". Selecting a chip refetches the list from the server rather than
    /// narrowing the already-loaded page locally.
    @Published var selectedCuisine: String?

    var favoriteRestaurants: [Restaurant] {
        restaurants.filter { favoriteIDs.contains($0.id) }
    }

    /// Known backend cuisine tags — GET /restaurants/?cuisine= matches these
    /// case-insensitively server-side. "All" clears the filter.
    let cuisineFilters = [
        "All", "Israeli", "Grill", "Pizza", "Sushi", "Asian", "Cafe", "Deli",
        "Bagels", "BBQ", "Burgers", "Steakhouse", "Meat", "Dairy", "Pareve",
        "Takeout", "Heimish"
    ]

    private let api = APIService.shared
    private var togglingIDs: Set<String> = []
    private var requestTogglingIDs: Set<String> = []
    private var hasLoadedRestaurants = false
    private var loadTask: Task<Void, Never>?
    /// Bumped on every load; stale loads (superseded by a newer cuisine
    /// selection or refresh) check it and drop their results instead of
    /// clobbering the newer fetch's state.
    private var loadGeneration = 0

    func ensureRestaurantsLoaded() async {
        if let existing = loadTask {
            await existing.value
            return
        }
        guard !hasLoadedRestaurants else { return }
        let task = Task { @MainActor in
            await self.loadRestaurants()
        }
        loadTask = task
        await task.value
    }

    func refreshRestaurants() async {
        if let existing = loadTask {
            await existing.value
            return
        }

        let task = Task { @MainActor in
            await self.loadRestaurants()
        }
        loadTask = task
        await task.value
    }

    /// Selects a server-side cuisine filter and refetches. Pass nil (or "All")
    /// to clear. Always starts a fresh load — an in-flight fetch for the old
    /// cuisine is superseded via `loadGeneration` rather than awaited.
    func selectCuisine(_ cuisine: String?) async {
        let normalized = (cuisine == "All") ? nil : cuisine
        guard normalized != selectedCuisine else { return }
        selectedCuisine = normalized
        let task = Task { @MainActor in
            await self.loadRestaurants()
        }
        loadTask = task
        await task.value
    }

    private func loadRestaurants() async {
        loadGeneration &+= 1
        let gen = loadGeneration

        isLoading = true
        errorMessage = nil

        do {
            let list = try await api.listRestaurants(cuisine: selectedCuisine)
            guard gen == loadGeneration else { return }
            // Server order is meaningful: orderable restaurants always come
            // first, preview listings after. Never re-sort previews above
            // orderable rows client-side.
            restaurants = list
            featuredRestaurants = Array(
                list
                    .filter { $0.orderable && $0.isOpen && $0.isActive }
                    .sorted { $0.rating > $1.rating }
                    .prefix(5)
            )
            hasLoadedRestaurants = true
        } catch {
            guard gen == loadGeneration else { return }
            errorMessage = error.isBenignCancellation ? nil : error.localizedDescription
        }

        guard gen == loadGeneration else { return }
        isLoading = false
        loadTask = nil

        // Load favorites alongside the restaurant list so the heart buttons
        // reflect real state. Deliberately AFTER the restaurants fetch and
        // isolated below so a favorites failure (e.g. a logged-out 401) never
        // populates errorMessage / the home error banner.
        await loadFavorites()
    }

    /// Fetches the user's favorite restaurant IDs. Failures are swallowed on
    /// purpose: favorites are a non-critical enhancement and a logged-out user
    /// gets a 401 here — surfacing that would wrongly red-banner the home feed.
    func loadFavorites() async {
        do {
            favoriteIDs = Set(try await api.listFavoriteIDs())
        } catch {
            // Leave favoriteIDs as-is; don't touch errorMessage.
        }
    }

    /// Optimistically toggles a favorite, reverting on failure. `togglingIDs`
    /// guards against a double-tap firing two in-flight requests for the same
    /// restaurant.
    func toggleFavorite(_ restaurantID: String) async {
        guard !togglingIDs.contains(restaurantID) else { return }
        togglingIDs.insert(restaurantID)
        defer { togglingIDs.remove(restaurantID) }

        let wasFavorite = favoriteIDs.contains(restaurantID)
        if wasFavorite {
            favoriteIDs.remove(restaurantID)
        } else {
            favoriteIDs.insert(restaurantID)
        }

        do {
            if wasFavorite {
                try await api.removeFavorite(restaurantID: restaurantID)
            } else {
                try await api.addFavorite(restaurantID: restaurantID)
            }
        } catch {
            // Revert the optimistic change so the UI matches the server.
            if wasFavorite {
                favoriteIDs.insert(restaurantID)
            } else {
                favoriteIDs.remove(restaurantID)
            }
        }
    }

    /// Optimistically toggles a "request this restaurant" vote on a preview
    /// listing, then reconciles with the server's {requested, request_count}
    /// response (reverting on failure). Returns the reconciled state so callers
    /// holding their own Restaurant copies (e.g. search results) can mirror it,
    /// or nil when the request failed. The API call is made even when the
    /// restaurant isn't in the loaded list — search can surface rows beyond it.
    @discardableResult
    func toggleRequest(_ restaurantID: String) async -> (requested: Bool, requestCount: Int)? {
        guard !requestTogglingIDs.contains(restaurantID) else { return nil }
        requestTogglingIDs.insert(restaurantID)
        defer { requestTogglingIDs.remove(restaurantID) }

        let original = restaurants.first(where: { $0.id == restaurantID })
        if let idx = restaurants.firstIndex(where: { $0.id == restaurantID }) {
            let requested = !restaurants[idx].requestedByMe
            restaurants[idx].requestedByMe = requested
            restaurants[idx].requestCount = max(restaurants[idx].requestCount + (requested ? 1 : -1), 0)
        }

        do {
            let response = try await api.toggleRestaurantRequest(restaurantID: restaurantID)
            if let idx = restaurants.firstIndex(where: { $0.id == restaurantID }) {
                restaurants[idx].requestedByMe = response.requested
                restaurants[idx].requestCount = response.requestCount
            }
            return (response.requested, response.requestCount)
        } catch {
            // Revert the optimistic change so the UI matches the server.
            if let original, let idx = restaurants.firstIndex(where: { $0.id == restaurantID }) {
                restaurants[idx].requestedByMe = original.requestedByMe
                restaurants[idx].requestCount = original.requestCount
            }
            return nil
        }
    }

    func searchRestaurants(query: String, kosherFilters: KosherFilters = KosherFilters()) async throws -> [Restaurant] {
        let results = try await api.searchRestaurants(query: query)
        return filteredRestaurants(
            searchText: query,
            selectedCuisine: nil,
            kosherFilters: kosherFilters,
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
        // Preview listings can carry is_active = false on the backend (they're
        // gated by listing_visibility instead), so they must survive this
        // client-side activity filter or the server's preview rows vanish.
        var results = source.filter { $0.isActive || $0.isPreview }

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
