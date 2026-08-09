import SwiftUI
import MapKit
import UIKit

/// Store-info sheet reached from the restaurant detail's ⋮ menu — address,
/// map + distance, hours/status, rating, phone, and kosher details. Mirrors the
/// "Store info" panel users know from other delivery apps, styled to KosherEats.
///
/// Everything here is read-only; a preview listing shows the same rows with its
/// "not on KosherEats yet" framing instead of order-time stats.
struct StoreInfoView: View {
    let restaurant: Restaurant
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var location = LocationManager.shared
    @State private var copied = false

    private var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: restaurant.lat, longitude: restaurant.lng)
    }

    private var fullAddress: String {
        let line2 = [restaurant.city, restaurant.state].filter { !$0.isEmpty }.joined(separator: ", ")
        return [restaurant.street, "\(line2) \(restaurant.zipCode)".trimmingCharacters(in: .whitespaces)]
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    }

    /// Straight-line distance from the user's last known location, in miles.
    /// nil when we have no fix or the restaurant has no coordinate.
    private var distanceMiles: Double? {
        guard let here = location.currentLocation,
              restaurant.lat != 0, restaurant.lng != 0 else { return nil }
        let a = CLLocation(latitude: here.latitude, longitude: here.longitude)
        let b = CLLocation(latitude: restaurant.lat, longitude: restaurant.lng)
        return a.distance(from: b) / 1609.344
    }

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(spacing: 0) {
                    mapHeader
                    titleBlock
                    Divider().background(Color.keDivider)
                    rows
                    kosherNote
                }
            }
            .background(Color.keBackground.ignoresSafeArea())
            .navigationTitle("Store info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.keTextPrimary)
                    }
                    .accessibilityLabel("Close")
                }
            }
        }
    }

    // MARK: - Map

    private var mapHeader: some View {
        ZStack(alignment: .topTrailing) {
            Map(initialPosition: .region(MKCoordinateRegion(
                center: coordinate,
                latitudinalMeters: 1200,
                longitudinalMeters: 1200
            ))) {
                Marker(restaurant.name, coordinate: coordinate)
                    .tint(Color.kePrimary)
            }
            .mapStyle(.standard(pointsOfInterest: .excludingAll))
            .frame(height: 200)
            .allowsHitTesting(false)   // a static locator, not an interactive map
            .saturation(restaurant.isPreview ? 0 : 1)

            if let miles = distanceMiles {
                Text(String(format: "%.1f mi away", miles))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.keTextPrimary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(Capsule().fill(Color.keBackgroundElevated))
                    .padding(12)
            }
        }
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(restaurant.name)
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.keTextPrimary)
            if !restaurant.cuisineType.isEmpty {
                Text(restaurant.cuisineType.joined(separator: " \u{2022} "))
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 16)
    }

    // MARK: - Rows

    @ViewBuilder
    private var rows: some View {
        // Address + copy
        if !fullAddress.isEmpty {
            infoRow(icon: "mappin.and.ellipse", title: fullAddress) {
                Button {
                    UIPasteboard.general.string = fullAddress
                    withAnimation { copied = true }
                } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 16))
                        .foregroundColor(copied ? .keSuccess : .keTextSecondary)
                }
                .accessibilityLabel(copied ? "Address copied" : "Copy address")
            }
            Divider().background(Color.keDivider).padding(.leading, 52)
        }

        // Status (we don't carry opening hours yet; show open/closed or preview state)
        infoRow(icon: "clock",
                title: restaurant.isPreview
                    ? String(localized: "Not on KosherEats yet")
                    : (restaurant.isOpen ? String(localized: "Open now") : String(localized: "Currently closed")),
                titleColor: restaurant.isPreview ? .keTextSecondary
                    : (restaurant.isOpen ? .keSuccess : .keError))
        Divider().background(Color.keDivider).padding(.leading, 52)

        // Rating
        if !restaurant.isPreview, restaurant.reviewCount > 0 {
            infoRow(icon: "star",
                    title: "\(restaurant.ratingFormatted) (\(restaurant.reviewCount) ratings)")
            Divider().background(Color.keDivider).padding(.leading, 52)
        }

        // Phone (tap to call)
        if !restaurant.phone.isEmpty {
            infoRow(icon: "phone", title: restaurant.phone) {
                Button {
                    let digits = restaurant.phone.filter { $0.isNumber || $0 == "+" }
                    if let url = URL(string: "tel://\(digits)") { UIApplication.shared.open(url) }
                } label: {
                    Image(systemName: "phone.arrow.up.right")
                        .font(.system(size: 16))
                        .foregroundColor(.kePrimary)
                }
                .accessibilityLabel("Call restaurant")
            }
            Divider().background(Color.keDivider).padding(.leading, 52)
        }

        // Kosher details
        if hasKosherRow {
            infoRow(icon: "checkmark.seal", iconColor: .kePrimary, title: kosherTitle, subtitle: kosherSubtitle)
        }
    }

    private var hasKosherRow: Bool {
        restaurant.hasKosherCertification || restaurant.isGlattKosher
            || restaurant.isCholovYisroel || restaurant.isPasYisroel
            || !restaurant.certifyingAgency.isEmpty
    }

    private var kosherTitle: String {
        if !restaurant.certifyingAgency.isEmpty {
            return String(localized: "Certified by \(restaurant.certifyingAgency)")
        }
        return String(localized: "Kosher")
    }

    private var kosherSubtitle: String? {
        var tags: [String] = []
        if restaurant.isGlattKosher { tags.append("Glatt") }
        if restaurant.isCholovYisroel { tags.append("Cholov Yisroel") }
        if restaurant.isPasYisroel { tags.append("Pas Yisroel") }
        return tags.isEmpty ? nil : tags.joined(separator: " \u{2022} ")
    }

    /// Kosher note — parallels the "contact the merchant to confirm" line other
    /// apps show, adapted to KosherEats' onboarding model for preview listings.
    @ViewBuilder
    private var kosherNote: some View {
        Text(restaurant.isPreview
             ? String(localized: "Not on KosherEats yet. Kosher certification is confirmed when the restaurant is onboarded.")
             : String(localized: "Kosher status is per the certifying agency. Contact the restaurant directly to confirm."))
            .font(.system(size: 12))
            .foregroundColor(.keTextMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 28)
    }

    // MARK: - Row builder

    private func infoRow(
        icon: String,
        iconColor: Color = .keTextSecondary,
        title: String,
        titleColor: Color = .keTextPrimary,
        subtitle: String? = nil,
        @ViewBuilder trailing: () -> some View = { EmptyView() }
    ) -> some View {
        HStack(alignment: .center, spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 20))
                .foregroundColor(iconColor)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 16))
                    .foregroundColor(titleColor)
                    .fixedSize(horizontal: false, vertical: true)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 13))
                        .foregroundColor(.keTextSecondary)
                }
            }
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }
}
