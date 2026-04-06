import Foundation
import CoreLocation

@MainActor
final class DashboardViewModel: ObservableObject {
    @Published var isOnline: Bool = false
    @Published var available: [AvailableDelivery] = []
    @Published var active: [CourierOrder] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

    private let api = APIService.shared
    private var pollTask: Task<Void, Never>?

    func toggleOnline(location: CLLocation?) async {
        let target = !isOnline
        do {
            try await api.setOnline(target,
                                    lat: location?.coordinate.latitude ?? 0,
                                    lng: location?.coordinate.longitude ?? 0)
            isOnline = target
            if target {
                startPolling()
            } else {
                stopPolling()
                available = []
            }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    func refresh() async {
        isLoading = true
        defer { isLoading = false }
        async let a = try? api.listAvailable()
        async let b = try? api.listActive()
        let (av, ac) = await (a, b)
        if let av = av { available = av }
        if let ac = ac { active = ac }
    }

    func claim(_ delivery: AvailableDelivery) async {
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

    func deliver(_ order: CourierOrder) async {
        do {
            try await api.deliver(orderId: order.id)
            await refresh()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(nanoseconds: 10_000_000_000) // 10s
            }
        }
    }

    private func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }
}
