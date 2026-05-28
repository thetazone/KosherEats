import SwiftUI

struct DealsView: View {
    @StateObject private var vm = DealsViewModel()
    @State private var showCreateDeal = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                if vm.isLoading && vm.deals.isEmpty {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .kePrimary))
                } else if vm.deals.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "tag.slash")
                            .font(.system(size: 48))
                            .foregroundColor(.keTextMuted)
                        Text("No Deals Yet")
                            .font(.headline)
                            .foregroundColor(.keTextPrimary)
                        Text("Create a deal to attract more customers.")
                            .font(.subheadline)
                            .foregroundColor(.keTextSecondary)
                        Button {
                            showCreateDeal = true
                        } label: {
                            Text("Create Deal")
                                .font(.subheadline.bold())
                                .foregroundColor(.keTextOnAccent)
                                .padding(.horizontal, 24)
                                .padding(.vertical, 12)
                                .background(Color.kePrimary)
                                .cornerRadius(10)
                        }
                    }
                } else {
                    ScrollView {
                        LazyVStack(spacing: 12) {
                            ForEach(vm.deals) { deal in
                                DealCard(deal: deal) {
                                    Task { await vm.deactivateDeal(deal.id) }
                                }
                            }
                        }
                        .padding()
                    }
                }
            }
            .navigationTitle("Deals")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showCreateDeal = true
                    } label: {
                        Image(systemName: "plus")
                            .foregroundColor(.kePrimary)
                    }
                }
            }
            .task { await vm.loadDeals() }
            .refreshable { await vm.loadDeals() }
            .sheet(isPresented: $showCreateDeal) {
                CreateDealView { await vm.loadDeals() }
            }
        }
    }
}

// MARK: - Deal Card

private struct DealCard: View {
    let deal: Deal
    let onDeactivate: () -> Void
    @State private var showConfirm = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(deal.title)
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                    Text(deal.discountLabel)
                        .font(.subheadline.bold())
                        .foregroundColor(.kePrimary)
                }

                Spacer()

                if deal.isActive {
                    Text("Active")
                        .font(.caption.bold())
                        .foregroundColor(.keSuccess)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.keSuccess.opacity(0.15))
                        .cornerRadius(6)
                } else {
                    Text("Expired")
                        .font(.caption.bold())
                        .foregroundColor(.keTextMuted)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.keCard)
                        .cornerRadius(6)
                }
            }

            if !deal.description.isEmpty {
                Text(deal.description)
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
                    .lineLimit(2)
            }

            HStack {
                Label("Expires \(formattedDate(deal.expiresAt))", systemImage: "calendar")
                    .font(.caption)
                    .foregroundColor(.keTextMuted)

                Spacer()

                if deal.isActive {
                    Button("Deactivate") {
                        showConfirm = true
                    }
                    .font(.caption.bold())
                    .foregroundColor(.keError)
                }
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(12)
        .confirmationDialog("Deactivate this deal?", isPresented: $showConfirm) {
            Button("Deactivate", role: .destructive, action: onDeactivate)
        }
    }

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let displayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        return f
    }()

    private func formattedDate(_ iso: String) -> String {
        guard let date = Self.isoFormatter.date(from: iso) else { return iso }
        return Self.displayFormatter.string(from: date)
    }
}
