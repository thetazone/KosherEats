import SwiftUI

/// Shared shimmer + skeleton row primitives for the seller app. Matches the
/// visual vocabulary of the consumer app (same grays, same 1.4s shimmer) so
/// a single designer understands both apps at a glance.

struct SkeletonBlock: View {
    var cornerRadius: CGFloat = 8
    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .fill(Color.keBorder)
            .overlay(ShimmerOverlay().opacity(0.35))
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

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

/// Active order card skeleton — matches the height/spacing of ActiveOrderCard
/// so the dashboard doesn't jump when real data arrives.
struct ActiveOrderCardSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 6) {
                    SkeletonBlock().frame(width: 140, height: 16)
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
        .cornerRadius(16)
    }
}

/// Menu item row skeleton for the management screen.
struct MenuItemRowSkeleton: View {
    var body: some View {
        HStack(spacing: 12) {
            SkeletonBlock(cornerRadius: 8)
                .frame(width: 60, height: 60)
            VStack(alignment: .leading, spacing: 6) {
                SkeletonBlock().frame(width: 160, height: 14)
                SkeletonBlock().frame(height: 11).frame(maxWidth: 200)
                SkeletonBlock().frame(width: 60, height: 12)
            }
            Spacer()
        }
        .padding(12)
        .background(Color.keCard)
        .cornerRadius(12)
    }
}

/// Dashboard stats grid skeleton — 4 tiles laid out like StatCard.
struct StatsGridSkeleton: View {
    var body: some View {
        LazyVGrid(columns: [
            GridItem(.flexible(), spacing: 12),
            GridItem(.flexible(), spacing: 12),
        ], spacing: 12) {
            ForEach(0..<4, id: \.self) { _ in
                VStack(alignment: .leading, spacing: 12) {
                    SkeletonBlock(cornerRadius: 6).frame(width: 24, height: 24)
                    SkeletonBlock().frame(width: 60, height: 22)
                    SkeletonBlock().frame(width: 90, height: 11)
                }
                .padding()
                .background(Color.keCard)
                .cornerRadius(16)
            }
        }
    }
}
