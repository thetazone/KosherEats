import Foundation

// In-app event bus for incoming push notifications. AppDelegate posts to
// this on every order-status push (foreground via willPresent, background
// via didReceive→tap), and OrdersViewModel subscribes to refresh on receipt
// so the seller dashboard updates the moment a courier claims/picks-up
// instead of waiting up to 30s for the next poll tick.
extension Notification.Name {
    static let orderStatusUpdated = Notification.Name("ke.orderStatusUpdated")
}

enum PushEvents {
    static let orderIDKey = "order_id"
    static let typeKey = "type"

    /// Inspects a UNNotification's userInfo and posts an
    /// orderStatusUpdated notification if the payload looks like an order
    /// event. No-ops on chat messages (those have their own pipeline).
    static func postIfOrderEvent(_ userInfo: [AnyHashable: Any]) {
        guard let orderID = userInfo[orderIDKey] as? String, !orderID.isEmpty else { return }
        let type = (userInfo[typeKey] as? String) ?? ""
        if type == "chat_message" { return }
        NotificationCenter.default.post(
            name: .orderStatusUpdated,
            object: nil,
            userInfo: [orderIDKey: orderID, typeKey: type]
        )
    }
}
