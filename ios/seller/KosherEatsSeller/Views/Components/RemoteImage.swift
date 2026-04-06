import SwiftUI

/// RemoteImage with shimmer loading + symbol fallback. Same component the
/// consumer app uses; kept per-target so each target has its own copy rather
/// than a shared module.
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
                errorFallback
            }
        }
        .clipped()
    }

    private var skeleton: some View {
        Rectangle()
            .fill(Color.keBorder)
            .overlay(ShimmerOverlay().opacity(0.4))
    }

    private var errorFallback: some View {
        Rectangle()
            .fill(Color.keBorder)
            .overlay(
                Image(systemName: fallbackSymbol)
                    .font(.system(size: 24))
                    .foregroundColor(.keTextMuted.opacity(0.5)),
            )
    }
}
