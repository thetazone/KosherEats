import Foundation
import UIKit
import UserNotifications

// See ios/courier for the pattern. Only the `app` value differs.
@MainActor
final class PushNotifications: NSObject, ObservableObject {
    static let shared = PushNotifications()
    private let app = "seller"

    var pendingToken: Data?

    /// Hex of the APNs token last successfully registered with the backend.
    /// Retained even after `pendingToken` is cleared so `unregisterIfPossible()`
    /// can tell the backend exactly which device row to delete on logout.
    /// Persisted in UserDefaults so a logout after an app relaunch (where the
    /// in-memory copy is gone but APNs hasn't re-delivered a token yet) can
    /// still unregister. Mirrors Android's PushBootstrap.deleteToken flow.
    private let registeredTokenKey = "ke_seller_registered_apns_hex"
    private var registeredTokenHex: String? {
        get { UserDefaults.standard.string(forKey: registeredTokenKey) }
        set {
            if let newValue {
                UserDefaults.standard.set(newValue, forKey: registeredTokenKey)
            } else {
                UserDefaults.standard.removeObject(forKey: registeredTokenKey)
            }
        }
    }

    func requestAuthorization() async {
        let center = UNUserNotificationCenter.current()
        // AppDelegate is the sole UNUserNotificationCenterDelegate —
        // it handles PushEvents.postIfOrderEvent() fanout. Don't steal it here.
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
        let hex = data.map { String(format: "%02x", $0) }.joined()
        // Retry up to 3 times with backoff so a transient failure on launch
        // doesn't permanently disable push until the next app relaunch.
        for attempt in 0..<3 {
            do {
                try await APIService.shared.registerDevice(token: hex, platform: "ios", app: app)
                pendingToken = nil
                // Remember the registered token so logout/deleteAccount can
                // unregister exactly this device row server-side.
                registeredTokenHex = hex
                return
            } catch {
                #if DEBUG
                print("[push] register attempt \(attempt + 1) failed: \(error)")
                #endif
                if attempt < 2 {
                    try? await Task.sleep(nanoseconds: UInt64(pow(2.0, Double(attempt))) * 1_000_000_000)
                }
            }
        }
    }

    func handleRegistrationError(_ error: Error) {
        #if DEBUG
        print("[push] APNs registration failed: \(error)")
        #endif
    }

    /// Call on auth restore or app foreground to retry any pending token
    /// that failed during the initial registration attempt.
    func retryPendingRegistration() {
        guard pendingToken != nil else { return }
        Task { await registerPendingTokenIfPossible() }
    }

    /// Tells the backend to drop this device's push token. MUST be awaited
    /// while the bearer token is still set (the unregister endpoint is
    /// authenticated and keys the delete on (user_id, token, app)), so
    /// AuthViewModel calls this BEFORE clearing the Keychain/in-memory token.
    /// Best-effort: a failure is logged but never blocks logout. Mirrors
    /// Android's PushBootstrap.deleteToken().
    func unregisterIfPossible() async {
        // Prefer the in-memory token (set this session), fall back to the
        // persisted hex from a prior launch's successful registration.
        let hex: String?
        if let data = pendingToken {
            hex = data.map { String(format: "%02x", $0) }.joined()
        } else {
            hex = registeredTokenHex
        }
        guard let hex else { return }
        do {
            try await APIService.shared.unregisterDevice(token: hex, platform: "ios", app: app)
        } catch {
            #if DEBUG
            print("[push] unregister failed: \(error)")
            #endif
        }
        // Clear the local record regardless — on the next login a fresh APNs
        // token will be (re)registered, and we don't want a stale hex around.
        registeredTokenHex = nil
        pendingToken = nil
    }

}
