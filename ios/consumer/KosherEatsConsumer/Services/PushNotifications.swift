import Foundation
import UIKit
import UserNotifications

// See ios/courier for the pattern. Only the `app` value differs.
@MainActor
final class PushNotifications: NSObject, ObservableObject {
    static let shared = PushNotifications()
    /// App identifier sent to /devices/register so the backend routes pushes
    /// to the correct APNs topic (consumer vs seller vs courier).
    private static let appIdentifier = "consumer"
    private let app = PushNotifications.appIdentifier

    var pendingToken: Data?

    func requestAuthorization() async {
        let center = UNUserNotificationCenter.current()
        do {
            let granted = try await center.requestAuthorization(options: [.alert, .badge, .sound])
            if granted {
                await MainActor.run { UIApplication.shared.registerForRemoteNotifications() }
            }
        } catch {
            #if DEBUG
            print("[push] authorization error: \(error)")
            #endif
        }
    }

    func handleTokenRegistration(_ deviceToken: Data) {
        pendingToken = deviceToken
        Task { await registerPendingTokenIfPossible() }
    }

    func registerPendingTokenIfPossible() async {
        guard let data = pendingToken else { return }
        // registerDevice requires auth. If APNs handed us a token before the
        // user signed in, the call below 401s and the token sits unregistered
        // forever — losing every push for that install. AuthViewModel calls
        // back into here on every successful auth so the deferred token
        // finally lands. Once registered we drop it so we don't double-post.
        guard APIService.shared.isAuthenticated else { return }
        let hex = data.map { String(format: "%02x", $0) }.joined()
        do {
            try await APIService.shared.registerDevice(token: hex, platform: "ios", app: app)
            pendingToken = nil
        } catch {
            #if DEBUG
            print("[push] failed to register token: \(error)")
            #endif
        }
    }

    /// Detaches the cached APNs token from the user that is logging out.
    /// Called by AuthViewModel.logout() *before* the auth token is cleared,
    /// because /devices/unregister requires auth. Best-effort: any failure is
    /// swallowed so a network blip never strands the user mid-logout. The
    /// cached token is intentionally NOT cleared — it's device-scoped, so the
    /// next successful login re-registers it via registerPendingTokenIfPossible.
    func unregisterCurrentDeviceToken() async {
        guard let data = pendingToken else { return }
        guard APIService.shared.isAuthenticated else { return }
        let hex = data.map { String(format: "%02x", $0) }.joined()
        do {
            try await APIService.shared.unregisterDevice(token: hex, platform: "ios", app: app)
        } catch {
            #if DEBUG
            print("[push] failed to unregister token: \(error)")
            #endif
        }
    }

    func handleRegistrationError(_ error: Error) {
        #if DEBUG
        print("[push] APNs registration failed: \(error)")
        #endif
    }
}
