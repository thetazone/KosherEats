import Foundation

@MainActor
class RestaurantViewModel: ObservableObject {
    @Published var restaurant: Restaurant?
    @Published var menuCategories: [MenuCategory] = []
    @Published var deals: [Deal] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIService.shared

    func load(restaurantID: String) async {
        isLoading = true
        errorMessage = nil

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

        do {
            async let restTask = api.getRestaurant(id: restaurantID)
            async let menuTask = api.getMenu(restaurantID: restaurantID)

            let rest = try await restTask
            let menu = try await menuTask

            restaurant = rest
            menuCategories = menu.sorted { $0.sortOrder < $1.sortOrder }
            deals = await dealsTask.value.filter { $0.isActive }
        } catch {
            errorMessage = error.localizedDescription
            // Still surface any deals that arrived despite the error.
            deals = await dealsTask.value.filter { $0.isActive }
        }

        isLoading = false
    }
}
