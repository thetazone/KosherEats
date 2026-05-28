import ActivityKit
import WidgetKit
import SwiftUI

struct DeliveryActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DeliveryAttributes.self) { context in
            // Lock Screen / Banner view
            lockScreenView(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                // Expanded view
                DynamicIslandExpandedRegion(.leading) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.attributes.restaurantName)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                        Text(context.state.statusText)
                            .font(.system(size: 12))
                            .foregroundColor(.secondary)
                    }
                }

                DynamicIslandExpandedRegion(.trailing) {
                    if let eta = context.state.eta {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(eta, style: .timer)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.orange)
                                .monospacedDigit()
                            Text("ETA")
                                .font(.system(size: 10))
                                .foregroundColor(.secondary)
                        }
                    }
                }

                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 12) {
                        // Progress dots
                        progressDots(for: context.state.status)

                        Spacer()

                        if let courier = context.state.courierName {
                            HStack(spacing: 4) {
                                Image(systemName: "person.circle.fill")
                                    .font(.system(size: 14))
                                Text(courier)
                                    .font(.system(size: 12, weight: .medium))
                            }
                            .foregroundColor(.white)
                        }
                    }
                    .padding(.top, 4)
                }
            } compactLeading: {
                Image(systemName: "fork.knife")
                    .foregroundColor(.orange)
            } compactTrailing: {
                if let eta = context.state.eta {
                    Text(eta, style: .timer)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.orange)
                        .monospacedDigit()
                }
            } minimal: {
                Image(systemName: "fork.knife")
                    .foregroundColor(.orange)
            }
        }
    }

    // MARK: - Lock Screen

    @ViewBuilder
    private func lockScreenView(context: ActivityViewContext<DeliveryAttributes>) -> some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(context.state.statusText)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                    Text(context.attributes.restaurantName)
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }
                Spacer()
                if let eta = context.state.eta {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(eta, style: .timer)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.orange)
                            .monospacedDigit()
                        Text("ETA")
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                    }
                }
            }

            // Progress bar
            progressBar(for: context.state.status)

            HStack {
                HStack(spacing: 4) {
                    Image(systemName: "bag.fill")
                        .font(.system(size: 11))
                    Text("\(context.attributes.itemCount) item\(context.attributes.itemCount == 1 ? "" : "s")")
                        .font(.system(size: 12))
                }
                .foregroundColor(.secondary)

                Spacer()

                if let courier = context.state.courierName, let vehicle = context.state.courierVehicle {
                    HStack(spacing: 4) {
                        Image(systemName: "car.fill")
                            .font(.system(size: 11))
                        Text("\(courier) · \(vehicle)")
                            .font(.system(size: 12))
                            .lineLimit(1)
                    }
                    .foregroundColor(.secondary)
                }
            }
        }
        .padding()
        .activityBackgroundTint(.black)
        .activitySystemActionForegroundColor(.orange)
    }

    // MARK: - Progress

    private func progressDots(for status: String) -> some View {
        let step = stepIndex(for: status)
        return HStack(spacing: 4) {
            ForEach(0..<5, id: \.self) { i in
                Circle()
                    .fill(i <= step ? Color.orange : Color.gray.opacity(0.4))
                    .frame(width: 8, height: 8)
            }
        }
    }

    private func progressBar(for status: String) -> some View {
        let step = stepIndex(for: status)
        let progress = Double(step) / 4.0
        return GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color.gray.opacity(0.3))
                    .frame(height: 5)
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color.orange)
                    .frame(width: geo.size.width * progress, height: 5)
            }
        }
        .frame(height: 5)
    }

    private func stepIndex(for status: String) -> Int {
        switch status {
        case "pending": return 0
        case "accepted": return 1
        case "preparing": return 2
        case "ready": return 3
        case "picked_up": return 4
        case "delivered": return 4
        default: return 0
        }
    }
}
