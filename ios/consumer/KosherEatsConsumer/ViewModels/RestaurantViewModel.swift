import Foundation

@MainActor
class RestaurantViewModel: ObservableObject {
    @Published var restaurant: Restaurant?
    @Published var menuCategories: [MenuCategory] = []
    @Published var deals: [Deal] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIService.shared
    private var loadGeneration = 0
    private var dealsTask: Task<[Deal], Never>?
    private var isTogglingRequest = false

    /// Optimistically toggles the user's "request this restaurant" vote for a
    /// preview listing and reconciles with the server response, reverting on
    /// failure. No-op for orderable restaurants (the backend 400s those).
    func toggleRequest() async {
        guard var current = restaurant, current.isPreview, !isTogglingRequest else { return }
        isTogglingRequest = true
        defer { isTogglingRequest = false }

        let original = current
        current.requestedByMe.toggle()
        current.requestCount = max(current.requestCount + (current.requestedByMe ? 1 : -1), 0)
        restaurant = current

        do {
            let response = try await api.toggleRestaurantRequest(restaurantID: original.id)
            guard restaurant?.id == original.id else { return }
            restaurant?.requestedByMe = response.requested
            restaurant?.requestCount = response.requestCount
        } catch {
            guard restaurant?.id == original.id else { return }
            restaurant?.requestedByMe = original.requestedByMe
            restaurant?.requestCount = original.requestCount
        }
    }

    func load(restaurantID: String) async {
        loadGeneration &+= 1
        let gen = loadGeneration

        isLoading = true
        errorMessage = nil

        // Cancel any deals fetch from a prior, now-superseded load.
        dealsTask?.cancel()
        // Fetch deals separately so a failure doesn't cancel the
        // restaurant + menu structured concurrency group.
        let dealsTask = Task<[Deal], Never> {
            do {
                return try await api.getRestaurantDeals(restaurantID: restaurantID)
            } catch {
                #if DEBUG
                print("[RestaurantViewModel] deals fetch failed for \(restaurantID): \(error.localizedDescription)")
                #endif
                return []
            }
        }
        self.dealsTask = dealsTask

        do {
            async let restTask = api.getRestaurant(id: restaurantID)
            async let menuTask = api.getMenu(restaurantID: restaurantID)

            let rest = try await restTask
            let menu = try await menuTask
            let fetchedDeals = await dealsTask.value.filter { $0.isActive }

            guard gen == loadGeneration else { return }
            restaurant = rest
            menuCategories = menu.sorted { $0.sortOrder < $1.sortOrder }
            deals = fetchedDeals
        } catch {
            let fetchedDeals = await dealsTask.value.filter { $0.isActive }
            guard gen == loadGeneration else { return }
            errorMessage = error.localizedDescription
            // Still surface any deals that arrived despite the error.
            deals = fetchedDeals
        }

        guard gen == loadGeneration else { return }
        isLoading = false
    }
}
