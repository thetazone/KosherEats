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

    /// Shared OrdersViewModel so Dashboard→OrderDetail navigations use a
    /// VM that already has orders loaded, avoiding silent no-op actions.
    let sharedOrdersVM = OrdersViewModel()

    private var refreshTimer: Timer?
    /// Fires whenever the seller picks a different restaurant so the
    /// dashboard reloads orders/stats/profile for the new one instead of
    /// waiting out the 30s timer tick.
    private var restaurantSubscription: AnyCancellable?
    /// Generation counter so a slow fetch started against Restaurant A can't
    /// overwrite state after the seller switches to Restaurant B.
    private var loadGeneration = 0
    /// Id of the restaurant the currently-displayed `stats` belong to, and
    /// whether we've ever loaded. Used to avoid blowing away last-good stats on
    /// every pull-to-refresh: we only zero `stats` when the seller actually
    /// switched restaurants (mirrors the Android `lastFetchedRestaurantId`
    /// guard). Otherwise a refresh briefly flashed "$0.00 / 0 orders" until the
    /// network returned.
    private var statsRestaurantId: String?
    private var hasLoadedStatsOnce = false

    func load() async {
        loadGeneration &+= 1
        let gen = loadGeneration
        isLoading = true
        errorMessage = nil
        // Only reset stats when the selected restaurant actually changed (or on
        // the very first load). On a same-restaurant refresh we keep the
        // last-good numbers visible — fetchStats overwrites them in place once
        // it returns — so the cards never flash zeros mid-service.
        let currentRestaurantId = SelectedRestaurant.shared.id
        if !hasLoadedStatsOnce || statsRestaurantId != currentRestaurantId {
            stats = DashboardStats()
        }
        hasLoadedStatsOnce = true
        statsRestaurantId = currentRestaurantId

        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.fetchActiveOrders(generation: gen) }
            group.addTask { await self.fetchStats(generation: gen) }
            group.addTask { await self.fetchRestaurant(generation: gen) }
            group.addTask { await self.fetchRestaurantCount(generation: gen) }
        }

        if gen == loadGeneration {
            isLoading = false
        }
    }

    func startAutoRefresh() {
        stopAutoRefresh()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                // Capture generation *inside* the task so it reflects the
                // current value at tick time, not the value when the timer
                // was created.
                let gen = self.loadGeneration
                await withTaskGroup(of: Void.self) { group in
                    group.addTask { await self.fetchActiveOrders(generation: gen) }
                    group.addTask { await self.fetchStats(generation: gen) }
                    group.addTask { await self.fetchRestaurant(generation: gen) }
                }
            }
        }
        if restaurantSubscription == nil {
            restaurantSubscription = SelectedRestaurant.shared.$id
                .dropFirst()
                .removeDuplicates()
                .sink { [weak self] _ in
                    guard let self else { return }
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

    deinit {
        refreshTimer?.invalidate()
        restaurantSubscription?.cancel()
    }

    @Published var isTogglingOpen = false

    func setRestaurantOpen(_ isOpen: Bool) async {
        guard !isTogglingOpen else { return }
        isTogglingOpen = true
        errorMessage = nil
        let gen = loadGeneration
        defer { isTogglingOpen = false }
        do {
            let result = try await APIService.shared.toggleOpen(isOpen)
            guard gen == loadGeneration else { return }
            self.restaurant = result
        } catch {
            guard gen == loadGeneration else { return }
            errorMessage = error.localizedDescription
        }
    }

    private func fetchActiveOrders(generation: Int) async {
        do {
            let orders = try await APIService.shared.getOrders()
            guard generation == loadGeneration else { return }
            let filtered = orders.filter { $0.status.isActive }
                .sorted { $0.createdAt > $1.createdAt }
            self.activeOrders = filtered
            // Keep the shared VM in sync so Dashboard→OrderDetail
            // navigations can find the order via syncOrderFromVM().
            // Route through mergeFresh so the dashboard's 30s timer (and
            // load()) can't stomp an accept/reject/prepare/ready mutation
            // that's mid-flight in the shared VM — same in-flight guard the
            // OrdersViewModel poll loop uses. A direct `orders =` assignment
            // here bypassed that guard and could revert an optimistic update
            // the seller is watching in SellerOrderDetailView.
            sharedOrdersVM.mergeFresh(orders)
        } catch {
            guard generation == loadGeneration else { return }
            errorMessage = error.localizedDescription
        }
    }

    private func fetchStats(generation: Int) async {
        do {
            let fetched = try await APIService.shared.getDashboardStats()
            guard generation == loadGeneration else { return }
            self.stats = fetched
        } catch {
            guard generation == loadGeneration else { return }
            errorMessage = error.localizedDescription
        }
    }

    private func fetchRestaurant(generation: Int) async {
        do {
            let fetched = try await APIService.shared.getRestaurant()
            guard generation == loadGeneration else { return }
            self.restaurant = fetched
        } catch {
            guard generation == loadGeneration else { return }
            errorMessage = error.localizedDescription
        }
    }

    private func fetchRestaurantCount(generation: Int) async {
        // Best-effort. If this fails we hide the picker rather than surfacing
        // an unrelated error on the dashboard — the dashboard's primary job is
        // showing the currently-active restaurant.
        if let list = try? await APIService.shared.listRestaurants() {
            guard generation == loadGeneration else { return }
            self.restaurantCount = list.count
        }
    }
}
