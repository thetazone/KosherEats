import UIKit
import UserNotifications

// Note: `Notification.Name.orderDeepLinkRequested` is declared alongside the
// other push notification names in PushEvents.swift so all push-related
// signals live in one place.

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

    // Foreground: show the banner + sound. Also fan the order-event userInfo
    // out via NotificationCenter so OrdersViewModel refreshes the dashboard
    // immediately on courier_assigned / courier_picked_up — without this the
    // seller dashboard waited up to 30s for the next poll tick before
    // showing "Out for delivery."
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        PushEvents.postIfOrderEvent(notification.request.content.userInfo)
        completionHandler([.banner, .sound, .badge])
    }

    // Tap (background/cold launch → foreground via notification): fan the
    // order event out so the freshly-loaded dashboard state matches the push,
    // AND request a deep link to the tapped order. Without the deep link the
    // seller lands on whatever tab was last open and has to hunt for the
    // ticket — Android lands them straight on it via the launch intent.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        PushEvents.postIfOrderEvent(userInfo)
        postOrderDeepLinkIfPresent(userInfo)
        completionHandler()
    }

    // Publishes a deep-link request carrying the tapped order's id so the UI
    // can navigate to it. Skips chat pushes (their own pipeline) and anything
    // lacking an order_id, matching PushEvents.postIfOrderEvent's gating so we
    // never request navigation for a non-order push.
    private func postOrderDeepLinkIfPresent(_ userInfo: [AnyHashable: Any]) {
        let typeRaw = (userInfo[PushEvents.typeKey] as? String) ?? ""
        if PushEvents.EventType(rawValue: typeRaw) == .chatMessage { return }

        guard let orderID = userInfo[PushEvents.orderIDKey] as? String,
              !orderID.isEmpty else { return }

        NotificationCenter.default.post(
            name: .orderDeepLinkRequested,
            object: nil,
            userInfo: [PushEvents.orderIDKey: orderID]
        )
    }
}
