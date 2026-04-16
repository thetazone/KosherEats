import Foundation
import CoreLocation

// LocationManager owns CoreLocation and pushes GPS heartbeats to the backend
// while the courier is online or on an active delivery. Background updates
// are enabled so the customer's live map keeps moving even if the courier
// backgrounds the app.
@MainActor
final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var currentLocation: CLLocation?
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var locationUpdateFailing = false

    private let manager = CLLocationManager()

    // Heartbeats are driven by `didUpdateLocations` rather than a timer. A
    // timer-based Task gets suspended when iOS decides to throttle the app
    // in deep background, whereas location deliveries are guaranteed to
    // wake us up as long as `allowsBackgroundLocationUpdates` is on. This
    // is the same pattern Uber/DoorDash use for live driver tracking.
    private var isHeartbeatActive = false
    private var lastHeartbeatSentAt: Date?
    private var heartbeatFailures = 0
    private let heartbeatMinInterval: TimeInterval = 8

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 15 // meters
        manager.pausesLocationUpdatesAutomatically = false
        // Blue "location in use" bar when tracking in the background —
        // required under iOS 17+ whenever we're pushing GPS from the
        // background so the customer's tracking map stays live.
        manager.showsBackgroundLocationIndicator = true
        authorizationStatus = manager.authorizationStatus
    }

    func requestPermission() {
        // Escalates to Always on the second call after the user grants
        // When-In-Use. We need Always so the courier can background the
        // app (phone in pocket, map in Google Maps) without the consumer's
        // tracking pin freezing.
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse:
            manager.requestAlwaysAuthorization()
        default:
            break
        }
    }

    func startTracking() {
        manager.startUpdatingLocation()
        enableBackgroundUpdatesIfAuthorized()
    }

    func stopTracking() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
    }

    // startHeartbeat opens the floodgate: every subsequent location update
    // is posted to the backend (throttled to one per `heartbeatMinInterval`).
    // Called when courier goes online AND when they have an active delivery.
    func startHeartbeat() {
        isHeartbeatActive = true
        lastHeartbeatSentAt = nil
        heartbeatFailures = 0
        // Send an immediate heartbeat if we already have a fix, so the
        // customer's pin doesn't lag for up to `distanceFilter` meters
        // of driving before we first report in.
        if let loc = currentLocation {
            Task { await sendHeartbeat(loc) }
        }
    }

    func stopHeartbeat() {
        isHeartbeatActive = false
        lastHeartbeatSentAt = nil
    }

    /// Call this after any authorization change or when (re)starting tracking.
    /// `allowsBackgroundLocationUpdates` can only be true once authorization
    /// is granted — if we set it before, iOS silently ignores it. So this
    /// must be called from both `startTracking()` AND the delegate callback
    /// that fires when the user grants permission.
    private func enableBackgroundUpdatesIfAuthorized() {
        let status = manager.authorizationStatus
        if status == .authorizedAlways || status == .authorizedWhenInUse {
            manager.allowsBackgroundLocationUpdates = true
        }
    }

    private func maybeSendHeartbeat(_ loc: CLLocation) async {
        guard isHeartbeatActive else { return }
        let now = Date()
        if let last = lastHeartbeatSentAt,
           now.timeIntervalSince(last) < heartbeatMinInterval {
            return
        }
        lastHeartbeatSentAt = now
        await sendHeartbeat(loc)
    }

    private func sendHeartbeat(_ loc: CLLocation) async {
        do {
            try await APIService.shared.sendLocation(
                lat: loc.coordinate.latitude,
                lng: loc.coordinate.longitude,
                heading: loc.course >= 0 ? loc.course : 0,
                speed: loc.speed >= 0 ? loc.speed : 0
            )
            heartbeatFailures = 0
            locationUpdateFailing = false
        } catch {
            if case APIError.unauthorized = error {
                // Token expired and refresh failed — stop hammering
                // the server. The auth state flag surfaces to the UI
                // so the courier is prompted to sign back in rather
                // than silently losing location updates.
                locationUpdateFailing = true
                isHeartbeatActive = false
                return
            }
            heartbeatFailures += 1
            if heartbeatFailures >= 3 {
                locationUpdateFailing = true
            }
        }
    }

    // MARK: - CLLocationManagerDelegate

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            self.currentLocation = loc
            await self.maybeSendHeartbeat(loc)
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
            // First-launch gotcha: DashboardView.task calls requestPermission()
            // then startTracking() synchronously. requestPermission() pops a
            // modal — so startTracking() runs while status is still
            // .notDetermined and skips flipping on background updates. Once
            // the user taps Allow the status changes here, and this is the
            // only reliable hook to enable background updates. Without this
            // fix, couriers pocket their phone mid-delivery and the customer's
            // tracking pin immediately freezes.
            self.enableBackgroundUpdatesIfAuthorized()
            if status == .authorizedAlways || status == .authorizedWhenInUse {
                self.manager.startUpdatingLocation()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Transient CoreLocation errors (kCLErrorLocationUnknown, airplane
        // mode flicker, etc.) shouldn't kill tracking — the next fix will
        // arrive. Log so we can diagnose a stuck-pin report later.
        print("[location] error: \(error.localizedDescription)")
    }
}
