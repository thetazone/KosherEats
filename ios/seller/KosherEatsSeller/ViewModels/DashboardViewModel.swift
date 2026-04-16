import Combine
import Foundation
import SwiftUI

@MainActor
class DashboardViewModel: ObservableObject {
    @Published var activeOrders: [Order] = []
    @Published var stats = DashboardStats()
    @Published var restaurant: Restaurant?
    /// Drives the picker button in the toolbar — we only want to surface it
    /// when the seller actually owns more than one restaurant.
    @Published var restaurantCount: Int = 1
    @Published var isLoading = false
    @Published var errorMessage: String?

    private var refreshTimer: Timer?
    /// Fires whenever the seller picks a different restaurant so the
    /// dashboard reloads orders/stats/profile for the new one instead of
    /// waiting out the 30s timer tick.
    private var restaurantSubscription: AnyCancellable?

    func load() async {
        isLoading = true
        errorMessage = nil

        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.fetchActiveOrders() }
            group.addTask { await self.fetchStats() }
            group.addTask { await self.fetchRestaurant() }
            group.addTask { await self.fetchRestaurantCount() }
        }

        isLoading = false
    }

    func startAutoRefresh() {
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                await self?.fetchActiveOrders()
            }
        }
        if restaurantSubscription == nil {
            restaurantSubscription = SelectedRestaurant.shared.$id
                .dropFirst()
                .removeDuplicates()
                .sink { [weak self] _ in
                    Task { @MainActor [weak self] in
                        await self?.load()
                    }
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

    private func fetchRestaurantCount() async {
        // Best-effort. If this fails we hide the picker rather than surfacing
        // an unrelated error on the dashboard — the dashboard's primary job is
        // showing the currently-active restaurant.
        if let list = try? await APIService.shared.listRestaurants() {
            self.restaurantCount = list.count
        }
    }
}
