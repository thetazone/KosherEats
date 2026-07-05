import AuthenticationServices
import CryptoKit
import Foundation
import GoogleSignIn
import SwiftUI
import UIKit

// Apple Sign-In replay-attack mitigation. Client generates a random nonce,
// sends SHA256(nonce) in the request, and Apple echoes the hash into the
// returned JWT's `nonce` claim. Backend re-hashes the raw value we POST and
// matches against the claim — a stolen JWT bound to a different nonce fails.
enum AppleSignInNonce {
    static func generate() -> (raw: String, hashed: String) {
        let charset: [Character] = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        var remaining = 32
        while remaining > 0 {
            var random: UInt8 = 0
            _ = SecRandomCopyBytes(kSecRandomDefault, 1, &random)
            if random < charset.count {
                result.append(charset[Int(random)])
                remaining -= 1
            }
        }
        let hash = SHA256.hash(data: Data(result.utf8))
        let hex = hash.map { String(format: "%02x", $0) }.joined()
        return (result, hex)
    }
}

@MainActor
final class AuthViewModel: ObservableObject {
    @Published var isAuthenticated: Bool = false
    @Published var user: User?
    @Published var profile: CourierProfile?
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?
    /// Set when a profile load fails for a reason that isn't 401. RootView
    /// reads this to swap the infinite spinner for a retry screen.
    @Published var profileError: String?

    private let api = APIService.shared

    init() {
        isAuthenticated = api.isAuthenticated
        if isAuthenticated {
            Task { await loadProfile() }
        }
    }

    func signup(email: String, password: String, firstName: String, lastName: String, phone: String) async {
        isLoading = true
        errorMessage = nil
        do {
            _ = try await api.register(email: email, password: password, firstName: firstName, lastName: lastName, phone: phone)
            isAuthenticated = true
            await loadProfile()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        isLoading = false
    }

    func login(email: String, password: String) async {
        isLoading = true
        errorMessage = nil
        do {
            _ = try await api.login(email: email, password: password)
            isAuthenticated = true
            await loadProfile()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        isLoading = false
    }

    func socialLogin(provider: String, token: String, firstName: String, lastName: String, nonce: String? = nil) async {
        isLoading = true
        errorMessage = nil
        do {
            _ = try await api.socialLogin(provider: provider, token: token, firstName: firstName, lastName: lastName, nonce: nonce)
            isAuthenticated = true
            await loadProfile()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        isLoading = false
    }

    // MARK: - Phone OTP login

    /// Fires Twilio Verify SMS for `phone` (E.164). Returns true on success so
    /// the caller can transition to the code-entry screen. Mirrors the seller's
    /// start/verify split.
    func startPhoneLogin(phone: String) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try await api.startPhoneLogin(phone: phone)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return false
        }
    }

    func silentResendOTP(phone: String) async {
        try? await api.startPhoneLogin(phone: phone)
    }

    /// Verifies the SMS code and signs the courier in. Returns true iff
    /// authenticated. Surfaces "no account" (404) and "not a courier" (403)
    /// with distinct messages so the user knows whether to retry or sign up.
    func verifyPhoneLogin(phone: String, code: String) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            _ = try await api.verifyPhoneLogin(phone: phone, code: code)
            isAuthenticated = true
            await loadProfile()
            return true
        } catch APIError.unauthorized {
            errorMessage = "Invalid or expired code."
            return false
        } catch APIError.httpError(let status, let msg) where status == 404 {
            errorMessage = msg.isEmpty ? "No account found for this phone number." : msg
            return false
        } catch APIError.httpError(let status, let msg) where status == 403 {
            errorMessage = msg.isEmpty ? "This phone number is not registered as a courier account." : msg
            return false
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return false
        }
    }

    func loadProfile() async {
        profileError = nil
        do {
            // Load both the generic user record (name / email / phone — the
            // "who are you") and the courier-specific profile (vehicle,
            // onboarding status, etc.) in parallel. Needed so the completion
            // sheet can gate on missing name / @privaterelay email.
            async let userTask = api.getUser()
            async let profileTask = api.getProfile()
            let (u, p) = try await (userTask, profileTask)
            user = u
            profile = p
        } catch APIError.unauthorized {
            logout()
        } catch let APIError.httpError(code, _) where code == 403 {
            // 403 on /courier/profile means this account isn't a courier —
            // likely a seller/admin Apple ID that got promoted to the wrong role.
            // Hard logout so the user isn't trapped on the retry screen.
            profileError = "This account does not have courier access."
            logout()
        } catch {
            profileError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    @discardableResult
    func updateUserProfile(firstName: String, lastName: String, phone: String, email: String? = nil) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            user = try await api.updateUserProfile(firstName: firstName, lastName: lastName, phone: phone, email: email)
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return false
        }
    }

    /// True when the signed-in courier is genuinely missing a name — legacy
    /// accounts created before the backend persisted Apple's first-auth
    /// `fullName` and started returning it on every sign-in.
    var needsProfileCompletion: Bool {
        guard let u = user else { return false }
        if u.firstName.trimmingCharacters(in: .whitespaces).isEmpty { return true }
        if u.lastName.trimmingCharacters(in: .whitespaces).isEmpty { return true }
        return false
    }

    /// True when the account's email is a synthesized address (Apple's
    /// privaterelay forwarder or the phone-OTP placeholder) we'd LIKE to
    /// replace for payout/delivery notifications — but App Review Guideline 4
    /// forbids demanding one after Sign in with Apple, so this only drives
    /// the optional dashboard banner, never a forced sheet.
    var hasPlaceholderEmail: Bool {
        guard let u = user else { return false }
        let email = u.email.lowercased()
        return email.hasSuffix("@privaterelay.appleid.com")
            || email.hasSuffix("@phone.koshereats.local")
    }

    /// Drives the dismissible dashboard nudge: worth offering the optional
    /// profile-completion sheet, never worth blocking on.
    var shouldOfferProfileCompletion: Bool {
        needsProfileCompletion || hasPlaceholderEmail
    }

    func logout() {
        api.logout()
        isAuthenticated = false
        profile = nil
        user = nil
    }

    func deleteAccount() async {
        isLoading = true
        errorMessage = nil
        do {
            try await api.deleteAccount()
            logout()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        isLoading = false
    }

    // MARK: - Google Sign-In

    func signInWithGoogle() {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let activeScene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        guard let rootVC = activeScene?.windows.first(where: \.isKeyWindow)?.rootViewController
                ?? activeScene?.windows.first?.rootViewController else {
            errorMessage = "Google sign-in failed. Please try again."
            return
        }

        GIDSignIn.sharedInstance.signIn(withPresenting: rootVC) { [weak self] result, error in
            guard let self else { return }
            if let error {
                Task { @MainActor in
                    let nsError = error as NSError
                    if nsError.domain == "com.google.GIDSignIn" && nsError.code == -5 {
                        return
                    }
                    self.errorMessage = "Google sign-in failed. Please try again."
                }
                return
            }
            guard let idToken = result?.user.idToken?.tokenString else {
                Task { @MainActor in
                    self.errorMessage = "Google sign-in failed. Please try again."
                }
                return
            }
            let firstName = result?.user.profile?.givenName ?? ""
            let lastName = result?.user.profile?.familyName ?? ""
            Task {
                await self.socialLogin(provider: "google", token: idToken, firstName: firstName, lastName: lastName)
            }
        }
    }

    // MARK: - Apple Sign-In

    func signInWithApple() {
        let (rawNonce, hashedNonce) = AppleSignInNonce.generate()
        let provider = ASAuthorizationAppleIDProvider()
        let request = provider.createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = hashedNonce

        let coordinator = AppleSignInCoordinator(rawNonce: rawNonce) { [weak self] result in
            guard let self else { return }
            Task { @MainActor in
                defer {
                    self.appleSignInCoordinator = nil
                    self.appleSignInController = nil
                }
                switch result {
                case .success(let (token, firstName, lastName, nonce)):
                    await self.socialLogin(provider: "apple", token: token, firstName: firstName, lastName: lastName, nonce: nonce)
                case .failure(let error):
                    if let authError = error as? ASAuthorizationError, authError.code == .canceled {
                        return
                    }
                    self.errorMessage = "Apple sign-in failed. Please try again."
                }
            }
        }

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = coordinator
        controller.presentationContextProvider = coordinator

        // ASAuthorizationController does NOT self-retain during performRequests().
        // Hold both the coordinator (delegate) and the controller for the
        // duration of the system flow or the callback drops on the floor.
        self.appleSignInCoordinator = coordinator
        self.appleSignInController = controller

        controller.performRequests()
    }

    private var appleSignInCoordinator: AppleSignInCoordinator?
    private var appleSignInController: ASAuthorizationController?
}

// MARK: - Apple Sign-In Coordinator

class AppleSignInCoordinator: NSObject,
                              ASAuthorizationControllerDelegate,
                              ASAuthorizationControllerPresentationContextProviding {
    private let rawNonce: String
    private let completion: (Result<(String, String, String, String), Error>) -> Void

    init(rawNonce: String,
         completion: @escaping (Result<(String, String, String, String), Error>) -> Void) {
        self.rawNonce = rawNonce
        self.completion = completion
    }

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithAuthorization authorization: ASAuthorization) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let identityTokenData = credential.identityToken,
              let identityToken = String(data: identityTokenData, encoding: .utf8) else {
            completion(.failure(NSError(
                domain: "AppleSignIn", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Failed to retrieve Apple identity token."]
            )))
            return
        }

        let firstName = credential.fullName?.givenName ?? ""
        let lastName = credential.fullName?.familyName ?? ""
        completion(.success((identityToken, firstName, lastName, rawNonce)))
    }

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithError error: Error) {
        completion(.failure(error))
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let activeScene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        return activeScene?.windows.first(where: \.isKeyWindow)
            ?? activeScene?.windows.first
            ?? ASPresentationAnchor()
    }
}
