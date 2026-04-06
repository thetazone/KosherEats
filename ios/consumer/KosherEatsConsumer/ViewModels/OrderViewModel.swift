import Foundation

@MainActor
class OrderViewModel: ObservableObject {
    @Published var orders: [Order] = []
    @Published var currentOrder: Order?
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIService.shared

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
    ) async -> Order? {
        isLoading = true
        errorMessage = nil

        do {
            let order = try await api.createOrder(
                deliveryAddress: deliveryAddress, lat: lat, lng: lng,
                paymentIntentId: paymentIntentId, tip: tip,
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
            if let index = orders.firstIndex(where: { $0.id == id }) {
                orders[index] = currentOrder!
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func startPolling(orderID: String) {
        Task {
            while currentOrder?.status.isActive == true {
                try? await Task.sleep(nanoseconds: 10_000_000_000) // 10s
                await loadOrder(id: orderID)
            }
        }
    }
}
