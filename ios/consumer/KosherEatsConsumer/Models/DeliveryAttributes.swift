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
        var courierVehicle: String?  // "Silver Toyota Camry"
    }
}
