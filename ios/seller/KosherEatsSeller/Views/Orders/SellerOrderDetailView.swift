import SwiftUI

private let iconTouchTarget: CGFloat = 44

struct SellerOrderDetailView: View {
    @ObservedObject var vm: OrdersViewModel
    @Environment(\.dismiss) private var dismiss
    private let orderID: String
    @State private var order: Order?
    @State private var showRejectAlert = false
    @State private var rejectReason = ""
    @State private var isActing = false

    init(vm: OrdersViewModel, order: Order) {
        self._vm = ObservedObject(wrappedValue: vm)
        self.orderID = order.id
        self._order = State(initialValue: order)
    }

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            if let order = order {
                ScrollView {
                    VStack(spacing: 20) {
                        statusHeader(order)
                        itemsSection(order)
                        deliverySection(order)
                        if let name = order.customerName, !name.isEmpty {
                            customerSection(name: name, phone: order.customerPhone)
                        }
                        priceSection(order)
                        actionButtons(order)
                    }
                    .padding()
                    .adaptiveContentWidth(720)
                }
            } else if vm.isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(spacing: 16) {
                    Image(systemName: "arrow.clockwise.circle")
                        .font(.largeTitle)
                        .foregroundColor(.keTextMuted)
                    Text("Order data unavailable")
                        .font(.headline)
                        .foregroundColor(.keTextSecondary)
                    Button("Retry") {
                        Task { await vm.fetchOrder(id: orderID) }
                    }
                    .font(.subheadline.bold())
                    .foregroundColor(.kePrimary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        // Intentional: prefix(8) keeps the nav title short while still being
        // unique enough to identify the order at a glance.
        .navigationTitle("Order #\(String(orderID.prefix(8)))")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .alert("Reject Order", isPresented: $showRejectAlert) {
            TextField("Reason (optional)", text: $rejectReason)
            Button("Cancel", role: .cancel) { }
            Button("Reject", role: .destructive) {
                Task {
                    vm.errorMessage = nil
                    await vm.rejectOrder(
                        id: orderID,
                        reason: rejectReason.isEmpty ? nil : rejectReason
                    )
                    // Pull the post-mutation state through the same race-safe
                    // helper Accept/Mark Ready use, then either dismiss (on
                    // real success — local order moved to .rejected) or stay
                    // on the detail screen so the error toast is visible. The
                    // previous code dismissed whenever vm.errorMessage was
                    // nil, but a silent guard-no-op also leaves errorMessage
                    // nil — so the user got dismissed back to the list with
                    // the order still pending and no signal that anything
                    // failed.
                    await syncOrderFromVM()
                    if order?.status == .rejected || order?.status == .cancelled {
                        dismiss()
                    }
                }
            }
        } message: {
            Text("Are you sure you want to reject this order?")
        }
        .overlay {
            if let msg = vm.successMessage {
                successToast(msg)
            }
        }
        // Surface the VM's errorMessage as an alert so a failed reject (or
        // any other action) doesn't disappear silently — previously errors
        // were assigned to vm.errorMessage but the detail view never bound
        // them to UI, so the user got no feedback when e.g. a Stripe refund
        // 502'd during reject. The alert clears the message on dismiss so
        // the next action starts clean.
        .alert("Action failed",
               isPresented: Binding(
                get: { vm.errorMessage != nil },
                set: { if !$0 { vm.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(vm.errorMessage ?? "")
        }
        .task {
            if order == nil {
                await vm.fetchOrder(id: orderID)
                order = vm.orders.first(where: { $0.id == orderID })
            }
        }
        .onReceive(vm.$orders) { updated in
            if let fresh = updated.first(where: { $0.id == orderID }) {
                order = fresh
            }
        }
    }

    /// After mutating an order via the VM (accept / reject / start preparing /
    /// mark ready / etc.), prefer reading the freshly-updated copy out of
    /// vm.orders. If the in-memory list happens to have churned (an active-vs-
    /// past filter swept the order out, or a poll arrived mid-transition),
    /// fall back to a direct fetch instead of blanking `self.order` to nil —
    /// which would drop the user into the "Order data unavailable" empty
    /// state right after a successful action and force a manual Retry.
    private func syncOrderFromVM() async {
        if let fresh = vm.orders.first(where: { $0.id == orderID }) {
            order = fresh
            return
        }
        await vm.fetchOrder(id: orderID)
        if let fresh = vm.orders.first(where: { $0.id == orderID }) {
            order = fresh
        }
    }

    // MARK: - Status Header

    private func statusHeader(_ order: Order) -> some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(statusColor(for: order).opacity(0.15))
                    .frame(width: 64, height: 64)

                Image(systemName: order.status.icon)
                    .font(.title.bold())
                    .foregroundColor(statusColor(for: order))
            }

            Text(order.status.displayName)
                .font(.title3.bold())
                .foregroundColor(statusColor(for: order))

            // Pickup vs delivery badge so the seller sees at a glance
            // whether to expect a courier (delivery) or a customer at the
            // counter (pickup). Lives next to the status so the two read as
            // a unit.
            HStack(spacing: 6) {
                Image(systemName: order.isPickup ? "bag.fill" : "bicycle")
                    .font(.caption.bold())
                Text(order.isPickup ? "PICKUP" : "DELIVERY")
                    .font(.caption.bold())
            }
            .foregroundColor(.kePrimary)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Color.kePrimary.opacity(0.15))
            .cornerRadius(6)

            Text("Placed \(order.formattedDate)")
                .font(.caption)
                .foregroundColor(.keTextMuted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .background(Color.keCard)
        .cornerRadius(16)
    }

    // MARK: - Items

    private func itemsSection(_ order: Order) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("Order Items", icon: "bag.fill")

            VStack(spacing: 0) {
                ForEach(Array(order.items.enumerated()), id: \.element.id) { index, item in
                    HStack {
                        Text("\(item.quantity)x")
                            .font(.subheadline.bold())
                            .foregroundColor(.kePrimary)
                            .frame(width: 32, alignment: .leading)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                                .font(.subheadline)
                                .foregroundColor(.keTextPrimary)

                            if let mods = item.modifierSummary {
                                Text(mods)
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                            }

                            if let notes = item.notes, !notes.isEmpty {
                                Text(notes)
                                    .font(.caption)
                                    .foregroundColor(.keTextMuted)
                                    .italic()
                            }
                        }

                        Spacer()

                        Text(item.lineTotalFormatted)
                            .font(.subheadline)
                            .foregroundColor(.keTextSecondary)
                    }
                    .padding(.vertical, 10)
                    .padding(.horizontal, 16)

                    if index < order.items.count - 1 {
                        Divider()
                            .background(Color.keBorder)
                            .padding(.horizontal, 16)
                    }
                }
            }
            .background(Color.keCard)
            .cornerRadius(14)
        }
    }

    // MARK: - Delivery

    private func deliverySection(_ order: Order) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("Delivery", icon: "location.fill")

            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Image(systemName: "mappin.circle.fill")
                        .foregroundColor(.kePrimary)

                    Text(order.deliveryAddress)
                        .font(.subheadline)
                        .foregroundColor(.keTextPrimary)
                }

                if let eta = order.estDeliveryTime {
                    HStack(spacing: 10) {
                        Image(systemName: "clock")
                            .foregroundColor(.keTextMuted)

                        Text("ETA: \(eta)")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                    }
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.keCard)
            .cornerRadius(14)
        }
    }

    // MARK: - Customer

    private func customerSection(name: String, phone: String?) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("Customer", icon: "person.fill")

            HStack(spacing: 12) {
                Circle()
                    .fill(Color.keBorder)
                    .frame(width: iconTouchTarget, height: iconTouchTarget)
                    .overlay(
                        Text(String(name.prefix(1)).uppercased())
                            .font(.headline)
                            .foregroundColor(.kePrimary)
                    )
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextPrimary)
                    if let phone, !phone.isEmpty {
                        Text(phone)
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                    }
                }
                Spacer()
                if let phone, !phone.isEmpty {
                    let cleaned = phone.filter { $0.isNumber || $0 == "+" }
                    if let url = URL(string: "tel:\(cleaned)") {
                        Link(destination: url) {
                            Image(systemName: "phone.fill")
                                .foregroundColor(.kePrimary)
                                .padding(10)
                                .background(Color.keBorder)
                                .clipShape(Circle())
                        }
                    }
                }
            }
            .padding()
            .background(Color.keCard)
            .cornerRadius(14)
        }
    }

    // MARK: - Price

    private func priceSection(_ order: Order) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("Payment", icon: "creditcard.fill")

            VStack(spacing: 8) {
                priceRow("Subtotal", value: order.subtotal)
                priceRow("Delivery Fee", value: order.deliveryFee)
                priceRow("Service Fee", value: order.serviceFee)
                priceRow("Tax", value: order.tax)
                if let tip = order.courierTip, tip > 0 {
                    priceRow("Courier Tip", value: tip)
                }

                Divider()
                    .background(Color.keBorder)

                HStack {
                    Text("Total")
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                    Spacer()
                    Text(order.totalFormatted)
                        .font(.headline)
                        .foregroundColor(.kePrimary)
                }
            }
            .padding()
            .background(Color.keCard)
            .cornerRadius(14)
        }
    }

    // MARK: - Action Buttons

    @ViewBuilder
    private func actionButtons(_ order: Order) -> some View {
        switch order.status {
        case .pending:
            VStack(spacing: 10) {
                actionButton("Accept Order", icon: "checkmark.circle.fill", color: .keSuccess) {
                    guard !isActing else { return }
                    isActing = true
                    Task {
                        vm.errorMessage = nil
                        await vm.acceptOrder(id: order.id)
                        await syncOrderFromVM()
                        isActing = false
                    }
                }
                .disabled(isActing)

                actionButton("Reject Order", icon: "xmark.circle.fill", color: .keError) {
                    Haptics.warning()
                    showRejectAlert = true
                }
            }

        case .accepted:
            actionButton("Start Preparing", icon: "flame.fill", color: .kePrimary) {
                guard !isActing else { return }
                isActing = true
                Task {
                    vm.errorMessage = nil
                    await vm.markPreparing(id: order.id)
                    await syncOrderFromVM()
                    isActing = false
                }
            }
            .disabled(isActing)

        case .preparing:
            actionButton("Mark as Ready for Pickup", icon: "bag.fill", color: .keSuccess) {
                guard !isActing else { return }
                isActing = true
                Task {
                    vm.errorMessage = nil
                    await vm.markReady(id: order.id)
                    await syncOrderFromVM()
                    isActing = false
                }
            }
            .disabled(isActing)

        case .ready, .pickedUp:
            if order.isPickup && order.status == .ready {
                // Pickup orders never get a courier — the seller marks them
                // completed when the customer arrives. Backend's CompleteOrder
                // handler enforces the same status='ready' guard.
                pickupReadyCard(order)
            } else {
                // Courier now owns the handoff. Show who's handling delivery
                // instead of an action — same UX pattern as the UberEats merchant app.
                courierStatusCard(order)
            }

        case .delivered, .cancelled, .rejected, .scheduled, .unknown:
            HStack(spacing: 12) {
                Image(systemName: order.status.icon)
                    .font(.title3)
                    .foregroundColor(.keTextSecondary)
                Text(order.status.displayName)
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .foregroundColor(.keTextSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding()
            .background(Color.keCard)
            .cornerRadius(14)
        case .completed:
            EmptyView()
        }
    }

    /// Replaces the courier card on pickup-fulfillment orders that are
    /// ready. The "Mark Picked Up" button drives status ready→completed
    /// via the existing CompleteOrder backend handler, which is the
    /// terminal step for pickup orders (no courier picked_up→delivered).
    @ViewBuilder
    private func pickupReadyCard(_ order: Order) -> some View {
        VStack(spacing: 14) {
            VStack(spacing: 6) {
                Image(systemName: "bag.fill")
                    .font(.system(size: 36))
                    .foregroundColor(.kePrimary)
                Text("Customer is picking up")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                if let name = order.customerName, !name.isEmpty {
                    Text(name)
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding()
            .background(Color.keCard)
            .cornerRadius(14)

            actionButton("Mark Picked Up", icon: "checkmark.circle.fill", color: .keSuccess) {
                guard !isActing else { return }
                isActing = true
                Task {
                    vm.errorMessage = nil
                    await vm.markCompleted(id: order.id)
                    await syncOrderFromVM()
                    isActing = false
                }
            }
            .disabled(isActing)
        }
    }

    @ViewBuilder
    private func courierStatusCard(_ order: Order) -> some View {
        if let courier = order.courier {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 6) {
                    Image(systemName: order.status == .pickedUp ? "car.fill" : "figure.walk.motion")
                        .foregroundColor(.kePrimary)
                    Text(order.status == .pickedUp ? "Courier is delivering" : "Courier on the way to pick up")
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                }

                HStack(spacing: 12) {
                    Circle()
                        .fill(Color.keBorder)
                        .frame(width: iconTouchTarget, height: iconTouchTarget)
                        .overlay(
                            Text(String(courier.firstName.prefix(1)))
                                .font(.headline)
                                .foregroundColor(.kePrimary)
                        )
                    VStack(alignment: .leading, spacing: 2) {
                        Text(courier.firstName)
                            .foregroundColor(.keTextPrimary)
                        HStack(spacing: 4) {
                            Image(systemName: "star.fill").font(.caption2).foregroundColor(.keWarning)
                            Text(String(format: "%.1f", courier.rating))
                                .font(.caption)
                                .foregroundColor(.keTextSecondary)
                            Text("• \(courier.totalDeliveries) deliveries")
                                .font(.caption)
                                .foregroundColor(.keTextMuted)
                        }
                        Text(courier.vehicleSummary)
                            .font(.caption)
                            .foregroundColor(.keTextMuted)
                    }
                    Spacer()
                    let cleanedCourierPhone = courier.phone.filter { $0.isNumber || $0 == "+" }
                    if let url = URL(string: "tel:\(cleanedCourierPhone)") {
                        Link(destination: url) {
                            Image(systemName: "phone.fill")
                                .foregroundColor(.kePrimary)
                                .padding(10)
                                .background(Color.keBorder)
                                .clipShape(Circle())
                        }
                    }
                }
            }
            .padding()
            .background(Color.keCard)
            .cornerRadius(14)
        } else {
            HStack(spacing: 10) {
                ProgressView().tint(.kePrimary)
                Text("Waiting for a courier to claim this order…")
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding()
            .background(Color.keCard)
            .cornerRadius(14)
        }
    }

    // MARK: - Helpers

    private func sectionHeader(_ title: String, icon: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
                .foregroundColor(.kePrimary)
            Text(title)
                .font(.headline)
                .foregroundColor(.keTextPrimary)
        }
    }

    private func priceRow(_ label: String, value: Int) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
            Spacer()
            Text(CurrencyFormat.string(fromCents: value))
                .font(.subheadline)
                .foregroundColor(.keTextPrimary)
        }
    }

    private func actionButton(
        _ title: String,
        icon: String,
        color: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                Text(title)
                    .font(.headline)
            }
            .foregroundColor(.keTextOnAccent)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(color)
            .cornerRadius(14)
        }
    }

    private func successToast(_ message: String) -> some View {
        VStack {
            Spacer()
            Text(message)
                .font(.subheadline.bold())
                .foregroundColor(.keTextOnAccent)
                .padding()
                .background(Color.keSuccess)
                .cornerRadius(12)
                .padding(.bottom, 20)
        }
        .transition(.move(edge: .bottom))
        .animation(.easeInOut, value: vm.successMessage)
    }

    private func statusColor(for order: Order) -> Color {
        order.status.resolvedColor
    }
}
