import SwiftUI
import ImageIO
import UIKit

/// RemoteImage is a thin wrapper that gives every image in the app a consistent
/// placeholder (shimmer/skeleton), an error fallback (with tap-to-retry), and
/// handles empty URL strings gracefully.
///
/// Using a single wrapper means restaurant cards, menu items, and restaurant
/// hero images all look identical while loading and on error — critical for
/// a premium delivery app feel.
///
/// Unlike a raw AsyncImage, RemoteImage loads through `RemoteImageLoader`, which:
///   * uses a dedicated URLSession backed by a generously-sized URLCache so
///     seller-uploaded photos are not re-downloaded on scroll-back or relaunch;
///   * downsamples arbitrary-resolution source images to the on-screen size via
///     ImageIO, so huge uploads don't blow up memory or decode time.
struct RemoteImage: View {
    let url: String?
    var contentMode: ContentMode = .fill
    var fallbackSymbol: String = "fork.knife"
    var accessibilityLabel: String = "Image"

    @StateObject private var loader = RemoteImageLoader()

    var body: some View {
        ZStack {
            GeometryReader { proxy in
                content(targetSize: proxy.size)
                    .frame(width: proxy.size.width, height: proxy.size.height)
            }
        }
        .clipped()
        .task(id: url) {
            await loader.load(urlString: url)
        }
    }

    @ViewBuilder
    private func content(targetSize: CGSize) -> some View {
        switch loader.state {
        case .idle, .loading:
            skeleton
        case .success(let image):
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: contentMode)
                .transition(.opacity)
                .accessibilityLabel(accessibilityLabel)
        case .failure, .noURL:
            errorFallback
        }
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
            // Tap to retry: only meaningful when there was actually a URL to fetch.
            .contentShape(Rectangle())
            .onTapGesture {
                guard case .failure = loader.state else { return }
                Task { await loader.load(urlString: url, force: true) }
            }
    }
}

/// Loads, caches, and downsamples remote images for `RemoteImage`.
///
/// Caching: backed by a single shared, large `URLCache` (memory + disk) so
/// images survive scroll-back and app relaunch instead of re-downloading
/// through the tiny default `URLCache.shared`.
///
/// Downsampling: decodes via `CGImageSourceCreateThumbnailAtIndex` so a 4000px
/// seller upload becomes a small bitmap sized for the screen, avoiding the
/// memory spikes and slow decodes of full-size images in a scrolling feed.
@MainActor
final class RemoteImageLoader: ObservableObject {
    enum State {
        case idle
        case loading
        case success(UIImage)
        case failure
        case noURL
    }

    @Published private(set) var state: State = .idle

    // Shared session with a real cache. URLSession.shared uses the tiny default
    // URLCache, which is purely HTTP-header driven and far too small for a media
    // feed; this gives us a dedicated, generously-sized store.
    private static let session: URLSession = {
        let cache = URLCache(
            memoryCapacity: 64 * 1024 * 1024,   // 64 MB
            diskCapacity: 256 * 1024 * 1024,    // 256 MB
            diskPath: "ke_remote_images",
        )
        let config = URLSessionConfiguration.default
        config.urlCache = cache
        config.requestCachePolicy = .returnCacheDataElseLoad
        return URLSession(configuration: config)
    }()

    // In-memory decoded-image cache keyed by URL string. URLCache stores the raw
    // bytes; this avoids re-decoding/re-downsampling on every scroll-back.
    private static let decoded = NSCache<NSString, UIImage>()

    // Max pixel dimension for downsampling. The on-screen point size times the
    // screen scale; capped so a giant container doesn't request a giant bitmap.
    private static let maxPixelSize: CGFloat = 1024

    private var currentURL: String?

    func load(urlString: String?, force: Bool = false) async {
        guard let urlString, !urlString.isEmpty, let parsed = URL(string: urlString) else {
            state = .noURL
            currentURL = nil
            return
        }

        // Avoid redundant reloads for the same URL unless explicitly retrying.
        if !force, currentURL == urlString, case .success = state { return }
        currentURL = urlString

        let key = urlString as NSString
        if !force, let cached = Self.decoded.object(forKey: key) {
            state = .success(cached)
            return
        }

        state = .loading

        var request = URLRequest(url: parsed)
        if force {
            request.cachePolicy = .reloadIgnoringLocalCacheData
        }

        do {
            let (data, _) = try await Self.session.data(for: request)
            // Ensure the request hasn't been superseded by a newer URL.
            guard currentURL == urlString else { return }
            if let image = Self.downsample(data: data, maxPixelSize: Self.maxPixelSize) {
                Self.decoded.setObject(image, forKey: key)
                state = .success(image)
            } else {
                state = .failure
            }
        } catch {
            guard currentURL == urlString else { return }
            state = .failure
        }
    }

    /// Decodes image data into a downsampled UIImage using ImageIO, so we never
    /// hold a full-resolution bitmap for a small on-screen frame.
    private nonisolated static func downsample(data: Data, maxPixelSize: CGFloat) -> UIImage? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else {
            return nil
        }
        let scaledMax = maxPixelSize * UIScreen.main.scale
        let downsampleOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: scaledMax,
        ] as CFDictionary
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, downsampleOptions) else {
            // Fall back to a plain decode if thumbnailing fails (e.g. odd format).
            return UIImage(data: data)
        }
        return UIImage(cgImage: cgImage)
    }
}

// ShimmerOverlay now lives in Components/SkeletonViews.swift so it can be
// shared between RemoteImage and the list-row skeleton cards.
