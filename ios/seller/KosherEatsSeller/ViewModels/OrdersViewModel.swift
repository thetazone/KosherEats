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
    /// Generation counter so a poll that started before a `load()` (or a
    /// restaurant switch) doesn't overwrite fresher state when it eventually
    /// returns. Each load+poll sequence bumps this; in-flight tasks check it
    /// before mutating @Published state.
    private var loadGeneration = 0
    /// Combine subscription that fires whenever the seller picks a different
    /// restaurant in the picker — drops in-flight polls and reloads against
    /// the new restaurant_id.
    private var restaurantSubscription: AnyCancellable?

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
        subscribeToRestaurantChanges()
        await load()
        let gen = loadGeneration
        self.knownPendingIDs = Set(self.orders.filter { $0.status == .pending }.map(\.id))
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
                self.orders = fresh
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
                    await self.load()
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
            print("[audio] failed to configure session: \(error)")
        }
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

    func acceptOrder(id: String) async {
        do {
            let updated = try await APIService.shared.acceptOrder(id: id)
            updateOrder(updated)
            flash("Order accepted")
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func rejectOrder(id: String, reason: String? = nil) async {
        do {
            let updated = try await APIService.shared.rejectOrder(id: id, reason: reason)
            updateOrder(updated)
            flash("Order rejected")
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func markPreparing(id: String) async {
        do {
            let updated = try await APIService.shared.markOrderPreparing(id: id)
            updateOrder(updated)
            flash("Started preparing")
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func markReady(id: String) async {
        do {
            let updated = try await APIService.shared.markOrderReady(id: id)
            updateOrder(updated)
            flash("Order ready for courier pickup")
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func updateOrder(_ updated: Order) {
        if let idx = orders.firstIndex(where: { $0.id == updated.id }) {
            orders[idx] = updated
        }
        applyFilter()
    }
}
