import SwiftUI

struct OrderDetailView: View {
    let orderID: String
    @StateObject private var vm = OrderViewModel()
    /// Gates the destructive cancel behind a confirmation dialog so a single
    /// accidental tap can't irreversibly cancel a paid order.
    @State private var showCancelConfirmation = false

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            if vm.isLoading && vm.currentOrder == nil {
                ProgressView()
                    .tint(.kePrimary)
            } else if let order = vm.currentOrder {
                ScrollView(showsIndicators: false) {
                    VStack(spacing: Theme.spacingLG) {
                        // Track order (live map) — shown for any active post-accept state
                        if order.status.isActive && order.status != .pending {
                            NavigationLink(destination: OrderTrackingView(orderId: order.id)) {
                                HStack {
                                    Image(systemName: "location.circle.fill")
                                    Text("Track your order")
                                        .font(.headline)
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                }
                                .foregroundColor(.keTextOnAccent)
                                .padding()
                                .background(Color.kePrimary)
                                .cornerRadius(Theme.cornerRadiusMedium)
                                .padding(.horizontal)
                            }
                            .simultaneousGesture(TapGesture().onEnded { Haptics.impact(.light) })
                        }

                        // Status tracker
                        statusTracker(order: order)

                        if order.status == .rejected {
                            VStack(spacing: 8) {
                                Text("Restaurant couldn't take this order")
                                    .font(.system(size: 16, weight: .bold))
                                    .foregroundColor(.keTextPrimary)
                                Text("Your payment has been refunded to your original payment method.")
                                    .font(.system(size: 14))
                                    .foregroundColor(.keTextSecondary)
                                    .multilineTextAlignment(.center)
                            }
                            .padding()
                            .frame(maxWidth: .infinity)
                            .background(Color.keError.opacity(0.1))
                            .cornerRadius(Theme.cornerRadiusMedium)
                            .padding(.horizontal)
                        }

                        // Restaurant info
                        restaurantHeader(order: order)

                        // Items
                        itemsSection(order: order)

                        // Delivery address (hidden for pickup orders)
                        if order.fulfillmentType != "pickup" {
                            deliverySection(order: order)
                        }

                        // Price breakdown
                        priceBreakdown(order: order)

                        // Cancel button. `.scheduled` is included so a customer
                        // who booked dinner in advance can cancel and be refunded
                        // in-app. NOTE: the backend CancelOrder whitelist
                        // (orders.go: status IN (pending, accepted)) currently does
                        // NOT include models.OrderScheduled, so a scheduled-order
                        // cancel returns 400 until that companion change lands —
                        // see backendFollowups. Until then the failure surfaces
                        // gracefully via the error alert below rather than silently
                        // hiding the action.
                        if order.status == .scheduled || order.status == .pending || order.status == .accepted {
                            Button(role: .destructive) {
                                showCancelConfirmation = true
                            } label: {
                                if vm.isCancelling {
                                    ProgressView()
                                        .frame(maxWidth: .infinity)
                                } else {
                                    Text("Cancel Order")
                                }
                            }
                            .buttonStyle(KESecondaryButtonStyle())
                            .disabled(vm.isCancelling)
                            .padding(.horizontal)
                            .confirmationDialog(
                                String(localized: "Cancel this order?"),
                                isPresented: $showCancelConfirmation,
                                titleVisibility: .visible
                            ) {
                                Button(String(localized: "Cancel Order"), role: .destructive) {
                                    Haptics.impact(.medium)
                                    Task { await vm.cancelOrder(id: order.id) }
                                }
                                Button(String(localized: "Keep Order"), role: .cancel) {}
                            } message: {
                                Text(String(localized: "This can't be undone. You'll be refunded to your original payment method."))
                            }
                        }

                        // Order ID
                        Text("Order #\(order.id.prefix(8))")
                            .font(.system(size: 12))
                            .foregroundColor(.keTextMuted)
                            .padding(.bottom, Theme.spacingLG)
                    }
                    .padding(.top)
                }
            } else if let error = vm.errorMessage {
                ErrorStateView(message: error) {
                    Task { await vm.loadOrder(id: orderID) }
                }
            }
        }
        .navigationTitle("Order Details")
        .navigationBarTitleDisplayMode(.inline)
        .alert(
            String(localized: "Couldn't cancel order"),
            isPresented: Binding(
                get: { vm.cancelError != nil },
                set: { if !$0 { vm.cancelError = nil } }
            ),
            presenting: vm.cancelError
        ) { _ in
            Button(String(localized: "OK"), role: .cancel) { vm.cancelError = nil }
        } message: { message in
            Text(message)
        }
        .task {
            await vm.loadOrder(id: orderID)
            if vm.currentOrder?.status.isActive == true {
                vm.startPolling(orderID: orderID)
            }
        }
        .onDisappear { vm.stopPolling() }
    }

    // MARK: - Status Tracker

    private func statusTracker(order: Order) -> some View {
        VStack(spacing: 16) {
            // Status icon
            ZStack {
                Circle()
                    .fill(statusColor(order.status).opacity(0.15))
                    .frame(width: 80, height: 80)
                Image(systemName: order.status.iconName)
                    .font(.system(size: 32))
                    .foregroundColor(statusColor(order.status))
            }

            Text(order.status.displayName)
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.keTextPrimary)

            if order.status.isActive && order.status != .cancelled && order.status != .rejected {
                Text("Estimated \(order.fulfillmentType == "pickup" ? "pickup" : "delivery"): \(order.estDeliveryTime.formatted(date: .omitted, time: .shortened))")
                    .font(.system(size: 14))
                    .foregroundColor(.keTextSecondary)
            }

            // Progress steps
            if order.status.isActive && order.status != .cancelled && order.status != .rejected {
                progressSteps(currentStep: order.status.stepIndex, fulfillmentType: order.fulfillmentType ?? "delivery")
            }
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusLarge)
        .padding(.horizontal)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(order.status.stepIndex >= 0
            ? "Order status: \(order.status.displayName), step \(order.status.stepIndex + 1) of 6"
            : "Order status: \(order.status.displayName)")
    }

    private func progressSteps(currentStep: Int, fulfillmentType: String = "delivery") -> some View {
        let isPickup = fulfillmentType == "pickup"
        let steps = ["Placed", "Accepted", "Preparing", "Ready", isPickup ? "Ready for Pickup" : "On the Way", isPickup ? "Picked Up" : "Delivered"]

        return HStack(spacing: 0) {
            ForEach(0..<steps.count, id: \.self) { index in
                VStack(spacing: 4) {
                    ZStack {
                        Circle()
                            .fill(index <= currentStep ? Color.kePrimary : Color.keCardHover)
                            .frame(width: 12, height: 12)

                        if index < currentStep {
                            Image(systemName: "checkmark")
                                .font(.system(size: 7, weight: .bold))
                                .foregroundColor(.keTextOnAccent)
                        } else if index == currentStep {
                            Circle()
                                .fill(Color.white)
                                .frame(width: 5, height: 5)
                        }
                    }
                    Text(steps[index])
                        .font(.system(size: 9))
                        .foregroundColor(index <= currentStep ? .kePrimary : .keTextMuted)
                }
                .frame(maxWidth: .infinity)

                if index < steps.count - 1 {
                    Rectangle()
                        .fill(index < currentStep ? Color.kePrimary : Color.keCardHover)
                        .frame(height: 2)
                        .offset(y: -6)
                }
            }
        }
        .padding(.horizontal, 8)
    }

    private func statusColor(_ status: OrderStatus) -> Color {
        switch status {
        case .scheduled, .pending: return .keWarning
        case .accepted, .preparing: return .kePrimary
        case .ready, .pickedUp: return .keDairy
        case .delivered: return .keSuccess
        case .cancelled, .rejected: return .keError
        case .completed:
            return .keSuccess
        @unknown default:
            return .keTextSecondary
        }
    }

    // MARK: - Restaurant Header

    private func restaurantHeader(order: Order) -> some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(Color.keCardHover)
                    .frame(width: 48, height: 48)
                Image(systemName: "fork.knife")
                    .foregroundColor(.kePrimary)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(order.restaurantName)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                Text(order.createdAt.formatted(date: .long, time: .shortened))
                    .font(.system(size: 13))
                    .foregroundColor(.keTextMuted)
            }

            Spacer()
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .padding(.horizontal)
    }

    // MARK: - Items

    private func itemsSection(order: Order) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Items")
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(.keTextPrimary)

            ForEach(order.items) { item in
                HStack {
                    Text("\(item.quantity)x")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(.kePrimary)
                        .frame(width: 30, alignment: .leading)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(.keTextPrimary)
                        if let notes = item.notes, !notes.isEmpty {
                            Text(notes)
                                .font(.system(size: 12))
                                .foregroundColor(.keTextMuted)
                                .italic()
                        }
                    }

                    Spacer()

                    Text(item.totalFormatted)
                        .font(.system(size: 15))
                        .foregroundColor(.keTextSecondary)
                }
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .padding(.horizontal)
    }

    // MARK: - Delivery

    private func deliverySection(order: Order) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "mappin.circle.fill")
                .font(.system(size: 24))
                .foregroundColor(.kePrimary)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text("Delivery Address")
                    .font(.system(size: 13))
                    .foregroundColor(.keTextMuted)
                Text(order.deliveryAddress)
                    .font(.system(size: 15))
                    .foregroundColor(.keTextPrimary)
            }

            Spacer()
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .padding(.horizontal)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Delivery address: \(order.deliveryAddress)")
    }

    // MARK: - Price Breakdown

    private func priceBreakdown(order: Order) -> some View {
        VStack(spacing: 10) {
            SummaryRow(label: "Subtotal", value: order.subtotalFormatted)
            SummaryRow(label: "Delivery Fee", value: order.deliveryFeeFormatted)
            SummaryRow(label: "Service Fee", value: order.serviceFeeFormatted)
            SummaryRow(label: "Tax", value: order.taxFormatted)
            if let tip = order.courierTip, tip > 0 {
                SummaryRow(label: "Driver Tip", value: "$\(String(format: "%.2f", Double(tip) / 100))")
            }

            Divider().background(Color.keDivider)

            HStack {
                Text("Total")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Text(order.totalFormatted)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(.kePrimary)
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .padding(.horizontal)
    }
}
