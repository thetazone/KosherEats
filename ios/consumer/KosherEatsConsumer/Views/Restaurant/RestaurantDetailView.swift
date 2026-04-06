import SwiftUI

struct RestaurantDetailView: View {
    let restaurantID: String
    @StateObject private var vm = RestaurantViewModel()
    @EnvironmentObject var cartVM: CartViewModel
    @Environment(\.dismiss) var dismiss

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            if vm.isLoading && vm.restaurant == nil {
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 16) {
                        SkeletonBlock(cornerRadius: 0).frame(height: 240)
                        VStack(alignment: .leading, spacing: 10) {
                            SkeletonBlock().frame(height: 22).frame(maxWidth: 220)
                            SkeletonBlock().frame(height: 12).frame(maxWidth: 140)
                            HStack(spacing: 10) {
                                SkeletonBlock().frame(width: 60, height: 14)
                                SkeletonBlock().frame(width: 60, height: 14)
                                SkeletonBlock().frame(width: 60, height: 14)
                            }
                            ForEach(0..<4, id: \.self) { _ in
                                MenuItemSkeleton()
                            }
                        }
                        .padding(.horizontal)
                    }
                }
            } else if let restaurant = vm.restaurant {
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 0) {
                        // Hero Section
                        heroSection(restaurant)

                        // Restaurant Info
                        infoSection(restaurant)

                        // Kashrus Details
                        kashrusSection(restaurant)

                        // Menu
                        menuSection
                    }
                    .padding(.bottom, 100)
                }
            } else if let error = vm.errorMessage {
                ErrorStateView(
                    message: error,
                    onRetry: { Task { await vm.load(restaurantID: restaurantID) } },
                )
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task {
            await vm.load(restaurantID: restaurantID)
        }
    }

    // MARK: - Hero

    private func heroSection(_ restaurant: Restaurant) -> some View {
        ZStack(alignment: .bottom) {
            RemoteImage(url: restaurant.coverImageURL ?? restaurant.imageURL)
                .frame(maxWidth: .infinity)
                .frame(height: 240)

            // Dark gradient at the bottom so text is readable even on bright photos.
            LinearGradient(
                colors: [.clear, .keBackground.opacity(0.9)],
                startPoint: .top,
                endPoint: .bottom,
            )
            .frame(height: 120)

            if !restaurant.isOpen {
                Color.black.opacity(0.55)
                    .frame(maxWidth: .infinity)
                    .frame(height: 240)
                    .overlay(
                        Text("Currently Closed")
                            .font(.title2.bold())
                            .foregroundColor(.white),
                    )
            }
        }
        .frame(height: 240)
        .clipped()
    }

    // MARK: - Info

    private func infoSection(_ restaurant: Restaurant) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(restaurant.name)
                        .font(.system(size: 26, weight: .bold))
                        .foregroundColor(.keTextPrimary)

                    Text(restaurant.cuisineType.joined(separator: " \u{2022} "))
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                }

                Spacer()

                KosherBadge(certification: restaurant.kosherCertification, size: .regular)
            }

            // Stats row
            HStack(spacing: 20) {
                StatPill(icon: "star.fill", text: "\(restaurant.ratingFormatted) (\(restaurant.reviewCount))", color: .kePrimary)
                StatPill(icon: "clock", text: restaurant.deliveryTimeFormatted, color: .keTextSecondary)
                StatPill(icon: "bicycle", text: restaurant.deliveryFeeFormatted, color: restaurant.deliveryFee == 0 ? .keSuccess : .keTextSecondary)
            }

            if restaurant.minOrder > 0 {
                Text("Min. order: \(restaurant.minOrderFormatted)")
                    .font(.system(size: 13))
                    .foregroundColor(.keTextMuted)
            }

            Text(restaurant.description)
                .font(.system(size: 15))
                .foregroundColor(.keTextSecondary)
                .lineLimit(3)

            Divider().background(Color.keDivider)
        }
        .padding()
    }

    // MARK: - Kashrus

    private func kashrusSection(_ restaurant: Restaurant) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Kashrus Information")
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(.keTextPrimary)

            HStack(spacing: 12) {
                KashrusInfoChip(
                    title: restaurant.kosherCertification.displayName,
                    subtitle: "Certification",
                    icon: "checkmark.seal.fill",
                    color: .kePrimary
                )

                if restaurant.isGlattKosher {
                    KashrusInfoChip(title: "Glatt", subtitle: "Kosher", icon: "checkmark.circle.fill", color: .keSuccess)
                }
            }

            HStack(spacing: 12) {
                if restaurant.isCholovYisroel {
                    KashrusInfoChip(title: "Cholov", subtitle: "Yisroel", icon: "drop.fill", color: .keDairy)
                }
                if restaurant.isPasYisroel {
                    KashrusInfoChip(title: "Pas", subtitle: "Yisroel", icon: "birthday.cake.fill", color: .keWarning)
                }
            }

            if !restaurant.certifyingAgency.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "building.2")
                        .font(.system(size: 13))
                        .foregroundColor(.keTextMuted)
                    Text("Certifying Agency: \(restaurant.certifyingAgency)")
                        .font(.system(size: 13))
                        .foregroundColor(.keTextSecondary)
                }
            }

            Divider().background(Color.keDivider)
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
    }

    // MARK: - Menu

    private var menuSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Menu")
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.keTextPrimary)
                .padding(.horizontal)

            if vm.menuCategories.isEmpty {
                Text("Menu not available")
                    .foregroundColor(.keTextMuted)
                    .frame(maxWidth: .infinity, minHeight: 100)
            } else {
                ForEach(vm.menuCategories) { category in
                    VStack(alignment: .leading, spacing: 10) {
                        Text(category.name)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.kePrimary)
                            .padding(.horizontal)

                        if let items = category.items {
                            ForEach(items) { item in
                                MenuItemView(
                                    item: item,
                                    restaurantID: restaurantID
                                )
                                .padding(.horizontal)
                            }
                        }
                    }
                    .padding(.bottom, 8)
                }
            }
        }
    }
}

// MARK: - Stat Pill

struct StatPill: View {
    let icon: String
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundColor(color)
            Text(text)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(color)
        }
    }
}

// MARK: - Kashrus Info Chip

struct KashrusInfoChip: View {
    let title: String
    let subtitle: String
    let icon: String
    let color: Color

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 16))
                .foregroundColor(color)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                Text(subtitle)
                    .font(.system(size: 11))
                    .foregroundColor(.keTextMuted)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusSmall)
    }
}
