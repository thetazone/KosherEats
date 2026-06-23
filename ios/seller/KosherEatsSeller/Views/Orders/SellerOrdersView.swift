import SwiftUI

struct SellerOrdersView: View {
    @StateObject private var vm = OrdersViewModel()
    @ObservedObject private var selectedRestaurant = SelectedRestaurant.shared
    @Environment(\.horizontalSizeClass) private var sizeClass

    /// Programmatic navigation stack. Holds the id of the order whose detail
    /// is currently pushed (one level deep — we never stack two detail
    /// screens). Driving navigation by id rather than by `NavigationLink`
    /// destination lets a push deep link open a specific ticket.
    @State private var navPath: [String] = []

    /// Order id carried by a tapped order push (`.orderDeepLinkRequested`)
    /// that we haven't been able to navigate to yet — typically because the
    /// app cold-launched from the push and `vm.orders` is still empty. Held
    /// here until `vm.load()` resolves it, at which point `resolveDeepLink()`
    /// pushes the detail and clears this.
    @State private var pendingDeepLinkOrderID: String?

    // Order cards stack vertically on iPhone (single column) but show in a
    // 2-column grid on iPad so reviewers see more orders at a glance.
    private var orderGridColumns: [GridItem] {
        SizeClass.isRegular(h: sizeClass)
            ? [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
            : [GridItem(.flexible())]
    }

    /// Uses the seller's current timezone (device timezone) for the "today"
    /// boundary. Calendar.current already uses TimeZone.current, but we set it
    /// explicitly so the intent is clear and future refactors don't break it.
    private var todayOrders: [Order] {
        var cal = Calendar.current
        cal.timeZone = TimeZone.current
        return vm.orders.filter { order in
            guard let date = order.createdAtDate else { return false }
            guard order.status != .cancelled && order.status != .rejected else { return false }
            return cal.isDateInToday(date)
        }
    }
    /// Subtotal only (food sales before tax/fees/tip). This is intentional:
    /// the seller ticker shows what they earned from food, not what the
    /// customer paid — tax and platform fees are not seller revenue.
    private var todayRevenue: Int {
        todayOrders.reduce(0) { $0 + $1.subtotal }
    }

    var body: some View {
        NavigationStack(path: $navPath) {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                VStack(spacing: 0) {
                    if let pollErr = vm.pollHealthError {
                        pollErrorBanner(pollErr)
                    }

                    if let name = selectedRestaurant.name {
                        restaurantIndicator(name)
                    }

                    // Filter Tabs
                    filterTabs
                        .padding(.horizontal)
                        .padding(.vertical, 12)
                        .adaptiveContentWidth(900)

                    if !todayOrders.isEmpty {
                        todayTicker
                    }

                    if vm.isLoading && vm.orders.isEmpty {
                        ScrollView {
                            LazyVGrid(columns: orderGridColumns, spacing: 12) {
                                ForEach(0..<4, id: \.self) { _ in
                                    ActiveOrderCardSkeleton()
                                }
                            }
                            .padding()
                            .adaptiveContentWidth(900)
                        }
                    } else if let err = vm.errorMessage, vm.orders.isEmpty {
                        ErrorStateView(
                            message: err,
                            onRetry: { Task { await vm.load() } },
                        )
                    } else if vm.filteredOrders.isEmpty {
                        Spacer()
                        emptyState
                        Spacer()
                    } else {
                        ScrollView {
                            LazyVGrid(columns: orderGridColumns, spacing: 12) {
                                ForEach(vm.filteredOrders) { order in
                                    NavigationLink(value: order.id) {
                                        OrderRowView(order: order)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding()
                            .adaptiveContentWidth(900)
                        }
                    }
                }
            }
            .navigationTitle("Orders")
            .navigationBarTitleDisplayMode(.large)
            // Resolve a pushed order id to its detail screen. The Order is read
            // out of vm.orders (kept fresh by the poll loop); the detail view
            // re-fetches by id if the in-memory copy is stale or missing.
            .navigationDestination(for: String.self) { orderID in
                if let order = vm.orders.first(where: { $0.id == orderID }) {
                    SellerOrderDetailView(vm: vm, order: order)
                } else {
                    // Cold-launch / not-yet-loaded: detail view fetches by id.
                    SellerOrderDetailView(vm: vm, orderID: orderID)
                }
            }
            .refreshable {
                Haptics.impact(.light)
                await vm.load()
            }
            .task(id: selectedRestaurant.id) {
                await vm.loadAndAutoRefresh()
            }
            // A push tap posts `.orderDeepLinkRequested` with the order_id.
            // Capture it, then try to navigate; if the list hasn't loaded yet
            // (cold launch from the push), `resolveDeepLink` kicks a load and
            // the .onReceive(vm.$orders) below retries once the fetch lands.
            .onReceive(NotificationCenter.default.publisher(for: .orderDeepLinkRequested)) { note in
                guard let orderID = note.userInfo?[PushEvents.orderIDKey] as? String,
                      !orderID.isEmpty else { return }
                pendingDeepLinkOrderID = orderID
                resolveDeepLink()
            }
            // Retry resolving a pending deep link whenever orders change — this
            // is what closes the cold-launch race: the push arrives before the
            // first fetch completes, so we can't navigate until vm.orders fills.
            .onReceive(vm.$orders) { _ in
                resolveDeepLink()
            }
        }
    }

    /// Pushes the detail screen for a pending deep-link order id. The detail
    /// view resolves the order by id (fetching if needed), so we navigate as
    /// soon as we have an id — but we first kick a `load()` if the list is
    /// empty so a cold launch from a push still ends up showing the ticket
    /// list underneath the pushed detail. Idempotent: clears the pending id
    /// and won't double-push the same order already on top of the stack.
    private func resolveDeepLink() {
        guard let orderID = pendingDeepLinkOrderID else { return }
        if navPath.last == orderID {
            pendingDeepLinkOrderID = nil
            return
        }
        if vm.orders.isEmpty && !vm.isLoading {
            Task { await vm.load() }
        }
        navPath = [orderID]
        pendingDeepLinkOrderID = nil
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

    /// Small pill naming the currently-selected restaurant. Keeps multi-
    /// restaurant sellers oriented when they switch — otherwise "Orders"
    /// looks the same for every restaurant they own.
    private func restaurantIndicator(_ name: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "storefront.fill")
                .font(.caption2)
                .foregroundColor(.kePrimary)
            Text("Showing: \(name)")
                .font(.caption.bold())
                .foregroundColor(.keTextSecondary)
                .lineLimit(1)
            Spacer()
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
    }

    /// Shown when the poll loop has failed ≥3 times. Red and dismissible via
    /// pull-to-refresh — if the next refresh succeeds, the banner clears.
    private func pollErrorBanner(_ message: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "wifi.exclamationmark")
                .foregroundColor(.keTextOnAccent)
            Text(message)
                .font(.caption.bold())
                .foregroundColor(.keTextOnAccent)
            Spacer()
            Button("Retry") {
                Task { await vm.load() }
            }
            .font(.caption.bold())
            .foregroundColor(.keTextOnAccent)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(Color.keError)
    }

    private var todayTicker: some View {
        HStack(spacing: 8) {
            Image(systemName: "chart.bar.fill")
                .foregroundColor(.kePrimary)
                .accessibilityHidden(true)
            Text("\(todayOrders.count) orders today")
                .font(.subheadline.bold())
                .foregroundColor(.keTextPrimary)
            Text("\u{2022}")
                .foregroundColor(.keTextMuted)
                .accessibilityHidden(true)
            Text("\(CurrencyFormat.string(fromCents: todayRevenue)) food sales")
                .font(.subheadline.bold())
                .foregroundColor(.kePrimary)
            Spacer()
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(Color.keCard)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(todayOrders.count) orders today, \(CurrencyFormat.string(fromCents: todayRevenue)) in food sales")
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "list.clipboard")
                .font(.system(size: 48))
                .foregroundColor(.keTextMuted)

            Text("No \(vm.selectedFilter.rawValue.lowercased()) orders")
                .font(.headline)
                .foregroundColor(.keTextSecondary)

            Text(String(localized: "New orders will appear here automatically."))
                .font(.subheadline)
                .foregroundColor(.keTextMuted)
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
            .accessibilityLabel("Status: \(order.status.displayName)")

            // Details
            VStack(alignment: .leading, spacing: 4) {
                Text("Order #\(String(order.id.prefix(8)))")
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)

                Text("\(order.itemCount) items")
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)

                timestampLine
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 4) {
                Text(order.totalFormatted)
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)

                statusPill
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
        order.status.resolvedColor
    }

    /// For orders still in the kitchen's lane (pending → ready), show a
    /// ticking "Waiting 8:23" counter so the seller can see at a glance
    /// which tickets are getting stale. Flips red once past 15 min so the
    /// pain point is immediately obvious. Finished orders just show the
    /// usual date string.
    @ViewBuilder
    private var timestampLine: some View {
        let showCounter = order.status == .pending
            || order.status == .accepted
            || order.status == .preparing
            || order.status == .ready
        if showCounter, let created = order.createdAtDate {
            TimelineView(.periodic(from: .now, by: 10)) { context in
                let elapsed = max(0, Int(context.date.timeIntervalSince(created)))
                let mins = elapsed / 60
                let secs = elapsed % 60
                let overdue = elapsed >= 15 * 60
                HStack(spacing: 4) {
                    Image(systemName: overdue ? "exclamationmark.triangle.fill" : "clock.fill")
                        .font(.system(size: 9))
                    Text("Waiting \(mins):\(String(format: "%02d", secs))")
                        .monospacedDigit()
                }
                .font(.caption2.bold())
                .foregroundColor(overdue ? .keError : .keTextMuted)
            }
        } else {
            Text(order.formattedDate)
                .font(.caption2)
                .foregroundColor(.keTextMuted)
        }
    }

    /// Default pill shows the status name. For `.pickedUp` specifically we
    /// swap in an "Out for delivery" badge with a bike glyph so the seller
    /// can tell at a glance which orders have left the kitchen — they share
    /// the Active tab with still-preparing orders now.
    @ViewBuilder
    private var statusPill: some View {
        if order.status == .pickedUp {
            HStack(spacing: 4) {
                Image(systemName: "bicycle")
                    .font(.caption2.bold())
                Text("Out for delivery")
                    .font(.caption2.bold())
            }
            .foregroundColor(.keTextOnAccent)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.kePrimary)
            .cornerRadius(6)
        } else {
            Text(order.status.displayName)
                .font(.caption2.bold())
                .foregroundColor(statusColor)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(statusColor.opacity(0.15))
                .cornerRadius(6)
        }
    }
}
