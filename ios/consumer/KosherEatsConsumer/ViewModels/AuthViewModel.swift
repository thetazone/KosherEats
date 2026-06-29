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
                    defer {
                        self.isSocialSignInInFlight = false
                        self.appleSignInDelegate = nil
                    }
                    await self.socialLogin(provider: "apple", token: token, firstName: firstName, lastName: lastName, nonce: nonce)
                }
            },
            onError: { [weak self] message in
                Task { @MainActor in
                    self?.isSocialSignInInFlight = false
                    self?.errorMessage = message
                    self?.appleSignInDelegate = nil
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
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let activeScene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        guard let rootVC = activeScene?.windows.first(where: \.isKeyWindow)?.rootViewController
                ?? activeScene?.windows.first?.rootViewController else {
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
        // End any active Live Activity before tearing down session state
        DeliveryActivityManager.shared.endTracking(finalStatus: "logged_out", displayText: "Session ended")

        GIDSignIn.sharedInstance.signOut()

        // Unregister this device's APNs token from the *current* user, then clear
        // the auth token — in that order, because /devices/unregister requires
        // auth. Without unregistering, the backend keeps mapping this device →
        // the user who just logged out, so the next account that signs in on the
        // same device receives the previous user's order-status and chat pushes.
        // Best-effort: a network failure must never strand the user in a
        // logged-in state, so the local teardown below still runs synchronously.
        // The cached APNs token is intentionally NOT cleared (it's device-scoped,
        // not secret) so the next login re-registers it immediately.
        Task { [api] in
            await PushNotifications.shared.unregisterCurrentDeviceToken()
            api.logout()
        }

        user = nil
        isAuthenticated = false
        isLoading = false
        errorMessage = nil
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

    /// True iff the currently-signed-in user still needs to fill in their name.
    /// Email/phone completion is no longer handled here — the verification flow
    /// (`needsVerification`) collects and OTP-confirms a real email and phone,
    /// which also resolves the relay/placeholder-email cases this used to catch.
    var needsProfileCompletion: Bool {
        guard let u = user else { return false }
        if u.firstName.trimmingCharacters(in: .whitespaces).isEmpty { return true }
        if u.lastName.trimmingCharacters(in: .whitespaces).isEmpty { return true }
        return false
    }

    // MARK: - Account verification (mandatory phone + email OTP)
    //
    // Every new consumer must end onboarding with a verified phone AND a
    // verified email. The backend hard-gates order/payment creation until both
    // flags are true; this drives the UI so the user completes them up front.

    /// True while the signed-in consumer still has an unverified phone or email.
    /// Drives the mandatory verification flow and mirrors the backend gate.
    var needsVerification: Bool {
        guard let u = user else { return false }
        return !u.emailVerified || !u.phoneVerified
    }

    /// The email currently on the account, unless it's a placeholder we should
    /// not pre-fill (Apple relay forwarder or the phone-OTP synthesized address).
    var prefillableEmail: String {
        guard let u = user else { return "" }
        let email = u.email.lowercased()
        if email.hasSuffix(Self.appleRelayDomain) || email.hasSuffix(Self.phoneLocalDomain) { return "" }
        return u.email
    }

    /// Sends a 6-digit code to `email` to attach + verify it on the account.
    func sendEmailCode(email: String) async -> Bool {
        isLoading = true; errorMessage = nil
        defer { isLoading = false }
        do { try await api.startEmailChange(email: email); return true }
        catch { errorMessage = Self.friendly(error); return false }
    }

    /// Verifies the emailed code, then refreshes the profile so `emailVerified`
    /// (and thus `needsVerification`) updates.
    func confirmEmail(email: String, code: String) async -> Bool {
        isLoading = true; errorMessage = nil
        defer { isLoading = false }
        do {
            try await api.verifyEmailChange(email: email, code: code)
            await loadProfile()
            return true
        } catch { errorMessage = Self.friendly(error); return false }
    }

    /// Sends an SMS code to `phone` (E.164) to attach + verify it on the account.
    func sendPhoneCode(phone: String) async -> Bool {
        isLoading = true; errorMessage = nil
        defer { isLoading = false }
        do { try await api.startPhoneChange(phone: phone); return true }
        catch { errorMessage = Self.friendly(error); return false }
    }

    /// Verifies the SMS code, then refreshes the profile so `phoneVerified`
    /// (and thus `needsVerification`) updates.
    func confirmPhone(phone: String, code: String) async -> Bool {
        isLoading = true; errorMessage = nil
        defer { isLoading = false }
        do {
            try await api.verifyPhoneChange(phone: phone, code: code)
            await loadProfile()
            return true
        } catch { errorMessage = Self.friendly(error); return false }
    }

    /// Maps API errors to a clean, user-facing string (strips the "Error 400:"
    /// prefix that httpError's description carries).
    static func friendly(_ error: Error) -> String {
        if case let APIError.httpError(_, msg) = error, !msg.isEmpty, msg != "Unknown error" {
            return msg
        }
        return (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
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
