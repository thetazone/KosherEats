import Foundation

@MainActor
class CartViewModel: ObservableObject {
    @Published var cart: Cart?
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var showCartCleared = false

    private let api = APIService.shared

    var itemCount: Int { cart?.itemCount ?? 0 }
    var isEmpty: Bool { cart?.items.isEmpty ?? true }

    func loadCart() async {
        do {
            cart = try await api.getCart()
        } catch {
            // Cart may not exist yet, that is fine
            cart = nil
        }
    }

    func addItem(menuItemID: String, quantity: Int, notes: String?, restaurantID: String) async {
        isLoading = true
        errorMessage = nil

        do {
            cart = try await api.addToCart(
                menuItemID: menuItemID,
                quantity: quantity,
                notes: notes,
                restaurantID: restaurantID
            )
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func updateQuantity(itemID: String, quantity: Int) async {
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
        do {
            cart = try await api.removeCartItem(id: itemID)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func clearCart() async {
        do {
            try await api.clearCart()
            cart = nil
            showCartCleared = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
