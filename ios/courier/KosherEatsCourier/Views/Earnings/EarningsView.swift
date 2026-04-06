import SwiftUI

struct EarningsView: View {
    @State private var history: [HistoryOrder] = []
    @State private var isLoading = false

    private var todayTotal: Int {
        history.reduce(0) { $0 + $1.deliveryFee + $1.courierTip }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Theme.spacingLG) {
                    VStack(spacing: Theme.spacingXS) {
                        Text("Total earned")
                            .font(.caption)
                            .foregroundColor(.keTextTertiary)
                        Text("$\(String(format: "%.2f", Double(todayTotal) / 100))")
                            .font(.system(size: 48, weight: .bold))
                            .foregroundColor(.kePrimary)
                        Text("\(history.count) deliveries")
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

                        if history.isEmpty {
                            Text("No completed deliveries yet.")
                                .font(.subheadline)
                                .foregroundColor(.keTextMuted)
                        } else {
                            ForEach(history) { h in
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(h.restaurantName)
                                            .foregroundColor(.keTextPrimary)
                                        Text(h.deliveredAt ?? "")
                                            .font(.caption)
                                            .foregroundColor(.keTextTertiary)
                                    }
                                    Spacer()
                                    Text("$\(String(format: "%.2f", Double(h.deliveryFee + h.courierTip) / 100))")
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
            .background(Color.keBackground.ignoresSafeArea())
            .navigationTitle("Earnings")
            .task { await load() }
            .refreshable { await load() }
        }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        history = (try? await APIService.shared.listHistory()) ?? []
    }
}
