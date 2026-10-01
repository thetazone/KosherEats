import SwiftUI

/// Restaurant payouts: Stripe Connect setup state, period summary, fee
/// explainer, and the per-order payout ledger. Reached from Settings →
/// Payouts and from the Dashboard's "Set up payouts" prompt.
///
/// Mirrors the courier app's PayoutsSetupView flow: POST /payouts/account →
/// GET /payouts/link → SFSafariViewController → on dismiss, GET /payouts/status.
struct PayoutsView: View {
    @StateObject private var vm = PayoutsViewModel()
    @State private var selectedLine: PayoutLine?

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
                // Deliberately a plain VStack, not LazyVStack: the ledger card
                // (one tall child) inside a lazy stack sent SwiftUI into an
                // endless LazySubviewPlacements loop (100% CPU hang) when
                // scrolled near the end. Pages are 50 rows, so eager layout
                // is cheap; the next page loads via the explicit button.
                VStack(alignment: .leading, spacing: 20) {
                    statusCard
                    periodPicker
                    summaryCard
                    feeExplainer
                    historySection
                }
                .padding()
                .adaptiveContentWidth(700)
            }
        }
        .navigationTitle("Payouts")
        .navigationBarTitleDisplayMode(.inline)
        .task { await vm.loadAll() }
        .refreshable {
            Haptics.impact(.light)
            await vm.loadAll()
        }
        .sheet(item: $vm.safariDestination, onDismiss: {
            Task { await vm.onboardingDismissed() }
        }) { destination in
            InAppSafariView(url: destination.url)
                .ignoresSafeArea()
        }
        .sheet(item: $selectedLine) { line in
            PayoutLineDetailSheet(line: line)
                .presentationDetents([.medium, .large])
        }
    }

    // MARK: - Setup status

    private var statusCard: some View {
        let state = vm.setupState
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: statusIcon(state))
                    .font(.system(size: 28))
                    .foregroundColor(statusColor(state))
                    .frame(width: 36)
                    .accessibilityHidden(true)

                // State pill on its own line so "Pending verification" never
                // squeezes the title/message into a narrow column.
                VStack(alignment: .leading, spacing: 6) {
                    if vm.status == nil && vm.isLoadingStatus {
                        ProgressView().tint(.kePrimary)
                    } else {
                        setupPill(state)
                    }
                    Text(statusTitle(state))
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                    Text(statusMessage(state))
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }

            if state == .notSetUp {
                VStack(alignment: .leading, spacing: 8) {
                    bulletRow(icon: "lock.shield.fill", text: "Stripe securely handles your bank info")
                    bulletRow(icon: "arrow.left.arrow.right", text: "Automatic transfers for every completed order")
                    bulletRow(icon: "doc.text.fill", text: "Tax forms handled by Stripe")
                }
            }

            setupButton(state)

            if let err = vm.onboardingError {
                Text(err)
                    .font(.caption)
                    .foregroundColor(.keError)
            } else if let err = vm.statusError {
                Text(err)
                    .font(.caption)
                    .foregroundColor(.keTextMuted)
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }

    @ViewBuilder
    private func setupButton(_ state: SellerPayoutStatus.SetupState) -> some View {
        // "Set up payouts" is load-bearing copy: the web return/refresh pages
        // tell restaurants to tap "Set up payouts" again when a Stripe link
        // expires, so every pre-ready state uses that exact label.
        let label = state == .ready ? "Update banking info" : "Set up payouts"

        if state == .notSetUp {
            Button {
                Haptics.impact(.light)
                Task { await vm.startOnboarding() }
            } label: {
                Group {
                    if vm.isStartingOnboarding {
                        ProgressView().tint(.keTextOnAccent)
                    } else {
                        Text(label).font(.headline)
                    }
                }
                .foregroundColor(.keTextOnAccent)
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(Color.kePrimary)
                .cornerRadius(14)
            }
            .disabled(vm.isStartingOnboarding)
        } else {
            Button {
                Task { await vm.startOnboarding() }
            } label: {
                Group {
                    if vm.isStartingOnboarding {
                        ProgressView().tint(.kePrimary)
                    } else {
                        Text(label).font(.subheadline.bold())
                    }
                }
                .foregroundColor(.kePrimary)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(Color.kePrimary.opacity(0.12))
                .cornerRadius(10)
            }
            .disabled(vm.isStartingOnboarding)
        }
    }

    private func setupPill(_ state: SellerPayoutStatus.SetupState) -> some View {
        let text: String
        switch state {
        case .ready: text = "Ready"
        case .pendingVerification: text = "Pending verification"
        case .notSetUp: text = "Not set up"
        }
        return Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundColor(statusColor(state))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(statusColor(state).opacity(0.15))
            .clipShape(Capsule())
            .fixedSize()
    }

    private func statusIcon(_ state: SellerPayoutStatus.SetupState) -> String {
        switch state {
        case .ready: return "checkmark.seal.fill"
        case .pendingVerification: return "hourglass.circle.fill"
        case .notSetUp: return "dollarsign.circle.fill"
        }
    }

    private func statusColor(_ state: SellerPayoutStatus.SetupState) -> Color {
        switch state {
        case .ready: return .keSuccess
        case .pendingVerification: return .keWarning
        case .notSetUp: return .kePrimary
        }
    }

    private func statusTitle(_ state: SellerPayoutStatus.SetupState) -> String {
        switch state {
        case .ready: return "Payouts are ready"
        case .pendingVerification: return "Stripe is reviewing your details"
        case .notSetUp: return "Connect your bank"
        }
    }

    private func statusMessage(_ state: SellerPayoutStatus.SetupState) -> String {
        switch state {
        case .ready:
            return "Earnings from every completed order are sent to your bank automatically via Stripe."
        case .pendingVerification:
            return "Orders keep counting while you wait and are paid out once you're verified."
        case .notSetUp:
            return "\(PayoutCopy.setupPrompt). It takes about 5 minutes with Stripe."
        }
    }

    private func bulletRow(icon: String, text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.caption)
                .foregroundColor(.kePrimary)
                .frame(width: 20)
                .accessibilityHidden(true)
            Text(text)
                .font(.caption)
                .foregroundColor(.keTextSecondary)
        }
    }

    // MARK: - Period picker

    private var periodPicker: some View {
        HStack(spacing: 8) {
            ForEach(PayoutPeriod.allCases) { period in
                let selected = vm.period == period
                Button {
                    Haptics.impact(.light)
                    vm.selectPeriod(period)
                } label: {
                    Text(period.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(selected ? .keTextOnAccent : .keTextSecondary)
                        .frame(maxWidth: .infinity)
                        .frame(height: 40)
                        .background(selected ? Color.kePrimary : Color.keCard)
                        .cornerRadius(10)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }

    // MARK: - Summary

    @ViewBuilder
    private var summaryCard: some View {
        if let s = vm.summary {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(vm.period.title)
                            .font(.headline)
                            .foregroundColor(.keTextPrimary)
                        Text(vm.period.displayRange())
                            .font(.caption)
                            .foregroundColor(.keTextMuted)
                    }
                    Spacer()
                    Text("\(s.orders) \(s.orders == 1 ? "order" : "orders")")
                        .font(.caption.weight(.semibold))
                        .foregroundColor(.keTextSecondary)
                }

                VStack(spacing: 10) {
                    summaryRow("Food sales", cents: s.foodSubtotalCents)
                    summaryRow("Sales tax collected", detail: "Passed to you", cents: s.salesTaxCents)
                    summaryRow("Delivery fees + tips kept", detail: "Self-delivery", cents: s.deliveryFeeCents + s.tipCents)
                    summaryRow("KosherEats fees", cents: s.keFeeCents, isDeduction: true)
                    summaryRow("Card processing", cents: s.processingFeeCents, isDeduction: true)
                }

                Divider().background(Color.keBorder)

                HStack {
                    Text("Net")
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                    Spacer()
                    Text(CurrencyFormat.string(fromCents: s.netCents))
                        .font(.title2.bold())
                        .foregroundColor(.keTextPrimary)
                        .monospacedDigit()
                }
                .accessibilityElement(children: .combine)

                HStack(spacing: 16) {
                    miniStat("Paid", cents: s.paidCents, color: .keSuccess)
                    miniStat("Pending", cents: s.pendingCents, color: .keWarning)
                    Spacer()
                }
            }
            .padding()
            .background(Color.keCard)
            .cornerRadius(16)
        } else if let err = vm.summaryError {
            inlineError(err) { Task { await vm.loadSummary() } }
        } else {
            summarySkeleton
        }
    }

    private func summaryRow(_ title: String, detail: String? = nil, cents: Int, isDeduction: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
                if let detail {
                    Text(detail)
                        .font(.caption2)
                        .foregroundColor(.keTextMuted)
                }
            }
            Spacer()
            Text(PayoutFormat.amount(cents, isDeduction: isDeduction))
                .font(.subheadline.weight(.medium))
                .foregroundColor(.keTextPrimary)
                .monospacedDigit()
        }
        .accessibilityElement(children: .combine)
    }

    private func miniStat(_ title: String, cents: Int, color: Color) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 6, height: 6)
                .accessibilityHidden(true)
            Text("\(title) \(CurrencyFormat.string(fromCents: cents))")
                .font(.caption)
                .foregroundColor(.keTextSecondary)
                .monospacedDigit()
        }
    }

    private var summarySkeleton: some View {
        VStack(alignment: .leading, spacing: 12) {
            SkeletonBlock().frame(width: 120, height: 16)
            ForEach(0..<5, id: \.self) { _ in
                HStack {
                    SkeletonBlock().frame(width: 150, height: 12)
                    Spacer()
                    SkeletonBlock().frame(width: 60, height: 12)
                }
            }
            SkeletonBlock().frame(height: 24)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
        .accessibilityLabel("Loading summary")
    }

    // MARK: - Fee explainer

    private var feeExplainer: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "info.circle")
                .font(.subheadline)
                .foregroundColor(.keTextMuted)
                .accessibilityHidden(true)
            Text(PayoutCopy.feeExplainer)
                .font(.caption)
                .foregroundColor(.keTextTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 4)
    }

    // MARK: - History

    @ViewBuilder
    private var historySection: some View {
        Text("Payout history")
            .font(.title3.bold())
            .foregroundColor(.keTextPrimary)
            .padding(.top, 4)

        if !vm.hasLoadedLines && vm.isLoadingLines {
            ForEach(0..<4, id: \.self) { _ in lineSkeleton }
        } else if !vm.hasLoadedLines, let err = vm.linesError {
            inlineError(err) { Task { await vm.loadLines() } }
        } else if vm.lines.isEmpty {
            emptyHistory
        } else {
            VStack(spacing: 0) {
                ForEach(Array(vm.lines.enumerated()), id: \.element.id) { index, line in
                    Button {
                        selectedLine = line
                    } label: {
                        PayoutLineRow(line: line)
                    }
                    .buttonStyle(.plain)

                    if index < vm.lines.count - 1 {
                        Divider().background(Color.keBorder).padding(.leading, 16)
                    }
                }
            }
            .background(Color.keCard)
            .cornerRadius(16)

            if vm.nextCursor != nil {
                loadMoreFooter
            }
        }
    }

    private var loadMoreFooter: some View {
        VStack(spacing: 8) {
            if let err = vm.linesError, !vm.isLoadingMore {
                Text(err)
                    .font(.caption)
                    .foregroundColor(.keError)
                    .multilineTextAlignment(.center)
            }
            Button {
                Task { await vm.loadMore() }
            } label: {
                Group {
                    if vm.isLoadingMore {
                        ProgressView().tint(.kePrimary)
                    } else {
                        Text("Load more").font(.subheadline.bold())
                    }
                }
                .foregroundColor(.kePrimary)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(Color.kePrimary.opacity(0.12))
                .cornerRadius(10)
            }
            .disabled(vm.isLoadingMore)
        }
    }

    private var emptyHistory: some View {
        VStack(spacing: 10) {
            Image(systemName: "banknote")
                .font(.system(size: 36))
                .foregroundColor(.keTextMuted)
                .accessibilityHidden(true)
            Text("No payouts yet")
                .font(.subheadline.weight(.semibold))
                .foregroundColor(.keTextPrimary)
            Text("Completed orders will show up here with their payout status.")
                .font(.caption)
                .foregroundColor(.keTextMuted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .padding(.horizontal, 24)
        .background(Color.keCard)
        .cornerRadius(16)
    }

    private var lineSkeleton: some View {
        HStack {
            VStack(alignment: .leading, spacing: 6) {
                SkeletonBlock().frame(width: 110, height: 14)
                SkeletonBlock().frame(width: 160, height: 11)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 6) {
                SkeletonBlock().frame(width: 60, height: 14)
                SkeletonBlock(cornerRadius: 10).frame(width: 70, height: 18)
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }

    private func inlineError(_ message: String, retry: @escaping () -> Void) -> some View {
        VStack(spacing: 10) {
            Text(message)
                .font(.subheadline)
                .foregroundColor(.keError)
                .multilineTextAlignment(.center)
            Button("Retry", action: retry)
                .font(.subheadline.bold())
                .foregroundColor(.kePrimary)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }
}

// MARK: - Ledger row

struct PayoutLineRow: View {
    let line: PayoutLine

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: line.fulfillment.icon)
                .font(.subheadline)
                .foregroundColor(.keTextTertiary)
                .frame(width: 24)
                .accessibilityHidden(true)

            // Chip gets its own line: "Failed – we're retrying" and "Set up
            // payouts to receive" are too long to share a row with the amount.
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) {
                    Text(line.orderLabel)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.keTextPrimary)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(CurrencyFormat.string(fromCents: line.netCents))
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextPrimary)
                        .monospacedDigit()
                }
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(.keTextMuted)
                    .lineLimit(1)
                PayoutStatusChip(status: line.status)
            }

            Image(systemName: "chevron.right")
                .font(.caption2.weight(.semibold))
                .foregroundColor(.keTextMuted)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Shows the payout breakdown")
    }

    private var subtitle: String {
        let date = PayoutDates.short(line.completedAt)
        return [date, line.fulfillment.label].compactMap { $0 }.joined(separator: " · ")
    }
}

// MARK: - Status chip

struct PayoutStatusChip: View {
    let status: PayoutLineStatus

    var body: some View {
        Text(status.label)
            .font(.caption2.weight(.semibold))
            .foregroundColor(status.color)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(status.color.opacity(0.15))
            .clipShape(Capsule())
    }
}

// MARK: - Formatting

enum PayoutFormat {
    /// Deductions render as "−$1.23" so fee rows read as money leaving the
    /// payout; a zero deduction stays "$0.00" (no orphan minus sign).
    static func amount(_ cents: Int, isDeduction: Bool = false) -> String {
        let formatted = CurrencyFormat.string(fromCents: abs(cents))
        guard isDeduction, cents != 0 else { return CurrencyFormat.string(fromCents: cents) }
        return "−\(formatted)"
    }
}
