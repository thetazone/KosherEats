import Foundation
import SwiftUI
import AuthenticationServices
import GoogleSignIn

@MainActor
class AuthViewModel: ObservableObject {
    @Published var isAuthenticated = false
    @Published var user: User?
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let tokenKey = "ke_seller_token"
    private let refreshTokenKey = "ke_seller_refresh_token"

    init() {
        if let token = UserDefaults.standard.string(forKey: tokenKey) {
            Task {
                await APIService.shared.setToken(token)
                // Verify the stored token is still valid before showing the
                // main tabs. If it 401s (expired), clear it and drop back to
                // the login screen — otherwise the first seller API call on
                // the home tab would show "session expired" with no clear
                // path back to the login form.
                do {
                    _ = try await APIService.shared.listRestaurants()
                    self.isAuthenticated = true
                } catch APIError.unauthorized {
                    UserDefaults.standard.removeObject(forKey: self.tokenKey)
                    UserDefaults.standard.removeObject(forKey: self.refreshTokenKey)
                    await APIService.shared.setToken(nil)
                    self.isAuthenticated = false
                } catch {
                    // Network / server errors: optimistically stay logged in,
                    // the user can retry once connectivity comes back.
                    self.isAuthenticated = true
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

            UserDefaults.standard.set(response.token, forKey: tokenKey)
            UserDefaults.standard.set(response.refreshToken, forKey: refreshTokenKey)
            await APIService.shared.setToken(response.token)

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

    func socialLogin(provider: String, token: String, firstName: String, lastName: String) async {
        isLoading = true
        errorMessage = nil

        do {
            let response = try await APIService.shared.socialLogin(
                provider: provider,
                token: token,
                firstName: firstName,
                lastName: lastName
            )

            guard response.user.role == .seller || response.user.role == .admin else {
                errorMessage = "This account is not registered as a seller."
                isLoading = false
                return
            }

            UserDefaults.standard.set(response.token, forKey: tokenKey)
            UserDefaults.standard.set(response.refreshToken, forKey: refreshTokenKey)
            await APIService.shared.setToken(response.token)

            self.user = response.user
            self.isAuthenticated = true
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    // MARK: - Google Sign-In

    func signInWithGoogle() {
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = windowScene.windows.first?.rootViewController else {
            errorMessage = "Unable to find root view controller."
            return
        }

        GIDSignIn.sharedInstance.signIn(withPresenting: rootVC) { [weak self] result, error in
            guard let self else { return }
            if let error {
                Task { @MainActor in self.errorMessage = error.localizedDescription }
                return
            }
            guard let idToken = result?.user.idToken?.tokenString else {
                Task { @MainActor in self.errorMessage = "Failed to retrieve Google ID token." }
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
        let provider = ASAuthorizationAppleIDProvider()
        let request = provider.createRequest()
        request.requestedScopes = [.fullName, .email]

        let delegate = AppleSignInDelegate { [weak self] result in
            guard let self else { return }
            Task { @MainActor in
                switch result {
                case .success(let (token, firstName, lastName)):
                    await self.socialLogin(provider: "apple", token: token, firstName: firstName, lastName: lastName)
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
        // Retain the delegate for the duration of the auth flow
        self.appleSignInDelegate = delegate

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = delegate
        controller.performRequests()
    }

    private var appleSignInDelegate: AppleSignInDelegate?

    func logout() {
        UserDefaults.standard.removeObject(forKey: tokenKey)
        UserDefaults.standard.removeObject(forKey: refreshTokenKey)
        Task {
            await APIService.shared.setToken(nil)
        }
        user = nil
        isAuthenticated = false
    }
}

// MARK: - Apple Sign-In Delegate

class AppleSignInDelegate: NSObject, ASAuthorizationControllerDelegate {
    private let completion: (Result<(String, String, String), Error>) -> Void

    init(completion: @escaping (Result<(String, String, String), Error>) -> Void) {
        self.completion = completion
    }

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithAuthorization authorization: ASAuthorization) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let identityTokenData = credential.identityToken,
              let identityToken = String(data: identityTokenData, encoding: .utf8) else {
            completion(.failure(NSError(domain: "AppleSignIn", code: -1,
                                       userInfo: [NSLocalizedDescriptionKey: "Failed to retrieve Apple identity token."])))
            return
        }

        let firstName = credential.fullName?.givenName ?? ""
        let lastName = credential.fullName?.familyName ?? ""
        completion(.success((identityToken, firstName, lastName)))
    }

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithError error: Error) {
        completion(.failure(error))
    }
}
