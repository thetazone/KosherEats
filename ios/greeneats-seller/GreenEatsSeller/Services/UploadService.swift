import Foundation
import UIKit

/// UploadService for seller menu item photos. Same shape as the courier
/// app's UploadService: presign → PUT to S3 → return public URL. Stub-aware
/// (returns the placeholder URL unchanged in dev mode).
@MainActor
final class UploadService {
    static let shared = UploadService()

    enum Kind: String {
        case menuItem = "menu_item"
        case restaurantCover = "restaurant/cover"
        case restaurantLogo = "restaurant/logo"
        case deal = "deal"
        case certificate = "restaurant/certificate"
    }

    private let plainHttp: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 60
        return URLSession(configuration: cfg)
    }()

    /// Maximum pixel length on the longest side. Images larger than this are
    /// scaled down (aspect-ratio preserved) before JPEG encoding so sellers
    /// uploading 12 MP+ iPhone photos don't waste bandwidth and S3 storage.
    private static let maxLongestSide: CGFloat = 1200

    func uploadImage(_ image: UIImage, kind: Kind) async throws -> String {
        let resized = Self.resizedIfNeeded(image)
        guard let jpeg = resized.jpegData(compressionQuality: 0.85) else {
            throw NSError(domain: "upload", code: 1, userInfo: [NSLocalizedDescriptionKey: "failed to encode image"])
        }

        let presign = try await APIService.shared.presignUpload(kind: kind.rawValue, contentType: "image/jpeg")
        if presign.isStub {
            return presign.publicUrl
        }

        guard let url = URL(string: presign.uploadUrl),
              url.scheme == "https" else {
            throw NSError(domain: "upload", code: 2, userInfo: [NSLocalizedDescriptionKey: "invalid or insecure upload URL"])
        }

        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")

        let (_, response) = try await plainHttp.upload(for: req, from: jpeg)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw NSError(domain: "upload", code: 3, userInfo: [NSLocalizedDescriptionKey: "S3 upload failed"])
        }
        return presign.publicUrl
    }

    // MARK: - Image resizing

    /// Returns the image scaled down so its longest side is at most
    /// `maxLongestSide`. Already-small images are returned unchanged.
    private static func resizedIfNeeded(_ image: UIImage) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxLongestSide else { return image }
        let scale = maxLongestSide / longest
        let newSize = CGSize(width: (image.size.width * scale).rounded(.down),
                             height: (image.size.height * scale).rounded(.down))
        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}
