import Foundation
import SwiftUI

@MainActor
final class AuthViewModel: ObservableObject {
    @Published var isAuthenticated: Bool = false
    @Published var profile: CourierProfile?
    @Published var isLoading: Bool = false
    @Published var errorMessage: String?

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

    func loadProfile() async {
        do {
            profile = try await api.getProfile()
        } catch {
            // If profile fetch fails on a stale token, log out.
            if case APIError.unauthorized = error {
                logout()
            }
        }
    }

    func logout() {
        api.logout()
        isAuthenticated = false
        profile = nil
    }
}
