import ActivityKit
import Foundation

struct DeliveryAttributes: ActivityAttributes {
    /// Fixed info — set once when the activity starts.
    let orderID: String
    let restaurantName: String
    let deliveryAddress: String
    let itemCount: Int

    /// Dynamic state — updated as the order progresses.
    struct ContentState: Codable, Hashable {
        var status: String           // "pending", "accepted", "preparing", "ready", "picked_up", "delivered"
        var statusText: String       // "Your food is being prepared"
        var eta: Date
        var courierName: String?
        var courierPhone: String?    // used to surface a call-courier action on the Lock Screen
        var courierVehicle: String?  // "Silver Toyota Camry"
        var orderTotal: String?      // formatted total, e.g. "$23.50"
    }
}
