import Foundation

@MainActor
class OrderViewModel: ObservableObject {
    @Published var orders: [Order] = []
    @Published var currentOrder: Order?
    @Published var isLoading = false
    @Published var errorMessage: String?
    /// Set when a cancel request fails (e.g. the backend rejects the status).
    /// Kept separate from `errorMessage` so a cancel failure surfaces as a
    /// transient alert while the loaded order stays on screen, rather than
    /// replacing the whole detail view with the load-error state.
    @Published var cancelError: String?
    /// True while a cancel request is in flight, to disable the button and
    /// show a spinner.
    @Published var isCancelling = false
    /// True while an older-history page fetch is in flight, so the list can
    /// show a trailing spinner and the onAppear hook can avoid stacking
    /// duplicate fetches for the same last row.
    @Published var isLoadingMore = false
    /// True while the backend may still have older orders to return. Flips to
    /// false once a page comes back with fewer than `limit` rows, which both
    /// hides the load-more affordance and stops the onAppear hook from looping.
    @Published var canLoadMore = true

    /// Page size for keyset history pagination. Must match the value used as
    /// the "short page = end of history" sentinel in loadMorePastOrders.
    private let pageSize = 50

    private let api = APIService.shared
    /// Handle for the in-flight poll loop started by `startPolling`. Storing
    /// it so we can cancel on view disappear / before launching a new poll —
    /// otherwise re-entering OrderDetailView stacks a fresh 10s poller on
    /// every visit against the same order id.
    private var pollTask: Task<Void, Never>?
    private var pollGeneration = 0

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
            // Use the keyset-paginated endpoint for the first page so older
            // history is reachable via loadMorePastOrders. A full first page
            // means there may be more; a short one means we already have it all.
            let page = try await api.listOrders(cursor: nil, limit: pageSize)
            orders = page
            canLoadMore = page.count >= pageSize
        } catch {
            errorMessage = error.isBenignCancellation ? nil : error.localizedDescription
        }

        isLoading = false
    }

    /// Fetches the next page of older orders and APPENDS it to `orders`. The
    /// backend returns rows in descending created_at across all statuses, so
    /// the oldest currently-loaded row (`orders.last`) is the keyset cursor for
    /// the next page. Guards prevent re-fetch loops: returns early if a fetch is
    /// already in flight, there's nothing more to load, or there's no cursor.
    func loadMorePastOrders() async {
        guard canLoadMore, !isLoadingMore, !isLoading else { return }
        guard let cursor = orders.last?.createdAt else { return }

        isLoadingMore = true
        defer { isLoadingMore = false }

        do {
            let page = try await api.listOrders(cursor: cursor, limit: pageSize)
            // Dedupe in case the cursor row shares a timestamp with a boundary
            // row the backend re-includes — keyset on a non-unique created_at
            // can overlap by one.
            let existingIDs = Set(orders.map { $0.id })
            let fresh = page.filter { !existingIDs.contains($0.id) }
            orders.append(contentsOf: fresh)
            // Stop when the backend hands back a short page: no more history.
            canLoadMore = page.count >= pageSize
        } catch {
            errorMessage = error.isBenignCancellation ? nil : error.localizedDescription
        }
    }

    func loadOrder(id: String) async {
        isLoading = true
        errorMessage = nil

        do {
            currentOrder = try await api.getOrder(id: id)
        } catch {
            errorMessage = error.isBenignCancellation ? nil : error.localizedDescription
        }

        isLoading = false
    }

    func createOrder(
        deliveryAddress: String,
        lat: Double,
        lng: Double,
        paymentIntentId: String,
        tip: Int,
        scheduledFor: Date? = nil,
    ) async -> Order? {
        isLoading = true
        errorMessage = nil

        do {
            let order = try await api.createOrder(
                deliveryAddress: deliveryAddress, lat: lat, lng: lng,
                paymentIntentId: paymentIntentId, tip: tip,
                scheduledFor: scheduledFor,
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
        guard !isCancelling else { return }
        isCancelling = true
        cancelError = nil
        defer { isCancelling = false }
        do {
            currentOrder = try await api.cancelOrder(id: id)
            if let index = orders.firstIndex(where: { $0.id == id }),
               let updated = currentOrder {
                orders[index] = updated
            }
        } catch {
            // Surface a friendly message; the backend currently 400s on a
            // scheduled-order cancel until the whitelist companion change lands.
            cancelError = cancelFailureMessage(for: error)
        }
    }

    /// Human-readable copy for a failed cancel. A 4xx from the server (e.g. the
    /// status isn't in the CancelOrder whitelist) gets actionable phrasing;
    /// everything else falls back to the underlying error description.
    private func cancelFailureMessage(for error: Error) -> String {
        if case let APIError.httpError(code, _) = error, (400..<500).contains(code) {
            return String(localized: "This order can't be cancelled in the app right now. Please contact support to cancel and request a refund.")
        }
        return error.localizedDescription
    }

    func startPolling(orderID: String) {
        pollTask?.cancel()
        pollGeneration &+= 1
        let gen = pollGeneration
        pollTask = Task { [weak self] in
            // Fetch immediately so callers see fresh data without a 10s lag.
            await self?.loadOrder(id: orderID)
            while !Task.isCancelled {
                guard let self else { break }
                guard !Task.isCancelled, self.pollGeneration == gen else { break }
                if let order = self.currentOrder, !order.status.isActive { break }
                try? await Task.sleep(nanoseconds: 10_000_000_000) // 10s
                guard !Task.isCancelled, self.pollGeneration == gen else { break }
                await self.loadOrder(id: orderID)
            }
        }
    }

    func stopPolling() {
        pollGeneration &+= 1
        pollTask?.cancel()
        pollTask = nil
    }

    deinit {
        pollTask?.cancel()
    }
}
