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
            let rest = try await api.getRestaurant(id: restaurantID)
            let menu = try await api.getMenu(restaurantID: restaurantID)
            restaurant = rest
            menuCategories = menu.sorted { $0.sortOrder < $1.sortOrder }
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }
}
