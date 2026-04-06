import SwiftUI
import CoreLocation

// DashboardView is the "working courier" home. Mirrors the Uber Driver / Dasher
// home screen: big online/offline toggle at top, list of available deliveries
// below, and any active delivery takes over the whole screen.
struct DashboardView: View {
    @EnvironmentObject var auth: AuthViewModel
    @StateObject private var vm = DashboardViewModel()
    @StateObject private var location = LocationManager()
    @State private var selectedTab: Tab = .deliveries

    enum Tab { case deliveries, earnings, profile }

    var body: some View {
        TabView(selection: $selectedTab) {
            deliveriesTab
                .tabItem { Label("Deliveries", systemImage: "shippingbox.fill") }
                .tag(Tab.deliveries)

            EarningsView()
                .tabItem { Label("Earnings", systemImage: "dollarsign.circle.fill") }
                .tag(Tab.earnings)

            CourierProfileView()
                .tabItem { Label("Profile", systemImage: "person.crop.circle.fill") }
                .tag(Tab.profile)
        }
        .tint(.kePrimary)
        .task {
            location.requestPermission()
            location.startTracking()
            await vm.refresh()
        }
    }

    private var deliveriesTab: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Theme.spacingMD) {
                    OnlineToggleCard(vm: vm, location: location)

                    if let active = vm.active.first {
                        ActiveDeliveryCard(order: active, vm: vm)
                    } else if vm.isOnline {
                        availableSection
                    } else {
                        offlineHero
                    }
                }
                .padding(Theme.spacingMD)
            }
            .background(Color.keBackground.ignoresSafeArea())
            .navigationTitle("KosherEats Driver")
            .navigationBarTitleDisplayMode(.inline)
            .refreshable { await vm.refresh() }
        }
    }

    private var availableSection: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            HStack {
                Text("Available nearby")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                Spacer()
                if vm.isLoading { ProgressView().tint(.kePrimary) }
            }

            if vm.available.isEmpty {
                VStack(spacing: Theme.spacingSM) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 36))
                        .foregroundColor(.keTextTertiary)
                    Text("No deliveries right now")
                        .foregroundColor(.keTextSecondary)
                    Text("You'll be notified when a new order is ready.")
                        .font(.caption)
                        .foregroundColor(.keTextMuted)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, Theme.spacingXL)
            } else {
                ForEach(vm.available) { d in
                    AvailableDeliveryCard(delivery: d, currentLocation: location.currentLocation) {
                        Task { await vm.claim(d) }
                    }
                }
            }
        }
    }

    private var offlineHero: some View {
        VStack(spacing: Theme.spacingMD) {
            Image(systemName: "moon.zzz.fill")
                .font(.system(size: 56))
                .foregroundColor(.keTextMuted)
            Text("You're offline")
                .font(.title3.bold())
                .foregroundColor(.keTextPrimary)
            Text("Tap the toggle above to start receiving deliveries.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical, Theme.spacingXL)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Online toggle

private struct OnlineToggleCard: View {
    @ObservedObject var vm: DashboardViewModel
    @ObservedObject var location: LocationManager

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(vm.isOnline ? "Online" : "Offline")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                Text(vm.isOnline ? "You're receiving delivery requests" : "Go online to start earning")
                    .font(.caption)
                    .foregroundColor(.keTextTertiary)
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { vm.isOnline },
                set: { _ in Task { await vm.toggleOnline(location: location.currentLocation) } }
            ))
            .labelsHidden()
            .tint(.kePrimary)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }
}

// MARK: - Available delivery card

struct AvailableDeliveryCard: View {
    let delivery: AvailableDelivery
    let currentLocation: CLLocation?
    let onAccept: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            HStack {
                Text("$\(String(format: "%.2f", Double(delivery.deliveryFee) / 100))")
                    .font(.title2.bold())
                    .foregroundColor(.kePrimary)
                Spacer()
                if let pickupDistance = distanceFromMe {
                    Label(String(format: "%.1f mi", pickupDistance),
                          systemImage: "location.fill")
                        .font(.caption)
                        .foregroundColor(.keTextTertiary)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                Label(delivery.restaurantName, systemImage: "fork.knife")
                    .foregroundColor(.keTextPrimary)
                Label(delivery.deliveryAddress, systemImage: "house.fill")
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
                    .lineLimit(1)
            }

            Button("Accept") { onAccept() }
                .buttonStyle(KEPrimaryButtonStyle())
                .padding(.top, Theme.spacingSM)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private var distanceFromMe: Double? {
        guard let me = currentLocation else { return nil }
        let pickup = CLLocation(latitude: delivery.restaurantLat, longitude: delivery.restaurantLng)
        return me.distance(from: pickup) / 1609.34 // meters -> miles
    }
}

// MARK: - Active delivery card

struct ActiveDeliveryCard: View {
    let order: CourierOrder
    @ObservedObject var vm: DashboardViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingMD) {
            Text(currentPhase)
                .font(.caption.bold())
                .foregroundColor(.kePrimary)
                .textCase(.uppercase)

            VStack(alignment: .leading, spacing: Theme.spacingSM) {
                Label {
                    VStack(alignment: .leading) {
                        Text("Pickup").font(.caption).foregroundColor(.keTextTertiary)
                        Text(order.restaurantName).foregroundColor(.keTextPrimary)
                    }
                } icon: {
                    Image(systemName: "fork.knife").foregroundColor(.kePrimary)
                }

                Divider().background(Color.keDivider)

                Label {
                    VStack(alignment: .leading) {
                        Text("Dropoff").font(.caption).foregroundColor(.keTextTertiary)
                        Text(order.deliveryAddress).foregroundColor(.keTextPrimary)
                    }
                } icon: {
                    Image(systemName: "house.fill").foregroundColor(.kePrimary)
                }
            }

            HStack(spacing: 8) {
                // Chat with the customer + restaurant on this order.
                NavigationLink(destination: OrderChatView(orderID: order.id)) {
                    Image(systemName: "bubble.left.fill")
                        .foregroundColor(.kePrimary)
                        .frame(width: 48, height: 48)
                        .background(Color.keBackgroundElevated)
                        .cornerRadius(12)
                }

                Button(actionLabel) {
                    Task {
                        if order.status == "ready" {
                            await vm.pickup(order)
                        } else {
                            await vm.deliver(order)
                        }
                    }
                }
                .buttonStyle(KEPrimaryButtonStyle())
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private var currentPhase: String {
        order.status == "ready" ? "Heading to restaurant" : "Delivering"
    }

    private var actionLabel: String {
        order.status == "ready" ? "I've picked it up" : "Mark delivered"
    }
}
