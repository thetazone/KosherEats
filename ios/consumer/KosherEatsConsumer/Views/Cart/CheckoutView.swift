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
    @StateObject private var vm = CheckoutViewModel()
    @Environment(\.dismiss) var dismiss
    @State private var showAddressPicker = false

    /// Called when the order is placed so the parent can dismiss the cart +
    /// navigate to the orders tab. Parent supplies the completion.
    var onOrderPlaced: (Order) -> Void

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(spacing: Theme.spacingLG) {
                    AddressCard(address: vm.selectedAddress, onChange: { showAddressPicker = true })

                    DeliveryTimeCard(scheduledFor: $vm.scheduledFor)

                    TipSelector(
                        subtotal: vm.bundle?.subtotal ?? 0,
                        selected: vm.tipSelection,
                        customAmount: $vm.customTipText,
                        onSelect: { choice in vm.selectTip(choice) },
                    )

                    if let bundle = vm.bundle {
                        TotalsCard(bundle: bundle)
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
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task {
            await vm.loadAddresses()
            await vm.refreshBundle()
            // If the user has no saved address, immediately open the picker.
            if vm.selectedAddress == nil {
                showAddressPicker = true
            }
        }
        .sheet(isPresented: $showAddressPicker) {
            AddressPickerSheet(selected: $vm.selectedAddress)
        }
        .onChange(of: vm.tipSelection) { _, _ in
            Task { await vm.refreshBundle() }
        }
        .onChange(of: vm.customTipText) { _, newValue in
            if vm.tipSelection == .custom {
                Task { await vm.refreshBundle() }
            }
        }
    }

    // MARK: - Pay button

    private var payButton: some View {
        VStack(spacing: 0) {
            Divider().background(Color.keDivider)
            Button {
                Task { await pay() }
            } label: {
                HStack {
                    if vm.isProcessing {
                        ProgressView().tint(.white)
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
            .padding()
            .background(Color.keBackgroundElevated)
        }
    }

    private var canPay: Bool {
        vm.bundle != nil && vm.selectedAddress != nil && !vm.isProcessing
    }

    private func pay() async {
        guard let bundle = vm.bundle, let address = vm.selectedAddress else { return }
        Haptics.impact(.medium)
        await vm.presentAndChargePaymentSheet(bundle: bundle)
        if vm.paymentSucceeded {
            if let order = await vm.placeOrder(address: address, bundle: bundle) {
                await cartVM.loadCart() // cart cleared server-side
                Haptics.success()
                onOrderPlaced(order)
            } else {
                Haptics.error()
            }
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
    @State private var showPicker = false
    @State private var draftDate = Date().addingTimeInterval(60 * 60)

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            Text("Delivery time")
                .font(.headline)
                .foregroundColor(.keTextPrimary)

            HStack(spacing: 8) {
                timeOption(
                    title: "ASAP",
                    subtitle: "Deliver as soon as possible",
                    isSelected: scheduledFor == nil,
                    action: { scheduledFor = nil },
                )
                timeOption(
                    title: "Schedule",
                    subtitle: scheduledFor != nil ? formatted(scheduledFor!) : "Pick a time",
                    isSelected: scheduledFor != nil,
                    action: {
                        showPicker = true
                        if scheduledFor == nil {
                            scheduledFor = draftDate
                        }
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
                        "Deliver at",
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
                .navigationTitle("Delivery time")
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
                    .keTextField()
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
    }
}

// MARK: - Totals card

private struct TotalsCard: View {
    let bundle: APIService.PaymentSheetBundle

    var body: some View {
        VStack(spacing: Theme.spacingSM) {
            totalsRow("Subtotal", bundle.subtotal)
            totalsRow("Tax", bundle.tax)
            totalsRow("Service fee", bundle.serviceFee)
            totalsRow("Delivery fee", bundle.deliveryFee)
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
