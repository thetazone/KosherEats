import SwiftUI

/// RemoteImage is a thin wrapper around AsyncImage that gives every image
/// in the app a consistent placeholder (shimmer/skeleton), an error fallback,
/// and handles empty URL strings gracefully.
///
/// Using a single wrapper means restaurant cards, menu items, and restaurant
/// hero images all look identical while loading and on error — critical for
/// a premium delivery app feel.
struct RemoteImage: View {
    let url: String?
    var contentMode: ContentMode = .fill
    var fallbackSymbol: String = "fork.knife"

    var body: some View {
        ZStack {
            if let urlString = url, !urlString.isEmpty, let parsed = URL(string: urlString) {
                AsyncImage(url: parsed, transaction: Transaction(animation: .easeInOut(duration: 0.25))) { phase in
                    switch phase {
                    case .empty:
                        skeleton
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(contentMode: contentMode)
                            .transition(.opacity)
                    case .failure:
                        errorFallback
                    @unknown default:
                        skeleton
                    }
                }
            } else {
                // No URL at all — skip network, show fallback directly.
                errorFallback
            }
        }
        .clipped()
    }

    private var skeleton: some View {
        // Subtle animated shimmer. Reuses the same ShimmerOverlay used by
        // list skeletons so the whole loading state feels cohesive.
        Rectangle()
            .fill(Color.keCardHover)
            .overlay(
                ShimmerOverlay()
                    .opacity(0.4),
            )
    }

    private var errorFallback: some View {
        Rectangle()
            .fill(Color.keCardHover)
            .overlay(
                Image(systemName: fallbackSymbol)
                    .font(.system(size: 24))
                    .foregroundColor(.keTextMuted.opacity(0.5)),
            )
    }
}

// ShimmerOverlay now lives in Components/SkeletonViews.swift so it can be
// shared between RemoteImage and the list-row skeleton cards.
