import AudioToolbox
import AVFoundation
import Combine
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
    /// Surfaces a sticky banner when the polling loop has failed N times in
    /// a row. Kept separate from `errorMessage` (which is consumed by views
    /// that only render errors when the list is empty) so a multi-hour outage
    /// during a busy service is impossible to miss.
    @Published var pollHealthError: String?

    private var knownPendingIDs: Set<String> = []
    /// Order IDs for which a mutation request is in-flight. The polling merge
    /// skips these so an accept/reject/prepare/ready tap can't be stomped by a
    /// poll tick that still sees the old server state.
    private var inFlightOrderIDs: Set<String> = []
    /// Generation counter so a poll that started before a `load()` (or a
    /// restaurant switch) doesn't overwrite fresher state when it eventually
    /// returns. Each load+poll sequence bumps this; in-flight tasks check it
    /// before mutating @Published state.
    private var loadGeneration = 0
    /// Combine subscription that fires whenever the seller picks a different
    /// restaurant in the picker — drops in-flight polls and reloads against
    /// the new restaurant_id.
    private var restaurantSubscription: AnyCancellable?
    /// NotificationCenter observer that reloads orders the moment a courier
    /// or system push arrives — closes the ~30s polling-only gap where the
    /// dashboard kept showing "Waiting for a courier..." after a claim.
    private var pushObserver: NSObjectProtocol?

    init() {
        pushObserver = NotificationCenter.default.addObserver(
            forName: .orderStatusUpdated,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            // Don't filter on order_id here — the seller dashboard is a
            // multi-order surface, and the cheapest correct response to
            // "some order changed" is to reload the whole list. The poll
            // already does this; we're just kicking it on demand.
            Task { @MainActor [weak self] in
                await self?.load()
            }
        }
    }

    deinit {
        if let pushObserver { NotificationCenter.default.removeObserver(pushObserver) }
        restaurantSubscription?.cancel()
        restaurantSubscription = nil
    }

    /// Auto-dismisses `successMessage` after a short beat so the toast
    /// doesn't stay pinned over the courier card (which was the bug: once
    /// "Order ready for courier pickup" set the message, it never cleared).
    private func flash(_ message: String) {
        successMessage = message
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if self?.successMessage == message {
                self?.successMessage = nil
            }
        }
    }

    enum OrderFilter: String, CaseIterable, Identifiable {
        case active = "Active"
        case completed = "Completed"
        case all = "All"

        var id: String { rawValue }
    }

    func load() async {
        loadGeneration &+= 1
        let gen = loadGeneration
        isLoading = true
        errorMessage = nil
        pollHealthError = nil

        do {
            let fresh = try await APIService.shared.getOrders()
            guard gen == loadGeneration else { return }
            // Preserve any order with a mutation in flight (an accept/prepare/ready
            // tap the seller is watching) — a push-driven load() landing the
            // pre-mutation server state would otherwise stomp the optimistic
            // update. mergeFresh keeps in-flight rows and calls applyFilter.
            mergeFresh(fresh)
        } catch APIError.unauthorized {
            guard gen == loadGeneration else { return }
            // Session died mid-use (refresh token expired/revoked). Route to
            // login instead of showing a generic "couldn't load" error the
            // seller can't act on.
            notifySessionExpired()
        } catch {
            guard gen == loadGeneration else { return }
            errorMessage = error.localizedDescription
        }

        if gen == loadGeneration {
            isLoading = false
        }
    }

    /// Loads once, then re-loads every `interval` seconds until cancelled.
    /// Intended to be called from SwiftUI `.task`, which auto-cancels when
    /// the view disappears, so new orders appear without the seller having
    /// to pull-to-refresh (they'd otherwise miss tickets sitting in the
    /// pending queue).
    ///
    /// Each polling tick re-reads `loadGeneration` so an explicit `load()`
    /// (pull-to-refresh, push, Retry) only drops a stale in-flight tick — it
    /// does NOT terminate the loop. A restaurant switch tears the whole task
    /// down via `.task(id:)` cancellation, which is the only thing that ends
    /// the loop besides the view disappearing.
    func loadAndAutoRefresh(interval: TimeInterval = 15) async {
        configureAudioSession()
        defer { deactivateAudioSession() }
        subscribeToRestaurantChanges()
        await load()
        // Only seed on first run — preserve across task restarts so orders
        // that arrived while the seller was on another tab still trigger alerts.
        if self.knownPendingIDs.isEmpty {
            self.knownPendingIDs = Set(self.orders.filter { $0.status == .pending }.map(\.id))
        }
        var consecutiveFailures = 0
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
            if Task.isCancelled { break }
            // Re-read the generation at the top of each tick. An external
            // load() (push observer, pull-to-refresh, Retry) bumps the gen —
            // we DON'T break on that, or the loop would die permanently and
            // new-order alerts would stop. Instead this tick's result is
            // checked against the gen captured here, so a concurrent load()
            // only invalidates the in-flight tick rather than terminating the
            // loop. A restaurant switch tears the whole task down via
            // `.task(id: selectedRestaurant.id)` cancellation, so the loop
            // never needs to self-terminate on a gen mismatch.
            let gen = loadGeneration
            // Silent refresh — no spinner on subsequent ticks.
            do {
                let fresh = try await APIService.shared.getOrders()
                // A concurrent load() landed while this tick was in flight —
                // its fresher result wins, so drop this stale one but keep
                // polling (the next tick re-reads the bumped gen).
                guard gen == loadGeneration else { continue }
                let newPending = Set(fresh.filter { $0.status == .pending }.map(\.id))
                let brandNew = newPending.subtracting(self.knownPendingIDs)
                if !brandNew.isEmpty {
                    playNewOrderAlert()
                    Haptics.notify(.warning)
                }
                self.knownPendingIDs = newPending
                mergeFresh(fresh)
                consecutiveFailures = 0
                pollHealthError = nil
            } catch APIError.unauthorized {
                // The refresh token expired or was revoked mid-session. This is
                // NOT a network blip — no number of retries will recover it, and
                // showing "Can't reach server" would be a lie. Hand off to
                // AuthViewModel (via .sessionExpired) to clear tokens and route
                // back to the login screen, then stop polling.
                notifySessionExpired()
                break
            } catch {
                consecutiveFailures += 1
                // Three in a row (~45s of silence) is long enough to be real —
                // surface a sticky banner so a wedged network doesn't leave
                // the seller staring at a stale ticket list mid-service.
                if consecutiveFailures >= 3 {
                    pollHealthError = "Can't reach server — orders may be out of date."
                }
            }
        }
    }

    /// Posts `.sessionExpired` so AuthViewModel can clear the stored tokens and
    /// drop the app back to the login screen with a "your session expired"
    /// message. Guards against re-posting on every subsequent tick/call once
    /// we've already kicked the seller out of an authenticated session.
    ///
    /// The routing back to login is owned by `AuthViewModel.isAuthenticated`,
    /// which this view model cannot reach directly — so the actual token
    /// invalidation + `isAuthenticated = false` flip lives in AuthViewModel's
    /// `.sessionExpired` observer (registered in its init, calling
    /// `handleSessionExpired()`). Here we only fire the event and clear the
    /// misleading "can't reach server" banner.
    private var didNotifySessionExpired = false
    private func notifySessionExpired() {
        guard !didNotifySessionExpired else { return }
        didNotifySessionExpired = true
        pollHealthError = nil
        errorMessage = nil
        NotificationCenter.default.post(name: .sessionExpired, object: nil)
    }

    /// Reload Orders (and reset polling state) whenever the seller picks a
    /// different restaurant. Without this the tab keeps showing the previous
    /// restaurant's tickets until the seller kills and relaunches the app.
    private func subscribeToRestaurantChanges() {
        guard restaurantSubscription == nil else { return }
        restaurantSubscription = SelectedRestaurant.shared.$id
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.knownPendingIDs = []
                    self.inFlightOrderIDs = []
                    self.pollHealthError = nil
                    await self.load()
                }
            }
    }

    /// `.playback` with `.mixWithOthers` lets the new-order ping cut through
    /// silent mode (common on a restaurant counter phone) without hijacking
    /// any other audio the device is playing.
    private func configureAudioSession() {
        // `setActive(true)` is an IPC round-trip to the media server that can
        // block for tens-to-hundreds of ms — too slow for the main actor,
        // which this @MainActor VM otherwise runs on. AVAudioSession is
        // thread-safe, so hop off to a utility task. The session only needs to
        // be active before the first new-order ping plays, so the async hop
        // doesn't race anything user-visible.
        Task.detached(priority: .utility) {
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
                try session.setActive(true)
            } catch {
                #if DEBUG
                print("[audio] failed to configure session: \(error)")
                #endif
            }
        }
    }

    private func deactivateAudioSession() {
        Task.detached(priority: .utility) {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    /// Timestamp of the last alert sound. Used to debounce rapid-fire alerts
    /// when multiple pending orders arrive in the same poll tick.
    private var lastAlertTime: Date = .distantPast

    /// Triple-ping on a brand-new ticket — a single 1007 is easy to miss in
    /// a loud kitchen. Three short beeps within ~1.2s has a distinctive
    /// cadence sellers learn to recognize.
    ///
    /// Debounced: skips if an alert was played within the last 2 seconds so
    /// multiple new pending orders in one poll don't stack overlapping pings.
    private func playNewOrderAlert() {
        let now = Date()
        guard now.timeIntervalSince(lastAlertTime) >= 2.0 else { return }
        lastAlertTime = now
        AudioServicesPlaySystemSound(1007)
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 400_000_000)
            AudioServicesPlaySystemSound(1007)
            try? await Task.sleep(nanoseconds: 400_000_000)
            AudioServicesPlaySystemSound(1007)
        }
    }

    func applyFilter() {
        switch selectedFilter {
        case .active:
            filteredOrders = orders.filter { $0.status.isActive }
                .sorted { ($0.createdAtDate ?? .distantPast) > ($1.createdAtDate ?? .distantPast) }
        case .completed:
            filteredOrders = orders.filter { !$0.status.isActive }
                .sorted { ($0.createdAtDate ?? .distantPast) > ($1.createdAtDate ?? .distantPast) }
        case .all:
            filteredOrders = orders.sorted { ($0.createdAtDate ?? .distantPast) > ($1.createdAtDate ?? .distantPast) }
        }
    }

    func fetchOrder(id: String) async {
        do {
            let fetched = try await APIService.shared.getOrder(id: id)
            updateOrder(fetched)
            pollHealthError = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // The optimistic status check (e.g. `orders.first?.status == .pending`)
    // used to live in each guard but produced false-negatives whenever a
    // poll-driven `orders` reassignment landed between the user tapping the
    // button and the guard evaluating: `orders.first(where:)` would briefly
    // return nil, the optional chain `?.status == .pending` would be false,
    // and the action would silently no-op. That manifested as "first tap of
    // Accept does nothing" right after a failed reject (#17). The backend
    // already validates state transitions and returns a 400 for invalid ones,
    // so we keep just the in-flight race guard and let the server be the
    // source of truth.

    func acceptOrder(id: String) async {
        guard !inFlightOrderIDs.contains(id) else { return }
        inFlightOrderIDs.insert(id)
        defer { inFlightOrderIDs.remove(id) }
        do {
            let updated = try await APIService.shared.acceptOrder(id: id)
            updateOrder(updated)
            Haptics.success()
            flash("Order accepted")
        } catch {
            errorMessage = error.localizedDescription
            if let fresh = try? await APIService.shared.getOrder(id: id) {
                updateOrder(fresh)
            }
        }
    }

    func rejectOrder(id: String, reason: String? = nil) async {
        guard !inFlightOrderIDs.contains(id) else { return }
        inFlightOrderIDs.insert(id)
        defer { inFlightOrderIDs.remove(id) }
        do {
            let updated = try await APIService.shared.rejectOrder(id: id, reason: reason)
            updateOrder(updated)
            Haptics.success()
            flash("Order rejected")
        } catch {
            errorMessage = error.localizedDescription
            if let fresh = try? await APIService.shared.getOrder(id: id) {
                updateOrder(fresh)
            }
        }
    }

    func markPreparing(id: String) async {
        guard !inFlightOrderIDs.contains(id) else { return }
        inFlightOrderIDs.insert(id)
        defer { inFlightOrderIDs.remove(id) }
        do {
            let updated = try await APIService.shared.markOrderPreparing(id: id)
            updateOrder(updated)
            Haptics.success()
            flash("Started preparing")
        } catch {
            errorMessage = error.localizedDescription
            if let fresh = try? await APIService.shared.getOrder(id: id) {
                updateOrder(fresh)
            }
        }
    }

    func markReady(id: String) async {
        guard !inFlightOrderIDs.contains(id) else { return }
        inFlightOrderIDs.insert(id)
        defer { inFlightOrderIDs.remove(id) }
        do {
            let updated = try await APIService.shared.markOrderReady(id: id)
            updateOrder(updated)
            Haptics.success()
            flash("Order ready for courier pickup")
        } catch {
            errorMessage = error.localizedDescription
            if let fresh = try? await APIService.shared.getOrder(id: id) {
                updateOrder(fresh)
            }
        }
    }

    /// Used by the seller's "Mark Picked Up" button on pickup-fulfillment
    /// orders at status='ready'. Drives the same code path as the other
    /// status-transition methods.
    func markCompleted(id: String) async {
        guard !inFlightOrderIDs.contains(id) else { return }
        inFlightOrderIDs.insert(id)
        defer { inFlightOrderIDs.remove(id) }
        do {
            let updated = try await APIService.shared.markOrderCompleted(id: id)
            updateOrder(updated)
            Haptics.success()
            flash("Order picked up")
        } catch {
            errorMessage = error.localizedDescription
            if let fresh = try? await APIService.shared.getOrder(id: id) {
                updateOrder(fresh)
            }
        }
    }

    /// Self-delivery: the seller's own driver collected the order (ready ->
    /// picked_up). The endpoint returns a status map, so re-fetch the order.
    func markSelfPickup(id: String) async {
        guard !inFlightOrderIDs.contains(id) else { return }
        inFlightOrderIDs.insert(id)
        defer { inFlightOrderIDs.remove(id) }
        do {
            try await APIService.shared.sellerPickupOrder(id: id)
            await fetchOrder(id: id)
            Haptics.success()
            flash("Marked picked up")
        } catch {
            errorMessage = error.localizedDescription
            if let fresh = try? await APIService.shared.getOrder(id: id) { updateOrder(fresh) }
        }
    }

    /// Self-delivery: the seller's own driver delivered the order (picked_up ->
    /// delivered). Credits 50% of the delivery fee server-side.
    func markSelfDeliver(id: String) async {
        guard !inFlightOrderIDs.contains(id) else { return }
        inFlightOrderIDs.insert(id)
        defer { inFlightOrderIDs.remove(id) }
        do {
            try await APIService.shared.sellerDeliverOrder(id: id)
            await fetchOrder(id: id)
            Haptics.success()
            flash("Marked delivered")
        } catch {
            errorMessage = error.localizedDescription
            if let fresh = try? await APIService.shared.getOrder(id: id) { updateOrder(fresh) }
        }
    }

    private func updateOrder(_ updated: Order) {
        if let idx = orders.firstIndex(where: { $0.id == updated.id }) {
            orders[idx] = updated
        } else {
            orders.append(updated)
        }
        applyFilter()
    }

    /// Replaces the order list with a freshly-fetched batch while preserving
    /// any order that currently has a mutation in flight. An accept / reject /
    /// prepare / ready tap optimistically updates the local copy, but a poll
    /// (this VM's own loop) or the dashboard's 30s timer can land a `fresh`
    /// batch that still reflects the pre-mutation server state — without this
    /// guard that stale copy would stomp the optimistic update the seller is
    /// watching in SellerOrderDetailView. Shared by the poll loop here and by
    /// DashboardViewModel.fetchActiveOrders via `sharedOrdersVM`.
    func mergeFresh(_ fresh: [Order]) {
        let inFlightOrders = Dictionary(
            uniqueKeysWithValues: orders
                .filter { inFlightOrderIDs.contains($0.id) }
                .map { ($0.id, $0) }
        )
        // Existing in-memory copies, keyed by id, so we can carry forward the
        // detail-only fields (courier, customer name/phone) the `/seller/orders`
        // list query strips — otherwise a poll/dashboard merge would blank the
        // courier card on the order the seller is viewing.
        let existingByID = Dictionary(uniqueKeysWithValues: orders.map { ($0.id, $0) })
        var merged = fresh.map { freshOrder -> Order in
            // An in-flight optimistic copy always wins — its status mutation is
            // newer than this poll's pre-mutation server state.
            if let inFlight = inFlightOrders[freshOrder.id] { return inFlight }
            if let existing = existingByID[freshOrder.id] {
                return Order(merging: freshOrder, preservingFrom: existing)
            }
            return freshOrder
        }
        let freshIDs = Set(fresh.map(\.id))
        merged.append(contentsOf: inFlightOrders.values.filter { !freshIDs.contains($0.id) })
        orders = merged
        applyFilter()
    }
}

// MARK: - Session Notifications

extension Notification.Name {
    /// Fired when an authenticated API call fails with `.unauthorized` after
    /// the refresh attempt was exhausted — i.e. the session is dead (refresh
    /// token expired or revoked mid-session) and no retry can recover it.
    ///
    /// AuthViewModel observes this to clear the stored tokens and set
    /// `isAuthenticated = false`, which routes the app back to
    /// `SellerLoginView`. Declared here (rather than in PushEvents.swift)
    /// because this is an auth-lifecycle event, distinct from the order-push
    /// event bus, and OrdersViewModel is the first site that needs it.
    static let sessionExpired = Notification.Name("ke.sessionExpired")
}
