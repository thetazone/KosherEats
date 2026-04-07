import UIKit
import UserNotifications

// Consumer app delegate. Forwards APNs registration callbacks into
// PushNotifications.shared, and routes push notification taps into the app
// via NotificationCenter events that MainTabView listens to.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in
            PushNotifications.shared.handleTokenRegistration(deviceToken)
        }
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Task { @MainActor in
            PushNotifications.shared.handleRegistrationError(error)
        }
    }

    // MARK: - UNUserNotificationCenterDelegate

    // Foreground: show the banner + sound. iOS 14+ lets us opt into banners.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void,
    ) {
        completionHandler([.banner, .sound, .badge])
    }

    // Tap: route to the relevant order screen. Backend pushes carry two
    // fields in their data payload: `type` (new_order, courier_assigned,
    // picked_up, delivered, ...) and `order_id`. We decide which screen to
    // open based on whether the order is still active.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void,
    ) {
        let info = response.notification.request.content.userInfo
        if let orderId = info["order_id"] as? String {
            let type = (info["type"] as? String) ?? ""
            // "courier_assigned", "picked_up" → open live tracking map.
            // "delivered" → open order detail (no more tracking to do).
            // Default → detail.
            let trackingTypes: Set<String> = ["courier_assigned", "picked_up"]
            let notificationName: Notification.Name =
                trackingTypes.contains(type) ? .navigateToOrderTracking : .navigateToOrderDetail

            DispatchQueue.main.async {
                NotificationCenter.default.post(
                    name: notificationName,
                    object: nil,
                    userInfo: ["order_id": orderId],
                )
            }
        }
        completionHandler()
    }
}
