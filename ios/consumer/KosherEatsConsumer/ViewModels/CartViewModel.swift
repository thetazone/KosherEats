import Foundation

@MainActor
class CartViewModel: ObservableObject {
    /// Half-second debounce after an add-to-cart error before reloading the cart.
    private static let cartDebounceNanos: UInt64 = 500_000_000

    @Published var cart: Cart?
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var showCartCleared = false
    @Published private(set) var isReordering = false
    @Published var appliedDeal: Deal?

    /// A pending add that would replace the current cart because it's from a
    /// different restaurant. The View binds to this to present a confirmation
    /// alert; the add only proceeds via `confirmPendingRestaurantSwitch()`.
    @Published var pendingRestaurantSwitch: PendingRestaurantSwitch?

    /// Captured parameters for an add-to-cart that is awaiting the user's
    /// confirmation to discard their existing cart from another restaurant.
    struct PendingRestaurantSwitch: Identifiable {
        let id = UUID()
        let menuItemID: String
        let quantity: Int
        let notes: String?
        let restaurantID: String
        /// The restaurant being switched *to*. Optional because the add-to-cart
        /// UI only has the restaurant id in scope; when nil the confirmation
        /// message degrades to a generic phrasing.
        let restaurantName: String?
        let modifierIDs: [String]
        let itemName: String
        let unitPrice: Int
        let selectedModifiers: [SelectedModifier]

        /// Title for the confirmation alert the View presents.
        var alertTitle: String {
            if let restaurantName, !restaurantName.isEmpty {
                return String(localized: "Start a new cart at \(restaurantName)?")
            }
            return String(localized: "Start a new cart?")
        }

        /// Body for the confirmation alert: warns that the existing cart from a
        /// different restaurant will be discarded (mirrors the backend wipe).
        var alertMessage: String {
            String(localized: "Your items from your current cart will be removed.")
        }
    }

    private let api = APIService.shared
    private var cartGeneration = 0

    var itemCount: Int { cart?.itemCount ?? 0 }
    var isEmpty: Bool { cart?.items.isEmpty ?? true }

    var discount: Int {
        guard let deal = appliedDeal, let cart = cart, cart.subtotal > 0 else { return 0 }
        guard deal.restaurantId == cart.restaurantID else { return 0 }
        guard !Self.isExpired(deal) else { return 0 }
        if let min = deal.minOrderAmount, cart.subtotal < min { return 0 }
        switch deal.discountType {
        case .percentage:
            return min(cart.subtotal, Int(Double(cart.subtotal) * Double(deal.discountValue) / 100.0))
        case .fixed:
            return min(deal.discountValue, cart.subtotal)
        case .bogo:
            guard cart.itemCount >= 2 else { return 0 }
            return cart.items.map(\.price).min() ?? 0
        case .unknown:
            return 0
        }
    }

    var discountedSubtotal: Int {
        (cart?.subtotal ?? 0) - discount
    }

    func applyDeal(_ deal: Deal) {
        appliedDeal = deal
    }

    func removeDeal() {
        appliedDeal = nil
    }

    /// The deal id to attach to checkout, or `nil` when no deal currently
    /// applies. Returns `nil` if the applied deal belongs to a different
    /// restaurant than the current cart or has expired, so callers never send a
    /// stale deal that the backend would reject with a 400. CheckoutView should
    /// read this rather than `appliedDeal?.id` directly.
    var dealIdForCheckout: String? {
        guard let deal = appliedDeal, let cart else { return nil }
        guard deal.restaurantId == cart.restaurantID, !Self.isExpired(deal) else { return nil }
        return deal.id
    }

    /// ISO-8601 parsers covering timestamps with and without fractional
    /// seconds (mirrors DealsView, since the backend emits both forms).
    private static let expiryFormatters: [ISO8601DateFormatter] = {
        let f1 = ISO8601DateFormatter()
        f1.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return [f1, f2]
    }()

    /// True when the deal has an `expiresAt` timestamp in the past. An
    /// unparseable timestamp is treated as not-expired (lets the backend be the
    /// authority) rather than silently dropping a possibly-valid deal.
    private static func isExpired(_ deal: Deal) -> Bool {
        guard let raw = deal.expiresAt else { return false }
        for f in expiryFormatters {
            if let date = f.date(from: raw) { return date <= Date() }
        }
        return false
    }

    /// Drops `appliedDeal` if it no longer matches the current cart's
    /// restaurant (or there is no cart). Called after every cart mutation so a
    /// deal tied to a previous restaurant can't survive a server-side cart swap
    /// and reach checkout. Expiry is handled at read time (`discount`,
    /// `dealIdForCheckout`) so a deal that expires mid-session still surfaces
    /// removed in the UI without needing a cart mutation to trigger it.
    private func reconcileAppliedDeal() {
        guard let deal = appliedDeal else { return }
        if cart?.restaurantID != deal.restaurantId {
            appliedDeal = nil
        }
    }

    // MARK: - Stale cart

    /// Reloads the cart from the server and drops it if the restaurant's menu
    /// has changed (e.g. items removed or prices updated). Callers should
    /// invoke this when navigating back to the cart after a period of
    /// inactivity, or when the menu screen detects a version/etag change.
    /// For local (guest) carts there is no server-side validation; the only
    /// remedy is to clear and re-add items.
    func revalidateCart() async {
        guard api.isAuthenticated else { return }
        await loadCart()
    }

    // MARK: - Load

    func loadCart() async {
        guard api.isAuthenticated else { return }
        errorMessage = nil
        do {
            cart = try await api.getCart()
            reconcileAppliedDeal()
        } catch let APIError.httpError(code, _) where code == 404 {
            cart = nil
            reconcileAppliedDeal()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Add

    @discardableResult
    func addItem(
        menuItemID: String,
        quantity: Int,
        notes: String?,
        restaurantID: String,
        modifierIDs: [String] = [],
        itemName: String = "",
        unitPrice: Int = 0,
        selectedModifiers: [SelectedModifier] = []
    ) async -> String? {
        if !api.isAuthenticated {
            addLocalItem(
                menuItemID: menuItemID,
                quantity: quantity,
                notes: notes,
                restaurantID: restaurantID,
                itemName: itemName,
                unitPrice: unitPrice,
                selectedModifiers: selectedModifiers
            )
            Haptics.success()
            return nil
        }

        isLoading = true
        errorMessage = nil
        let gen = cartGeneration

        do {
            let result = try await api.addToCart(
                menuItemID: menuItemID,
                quantity: quantity,
                notes: notes,
                restaurantID: restaurantID,
                modifierIDs: modifierIDs,
            )
            guard cartGeneration == gen else { isLoading = false; return nil }
            cart = result
            reconcileAppliedDeal()
            Haptics.success()
            isLoading = false
            return nil
        } catch {
            let msg = error.localizedDescription
            errorMessage = msg
            Haptics.error()
            try? await Task.sleep(nanoseconds: Self.cartDebounceNanos)
            await loadCart()
            isLoading = false
            return msg
        }
    }

    // MARK: - Add with restaurant-switch confirmation

    /// True if adding an item from `restaurantID` would discard the current
    /// non-empty cart because it belongs to a different restaurant. Mirrors the
    /// backend's AddToCart behaviour (it deletes the old cart_items and
    /// reassigns the cart) and the local guest-cart "start fresh" branch.
    func wouldSwitchRestaurant(to restaurantID: String) -> Bool {
        guard let cart, !cart.items.isEmpty else { return false }
        return cart.restaurantID != restaurantID
    }

    /// Entry point for the Add-to-Cart UI. If the add would silently wipe an
    /// existing cart from another restaurant, it stashes the request and
    /// publishes `pendingRestaurantSwitch` so the View can confirm first;
    /// otherwise it adds immediately. Returns the add error message (if any)
    /// when it proceeded without needing confirmation, or `nil` when it either
    /// succeeded or is now awaiting confirmation.
    @discardableResult
    func requestAddItem(
        menuItemID: String,
        quantity: Int,
        notes: String?,
        restaurantID: String,
        restaurantName: String? = nil,
        modifierIDs: [String] = [],
        itemName: String = "",
        unitPrice: Int = 0,
        selectedModifiers: [SelectedModifier] = []
    ) async -> String? {
        if wouldSwitchRestaurant(to: restaurantID) {
            pendingRestaurantSwitch = PendingRestaurantSwitch(
                menuItemID: menuItemID,
                quantity: quantity,
                notes: notes,
                restaurantID: restaurantID,
                restaurantName: restaurantName,
                modifierIDs: modifierIDs,
                itemName: itemName,
                unitPrice: unitPrice,
                selectedModifiers: selectedModifiers
            )
            return nil
        }
        return await addItem(
            menuItemID: menuItemID,
            quantity: quantity,
            notes: notes,
            restaurantID: restaurantID,
            modifierIDs: modifierIDs,
            itemName: itemName,
            unitPrice: unitPrice,
            selectedModifiers: selectedModifiers
        )
    }

    /// Proceeds with the add the user confirmed will replace their cart.
    @discardableResult
    func confirmPendingRestaurantSwitch() async -> String? {
        guard let pending = pendingRestaurantSwitch else { return nil }
        pendingRestaurantSwitch = nil
        // The deal was tied to the old restaurant's cart; drop it so it can't
        // be misapplied to the new restaurant's items.
        appliedDeal = nil
        return await addItem(
            menuItemID: pending.menuItemID,
            quantity: pending.quantity,
            notes: pending.notes,
            restaurantID: pending.restaurantID,
            modifierIDs: pending.modifierIDs,
            itemName: pending.itemName,
            unitPrice: pending.unitPrice,
            selectedModifiers: pending.selectedModifiers
        )
    }

    /// Dismisses the pending add, leaving the existing cart untouched.
    func cancelPendingRestaurantSwitch() {
        pendingRestaurantSwitch = nil
    }

    func reorder(items: [OrderItem], restaurantID: String) async -> String? {
        guard !isReordering else { return nil }
        isReordering = true
        defer { isReordering = false }

        for item in items {
            if let err = await addItem(
                menuItemID: item.menuItemID,
                quantity: item.quantity,
                notes: item.notes,
                restaurantID: restaurantID,
                modifierIDs: item.selectedModifiers?.map(\.id) ?? []
            ) {
                await loadCart()
                return err
            }
        }
        return nil
    }

    // MARK: - Update / Remove / Clear

    func updateQuantity(itemID: String, quantity: Int) async {
        if !api.isAuthenticated {
            updateLocalQuantity(itemID: itemID, quantity: quantity)
            return
        }
        do {
            if quantity <= 0 {
                cart = try await api.removeCartItem(id: itemID)
            } else {
                cart = try await api.updateCartItem(id: itemID, quantity: quantity)
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func removeItem(itemID: String) async {
        if !api.isAuthenticated {
            updateLocalQuantity(itemID: itemID, quantity: 0)
            return
        }
        do {
            cart = try await api.removeCartItem(id: itemID)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func clearCart() async {
        cartGeneration &+= 1
        appliedDeal = nil
        if !api.isAuthenticated {
            cart = nil
            showCartCleared = true
            return
        }
        do {
            try await api.clearCart()
            cart = nil
            showCartCleared = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Sync local cart to server after sign-in

    func syncLocalCartIfNeeded() async {
        guard api.isAuthenticated, let localCart = cart,
              localCart.id.hasPrefix("local-") else { return }

        let items = localCart.items
        cart = nil

        for item in items {
            _ = await addItem(
                menuItemID: item.menuItemID,
                quantity: item.quantity,
                notes: item.notes,
                restaurantID: localCart.restaurantID,
                modifierIDs: item.selectedModifiers?.map(\.id) ?? []
            )
        }
    }

    // MARK: - Local cart helpers

    private func addLocalItem(
        menuItemID: String,
        quantity: Int,
        notes: String?,
        restaurantID: String,
        itemName: String,
        unitPrice: Int,
        selectedModifiers: [SelectedModifier]
    ) {
        let modifierDelta = selectedModifiers.reduce(0) { $0 + $1.priceDelta }
        let priceWithMods = unitPrice + modifierDelta

        if var existing = cart, existing.restaurantID == restaurantID {
            if let idx = existing.items.firstIndex(where: {
                $0.menuItemID == menuItemID && $0.notes == notes
                && Set($0.selectedModifiers ?? []) == Set(selectedModifiers)
            }) {
                existing.items[idx].quantity += quantity
                existing.subtotal = existing.items.reduce(0) { $0 + $1.price * $1.quantity }
                cart = existing
            } else {
                let newItem = CartItem(
                    id: UUID().uuidString,
                    cartID: existing.id,
                    menuItemID: menuItemID,
                    name: itemName,
                    price: priceWithMods,
                    quantity: quantity,
                    notes: notes,
                    selectedModifiers: selectedModifiers.isEmpty ? nil : selectedModifiers
                )
                existing.items.append(newItem)
                existing.subtotal = existing.items.reduce(0) { $0 + $1.price * $1.quantity }
                cart = existing
            }
        } else {
            // Different restaurant or no cart — start fresh
            appliedDeal = nil
            let newItem = CartItem(
                id: UUID().uuidString,
                cartID: "local-cart",
                menuItemID: menuItemID,
                name: itemName,
                price: priceWithMods,
                quantity: quantity,
                notes: notes,
                selectedModifiers: selectedModifiers.isEmpty ? nil : selectedModifiers
            )
            cart = Cart(
                id: "local-cart",
                userID: "guest",
                restaurantID: restaurantID,
                items: [newItem],
                subtotal: priceWithMods * quantity
            )
        }
    }

    private func updateLocalQuantity(itemID: String, quantity: Int) {
        guard var existing = cart else { return }
        if quantity <= 0 {
            existing.items.removeAll { $0.id == itemID }
        } else if let idx = existing.items.firstIndex(where: { $0.id == itemID }) {
            existing.items[idx].quantity = quantity
        }
        if existing.items.isEmpty {
            cart = nil
        } else {
            existing.subtotal = existing.items.reduce(0) { $0 + $1.price * $1.quantity }
            cart = existing
        }
    }
}
