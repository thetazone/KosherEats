import SwiftUI

struct SellerOrdersView: View {
    @StateObject private var vm = OrdersViewModel()

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                VStack(spacing: 0) {
                    // Filter Tabs
                    filterTabs
                        .padding(.horizontal)
                        .padding(.vertical, 12)

                    if vm.isLoading && vm.orders.isEmpty {
                        Spacer()
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .kePrimary))
                        Spacer()
                    } else if vm.filteredOrders.isEmpty {
                        Spacer()
                        emptyState
                        Spacer()
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 12) {
                                ForEach(vm.filteredOrders) { order in
                                    NavigationLink(destination: SellerOrderDetailView(order: order)) {
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
            .refreshable {
                await vm.load()
            }
            .task {
                await vm.load()
            }
        }
    }

    // MARK: - Filter Tabs

    private var filterTabs: some View {
        HStack(spacing: 8) {
            ForEach(OrdersViewModel.OrderFilter.allCases) { filter in
                Button {
                    vm.selectedFilter = filter
                    vm.applyFilter()
                } label: {
                    Text(filter.rawValue)
                        .font(.subheadline.bold())
                        .foregroundColor(
                            vm.selectedFilter == filter ? .white : .keTextSecondary
                        )
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(
                            vm.selectedFilter == filter ? Color.kePrimary : Color.keCard
                        )
                        .cornerRadius(10)
                }
            }
            Spacer()
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "list.clipboard")
                .font(.system(size: 48))
                .foregroundColor(.keTextMuted)

            Text("No \(vm.selectedFilter.rawValue.lowercased()) orders")
                .font(.headline)
                .foregroundColor(.keTextSecondary)
        }
    }
}

// MARK: - Order Row

struct OrderRowView: View {
    let order: Order

    var body: some View {
        HStack(spacing: 14) {
            // Status Icon
            ZStack {
                Circle()
                    .fill(statusColor.opacity(0.15))
                    .frame(width: 44, height: 44)

                Image(systemName: order.status.icon)
                    .font(.body.bold())
                    .foregroundColor(statusColor)
            }

            // Details
            VStack(alignment: .leading, spacing: 4) {
                Text("Order #\(String(order.id.prefix(8)))")
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)

                Text("\(order.itemCount) items")
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)

                Text(order.formattedDate)
                    .font(.caption2)
                    .foregroundColor(.keTextMuted)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 4) {
                Text(String(format: "$%.2f", order.total))
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)

                Text(order.status.displayName)
                    .font(.caption2.bold())
                    .foregroundColor(statusColor)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(statusColor.opacity(0.15))
                    .cornerRadius(6)
            }

            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.keTextMuted)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(14)
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
