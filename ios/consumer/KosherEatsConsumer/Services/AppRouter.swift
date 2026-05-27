import Foundation
import SwiftUI

enum AppTab: Hashable {
    case home
    case nearby
    case deals
    case orders
    case profile
}

enum AppRoute: Equatable {
    case ordersTab
    case tracking(orderID: String)
    case detail(orderID: String)
    case restaurant(id: String)
}

/// Shared app router for cross-tab navigation initiated by checkout flows,
/// push notifications, and other non-local interactions. It serializes
/// requests so order detail and tracking destinations never activate at the
/// same time.
@MainActor
final class AppRouter: ObservableObject {
    static let shared = AppRouter()

    @Published var selectedTab: AppTab = .home
    @Published var pendingTrackingOrderId: String?
    @Published var pendingDetailOrderId: String?
    @Published var pendingRestaurantId: String?

    private var queue: [AppRoute] = []
    private var isProcessing = false

    /// Clear any pending routes -- called on logout to prevent stale
    /// deep links from firing after a different user signs in.
    func clearPendingRoutes() {
        queue.removeAll()
        isProcessing = false
        pendingTrackingOrderId = nil
        pendingDetailOrderId = nil
        pendingRestaurantId = nil
    }

    func navigate(_ route: AppRoute) {
        // Deduplicate: skip if the same route is already last in the queue
        // or is the only pending route.
        if let last = queue.last, last == route { return }
        if queue.isEmpty, routeMatchesCurrent(route) { return }

        queue.append(route)
        guard !isProcessing else { return }
        processQueue()
    }

    /// Parse a deep link URL and navigate. Supports:
    ///   koshereats://orders
    ///   koshereats://orders/{id}
    ///   koshereats://orders/{id}/tracking
    ///   koshereats://restaurant/{id}
    func handleDeepLink(_ url: URL) {
        let path = url.pathComponents.filter { $0 != "/" }

        if path.first == "orders" || url.host == "orders" {
            let segments = url.host == "orders"
                ? Array(path)
                : Array(path.dropFirst())

            if segments.isEmpty {
                // koshereats://orders (host-style) or koshereats:///orders/
                navigate(.ordersTab)
            } else if segments.count >= 2, segments.last == "tracking" {
                navigate(.tracking(orderID: segments[segments.count - 2]))
            } else {
                // Just an order ID — show detail
                navigate(.detail(orderID: segments[0]))
            }
        } else if path.first == "restaurant" || url.host == "restaurant" {
            let segments = url.host == "restaurant"
                ? Array(path)
                : Array(path.dropFirst())
            if let id = segments.first {
                navigate(.restaurant(id: id))
            }
        }
    }

    // MARK: - Private

    private func routeMatchesCurrent(_ route: AppRoute) -> Bool {
        switch route {
        case .ordersTab:
            return selectedTab == .orders
                && pendingTrackingOrderId == nil && pendingDetailOrderId == nil
        case .tracking(let id):
            return pendingTrackingOrderId == id
        case .detail(let id):
            return pendingDetailOrderId == id
        case .restaurant(let id):
            return pendingRestaurantId == id
        }
    }

    private func processQueue() {
        guard !queue.isEmpty else {
            isProcessing = false
            return
        }
        isProcessing = true
        let route = queue.removeFirst()

        // Clear previous pending destinations so only one is active.
        pendingTrackingOrderId = nil
        pendingDetailOrderId = nil
        pendingRestaurantId = nil

        switch route {
        case .ordersTab:
            selectedTab = .orders
        case .tracking(let orderID):
            selectedTab = .orders
            pendingTrackingOrderId = orderID
        case .detail(let orderID):
            selectedTab = .orders
            pendingDetailOrderId = orderID
        case .restaurant(let id):
            selectedTab = .home
            pendingRestaurantId = id
        }

        if !queue.isEmpty {
            // Give SwiftUI a run-loop cycle to settle the tab/navigation
            // change before pushing the next route.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                self?.processQueue()
            }
        } else {
            isProcessing = false
        }
    }
}
