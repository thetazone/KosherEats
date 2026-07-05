import SwiftUI

struct DashboardView: View {
    @StateObject private var vm = DashboardViewModel()
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var showingPicker = false
    @State private var pickerSelectionID: String? = SelectedRestaurant.shared.id
    @State private var loadTask: Task<Void, Never>?
    @State private var showProfileSheet = false
    // @State (not @AppStorage) on purpose: dismissal lasts for the session
    // and self-re-arms on logout because the view tree is destroyed.
    @State private var profileBannerDismissed = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        // Non-blocking nudge to finish the profile —
                        // user-initiated and dismissible per App Review
                        // Guideline 4 (never a forced sheet).
                        if authVM.shouldOfferProfileCompletion && !profileBannerDismissed {
                            profileCompletionBanner
                        }

                        // Restaurant Status
                        if let restaurant = vm.restaurant {
                            restaurantStatusCard(restaurant)
                        } else if vm.isLoading {
                            SkeletonBlock(cornerRadius: 16).frame(height: 72)
                        }

                        // Stats Grid — skeleton while loading so the layout
                        // doesn't jump when real numbers arrive.
                        if vm.isLoading && vm.restaurant == nil {
                            StatsGridSkeleton()
                        } else {
                            statsGrid
                        }

                        if let err = vm.errorMessage, vm.restaurant == nil {
                            ErrorStateView(
                                message: err,
                                onRetry: { Task { await vm.load() } },
                            )
                            .frame(minHeight: 240)
                        } else {
                            activeOrdersSection
                        }
                    }
                    .padding()
                    .adaptiveContentWidth(900)
                }
            }
            .navigationTitle("Dashboard")
            .navigationBarTitleDisplayMode(.large)
            // Surface action errors (notably the open/closed toggle) as an alert
            // once the restaurant has loaded. Previously vm.errorMessage was only
            // rendered in the empty-state branch (restaurant == nil), so a failed
            // setRestaurantOpen — including the backend's 403 "restaurant must be
            // approved before it can be opened for orders" — left the toggle
            // silently snapping back with no feedback. The full-screen
            // ErrorStateView still owns the initial-load (restaurant == nil) case.
            .alert("Couldn't update",
                   isPresented: Binding(
                    get: { vm.errorMessage != nil && vm.restaurant != nil },
                    set: { if !$0 { vm.errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(vm.errorMessage ?? "")
            }
            .toolbar {
                // Surface the restaurant picker only when the seller owns
                // more than one restaurant — otherwise there's nothing to
                // switch between and the button would be dead weight.
                if vm.restaurantCount > 1 {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            showingPicker = true
                        } label: {
                            Image(systemName: "storefront.fill")
                                .foregroundColor(.kePrimary)
                        }
                        .accessibilityLabel("Switch restaurant")
                    }
                }
            }
            .sheet(isPresented: $showingPicker) {
                RestaurantPickerSheet(
                    isPresented: $showingPicker,
                    currentID: $pickerSelectionID,
                    onChange: {
                        // Re-fetch everything under the new restaurant context.
                        // All seller endpoints key off SelectedRestaurant.shared,
                        // which the sheet has already updated.
                        // Cancel any in-flight load to avoid concurrent fetches.
                        loadTask?.cancel()
                        loadTask = Task { await vm.load() }
                    }
                )
            }
            .refreshable {
                Haptics.impact(.light)
                await vm.load()
            }
            .task {
                await vm.load()
                vm.startAutoRefresh()
            }
            .onDisappear {
                vm.stopAutoRefresh()
            }
            .sheet(isPresented: $showProfileSheet) {
                ProfileCompletionSheet()
                    .environmentObject(authVM)
                    .presentationDetents([.medium, .large])
            }
        }
    }

    // MARK: - Profile Completion Banner

    // Non-blocking nudge: opens the optional ProfileCompletionSheet only when
    // the seller taps it, and can be dismissed outright — App Review
    // Guideline 4 forbids demanding name/email after Sign in with Apple.
    private var profileCompletionBanner: some View {
        HStack(spacing: 12) {
            Button {
                showProfileSheet = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "person.crop.circle.badge.exclamationmark")
                        .font(.title3)
                        .foregroundColor(.kePrimary)

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Complete your profile")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(.keTextPrimary)

                        Text("Add your name and a contact email for payout and order updates.")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                            .multilineTextAlignment(.leading)
                    }

                    Spacer()
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens a form to add your name and email")

            Button {
                withAnimation { profileBannerDismissed = true }
            } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.keTextMuted)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss profile reminder")
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }

    // MARK: - Restaurant Status

    private func restaurantStatusCard(_ restaurant: Restaurant) -> some View {
        // Until the platform admin approves the restaurant, the seller can't
        // go live — the open/closed toggle is disabled and shows "Pending
        // approval" instead of Open/Closed. The backend enforces the same
        // 403 ("must be approved before it can be opened"), this just keeps
        // the UI honest about why. Mirrors Android DashboardScreen.kt's
        // `isApproved` gating + caption.
        let isApproved = restaurant.isApproved
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(restaurant.name)
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)

                    Text(statusLabel(restaurant: restaurant, isApproved: isApproved))
                        .font(.subheadline)
                        .foregroundColor(statusColor(restaurant: restaurant, isApproved: isApproved))
                }

                Spacer()

                Toggle("", isOn: Binding(
                    // Force the toggle visually off while unapproved so it can
                    // never read "open" before review, regardless of the
                    // restaurant's stored is_open flag.
                    get: { isApproved && restaurant.isOpen },
                    set: { newValue in
                        Task { await vm.setRestaurantOpen(newValue) }
                    }
                ))
                .tint(.kePrimary)
                .labelsHidden()
                .disabled(!isApproved || vm.isTogglingOpen)
            }

            if !isApproved {
                Text("We'll email you once the platform admin reviews your application. You can edit your menu and settings while you wait.")
                    .font(.caption)
                    .foregroundColor(.keTextMuted)
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }

    private func statusLabel(restaurant: Restaurant, isApproved: Bool) -> String {
        if !isApproved { return "Pending approval" }
        return restaurant.isOpen ? "Currently Open" : "Currently Closed"
    }

    private func statusColor(restaurant: Restaurant, isApproved: Bool) -> Color {
        if !isApproved { return .keWarning }
        return restaurant.isOpen ? .keSuccess : .keTextMuted
    }

    // MARK: - Stats Grid

    private var statsGrid: some View {
        // 4 columns on iPad (regular size class) so stats cards breathe,
        // 2 columns on iPhone where they'd be too narrow otherwise.
        let columns: [GridItem] = SizeClass.isRegular(h: sizeClass)
            ? Array(repeating: GridItem(.flexible(), spacing: 12), count: 4)
            : Array(repeating: GridItem(.flexible(), spacing: 12), count: 2)

        return LazyVGrid(columns: columns, spacing: 12) {
            StatCard(
                title: "Active Orders",
                value: "\(vm.stats.activeOrders)",
                icon: "flame.fill",
                iconColor: .kePrimary
            )

            StatCard(
                title: "Today's Orders",
                value: "\(vm.stats.todayOrders)",
                icon: "bag.fill",
                iconColor: .keSuccess
            )

            StatCard(
                title: "Today's Revenue",
                // Backend returns cents; divide for display.
                value: CurrencyFormat.string(fromCents: vm.stats.todayRevenue),
                icon: "dollarsign.circle.fill",
                iconColor: .keWarning
            )

            StatCard(
                title: "Delivery Earnings",
                // Seller's 50% of delivery fees on self-delivered orders today.
                value: CurrencyFormat.string(fromCents: vm.stats.todayDeliveryEarnings),
                icon: "car.circle.fill",
                iconColor: .keSuccess
            )

            StatCard(
                title: "Avg Prep Time",
                value: String(format: "%.0f min", vm.stats.avgPrepTime),
                icon: "clock.fill",
                iconColor: .keTextSecondary
            )

            // 6th cell: the restaurant's default delivery method for NEW orders
            // (existing orders keep their mode). The active mode is filled green
            // so the seller sees the current method at a glance.
            if let restaurant = vm.restaurant {
                deliveryModeTile(restaurant)
            }
        }
    }

    // MARK: - Delivery Method

    private func deliveryModeTile(_ restaurant: Restaurant) -> some View {
        let mode = restaurant.deliveryMode == "restaurant" ? "restaurant" : "external"
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "car.fill")
                    .font(.title3)
                    .foregroundColor(.kePrimary)
                Spacer()
            }
            VStack(spacing: 6) {
                deliveryModePill("Uber Direct", value: "external", selected: mode == "external")
                deliveryModePill("Self-delivery", value: "restaurant", selected: mode == "restaurant")
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.keCard)
        .cornerRadius(16)
    }

    private func deliveryModePill(_ title: String, value: String, selected: Bool) -> some View {
        Button {
            guard !vm.isTogglingDeliveryMode else { return }
            Task { await vm.setRestaurantDeliveryMode(value) }
        } label: {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundColor(selected ? .white : .keTextSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(selected ? Color.keSuccess : Color.keBorder.opacity(0.4))
                .cornerRadius(8)
        }
        .buttonStyle(.plain)
        .disabled(vm.isTogglingDeliveryMode)
    }

    // MARK: - Active Orders

    private var activeOrdersSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Active Orders")
                    .font(.title3.bold())
                    .foregroundColor(.keTextPrimary)

                Spacer()

                if !vm.activeOrders.isEmpty {
                    Text("\(vm.activeOrders.count)")
                        .font(.caption.bold())
                        .foregroundColor(.keTextOnAccent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.kePrimary)
                        .cornerRadius(8)
                }
            }

            if vm.activeOrders.isEmpty {
                emptyActiveOrders
            } else {
                ForEach(vm.activeOrders) { order in
                    NavigationLink(destination: SellerOrderDetailView(vm: vm.sharedOrdersVM, order: order)
                        .onDisappear { Task { await vm.load() } }
                    ) {
                        ActiveOrderCard(order: order)
                    }
                    .buttonStyle(.plain)
                }

                // The server-side active count is authoritative. If it exceeds
                // the number of orders we fetched (limit 100) and filtered to
                // active statuses, the badge/list would silently undercount
                // versus the stat card above — so surface a "Showing X of Y"
                // row pointing the seller at the Orders tab for the full list.
                // Mirrors Android DashboardScreen.kt's truncation card.
                if !vm.isLoading && vm.stats.activeOrders > vm.activeOrders.count {
                    truncationNotice
                }
            }
        }
    }

    private var truncationNotice: some View {
        // Tapping the row jumps to the Orders tab (Android "View all" parity).
        // selectedTab lives on MainTabView and isn't reachable from here, so we
        // post `.switchToOrdersTabRequested`, which MainTabView observes.
        Button {
            Haptics.impact(.light)
            NotificationCenter.default.post(name: .switchToOrdersTabRequested, object: nil)
        } label: {
            HStack {
                Text("Showing \(vm.activeOrders.count) of \(vm.stats.activeOrders) active orders")
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)

                Spacer()

                Text("See Orders tab")
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.kePrimary)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.kePrimary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(Color.keCard)
            .cornerRadius(16)
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens the Orders tab to see all active orders")
    }

    private var emptyActiveOrders: some View {
        VStack(spacing: 12) {
            Image(systemName: "tray")
                .font(.system(size: 40))
                .foregroundColor(.keTextMuted)

            Text("No active orders")
                .font(.subheadline)
                .foregroundColor(.keTextMuted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .background(Color.keCard)
        .cornerRadius(16)
    }
}

// MARK: - Stat Card

struct StatCard: View {
    let title: String
    let value: String
    let icon: String
    let iconColor: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: icon)
                    .font(.title3)
                    .foregroundColor(iconColor)
                Spacer()
            }

            Text(value)
                .font(.title2.bold())
                .foregroundColor(.keTextPrimary)

            Text(title)
                .font(.caption)
                .foregroundColor(.keTextSecondary)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }
}
