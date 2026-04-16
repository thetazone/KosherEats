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
    @State private var cameraPosition: MapCameraPosition = .automatic

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
        }
        .refreshable {
            Haptics.impact(.light)
            await loadOnce()
        }
        .onDisappear { pollTask?.cancel() }
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

    private func progressBar(for status: OrderStatus) -> some View {
        HStack(spacing: 4) {
            ForEach(0..<5) { index in
                RoundedRectangle(cornerRadius: 2)
                    .fill(index <= status.stepIndex ? Color.kePrimary : Color.keCard)
                    .frame(height: 4)
            }
        }
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
