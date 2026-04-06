import Foundation

/// App-wide navigation events broadcast through NotificationCenter so any
/// view can ask the root to switch tabs or open a specific order. The root
/// MainTabView listens and updates its bindings.
///
/// This avoids passing navigation state through every intermediate view.
extension Notification.Name {
    /// Switch MainTabView to the Orders tab. Used after a fresh checkout.
    static let navigateToOrdersTab = Notification.Name("ke.navigateToOrdersTab")

    /// Open OrderTrackingView for a specific order. userInfo["order_id"] is the id.
    /// Used by the "Track your order" button and push notification taps.
    static let navigateToOrderTracking = Notification.Name("ke.navigateToOrderTracking")

    /// Open OrderDetailView for a specific order. userInfo["order_id"] is the id.
    /// Used by push notification taps on non-tracking notifications.
    static let navigateToOrderDetail = Notification.Name("ke.navigateToOrderDetail")
}
