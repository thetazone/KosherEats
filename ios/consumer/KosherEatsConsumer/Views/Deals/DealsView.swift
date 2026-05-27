import SwiftUI

struct DealsView: View {
    @EnvironmentObject var cartVM: CartViewModel
    @StateObject private var vm = DealsViewModel()

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            if vm.isLoading && vm.deals.isEmpty {
                ProgressView().tint(.kePrimary)
            } else if vm.deals.isEmpty {
                VStack(spacing: 16) {
                    Image(systemName: "tag.slash")
                        .font(.system(size: 48))
                        .foregroundColor(.keTextMuted)
                    Text("No deals right now")
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                    Text("Check back later for deals from nearby restaurants.")
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
            } else {
                ScrollView(showsIndicators: false) {
                    LazyVStack(spacing: 14) {
                        ForEach(vm.deals) { deal in
                            NavigationLink(destination: RestaurantDetailView(restaurantID: deal.restaurantId)) {
                                DealListCard(deal: deal) {
                                    cartVM.applyDeal(deal)
                                    Haptics.success()
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding()
                }
            }
        }
        .navigationTitle("Deals Near You")
        .navigationBarTitleDisplayMode(.large)
        .task { await vm.load() }
        .refreshable { await vm.load() }
        .overlay {
            if vm.isLoading && !vm.deals.isEmpty {
                ProgressView()
                    .tint(.kePrimary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding()
            }
        }
    }
}

// MARK: - ViewModel

@MainActor
class DealsViewModel: ObservableObject {
    @Published var deals: [Deal] = []
    @Published var isLoading = false
    @Published var errorMessage: String?

    private static let expiryFormatters: [ISO8601DateFormatter] = {
        let f1 = ISO8601DateFormatter()
        f1.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return [f1, f2]
    }()

    func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let all = try await APIService.shared.getNearbyDeals()
            deals = all.filter { !Self.isExpired($0) }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Returns `true` when the deal has an `expiresAt` timestamp that is in the past.
    private static func isExpired(_ deal: Deal) -> Bool {
        guard let raw = deal.expiresAt else { return false }
        for f in expiryFormatters {
            if let date = f.date(from: raw) {
                return date <= Date()
            }
        }
        // Unparseable expiry -- keep the deal visible rather than hiding it.
        return false
    }
}

// MARK: - Deal Card (compact, for horizontal scroll in restaurant detail)

struct DealCard: View {
    let deal: Deal
    let onApply: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "tag.fill")
                    .font(.system(size: 14))
                    .foregroundColor(.keSuccess)
                Text(deal.discountBadge)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.keSuccess)
            }

            Text(deal.title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(.keTextPrimary)
                .lineLimit(2)

            if !deal.description.isEmpty {
                Text(deal.description)
                    .font(.system(size: 13))
                    .foregroundColor(.keTextSecondary)
                    .lineLimit(2)
            }

            if let min = deal.minOrderFormatted {
                Text("Min. order \(min)")
                    .font(.system(size: 11))
                    .foregroundColor(.keTextMuted)
            }

            Button(action: onApply) {
                Text("Apply Deal")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.keTextOnAccent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 32)
                    .background(Color.kePrimary)
                    .cornerRadius(8)
            }
        }
        .padding(12)
        .frame(width: 200)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }
}

// MARK: - Deal List Card (full width, for deals listing)

struct DealListCard: View {
    let deal: Deal
    let onApply: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let imageUrl = deal.displayImageUrl, let url = URL(string: imageUrl) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(height: 140)
                            .clipped()
                    case .failure:
                        dealImagePlaceholder
                    case .empty:
                        ProgressView()
                            .tint(.kePrimary)
                            .frame(maxWidth: .infinity)
                            .frame(height: 140)
                    @unknown default:
                        dealImagePlaceholder
                    }
                }
                .cornerRadius(Theme.cornerRadiusMedium)
            }

            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Image(systemName: "tag.fill")
                            .font(.system(size: 12))
                            .foregroundColor(.keSuccess)
                        Text(deal.discountBadge)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.keSuccess)
                    }

                    Text(deal.title)
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(.keTextPrimary)
                        .lineLimit(2)

                    if let name = deal.restaurantName {
                        Text(name)
                            .font(.system(size: 13))
                            .foregroundColor(.keTextSecondary)
                    }

                    if !deal.description.isEmpty {
                        Text(deal.description)
                            .font(.system(size: 13))
                            .foregroundColor(.keTextMuted)
                            .lineLimit(2)
                    }
                }

                Spacer()

                Button(action: onApply) {
                    Text("Apply")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(.keTextOnAccent)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.kePrimary)
                        .cornerRadius(8)
                }
            }

            if let min = deal.minOrderFormatted {
                Text("Min. order \(min)")
                    .font(.system(size: 11))
                    .foregroundColor(.keTextMuted)
            }

            if let expires = deal.expiresAt {
                ExpiryLabel(expiresAt: expires)
            }
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private var dealImagePlaceholder: some View {
        ZStack {
            Color.keBackgroundElevated
            Image(systemName: "tag.fill")
                .font(.system(size: 32))
                .foregroundColor(.keTextMuted)
        }
        .frame(height: 140)
    }
}

// MARK: - Expiry Label

private struct ExpiryLabel: View {
    let expiresAt: String

    private static let formatters: [ISO8601DateFormatter] = {
        let f1 = ISO8601DateFormatter()
        f1.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return [f1, f2]
    }()

    var body: some View {
        if let remaining = timeRemaining {
            HStack(spacing: 4) {
                Image(systemName: "clock")
                    .font(.system(size: 10))
                Text(remaining)
                    .font(.system(size: 11, weight: .medium))
            }
            .foregroundColor(.keWarning)
        }
    }

    private var timeRemaining: String? {
        var expiry: Date?
        for f in Self.formatters {
            if let d = f.date(from: expiresAt) { expiry = d; break }
        }
        guard let expiry, expiry > Date() else { return nil }

        let diff = Calendar.current.dateComponents([.day, .hour, .minute], from: Date(), to: expiry)
        if let days = diff.day, days > 0 {
            return "\(days)d left"
        } else if let hours = diff.hour, hours > 0 {
            return "\(hours)h left"
        } else if let mins = diff.minute, mins > 0 {
            return "\(mins)m left"
        }
        return nil
    }
}
