import SwiftUI

struct OrderDetailView: View {
    let orderID: String
    @StateObject private var vm = OrderViewModel()

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
                                .foregroundColor(.white)
                                .padding()
                                .background(Color.kePrimary)
                                .cornerRadius(Theme.cornerRadiusMedium)
                                .padding(.horizontal)
                            }
                        }

                        // Status tracker
                        statusTracker(order: order)

                        // Restaurant info
                        restaurantHeader(order: order)

                        // Items
                        itemsSection(order: order)

                        // Delivery address
                        deliverySection(order: order)

                        // Price breakdown
                        priceBreakdown(order: order)

                        // Cancel button
                        if order.status == .pending {
                            Button {
                                Task { await vm.cancelOrder(id: order.id) }
                            } label: {
                                Text("Cancel Order")
                            }
                            .buttonStyle(KESecondaryButtonStyle())
                            .padding(.horizontal)
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
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 40))
                        .foregroundColor(.keError)
                    Text(error)
                        .foregroundColor(.keTextSecondary)
                }
            }
        }
        .navigationTitle("Order Details")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task {
            await vm.loadOrder(id: orderID)
            if vm.currentOrder?.status.isActive == true {
                vm.startPolling(orderID: orderID)
            }
        }
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

            if order.status.isActive {
                Text("Estimated delivery: \(order.estDeliveryTime.formatted(date: .omitted, time: .shortened))")
                    .font(.system(size: 14))
                    .foregroundColor(.keTextSecondary)
            }

            // Progress steps
            if order.status.isActive {
                progressSteps(currentStep: order.status.stepIndex)
            }
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusLarge)
        .padding(.horizontal)
    }

    private func progressSteps(currentStep: Int) -> some View {
        let steps = ["Placed", "Accepted", "Preparing", "Ready", "On the Way", "Delivered"]

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
                                .foregroundColor(.white)
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
                Text(order.createdAt.formatted(date: .abbreviated, time: .shortened))
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
    }

    // MARK: - Price Breakdown

    private func priceBreakdown(order: Order) -> some View {
        VStack(spacing: 10) {
            SummaryRow(label: "Subtotal", value: order.subtotalFormatted)
            SummaryRow(label: "Delivery Fee", value: order.deliveryFeeFormatted)
            SummaryRow(label: "Service Fee", value: order.serviceFeeFormatted)
            SummaryRow(label: "Tax", value: order.taxFormatted)

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
