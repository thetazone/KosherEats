import SwiftUI

/// Per-order payout breakdown: every *_cents field on the ledger line, plus
/// status, timestamps and the Stripe transfer id for support lookups.
struct PayoutLineDetailSheet: View {
    let line: PayoutLine
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        header
                        breakdown
                        details
                        if line.status == .awaitingAccount {
                            Text("This payout is on hold until payouts are set up. Go to Settings → Payouts to connect your bank with Stripe.")
                                .font(.caption)
                                .foregroundColor(.keTextTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding()
                    .adaptiveContentWidth(600)
                }
            }
            .navigationTitle(line.orderLabel)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .foregroundColor(.kePrimary)
                }
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(CurrencyFormat.string(fromCents: line.netCents))
                    .font(.largeTitle.bold())
                    .foregroundColor(.keTextPrimary)
                    .monospacedDigit()
                Spacer()
                PayoutStatusChip(status: line.status)
            }
            HStack(spacing: 6) {
                Image(systemName: line.fulfillment.icon)
                    .accessibilityHidden(true)
                Text(line.fulfillment.label)
            }
            .font(.subheadline)
            .foregroundColor(.keTextSecondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var breakdown: some View {
        VStack(spacing: 12) {
            row("Food subtotal", PayoutFormat.amount(line.foodSubtotalCents))
            row("Sales tax collected", PayoutFormat.amount(line.salesTaxCents),
                detail: "Passed to you to remit")
            row("Delivery fee", PayoutFormat.amount(line.deliveryFeeCents))
            row("Tip", PayoutFormat.amount(line.tipCents))
            row("KosherEats fee", PayoutFormat.amount(line.keFeeCents, isDeduction: true))
            row("Card processing", PayoutFormat.amount(line.processingFeeCents, isDeduction: true))

            Divider().background(Color.keBorder)

            HStack {
                Text("Net payout")
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Text(CurrencyFormat.string(fromCents: line.netCents))
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                    .monospacedDigit()
            }
            .accessibilityElement(children: .combine)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }

    private var details: some View {
        VStack(spacing: 12) {
            row("Status", line.status.label)
            row("Completed", PayoutDates.display(line.completedAt))
            if let paidAt = line.paidAt {
                row("Paid", PayoutDates.display(paidAt))
            }
            row("Order ID", String(line.orderId.prefix(8)), monospaced: true)
            if let transfer = line.transferId, !transfer.isEmpty {
                row("Stripe transfer", transfer, monospaced: true)
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }

    private func row(_ title: String, _ value: String, detail: String? = nil, monospaced: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
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
            Text(value)
                .font(monospaced ? .caption.monospaced() : .subheadline.weight(.medium))
                .foregroundColor(.keTextPrimary)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
                .lineLimit(monospaced ? 1 : nil)
                .truncationMode(.middle)
                .textSelection(.enabled)
        }
        .accessibilityElement(children: .combine)
    }
}
