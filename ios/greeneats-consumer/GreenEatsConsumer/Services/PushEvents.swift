import Foundation

// In-app event bus for incoming push notifications. AppDelegate posts to
// this on every order-status push (foreground via willPresent, background
// via didReceive→tap), and any ViewModel showing live order data subscribes
// to refresh itself.
//
// Decouples APNs handling from the VMs — without this, an order push hits
// the device but the open OrderTrackingView keeps showing stale state until
// its 30s polling timer ticks. With this, the push triggers an immediate
// re-fetch so the user sees the new status the moment the buzz arrives.
extension Notification.Name {
    static let orderStatusUpdated = Notification.Name("ke.orderStatusUpdated")
}

enum PushEvents {
    /// userInfo key for the affected order id.
    static let orderIDKey = "order_id"
    /// userInfo key for the push `type` field (e.g. "order_accepted",
    /// "order_preparing", "courier_assigned", "picked_up", "delivered",
    /// "order_rejected", "auto_rejected").
    static let typeKey = "type"

    /// Inspects a UNNotification's userInfo and posts an
    /// orderStatusUpdated notification if the payload looks like an order
    /// event. No-ops on chat messages or anything without an order_id so
    /// non-order pushes don't churn unrelated VMs.
    static func postIfOrderEvent(_ userInfo: [AnyHashable: Any]) {
        guard let orderID = userInfo[orderIDKey] as? String, !orderID.isEmpty else { return }
        let type = (userInfo[typeKey] as? String) ?? ""
        // Skip chat messages — they have their own pipeline. Order events
        // are anything else with an order_id.
        if type == "chat_message" { return }
        NotificationCenter.default.post(
            name: .orderStatusUpdated,
            object: nil,
            userInfo: [orderIDKey: orderID, typeKey: type]
        )
    }
}
