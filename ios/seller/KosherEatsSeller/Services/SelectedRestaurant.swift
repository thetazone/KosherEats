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
    private let nameKey = "selected_restaurant_name"

    @Published var id: String?
    /// Display name of the active restaurant, persisted alongside the id so
    /// Orders/Menu headers can show "Showing: <name>" on cold launch before
    /// any API call returns. Optional because pre-upgrade installs won't
    /// have it written yet.
    @Published var name: String?

    init() {
        self.id = UserDefaults.standard.string(forKey: storageKey)
        self.name = UserDefaults.standard.string(forKey: nameKey)
    }

    func set(_ restaurantID: String?, name: String? = nil) {
        id = restaurantID
        self.name = name
        if let restaurantID = restaurantID {
            UserDefaults.standard.set(restaurantID, forKey: storageKey)
        } else {
            UserDefaults.standard.removeObject(forKey: storageKey)
        }
        if let name = name {
            UserDefaults.standard.set(name, forKey: nameKey)
        } else {
            UserDefaults.standard.removeObject(forKey: nameKey)
        }
    }

    /// Appends ?restaurant_id=xxx to a path if a selection is set.
    /// Builds the value with URLComponents/URLQueryItem so the id is escaped
    /// as a query-parameter *value*. Note `URLComponents.query` does NOT
    /// percent-encode `&`, `=`, or `+` (they are all legal in a query); use
    /// `.percentEncodedQuery` and escape `+`->%2B explicitly below.
    func appendQuery(to path: String) -> String {
        guard let id = id else { return path }
        var components = URLComponents()
        components.queryItems = [URLQueryItem(name: "restaurant_id", value: id)]
        // URLComponents.query leaves `+` literal and only `.percentEncodedQuery`
        // escapes `&`/`=`; Go's url.Query() decodes a bare `+` to a space. Use the
        // percent-encoded query and escape `+`->%2B (mirrors getOrders()). Any real
        // `%` is already `%25` here, so there is no double-encoding.
        guard let encodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B") else { return path }
        let sep = path.contains("?") ? "&" : "?"
        return "\(path)\(sep)\(encodedQuery)"
    }
}
