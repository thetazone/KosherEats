import SwiftUI

/// Skeleton placeholder views used while data is loading. Paired with the
/// same shimmer effect that RemoteImage uses so the whole loading state
/// feels cohesive.
///
/// Pattern: when a list is loading for the first time, we show 4–6 skeleton
/// rows matching the real row shape. Much better UX than a center spinner —
/// the layout doesn't pop when data arrives, and the user can see what's
/// coming.

// MARK: - Reusable shimmer primitive

/// Shimmering rounded rectangle. Drop-in replacement for any fixed-size box
/// while waiting on content. Matches the gray family of keCardHover so the
/// skeleton blends into the dark theme.
struct SkeletonBlock: View {
    var cornerRadius: CGFloat = 8
    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .fill(Color.keCardHover)
            .overlay(ShimmerOverlay().opacity(0.35))
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            .drawingGroup()
            .accessibilityHidden(true)
    }
}

/// Shimmer gradient that sweeps left-to-right. Used by both RemoteImage and
/// the skeleton blocks above — kept here so the two stay visually identical.
struct ShimmerOverlay: View {
    @State private var phase: CGFloat = -1

    var body: some View {
        GeometryReader { geo in
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .white.opacity(0.18), location: 0.5),
                    .init(color: .clear, location: 1),
                ],
                startPoint: .leading,
                endPoint: .trailing,
            )
            .frame(width: geo.size.width * 2)
            .offset(x: phase * geo.size.width)
            .onAppear {
                withAnimation(.linear(duration: 1.4).repeatForever(autoreverses: false)) {
                    phase = 1
                }
            }
        }
    }
}

// MARK: - Restaurant card skeleton

/// Matches RestaurantCardView's layout (90×90 image + stacked text) so the
/// list doesn't jump when real data arrives.
struct RestaurantCardSkeleton: View {
    var body: some View {
        HStack(spacing: 14) {
            SkeletonBlock(cornerRadius: Theme.cornerRadiusMedium)
                .frame(width: 90, height: 90)

            VStack(alignment: .leading, spacing: 8) {
                SkeletonBlock().frame(height: 16).frame(maxWidth: 180)
                SkeletonBlock().frame(height: 12).frame(maxWidth: 120)
                SkeletonBlock().frame(height: 12).frame(maxWidth: 150)
                HStack(spacing: 10) {
                    SkeletonBlock().frame(width: 40, height: 12)
                    SkeletonBlock().frame(width: 50, height: 12)
                    SkeletonBlock().frame(width: 40, height: 12)
                }
            }
            Spacer()
        }
        .padding(12)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .accessibilityElement()
        .accessibilityLabel("Loading restaurant")
    }
}

// MARK: - Order row skeleton

struct OrderRowSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 6) {
                    SkeletonBlock().frame(width: 160, height: 16)
                    SkeletonBlock().frame(width: 100, height: 12)
                }
                Spacer()
                SkeletonBlock(cornerRadius: 12).frame(width: 70, height: 22)
            }
            SkeletonBlock().frame(height: 12)
            HStack {
                SkeletonBlock().frame(width: 80, height: 14)
                Spacer()
                SkeletonBlock().frame(width: 60, height: 14)
            }
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .accessibilityElement()
        .accessibilityLabel("Loading order")
    }
}

// MARK: - Menu item row skeleton

struct MenuItemSkeleton: View {
    var body: some View {
        HStack(spacing: 14) {
            SkeletonBlock(cornerRadius: Theme.cornerRadiusSmall)
                .frame(width: 72, height: 72)

            VStack(alignment: .leading, spacing: 6) {
                SkeletonBlock().frame(width: 160, height: 14)
                SkeletonBlock().frame(height: 11).frame(maxWidth: 220)
                SkeletonBlock().frame(width: 60, height: 14)
            }
            Spacer()
        }
        .padding(12)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .accessibilityElement()
        .accessibilityLabel("Loading menu item")
    }
}
