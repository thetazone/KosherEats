import SwiftUI

struct CartView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var cartVM: CartViewModel
    @EnvironmentObject var router: AppRouter
    @Environment(\.dismiss) var dismiss
    @State private var showCheckout = false
    @State private var showLoginSheet = false

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
            .alert("Cart Error", isPresented: Binding(
                get: { cartVM.errorMessage != nil },
                set: { if !$0 { cartVM.errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(cartVM.errorMessage ?? "")
            }
            .navigationDestination(isPresented: $showCheckout) {
                CheckoutView(onOrderPlaced: { _ in
                    // Order was placed and user dismissed confirmation.
                    // Dismiss the cart sheet and go to orders tab.
                    dismiss()
                    router.navigate(.ordersTab)
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
            Text(String(localized: "Your cart is empty"))
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.keTextPrimary)
            Text(String(localized: "Add items from a restaurant to get started"))
                .font(.body)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)
            Button {
                dismiss()
            } label: {
                Text(String(localized: "Browse Restaurants"))
            }
            .buttonStyle(KEPrimaryButtonStyle())
            .frame(maxWidth: 320)
        }
    }

    // MARK: - Order Summary

    private func orderSummary(cart: Cart) -> some View {
        VStack(spacing: 10) {
            SummaryRow(label: "Subtotal", value: cart.subtotalFormatted)

            if let deal = cartVM.appliedDeal {
                HStack {
                    HStack(spacing: 6) {
                        Image(systemName: "tag.fill")
                            .font(.system(size: 12))
                        Text(deal.title)
                            .font(.system(size: 14, weight: .semibold))
                    }
                    .foregroundColor(.keSuccess)

                    Spacer()

                    if cartVM.discount > 0 {
                        Text("-$\(String(format: "%.2f", Double(cartVM.discount) / 100))")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.keSuccess)
                    }

                    Button {
                        cartVM.removeDeal()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 16))
                            .foregroundColor(.keTextMuted)
                    }
                }
            }

            SummaryRow(label: "Delivery Fee", value: "Calculated at checkout")
            SummaryRow(label: "Service Fee", value: "Calculated at checkout")

            Divider().background(Color.keDivider)

            HStack {
                Text("Estimated Total")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Text("$\(String(format: "%.2f", Double(cartVM.discountedSubtotal) / 100))")
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
                    if authVM.isAuthenticated {
                        showCheckout = true
                    } else {
                        showLoginSheet = true
                    }
                } label: {
                    HStack {
                        Text("Checkout")
                        Spacer()
                        Text("$\(String(format: "%.2f", Double(cartVM.discountedSubtotal) / 100))")
                    }
                }
                .buttonStyle(KEPrimaryButtonStyle())
            }
            .padding()
            .background(Color.keBackgroundElevated)
        }
        .sheet(isPresented: $showLoginSheet) {
            LoginView(dismissLabel: "Back")
                .environmentObject(authVM)
                .presentationDetents([.medium, .large])
        }
    }
}

// MARK: - Cart Item Row

struct CartItemRow: View {
    let item: CartItem
    @EnvironmentObject var cartVM: CartViewModel

    /// Optimistic quantity shown in the stepper. `nil` means "no pending local
    /// edit — trust the cart". Tracking it locally lets rapid taps accumulate
    /// off the latest *intended* value instead of the stale rendered
    /// `item.quantity`, so tapping + twice from 1 reliably reaches 3.
    @State private var pendingQuantity: Int?
    /// True while a quantity PATCH for this row is in flight. Used to coalesce:
    /// extra taps update `pendingQuantity`, and a single follow-up request is
    /// sent with the newest value once the in-flight one returns.
    @State private var isUpdating = false

    /// Quantity to render — the optimistic value if the user is mid-edit,
    /// otherwise the server-backed value.
    private var displayQuantity: Int { pendingQuantity ?? item.quantity }

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
                    Haptics.impact(.light)
                    setQuantity(max(0, displayQuantity - 1))
                } label: {
                    Image(systemName: displayQuantity <= 1 ? "trash" : "minus")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(displayQuantity <= 1 ? .keError : .keTextPrimary)
                        .frame(width: 30, height: 30)
                        .background(Color.keCardHover)
                        .cornerRadius(8)
                }
                .accessibilityLabel(displayQuantity <= 1 ? "Remove item" : "Decrease quantity")

                Text("\(displayQuantity)")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                    .frame(width: 24)
                    .accessibilityLabel("\(displayQuantity)")

                Button {
                    Haptics.impact(.light)
                    setQuantity(displayQuantity + 1)
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.kePrimary)
                        .frame(width: 30, height: 30)
                        .background(Color.kePrimary.opacity(0.15))
                        .cornerRadius(8)
                }
                .accessibilityLabel("Increase quantity")
            }
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        // If the server-backed quantity catches up to (or moves past) our
        // optimistic value while no request is in flight, drop the local
        // override so we render the source of truth again.
        .onChange(of: item.quantity) { _, newValue in
            if !isUpdating, pendingQuantity == newValue { pendingQuantity = nil }
        }
    }

    /// Records the new desired quantity optimistically and drives a coalesced,
    /// serialized PATCH. Concurrent taps only ever leave one request in flight;
    /// the latest desired value is sent once the prior one resolves.
    private func setQuantity(_ newQuantity: Int) {
        pendingQuantity = newQuantity
        guard !isUpdating else { return }
        Task { await flushQuantityUpdates() }
    }

    /// Sends PATCHes one at a time until the optimistic value matches what the
    /// server last returned, so superseded taps collapse into a single final
    /// request. On the last successful round we clear the local override.
    private func flushQuantityUpdates() async {
        isUpdating = true
        defer { isUpdating = false }

        while let target = pendingQuantity, target != item.quantity {
            await cartVM.updateQuantity(itemID: item.id, quantity: target)

            // Bail out if the request failed: the VM surfaced an error and left
            // the cart unchanged, so reset to the authoritative value rather
            // than spinning forever on a value the server rejected.
            if cartVM.errorMessage != nil {
                pendingQuantity = nil
                return
            }

            // The cart now reflects `target` (or removal). If no newer tap came
            // in while the request was in flight, drop the override.
            if pendingQuantity == target { pendingQuantity = nil }
        }
        pendingQuantity = nil
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
