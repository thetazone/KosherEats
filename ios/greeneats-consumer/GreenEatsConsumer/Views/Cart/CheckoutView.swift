import PassKit
import SwiftUI
import StripePaymentSheet

/// CheckoutView is the final step before an order exists. It:
///   1. Loads the user's default delivery address (or presents an "add address" CTA).
///   2. Lets the user pick a tip preset or enter a custom amount.
///   3. Asks the backend to compute authoritative totals + create a Stripe PaymentSheet.
///   4. Presents PaymentSheet; on success, calls createOrder with the payment intent id.
///   5. Deep-links to OrderDetailView for the fresh order.
///
/// Totals are re-fetched from the server any time the tip changes so the
/// client never computes money locally — the backend is the source of truth.
struct CheckoutView: View {
    @EnvironmentObject var cartVM: CartViewModel
    @EnvironmentObject var router: AppRouter
    @StateObject private var vm = CheckoutViewModel()
    @Environment(\.dismiss) var dismiss
    @State private var showAddressPicker = false
    @State private var placedOrder: Order?
    @State private var bundleRefreshTask: Task<Void, Never>?
    // Carries the order id we should deep-link to AFTER the confirmation
    // cover finishes dismissing — prevents the root tab from racing the
    // fullScreenCover dismissal animation.
    @State private var pendingTrackOrderId: String?

    /// Called when the user finishes with confirmation (Done or Track).
    var onOrderPlaced: (Order) -> Void

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(spacing: Theme.spacingLG) {
                    FulfillmentPicker(
                        selected: $vm.fulfillmentType
                    )

                    if vm.fulfillmentType == "delivery" {
                        AddressCard(address: vm.selectedAddress, onChange: { showAddressPicker = true })
                    }

                    DeliveryTimeCard(scheduledFor: $vm.scheduledFor, isPickup: vm.fulfillmentType == "pickup")

                    // No courier on pickup orders → no tip slot.
                    if vm.fulfillmentType == "delivery" {
                        TipSelector(
                            subtotal: vm.bundle?.subtotal ?? 0,
                            selected: vm.tipSelection,
                            customAmount: $vm.customTipText,
                            onSelect: { choice in vm.selectTip(choice) },
                        )
                    }

                    if let bundle = vm.bundle {
                        if let brand = bundle.defaultCardBrand, let last4 = bundle.defaultCardLast4 {
                            SavedCardCard(brand: brand, last4: last4)
                        }
                        ZStack(alignment: .topTrailing) {
                            TotalsCard(bundle: bundle)
                            if vm.isLoadingBundle {
                                ProgressView()
                                    .tint(.kePrimary)
                                    .padding(12)
                            }
                        }
                    } else if vm.isLoadingBundle {
                        ProgressView().tint(.kePrimary).padding()
                    }

                    if let err = vm.errorMessage {
                        Text(err)
                            .font(.footnote)
                            .foregroundColor(.keError)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding()
                .padding(.bottom, 140)
            }

            VStack {
                Spacer()
                payButton
            }
        }
        .navigationTitle("Checkout")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            vm.appliedDealId = cartVM.appliedDeal?.id
            await vm.loadAddresses()
            await vm.refreshBundle()
            if vm.selectedAddress == nil && vm.fulfillmentType == "delivery" {
                showAddressPicker = true
            }
        }
        .sheet(isPresented: $showAddressPicker) {
            AddressPickerSheet(selected: $vm.selectedAddress)
        }
        .onChange(of: vm.tipSelection) { _, _ in
            bundleRefreshTask?.cancel()
            bundleRefreshTask = Task { await vm.refreshBundle() }
        }
        .onChange(of: vm.customTipText) { _, _ in
            if vm.tipSelection == .custom {
                bundleRefreshTask?.cancel()
                bundleRefreshTask = Task { await vm.refreshBundle() }
            }
        }
        .alert("Payment Received — Order Failed", isPresented: $vm.orderCreationFailed) {
            Button("Retry") {
                Task {
                    guard let bundle = vm.bundle, let addr = vm.selectedAddress else { return }
                    if let order = await vm.placeOrder(address: addr, bundle: bundle) {
                        Haptics.success()
                        showAddressPicker = false
                        placedOrder = order
                    } else {
                        Haptics.error()
                        vm.orderCreationFailed = true
                    }
                }
            }
            Button("Contact Support") {
                if let url = URL(string: "mailto:support@greeneats.com") {
                    UIApplication.shared.open(url)
                }
            }
            Button("Dismiss", role: .cancel) {}
        } message: {
            Text("Your payment was received but we couldn't create your order. You have not been double-charged. Please retry or contact support.")
        }
        .fullScreenCover(
            item: $placedOrder,
            onDismiss: {
                // Fires after the cover has fully dismissed, so the
                // destination view in the underlying tab is settled and
                // the tracking push lands cleanly without the old 0.3s
                // asyncAfter dance.
                if let id = pendingTrackOrderId {
                    pendingTrackOrderId = nil
                    router.navigate(.tracking(orderID: id))
                }
            }
        ) { order in
            OrderConfirmationView(
                order: order,
                onDone: {
                    placedOrder = nil
                    Task { await cartVM.loadCart() }
                    onOrderPlaced(order)
                },
                onTrack: {
                    pendingTrackOrderId = order.id
                    placedOrder = nil
                    Task { await cartVM.loadCart() }
                    onOrderPlaced(order)
                }
            )
        }
    }

    // MARK: - Pay button

    private var payButton: some View {
        VStack(spacing: 0) {
            Divider().background(Color.keDivider)
            VStack(spacing: 10) {
                // Express Apple Pay — skips PaymentSheet when the device has
                // a card in Wallet. Reads the same bundle as "Place Order"
                // so totals match what PaymentSheet would have shown.
                if PKPaymentAuthorizationController.canMakePayments() {
                    PayWithApplePayButton(.plain) {
                        Task { await payWithApplePay() }
                    }
                    .payWithApplePayButtonStyle(.white)
                    .frame(height: 50)
                    .cornerRadius(10)
                    .disabled(!canPay)
                    .opacity(canPay ? 1 : 0.5)
                }

                Button {
                    Task { await pay() }
                } label: {
                    HStack {
                        if vm.isProcessing {
                            ProgressView().tint(.keTextOnAccent)
                        } else {
                            Text("Place Order")
                            Spacer()
                            if let bundle = vm.bundle {
                                Text(format(bundle.total))
                            }
                        }
                    }
                }
                .buttonStyle(KEPrimaryButtonStyle(isEnabled: canPay))
                .disabled(!canPay)
            }
            .padding()
            .background(Color.keBackgroundElevated)
        }
    }

    private var canPay: Bool {
        guard vm.bundle != nil, !vm.isProcessing, !vm.isLoadingBundle else { return false }
        // Pickup orders don't need an address; delivery orders do.
        if vm.fulfillmentType == "pickup" { return true }
        return vm.selectedAddress != nil
    }

    private func pay() async {
        guard let bundle = vm.bundle else { return }
        if vm.fulfillmentType == "delivery", vm.selectedAddress == nil { return }
        Haptics.impact(.medium)
        await vm.presentAndChargePaymentSheet(bundle: bundle)
        await finishIfSucceeded(bundle: bundle, address: vm.selectedAddress)
    }

    private func payWithApplePay() async {
        guard let bundle = vm.bundle else { return }
        if vm.fulfillmentType == "delivery", vm.selectedAddress == nil { return }
        Haptics.impact(.medium)
        await vm.presentApplePayExpress(bundle: bundle)
        await finishIfSucceeded(bundle: bundle, address: vm.selectedAddress)
    }

    private func finishIfSucceeded(bundle: APIService.PaymentSheetBundle, address: Address?) async {
        if vm.paymentSucceeded {
            if let order = await vm.placeOrder(address: address, bundle: bundle) {
                Haptics.success()
                showAddressPicker = false
                placedOrder = order
            } else {
                Haptics.error()
                vm.orderCreationFailed = true
            }
        }
    }
}

/// Two-button segment letting the consumer pick delivery (default) vs
/// self-pickup. Triggers a bundle refresh via the VM's didSet so totals
/// update immediately when the user toggles.
private struct FulfillmentPicker: View {
    @Binding var selected: String

    var body: some View {
        HStack(spacing: 0) {
            tile(title: "Delivery", systemImage: "bicycle", value: "delivery")
            tile(title: "Pickup", systemImage: "bag.fill", value: "pickup")
        }
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private func tile(title: String, systemImage: String, value: String) -> some View {
        let isSelected = selected == value
        return Button { selected = value } label: {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                Text(title)
            }
            .font(.subheadline.bold())
            .foregroundColor(isSelected ? .keTextOnAccent : .keTextSecondary)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(isSelected ? Color.kePrimary : Color.clear)
            .cornerRadius(Theme.cornerRadiusMedium)
        }
    }
}

// MARK: - Address card

private struct AddressCard: View {
    let address: Address?
    let onChange: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            HStack {
                Label("Delivering to", systemImage: "house.fill")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Button("Change", action: onChange)
                    .font(.subheadline)
                    .foregroundColor(.kePrimary)
            }
            if let a = address {
                Text(a.formatted)
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
            } else {
                Text("Add a delivery address to continue")
                    .font(.subheadline)
                    .foregroundColor(.keError)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }
}

// MARK: - Delivery time

/// ASAP vs schedule-later picker. A scheduled date >30 minutes in the
/// future puts the order into backend 'scheduled' status — the dispatcher
/// promotes it to 'pending' 30 min before the delivery window so the
/// kitchen has time to prepare.
private struct DeliveryTimeCard: View {
    @Binding var scheduledFor: Date?
    /// When true, header reads "Pickup time" and ASAP subtext reads
    /// "Pickup as soon as possible" — same picker, just relabeled so the
    /// copy matches the consumer's actual fulfillment choice.
    var isPickup: Bool = false
    @State private var showPicker = false
    @State private var draftDate = Date().addingTimeInterval(60 * 60)

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            Text(isPickup ? "Pickup time" : "Delivery time")
                .font(.headline)
                .foregroundColor(.keTextPrimary)

            HStack(spacing: 8) {
                timeOption(
                    title: "ASAP",
                    subtitle: isPickup ? "Pickup as soon as possible" : "Deliver as soon as possible",
                    isSelected: scheduledFor == nil,
                    action: { scheduledFor = nil },
                )
                timeOption(
                    title: "Schedule",
                    subtitle: scheduledFor.map { formatted($0) } ?? "Pick a time",
                    isSelected: scheduledFor != nil,
                    action: {
                        draftDate = Date().addingTimeInterval(3600)
                        showPicker = true
                    },
                )
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .sheet(isPresented: $showPicker) {
            NavigationStack {
                VStack {
                    DatePicker(
                        isPickup ? "Pickup at" : "Deliver at",
                        selection: Binding(
                            get: { scheduledFor ?? draftDate },
                            set: { scheduledFor = $0; draftDate = $0 },
                        ),
                        in: Date().addingTimeInterval(45 * 60)...Date().addingTimeInterval(7 * 24 * 3600),
                        displayedComponents: [.date, .hourAndMinute],
                    )
                    .datePickerStyle(.wheel)
                    .labelsHidden()
                    Spacer()
                }
                .padding()
                .background(Color.keBackground)
                .navigationTitle(isPickup ? "Pickup time" : "Delivery time")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { showPicker = false }
                            .foregroundColor(.kePrimary)
                    }
                    ToolbarItem(placement: .cancellationAction) {
                        Button("ASAP instead") {
                            scheduledFor = nil
                            showPicker = false
                        }
                        .foregroundColor(.keTextMuted)
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }

    private func timeOption(title: String, subtitle: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.bold())
                    .foregroundColor(isSelected ? .white : .keTextSecondary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(isSelected ? .white.opacity(0.9) : .keTextTertiary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 10)
            .padding(.horizontal, 12)
            .background(isSelected ? Color.kePrimary : Color.keBackgroundElevated)
            .cornerRadius(10)
        }
    }

    private func formatted(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: date)
    }
}

// MARK: - Tip selector

private struct TipSelector: View {
    let subtotal: Int
    let selected: CheckoutViewModel.TipChoice
    @Binding var customAmount: String
    let onSelect: (CheckoutViewModel.TipChoice) -> Void
    @FocusState private var customFieldFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            Text("Tip your driver")
                .font(.headline)
                .foregroundColor(.keTextPrimary)
            Text("100% of the tip goes to your courier.")
                .font(.caption)
                .foregroundColor(.keTextTertiary)

            HStack(spacing: 8) {
                ForEach(CheckoutViewModel.TipChoice.presets, id: \.self) { choice in
                    TipChip(
                        label: choice.label(for: subtotal),
                        isSelected: selected == choice,
                        action: { onSelect(choice) },
                    )
                }
            }

            if selected == .custom {
                TextField("Custom amount", text: $customAmount)
                    .keyboardType(.decimalPad)
                    .focused($customFieldFocused)
                    .keTextField()
                    .toolbar {
                        // .decimalPad has no return key, so users used to get
                        // stuck — tapping outside was the only way out. Add a
                        // Done bar above the keyboard to dismiss cleanly.
                        ToolbarItemGroup(placement: .keyboard) {
                            Spacer()
                            Button("Done") { customFieldFocused = false }
                                .foregroundColor(.kePrimary)
                        }
                    }
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }
}

private struct TipChip: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline.bold())
                .foregroundColor(isSelected ? .white : .keTextSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(isSelected ? Color.kePrimary : Color.keBackgroundElevated)
                )
        }
        .accessibilityLabel("Tip \(label)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: - Saved card preview

/// Shows the user's default card on file ("Visa •••• 4242") so they know
/// which payment method PaymentSheet will land on by default. Tapping the
/// full PaymentSheet still lets them switch or add a new one; this is a
/// visual affordance so Apple's reviewer + return customers don't have to
/// open the sheet to verify.
private struct SavedCardCard: View {
    let brand: String
    let last4: String

    var body: some View {
        HStack(spacing: Theme.spacingSM) {
            Image(systemName: "creditcard.fill")
                .foregroundColor(.kePrimary)
            VStack(alignment: .leading, spacing: 2) {
                Text("Paying with saved card")
                    .font(.caption)
                    .foregroundColor(.keTextTertiary)
                Text("\(brand.capitalized) •••• \(last4)")
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)
            }
            Spacer()
            Text("Change at checkout")
                .font(.caption2)
                .foregroundColor(.keTextMuted)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }
}

// MARK: - Totals card

private struct TotalsCard: View {
    let bundle: APIService.PaymentSheetBundle

    var body: some View {
        VStack(spacing: Theme.spacingSM) {
            totalsRow("Subtotal", bundle.subtotal)
            if let discount = bundle.discount, discount > 0 {
                HStack {
                    HStack(spacing: 4) {
                        Image(systemName: "tag.fill")
                            .font(.system(size: 11))
                        Text("Deal discount")
                            .font(.subheadline)
                    }
                    .foregroundColor(.keSuccess)
                    Spacer()
                    Text("-\(format(discount))")
                        .font(.subheadline.bold())
                        .foregroundColor(.keSuccess)
                }
            }
            totalsRow("Tax", bundle.tax)
            totalsRow("Service fee", bundle.serviceFee)
            if bundle.deliveryFee > 0 {
                totalsRow("Delivery fee", bundle.deliveryFee)
            }
            if bundle.tip > 0 {
                totalsRow("Driver tip", bundle.tip)
            }
            Divider().background(Color.keDivider)
            HStack {
                Text("Total")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Text(format(bundle.total))
                    .font(.headline)
                    .foregroundColor(.kePrimary)
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private func totalsRow(_ label: String, _ cents: Int) -> some View {
        HStack {
            Text(label).font(.subheadline).foregroundColor(.keTextSecondary)
            Spacer()
            Text(format(cents)).font(.subheadline).foregroundColor(.keTextPrimary)
        }
    }
}

// MARK: - Helpers

private func format(_ cents: Int) -> String {
    String(format: "$%.2f", Double(cents) / 100)
}
