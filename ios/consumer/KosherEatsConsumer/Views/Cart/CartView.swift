import SwiftUI

struct CartView: View {
    @EnvironmentObject var cartVM: CartViewModel
    @Environment(\.dismiss) var dismiss
    @State private var showCheckout = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                if cartVM.isEmpty {
                    emptyCartView
                } else if let cart = cartVM.cart {
                    VStack(spacing: 0) {
                        ScrollView(showsIndicators: false) {
                            VStack(spacing: 16) {
                                // Cart items
                                ForEach(cart.items) { item in
                                    CartItemRow(item: item)
                                }

                                Divider().background(Color.keDivider)

                                // Order summary
                                orderSummary(cart: cart)
                            }
                            .padding()
                        }

                        // Checkout button
                        checkoutSection(cart: cart)
                    }
                }
            }
            .navigationTitle("Cart")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Close") { dismiss() }
                        .foregroundColor(.kePrimary)
                }
                if !cartVM.isEmpty {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("Clear") {
                            Task { await cartVM.clearCart() }
                        }
                        .foregroundColor(.keError)
                    }
                }
            }
            .navigationDestination(isPresented: $showCheckout) {
                CheckoutView(onOrderPlaced: { _ in
                    // Order was placed and user dismissed confirmation.
                    // Dismiss the cart sheet and go to orders tab.
                    dismiss()
                    NotificationCenter.default.post(name: .navigateToOrdersTab, object: nil)
                })
                .environmentObject(cartVM)
            }
        }
    }

    // MARK: - Empty Cart

    private var emptyCartView: some View {
        VStack(spacing: 16) {
            Image(systemName: "cart")
                .font(.system(size: 64))
                .foregroundColor(.keTextMuted)
            Text("Your cart is empty")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.keTextPrimary)
            Text("Add items from a restaurant to get started")
                .font(.body)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)
            Button {
                dismiss()
            } label: {
                Text("Browse Restaurants")
            }
            .buttonStyle(KEPrimaryButtonStyle())
            .frame(width: 220)
        }
    }

    // MARK: - Order Summary

    private func orderSummary(cart: Cart) -> some View {
        VStack(spacing: 10) {
            SummaryRow(label: "Subtotal", value: cart.subtotalFormatted)
            SummaryRow(label: "Delivery Fee", value: "Calculated at checkout")
            SummaryRow(label: "Service Fee", value: "Calculated at checkout")

            Divider().background(Color.keDivider)

            HStack {
                Text("Estimated Total")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Text(cart.subtotalFormatted)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(.kePrimary)
            }
        }
    }

    // MARK: - Checkout

    private func checkoutSection(cart: Cart) -> some View {
        VStack(spacing: 0) {
            Divider().background(Color.keDivider)

            VStack(spacing: 12) {
                Button {
                    showCheckout = true
                } label: {
                    HStack {
                        Text("Checkout")
                        Spacer()
                        Text(cart.subtotalFormatted)
                    }
                }
                .buttonStyle(KEPrimaryButtonStyle())
            }
            .padding()
            .background(Color.keBackgroundElevated)
        }
    }
}

// MARK: - Cart Item Row

struct CartItemRow: View {
    let item: CartItem
    @EnvironmentObject var cartVM: CartViewModel

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.keTextPrimary)

                // Selected modifiers shown as a single dot-separated line —
                // same pattern UberEats uses in the cart view.
                if let summary = item.modifierSummary {
                    Text(summary)
                        .font(.system(size: 12))
                        .foregroundColor(.keTextTertiary)
                        .lineLimit(2)
                }

                if let notes = item.notes, !notes.isEmpty {
                    Text(notes)
                        .font(.system(size: 12))
                        .foregroundColor(.keTextMuted)
                        .italic()
                }

                Text(item.totalFormatted)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(.kePrimary)
            }

            Spacer()

            // Quantity controls
            HStack(spacing: 12) {
                Button {
                    Task {
                        await cartVM.updateQuantity(itemID: item.id, quantity: item.quantity - 1)
                    }
                } label: {
                    Image(systemName: item.quantity == 1 ? "trash" : "minus")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(item.quantity == 1 ? .keError : .keTextPrimary)
                        .frame(width: 30, height: 30)
                        .background(Color.keCardHover)
                        .cornerRadius(8)
                }

                Text("\(item.quantity)")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                    .frame(width: 24)

                Button {
                    Task {
                        await cartVM.updateQuantity(itemID: item.id, quantity: item.quantity + 1)
                    }
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.kePrimary)
                        .frame(width: 30, height: 30)
                        .background(Color.kePrimary.opacity(0.15))
                        .cornerRadius(8)
                }
            }
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }
}

// MARK: - Summary Row

struct SummaryRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.system(size: 15))
                .foregroundColor(.keTextSecondary)
            Spacer()
            Text(value)
                .font(.system(size: 15))
                .foregroundColor(.keTextSecondary)
        }
    }
}
