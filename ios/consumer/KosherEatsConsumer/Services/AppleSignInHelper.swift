import AuthenticationServices
import Foundation

class AppleSignInDelegate: NSObject, ASAuthorizationControllerDelegate {
    private let onSuccess: (String, String, String) -> Void
    private let onError: (String) -> Void

    init(
        onSuccess: @escaping (String, String, String) -> Void,
        onError: @escaping (String) -> Void
    ) {
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

        onSuccess(identityTokenString, firstName, lastName)
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        onError(error.localizedDescription)
    }
}
