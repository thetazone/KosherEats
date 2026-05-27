import SwiftUI

struct OrdersListView: View {
    @StateObject private var vm = OrderViewModel()
    @EnvironmentObject var cartVM: CartViewModel
    @Binding var pendingTrackingOrderId: String?
    @Binding var pendingDetailOrderId: String?
    @State private var selectedSegment = 0
    @State private var showReorderToast = false
    @State private var reorderError: String? = nil
    @State private var reorderTask: Task<Void, Never>? = nil

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                VStack(spacing: 0) {
                    // Segment control
                    Picker("", selection: $selectedSegment) {
                        Text("Active").tag(0)
                        Text("Past").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .padding()

                    let displayOrders = selectedSegment == 0 ? vm.activeOrders : vm.pastOrders

                    if vm.isLoading && displayOrders.isEmpty {
                        ScrollView(showsIndicators: false) {
                            LazyVStack(spacing: 12) {
                                ForEach(0..<3, id: \.self) { _ in
                                    OrderRowSkeleton()
                                }
                            }
                            .padding()
                        }
                    } else if let err = vm.errorMessage, vm.orders.isEmpty {
                        ErrorStateView(
                            message: err,
                            onRetry: { Task { await vm.loadOrders() } },
                        )
                    } else if displayOrders.isEmpty {
                        Spacer()
                        VStack(spacing: 12) {
                            Image(systemName: selectedSegment == 0 ? "clock" : "bag")
                                .font(.system(size: 48))
                                .foregroundColor(.keTextMuted)
                            Text(selectedSegment == 0 ? "No active orders" : "No past orders")
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundColor(.keTextPrimary)
                            Text(selectedSegment == 0 ? "Your active orders will appear here" : "Your order history will appear here")
                                .font(.body)
                                .foregroundColor(.keTextSecondary)
                        }
                        Spacer()
                    } else {
                        ScrollView(showsIndicators: false) {
                            LazyVStack(spacing: 12) {
                                ForEach(displayOrders) { order in
                                    NavigationLink(destination: OrderDetailView(orderID: order.id)) {
                                        OrderRowView(
                                            order: order,
                                            onReorder: order.status == .delivered ? {
                                                guard !cartVM.isReordering else { return }
                                                reorderTask?.cancel()
                                                reorderTask = Task {
                                                    let error = await cartVM.reorder(
                                                        items: order.items,
                                                        restaurantID: order.restaurantID
                                                    )
                                                    guard !Task.isCancelled else { return }
                                                    if error == nil {
                                                        showReorderToast = true
                                                    } else {
                                                        reorderError = error
                                                    }
                                                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                                                    guard !Task.isCancelled else { return }
                                                    showReorderToast = false
                                                    reorderError = nil
                                                }
                                            } : nil
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding()
                        }
                    }
                }
            }
            .overlay(alignment: .bottom) {
                if cartVM.isReordering {
                    HStack(spacing: 8) {
                        ProgressView().tint(.keTextOnAccent)
                        Text("Adding items to cart…")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.keTextOnAccent)
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(Color.kePrimary)
                    .cornerRadius(25)
                    .shadow(radius: 4)
                    .padding(.bottom, 32)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .animation(.easeInOut, value: cartVM.isReordering)
                } else if showReorderToast {
                    Text("Items added to cart!")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.keTextOnAccent)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(Color.kePrimary)
                        .cornerRadius(25)
                        .shadow(radius: 4)
                        .padding(.bottom, 32)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .animation(.easeInOut, value: showReorderToast)
                } else if let error = reorderError {
                    Text(error)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.keTextOnAccent)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .background(Color.red)
                        .cornerRadius(25)
                        .shadow(radius: 4)
                        .padding(.bottom, 32)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .animation(.easeInOut, value: reorderError)
                }
            }
            .navigationTitle("Orders")
            .navigationBarTitleDisplayMode(.large)
            .onDisappear {
                reorderTask?.cancel()
                reorderTask = nil
                showReorderToast = false
                reorderError = nil
            }
            .task {
                await vm.loadOrders()
            }
            .refreshable {
                Haptics.impact(.light)
                await vm.loadOrders()
            }
            .navigationDestination(item: $pendingTrackingOrderId) { id in
                OrderTrackingView(orderId: id)
            }
            .navigationDestination(item: $pendingDetailOrderId) { id in
                OrderDetailView(orderID: id)
            }
        }
    }
}

// MARK: - Order Row

struct OrderRowView: View {
    let order: Order
    var onReorder: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(order.restaurantName)
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(.keTextPrimary)

                    Text(order.createdAt.formatted(date: .abbreviated, time: .shortened))
                        .font(.system(size: 13))
                        .foregroundColor(.keTextMuted)
                }

                Spacer()

                OrderStatusBadge(status: order.status)
            }

            // Items preview
            Text(order.items.map { "\($0.quantity)x \($0.name)" }.joined(separator: ", "))
                .font(.system(size: 14))
                .foregroundColor(.keTextSecondary)
                .lineLimit(2)

            HStack {
                Text(order.totalFormatted)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.kePrimary)

                Spacer()

                HStack(spacing: 4) {
                    Text("View Details")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.keTextSecondary)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12))
                        .foregroundColor(.keTextMuted)
                }
            }

            if order.status == .delivered {
                Button {
                    onReorder?()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "bag.fill")
                        Text("Order Again")
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.kePrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color.kePrimary.opacity(0.1))
                    .cornerRadius(10)
                }
            }
        }
        .padding(16)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }
}

// MARK: - Order Status Badge

struct OrderStatusBadge: View {
    let status: OrderStatus

    private var color: Color {
        switch status {
        case .scheduled, .pending: return .keWarning
        case .accepted, .preparing: return .kePrimary
        case .ready, .pickedUp: return .keDairy
        case .delivered: return .keSuccess
        case .cancelled, .rejected: return .keError
        case .completed:
            return .clear
        @unknown default:
            return .keTextSecondary
        }
    }

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: status.iconName)
                .font(.system(size: 10))
            Text(status.displayName)
                .font(.system(size: 12, weight: .bold))
        }
        .foregroundColor(color)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(color.opacity(0.15))
        .cornerRadius(8)
    }
}
