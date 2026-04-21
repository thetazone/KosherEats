import Foundation
import UIKit

// UploadService handles the presign -> PUT -> return public URL dance.
// Used for onboarding documents and (eventually) profile photos.
//
// Dev stub mode: when the backend returns a "stub://" URL, we short-circuit
// and just return that string without making any HTTP call. This keeps the
// onboarding flow working end-to-end without real S3 credentials.
@MainActor
final class UploadService {
    static let shared = UploadService()

    enum UploadKind: String {
        case license = "courier/license"
        case insurance = "courier/insurance"
        case registration = "courier/registration"
        case profile = "courier/profile"
        case deliveryProof = "delivery_proof"
    }

    struct PresignResponse: Decodable {
        let uploadUrl: String
        let publicUrl: String
        let key: String
        let expiresIn: Int

        enum CodingKeys: String, CodingKey {
            case key
            case uploadUrl = "upload_url"
            case publicUrl = "public_url"
            case expiresIn = "expires_in"
        }
    }

    // uploadImage is a one-shot helper: takes a UIImage, asks the backend for
    // a presigned URL, PUTs the JPEG, returns the resulting public URL.
    func uploadImage(_ image: UIImage, kind: UploadKind) async throws -> String {
        let jpegQuality: CGFloat = 0.85
        guard let jpeg = image.jpegData(compressionQuality: jpegQuality) else {
            throw NSError(domain: "upload", code: 1, userInfo: [NSLocalizedDescriptionKey: "failed to encode image"])
        }

        let presign = try await APIService.shared.presignUpload(kind: kind.rawValue, contentType: "image/jpeg")

        // Stub mode: backend returned a placeholder. Skip the actual PUT.
        if presign.uploadUrl.hasPrefix("stub://") {
            return presign.publicUrl
        }

        guard let url = URL(string: presign.uploadUrl) else {
            throw NSError(domain: "upload", code: 2, userInfo: [NSLocalizedDescriptionKey: "invalid upload URL"])
        }

        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        req.httpBody = jpeg
        req.timeoutInterval = 30

        // Use a dedicated session so that invalidateAndCancel() in the onCancel
        // handler aborts only this request and not any other in-flight tasks.
        let session = URLSession(configuration: .default)
        defer { session.finishTasksAndInvalidate() }
        let (_, response) = try await withTaskCancellationHandler {
            try await session.data(for: req)
        } onCancel: {
            // group.cancelAll() in uploadImageWithTimeout only sets Task.isCancelled;
            // explicitly cancel the URLSession task so the S3 PUT is actually aborted.
            session.invalidateAndCancel()
        }
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw NSError(domain: "upload", code: 3, userInfo: [NSLocalizedDescriptionKey: "S3 PUT failed"])
        }

        return presign.publicUrl
    }
}
