import Foundation
import SwiftUI

@MainActor
class OrdersViewModel: ObservableObject {
    @Published var orders: [Order] = []
    @Published var filteredOrders: [Order] = []
    @Published var selectedFilter: OrderFilter = .active
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var successMessage: String?

    enum OrderFilter: String, CaseIterable, Identifiable {
        case active = "Active"
        case completed = "Completed"
        case all = "All"

        var id: String { rawValue }
    }

    func load() async {
        isLoading = true
        errorMessage = nil

        do {
            self.orders = try await APIService.shared.getOrders()
            applyFilter()
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func applyFilter() {
        switch selectedFilter {
        case .active:
            filteredOrders = orders.filter { $0.status.isActive }
                .sorted { $0.createdAt > $1.createdAt }
        case .completed:
            filteredOrders = orders.filter { !$0.status.isActive }
                .sorted { $0.createdAt > $1.createdAt }
        case .all:
            filteredOrders = orders.sorted { $0.createdAt > $1.createdAt }
        }
    }

    func acceptOrder(id: String) async {
        do {
            let updated = try await APIService.shared.acceptOrder(id: id)
            updateOrder(updated)
            successMessage = "Order accepted"
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func rejectOrder(id: String, reason: String? = nil) async {
        do {
            let updated = try await APIService.shared.rejectOrder(id: id, reason: reason)
            updateOrder(updated)
            successMessage = "Order rejected"
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func markReady(id: String) async {
        do {
            let updated = try await APIService.shared.markOrderReady(id: id)
            updateOrder(updated)
            successMessage = "Order marked as ready"
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func completeOrder(id: String) async {
        do {
            let updated = try await APIService.shared.completeOrder(id: id)
            updateOrder(updated)
            successMessage = "Order completed"
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func updateOrder(_ updated: Order) {
        if let idx = orders.firstIndex(where: { $0.id == updated.id }) {
            orders[idx] = updated
        }
        applyFilter()
    }
}
