import SwiftUI

struct RestaurantDetailView: View {
    let restaurantID: String
    @StateObject private var vm = RestaurantViewModel()
    @EnvironmentObject var cartVM: CartViewModel
    @Environment(\.dismiss) var dismiss
    @State private var showCertificate = false
    /// Menu item whose AddToCartSheet is presented after tapping a deal that
    /// links to a specific item (mirrors Android's deal -> item-sheet flow).
    @State private var dealLinkedItem: MenuItem?

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
                    LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                        // Hero Section
                        heroSection(restaurant)

                        // Restaurant Info
                        infoSection(restaurant)

                        // Kashrus Details
                        kashrusSection(restaurant)

                        // Deals
                        if !vm.deals.isEmpty {
                            dealsSection
                        }

                        // Applied deal banner (title, min-order progress, remove)
                        if let deal = appliedDealForThisRestaurant {
                            appliedDealBanner(deal)
                        }

                        // Menu
                        menuSection
                    }
                    .padding(.bottom, 100)
                }
                .sheet(item: $dealLinkedItem) { item in
                    AddToCartSheet(
                        item: item,
                        restaurantID: restaurantID,
                        restaurantName: vm.restaurant?.name
                    )
                }
            } else if let error = vm.errorMessage {
                ErrorStateView(
                    message: error,
                    onRetry: { Task { await vm.load(restaurantID: restaurantID) } },
                )
            }
        }
        .navigationBarTitleDisplayMode(.inline)
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
                .accessibilityLabel("\(restaurant.name) cover photo")

            // Dark gradient at the bottom so text is readable even on bright photos.
            LinearGradient(
                colors: [.clear, .keBackground.opacity(0.9)],
                startPoint: .top,
                endPoint: .bottom,
            )
            .frame(height: 120)
            .accessibilityHidden(true)

            if !restaurant.isOpen {
                Color.black.opacity(0.55)
                    .frame(maxWidth: .infinity)
                    .frame(height: 240)
                    .overlay(
                        Text(String(localized: "Currently Closed"))
                            .font(.title2.bold())
                            .foregroundColor(.keTextOnAccent),
                    )
                    .accessibilityLabel(String(localized: "Restaurant is currently closed"))
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
                Text(String(localized: "Min. order: \(restaurant.minOrderFormatted)"))
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
            Text(String(localized: "Kashrus Information"))
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
                    Text(String(localized: "Certifying Agency: \(restaurant.certifyingAgency)"))
                        .font(.system(size: 13))
                        .foregroundColor(.keTextSecondary)
                }
            }

            if let certUrl = restaurant.kosherCertificateUrl, !certUrl.isEmpty {
                Button {
                    showCertificate = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "doc.text.magnifyingglass")
                        Text(String(localized: "View Kosher Certificate"))
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.kePrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background(Color.kePrimary.opacity(0.1))
                    .cornerRadius(10)
                }
                .accessibilityLabel(String(localized: "View kosher certificate for \(restaurant.name)"))
                .sheet(isPresented: $showCertificate) {
                    KosherCertificateSheet(url: certUrl, restaurantName: restaurant.name)
                }
            }

            Divider().background(Color.keDivider)
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
    }

    // MARK: - Deals

    private var dealsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "Deals"))
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(.keTextPrimary)
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(vm.deals) { deal in
                        DealCard(
                            deal: deal,
                            onApply: { applyDeal(deal) },
                            isApplied: cartVM.appliedDeal?.id == deal.id,
                            onRemove: {
                                cartVM.removeDeal()
                                Haptics.success()
                            }
                        )
                    }
                }
                .padding(.horizontal)
            }

            Divider().background(Color.keDivider).padding(.horizontal)
        }
        .padding(.bottom, 8)
    }

    /// The currently applied deal, but only when it belongs to this restaurant —
    /// a deal applied for a different restaurant must not surface here.
    private var appliedDealForThisRestaurant: Deal? {
        guard let deal = cartVM.appliedDeal, deal.restaurantId == restaurantID else { return nil }
        return deal
    }

    /// Applies a deal and, when it links to a menu item that exists on this
    /// menu and the restaurant is open, opens that item's add-to-cart sheet
    /// (mirrors Android's RestaurantDetailScreen deal-tap behaviour).
    private func applyDeal(_ deal: Deal) {
        cartVM.applyDeal(deal)
        Haptics.success()
        guard deal.hasLinkedItem,
              vm.restaurant?.isOpen == true,
              let linkedItem = vm.menuCategories
                .compactMap(\.items)
                .flatMap({ $0 })
                .first(where: { $0.id == deal.menuItemId })
        else { return }
        dealLinkedItem = linkedItem
    }

    // MARK: - Applied Deal Banner

    private func appliedDealBanner(_ deal: Deal) -> some View {
        let subtotal = cartVM.cart?.subtotal ?? 0
        let minOrder = deal.minOrderAmount ?? 0
        let needsMore = max(minOrder - subtotal, 0)

        let progressText: String
        if needsMore <= 0 {
            progressText = String(localized: "Deal applied — discount appears at checkout")
        } else if subtotal > 0 {
            progressText = String(localized: "Order: \(format(subtotal)) / \(format(minOrder)) — add \(format(needsMore)) more")
        } else if minOrder > 0 {
            progressText = String(localized: "Minimum \(format(minOrder)) — add items to unlock")
        } else {
            progressText = String(localized: "Add items to unlock this deal")
        }

        return HStack(alignment: .top, spacing: 12) {
            Image(systemName: "tag.fill")
                .font(.system(size: 18))
                .foregroundColor(.kePrimary)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(deal.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.keTextPrimary)
                Text(progressText)
                    .font(.system(size: 13))
                    .foregroundColor(needsMore > 0 ? .kePrimary : .keTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            Button {
                cartVM.removeDeal()
                Haptics.success()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.keTextMuted)
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(String(localized: "Remove deal"))
        }
        .padding(12)
        .background(Color.kePrimary.opacity(0.12))
        .cornerRadius(Theme.cornerRadiusMedium)
        .padding(.horizontal)
        .padding(.bottom, 8)
        .accessibilityElement(children: .combine)
    }

    /// Formats integer cents as a localized dollar string, matching the
    /// `*Formatted` helpers used across the cart models.
    private func format(_ cents: Int) -> String {
        Money.dollars(max(cents, 0))
    }

    // MARK: - Menu

    private var menuSection: some View {
        Group {
            Text(String(localized: "Menu"))
                .font(.system(size: 22, weight: .bold))
                .foregroundColor(.keTextPrimary)
                .padding(.horizontal)
                .padding(.top, 16)

            if vm.menuCategories.isEmpty {
                VStack(spacing: Theme.spacingMD) {
                    Image(systemName: "menucard")
                        .font(.system(size: 48))
                        .foregroundColor(.keTextMuted)
                    Text("Menu not available")
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                    Text("This restaurant hasn't published a menu yet.")
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                }
                .frame(maxWidth: .infinity, minHeight: 100)
            } else {
                ForEach(vm.menuCategories) { category in
                    Section {
                        if let items = category.items {
                            ForEach(items) { item in
                                MenuItemView(
                                    item: item,
                                    restaurantID: restaurantID,
                                    restaurantName: vm.restaurant?.name
                                )
                                .padding(.horizontal)
                            }
                        }
                    } header: {
                        Text(category.name)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.kePrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal)
                            .padding(.vertical, 10)
                            .background(Color.keBackground)
                    }
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
                .accessibilityHidden(true)
            Text(text)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(color)
        }
        .accessibilityElement(children: .combine)
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
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title) \(subtitle)")
    }
}
