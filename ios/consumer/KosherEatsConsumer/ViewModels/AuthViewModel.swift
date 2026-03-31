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
