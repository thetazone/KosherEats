import Combine
import Foundation
import SwiftUI

@MainActor
class MenuViewModel: ObservableObject {
    @Published var categories: [MenuCategory] = []
    @Published var allItems: [MenuItem] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var successMessage: String?
    @Published var togglingItemIDs: Set<String> = []

    /// The most recent menu import — surfaced as a banner while it runs and
    /// briefly when it finishes. nil when there's nothing to show.
    @Published var activeImport: MenuImport?
    private var importPollTask: Task<Void, Never>?
    #if DEBUG
    /// Preview/screenshot harness: when true, load() is a no-op so seeded
    /// categories + activeImport survive without a live backend.
    var previewMode = false
    #endif

    /// Watches `SelectedRestaurant` so a switch in the picker reloads the
    /// menu tab in place — otherwise sellers would see the previous
    /// restaurant's items until they relaunch.
    private var restaurantSubscription: AnyCancellable?
    private var isReloading = false
    private var pendingReload = false

    func startObservingRestaurant() {
        guard restaurantSubscription == nil else { return }
        restaurantSubscription = SelectedRestaurant.shared.$id
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] _ in
                Task { @MainActor [weak self] in
                    await self?.load()
                }
            }
    }

    func load() async {
        #if DEBUG
        if previewMode { isLoading = false; return }
        #endif
        guard !isReloading else {
            pendingReload = true
            return
        }
        isReloading = true
        defer {
            isReloading = false
            if pendingReload {
                pendingReload = false
                Task { @MainActor [weak self] in
                    await self?.load()
                }
            }
        }
        isLoading = true
        errorMessage = nil

        do {
            let cats = try await APIService.shared.getMenu()
            self.categories = cats
            self.allItems = cats.flatMap { $0.items ?? [] }
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func createItem(
        categoryId: String,
        name: String,
        description: String,
        price: Int, // cents
        imageUrl: String,
        isMeat: Bool,
        isDairy: Bool,
        isPareve: Bool
    ) async -> Bool {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = "Item name is required"
            return false
        }
        guard name.count <= 100 else {
            errorMessage = "Item name must be 100 characters or less"
            return false
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        let request = CreateMenuItemRequest(
            categoryId: categoryId,
            name: name,
            description: description,
            price: price,
            imageUrl: imageUrl,
            isMeat: isMeat,
            isDairy: isDairy,
            isPareve: isPareve,
            isAvailable: true
        )

        do {
            _ = try await APIService.shared.createMenuItem(request)
            flash("Item created successfully")
            await load()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func updateItem(
        id: String,
        categoryId: String,
        name: String,
        description: String,
        price: Int, // cents
        imageUrl: String,
        isMeat: Bool,
        isDairy: Bool,
        isPareve: Bool,
        isAvailable: Bool
    ) async -> Bool {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = "Item name is required"
            return false
        }
        guard name.count <= 100 else {
            errorMessage = "Item name must be 100 characters or less"
            return false
        }
        isLoading = true
        defer { isLoading = false }
        errorMessage = nil

        let request = CreateMenuItemRequest(
            categoryId: categoryId,
            name: name,
            description: description,
            price: price,
            imageUrl: imageUrl,
            isMeat: isMeat,
            isDairy: isDairy,
            isPareve: isPareve,
            isAvailable: isAvailable
        )

        do {
            _ = try await APIService.shared.updateMenuItem(id: id, request)
            flash("Item updated successfully")
            await load()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func deleteItem(id: String) async {
        errorMessage = nil
        do {
            try await APIService.shared.deleteMenuItem(id: id)
            flash("Item deleted")
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func toggleAvailability(item: MenuItem) async {
        guard !togglingItemIDs.contains(item.id) else { return }
        togglingItemIDs.insert(item.id)
        defer { togglingItemIDs.remove(item.id) }

        let newValue = !item.isAvailable
        setAvailability(id: item.id, newValue)

        do {
            _ = try await APIService.shared.toggleItemAvailability(
                id: item.id,
                available: newValue
            )
        } catch {
            errorMessage = error.localizedDescription
            // Refetch from server rather than in-place rollback — if load()
            // ran between the optimistic write and this catch, the local
            // categories array is already replaced and the rollback would no-op.
            await load()
        }
    }

    private func flash(_ message: String) {
        successMessage = message
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if self?.successMessage == message {
                self?.successMessage = nil
            }
        }
    }

    private func setAvailability(id: String, _ value: Bool) {
        for i in categories.indices {
            guard var items = categories[i].items else { continue }
            if let j = items.firstIndex(where: { $0.id == id }) {
                items[j].isAvailable = value
                categories[i].items = items
            }
        }
        if let k = allItems.firstIndex(where: { $0.id == id }) {
            allItems[k].isAvailable = value
        }
    }

    func createCategory(name: String) async {
        errorMessage = nil
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            errorMessage = "Category name cannot be empty."
            return
        }
        guard trimmed.count <= 100 else {
            errorMessage = "Category name must be 100 characters or less."
            return
        }
        if categories.contains(where: { $0.name.lowercased() == trimmed.lowercased() }) {
            errorMessage = "A category with this name already exists."
            return
        }
        do {
            _ = try await APIService.shared.createCategory(trimmed)
            flash("Category created")
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteCategory(id: String) async {
        errorMessage = nil
        do {
            try await APIService.shared.deleteCategory(id: id)
            flash("Category deleted")
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Import status

    /// Watches the latest menu import. While one is pending/running it polls
    /// every few seconds, surfacing progress; when it finishes it reloads the
    /// menu so the imported items appear, holds the result briefly, then clears.
    func startImportWatch() {
        guard importPollTask == nil else { return }
        importPollTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.importPollTask = nil }
            guard let job = await self.fetchLatestImport(), job.isInProgress else { return }
            self.activeImport = job
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                if Task.isCancelled { return }
                guard let latest = await self.fetchLatestImport() else { return }
                self.activeImport = latest
                if !latest.isInProgress {
                    await self.load()                       // surface imported items
                    try? await Task.sleep(nanoseconds: 4_000_000_000)
                    if self.activeImport?.id == latest.id { self.activeImport = nil }
                    return
                }
            }
        }
    }

    func stopImportWatch() {
        importPollTask?.cancel()
        importPollTask = nil
    }

    private func fetchLatestImport() async -> MenuImport? {
        (try? await APIService.shared.listMenuImports())?.first
    }

    #if DEBUG
    /// Seeded state for the screenshot harness: an in-progress import over a
    /// small real-looking menu. No backend calls (previewMode short-circuits load()).
    static func previewImporting() -> MenuViewModel {
        let vm = MenuViewModel()
        vm.previewMode = true
        let item = { (id: String, name: String, cents: Int) in
            MenuItem(id: id, restaurantId: "r1", categoryId: "c1", name: name,
                     description: "", imageUrl: nil, price: cents,
                     isMeat: false, isDairy: false, isPareve: true,
                     isAvailable: true, modifierGroups: nil)
        }
        vm.categories = [
            MenuCategory(id: "c1", restaurantId: "r1", name: "From the Oven", sortOrder: 0,
                         items: [item("i1", "Slice of Pizza", 450), item("i2", "Kalamata Pizza Pie", 4000)]),
            MenuCategory(id: "c2", restaurantId: "r1", name: "From the Fryer", sortOrder: 1,
                         items: [item("i3", "Mozzarella Sticks (12 pcs)", 1650), item("i4", "Four Garlic Knots", 400)]),
        ]
        vm.allItems = vm.categories.flatMap { $0.items ?? [] }
        vm.activeImport = MenuImport(id: "imp1", status: "running", sourceUrl: nil,
                                     itemsTotal: 14, itemsCreated: 6, error: nil)
        return vm
    }
    #endif
}
