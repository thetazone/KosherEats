import Foundation

// In-app event bus for incoming push notifications. AppDelegate posts to
// this on every order-status push (foreground via willPresent, background
// via didReceive->tap), and any ViewModel showing live order data subscribes
// to refresh itself.
//
// Decouples APNs handling from the VMs -- without this, an order push hits
// the device but the open OrderTrackingView keeps showing stale state until
// its 30s polling timer ticks. With this, the push triggers an immediate
// re-fetch so the user sees the new status the moment the buzz arrives.

// MARK: - Notification Names

extension Notification.Name {
    /// Generic order-status change (accepted, preparing, picked up, etc.).
    static let orderStatusUpdated = Notification.Name("ke.orderStatusUpdated")

    /// Courier location update -- separate from status so the tracking map
    /// can observe location ticks without reacting to unrelated status events.
    static let courierLocationUpdated = Notification.Name("ke.courierLocationUpdated")

    /// Fired specifically when an order is cancelled or rejected so VMs can
    /// show a distinct UI (alert, refund banner) rather than a generic status
    /// change. Also posted alongside `orderStatusUpdated` for backward compat.
    static let orderCancelled = Notification.Name("ke.orderCancelled")

    /// A promotional or reorder push (not tied to an active order lifecycle).
    static let promotionReceived = Notification.Name("ke.promotionReceived")
}

// MARK: - Push Event Types

enum PushEvents {
    /// userInfo key for the affected order id.
    static let orderIDKey = "order_id"
    /// userInfo key for the push `type` field (e.g. "order_accepted",
    /// "order_preparing", "courier_assigned", "picked_up", "delivered",
    /// "order_rejected", "auto_rejected", "courier_location").
    static let typeKey = "type"

    /// Known push types that correspond to order-lifecycle events.
    /// Listing them explicitly prevents typo-driven silent misses and
    /// lets callers switch on a closed set.
    enum EventType: String {
        case orderAccepted = "order_accepted"
        case orderPreparing = "order_preparing"
        case orderReady = "order_ready"
        case courierAssigned = "courier_assigned"
        case pickedUp = "picked_up"
        case delivered = "delivered"
        case orderRejected = "order_rejected"
        case autoRejected = "auto_rejected"
        case orderCancelled = "order_cancelled"
        case courierLocation = "courier_location"
        case promotion = "promotion"
        case chatMessage = "chat_message"
    }

    /// Inspects a UNNotification's userInfo and posts the appropriate
    /// notification(s). No-ops on chat messages so non-order pushes don't
    /// churn unrelated VMs.
    static func postIfOrderEvent(_ userInfo: [AnyHashable: Any]) {
        let typeRaw = (userInfo[typeKey] as? String) ?? ""
        let eventType = EventType(rawValue: typeRaw)

        // Chat messages have their own pipeline.
        if eventType == .chatMessage { return }

        // Promotions don't require an order_id.
        if eventType == .promotion {
            NotificationCenter.default.post(
                name: .promotionReceived,
                object: nil,
                userInfo: userInfo
            )
            return
        }

        // Courier location updates only need lat/lng, posted on a separate
        // channel so the map view can subscribe without noise.
        if eventType == .courierLocation {
            guard let orderID = userInfo[orderIDKey] as? String, !orderID.isEmpty else { return }
            NotificationCenter.default.post(
                name: .courierLocationUpdated,
                object: nil,
                userInfo: userInfo
            )
            return
        }

        // All remaining order-lifecycle events require an order_id.
        guard let orderID = userInfo[orderIDKey] as? String, !orderID.isEmpty else { return }

        let info: [String: Any] = [orderIDKey: orderID, typeKey: typeRaw]

        // Always post the generic status-updated notification.
        NotificationCenter.default.post(
            name: .orderStatusUpdated,
            object: nil,
            userInfo: info
        )

        // Additionally post on the cancellation channel so VMs that care
        // about the distinction can subscribe separately.
        if eventType == .orderCancelled || eventType == .orderRejected || eventType == .autoRejected {
            NotificationCenter.default.post(
                name: .orderCancelled,
                object: nil,
                userInfo: info
            )
        }
    }
}
