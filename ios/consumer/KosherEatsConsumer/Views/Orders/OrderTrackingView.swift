import SwiftUI
import MapKit

// OrderTrackingView is the "where's my food" screen consumers open from an
// active order. Shows a MapKit map with three pins (restaurant, courier,
// delivery address) plus a status header and a courier info card.
//
// It polls GET /orders/:id every 8s so the courier pin moves in near
// real-time. We'd switch to a push-driven WebSocket later, but polling is
// plenty for v1 and matches what UberEats consumer actually does.
struct OrderTrackingView: View {
    let orderId: String

    @State private var order: Order?
    @State private var pollTask: Task<Void, Never>?
    @State private var locationStreamTask: Task<Void, Never>?
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

    var body: some View {
        VStack(spacing: 0) {
            if let order = order {
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
            } else {
                ProgressView()
                    .tint(.kePrimary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Color.keBackground.ignoresSafeArea())
        .navigationTitle("Tracking")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await loadOnce()
            startPolling()
            startLocationStream()
        }
        .refreshable {
            Haptics.impact(.light)
            await loadOnce()
        }
        .onDisappear {
            pollTask?.cancel()
            locationStreamTask?.cancel()
        }
        .onChange(of: order?.status) { _, newStatus in
            maybePromptForRating(newStatus: newStatus)
        }
        .sheet(isPresented: $showRatingSheet) {
            if let o = order, let courier = o.courier {
                CourierRatingSheet(
                    orderId: o.id,
                    courierFirstName: courier.firstName,
                    onSubmitted: { stars in
                        // Reflect the submitted rating locally so the poll
                        // loop doesn't re-prompt; the next GetOrder response
                        // will also carry it, but this keeps the UI honest
                        // while that's in flight.
                        order?.courierRating = stars
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
              let o = order,
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
        case .cancelled, .rejected: return "Order was " + status.displayName.lowercased()
        }
    }

    private func phaseSubtext(for status: OrderStatus) -> String {
        switch status {
        case .pending: return "We've sent your order to the restaurant."
        case .accepted: return "They'll start cooking any moment."
        case .preparing: return "Arriving soon."
        case .ready: return "A courier will claim your order shortly."
        case .pickedUp: return "Your courier is heading to you."
        case .delivered: return ""
        default: return ""
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

                if let url = URL(string: "tel:\(courier.phone)") {
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

    // MARK: - Polling

    private func loadOnce() async {
        do {
            let fetched = try await APIService.shared.getOrder(id: orderId)
            let isFirst = order == nil
            order = fetched

            // Live Activity management
            if isFirst && fetched.status.isActive {
                DeliveryActivityManager.shared.startTracking(order: fetched)
            } else if fetched.status.isActive {
                DeliveryActivityManager.shared.update(order: fetched)
            } else {
                DeliveryActivityManager.shared.endTracking(order: fetched)
            }
        } catch {
            print("[tracking] fetch error: \(error)")
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                if Task.isCancelled { break }
                await loadOnce()
                if let status = order?.status, !status.isActive { break }
            }
        }
    }

    // startLocationStream subscribes to the backend SSE stream so courier
    // position updates arrive in real time instead of waiting on the 8s poll.
    // We keep the poll running for status transitions + as a reconnect safety
    // net; the stream only mutates the courier's lat/lng in-place.
    private func startLocationStream() {
        locationStreamTask?.cancel()
        locationStreamTask = Task {
            // Reconnect loop: SSE streams die naturally (server restart, brief
            // network blip, cellular handoff) and there's no cost to just
            // reopening. Backoff grows on consecutive failures so a downed
            // backend doesn't get hammered every 3s; resets on each success.
            var consecutiveFailures = 0
            while !Task.isCancelled {
                var sawAuthFailure = false
                do {
                    let stream = APIService.shared.streamOrderLocation(id: orderId)
                    for try await event in stream {
                        if Task.isCancelled { return }
                        consecutiveFailures = 0
                        guard event.lat >= -90 && event.lat <= 90
                              && event.lng >= -180 && event.lng <= 180
                              && event.lat != 0 && event.lng != 0 else {
                            continue
                        }
                        if var current = order, var courier = current.courier {
                            courier.lat = event.lat
                            courier.lng = event.lng
                            current.courier = courier
                            order = current
                            DeliveryActivityManager.shared.update(order: current)
                        }
                    }
                } catch APIError.unauthorized {
                    // Token expired mid-stream. The next request<T> call from
                    // the polling loop will refresh; we just need to wait so
                    // the new token is in the keychain before reconnecting.
                    sawAuthFailure = true
                    print("[tracking] stream 401 — waiting for poll-driven token refresh")
                } catch {
                    print("[tracking] location stream error: \(error)")
                }
                if Task.isCancelled { break }
                if let status = order?.status, !status.isActive { break }

                consecutiveFailures += 1
                let delaySeconds: Double
                if sawAuthFailure {
                    // Give the polling refresh a beat to complete before we
                    // reopen with what would otherwise be the same dead token.
                    delaySeconds = 2
                } else {
                    delaySeconds = min(3 * pow(2, Double(consecutiveFailures - 1)), 60)
                }
                try? await Task.sleep(nanoseconds: UInt64(delaySeconds * 1_000_000_000))
            }
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
                        .font(.system(size: 9, weight: i == stepIndex ? .bold : .regular))
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
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.white)
            } else if isActive {
                Image(systemName: icon)
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.white)
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
