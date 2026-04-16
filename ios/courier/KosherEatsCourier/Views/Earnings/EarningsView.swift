import SwiftUI

struct EarningsView: View {
    @State private var history: [HistoryOrder] = []
    @State private var isLoading = false
    @State private var errorMessage: String?

    private var todayTotal: Int {
        history.reduce(0) { $0 + $1.courierPayout }
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
        } catch {
            history = []
            errorMessage = error.localizedDescription
        }
    }
}
