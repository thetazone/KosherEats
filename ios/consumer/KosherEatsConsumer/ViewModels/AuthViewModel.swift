import Foundation
import SwiftUI

@MainActor
class AuthViewModel: ObservableObject {
    @Published var user: User?
    @Published var isAuthenticated = false
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIService.shared

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

    func signInWithGoogle() {
        errorMessage = "Google Sign-In SDK needs to be configured. Add GoogleSignIn pod and set up your OAuth client ID."
    }

    func signInWithApple() {
        errorMessage = "Apple Sign-In needs to be configured. Enable Sign in with Apple capability in Xcode."
    }

    func signInWithFacebook() {
        errorMessage = "Facebook Login SDK needs to be configured. Add FacebookLogin pod and set up your App ID."
    }

    func logout() {
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
