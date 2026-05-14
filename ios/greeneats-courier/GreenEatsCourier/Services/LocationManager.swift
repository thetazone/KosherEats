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
    @Published var permissionDenied = false
    @Published var needsReauth = false

    private let manager = CLLocationManager()

    // Set to true while the courier has an active delivery; false when online-idle.
    // Switching this escalates/de-escalates GPS accuracy to save battery.
    var hasActiveDelivery: Bool = false {
        didSet { manager.desiredAccuracy = hasActiveDelivery ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters }
    }

    // Heartbeats are driven by `didUpdateLocations` rather than a timer. A
    // timer-based Task gets suspended when iOS decides to throttle the app
    // in deep background, whereas location deliveries are guaranteed to
    // wake us up as long as `allowsBackgroundLocationUpdates` is on. This
    // is the same pattern Uber/DoorDash use for live driver tracking.
    private var isHeartbeatActive = false
    private var lastHeartbeatSentAt: Date?
    private var heartbeatFailures = 0
    private let heartbeatMinInterval: TimeInterval = 8
    private var heartbeatRecoveryTask: Task<Void, Never>?
    private var heartbeatImmediateTask: Task<Void, Never>?
    private var heartbeatGeneration = 0

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
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
        heartbeatGeneration += 1
        heartbeatRecoveryTask?.cancel()
        heartbeatRecoveryTask = nil
        isHeartbeatActive = true
        lastHeartbeatSentAt = nil
        heartbeatFailures = 0
        // Send an immediate heartbeat if we already have a fix, so the
        // customer's pin doesn't lag for up to `distanceFilter` meters
        // of driving before we first report in.
        if let loc = currentLocation {
            let gen = heartbeatGeneration
            heartbeatImmediateTask = Task {
                guard self.heartbeatGeneration == gen else { return }
                await sendHeartbeat(loc)
            }
        }
    }

    func stopHeartbeat() {
        heartbeatGeneration += 1
        heartbeatImmediateTask?.cancel()
        heartbeatImmediateTask = nil
        heartbeatRecoveryTask?.cancel()
        heartbeatRecoveryTask = nil
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
                heading: loc.course >= 0 ? loc.course : nil,
                speed: loc.speed >= 0 ? loc.speed : 0
            )
            heartbeatFailures = 0
            locationUpdateFailing = false
        } catch {
            if case APIError.unauthorized = error {
                // Token expired and refresh failed — surface auth
                // failure so DashboardView can trigger logout rather
                // than leaving the courier stuck with a red banner.
                locationUpdateFailing = true
                isHeartbeatActive = false
                needsReauth = true
                return
            }
            heartbeatFailures += 1
            if heartbeatFailures >= 3 {
                locationUpdateFailing = true
                isHeartbeatActive = false
                // Re-arm after 30 s so a transient network hiccup doesn't
                // permanently freeze the customer's tracking pin. Cancelled
                // by stopHeartbeat()/startHeartbeat() if the courier goes
                // offline or manually restarts before the timer fires.
                let gen = heartbeatGeneration
                heartbeatRecoveryTask = Task {
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                    guard !Task.isCancelled, self.heartbeatGeneration == gen else { return }
                    self.heartbeatFailures = 0
                    self.isHeartbeatActive = true
                    // Send an immediate heartbeat so a stationary courier
                    // doesn't wait for the next didUpdateLocations (which
                    // requires 15m of movement to fire).
                    if let loc = self.currentLocation {
                        await self.sendHeartbeat(loc)
                    }
                }
            }
        }
    }

    // MARK: - CLLocationManagerDelegate

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            self.currentLocation = loc
            // Only heartbeat with fresh, accurate fixes — iOS often delivers
            // a stale cached fix on wake with high horizontal uncertainty.
            let age = abs(loc.timestamp.timeIntervalSinceNow)
            guard loc.horizontalAccuracy > 0,
                  loc.horizontalAccuracy < 100,
                  age < 30 else { return }
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
            if status == .denied || status == .restricted {
                self.permissionDenied = true
            } else {
                self.permissionDenied = false
                self.enableBackgroundUpdatesIfAuthorized()
                if status == .authorizedAlways || status == .authorizedWhenInUse {
                    self.manager.startUpdatingLocation()
                }
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Transient CoreLocation errors (kCLErrorLocationUnknown, airplane
        // mode flicker, etc.) shouldn't kill tracking — the next fix will
        // arrive. Log so we can diagnose a stuck-pin report later.
        #if DEBUG
        print("[location] error: \(error.localizedDescription)")
        #endif
    }
}
