import SwiftUI
import MapKit

// OrderTrackingView is the "where's my food" screen consumers open from an
// active order. Shows a MapKit map with three pins (restaurant, courier,
// delivery address) plus a status header and a courier info card, while the
// view model owns the polling and SSE location stream lifecycle.
struct OrderTrackingView: View {
    let orderId: String
    @StateObject private var vm: OrderTrackingViewModel
    @Environment(\.openURL) private var openURL

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
                if order.isExternalDelivery {
                    // No platform courier and no live location stream for
                    // third-party deliveries — show a simple "track in their
                    // app" card instead of the frozen map / "finding a courier".
                    externalDeliveryCard(for: order)

                    statusHeader(for: order)

                    ScrollView {
                        VStack(spacing: Theme.spacingMD) {
                            addressCard(for: order)
                        }
                        .padding(Theme.spacingMD)
                    }
                } else {
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
            if newStatus != nil, let order = vm.order {
                UIAccessibility.post(
                    notification: .announcement,
                    argument: phaseText(for: order)
                )
            }
        }
        .onChange(of: vm.order?.estDeliveryTime) { oldETA, newETA in
            guard let newETA, oldETA != nil, newETA != oldETA else { return }
            UIAccessibility.post(
                notification: .announcement,
                argument: "Estimated delivery updated to \(newETA.formatted(date: .omitted, time: .shortened))"
            )
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
        if order.deliveryLat != 0 || order.deliveryLng != 0 {
            Annotation("Delivery", coordinate: .init(latitude: order.deliveryLat, longitude: order.deliveryLng)) {
                MapPin(symbol: "house.fill", color: .keSuccess)
            }
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
        // Mirror the annotation guard: pickup orders carry deliveryLat/Lng of
        // (0,0), so seeding the bounding region with that coordinate would
        // span the map from the restaurant to Null Island. Only include the
        // delivery pin when it's a real coordinate.
        var coords: [CLLocationCoordinate2D] = []
        if order.deliveryLat != 0 || order.deliveryLng != 0 {
            coords.append(CLLocationCoordinate2D(latitude: order.deliveryLat, longitude: order.deliveryLng))
        }
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
            Text(phaseText(for: order))
                .font(.title3.bold())
                .foregroundColor(.keTextPrimary)
            Text(phaseSubtext(for: order))
                .font(.caption)
                .foregroundColor(.keTextSecondary)

            if order.status.isActive && order.status != .pending,
               let etaTime = sanitizedETA(order.estDeliveryTime) {
                let etaText = "ETA: \(etaTime.formatted(date: .omitted, time: .shortened))"
                Text(etaText)
                    .font(.subheadline.bold())
                    .foregroundColor(.kePrimary)
                    .accessibilityValue("Estimated \(isPickup(order) ? "pickup" : "delivery") at \(etaTime.formatted(date: .omitted, time: .shortened))")
            }

            // Cancelled/rejected orders have stepIndex -1, which would render an
            // all-empty 6-step skeleton; hide the timeline for terminal states
            // (matching OrderDetailView).
            if order.status.stepIndex >= 0 {
                progressBar(for: order)
                    .padding(.top, Theme.spacingSM)
            }
        }
        .padding(Theme.spacingMD)
        .frame(maxWidth: .infinity)
        .background(Color.keBackgroundElevated)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Order status: \(phaseText(for: order))")
    }

    private func isPickup(_ order: Order) -> Bool {
        order.fulfillmentType == "pickup"
    }

    /// Returns the ETA only when it's a plausible time. Guards against
    /// epoch-zero / stale garbage values, mirroring OrderConfirmationView.etaText.
    private func sanitizedETA(_ eta: Date) -> Date? {
        guard eta.timeIntervalSince1970 > 1,
              eta > Date().addingTimeInterval(-24 * 3600) else {
            return nil
        }
        return eta
    }

    private func phaseText(for order: Order) -> String {
        let status = order.status
        if order.isExternalDelivery {
            let provider = Self.providerName(for: order.externalProvider)
            switch status {
            case .ready: return "Handing off to \(provider)"
            case .pickedUp: return "On its way with \(provider)"
            default: break
            }
        } else if isPickup(order) {
            switch status {
            case .ready: return "Ready for pickup"
            case .pickedUp: return "Picked up"
            default: break
            }
        }
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

    private func phaseSubtext(for order: Order) -> String {
        let status = order.status
        if order.isExternalDelivery {
            let provider = Self.providerName(for: order.externalProvider)
            switch status {
            case .ready: return "Your order is ready and a courier from \(provider) is on the way to pick it up."
            case .pickedUp: return "Your order is on its way with \(provider)."
            default: break
            }
        } else if isPickup(order) {
            switch status {
            case .ready: return "Your order is ready to collect at the restaurant."
            case .pickedUp: return "You've collected your order."
            case .preparing: return "Your meal is being cooked and packed for pickup."
            default: break
            }
        }
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
            return "We're working on your order. Check back shortly."
        }
    }
    /// 6-step timeline stepper: Ordered → Accepted → Preparing → Ready → En route → Delivered.
    /// Completed steps show a filled kePrimary circle with a check, the active
    /// step pulses to signal "this is where you are right now", and future
    /// steps are muted. Connector lines between steps track fill proportionally.
    /// For pickup orders the last two step labels switch to pickup wording.
    private func progressBar(for order: Order) -> some View {
        DeliveryTimeline(stepIndex: order.status.stepIndex, isPickup: isPickup(order))
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
                .accessibilityLabel("Message \(courier.firstName)")

                let cleaned = courier.phone.filter { $0.isNumber || $0 == "+" }
                if let url = URL(string: "tel:\(cleaned)") {
                    Link(destination: url) {
                        Image(systemName: "phone.fill")
                            .foregroundColor(.kePrimary)
                            .padding(10)
                            .background(Color.keCardHover)
                            .clipShape(Circle())
                    }
                    .accessibilityLabel("Call \(courier.firstName)")
                }
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private func addressCard(for order: Order) -> some View {
        let isPickup = order.fulfillmentType == "pickup"
        return HStack(alignment: .top, spacing: Theme.spacingSM) {
            Image(systemName: isPickup ? "bag.fill" : "house.fill")
                .foregroundColor(.kePrimary)
            VStack(alignment: .leading, spacing: 2) {
                Text(isPickup ? "Pickup from" : "Delivering to")
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

    /// Stand-in for the live map when delivery is handled by a third-party
    /// network (Uber Direct / DoorDash Drive): there is no platform courier or
    /// location stream, so we surface a "track in their app" card instead.
    private func externalDeliveryCard(for order: Order) -> some View {
        let trackingURL = (order.externalTrackingURL?.isEmpty == false)
            ? URL(string: order.externalTrackingURL!)
            : nil
        return VStack(spacing: Theme.spacingMD) {
            Image(systemName: "shippingbox.fill")
                .font(.system(size: 40))
                .foregroundColor(.kePrimary)
            VStack(spacing: Theme.spacingXS) {
                Text("Delivered by \(Self.providerName(for: order.externalProvider))")
                    .font(.title3.bold())
                    .foregroundColor(.keTextPrimary)
                Text(Self.externalCardSubtext(for: order))
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
            }
            .multilineTextAlignment(.center)

            if let trackingURL {
                Button("Track delivery") {
                    openURL(trackingURL)
                }
                .buttonStyle(KEPrimaryButtonStyle())
            }
        }
        .padding(Theme.spacingLG)
        .frame(maxWidth: .infinity)
        .background(Color.keBackgroundElevated)
    }

    /// Status-aware subtext for the external-delivery card so we don't claim
    /// "on its way" while the order is still being prepared or handed off.
    private static func externalCardSubtext(for order: Order) -> String {
        let provider = providerName(for: order.externalProvider)
        switch order.status {
        case .scheduled:
            return "Your order is scheduled. A courier from \(provider) will deliver it."
        case .pending, .accepted, .preparing:
            return "The restaurant is preparing your order. A courier from \(provider) will deliver it."
        case .ready:
            return "Your order is ready and a courier from \(provider) is on the way to pick it up."
        case .pickedUp:
            return "Your order is on its way with \(provider)."
        case .delivered, .completed:
            return "Your order has been delivered."
        case .cancelled, .rejected:
            return "This order will not be delivered."
        case .unknown:
            return "We're working on your order. Check back shortly."
        }
    }

    /// Human-friendly name for an external delivery provider key.
    private static func providerName(for provider: String?) -> String {
        switch provider {
        case "uber_direct": return "Uber"
        case "doordash_drive": return "DoorDash"
        default: return "our delivery partner"
        }
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
    var isPickup: Bool = false
    @State private var pulse = false

    private struct Step {
        let label: String
        let icon: String
    }

    private var steps: [Step] {
        [
            .init(label: "Ordered",   icon: "bag.fill"),
            .init(label: "Accepted",  icon: "checkmark"),
            .init(label: "Preparing", icon: "flame.fill"),
            .init(label: "Ready",     icon: "takeoutbag.and.cup.and.straw.fill"),
            isPickup
                ? .init(label: "Ready for pickup", icon: "takeoutbag.and.cup.and.straw.fill")
                : .init(label: "En route", icon: "bicycle"),
            isPickup
                ? .init(label: "Picked up", icon: "bag.fill")
                : .init(label: "Delivered", icon: "house.fill"),
        ]
    }

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
