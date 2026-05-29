import SwiftUI
import MapKit

// OrderTrackingView is the "where's my food" screen consumers open from an
// active order. Shows a MapKit map with three pins (restaurant, courier,
// delivery address) plus a status header and a courier info card, while the
// view model owns the polling and SSE location stream lifecycle.
struct OrderTrackingView: View {
    let orderId: String
    @StateObject private var vm: OrderTrackingViewModel

    @State private var cameraPosition: MapCameraPosition = .automatic
    // Flip true once we've fit the camera to the order's pins. Prevents the
    // streaming courier position (arrives every ~1s via SSE) from constantly
    // yanking the camera away from wherever the user has panned to.
    @State private var hasFitCamera = false

    // Driven by onChange of order.status → .delivered. Tracked locally so
    // the user can dismiss/skip without triggering a re-present on the
    // next poll tick that also reports the terminal state.
    @State private var showRatingSheet = false
    @State private var ratingPrompted = false

    init(orderId: String) {
        self.orderId = orderId
        _vm = StateObject(wrappedValue: OrderTrackingViewModel(orderID: orderId))
    }

    var body: some View {
        VStack(spacing: 0) {
            if let order = vm.order {
                map(for: order)
                    .frame(maxWidth: .infinity)
                    .frame(height: 420)

                statusHeader(for: order)

                ScrollView {
                    VStack(spacing: Theme.spacingMD) {
                        if let courier = order.courier {
                            courierCard(courier)
                        }
                        addressCard(for: order)
                    }
                    .padding(Theme.spacingMD)
                }
            } else if vm.errorMessage != nil {
                VStack(spacing: Theme.spacingMD) {
                    Image(systemName: "wifi.exclamationmark")
                        .font(.system(size: 40))
                        .foregroundColor(.keTextSecondary)
                    Text("Couldn't load order")
                        .font(.title3.bold())
                        .foregroundColor(.keTextPrimary)
                    Button("Retry") {
                        Task { await vm.refresh() }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.kePrimary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView()
                    .tint(.kePrimary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .overlay(alignment: .top) {
            if vm.order != nil, vm.errorMessage != nil {
                HStack(spacing: Theme.spacingSM) {
                    Image(systemName: "wifi.exclamationmark").font(.caption)
                    Text("Unable to update — showing last known status").font(.caption)
                }
                .foregroundColor(.keTextOnAccent)
                .padding(.horizontal, Theme.spacingMD)
                .padding(.vertical, Theme.spacingSM)
                .background(Color.keWarning.opacity(0.9))
                .cornerRadius(Theme.cornerRadiusMedium)
                .padding(.top, Theme.spacingSM)
            }
        }
        .background(Color.keBackground.ignoresSafeArea())
        .navigationTitle("Tracking")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: orderId) {
            await vm.start()
        }
        .onDisappear {
            vm.stop()
        }
        .refreshable {
            Haptics.impact(.light)
            await vm.refresh()
        }
        .onChange(of: vm.order?.status) { _, newStatus in
            maybePromptForRating(newStatus: newStatus)
        }
        .sheet(isPresented: $showRatingSheet) {
            if let o = vm.order, let courier = o.courier {
                CourierRatingSheet(
                    orderId: o.id,
                    courierFirstName: courier.firstName,
                    onSubmitted: { stars in
                        // Reflect the submitted rating locally so the poll
                        // loop doesn't re-prompt; the next GetOrder response
                        // will also carry it, but this keeps the UI honest
                        // while that's in flight.
                        vm.markCourierRatingSubmitted(stars: stars)
                        showRatingSheet = false
                    },
                    onDismiss: {
                        showRatingSheet = false
                    },
                )
            }
        }
    }

    /// Opens the rating prompt once, the moment a delivered order is
    /// observed with a courier attached and no prior rating on file.
    private func maybePromptForRating(newStatus: OrderStatus?) {
        guard !ratingPrompted,
              newStatus == .delivered,
              let o = vm.order,
              o.courier != nil,
              o.courierRating == nil else {
            return
        }
        ratingPrompted = true
        showRatingSheet = true
    }

    // MARK: - Map

    @MapContentBuilder
    private func annotations(for order: Order) -> some MapContent {
        if let lat = order.restaurantLat, let lng = order.restaurantLng {
            Annotation("Restaurant", coordinate: .init(latitude: lat, longitude: lng)) {
                MapPin(symbol: "fork.knife", color: .kePrimary)
            }
        }
        Annotation("Delivery", coordinate: .init(latitude: order.deliveryLat, longitude: order.deliveryLng)) {
            MapPin(symbol: "house.fill", color: .keSuccess)
        }
        if let c = order.courier, c.lat != 0, c.lng != 0 {
            Annotation("Courier", coordinate: .init(latitude: c.lat, longitude: c.lng)) {
                MapPin(symbol: "car.fill", color: .white, background: .black)
            }
        }
    }

    private func map(for order: Order) -> some View {
        Map(position: $cameraPosition) {
            annotations(for: order)
        }
        .mapStyle(.standard(elevation: .realistic))
        .onAppear { fitCameraIfNeeded(for: order) }
        .onChange(of: order.id) { _, _ in
            hasFitCamera = false
            fitCameraIfNeeded(for: order)
        }
    }

    /// Fits the camera around the order's known pins (restaurant, delivery,
    /// courier if available) the first time we render with a loaded order.
    /// The default `.automatic` camera position doesn't re-fit when content
    /// arrives after first render, so an order that loads asynchronously
    /// leaves the map on the global fallback until the user pans.
    private func fitCameraIfNeeded(for order: Order) {
        guard !hasFitCamera else { return }
        var coords: [CLLocationCoordinate2D] = [
            CLLocationCoordinate2D(latitude: order.deliveryLat, longitude: order.deliveryLng)
        ]
        if let lat = order.restaurantLat, let lng = order.restaurantLng {
            coords.append(CLLocationCoordinate2D(latitude: lat, longitude: lng))
        }
        if let c = order.courier, c.lat != 0, c.lng != 0 {
            coords.append(CLLocationCoordinate2D(latitude: c.lat, longitude: c.lng))
        }
        guard let region = Self.boundingRegion(for: coords) else { return }
        cameraPosition = .region(region)
        hasFitCamera = true
    }

    private static func boundingRegion(for coords: [CLLocationCoordinate2D]) -> MKCoordinateRegion? {
        guard let first = coords.first else { return nil }
        if coords.count == 1 {
            return MKCoordinateRegion(center: first, latitudinalMeters: 2000, longitudinalMeters: 2000)
        }
        let lats = coords.map(\.latitude)
        let lngs = coords.map(\.longitude)
        guard let minLat = lats.min(),
              let maxLat = lats.max(),
              let minLng = lngs.min(),
              let maxLng = lngs.max() else {
            return MKCoordinateRegion(center: first, latitudinalMeters: 2000, longitudinalMeters: 2000)
        }
        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLng + maxLng) / 2
        )
        let span = MKCoordinateSpan(
            latitudeDelta: max((maxLat - minLat) * 1.5, 0.01),
            longitudeDelta: max((maxLng - minLng) * 1.5, 0.01)
        )
        return MKCoordinateRegion(center: center, span: span)
    }

    // MARK: - Status header

    private func statusHeader(for order: Order) -> some View {
        VStack(spacing: Theme.spacingXS) {
            Text(phaseText(for: order.status))
                .font(.title3.bold())
                .foregroundColor(.keTextPrimary)
            Text(phaseSubtext(for: order.status))
                .font(.caption)
                .foregroundColor(.keTextSecondary)

            progressBar(for: order.status)
                .padding(.top, Theme.spacingSM)
        }
        .padding(Theme.spacingMD)
        .frame(maxWidth: .infinity)
        .background(Color.keBackgroundElevated)
    }

    private func phaseText(for status: OrderStatus) -> String {
        switch status {
        case .scheduled: return "Scheduled for later"
        case .pending: return "Waiting for the restaurant"
        case .accepted: return "Restaurant accepted your order"
        case .preparing: return "Your food is being prepared"
        case .ready: return "Waiting for a courier"
        case .pickedUp: return "Your order is on the way"
        case .delivered: return "Delivered — enjoy!"
        case .cancelled, .rejected:
            return "Order was " + status.displayName.lowercased()
        case .completed:
            return "Order completed"
        case .unknown:
            return "Processing your order"
        }
    }

    private func phaseSubtext(for status: OrderStatus) -> String {
        switch status {
        case .scheduled:
            return "Your order is queued and will move into the kitchen closer to the scheduled time."
        case .pending:
            return "The restaurant is reviewing the order and will confirm it shortly."
        case .accepted:
            return "The kitchen has the order and will begin preparing it."
        case .preparing:
            return "Your meal is being cooked and packed for pickup."
        case .ready:
            return "The order is ready and we're matching it with a courier."
        case .pickedUp:
            return "Your courier has the order and is heading to your delivery address."
        case .delivered:
            return "The dropoff is complete."
        case .completed:
            return "This order has been closed."
        case .cancelled:
            return "The order will not be fulfilled."
        case .rejected:
            return "The restaurant could not accept this order."
        case .unknown:
            return "We're working on your order"
        }
    }
    /// 6-step timeline stepper: Ordered → Accepted → Preparing → Ready → En route → Delivered.
    /// Completed steps show a filled kePrimary circle with a check, the active
    /// step pulses to signal "this is where you are right now", and future
    /// steps are muted. Connector lines between steps track fill proportionally.
    private func progressBar(for status: OrderStatus) -> some View {
        DeliveryTimeline(stepIndex: status.stepIndex)
    }

    // MARK: - Cards

    private func courierCard(_ courier: CourierPublic) -> some View {
        HStack(spacing: Theme.spacingMD) {
            Circle()
                .fill(Color.keCardHover)
                .frame(width: 52, height: 52)
                .overlay(
                    Text(String(courier.firstName.prefix(1)))
                        .font(.title3.bold())
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
                }
                Text(courier.vehicleSummary)
                    .font(.caption)
                    .foregroundColor(.keTextTertiary)
            }

            Spacer()

            HStack(spacing: 8) {
                // Message the courier via order-scoped chat.
                NavigationLink(destination: OrderChatView(orderID: orderId)) {
                    Image(systemName: "bubble.left.fill")
                        .foregroundColor(.kePrimary)
                        .padding(10)
                        .background(Color.keCardHover)
                        .clipShape(Circle())
                }

                if let url = URL(string: "tel:\(courier.phone.filter { $0.isNumber || $0 == "+" })") {
                    Link(destination: url) {
                        Image(systemName: "phone.fill")
                            .foregroundColor(.kePrimary)
                            .padding(10)
                            .background(Color.keCardHover)
                            .clipShape(Circle())
                    }
                }
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private func addressCard(for order: Order) -> some View {
        HStack(alignment: .top, spacing: Theme.spacingSM) {
            Image(systemName: "house.fill")
                .foregroundColor(.kePrimary)
            VStack(alignment: .leading, spacing: 2) {
                Text("Delivering to")
                    .font(.caption)
                    .foregroundColor(.keTextTertiary)
                Text(order.deliveryAddress)
                    .foregroundColor(.keTextPrimary)
            }
            Spacer()
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

}

// A small reusable map pin. SwiftUI's Map uses Annotation for custom content.
struct MapPin: View {
    let symbol: String
    var color: Color = .kePrimary
    var background: Color = .kePrimary

    var body: some View {
        ZStack {
            Circle()
                .fill(background)
                .frame(width: 36, height: 36)
                .shadow(radius: 4)
            Image(systemName: symbol)
                .foregroundColor(color)
                .font(.system(size: 16, weight: .bold))
        }
    }
}

// MARK: - Delivery timeline

/// Visual progress stepper for an order. Six steps, matching
/// OrderStatus.stepIndex. The active step (equal to stepIndex) pulses; prior
/// steps are filled with a check; future steps are empty rings.
struct DeliveryTimeline: View {
    let stepIndex: Int
    @State private var pulse = false

    private struct Step {
        let label: String
        let icon: String
    }

    private let steps: [Step] = [
        .init(label: "Ordered",   icon: "bag.fill"),
        .init(label: "Accepted",  icon: "checkmark"),
        .init(label: "Preparing", icon: "flame.fill"),
        .init(label: "Ready",     icon: "takeoutbag.and.cup.and.straw.fill"),
        .init(label: "En route",  icon: "bicycle"),
        .init(label: "Delivered", icon: "house.fill"),
    ]

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 0) {
                ForEach(Array(steps.enumerated()), id: \.offset) { (i, step) in
                    node(for: i, icon: step.icon)
                    if i < steps.count - 1 {
                        connector(leftIndex: i)
                    }
                }
            }

            HStack(spacing: 0) {
                ForEach(Array(steps.enumerated()), id: \.offset) { (i, step) in
                    Text(step.label)
                        .font(.system(.caption2, weight: i == stepIndex ? .bold : .regular))
                        .foregroundColor(i <= stepIndex ? .keTextPrimary : .keTextMuted)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .padding(.horizontal, 4)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }

    @ViewBuilder
    private func node(for index: Int, icon: String) -> some View {
        let isDone = index < stepIndex
        let isActive = index == stepIndex

        ZStack {
            Circle()
                .fill(isDone || isActive ? Color.kePrimary : Color.keCard)
                .frame(width: 22, height: 22)

            if isDone {
                Image(systemName: "checkmark")
                    .font(.system(.caption, weight: .bold))
                    .foregroundColor(.keTextOnAccent)
            } else if isActive {
                Image(systemName: icon)
                    .font(.system(.caption2, weight: .bold))
                    .foregroundColor(.keTextOnAccent)
            }

            if isActive {
                Circle()
                    .stroke(Color.kePrimary.opacity(pulse ? 0.0 : 0.5), lineWidth: 2)
                    .frame(width: pulse ? 34 : 22, height: pulse ? 34 : 22)
            }
        }
    }

    private func connector(leftIndex: Int) -> some View {
        // Fill the connector to the right of step `leftIndex` if the order
        // has already advanced past that step.
        let filled = leftIndex < stepIndex
        return Rectangle()
            .fill(filled ? Color.kePrimary : Color.keCard)
            .frame(height: 2)
            .frame(maxWidth: .infinity)
    }
}
