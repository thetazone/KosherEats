import Foundation

@MainActor
final class OrderTrackingViewModel: ObservableObject {
    @Published var order: Order?
    @Published var errorMessage: String?

    let orderID: String

    private let api = APIService.shared
    private var pollTask: Task<Void, Never>?
    private var pollGeneration = 0
    private var locationStreamTask: Task<Void, Never>?

    /// One-shot guard: prevents a courier_location SSE event storm from firing a
    /// full getOrder() refresh on every event while the order's `courier` is still
    /// nil (assignment window / payload omits courier). Reset once a courier attaches.
    private var didRequestCourierAttach = false

    /// NotificationCenter observer reference so we can unhook on stop().
    /// Without this, every push triggers an immediate refresh — meant — but
    /// stale VMs (deinit'd tracking screens) would keep firing fetches.
    private var pushObserver: NSObjectProtocol?

    init(orderID: String) {
        self.orderID = orderID
    }

    func start() async {
        stop()
        await refresh()
        startPolling()
        // Only open the courier-location SSE for platform deliveries. Pickup
        // orders have no courier, and external (Uber Direct / DoorDash) orders
        // are tracked in the provider's app — both would leave an idle stream
        // open that never carries a courier position. refresh() above is awaited,
        // so self.order is populated by this point.
        let isPickup = order?.fulfillmentType == "pickup"
        let isExternal = order?.isExternalDelivery ?? false
        if !isPickup && !isExternal {
            startLocationStream()
        }
        observePushEvents()
    }

    /// Subscribe to incoming order-event pushes so a status-change push
    /// (`order_accepted`, `order_preparing`, `courier_assigned`, etc.)
    /// triggers an immediate refresh instead of waiting up to 30s for the
    /// next poll tick. Only refreshes when the push's order_id matches this
    /// VM's order — other concurrent orders trigger their own VMs' fetches.
    private func observePushEvents() {
        if pushObserver != nil { return }
        pushObserver = NotificationCenter.default.addObserver(
            forName: .orderStatusUpdated,
            object: nil,
            queue: .main
        ) { [weak self] notif in
            guard let self else { return }
            let pushedID = (notif.userInfo?[PushEvents.orderIDKey] as? String) ?? ""
            guard pushedID == self.orderID else { return }
            Task { @MainActor [weak self] in
                await self?.refresh()
            }
        }
    }

    func refresh() async {
        do {
            var fetched = try await api.getOrder(id: orderID)
            errorMessage = nil
            let isFirst = order == nil
            // SSE is the sole source of courier position. If the stream has
            // already placed the courier, don't let a stale poll response
            // overwrite lat/lng with an older server-side snapshot.
            if var fetchedCourier = fetched.courier,
               let existingCourier = order?.courier,
               fetchedCourier.id == existingCourier.id,
               existingCourier.lat != 0 || existingCourier.lng != 0 {
                fetchedCourier.lat = existingCourier.lat
                fetchedCourier.lng = existingCourier.lng
                fetched.courier = fetchedCourier
            }
            order = fetched

            if isFirst && fetched.status.isActive {
                DeliveryActivityManager.shared.startTracking(order: fetched)
            } else if fetched.status.isActive {
                DeliveryActivityManager.shared.update(order: fetched)
            } else {
                DeliveryActivityManager.shared.endTracking(order: fetched)
            }
        } catch {
            errorMessage = error.isBenignCancellation ? nil : error.localizedDescription
        }
    }

    func stop() {
        pollGeneration &+= 1
        pollTask?.cancel()
        pollTask = nil
        locationStreamTask?.cancel()
        locationStreamTask = nil
        if let pushObserver {
            NotificationCenter.default.removeObserver(pushObserver)
            self.pushObserver = nil
        }
    }

    func markCourierRatingSubmitted(stars: Int) {
        order?.courierRating = stars
    }

    private static let pollIntervalNanos: UInt64 = 8_000_000_000 // 8s

    private func startPolling() {
        pollTask?.cancel()
        pollGeneration &+= 1
        let generation = pollGeneration
        pollTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.pollIntervalNanos)
                guard let self else { return }
                if Task.isCancelled || self.pollGeneration != generation { break }
                await self.refresh()
                if !(self.order?.status.isActive ?? true) { break }
            }
        }
    }

    private func startLocationStream() {
        locationStreamTask?.cancel()
        locationStreamTask = Task { @MainActor [weak self] in
            var consecutiveFailures = 0
            while !Task.isCancelled {
                guard let self else { return }
                var sawAuthFailure = false
                var streamFailed = false
                do {
                    let stream = self.api.streamOrderLocation(id: self.orderID)
                    for try await event in stream {
                        if Task.isCancelled { return }
                        consecutiveFailures = 0
                        self.errorMessage = nil
                        guard event.lat >= -90 && event.lat <= 90,
                              event.lng >= -180 && event.lng <= 180,
                              event.lat != 0 && event.lng != 0 else {
                            continue
                        }
                        if var currentOrder = self.order, var courier = currentOrder.courier {
                            courier.lat = event.lat
                            courier.lng = event.lng
                            currentOrder.courier = courier
                            self.order = currentOrder
                            self.didRequestCourierAttach = false
                            DeliveryActivityManager.shared.update(order: currentOrder)
                        } else if self.order?.courier == nil && !self.didRequestCourierAttach {
                            // First SSE event arrived before poll attached the courier
                            // object — force a refresh so subsequent events can apply.
                            // One-shot: don't re-fire on every event if the refresh
                            // still returns a nil courier (assignment window). The flag
                            // resets above once a courier actually attaches.
                            self.didRequestCourierAttach = true
                            await self.refresh()
                        }
                    }
                } catch APIError.unauthorized {
                    sawAuthFailure = true
                    streamFailed = true
                    let refreshed = try? await self.api.performTokenRefresh()
                    if refreshed != true {
                        self.errorMessage = "Session expired. Please log in again."
                        break
                    }
                } catch {
                    self.errorMessage = error.isBenignCancellation ? nil : error.localizedDescription
                    streamFailed = true
                }

                if Task.isCancelled { break }
                if !(self.order?.status.isActive ?? true) { break }

                if !streamFailed {
                    // Clean server close (load-balancer recycle / terminal-state
                    // handoff) on a still-active order — not a failure. Reset the
                    // backoff and reconnect after a short fixed delay.
                    consecutiveFailures = 0
                    try? await Task.sleep(nanoseconds: 1_000_000_000) // 1s
                    continue
                }

                consecutiveFailures += 1
                let delaySeconds: Double
                if sawAuthFailure {
                    delaySeconds = 2
                } else {
                    delaySeconds = min(3 * pow(2, Double(consecutiveFailures - 1)), 15)
                }
                try? await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
            }
        }
    }

    deinit {
        pollTask?.cancel()
        locationStreamTask?.cancel()
        if let pushObserver { NotificationCenter.default.removeObserver(pushObserver) }
    }
}
