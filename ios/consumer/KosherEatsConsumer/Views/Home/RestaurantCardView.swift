import SwiftUI

struct RestaurantCardView: View {
    let restaurant: Restaurant

    var body: some View {
        HStack(spacing: 14) {
            // Image placeholder
            ZStack {
                RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                    .fill(Color.keCardHover)
                    .frame(width: 90, height: 90)

                Image(systemName: "fork.knife")
                    .font(.system(size: 28))
                    .foregroundColor(.kePrimary.opacity(0.6))
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(restaurant.name)
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(.keTextPrimary)
                        .lineLimit(1)

                    Spacer()

                    if !restaurant.isOpen {
                        Text("CLOSED")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.keError)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.keError.opacity(0.15))
                            .cornerRadius(4)
                    }
                }

                // Certification & Kashrus tags
                HStack(spacing: 6) {
                    KosherBadge(certification: restaurant.kosherCertification, size: .compact)

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

                // Cuisine tags
                if !restaurant.cuisineType.isEmpty {
                    Text(restaurant.cuisineType.joined(separator: " \u{2022} "))
                        .font(.system(size: 13))
                        .foregroundColor(.keTextTertiary)
                        .lineLimit(1)
                }

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
        .padding(12)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
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
