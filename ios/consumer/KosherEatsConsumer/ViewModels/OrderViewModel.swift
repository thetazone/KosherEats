import Foundation

@MainActor
class OrderViewModel: ObservableObject {
    @Published var orders: [Order] = []
    @Published var currentOrder: Order?
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIService.shared
    /// Handle for the in-flight poll loop started by `startPolling`. Storing
    /// it so we can cancel on view disappear / before launching a new poll —
    /// otherwise re-entering OrderDetailView stacks a fresh 10s poller on
    /// every visit against the same order id.
    private var pollTask: Task<Void, Never>?
    private var pollGeneration = 0

    var activeOrders: [Order] {
        orders.filter { $0.status.isActive }
    }

    var pastOrders: [Order] {
        orders.filter { !$0.status.isActive }
    }

    func loadOrders() async {
        isLoading = true
        errorMessage = nil

        do {
            orders = try await api.listOrders()
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func loadOrder(id: String) async {
        isLoading = true
        errorMessage = nil

        do {
            currentOrder = try await api.getOrder(id: id)
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func createOrder(
        deliveryAddress: String,
        lat: Double,
        lng: Double,
        paymentIntentId: String,
        tip: Int,
        scheduledFor: Date? = nil,
    ) async -> Order? {
        isLoading = true
        errorMessage = nil

        do {
            let order = try await api.createOrder(
                deliveryAddress: deliveryAddress, lat: lat, lng: lng,
                paymentIntentId: paymentIntentId, tip: tip,
                scheduledFor: scheduledFor,
            )
            currentOrder = order
            isLoading = false
            return order
        } catch {
            errorMessage = error.localizedDescription
            isLoading = false
            return nil
        }
    }

    func cancelOrder(id: String) async {
        do {
            currentOrder = try await api.cancelOrder(id: id)
            if let index = orders.firstIndex(where: { $0.id == id }),
               let updated = currentOrder {
                orders[index] = updated
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func startPolling(orderID: String) {
        pollTask?.cancel()
        pollGeneration &+= 1
        let gen = pollGeneration
        pollTask = Task { [weak self] in
            // Fetch immediately so callers see fresh data without a 10s lag.
            await self?.loadOrder(id: orderID)
            while !Task.isCancelled {
                guard let self else { break }
                guard !Task.isCancelled, self.pollGeneration == gen else { break }
                if let order = self.currentOrder, !order.status.isActive { break }
                try? await Task.sleep(nanoseconds: 10_000_000_000) // 10s
                guard !Task.isCancelled, self.pollGeneration == gen else { break }
                await self.loadOrder(id: orderID)
            }
        }
    }

    func stopPolling() {
        pollGeneration &+= 1
        pollTask?.cancel()
        pollTask = nil
    }

    deinit {
        pollTask?.cancel()
    }
}
