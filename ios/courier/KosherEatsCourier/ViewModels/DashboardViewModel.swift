import Foundation
import CoreLocation

@MainActor
final class DashboardViewModel: ObservableObject {
    @Published var isOnline: Bool = false
    @Published var available: [AvailableDelivery] = []
    /// Orders the seller has accepted/preparing but hasn't yet marked ready.
    /// Couriers can't claim from this list (the claim endpoint enforces
    /// status='ready'), but seeing them lets the courier head toward the
    /// restaurant ahead of the kitchen finishing.
    @Published var upcoming: [AvailableDelivery] = []
    @Published var active: [CourierOrder] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var todayEarnings: Int = 0
    /// Set to true when silent polling receives a 401. The parent view should
    /// observe this and trigger a logout through AuthViewModel.
    @Published var forceLogout: Bool = false
    @Published var connectionLost: Bool = false
    private var consecutivePollFailures = 0

    private let api = APIService.shared
    private var pollTask: Task<Void, Never>?
    private weak var locationManager: LocationManager?

    /// Flips online/offline. Going online requires a real GPS fix — dispatch
    /// uses the courier's position to pick a driver, so publishing (0,0) would
    /// put every online courier at Null Island and break proximity ranking.
    /// On success, starts the GPS heartbeat so the customer's live map keeps
    /// moving; on offline, stops it.
    func toggleOnline(location: LocationManager) async {
        guard !isTogglingOnline else { return }
        isTogglingOnline = true
        defer { isTogglingOnline = false }
        let target = !isOnline
        if target {
            // Permission first — a denied/restricted state will never produce
            // a fix, so don't make the courier wait for one that won't come.
            if location.permissionDenied {
                errorMessage = "Location access is denied. Open Settings to enable it."
                return
            }
            // Accept a stale-but-valid fix (e.g. simulator GPX where accuracy
            // can read -1 on the first tick). We just need *some* coordinate
            // — server-side dispatch only needs a starting position to score
            // proximity; subsequent heartbeats refine it.
            guard let loc = location.currentLocation else {
                errorMessage = "Waiting for GPS fix… try again in a moment."
                return
            }
            do {
                try await api.setOnline(true,
                                        lat: loc.coordinate.latitude,
                                        lng: loc.coordinate.longitude)
                isOnline = true
                locationManager = location
                location.startHeartbeat()
                startPolling()
            } catch {
                errorMessage = userFacingMessage(for: error)
            }
        } else {
            let loc = location.currentLocation
            do {
                try await api.setOnline(false,
                                        lat: loc?.coordinate.latitude ?? 0,
                                        lng: loc?.coordinate.longitude ?? 0)
                isOnline = false
                locationManager = nil
                location.hasActiveDelivery = false
                location.stopHeartbeat()
                stopPolling()
                available = []
            } catch {
                errorMessage = userFacingMessage(for: error)
            }
        }
    }

    func refresh(silent: Bool = false) async {
        if !silent { isLoading = true }
        defer { if !silent { isLoading = false } }

        var anyFailed = false
        do {
            available = try await api.listAvailable()
        } catch APIError.unauthorized {
            forceLogout = true
            return
        } catch {
            anyFailed = true
            if !silent {
                errorMessage = userFacingMessage(for: error)
            }
        }

        // Best-effort fetch — failure here doesn't escalate the connection-
        // lost banner since the upcoming list is informational, not blocking.
        if let upcomingFetched = try? await api.listUpcoming() {
            upcoming = upcomingFetched
        }

        do {
            active = try await api.listActive()
            locationManager?.hasActiveDelivery = !active.isEmpty
        } catch APIError.unauthorized {
            forceLogout = true
            return
        } catch {
            anyFailed = true
            if !silent {
                errorMessage = userFacingMessage(for: error)
            }
        }

        if anyFailed {
            consecutivePollFailures += 1
            connectionLost = consecutivePollFailures >= 3
        } else {
            consecutivePollFailures = 0
            connectionLost = false
        }
    }

    @Published var isTogglingOnline = false
    @Published var isClaiming = false
    @Published var isPickingUp = false
    @Published var isDelivering = false

    func claim(_ delivery: AvailableDelivery) async {
        guard !isClaiming else { return }
        isClaiming = true
        defer { isClaiming = false }
        do {
            try await withRetry { try await self.api.claim(orderId: delivery.id) }
            await refresh()
        } catch {
            errorMessage = userFacingMessage(for: error)
        }
    }

    func pickup(_ order: CourierOrder) async {
        guard !isPickingUp else { return }
        isPickingUp = true
        defer { isPickingUp = false }
        do {
            try await withRetry { try await self.api.pickup(orderId: order.id) }
            await refresh()
        } catch {
            errorMessage = userFacingMessage(for: error)
        }
    }

    /// Retries a network call up to 3 times with exponential backoff for
    /// transient failures. Non-retryable errors (auth, 4xx) throw immediately.
    private func withRetry(_ operation: @escaping () async throws -> Void) async throws {
        var lastError: Error?
        for attempt in 0..<3 {
            do {
                try await operation()
                return
            } catch APIError.unauthorized {
                throw APIError.unauthorized
            } catch let APIError.httpError(code, msg) where (400..<500).contains(code) {
                throw APIError.httpError(code, msg)
            } catch {
                lastError = error
                if attempt < 2 {
                    try? await Task.sleep(nanoseconds: UInt64(pow(2.0, Double(attempt))) * 1_000_000_000)
                }
            }
        }
        throw lastError!
    }

    private static let earningsFractionalFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let earningsPlainFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    func loadTodayEarnings() async {
        do {
            let history = try await api.listHistory()
            let calendar = Calendar.current
            todayEarnings = history
                .filter { entry in
                    guard let str = entry.deliveredAt,
                          let date = Self.earningsFractionalFormatter.date(from: str)
                                  ?? Self.earningsPlainFormatter.date(from: str) else { return false }
                    return calendar.isDateInToday(date)
                }
                .reduce(0) { $0 + $1.courierPayout }
        } catch {
            errorMessage = userFacingMessage(for: error)
        }
    }

    func deliver(_ order: CourierOrder, proofURL: String? = nil) async {
        guard !isDelivering else { return }
        isDelivering = true
        defer { isDelivering = false }
        do {
            // Don't re-upload proofURL on retry — the URL is reusable.
            try await withRetry { try await self.api.deliver(orderId: order.id, proofURL: proofURL) }
            // Immediately remove from active so the UI doesn't show the
            // delivered order even if refresh() fails below.
            active.removeAll { $0.id == order.id }
            locationManager?.hasActiveDelivery = !active.isEmpty
            await refresh()
            await loadTodayEarnings()
        } catch {
            errorMessage = userFacingMessage(for: error)
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh(silent: true)
                do {
                    try await Task.sleep(nanoseconds: 10_000_000_000) // 10s
                } catch is CancellationError {
                    return
                } catch {
                    // unexpected sleep error — keep polling
                }
            }
        }
    }

    private func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    /// Restores local online state + polling + heartbeat when the app relaunches
    /// mid-delivery. iOS may kill the app in the background (memory pressure,
    /// after a long stretch with no updates); on relaunch the backend still
    /// considers the courier online and may have already dispatched an active
    /// order. Without this, `isOnline` stays false, polling never starts, and
    /// GPS heartbeats never fire — so the customer's tracking pin freezes.
    ///
    /// We re-fetch active orders from the backend rather than trusting local
    /// state: the order may have been reassigned while the app was dead, and
    /// blindly resuming would send heartbeats against a stale/reassigned order.
    func resumeIfActive(location: LocationManager) async {
        guard !isOnline else { return }
        do {
            let backendActive = try await api.listActive()
            guard !backendActive.isEmpty else { return }
            active = backendActive

            // Re-announce online to the backend so the server doesn't
            // think we're offline after a heartbeat-timeout or app kill.
            if let loc = location.currentLocation {
                try? await api.setOnline(true,
                                         lat: loc.coordinate.latitude,
                                         lng: loc.coordinate.longitude)
            }

            isOnline = true
            locationManager = location
            location.hasActiveDelivery = true
            location.startTracking()
            location.startHeartbeat()
            startPolling()
        } catch {
            // Cannot verify — leave isOnline = false rather than restoring stale state.
        }
    }
}
