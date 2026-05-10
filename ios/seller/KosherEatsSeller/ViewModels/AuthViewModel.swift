import Foundation
import SwiftUI
import AuthenticationServices
import CryptoKit
import GoogleSignIn

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
class AuthViewModel: ObservableObject {
    @Published var isAuthenticated = false
    @Published var user: User?
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let tokenKey = "ke_seller_token"
    private let refreshTokenKey = "ke_seller_refresh_token"
    private var restoreTask: Task<Void, Never>?

    // Only seller/admin accounts can access the seller dashboard. Consumer
    // accounts (created via Apple/Google social login on this app) are still
    // allowed to authenticate — they land on the onboarding screen instead of
    // the main tabs. This is required for App Review: the reviewer's Apple ID
    // hits /auth/social and gets a consumer role, and the flow must succeed
    // visibly (guideline 4.8) even though they can't manage a restaurant.
    var hasSellerAccess: Bool {
        guard let role = user?.role else { return false }
        return role == .seller || role == .admin
    }

    init() {
        // APIService.init() pre-loads tokens from Keychain synchronously, so
        // they are set before any concurrent API call can fire. This Task only
        // needs to validate the token and restore the user profile.
        if KeychainHelper.load(forKey: tokenKey) != nil {
            restoreTask = Task {
                // Restore both the token AND the user on cold start. Without
                // loading the profile here, `self.user` stays nil and
                // `hasSellerAccess` returns false, which routes a real seller
                // into SellerOnboardingView on every relaunch. `/user/profile`
                // also doubles as the token-validity probe — a 401 means the
                // token is expired so we clear and drop to the login screen.
                do {
                    let me = try await APIService.shared.getProfile()
                    self.user = me
                    self.isAuthenticated = true
                } catch APIError.unauthorized {
                    KeychainHelper.delete(forKey: self.tokenKey)
                    KeychainHelper.delete(forKey: self.refreshTokenKey)
                    await APIService.shared.setToken(nil)
                    await APIService.shared.setRefreshToken(nil)
                    self.isAuthenticated = false
                } catch {
                    // Network / server errors on launch: don't unilaterally log
                    // the seller out, but also don't pretend to know their role
                    // — stay on the login screen so a retry can re-authenticate
                    // cleanly instead of landing them on the wrong tab.
                    self.isAuthenticated = false
                }
            }
        }
    }

    func login(email: String, password: String) async {
        isLoading = true
        errorMessage = nil

        do {
            let response = try await APIService.shared.login(email: email, password: password)

            guard response.user.role == .seller || response.user.role == .admin else {
                errorMessage = "This account is not registered as a seller."
                isLoading = false
                return
            }

            KeychainHelper.save(response.token, forKey: tokenKey)
            KeychainHelper.save(response.refreshToken, forKey: refreshTokenKey)
            await APIService.shared.setToken(response.token)
            await APIService.shared.setRefreshToken(response.refreshToken)

            self.user = response.user
            self.isAuthenticated = true
        } catch APIError.unauthorized {
            // 401 on /auth/login means the credentials didn't match — NOT an
            // expired session. The generic .unauthorized message ("Session
            // expired…") reads as a bug to anyone who hasn't logged in yet.
            errorMessage = "Invalid email or password."
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    /// Email-based sign-up for the seller app. Creates a consumer-role account
    /// via /auth/register; the user lands on SellerOnboardingView (same pattern
    /// as Apple/Google for non-seller roles) because there's no public
    /// self-service seller registration.
    func signup(email: String, password: String, firstName: String, lastName: String) async {
        isLoading = true
        errorMessage = nil

        do {
            let response = try await APIService.shared.register(
                email: email, password: password,
                firstName: firstName, lastName: lastName
            )

            KeychainHelper.save(response.token, forKey: tokenKey)
            KeychainHelper.save(response.refreshToken, forKey: refreshTokenKey)
            await APIService.shared.setToken(response.token)
            await APIService.shared.setRefreshToken(response.refreshToken)

            self.user = response.user
            self.isAuthenticated = true
        } catch APIError.serverError(409, _) {
            errorMessage = "An account already exists for this email."
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    // MARK: - Phone OTP Login

    /// Triggers Twilio Verify SMS for `phone` (must be E.164). Returns true on
    /// success so the view can transition to the OTP-entry screen.
    func startPhoneLogin(phone: String) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try await APIService.shared.startPhoneLogin(phone: phone)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func silentResendOTP(phone: String) async {
        try? await APIService.shared.startPhoneLogin(phone: phone)
    }

    /// Verifies the SMS code. Mirrors `login` — same role gate, same token
    /// persistence. Returns true if the user is now authenticated.
    func verifyPhoneLogin(phone: String, code: String) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let response = try await APIService.shared.verifyPhoneLogin(phone: phone, code: code)

            guard response.user.role == .seller || response.user.role == .admin else {
                errorMessage = "This phone number is not registered as a seller."
                return false
            }

            KeychainHelper.save(response.token, forKey: tokenKey)
            KeychainHelper.save(response.refreshToken, forKey: refreshTokenKey)
            await APIService.shared.setToken(response.token)
            await APIService.shared.setRefreshToken(response.refreshToken)

            self.user = response.user
            self.isAuthenticated = true
            return true
        } catch APIError.unauthorized {
            errorMessage = "Invalid or expired code."
            return false
        } catch APIError.serverError(let status, let msg) where status == 404 {
            errorMessage = msg.isEmpty ? "No account found for this phone number." : msg
            return false
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    /// Signs in as the App Store review team's shared demo seller. Paired
    /// with POST /auth/reviewer/seller on the backend — temporary, meant to
    /// be deleted once a self-serve demo path exists.
    func reviewerSellerLogin() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let response = try await APIService.shared.reviewerSellerLogin()

            KeychainHelper.save(response.token, forKey: tokenKey)
            KeychainHelper.save(response.refreshToken, forKey: refreshTokenKey)
            await APIService.shared.setToken(response.token)
            await APIService.shared.setRefreshToken(response.refreshToken)

            self.user = response.user
            self.isAuthenticated = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func socialLogin(provider: String, token: String, firstName: String, lastName: String, nonce: String? = nil) async {
        isLoading = true
        errorMessage = nil

        do {
            let response = try await APIService.shared.socialLogin(
                provider: provider,
                token: token,
                firstName: firstName,
                lastName: lastName,
                nonce: nonce
            )

            // Non-sellers are allowed to authenticate on the seller app — they
            // land on SellerOnboardingView rather than the dashboard. Role
            // gating is handled by `hasSellerAccess` in the root view, not by
            // rejecting at this stage.
            KeychainHelper.save(response.token, forKey: tokenKey)
            KeychainHelper.save(response.refreshToken, forKey: refreshTokenKey)
            await APIService.shared.setToken(response.token)
            await APIService.shared.setRefreshToken(response.refreshToken)

            self.user = response.user
            self.isAuthenticated = true
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    // MARK: - Google Sign-In

    func signInWithGoogle() {
        // Prefer the foreground-active scene's key window so the Google sheet
        // anchors correctly on iPad and in multi-window states. `scenes.first`
        // is whatever order the runtime returns — often wrong.
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
                    // Google throws -5 ("The user canceled the sign-in flow.")
                    // when the user dismisses the sheet. That's not a failure.
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
    //
    // Apple returns the user's real name only on the FIRST authorization grant.
    // On subsequent sign-ins `credential.fullName` is nil — the backend is
    // responsible for persisting the name on first insert (keyed off the
    // stable `sub` claim in the identity token) so that's the source of truth.
    //
    // TODO(security): when the backend starts verifying Apple identity token
    // signatures against https://appleid.apple.com/auth/keys, add nonce
    // replay protection here: generate a random value, set
    // `request.nonce = sha256(raw)`, and send `raw` to the backend for the
    // nonce-match check. Until then, a client-side nonce is cosmetic.

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
                    // Release the coordinator + controller once the flow ends
                    // (success, failure, or cancel). Otherwise each tap leaks
                    // a coordinator + controller pair.
                    self.appleSignInCoordinator = nil
                    self.appleSignInController = nil
                }
                switch result {
                case .success(let (token, firstName, lastName, nonce)):
                    await self.socialLogin(provider: "apple", token: token, firstName: firstName, lastName: lastName, nonce: nonce)
                case .failure(let error):
                    // User tapping "Cancel" is not an error — don't flash an
                    // alarming toast. Only surface real failures.
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

        // Retain both the coordinator (delegate target) and the controller for
        // the duration of the async system flow. ASAuthorizationController
        // does NOT self-retain during `performRequests()`, so losing the
        // reference here would drop the callback on the floor.
        self.appleSignInCoordinator = coordinator
        self.appleSignInController = controller

        controller.performRequests()
    }

    private var appleSignInCoordinator: AppleSignInCoordinator?
    private var appleSignInController: ASAuthorizationController?

    func logout() {
        restoreTask?.cancel()
        restoreTask = nil
        KeychainHelper.delete(forKey: tokenKey)
        KeychainHelper.delete(forKey: refreshTokenKey)
        Task {
            await APIService.shared.setToken(nil)
            await APIService.shared.setRefreshToken(nil)
        }
        user = nil
        isAuthenticated = false
        PushNotifications.shared.pendingToken = nil
    }

    func deleteAccount() async {
        isLoading = true
        errorMessage = nil
        do {
            try await APIService.shared.deleteAccount()
            logout()
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    @discardableResult
    func updateProfile(firstName: String, lastName: String, phone: String, email: String? = nil) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            user = try await APIService.shared.updateProfile(
                firstName: firstName, lastName: lastName, phone: phone, email: email
            )
            return true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return false
        }
    }

    /// True when the signed-in user is still missing identity info — e.g.
    /// Apple sign-in where `fullName` was nil on a return authorization, or
    /// a @privaterelay.appleid.com address we'd rather replace.
    var needsProfileCompletion: Bool {
        guard let u = user else { return false }
        let email = u.email.lowercased()
        if u.firstName.trimmingCharacters(in: .whitespaces).isEmpty { return true }
        if u.lastName.trimmingCharacters(in: .whitespaces).isEmpty { return true }
        if email.hasSuffix("@privaterelay.appleid.com") { return true }
        if email.hasSuffix("@phone.koshereats.local") { return true }
        return false
    }
}

// MARK: - Apple Sign-In Coordinator
//
// Conforms to both the delegate and the presentation-context provider so
// Apple's UI anchors to the correct window on iPad and in multi-scene apps.

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

    // MARK: ASAuthorizationControllerDelegate

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

    // MARK: ASAuthorizationControllerPresentationContextProviding

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        // Find the foreground-active scene's key window. Falling back to any
        // available window rather than constructing a stray UIWindow() avoids
        // Apple's sheet presenting on an orphaned anchor in edge cases.
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let activeScene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        return activeScene?.windows.first(where: \.isKeyWindow)
            ?? activeScene?.windows.first
            ?? ASPresentationAnchor()
    }
}
