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
    @State private var escalateMessage: String?
    @State private var showDispatchConfirm = false
    @State private var isDispatching = false

    init(vm: OrdersViewModel, order: Order) {
        self._vm = ObservedObject(wrappedValue: vm)
        self.orderID = order.id
        self._order = State(initialValue: order)
    }

    /// Resolve a detail screen from just an order id — used by the push
    /// deep link when the order isn't in `vm.orders` yet (cold launch from a
    /// notification tap). `order` starts nil; the `.task` below fetches by id,
    /// and `.onReceive(vm.$orders)` fills it once the list loads.
    init(vm: OrdersViewModel, orderID: String) {
        self._vm = ObservedObject(wrappedValue: vm)
        self.orderID = orderID
        self._order = State(initialValue: nil)
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
        .alert("Uber dispatch",
               isPresented: Binding(
                get: { escalateMessage != nil },
                set: { if !$0 { escalateMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(escalateMessage ?? "")
        }
        .task {
            // Always fetch the authoritative full order. The seeded copy comes
            // from the list (ListSellerOrders), which doesn't carry `courier` or
            // `external_delivery_id` — so the courier card and escalate gating
            // would render off incomplete data until some later refresh. The list
            // copy stays only as an instant-render placeholder.
            await vm.fetchOrder(id: orderID)
            if let fresh = vm.orders.first(where: { $0.id == orderID }) {
                order = fresh
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

    @ViewBuilder
    private func deliverySection(_ order: Order) -> some View {
        if order.isPickup {
            // Pickup orders never get a courier and the customer's home address
            // (which the backend stores in delivery_address for pickups too)
            // is not a delivery destination — so present a "Pickup" card rather
            // than a misleading "Delivery" header with a map pin.
            VStack(alignment: .leading, spacing: 12) {
                sectionHeader("Pickup", icon: "bag.fill")

                HStack(spacing: 10) {
                    Image(systemName: "storefront.fill")
                        .foregroundColor(.kePrimary)

                    Text("Customer collects at the counter")
                        .font(.subheadline)
                        .foregroundColor(.keTextPrimary)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.keCard)
                .cornerRadius(14)
            }
        } else {
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

                    if let eta = order.estDeliveryTimeFormatted {
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
                // The backend masks the customer phone to the last 4 digits
                // (e.g. "*******1234"), so a tel: link built from it would dial
                // a useless number. Only offer the call button on an unmasked
                // value; otherwise the digits still show as plain text above.
                if let phone, !phone.isEmpty, !phone.contains("*") {
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
                if order.discount > 0 {
                    savingsRow("Savings", value: order.discount)
                }
                // Pickup orders carry no delivery fee (backend forces 0) and
                // the service fee is always 0, so suppress these rows when they
                // are zero rather than showing meaningless "$0.00" lines.
                if !order.isPickup && order.deliveryFee > 0 {
                    priceRow("Delivery Fee", value: order.deliveryFee)
                }
                if order.serviceFee > 0 {
                    priceRow("Service Fee", value: order.serviceFee)
                }
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
            VStack(spacing: 10) {
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

                if canChooseDeliveryMode(order) { deliveryModeChoiceButton(order) }
            }

        case .preparing:
            VStack(spacing: 10) {
                actionButton(readyButtonTitle(order), icon: "bag.fill", color: .keSuccess) {
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

                if canChooseDeliveryMode(order) { deliveryModeChoiceButton(order) }
            }

        case .ready, .pickedUp:
            if order.isPickup && order.status == .ready {
                // Pickup orders never get a courier — the seller marks them
                // completed when the customer arrives. Backend's CompleteOrder
                // handler enforces the same status='ready' guard.
                pickupReadyCard(order)
            } else if order.isSelfDelivery && order.courier == nil && (order.externalDeliveryId ?? "").isEmpty {
                // Self-delivery ('restaurant' mode), not handed off: the seller
                // drives ready -> picked_up -> delivered with its own driver.
                // (Once escalated to Uber, externalDeliveryId is set and we fall
                // through to the partner-status card below.)
                VStack(spacing: 10) {
                    if order.status == .ready {
                        actionButton("Mark Picked Up (self-delivery)", icon: "bag.fill", color: .kePrimary) {
                            guard !isActing else { return }
                            isActing = true
                            Task {
                                vm.errorMessage = nil
                                await vm.markSelfPickup(id: order.id)
                                await syncOrderFromVM()
                                isActing = false
                            }
                        }
                        .disabled(isActing)
                        // Driver fell through? Hand it to Uber instead.
                        escalateButton(order)
                    } else { // .pickedUp
                        actionButton("Mark Delivered", icon: "checkmark.circle.fill", color: .keSuccess) {
                            guard !isActing else { return }
                            isActing = true
                            Task {
                                vm.errorMessage = nil
                                await vm.markSelfDeliver(id: order.id)
                                await syncOrderFromVM()
                                isActing = false
                            }
                        }
                        .disabled(isActing)
                    }
                }
            } else {
                VStack(spacing: 10) {
                    // Courier now owns the handoff. Show who's handling delivery
                    // instead of an action — same UX pattern as the UberEats merchant app.
                    courierStatusCard(order)

                    // A ready delivery order that nobody is handling yet (no courier
                    // claimed it, not already on a provider) can still be punted to
                    // Uber — the seller's own driver may have fallen through. Once a
                    // courier claims it or it's dispatched, this drops off. Mirrors the
                    // backend escalate guard: courier_id IS NULL AND external_delivery_id
                    // IS NULL, status IN (accepted, preparing, ready).
                    if order.status == .ready
                        && order.courier == nil
                        && (order.externalDeliveryId ?? "").isEmpty {
                        if canChooseDeliveryMode(order) {
                            deliveryModeChoiceButton(order)
                        } else {
                            escalateButton(order)
                        }
                    }
                }
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

    /// Whether the "Dispatch to Uber" button should be offered: a delivery
    /// order that nobody is already handling. Mirrors the backend EscalateToUber
    /// guard (courier_id IS NULL AND external_delivery_id IS NULL) so the button
    /// doesn't appear on already-claimed/already-dispatched orders.
    private func canEscalate(_ order: Order) -> Bool {
        !order.isPickup
            && order.status == .ready
            && order.isSelfDelivery
            && order.courier == nil
            && (order.externalDeliveryId ?? "").isEmpty
    }

    private func canChooseDeliveryMode(_ order: Order) -> Bool {
        !order.isPickup
            && (order.status == .accepted || order.status == .preparing || order.status == .ready)
            && order.courier == nil
            && (order.externalDeliveryId ?? "").isEmpty
    }

    private func readyButtonTitle(_ order: Order) -> String {
        if order.isPickup {
            return "Ready for customer pickup"
        }
        if order.isSelfDelivery {
            return "Ready for your driver"
        }
        if order.deliveryMode == "external" {
            return "Ready for Uber pickup"
        }
        return "Ready for courier pickup"
    }

    @ViewBuilder
    private func deliveryModeChoiceButton(_ order: Order) -> some View {
        let switchingToSelfDelivery = !order.isSelfDelivery
        let title = switchingToSelfDelivery ? "Self-deliver this order" : "Use Uber Direct for this order"
        let icon = switchingToSelfDelivery ? "car.side.fill" : "car.circle.fill"
        let nextMode = switchingToSelfDelivery ? "restaurant" : "external"

        Button {
            guard !isActing else { return }
            isActing = true
            Task {
                vm.errorMessage = nil
                await vm.setDeliveryMode(id: order.id, deliveryMode: nextMode)
                await syncOrderFromVM()
                isActing = false
            }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: icon)
                Text(title).font(.headline)
            }
            .foregroundColor(.kePrimary)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(Color.keCard)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color.kePrimary, lineWidth: 1.5)
            )
            .cornerRadius(14)
        }
        .disabled(isActing)
    }

    /// Secondary action on an open delivery order: hand it off to an Uber
    /// courier when the seller is overwhelmed. One-way — the backend rejects
    /// orders already on a courier/provider; the result surfaces via an alert.
    @ViewBuilder
    private func escalateButton(_ order: Order) -> some View {
        // Secondary (outlined) style so it reads as the lower-priority escalation
        // lever, not a co-equal of the filled primary action above it.
        Button {
            Haptics.warning()
            showDispatchConfirm = true
        } label: {
            HStack(spacing: 8) {
                if isDispatching {
                    ProgressView().tint(.kePrimary)
                    Text("Dispatching…").font(.headline)
                } else {
                    Image(systemName: "car.circle.fill")
                    Text("Dispatch to Uber").font(.headline)
                }
            }
            .foregroundColor(.kePrimary)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(Color.keCard)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color.kePrimary, lineWidth: 1.5)
            )
            .cornerRadius(14)
        }
        .disabled(isActing || isDispatching)
        // One-way, irreversible action → confirm first (Reject has one too).
        .confirmationDialog(
            "Dispatch this order to an Uber courier?",
            isPresented: $showDispatchConfirm,
            titleVisibility: .visible
        ) {
            Button("Dispatch to Uber") { performDispatch(order) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("A courier will be sent to pick up this order. This can't be undone.")
        }
    }

    /// Runs the escalate request, mapping the backend's distinct status codes to
    /// honest copy. A 409 ("already being dispatched" — a sweep or a second tap
    /// won the claim) is NOT a failure: it means a courier IS coming, so we show
    /// a neutral message rather than the old scary "couldn't send" error.
    private func performDispatch(_ order: Order) {
        guard !isActing, !isDispatching else { return }
        isActing = true
        isDispatching = true
        Task {
            vm.errorMessage = nil
            do {
                _ = try await APIService.shared.escalateOrderToUber(id: order.id)
                Haptics.success()
                escalateMessage = "Sent to Uber — a courier is on the way."
            } catch APIError.serverError(let code, _) where code == 409 {
                Haptics.success()
                escalateMessage = "This order is already being dispatched to a courier."
            } catch APIError.serverError(let code, _) where code == 503 {
                escalateMessage = "No delivery partner is available right now. Please try again shortly."
            } catch {
                escalateMessage = "Couldn't reach a courier. Please try again."
            }
            // Escalate sets external_delivery_id but NOT status, and the cached
            // list copy doesn't carry external_delivery_id — so syncOrderFromVM's
            // cache-first path would keep the stale order (escalate button stuck
            // on, status unchanged). Force a fresh detail fetch first.
            await vm.fetchOrder(id: order.id)
            await syncOrderFromVM()
            isDispatching = false
            isActing = false
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
        } else if let ext = order.externalDeliveryId, !ext.isEmpty {
            // Dispatched to an external partner (Uber Direct / DoorDash): there is
            // no platform courier row, so the generic "waiting for a courier"
            // spinner below would wrongly imply the order is stuck/unclaimed. It's
            // been handed off — a partner courier is en route. This is the common
            // case now that external dispatch is the default delivery path.
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 10) {
                    Image(systemName: order.status == .pickedUp ? "car.fill" : "shippingbox.fill")
                        .foregroundColor(.kePrimary)
                    Text(order.status == .pickedUp
                         ? "Out for delivery with \(order.externalProviderName)"
                         : "Handed to \(order.externalProviderName) — a courier is on the way")
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(.keTextPrimary)
                }

                // The provider's tracking URL is the only courier visibility we
                // get on the external path (Uber Direct / DoorDash don't expose
                // courier name/phone to us). Surface it so the seller — or the
                // customer they're on the phone with — can see live ETA/location.
                if let urlStr = order.externalTrackingUrl, !urlStr.isEmpty,
                   let url = URL(string: urlStr) {
                    Link(destination: url) {
                        HStack(spacing: 8) {
                            Image(systemName: "location.fill.viewfinder")
                            Text("Track delivery")
                                .font(.subheadline.weight(.semibold))
                            Spacer()
                            Image(systemName: "arrow.up.right").font(.caption)
                        }
                        .foregroundColor(.kePrimary)
                        .padding(.vertical, 11)
                        .padding(.horizontal, 12)
                        .frame(maxWidth: .infinity)
                        .background(Color.keBorder.opacity(0.5))
                        .cornerRadius(10)
                    }
                }

                if let ext = order.externalDeliveryId, !ext.isEmpty {
                    Text("Delivery ID: \(ext)")
                        .font(.caption2)
                        .foregroundColor(.keTextMuted)
                        .textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
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

    /// A discount/savings line, rendered as a negative amount (`-$X.XX`) and
    /// tinted success-green so the breakdown rows still sum to `order.total`.
    /// `value` is the positive discount amount in cents.
    private func savingsRow(_ label: String, value: Int) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundColor(.keSuccess)
            Spacer()
            Text("-\(CurrencyFormat.string(fromCents: value))")
                .font(.subheadline)
                .foregroundColor(.keSuccess)
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
