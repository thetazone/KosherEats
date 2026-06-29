import SwiftUI

/// Unified email sign-in / sign-up. The user enters their email, we hit
/// `/auth/email/check` once, and the form expands to either "enter your
/// password" (returning user) or "set a password + tell us your name" (new
/// user). Replaces the old pair of SignupView + LoginView.
struct EmailAuthView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    // New-user signup is gated on an emailed OTP BEFORE the password is chosen
    // (verified backend data): initial → verifyCode → new. Returning users skip
    // straight to `existing`.
    enum Mode { case initial, existing, verifyCode, new }

    @State private var email = ""
    @State private var password = ""
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var code = ""
    @State private var mode: Mode = .initial
    @State private var isChecking = false
    @State private var localError: String?
    @State private var hasAutoSubmittedCode = false
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case email, password, first, last, code }

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    header

                    VStack(alignment: .leading, spacing: 14) {
                        field("Email") {
                            TextField("you@example.com", text: $email)
                                .textContentType(.username)
                                .keyboardType(.emailAddress)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: .email)
                                .keTextField()
                                .accessibilityLabel("Email address")
                                .onChange(of: email) { _, _ in
                                    // Any edit invalidates the prior check —
                                    // the user may have corrected a typo and
                                    // the "account exists" answer no longer
                                    // applies to this email.
                                    if mode != .initial { mode = .initial }
                                }
                                .disabled(mode != .initial || authVM.isLoading)
                        }

                        if mode == .verifyCode {
                            field("Verification code") {
                                TextField("123456", text: $code)
                                    .keyboardType(.numberPad)
                                    .textContentType(.oneTimeCode)
                                    .font(.system(size: 22, weight: .semibold, design: .monospaced))
                                    .focused($focusedField, equals: .code)
                                    .keTextField()
                                    .accessibilityLabel("Email verification code")
                                    .onChange(of: code) { _, v in
                                        let digits = v.filter(\.isNumber)
                                        if digits != v { code = digits }
                                        if code.count > 6 { code = String(code.prefix(6)) }
                                        if code.count == 6, !hasAutoSubmittedCode {
                                            hasAutoSubmittedCode = true
                                            Task { await primary() }
                                        } else if code.count < 6 {
                                            hasAutoSubmittedCode = false
                                        }
                                    }
                            }
                        }

                        if mode == .new {
                            field("First name") {
                                TextField("First name", text: $firstName)
                                    .textContentType(.givenName)
                                    .textInputAutocapitalization(.words)
                                    .focused($focusedField, equals: .first)
                                    .keTextField()
                                    .accessibilityLabel("First name")
                            }
                            field("Last name") {
                                TextField("Last name", text: $lastName)
                                    .textContentType(.familyName)
                                    .textInputAutocapitalization(.words)
                                    .focused($focusedField, equals: .last)
                                    .keTextField()
                                    .accessibilityLabel("Last name")
                            }
                        }

                        if mode == .existing || mode == .new {
                            field("Password") {
                                SecureField(mode == .new ? "Create a password" : "Enter your password", text: $password)
                                    .textContentType(mode == .new ? .newPassword : .password)
                                    .focused($focusedField, equals: .password)
                                    .submitLabel(.go)
                                    .onSubmit { Task { await primary() } }
                                    .keTextField()
                                    .accessibilityLabel("Password")
                                    .accessibilityHint(mode == .new ? "Minimum 8 characters" : "")
                            }
                        }
                    }

                    if let err = localError ?? authVM.errorMessage {
                        Text(err)
                            .font(.system(size: 14))
                            .foregroundColor(.keError)
                    }

                    Button {
                        Task { await primary() }
                    } label: {
                        Group {
                            if isChecking || authVM.isLoading {
                                ProgressView().tint(.keTextOnAccent)
                            } else {
                                Text(primaryLabel).font(.headline)
                            }
                        }
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(canSubmit ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                        .cornerRadius(Theme.cornerRadiusMedium)
                    }
                    .disabled(!canSubmit || isChecking || authVM.isLoading)
                    .accessibilityLabel(primaryLabel)
                    .accessibilityHint(isChecking || authVM.isLoading ? "Loading" : "")

                    if mode != .initial {
                        Button("Use a different email") { reset() }
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.keTextSecondary)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(24)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { focusedField = .email }
        .onChange(of: authVM.isAuthenticated) { _, authed in
            if authed { dismiss() }
        }
    }

    @ViewBuilder
    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(.keTextPrimary)
            Text(subtitle)
                .font(.body)
                .foregroundColor(.keTextSecondary)
        }
    }

    @ViewBuilder
    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.keTextSecondary)
            content()
        }
    }

    private var title: String {
        switch mode {
        case .initial: return "Continue with email"
        case .existing: return "Welcome back"
        case .verifyCode: return "Verify your email"
        case .new: return "Create your account"
        }
    }

    private var subtitle: String {
        switch mode {
        case .initial: return "We'll check if you already have an account."
        case .existing: return "Enter the password for \(email)."
        case .verifyCode: return "Enter the 6-digit code we sent to \(email)."
        case .new: return "Just a few details to get you set up."
        }
    }

    private var primaryLabel: String {
        switch mode {
        case .initial: return "Continue"
        case .existing: return "Sign in"
        case .verifyCode: return "Verify"
        case .new: return "Create account"
        }
    }

    private var canSubmit: Bool {
        switch mode {
        case .initial:
            return isEmailShapedValid
        case .existing:
            return !password.isEmpty
        case .verifyCode:
            return code.count == 6
        case .new:
            return !password.isEmpty && password.count >= 8
                && !firstName.trimmingCharacters(in: .whitespaces).isEmpty
                && !lastName.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    private var isEmailShapedValid: Bool {
        let e = email.trimmingCharacters(in: .whitespaces)
        return e.contains("@") && e.contains(".") && e.count >= 5
    }

    private func reset() {
        mode = .initial
        password = ""
        firstName = ""
        lastName = ""
        code = ""
        hasAutoSubmittedCode = false
        localError = nil
        authVM.errorMessage = nil
        focusedField = .email
    }

    private var cleanEmail: String { email.trimmingCharacters(in: .whitespaces).lowercased() }

    private func primary() async {
        localError = nil
        switch mode {
        case .initial:
            await check()
        case .existing:
            await authVM.login(email: cleanEmail, password: password)
        case .verifyCode:
            await verifyCode()
        case .new:
            // Email already OTP-verified above; register creates the account
            // with email_verified=true. Phone is collected next by the
            // mandatory verification flow (no phone sent here).
            await authVM.register(
                email: cleanEmail,
                password: password,
                firstName: firstName.trimmingCharacters(in: .whitespaces),
                lastName: lastName.trimmingCharacters(in: .whitespaces),
                phone: ""
            )
        }
    }

    private func check() async {
        guard isEmailShapedValid else {
            localError = "Please enter a valid email address."
            return
        }
        isChecking = true
        defer { isChecking = false }
        do {
            let result = try await APIService.shared.checkEmail(cleanEmail)
            if result.exists {
                mode = .existing
                focusedField = .password
            } else {
                // New account → send the email OTP and collect it before the
                // user picks a password.
                try await APIService.shared.startEmailSignup(email: cleanEmail)
                code = ""
                hasAutoSubmittedCode = false
                mode = .verifyCode
                focusedField = .code
            }
        } catch {
            localError = AuthViewModel.friendly(error)
        }
    }

    private func verifyCode() async {
        guard code.count == 6 else { return }
        isChecking = true
        defer { isChecking = false }
        do {
            try await APIService.shared.verifyEmailSignup(email: cleanEmail, code: code)
            mode = .new
            focusedField = .first
        } catch {
            localError = AuthViewModel.friendly(error)
            code = ""
            hasAutoSubmittedCode = false
        }
    }
}
