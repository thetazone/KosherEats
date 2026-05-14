import UIKit
import UserNotifications

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

    // Tap (background → foreground via notification): same fan-out so the
    // dashboard's freshly-loaded state matches the push that brought the
    // user back into the app.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        PushEvents.postIfOrderEvent(response.notification.request.content.userInfo)
        completionHandler()
    }
}
