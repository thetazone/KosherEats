import Foundation
import SwiftUI

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
                self.isAuthenticated = true
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

    func signInWithGoogle() {
        errorMessage = "Google Sign-In is not yet configured. Please add the GoogleSignIn SDK and set up your OAuth client ID."
    }

    func signInWithApple() {
        errorMessage = "Apple Sign-In is not yet configured. Please enable Sign in with Apple capability in your project settings."
    }

    func signInWithFacebook() {
        errorMessage = "Facebook Login is not yet configured. Please add the Facebook SDK and set up your App ID."
    }

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
