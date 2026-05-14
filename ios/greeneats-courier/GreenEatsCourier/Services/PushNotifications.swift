import Foundation
import UIKit
import UserNotifications

// PushNotifications handles the APNs registration dance and posts the
// device token to the backend. The backend stores tokens per (user, app)
// so the same device can be logged into different app targets over time.
//
// This file is identical in shape across all 3 GreenEats apps; only
// the `app` value differs ("consumer" / "seller" / "courier").
@MainActor
final class PushNotifications: NSObject, ObservableObject {
    static let shared = PushNotifications()
    private let app = "courier"

    // Called from AppDelegate once the OS hands us a token.
    var pendingToken: Data?

    func requestAuthorization() async {
        let center = UNUserNotificationCenter.current()
        do {
            let granted = try await center.requestAuthorization(options: [.alert, .badge, .sound])
            if granted {
                await MainActor.run { UIApplication.shared.registerForRemoteNotifications() }
            }
        } catch {
            print("[push] authorization error: \(error)")
        }
    }

    // AppDelegate forwards the token here. We cache it + best-effort register;
    // if the user isn't logged in yet, we'll retry after login.
    func handleTokenRegistration(_ deviceToken: Data) {
        pendingToken = deviceToken
        Task { await registerPendingTokenIfPossible() }
    }

    func registerPendingTokenIfPossible() async {
        guard let data = pendingToken else { return }
        let hex = data.map { String(format: "%02x", $0) }.joined()
        do {
            try await APIService.shared.registerDevice(token: hex, platform: "ios", app: app)
        } catch {
            print("[push] failed to register token: \(error)")
        }
    }

    func handleRegistrationError(_ error: Error) {
        print("[push] APNs registration failed: \(error)")
    }
}
