import SwiftUI

struct DashboardView: View {
    @StateObject private var vm = DashboardViewModel()
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var showingPicker = false
    @State private var pickerSelectionID: String? = SelectedRestaurant.shared.id

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
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
                        Task { await vm.load() }
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
        }
    }

    // MARK: - Restaurant Status

    private func restaurantStatusCard(_ restaurant: Restaurant) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(restaurant.name)
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)

                Text(restaurant.isOpen ? "Currently Open" : "Currently Closed")
                    .font(.subheadline)
                    .foregroundColor(restaurant.isOpen ? .keSuccess : .keTextMuted)
            }

            Spacer()

            Toggle("", isOn: Binding(
                get: { restaurant.isOpen },
                set: { newValue in
                    Task { await vm.setRestaurantOpen(newValue) }
                }
            ))
            .tint(.kePrimary)
            .labelsHidden()
            .disabled(vm.isTogglingOpen)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
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
                title: "Avg Prep Time",
                value: String(format: "%.0f min", vm.stats.avgPrepTime),
                icon: "clock.fill",
                iconColor: .keTextSecondary
            )
        }
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
                    NavigationLink(destination: SellerOrderDetailView(vm: vm.sharedOrdersVM, order: order)) {
                        ActiveOrderCard(order: order)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
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
