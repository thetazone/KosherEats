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

    func uploadImage(_ image: UIImage, kind: Kind) async throws -> String {
        guard let jpeg = image.jpegData(compressionQuality: 0.85) else {
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
}
