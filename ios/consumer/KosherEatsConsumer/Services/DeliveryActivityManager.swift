import ActivityKit
import Foundation
import os.log

private let logger = Logger(subsystem: "com.koshereats.consumer", category: "DeliveryActivity")

@MainActor
final class DeliveryActivityManager {
    static let shared = DeliveryActivityManager()

    private var currentActivity: Activity<DeliveryAttributes>?

    private init() {
        // End any stale activities left over from a previous app session
        // (e.g. app was killed mid-delivery). Without this, zombie Live
        // Activities persist on the Lock Screen until iOS expires them.
        cleanupStaleActivities()
    }

    // MARK: - Public API

    func startTracking(order: Order) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            logger.info("Live Activities not authorised — skipping start for order \(order.id)")
            return
        }

        // If there's already an activity running, end it first.
        if currentActivity != nil {
            logger.info("Replacing existing activity for new order \(order.id)")
            endTracking(finalStatus: "replaced", displayText: "Replaced by new order")
        }

        let attributes = DeliveryAttributes(
            orderID: order.id,
            restaurantName: order.restaurantName,
            deliveryAddress: order.deliveryAddress,
            itemCount: order.items.count
        )

        let state = makeContentState(from: order)

        do {
            let activity = try Activity.request(
                attributes: attributes,
                content: .init(state: state, staleDate: nil),
                pushType: nil
            )
            currentActivity = activity
            logger.info("Started Live Activity \(activity.id) for order \(order.id)")
        } catch {
            logger.error("Failed to start Live Activity for order \(order.id): \(error.localizedDescription)")
        }
    }

    func update(order: Order) {
        guard let activity = currentActivity else {
            logger.debug("update called but no current activity — ignoring")
            return
        }

        let state = makeContentState(from: order)

        Task {
            await activity.update(.init(state: state, staleDate: nil))
            logger.debug("Updated activity \(activity.id) → \(order.status.rawValue)")
        }
    }

    func endTracking(order: Order) {
        guard let activity = currentActivity else { return }

        // Nil out immediately so no further updates race against dismissal.
        currentActivity = nil

        let finalState = makeContentState(from: order)

        Task {
            await activity.end(
                .init(state: finalState, staleDate: nil),
                dismissalPolicy: .after(.now + 300)
            )
            logger.info("Ended activity \(activity.id) for order \(order.id)")
        }
    }

    // MARK: - Helpers

    private func endTracking(finalStatus: String, displayText: String) {
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

    private func makeContentState(from order: Order) -> DeliveryAttributes.ContentState {
        DeliveryAttributes.ContentState(
            status: order.status.rawValue,
            statusText: statusText(for: order.status),
            eta: order.estDeliveryTime,
            courierName: order.courier?.firstName,
            courierVehicle: courierVehicle(from: order.courier)
        )
    }

    private func cleanupStaleActivities() {
        for activity in Activity<DeliveryAttributes>.activities {
            Task {
                let state = DeliveryAttributes.ContentState(
                    status: "stale",
                    statusText: "Session expired",
                    eta: nil,
                    courierName: nil,
                    courierVehicle: nil
                )
                await activity.end(
                    .init(state: state, staleDate: nil),
                    dismissalPolicy: .immediate
                )
                logger.info("Cleaned up stale activity \(activity.id)")
            }
        }
    }

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
