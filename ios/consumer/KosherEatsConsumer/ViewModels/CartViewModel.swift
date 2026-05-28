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

    private let api = APIService.shared
    private var cartGeneration = 0

    var itemCount: Int { cart?.itemCount ?? 0 }
    var isEmpty: Bool { cart?.items.isEmpty ?? true }

    var discount: Int {
        guard let deal = appliedDeal, let cart = cart, cart.subtotal > 0 else { return 0 }
        guard deal.restaurantId == cart.restaurantID else { return 0 }
        if let min = deal.minOrderAmount, cart.subtotal < min { return 0 }
        switch deal.discountType {
        case .percentage:
            return min(cart.subtotal, Int(Double(cart.subtotal) * Double(deal.discountValue) / 100.0))
        case .fixed:
            return min(deal.discountValue, cart.subtotal)
        case .bogo:
            guard cart.itemCount >= 2 else { return 0 }
            return cart.items.map(\.price).min() ?? 0
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
        } catch let APIError.httpError(code, _) where code == 404 {
            cart = nil
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
            if let idx = existing.items.firstIndex(where: { $0.menuItemID == menuItemID && $0.notes == notes }) {
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
