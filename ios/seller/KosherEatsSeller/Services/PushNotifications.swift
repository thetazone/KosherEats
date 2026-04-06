import Foundation
import UIKit
import UserNotifications

// See ios/courier for the pattern. Only the `app` value differs.
@MainActor
final class PushNotifications: NSObject, ObservableObject {
    static let shared = PushNotifications()
    private let app = "seller"

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
