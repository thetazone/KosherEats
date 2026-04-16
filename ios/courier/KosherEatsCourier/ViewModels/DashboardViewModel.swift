import Foundation
import CoreLocation

@MainActor
final class DashboardViewModel: ObservableObject {
    @Published var isOnline: Bool = false
    @Published var available: [AvailableDelivery] = []
    @Published var active: [CourierOrder] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    @Published var todayEarnings: Int = 0
    /// Set to true when silent polling receives a 401. The parent view should
    /// observe this and trigger a logout through AuthViewModel.
    @Published var forceLogout: Bool = false

    private let api = APIService.shared
    private var pollTask: Task<Void, Never>?

    /// Flips online/offline. Going online requires a real GPS fix — dispatch
    /// uses the courier's position to pick a driver, so publishing (0,0) would
    /// put every online courier at Null Island and break proximity ranking.
    /// On success, starts the GPS heartbeat so the customer's live map keeps
    /// moving; on offline, stops it.
    func toggleOnline(location: LocationManager) async {
        let target = !isOnline
        if target {
            guard let loc = location.currentLocation else {
                errorMessage = "Waiting for GPS — hold on a second and try again."
                return
            }
            do {
                try await api.setOnline(true,
                                        lat: loc.coordinate.latitude,
                                        lng: loc.coordinate.longitude)
                isOnline = true
                location.startHeartbeat()
                startPolling()
            } catch {
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        } else {
            // Going offline: best-effort flip; even if the backend call fails
            // we still stop heartbeating + polling locally.
            let loc = location.currentLocation
            do {
                try await api.setOnline(false,
                                        lat: loc?.coordinate.latitude ?? 0,
                                        lng: loc?.coordinate.longitude ?? 0)
            } catch {
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
            isOnline = false
            location.stopHeartbeat()
            stopPolling()
            available = []
        }
    }

    func refresh(silent: Bool = false) async {
        if !silent { isLoading = true }
        defer { if !silent { isLoading = false } }

        do {
            available = try await api.listAvailable()
        } catch APIError.unauthorized {
            forceLogout = true
            return
        } catch {
            // Transient network error during silent poll — ignore.
            if !silent {
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }

        do {
            active = try await api.listActive()
        } catch APIError.unauthorized {
            forceLogout = true
            return
        } catch {
            if !silent {
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }

    private var isClaiming = false

    func claim(_ delivery: AvailableDelivery) async {
        guard !isClaiming else { return }
        isClaiming = true
        defer { isClaiming = false }
        do {
            try await api.claim(orderId: delivery.id)
            await refresh()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    func pickup(_ order: CourierOrder) async {
        do {
            try await api.pickup(orderId: order.id)
            await refresh()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    func loadTodayEarnings() async {
        if let history = try? await api.listHistory() {
            todayEarnings = history.reduce(0) { $0 + $1.courierPayout }
        }
    }

    func deliver(_ order: CourierOrder, proofURL: String? = nil) async {
        do {
            try await api.deliver(orderId: order.id, proofURL: proofURL)
            await refresh()
            await loadTodayEarnings()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh(silent: true)
                try? await Task.sleep(nanoseconds: 10_000_000_000) // 10s
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
    func resumeIfActive(location: LocationManager) {
        guard !active.isEmpty, !isOnline else { return }
        isOnline = true
        location.startHeartbeat()
        startPolling()
    }
}
