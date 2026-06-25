import SwiftUI

struct EarningsView: View {
    @EnvironmentObject private var dashVM: DashboardViewModel
    @State private var history: [HistoryOrder] = []
    @State private var isLoading = false
    @State private var errorMessage: String?

    private static let isoFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let isoPlain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "h:mm a"
        return f
    }()

    private func formatDeliveredAt(_ raw: String?) -> String {
        guard let raw else { return "" }
        let date = Self.isoFractional.date(from: raw) ?? Self.isoPlain.date(from: raw)
        guard let date else { return raw }
        return Self.timeFormatter.string(from: date)
    }

    /// Today's deliveries, derived from the single `history` fetch so the total
    /// and the count are always consistent (rather than reading the dollar total
    /// from a separate dashVM.loadTodayEarnings() fetch that can disagree).
    private var todayHistory: [HistoryOrder] {
        let cal = Calendar.current
        return history.filter { order in
            guard let raw = order.deliveredAt,
                  let date = Self.isoFractional.date(from: raw) ?? Self.isoPlain.date(from: raw)
            else { return false }
            return cal.isDateInToday(date)
        }
    }
    private var todayTotal: Int { todayHistory.reduce(0) { $0 + $1.courierPayout } }
    private var todayDeliveryCount: Int { todayHistory.count }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Theme.spacingLG) {
                    VStack(spacing: Theme.spacingXS) {
                        Text("Today's earnings")
                            .font(.caption)
                            .foregroundColor(.keTextTertiary)
                        Text("$\(String(format: "%.2f", Double(todayTotal) / 100))")
                            .font(.system(size: 48, weight: .bold))
                            .foregroundColor(.kePrimary)
                        Text("\(todayDeliveryCount) deliver\(todayDeliveryCount == 1 ? "y" : "ies") today")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Theme.spacingLG)
                    .background(Color.keCard)
                    .cornerRadius(Theme.cornerRadiusMedium)

                    VStack(alignment: .leading, spacing: Theme.spacingSM) {
                        Text("Recent deliveries")
                            .font(.headline)
                            .foregroundColor(.keTextPrimary)

                        if isLoading && history.isEmpty {
                            ProgressView()
                                .tint(.kePrimary)
                                .frame(maxWidth: .infinity)
                                .padding()
                        } else if history.isEmpty {
                            Text("No completed deliveries yet.")
                                .font(.subheadline)
                                .foregroundColor(.keTextMuted)
                        } else {
                            ForEach(history) { h in
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(h.restaurantName)
                                            .foregroundColor(.keTextPrimary)
                                        Text(formatDeliveredAt(h.deliveredAt))
                                            .font(.caption)
                                            .foregroundColor(.keTextTertiary)
                                    }
                                    Spacer()
                                    Text("$\(String(format: "%.2f", Double(h.courierPayout) / 100))")
                                        .foregroundColor(.keSuccess)
                                }
                                .padding(.vertical, Theme.spacingSM)
                                Divider().background(Color.keDivider)
                            }
                        }
                    }
                    .padding()
                    .background(Color.keCard)
                    .cornerRadius(Theme.cornerRadiusMedium)
                }
                .padding(Theme.spacingMD)
            }
            .overlay {
                if let errorMessage {
                    VStack(spacing: Theme.spacingMD) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.largeTitle)
                            .foregroundColor(.keWarning)
                        Text("Could not load earnings")
                            .font(.headline)
                            .foregroundColor(.keTextPrimary)
                        Text(errorMessage)
                            .font(.subheadline)
                            .foregroundColor(.keTextSecondary)
                            .multilineTextAlignment(.center)
                        Button("Retry") {
                            Task { await load() }
                        }
                        .buttonStyle(KEPrimaryButtonStyle())
                    }
                    .padding(Theme.spacingLG)
                }
            }
            .background(Color.keBackground.ignoresSafeArea())
            .navigationTitle("Earnings")
            .task { await load() }
            .refreshable { await load() }
        }
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            history = try await APIService.shared.listHistory()
            errorMessage = nil
        } catch {
            // Don't wipe already-loaded history on a transient refresh error —
            // the courier keeps their existing data visible.
            errorMessage = error.localizedDescription
        }
    }
}
