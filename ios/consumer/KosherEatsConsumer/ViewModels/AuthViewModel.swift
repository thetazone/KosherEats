import Foundation
import SwiftUI
import AuthenticationServices
import GoogleSignIn

@MainActor
class AuthViewModel: ObservableObject {
    /// Apple's private-relay email domain — real address hidden from the app.
    private static let appleRelayDomain = "@privaterelay.appleid.com"
    /// Synthetic email domain the backend assigns to phone-OTP signups.
    private static let phoneLocalDomain = "@phone.koshereats.local"

    @Published var user: User?
    @Published var isAuthenticated = false
    @Published var isLoading = false
    @Published var errorMessage: String?

    private let api = APIService.shared
    private var appleSignInDelegate: AppleSignInDelegate?
    // Single guard for any social sign-in flow in flight. Without this, a
    // user double-tapping "Continue with Apple" stacks two ASAuthorizationController
    // sessions whose delegates leak and whose results race each other.
    private var isSocialSignInInFlight = false

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
            await onAuthSucceeded()
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
            await onAuthSucceeded()
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func socialLogin(provider: String, token: String, firstName: String, lastName: String, nonce: String? = nil) async {
        isLoading = true
        errorMessage = nil

        do {
            let response = try await api.socialLogin(provider: provider, token: token, firstName: firstName, lastName: lastName, nonce: nonce)
            user = response.user
            isAuthenticated = true
            await onAuthSucceeded()
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    // Called from every successful sign-in path so an APNs token that arrived
    // before the user authenticated (the common case for fresh installs)
    // finally gets posted to /devices/register. Without this hook, the
    // pendingToken in PushNotifications sat unregistered forever.
    private func onAuthSucceeded() async {
        await PushNotifications.shared.registerPendingTokenIfPossible()
    }

    // MARK: - Phone OTP login
    //
    // Backend auto-creates a consumer account if the phone number is new, or
    // signs in the existing user otherwise. No role promotion happens for the
    // consumer app (backend never demotes a seller/courier down to consumer).

    /// Triggers Twilio Verify SMS for `phone` (E.164). Returns true on success
    /// so the caller can transition to the OTP-entry screen.
    func startPhoneLogin(phone: String) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            try await api.startPhoneLogin(phone: phone)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func silentResendOTP(phone: String) async {
        try? await api.startPhoneLogin(phone: phone)
    }

    /// Verifies the SMS code. Returns true iff authenticated.
    func verifyPhoneLogin(phone: String, code: String) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let response = try await api.verifyPhoneLogin(phone: phone, code: code)
            user = response.user
            isAuthenticated = true
            await onAuthSucceeded()
            return true
        } catch APIError.unauthorized {
            errorMessage = "Invalid or expired code."
            return false
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    // MARK: - Apple Sign-In

    func signInWithApple() {
        guard !isSocialSignInInFlight else { return }
        isSocialSignInInFlight = true

        let (rawNonce, hashedNonce) = AppleSignInNonce.generate()
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.email, .fullName]
        // SHA256(rawNonce) goes in the request; Apple echoes it into the
        // returned JWT's nonce claim. Backend re-hashes the raw nonce we
        // POST and matches against the claim — replays of a stolen token
        // bound to a different nonce fail verification.
        request.nonce = hashedNonce

        appleSignInDelegate = AppleSignInDelegate(
            rawNonce: rawNonce,
            onSuccess: { [weak self] token, firstName, lastName, nonce in
                guard let self else { return }
                Task { @MainActor in
                    defer { self.isSocialSignInInFlight = false }
                    await self.socialLogin(provider: "apple", token: token, firstName: firstName, lastName: lastName, nonce: nonce)
                }
            },
            onError: { [weak self] message in
                Task { @MainActor in
                    self?.isSocialSignInInFlight = false
                    self?.errorMessage = message
                }
            }
        )

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = appleSignInDelegate
        controller.presentationContextProvider = appleSignInDelegate
        controller.performRequests()
    }

    // MARK: - Google Sign-In

    func signInWithGoogle() {
        guard !isSocialSignInInFlight else { return }
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = windowScene.windows.first?.rootViewController else {
            errorMessage = "Cannot find root view controller"
            return
        }
        isSocialSignInInFlight = true

        // Sign out any cached GID user first so the sheet always shows the
        // account chooser (with "Use another account") instead of silently
        // reusing the last-signed-in account. Our backend tokens — not the
        // GIDSignIn state — drive whether the user is logged into the app.
        GIDSignIn.sharedInstance.signOut()

        GIDSignIn.sharedInstance.signIn(withPresenting: rootVC) { [weak self] result, error in
            guard let self else { return }
            if let error = error {
                Task { @MainActor in
                    self.isSocialSignInInFlight = false
                    self.errorMessage = error.localizedDescription
                }
                return
            }
            guard let idToken = result?.user.idToken?.tokenString else {
                Task { @MainActor in
                    self.isSocialSignInInFlight = false
                    self.errorMessage = "Failed to get Google ID token"
                }
                return
            }
            let firstName = result?.user.profile?.givenName ?? ""
            let lastName = result?.user.profile?.familyName ?? ""
            Task { @MainActor in
                defer { self.isSocialSignInInFlight = false }
                await self.socialLogin(provider: "google", token: idToken, firstName: firstName, lastName: lastName)
            }
        }
    }

    func logout() {
        GIDSignIn.sharedInstance.signOut()
        api.logout()
        user = nil
        isAuthenticated = false
        isLoading = false
        errorMessage = nil
        PushNotifications.shared.pendingToken = nil
        AppRouter.shared.clearPendingRoutes()
    }

    func loadProfile() async {
        do {
            user = try await api.getProfile()
        } catch {
            // Surface the error so a "?" avatar isn't silent. Decode failures
            // and transient errors used to disappear here — making the Profile
            // screen show "?" with no breadcrumb. Console-only; not user-facing.
            print("⚠️ loadProfile failed: \(error)")
            if case APIError.unauthorized = error {
                logout()
            }
        }
    }

    @discardableResult
    func refreshToken() async -> Bool {
        do {
            let refreshed = try await api.performTokenRefresh()
            if !refreshed { logout() }
            return refreshed
        } catch {
            logout()
            return false
        }
    }

    @discardableResult
    func updateProfile(firstName: String, lastName: String, phone: String, email: String? = nil) async -> Bool {
        isLoading = true
        errorMessage = nil

        do {
            user = try await api.updateProfile(firstName: firstName, lastName: lastName, phone: phone, email: email)
            isLoading = false
            return true
        } catch {
            errorMessage = error.localizedDescription
            isLoading = false
            return false
        }
    }

    /// True iff the currently-signed-in user still needs to fill in basic
    /// profile info. Triggers ProfileCompletionSheet on:
    /// - Empty first or last name
    /// - Apple's @privaterelay.appleid.com email forwarder (real email hidden)
    /// - The phone-OTP synthesized email (backend creates `<phone>@phone.koshereats.local`
    ///   when phone signup didn't include name/email — every fresh phone user
    ///   matches this until they fill in real details).
    var needsProfileCompletion: Bool {
        guard let u = user else { return false }
        let email = u.email.lowercased()
        if u.firstName.trimmingCharacters(in: .whitespaces).isEmpty { return true }
        if u.lastName.trimmingCharacters(in: .whitespaces).isEmpty { return true }
        if email.hasSuffix(Self.appleRelayDomain) { return true }
        if email.hasSuffix(Self.phoneLocalDomain) { return true }
        return false
    }

    func deleteAccount() async {
        isLoading = true
        errorMessage = nil

        do {
            try await api.deleteAccount()
            logout()
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }
}
