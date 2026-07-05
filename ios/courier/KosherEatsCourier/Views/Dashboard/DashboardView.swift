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
    @State private var showProfileSheet = false
    // @State (not @AppStorage) on purpose: dismissal lasts for the session
    // and self-re-arms on logout because the view tree is destroyed.
    @State private var profileBannerDismissed = false

    enum Tab { case deliveries, earnings, profile }

    var body: some View {
        TabView(selection: $selectedTab) {
            deliveriesTab
                .tabItem { Label("Deliveries", systemImage: "shippingbox.fill") }
                .tag(Tab.deliveries)

            EarningsView()
                .environmentObject(vm)
                .tabItem { Label("Earnings", systemImage: "dollarsign.circle.fill") }
                .tag(Tab.earnings)

            CourierProfileView()
                .tabItem { Label("Profile", systemImage: "person.crop.circle.fill") }
                .tag(Tab.profile)
        }
        .tint(.kePrimary)
        .task {
            location.requestPermission()
            await vm.refresh()
            await vm.resumeIfActive(location: location)
            await vm.loadTodayEarnings()
        }
        .onChange(of: vm.forceLogout) { _, shouldLogout in
            if shouldLogout { auth.logout() }
        }
        .onChange(of: location.needsReauth) { _, needs in
            if needs { auth.logout() }
        }
    }

    private var deliveriesTab: some View {
        NavigationStack {
            Group {
                if let active = vm.active.first {
                    DeliveryMapView(order: active, vm: vm, location: location)
                } else {
                    ScrollView {
                        VStack(spacing: Theme.spacingMD) {
                            // Non-blocking nudge to finish the profile —
                            // user-initiated and dismissible per App Review
                            // Guideline 4 (never a forced sheet).
                            if auth.shouldOfferProfileCompletion && !profileBannerDismissed {
                                profileCompletionBanner
                            }

                            if location.permissionDenied {
                                locationPermissionDeniedBanner
                            }

                            if location.locationUpdateFailing {
                                locationFailingBanner
                            }

                            // Payouts-not-ready nag: without a Stripe Connect
                                // account, completed deliveries queue up in the
                                // payout retry table and the courier doesn't
                                // see a dime. Surface this at the top of the
                                // dashboard so nobody drives for hours wondering
                                // why "Earnings" went up but their bank account
                                // didn't.
                            if let p = auth.profile, !p.payoutReady {
                                payoutReminderBanner
                            }

                            OnlineToggleCard(vm: vm, location: location)

                            if let msg = vm.errorMessage, !msg.isEmpty {
                                HStack(spacing: 8) {
                                    Image(systemName: "exclamationmark.circle.fill")
                                        .foregroundColor(.keWarning)
                                    Text(msg)
                                        .font(.subheadline)
                                        .foregroundColor(.keTextPrimary)
                                    Spacer()
                                    Button("Dismiss") { vm.errorMessage = nil }
                                        .font(.caption.bold())
                                        .foregroundColor(.kePrimary)
                                }
                                .padding()
                                .background(Color.keWarning.opacity(0.12))
                                .cornerRadius(Theme.cornerRadiusMedium)
                            }

                            if vm.todayEarnings > 0 {
                                todayEarningsPill
                            }

                            if vm.isOnline {
                                availableSection
                            } else {
                                offlineHero
                            }
                        }
                        .padding(Theme.spacingMD)
                    }
                    .refreshable {
                        await vm.refresh()
                        await auth.loadProfile()
                    }
                }
            }
            .background(Color.keBackground.ignoresSafeArea())
            .navigationTitle("KosherEats Courier")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showProfileSheet) {
                ProfileCompletionSheet()
                    .environmentObject(auth)
                    .presentationDetents([.medium, .large])
            }
        }
    }

    // MARK: - Profile Completion Banner

    // Non-blocking nudge: opens the optional ProfileCompletionSheet only when
    // the courier taps it, and can be dismissed outright — App Review
    // Guideline 4 forbids demanding name/email after Sign in with Apple.
    private var profileCompletionBanner: some View {
        HStack(spacing: Theme.spacingSM) {
            Button {
                showProfileSheet = true
            } label: {
                HStack(spacing: Theme.spacingSM) {
                    Image(systemName: "person.crop.circle.badge.exclamationmark")
                        .font(.title3)
                        .foregroundColor(.kePrimary)

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Complete your profile")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(.keTextPrimary)

                        Text("Add your name and a contact email for payout and delivery updates.")
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
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private var locationPermissionDeniedBanner: some View {
        Button {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        } label: {
            HStack {
                Image(systemName: "location.slash.fill")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Location access denied")
                        .font(.subheadline.bold())
                    Text("Tap to open Settings and enable location.")
                        .font(.caption)
                }
                Spacer()
                Image(systemName: "chevron.right")
            }
            .foregroundColor(.keTextOnAccent)
            .padding()
            .background(Color.red.cornerRadius(8))
            .padding(.horizontal)
        }
    }

    private var locationFailingBanner: some View {
        HStack {
            Image(systemName: "location.slash.fill")
            Text("Location updates failing \u{2014} customers can\u{2019}t track you")
        }
        .foregroundColor(.keTextOnAccent)
        .padding()
        .background(Color.red.cornerRadius(8))
        .padding(.horizontal)
    }

    private var payoutReminderBanner: some View {
        NavigationLink(destination: PayoutsSetupView()) {
            HStack(spacing: Theme.spacingSM) {
                Image(systemName: "dollarsign.circle.fill")
                    .font(.title2)
                    .foregroundColor(.kePrimary)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Set up direct deposit")
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextPrimary)
                    Text("Link your bank with Stripe so you can get paid.")
                        .font(.caption)
                        .foregroundColor(.keTextSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundColor(.keTextTertiary)
            }
            .padding()
            .background(Color.kePrimary.opacity(0.12))
            .overlay(
                RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                    .stroke(Color.kePrimary.opacity(0.35), lineWidth: 1)
            )
            .cornerRadius(Theme.cornerRadiusMedium)
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
                    .disabled(vm.isClaiming)
                    .opacity(vm.isClaiming ? 0.6 : 1)
                }
            }

            // Coming up — orders the seller is preparing but hasn't yet
            // marked ready. Couriers can't claim these (UpcomingDeliveryCard
            // omits the claim button + the backend rejects), but seeing them
            // lets a courier head toward the restaurant ahead of the kitchen
            // finishing.
            if !vm.upcoming.isEmpty {
                Text("Coming up")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                    .padding(.top, Theme.spacingMD)
                ForEach(vm.upcoming) { d in
                    UpcomingDeliveryCard(delivery: d, currentLocation: location.currentLocation)
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

    private var todayEarningsPill: some View {
        HStack {
            Image(systemName: "dollarsign.circle.fill")
                .foregroundColor(.keSuccess)
            Text("Today")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
            Spacer()
            Text(String(format: "$%.2f", Double(vm.todayEarnings) / 100))
                .font(.title3.bold())
                .foregroundColor(.keTextPrimary)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
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
                set: { _ in Task { await vm.toggleOnline(location: location) } }
            ))
            .labelsHidden()
            .tint(.kePrimary)
            .disabled(vm.isTogglingOnline)
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
            HStack(alignment: .firstTextBaseline) {
                // Courier-facing payout = base delivery fee + 100% of the
                // customer's tip. We show the combined number up front as
                // the headline amount, then break it down below so couriers
                // on the road can eyeball each job's payout at a glance.
                Text("$\(String(format: "%.2f", Double(delivery.deliveryFee + delivery.courierTip) / 100))")
                    .font(.largeTitle.bold())
                    .foregroundColor(.kePrimary)
                if delivery.courierTip > 0 {
                    Text("incl. tip")
                        .font(.caption.bold())
                        .foregroundColor(.keSuccess)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.keSuccess.opacity(0.15))
                        .cornerRadius(4)
                }
                Spacer()
                if let pickupDistance = distanceFromMe {
                    Label(String(format: "%.1f mi", pickupDistance),
                          systemImage: "location.fill")
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextPrimary)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Label(delivery.restaurantName, systemImage: "fork.knife")
                    .font(.title3.bold())
                    .foregroundColor(.keTextPrimary)
                Label(delivery.deliveryAddress, systemImage: "house.fill")
                    .font(.body)
                    .foregroundColor(.keTextPrimary)
                    .lineLimit(2)
            }

            Button("Accept") { Haptics.impact(.medium); onAccept() }
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

// MARK: - Upcoming delivery card

/// Same shape as AvailableDeliveryCard but no Accept button (claim endpoint
/// rejects until status='ready') and no delivery address (the courier hasn't
/// claimed yet, so we don't expose customer-side data). Status badge tells
/// the courier why this isn't claimable.
struct UpcomingDeliveryCard: View {
    let delivery: AvailableDelivery
    let currentLocation: CLLocation?

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            HStack(alignment: .firstTextBaseline) {
                Text("$\(String(format: "%.2f", Double(delivery.deliveryFee + delivery.courierTip) / 100))")
                    .font(.title2.bold())
                    .foregroundColor(.keTextSecondary)
                Text(statusLabel)
                    .font(.caption.bold())
                    .foregroundColor(.kePrimary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Color.kePrimary.opacity(0.15))
                    .cornerRadius(6)
                Spacer()
                if let pickupDistance = distanceFromMe {
                    Label(String(format: "%.1f mi", pickupDistance),
                          systemImage: "location.fill")
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextSecondary)
                }
            }

            Label(delivery.restaurantName, systemImage: "fork.knife")
                .font(.title3.bold())
                .foregroundColor(.keTextPrimary)

            Text("Available to claim once the restaurant marks it ready.")
                .font(.caption)
                .foregroundColor(.keTextMuted)
        }
        .padding()
        .background(Color.keCard.opacity(0.55))
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private var statusLabel: String {
        switch delivery.status {
        case "accepted": return "JUST ACCEPTED"
        case "preparing": return "PREPARING"
        default: return delivery.status.uppercased()
        }
    }

    private var distanceFromMe: Double? {
        guard let me = currentLocation else { return nil }
        let pickup = CLLocation(latitude: delivery.restaurantLat, longitude: delivery.restaurantLng)
        return me.distance(from: pickup) / 1609.34
    }
}

