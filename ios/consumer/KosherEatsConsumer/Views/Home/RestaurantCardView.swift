import SwiftUI

struct RestaurantCardView: View {
    let restaurant: Restaurant
    var isFavorite: Bool = false
    var onToggleFavorite: (() -> Void)?
    /// Tap handler for the "request this restaurant" control shown on preview
    /// listings in place of the favorite heart. The caller owns auth-gating.
    var onToggleRequest: (() -> Void)?

    var body: some View {
        HStack(spacing: 14) {
            ZStack(alignment: .topTrailing) {
                RemoteImage(url: restaurant.imageURL)
                    .frame(width: 90, height: 90)
                    .cornerRadius(Theme.cornerRadiusMedium)
                    // Previews render desaturated — the closed-state gray
                    // treatment extended to the photo.
                    .saturation(restaurant.isPreview ? 0 : 1)
                    .opacity(restaurant.isPreview ? 0.6 : 1)

                // Optional logo badge in the bottom-right of the hero photo.
                // Sellers upload it during onboarding when their brand mark is
                // distinct from the picture.
                if let logoURL = restaurant.logoURL, !logoURL.isEmpty {
                    RemoteImage(url: logoURL)
                        .frame(width: 28, height: 28)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(Color.white, lineWidth: 1.5))
                        .offset(x: -4, y: 58)
                        .saturation(restaurant.isPreview ? 0 : 1)
                }

                if restaurant.isPreview {
                    RequestRestaurantBadge(
                        restaurant: restaurant,
                        onToggleRequest: onToggleRequest
                    )
                    .padding(4)
                } else {
                    Button {
                        onToggleFavorite?()
                    } label: {
                        Image(systemName: isFavorite ? "heart.fill" : "heart")
                            .font(.system(size: 16))
                            .foregroundColor(isFavorite ? .red : .gray)
                            .padding(6)
                            .background(.ultraThinMaterial)
                            .clipShape(Circle())
                    }
                    .padding(4)
                    .accessibilityLabel(isFavorite ? String(localized: "Remove \(restaurant.name) from favorites") : String(localized: "Add \(restaurant.name) to favorites"))
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(restaurant.name)
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(restaurant.isPreview ? .keTextSecondary : .keTextPrimary)
                        .lineLimit(1)

                    Spacer()

                    if restaurant.isPreview {
                        // Preview listings reuse the CLOSED badge styling in a
                        // neutral tone: browsable, not orderable yet.
                        Text("COMING SOON")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.keTextMuted)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.keTextMuted.opacity(0.15))
                            .cornerRadius(Theme.cornerRadiusSmall)
                    } else if !restaurant.isOpen {
                        Text("CLOSED")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.keError)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.keError.opacity(0.15))
                            .cornerRadius(Theme.cornerRadiusSmall)
                    }
                }

                // Certification & Kashrus tags. No badge at all when the
                // backend sent an empty certification (common on previews).
                if restaurant.hasKosherCertification || restaurant.isGlattKosher
                    || restaurant.isCholovYisroel || restaurant.isPasYisroel {
                    HStack(spacing: 6) {
                        if restaurant.hasKosherCertification {
                            KosherBadge(certification: restaurant.kosherCertification, size: .compact)
                        }

                        if restaurant.isGlattKosher {
                            KashrusTag(text: "Glatt", color: .kePrimary)
                        }
                        if restaurant.isCholovYisroel {
                            KashrusTag(text: "CY", color: .keDairy)
                        }
                        if restaurant.isPasYisroel {
                            KashrusTag(text: "PY", color: .kePareve)
                        }
                    }
                    .opacity(restaurant.isPreview ? 0.65 : 1)
                }

                // Cuisine tags
                if !restaurant.cuisineType.isEmpty {
                    Text(restaurant.cuisineType.joined(separator: " \u{2022} "))
                        .font(.system(size: 13))
                        .foregroundColor(.keTextTertiary)
                        .lineLimit(1)
                }

                if restaurant.isPreview {
                    // Previews have no rating/ETA/fee worth showing — surface
                    // the request pitch instead.
                    Text(String(localized: "Not on KosherEats yet"))
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.keTextMuted)
                } else {
                    // Rating, time, delivery
                    HStack(spacing: 12) {
                        HStack(spacing: 3) {
                            Image(systemName: "star.fill")
                                .font(.system(size: 11))
                                .foregroundColor(.kePrimary)
                            Text(restaurant.ratingFormatted)
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundColor(.keTextPrimary)
                            Text("(\(restaurant.reviewCount))")
                                .font(.system(size: 12))
                                .foregroundColor(.keTextMuted)
                        }

                        HStack(spacing: 3) {
                            Image(systemName: "clock")
                                .font(.system(size: 11))
                                .foregroundColor(.keTextMuted)
                            Text(restaurant.deliveryTimeFormatted)
                                .font(.system(size: 13))
                                .foregroundColor(.keTextSecondary)
                        }

                        Text(restaurant.deliveryFeeFormatted)
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(restaurant.deliveryFee == 0 ? .keSuccess : .keTextSecondary)
                    }
                }
            }
        }
        .padding(12)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilitySummary)
    }

    private var accessibilitySummary: String {
        if restaurant.isPreview {
            return String(localized: "\(restaurant.name), not on KosherEats yet, \(restaurant.requestCount) requests")
        }
        return "\(restaurant.name), \(restaurant.kosherCertification.displayName), \(restaurant.ratingFormatted) stars, \(restaurant.deliveryTimeFormatted) delivery\(restaurant.isOpen ? "" : ", closed")"
    }
}

// MARK: - Request Restaurant Badge

/// Heart + count control shown on preview listings (in place of the favorite
/// heart). Filled when the signed-in user has an active request; tapping
/// toggles it via POST /restaurants/{id}/request upstream.
struct RequestRestaurantBadge: View {
    let restaurant: Restaurant
    var onToggleRequest: (() -> Void)?

    var body: some View {
        Button {
            onToggleRequest?()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: restaurant.requestedByMe ? "heart.fill" : "heart")
                    .font(.system(size: 14))
                    .foregroundColor(restaurant.requestedByMe ? .red : .gray)
                if restaurant.requestCount > 0 {
                    Text("\(restaurant.requestCount)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(restaurant.requestedByMe ? .red : .gray)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial)
            .clipShape(Capsule())
        }
        .accessibilityLabel(
            restaurant.requestedByMe
                ? String(localized: "Retract your request for \(restaurant.name), \(restaurant.requestCount) requests")
                : String(localized: "Request \(restaurant.name) on KosherEats, \(restaurant.requestCount) requests")
        )
    }
}

// MARK: - Kashrus Tag

struct KashrusTag: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .foregroundColor(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15))
            .cornerRadius(4)
    }
}
