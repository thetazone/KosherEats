import Foundation

// In-app event bus for incoming push notifications. AppDelegate posts to
// this on every order-status push (foreground via willPresent, background
// via didReceive->tap), and any ViewModel showing live order data subscribes
// to refresh itself.
//
// Decouples APNs handling from the VMs -- without this, an order push hits
// the device but the open OrdersView keeps showing stale state until its
// 30s polling timer ticks. With this, the push triggers an immediate
// re-fetch so the seller sees the new status the moment it arrives.

// MARK: - Notification Names

extension Notification.Name {
    /// Generic order-status change (new order, cancelled, picked up, etc.).
    static let orderStatusUpdated = Notification.Name("ke.orderStatusUpdated")

    /// Fired when a brand-new order arrives so the seller dashboard can play
    /// an alert sound / show a prominent banner distinct from a status update
    /// on an already-acknowledged order.
    static let newOrderReceived = Notification.Name("ke.newOrderReceived")

    /// Fired specifically when an order is cancelled by the consumer so the
    /// seller can show a distinct alert rather than a generic status change.
    static let orderCancelled = Notification.Name("ke.orderCancelled")

    /// Posted when the seller taps an order push (background or cold launch).
    /// Observers (MainTabView / SellerOrdersView) switch to the Orders tab and
    /// push SellerOrderDetailView for the carried `order_id`, mirroring the
    /// Android launch-intent deep link (NavGraph routes to Screen.OrderDetail).
    static let orderDeepLinkRequested = Notification.Name("ke.orderDeepLinkRequested")
}

// MARK: - Push Event Types

enum PushEvents {
    /// userInfo key for the affected order id.
    static let orderIDKey = "order_id"
    /// userInfo key for the push `type` field (e.g. "new_order",
    /// "order_cancelled", "courier_assigned", "picked_up", "delivered").
    static let typeKey = "type"

    /// Known push types that correspond to order-lifecycle events.
    /// Listing them explicitly prevents typo-driven silent misses and
    /// lets callers switch on a closed set.
    enum EventType: String {
        case newOrder = "new_order"
        case orderAccepted = "order_accepted"
        case orderPreparing = "order_preparing"
        case orderReady = "order_ready"
        case courierAssigned = "courier_assigned"
        case pickedUp = "picked_up"
        case delivered = "delivered"
        case orderCancelled = "order_cancelled"
        case orderRejected = "order_rejected"
        case autoRejected = "auto_rejected"
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

        // All order-lifecycle events require an order_id.
        guard let orderID = userInfo[orderIDKey] as? String, !orderID.isEmpty else { return }

        let info: [String: Any] = [orderIDKey: orderID, typeKey: typeRaw]

        // Always post the generic status-updated notification.
        NotificationCenter.default.post(
            name: .orderStatusUpdated,
            object: nil,
            userInfo: info
        )

        // New-order pushes get an additional dedicated notification so the
        // dashboard can play its incoming-order chime without reacting to
        // every status change.
        if eventType == .newOrder {
            NotificationCenter.default.post(
                name: .newOrderReceived,
                object: nil,
                userInfo: info
            )
        }

        // Cancellation channel so VMs that care about the distinction can
        // subscribe separately.
        if eventType == .orderCancelled {
            NotificationCenter.default.post(
                name: .orderCancelled,
                object: nil,
                userInfo: info
            )
        }
    }
}
