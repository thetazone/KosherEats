import ActivityKit
import Foundation

@MainActor
final class DeliveryActivityManager {
    static let shared = DeliveryActivityManager()
    private init() {}

    private var currentActivity: Activity<DeliveryAttributes>?

    func startTracking(order: Order) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

        let attributes = DeliveryAttributes(
            orderID: order.id,
            restaurantName: order.restaurantName,
            deliveryAddress: order.deliveryAddress,
            itemCount: order.items.count
        )

        let state = DeliveryAttributes.ContentState(
            status: order.status.rawValue,
            statusText: statusText(for: order.status),
            eta: order.estDeliveryTime,
            courierName: order.courier?.firstName,
            courierVehicle: courierVehicle(from: order.courier)
        )

        do {
            let activity = try Activity.request(
                attributes: attributes,
                content: .init(state: state, staleDate: nil),
                pushType: nil
            )
            currentActivity = activity
        } catch {
            // LiveActivity start failed — non-critical; tracking view
            // still works via polling. Swallow silently in production.
        }
    }

    func update(order: Order) {
        guard let activity = currentActivity else { return }

        let state = DeliveryAttributes.ContentState(
            status: order.status.rawValue,
            statusText: statusText(for: order.status),
            eta: order.estDeliveryTime,
            courierName: order.courier?.firstName,
            courierVehicle: courierVehicle(from: order.courier)
        )

        Task {
            await activity.update(.init(state: state, staleDate: nil))
        }
    }

    func endTracking(order: Order) {
        guard let activity = currentActivity else { return }
        currentActivity = nil

        let finalState = DeliveryAttributes.ContentState(
            status: order.status.rawValue,
            statusText: order.status == .delivered ? "Delivered — enjoy!" : "Order \(order.status.displayName.lowercased())",
            eta: order.estDeliveryTime,
            courierName: order.courier?.firstName,
            courierVehicle: courierVehicle(from: order.courier)
        )

        Task {
            await activity.end(.init(state: finalState, staleDate: nil), dismissalPolicy: .after(.now + 300))
        }
    }

    /// End tracking without an Order object — used by logout / session cleanup
    /// paths where the full order isn't available.
    func endTracking(finalStatus: String, displayText: String) {
        guard let activity = currentActivity else { return }
        currentActivity = nil

        let state = DeliveryAttributes.ContentState(
            status: finalStatus,
            statusText: displayText,
            eta: nil,
            courierName: nil,
            courierVehicle: nil
        )

        Task {
            await activity.end(
                .init(state: state, staleDate: nil),
                dismissalPolicy: .immediate
            )
        }
    }

    // MARK: - Helpers

    private func statusText(for status: OrderStatus) -> String {
        switch status {
        case .scheduled: return "Scheduled for later"
        case .pending: return "Waiting for restaurant"
        case .accepted: return "Restaurant accepted"
        case .preparing: return "Your food is being prepared"
        case .ready: return "Ready for pickup"
        case .pickedUp: return "Your order is on the way"
        case .delivered: return "Delivered — enjoy!"
        case .cancelled: return "Order cancelled"
        case .rejected: return "Order rejected"
        case .completed: return "Order completed"
        @unknown default: return "Unknown status"
        }
    }

    private func courierVehicle(from courier: CourierPublic?) -> String? {
        guard let c = courier else { return nil }
        return [c.vehicleColor, c.vehicleMake, c.vehicleModel]
            .compactMap { $0 }
            .joined(separator: " ")
    }
}
