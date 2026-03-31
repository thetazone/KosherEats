import Foundation

@MainActor
class RestaurantViewModel: ObservableObject {
    @Published var restaurant: Restaurant?
    @Published var menuCategories: [MenuCategory] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIService.shared

    func load(restaurantID: String) async {
        isLoading = true
        errorMessage = nil

        do {
            async let restaurantTask = api.getRestaurant(id: restaurantID)
            async let menuTask = api.getMenu(restaurantID: restaurantID)

            let (rest, menu) = try await (restaurantTask, menuTask)
            restaurant = rest
            menuCategories = menu.sorted { $0.sortOrder < $1.sortOrder }
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }
}
