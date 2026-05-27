import SwiftUI

struct OrderConfirmationView: View {
    let order: Order
    var onDone: () -> Void
    var onTrack: () -> Void

    @State private var showCheckmark = false

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            VStack(spacing: 0) {
                // Dismiss bar
                HStack {
                    Spacer()
                    Button {
                        onDone()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 28))
                            .foregroundColor(.keTextMuted)
                    }
                }
                .padding()

                ScrollView(showsIndicators: false) {
                    VStack(spacing: Theme.spacingLG) {
                        // Checkmark animation
                        checkmark
                            .scaleEffect(showCheckmark ? 1.0 : 0.5)
                            .opacity(showCheckmark ? 1.0 : 0)
                            .animation(.spring(response: 0.55, dampingFraction: 0.65), value: showCheckmark)

                        VStack(spacing: 6) {
                            Text("Order placed!")
                                .font(.system(size: 28, weight: .bold))
                                .foregroundColor(.keTextPrimary)
                            Text(order.restaurantName)
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(.kePrimary)
                        }

                        // ETA card
                        etaCard

                        // Courier info (if assigned)
                        if let courier = order.courier {
                            courierCard(courier)
                        }

                        // Order items
                        itemsCard

                        // Price breakdown
                        priceCard

                        // Delivery address
                        addressCard
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 120)
                }

                // Bottom CTAs
                VStack(spacing: Theme.spacingSM) {
                    Button {
                        onTrack()
                    } label: {
                        HStack {
                            Image(systemName: "location.circle.fill")
                            Text("Track your order")
                        }
                    }
                    .buttonStyle(KEPrimaryButtonStyle())

                    Button {
                        onDone()
                    } label: {
                        Text("Done")
                    }
                    .buttonStyle(KESecondaryButtonStyle())
                }
                .padding(.horizontal, Theme.spacingLG)
                .padding(.bottom, Theme.spacingLG)
                .background(
                    LinearGradient(
                        colors: [Color.keBackground.opacity(0), Color.keBackground],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .frame(height: 40)
                    .offset(y: -40),
                    alignment: .top
                )
            }
        }
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                showCheckmark = true
            }
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
    }

    // MARK: - Checkmark

    private var checkmark: some View {
        ZStack {
            Circle()
                .fill(Color.keSuccess.opacity(0.15))
                .frame(width: 120, height: 120)
            Circle()
                .fill(Color.keSuccess.opacity(0.3))
                .frame(width: 84, height: 84)
            Image(systemName: "checkmark")
                .font(.system(size: 44, weight: .bold))
                .foregroundColor(.keSuccess)
        }
    }

    // MARK: - ETA

    private var etaCard: some View {
        VStack(spacing: 8) {
            Text("Estimated delivery")
                .font(.caption)
                .foregroundColor(.keTextTertiary)
            Text(etaText)
                .font(.system(size: 24, weight: .bold))
                .foregroundColor(.kePrimary)
            Text("Order #\(order.id.prefix(8))")
                .font(.caption)
                .foregroundColor(.keTextMuted)
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    // MARK: - Courier

    private func courierCard(_ courier: CourierPublic) -> some View {
        HStack(spacing: 14) {
            // Avatar
            if let url = courier.avatarURL, let imageURL = URL(string: url) {
                AsyncImage(url: imageURL) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Image(systemName: "person.circle.fill")
                        .font(.system(size: 44))
                        .foregroundColor(.keTextMuted)
                }
                .frame(width: 50, height: 50)
                .clipShape(Circle())
            } else {
                Image(systemName: "person.circle.fill")
                    .font(.system(size: 44))
                    .foregroundColor(.keTextMuted)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(courier.firstName)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                HStack(spacing: 4) {
                    Image(systemName: "star.fill")
                        .font(.system(size: 11))
                        .foregroundColor(.kePrimary)
                    Text(String(format: "%.1f", courier.rating))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(.keTextPrimary)
                    Text("·")
                        .foregroundColor(.keTextMuted)
                    Text("\(courier.totalDeliveries) deliveries")
                        .font(.system(size: 12))
                        .foregroundColor(.keTextTertiary)
                }
                if let vehicle = courier.vehicleColor, let make = courier.vehicleMake {
                    Text("\(vehicle) \(make) \(courier.vehicleModel ?? "")")
                        .font(.system(size: 12))
                        .foregroundColor(.keTextTertiary)
                }
            }

            Spacer()

            // Call button
            if let phoneURL = URL(string: "tel:\(courier.phone)") {
                Link(destination: phoneURL) {
                    Image(systemName: "phone.circle.fill")
                        .font(.system(size: 36))
                        .foregroundColor(.keSuccess)
                }
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    // MARK: - Items

    private var itemsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Your order")
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(.keTextPrimary)

            ForEach(order.items) { item in
                HStack(alignment: .top) {
                    Text("\(item.quantity)×")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.kePrimary)
                        .frame(width: 28, alignment: .leading)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.keTextPrimary)
                        if let mods = item.modifierSummary {
                            Text(mods)
                                .font(.system(size: 12))
                                .foregroundColor(.keTextTertiary)
                        }
                        if let notes = item.notes, !notes.isEmpty {
                            Text(notes)
                                .font(.system(size: 12))
                                .foregroundColor(.keTextMuted)
                                .italic()
                        }
                    }

                    Spacer()

                    Text(item.totalFormatted)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.keTextSecondary)
                }
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    // MARK: - Price breakdown

    private var priceCard: some View {
        VStack(spacing: 8) {
            priceRow("Subtotal", order.subtotalFormatted)
            priceRow("Delivery fee", order.deliveryFeeFormatted)
            priceRow("Service fee", order.serviceFeeFormatted)
            priceRow("Tax", order.taxFormatted)

            Divider().background(Color.keDivider)

            HStack {
                Text("Total")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Text(order.totalFormatted)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.kePrimary)
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private func priceRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 14))
                .foregroundColor(.keTextSecondary)
            Spacer()
            Text(value)
                .font(.system(size: 14))
                .foregroundColor(.keTextPrimary)
        }
    }

    // MARK: - Address

    private var addressCard: some View {
        HStack(spacing: 10) {
            Image(systemName: "mappin.circle.fill")
                .font(.system(size: 24))
                .foregroundColor(.kePrimary)
            VStack(alignment: .leading, spacing: 2) {
                Text("Delivering to")
                    .font(.system(size: 12))
                    .foregroundColor(.keTextTertiary)
                Text(order.deliveryAddress)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(.keTextPrimary)
            }
            Spacer()
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    // MARK: - Helpers

    private var etaText: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: order.estDeliveryTime)
    }
}
