import SwiftUI

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

            // Action hint
            if order.status == .pending {
                HStack {
                    Image(systemName: "exclamationmark.circle.fill")
                        .foregroundColor(.keWarning)
                    Text("Needs your attention")
                        .font(.caption.bold())
                        .foregroundColor(.keWarning)
                }
                .padding(.top, 4)
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
    }

    private var statusBadge: some View {
        HStack(spacing: 4) {
            Image(systemName: order.status.icon)
                .font(.caption2)
            Text(order.status.displayName)
                .font(.caption.bold())
        }
        .foregroundColor(statusColor)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(statusColor.opacity(0.15))
        .cornerRadius(8)
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
