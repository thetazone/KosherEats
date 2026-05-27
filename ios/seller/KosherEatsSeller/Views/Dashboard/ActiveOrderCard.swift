import SwiftUI

/// Matches the backend's pendingOrderTTL in scheduler/dispatcher.go. After
/// this much time in 'pending' the backend auto-rejects and refunds, so the
/// card shows a live countdown to that deadline to pressure the seller.
private let pendingOrderTTL: TimeInterval = 10 * 60

/// Time-remaining threshold at which the pending timer flips to error color
/// ("less than 2 min left"). Purely visual; the auto-reject itself is
/// backend-driven.
private let pendingUrgentThreshold: TimeInterval = 2 * 60

struct ActiveOrderCard: View {
    let order: Order

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Order #\(String(order.id.prefix(8)))")
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)

                    Text(order.formattedDate)
                        .font(.caption)
                        .foregroundColor(.keTextMuted)
                }

                Spacer()

                statusBadge
            }

            Divider()
                .background(Color.keBorder)

            // Items Summary
            VStack(alignment: .leading, spacing: 4) {
                ForEach(order.items.prefix(3)) { item in
                    HStack {
                        Text("\(item.quantity)x")
                            .font(.caption.bold())
                            .foregroundColor(.kePrimary)

                        Text(item.name)
                            .font(.subheadline)
                            .foregroundColor(.keTextPrimary)
                            .lineLimit(1)

                        Spacer()
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("\(item.quantity) times \(item.name)")
                }

                if order.items.count > 3 {
                    Text("+\(order.items.count - 3) more items")
                        .font(.caption)
                        .foregroundColor(.keTextMuted)
                }
            }

            Divider()
                .background(Color.keBorder)

            // Footer
            HStack {
                Label("\(order.itemCount) items", systemImage: "bag")
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)

                Spacer()

                Text(order.totalFormatted)
                    .font(.headline)
                    .foregroundColor(.kePrimary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(order.itemCount) items, total \(order.totalFormatted)")

            // Action hint — live countdown for pending orders so the seller
            // sees how close they are to the auto-reject deadline.
            // Guard: only render the countdown when createdAtDate is non-nil;
            // a nil date (e.g. malformed server response) skips the timer
            // rather than crashing.
            if order.status == .pending {
                if let placedAt = order.createdAtDate {
                    PendingCountdown(placedAt: placedAt)
                        .padding(.top, 4)
                }
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(
                    order.status == .pending ? Color.keWarning.opacity(0.5) : Color.clear,
                    lineWidth: 1
                )
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Order \(String(order.id.prefix(8))), \(order.status.displayName)")
        .accessibilityHint("\(order.itemCount) items, \(order.totalFormatted)")
    }

    private var statusBadge: some View {
        HStack(spacing: 4) {
            Image(systemName: order.status.icon)
                .font(.caption2)
                .accessibilityHidden(true)
            Text(order.status.displayName)
                .font(.caption.bold())
        }
        .foregroundColor(statusColor)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(statusColor.opacity(0.15))
        .cornerRadius(8)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Status: \(order.status.displayName)")
    }

    private var statusColor: Color {
        switch order.status.color {
        case "primary": return .kePrimary
        case "success": return .keSuccess
        case "warning": return .keWarning
        case "error": return .keError
        default: return .keTextSecondary
        }
    }
}

/// Live countdown showing how long until the backend will auto-reject + refund
/// this pending order. Driven by a 1-second `TimelineView` so it updates in
/// place without the parent having to re-render. Flips from warning → error
/// color in the last `pendingUrgentThreshold` seconds.
private struct PendingCountdown: View {
    let placedAt: Date

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let elapsed = ctx.date.timeIntervalSince(placedAt)
            let remaining = max(0, pendingOrderTTL - elapsed)
            let urgent = remaining <= pendingUrgentThreshold
            let expired = remaining <= 0

            HStack(spacing: 6) {
                Image(systemName: expired ? "xmark.octagon.fill" : "clock.fill")
                    .accessibilityHidden(true)
                Text(label(elapsed: elapsed, remaining: remaining, expired: expired))
                    .font(.caption.bold())
            }
            .foregroundColor(expired || urgent ? .keError : .keWarning)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(expired ? "Order auto-rejecting" : "Respond in \(Int(remaining / 60)) minutes \(Int(remaining) % 60) seconds")
        }
    }

    private func label(elapsed: TimeInterval, remaining: TimeInterval, expired: Bool) -> String {
        if expired {
            return "Auto-rejecting…"
        }
        return "Respond in \(format(remaining)) • pending \(format(elapsed))"
    }

    private func format(_ seconds: TimeInterval) -> String {
        let s = Int(seconds)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}
