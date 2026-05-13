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

    private var queue: [AppRoute] = []

    /// Clear any pending routes — called on logout to prevent stale
    /// deep links from firing after a different user signs in.
    func clearPendingRoutes() {
        queue.removeAll()
        pendingTrackingOrderId = nil
        pendingDetailOrderId = nil
    }

    func navigate(_ route: AppRoute) {
        queue.append(route)
        guard queue.count == 1 else { return }
        DispatchQueue.main.async { [weak self] in
            self?.processNextRoute()
        }
    }

    private func processNextRoute() {
        guard !queue.isEmpty else { return }
        let route = queue.removeFirst()

        pendingTrackingOrderId = nil
        pendingDetailOrderId = nil

        switch route {
        case .ordersTab:
            selectedTab = .orders
        case .tracking(let orderID):
            selectedTab = .orders
            pendingTrackingOrderId = orderID
        case .detail(let orderID):
            selectedTab = .orders
            pendingDetailOrderId = orderID
        }

        if !queue.isEmpty {
            DispatchQueue.main.async { [weak self] in
                self?.processNextRoute()
            }
        }
    }
}
