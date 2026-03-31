import SwiftUI

struct DashboardView: View {
    @StateObject private var vm = DashboardViewModel()

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        // Restaurant Status
                        if let restaurant = vm.restaurant {
                            restaurantStatusCard(restaurant)
                        }

                        // Stats Grid
                        statsGrid

                        // Active Orders
                        activeOrdersSection
                    }
                    .padding()
                }
            }
            .navigationTitle("Dashboard")
            .navigationBarTitleDisplayMode(.large)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .refreshable {
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
                set: { _ in
                    Task { await vm.toggleRestaurantOpen() }
                }
            ))
            .tint(.kePrimary)
            .labelsHidden()
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }

    // MARK: - Stats Grid

    private var statsGrid: some View {
        LazyVGrid(columns: [
            GridItem(.flexible(), spacing: 12),
            GridItem(.flexible(), spacing: 12)
        ], spacing: 12) {
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
                value: String(format: "$%.2f", vm.stats.todayRevenue),
                icon: "dollarsign.circle.fill",
                iconColor: .keWarning
            )

            StatCard(
                title: "Avg Prep Time",
                value: "\(vm.stats.avgPrepTime) min",
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
                        .foregroundColor(.white)
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
                    NavigationLink(destination: SellerOrderDetailView(order: order)) {
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
