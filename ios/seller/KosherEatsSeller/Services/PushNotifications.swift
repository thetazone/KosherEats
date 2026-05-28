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

}
