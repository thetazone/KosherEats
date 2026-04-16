import SwiftUI

struct OrdersListView: View {
    @StateObject private var vm = OrderViewModel()
    @State private var selectedSegment = 0
    // Deep-link state: when a navigation notification arrives, we set one
    // of these so a NavigationLink(isActive:) pushes the right screen.
    @State private var deepLinkOrderId: String?
    @State private var showTrackingForDeepLink = false
    @State private var showDetailForDeepLink = false

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
                                        OrderRowView(order: order)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding()
                        }
                    }
                }
            }
            .navigationTitle("Orders")
            .navigationBarTitleDisplayMode(.large)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .task {
                await vm.loadOrders()
            }
            .refreshable {
                Haptics.impact(.light)
                await vm.loadOrders()
            }
            // Deep-link hooks for "track your order" button and push taps.
            .onReceive(NotificationCenter.default.publisher(for: .navigateToOrderTracking)) { note in
                if let id = note.userInfo?["order_id"] as? String {
                    deepLinkOrderId = id
                    showTrackingForDeepLink = true
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .navigateToOrderDetail)) { note in
                if let id = note.userInfo?["order_id"] as? String {
                    deepLinkOrderId = id
                    showDetailForDeepLink = true
                }
            }
            .navigationDestination(isPresented: $showTrackingForDeepLink) {
                if let id = deepLinkOrderId {
                    OrderTrackingView(orderId: id)
                }
            }
            .navigationDestination(isPresented: $showDetailForDeepLink) {
                if let id = deepLinkOrderId {
                    OrderDetailView(orderID: id)
                }
            }
        }
    }
}

// MARK: - Order Row

struct OrderRowView: View {
    let order: Order

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
