import AuthenticationServices
import CryptoKit
import Foundation
import UIKit

// AppleSignInNonce generates a cryptographically random raw nonce and exposes
// its SHA256 hash. Apple's recommended replay-attack mitigation: the hashed
// nonce goes into request.nonce and Apple bakes it into the returned JWT's
// `nonce` claim. The backend verifies that the JWT's nonce matches a SHA256
// of the raw nonce we send up alongside the token.
enum AppleSignInNonce {
    static func generate() -> (raw: String, hashed: String) {
        let raw = randomNonceString()
        let hashed = sha256Hex(raw)
        return (raw, hashed)
    }

    private static func randomNonceString(length: Int = 32) -> String {
        precondition(length > 0)
        let charset: [Character] = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        var remainingLength = length
        while remainingLength > 0 {
            let randoms: [UInt8] = (0..<16).map { _ in
                var random: UInt8 = 0
                let status = SecRandomCopyBytes(kSecRandomDefault, 1, &random)
                precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
                return random
            }
            for r in randoms where remainingLength > 0 {
                if r < charset.count {
                    result.append(charset[Int(r)])
                    remainingLength -= 1
                }
            }
        }
        return result
    }

    private static func sha256Hex(_ input: String) -> String {
        let data = Data(input.utf8)
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

class AppleSignInDelegate: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }

    private let rawNonce: String
    private let onSuccess: (_ token: String, _ firstName: String, _ lastName: String, _ rawNonce: String) -> Void
    private let onError: (String) -> Void

    init(
        rawNonce: String,
        onSuccess: @escaping (String, String, String, String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        self.rawNonce = rawNonce
        self.onSuccess = onSuccess
        self.onError = onError
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let identityTokenData = credential.identityToken,
              let identityTokenString = String(data: identityTokenData, encoding: .utf8)
        else {
            onError("Failed to get Apple ID credentials")
            return
        }

        let firstName = credential.fullName?.givenName ?? ""
        let lastName = credential.fullName?.familyName ?? ""

        onSuccess(identityTokenString, firstName, lastName, rawNonce)
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        onError(error.localizedDescription)
    }
}
