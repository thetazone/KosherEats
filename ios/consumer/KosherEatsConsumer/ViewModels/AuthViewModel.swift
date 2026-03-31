import Foundation
import SwiftUI
import AuthenticationServices
import GoogleSignIn
import FacebookLogin

@MainActor
class AuthViewModel: ObservableObject {
    @Published var user: User?
    @Published var isAuthenticated = false
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIService.shared
    private var appleSignInDelegate: AppleSignInDelegate?

    init() {
        isAuthenticated = api.isAuthenticated
        if isAuthenticated {
            Task { await loadProfile() }
        }
    }

    func login(email: String, password: String) async {
        isLoading = true
        errorMessage = nil

        do {
            let response = try await api.login(email: email, password: password)
            user = response.user
            isAuthenticated = true
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func register(email: String, password: String, firstName: String, lastName: String, phone: String) async {
        isLoading = true
        errorMessage = nil

        do {
            let response = try await api.register(
                email: email,
                password: password,
                firstName: firstName,
                lastName: lastName,
                phone: phone
            )
            user = response.user
            isAuthenticated = true
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func socialLogin(provider: String, token: String, firstName: String, lastName: String) async {
        isLoading = true
        errorMessage = nil

        do {
            let response = try await api.socialLogin(provider: provider, token: token, firstName: firstName, lastName: lastName)
            user = response.user
            isAuthenticated = true
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    // MARK: - Apple Sign-In

    func signInWithApple() {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.email, .fullName]

        appleSignInDelegate = AppleSignInDelegate(
            onSuccess: { [weak self] token, firstName, lastName in
                guard let self else { return }
                Task { @MainActor in
                    await self.socialLogin(provider: "apple", token: token, firstName: firstName, lastName: lastName)
                }
            },
            onError: { [weak self] message in
                Task { @MainActor in
                    self?.errorMessage = message
                }
            }
        )

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = appleSignInDelegate
        controller.performRequests()
    }

    // MARK: - Google Sign-In

    func signInWithGoogle() {
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = windowScene.windows.first?.rootViewController else {
            errorMessage = "Cannot find root view controller"
            return
        }

        GIDSignIn.sharedInstance.signIn(withPresenting: rootVC) { [weak self] result, error in
            guard let self else { return }
            if let error = error {
                Task { @MainActor in
                    self.errorMessage = error.localizedDescription
                }
                return
            }
            guard let idToken = result?.user.idToken?.tokenString else {
                Task { @MainActor in
                    self.errorMessage = "Failed to get Google ID token"
                }
                return
            }
            let firstName = result?.user.profile?.givenName ?? ""
            let lastName = result?.user.profile?.familyName ?? ""
            Task { @MainActor in
                await self.socialLogin(provider: "google", token: idToken, firstName: firstName, lastName: lastName)
            }
        }
    }

    // MARK: - Facebook Sign-In

    func signInWithFacebook() {
        let loginManager = LoginManager()
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = windowScene.windows.first?.rootViewController else {
            errorMessage = "Cannot find root view controller"
            return
        }

        loginManager.logIn(permissions: ["email", "public_profile"], from: rootVC) { [weak self] result, error in
            guard let self else { return }
            if let error = error {
                Task { @MainActor in
                    self.errorMessage = error.localizedDescription
                }
                return
            }
            guard let result = result, !result.isCancelled, let token = result.token?.tokenString else {
                return
            }
            Task { @MainActor in
                await self.socialLogin(provider: "facebook", token: token, firstName: "", lastName: "")
            }
        }
    }

    func logout() {
        GIDSignIn.sharedInstance.signOut()
        LoginManager().logOut()
        api.logout()
        user = nil
        isAuthenticated = false
    }

    func loadProfile() async {
        do {
            user = try await api.getProfile()
        } catch {
            if case APIError.unauthorized = error {
                logout()
            }
        }
    }

    func updateProfile(firstName: String, lastName: String, phone: String) async {
        isLoading = true
        errorMessage = nil

        do {
            user = try await api.updateProfile(firstName: firstName, lastName: lastName, phone: phone)
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }
}
