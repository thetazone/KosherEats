import CoreLocation
import UIKit

@MainActor
final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationManager()

    private let manager = CLLocationManager()
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var currentLocation: CLLocationCoordinate2D?

    /// Whether location updates were active before the app backgrounded.
    /// Used to auto-resume when the app returns to the foreground.
    private var wasUpdatingBeforeBackground = false
    private var backgroundObserver: NSObjectProtocol?
    private var foregroundObserver: NSObjectProtocol?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        authorizationStatus = manager.authorizationStatus
        observeAppLifecycle()
    }

    /// Stop continuous location updates when the app backgrounds to avoid
    /// unnecessary battery drain (we only have WhenInUse authorization).
    /// Resume automatically when the app returns to the foreground.
    private func observeAppLifecycle() {
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            // CLLocationManager doesn't expose an "isUpdating" flag, so we
            // track it ourselves. If nobody called startUpdatingLocation we
            // don't need to stop anything.
            self.manager.stopUpdatingLocation()
        }
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self, self.wasUpdatingBeforeBackground else { return }
            self.wasUpdatingBeforeBackground = false
            let status = self.manager.authorizationStatus
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                self.manager.startUpdatingLocation()
            }
        }
    }

    deinit {
        if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver) }
        if let foregroundObserver { NotificationCenter.default.removeObserver(foregroundObserver) }
    }

    func requestLocationPermission() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
    }

    // Driven by map-surfaced views that want to snap the camera to the user
    // as soon as a fix lands. SwiftUI's `.userLocation` camera position
    // resolves to its fallback the first time the map renders — if we
    // haven't separately started a CoreLocation session by then, the map
    // sits on that fallback (globe view) until the user pans.
    func startUpdatingLocation() {
        let status = manager.authorizationStatus
        if status == .authorizedWhenInUse || status == .authorizedAlways {
            wasUpdatingBeforeBackground = true
            manager.startUpdatingLocation()
        }
    }

    func stopUpdatingLocation() {
        wasUpdatingBeforeBackground = false
        manager.stopUpdatingLocation()
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        MainActor.assumeIsolated {
            authorizationStatus = status
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                wasUpdatingBeforeBackground = true
                manager.startUpdatingLocation()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        let coord = last.coordinate
        MainActor.assumeIsolated {
            currentLocation = coord
        }
    }
}
