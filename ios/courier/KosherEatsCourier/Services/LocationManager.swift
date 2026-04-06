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

    private let manager = CLLocationManager()
    private var heartbeatTask: Task<Void, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 15 // meters
        manager.pausesLocationUpdatesAutomatically = false
        authorizationStatus = manager.authorizationStatus
    }

    func requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    func startTracking() {
        manager.startUpdatingLocation()
        // Enable background updates once we have "always" authorization.
        if manager.authorizationStatus == .authorizedAlways {
            manager.allowsBackgroundLocationUpdates = true
        }
    }

    func stopTracking() {
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    // startHeartbeat posts the current location to the backend every `interval` seconds.
    // Called when courier goes online AND when they have an active delivery.
    func startHeartbeat(interval: TimeInterval = 8) {
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                if let loc = self?.currentLocation {
                    try? await APIService.shared.sendLocation(
                        lat: loc.coordinate.latitude,
                        lng: loc.coordinate.longitude,
                        heading: loc.course >= 0 ? loc.course : 0,
                        speed: loc.speed >= 0 ? loc.speed : 0
                    )
                }
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
            }
        }
    }

    func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    // MARK: - CLLocationManagerDelegate

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            self.currentLocation = loc
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
        }
    }
}
