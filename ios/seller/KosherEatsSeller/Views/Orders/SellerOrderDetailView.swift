import SwiftUI

struct SellerOrderDetailView: View {
    @StateObject private var vm = OrdersViewModel()
    @Environment(\.dismiss) private var dismiss
    @State var order: Order
    @State private var showRejectAlert = false
    @State private var rejectReason = ""
    @State private var isActing = false

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 20) {
                    // Status Header
                    statusHeader

                    // Order Items
                    itemsSection

                    // Delivery Info
                    deliverySection

                    // Customer contact
                    if let name = order.customerName, !name.isEmpty {
                        customerSection(name: name, phone: order.customerPhone)
                    }

                    // Price Breakdown
                    priceSection

                    // Action Buttons
                    actionButtons
                }
                .padding()
                .adaptiveContentWidth(720)
            }
        }
        .navigationTitle("Order #\(String(order.id.prefix(8)))")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .alert("Reject Order", isPresented: $showRejectAlert) {
            TextField("Reason (optional)", text: $rejectReason)
            Button("Cancel", role: .cancel) { }
            Button("Reject", role: .destructive) {
                Task {
                    vm.errorMessage = nil
                    await vm.rejectOrder(
                        id: order.id,
                        reason: rejectReason.isEmpty ? nil : rejectReason
                    )
                    if vm.errorMessage == nil { order.status = .rejected }
                    dismiss()
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
    }

    // MARK: - Status Header

    private var statusHeader: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(statusColor.opacity(0.15))
                    .frame(width: 64, height: 64)

                Image(systemName: order.status.icon)
                    .font(.title.bold())
                    .foregroundColor(statusColor)
            }

            Text(order.status.displayName)
                .font(.title3.bold())
                .foregroundColor(statusColor)

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

    private var itemsSection: some View {
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

    private var deliverySection: some View {
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
                    .frame(width: 44, height: 44)
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
                if let phone, !phone.isEmpty,
                   let encoded = phone.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
                   let url = URL(string: "tel:\(encoded)") {
                    Link(destination: url) {
                        Image(systemName: "phone.fill")
                            .foregroundColor(.kePrimary)
                            .padding(10)
                            .background(Color.keBorder)
                            .clipShape(Circle())
                    }
                }
            }
            .padding()
            .background(Color.keCard)
            .cornerRadius(14)
        }
    }

    // MARK: - Price

    private var priceSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionHeader("Payment", icon: "creditcard.fill")

            VStack(spacing: 8) {
                priceRow("Subtotal", value: order.subtotal)
                priceRow("Delivery Fee", value: order.deliveryFee)
                priceRow("Service Fee", value: order.serviceFee)
                priceRow("Tax", value: order.tax)

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
    private var actionButtons: some View {
        switch order.status {
        case .pending:
            VStack(spacing: 10) {
                actionButton("Accept Order", icon: "checkmark.circle.fill", color: .keSuccess) {
                    guard !isActing else { return }
                    isActing = true
                    Task {
                        vm.errorMessage = nil
                        await vm.acceptOrder(id: order.id)
                        if vm.errorMessage == nil { order.status = .accepted; Haptics.success() }
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
                    if vm.errorMessage == nil { order.status = .preparing; Haptics.impact(.medium) }
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
                    if vm.errorMessage == nil { order.status = .ready; Haptics.success() }
                    isActing = false
                }
            }
            .disabled(isActing)

        case .ready, .pickedUp:
            // Courier now owns the handoff. Show who's handling delivery
            // instead of an action — same UX pattern as the UberEats merchant app.
            courierStatusCard

        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var courierStatusCard: some View {
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
                        .frame(width: 44, height: 44)
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
                    if let encoded = courier.phone.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
                       let url = URL(string: "tel:\(encoded)") {
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
            Text(String(format: "$%.2f", Double(value) / 100))
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
            .foregroundColor(.white)
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
                .foregroundColor(.white)
                .padding()
                .background(Color.keSuccess)
                .cornerRadius(12)
                .padding(.bottom, 20)
        }
        .transition(.move(edge: .bottom))
        .animation(.easeInOut, value: vm.successMessage)
    }

    private var statusColor: Color {
        switch order.status.color {
        case "primary": return .kePrimary
        case "success": return .keSuccess
        case "warning": return .keWarning
        case "error": return .keError
        default: return .keTextSecondary
        }
    }
}
