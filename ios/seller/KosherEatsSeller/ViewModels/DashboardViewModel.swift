import Foundation
import SwiftUI

@MainActor
class DashboardViewModel: ObservableObject {
    @Published var activeOrders: [Order] = []
    @Published var stats = DashboardStats()
    @Published var restaurant: Restaurant?
    @Published var isLoading = false
    @Published var errorMessage: String?

    private var refreshTimer: Timer?

    func load() async {
        isLoading = true
        errorMessage = nil

        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.fetchActiveOrders() }
            group.addTask { await self.fetchStats() }
            group.addTask { await self.fetchRestaurant() }
        }

        isLoading = false
    }

    func startAutoRefresh() {
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                await self?.fetchActiveOrders()
            }
        }
    }

    func stopAutoRefresh() {
        refreshTimer?.invalidate()
        refreshTimer = nil
    }

    func toggleRestaurantOpen() async {
        guard let restaurant = restaurant else { return }
        do {
            self.restaurant = try await APIService.shared.toggleOpen(!restaurant.isOpen)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func fetchActiveOrders() async {
        do {
            let orders = try await APIService.shared.getOrders()
            self.activeOrders = orders.filter { $0.status.isActive }
                .sorted { $0.createdAt < $1.createdAt }
            self.stats.activeOrders = self.activeOrders.count
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func fetchStats() async {
        do {
            self.stats = try await APIService.shared.getDashboardStats()
        } catch {
            // Stats endpoint might not exist yet; use calculated values
            self.stats.activeOrders = activeOrders.count
        }
    }

    private func fetchRestaurant() async {
        do {
            self.restaurant = try await APIService.shared.getRestaurant()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
