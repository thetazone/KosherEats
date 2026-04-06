import Foundation
import Combine

/// Stores the currently-selected restaurant id for a multi-restaurant seller.
/// Persisted in UserDefaults so the choice survives app restarts.
///
/// Every seller API call reads SelectedRestaurant.shared.id and appends it
/// as ?restaurant_id=… query param. When nil (first launch / single-restaurant
/// seller), the backend falls back to the first owned restaurant.
@MainActor
final class SelectedRestaurant: ObservableObject {
    static let shared = SelectedRestaurant()

    private let storageKey = "selected_restaurant_id"

    @Published var id: String?

    init() {
        self.id = UserDefaults.standard.string(forKey: storageKey)
    }

    func set(_ restaurantID: String?) {
        id = restaurantID
        if let restaurantID = restaurantID {
            UserDefaults.standard.set(restaurantID, forKey: storageKey)
        } else {
            UserDefaults.standard.removeObject(forKey: storageKey)
        }
    }

    /// Appends ?restaurant_id=xxx to a path if a selection is set.
    func appendQuery(to path: String) -> String {
        guard let id = id else { return path }
        let sep = path.contains("?") ? "&" : "?"
        return "\(path)\(sep)restaurant_id=\(id)"
    }
}
