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
            self.orders = fresh
            applyFilter()
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
    /// Polling ticks check `loadGeneration` so a restaurant switch (or any
    /// explicit `load()`) cleanly abandons an in-flight tick rather than
    /// letting the old restaurant's orders stomp on the new view.
    func loadAndAutoRefresh(interval: TimeInterval = 15) async {
        configureAudioSession()
        defer { deactivateAudioSession() }
        subscribeToRestaurantChanges()
        await load()
        let gen = loadGeneration
        // Only seed on first run — preserve across task restarts so orders
        // that arrived while the seller was on another tab still trigger alerts.
        if self.knownPendingIDs.isEmpty {
            self.knownPendingIDs = Set(self.orders.filter { $0.status == .pending }.map(\.id))
        }
        var consecutiveFailures = 0
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
            if Task.isCancelled { break }
            // Restaurant switched (or an external load() bumped the gen);
            // abandon this loop — the new load() will start its own.
            if gen != loadGeneration { break }
            // Silent refresh — no spinner on subsequent ticks.
            do {
                let fresh = try await APIService.shared.getOrders()
                guard gen == loadGeneration else { break }
                let newPending = Set(fresh.filter { $0.status == .pending }.map(\.id))
                let brandNew = newPending.subtracting(self.knownPendingIDs)
                if !brandNew.isEmpty {
                    playNewOrderAlert()
                    Haptics.notify(.warning)
                }
                self.knownPendingIDs = newPending
                let inFlightOrders = Dictionary(
                    uniqueKeysWithValues: self.orders
                        .filter { inFlightOrderIDs.contains($0.id) }
                        .map { ($0.id, $0) }
                )
                self.orders = fresh.map { order in
                    inFlightOrders[order.id] ?? order
                }
                applyFilter()
                consecutiveFailures = 0
                pollHealthError = nil
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
                    self.pollHealthError = nil
                }
            }
    }

    /// `.playback` with `.mixWithOthers` lets the new-order ping cut through
    /// silent mode (common on a restaurant counter phone) without hijacking
    /// any other audio the device is playing.
    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            #if DEBUG
            print("[audio] failed to configure session: \(error)")
            #endif
        }
    }

    private func deactivateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Triple-ping on a brand-new ticket — a single 1007 is easy to miss in
    /// a loud kitchen. Three short beeps within ~1.2s has a distinctive
    /// cadence sellers learn to recognize.
    private func playNewOrderAlert() {
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
                .sorted { $0.createdAt > $1.createdAt }
        case .completed:
            filteredOrders = orders.filter { !$0.status.isActive }
                .sorted { $0.createdAt > $1.createdAt }
        case .all:
            filteredOrders = orders.sorted { $0.createdAt > $1.createdAt }
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
            flash("Order picked up")
        } catch {
            errorMessage = error.localizedDescription
            if let fresh = try? await APIService.shared.getOrder(id: id) {
                updateOrder(fresh)
            }
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
}
