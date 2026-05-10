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

        do {
            async let restTask = api.getRestaurant(id: restaurantID)
            async let menuTask = api.getMenu(restaurantID: restaurantID)
            async let dealsTask = api.getRestaurantDeals(restaurantID: restaurantID)

            let rest = try await restTask
            let menu = try await menuTask
            let fetchedDeals = (try? await dealsTask) ?? []

            restaurant = rest
            menuCategories = menu.sorted { $0.sortOrder < $1.sortOrder }
            deals = fetchedDeals.filter { $0.isActive }
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }
}
