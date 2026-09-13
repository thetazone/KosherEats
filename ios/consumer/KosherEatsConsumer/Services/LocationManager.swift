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
    private var backgroundObserver: ObserverToken?
    private var foregroundObserver: ObserverToken?

    /// NotificationCenter's block-observer handle is typed `any NSObjectProtocol`,
    /// which isn't Sendable — so a `deinit` (always nonisolated, even on a
    /// @MainActor class) can't read a stored property of that type under Swift 6.
    /// The handle is an opaque token that `removeObserver` accepts from any
    /// thread, so boxing it in an explicitly-Sendable holder is safe and lets
    /// `deinit` unregister directly instead of hopping to the main actor (a hop
    /// would have to capture `self` while it's being torn down).
    private struct ObserverToken: @unchecked Sendable {
        let value: any NSObjectProtocol
    }

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
        // `addObserver(forName:object:queue:using:)` takes a @Sendable closure,
        // so the compiler can't see that `queue: .main` already guarantees
        // main-thread delivery. `MainActor.assumeIsolated` states that fact
        // without an async hop, so the handlers keep running synchronously in
        // notification order (a `Task { @MainActor }` hop would let a
        // background/foreground pair land out of order).
        backgroundObserver = ObserverToken(value: NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                // CLLocationManager doesn't expose an "isUpdating" flag, so we
                // track it ourselves. If nobody called startUpdatingLocation we
                // don't need to stop anything.
                self.manager.stopUpdatingLocation()
            }
        })
        foregroundObserver = ObserverToken(value: NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.wasUpdatingBeforeBackground else { return }
                self.wasUpdatingBeforeBackground = false
                let status = self.manager.authorizationStatus
                if status == .authorizedWhenInUse || status == .authorizedAlways {
                    self.manager.startUpdatingLocation()
                }
            }
        })
    }

    deinit {
        if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver.value) }
        if let foregroundObserver { NotificationCenter.default.removeObserver(foregroundObserver.value) }
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
        // Read the status off the callback's own (nonisolated) reference, then
        // drive the session through `self.manager` inside the actor. Capturing
        // the `manager` parameter in the closure would "send" a non-Sendable
        // CLLocationManager across the isolation boundary; it's the same object
        // we already own on the main actor, so reach for that copy instead.
        let status = manager.authorizationStatus
        MainActor.assumeIsolated {
            authorizationStatus = status
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                wasUpdatingBeforeBackground = true
                self.manager.startUpdatingLocation()
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
