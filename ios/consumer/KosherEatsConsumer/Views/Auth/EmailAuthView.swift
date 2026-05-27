import SwiftUI

/// Unified email sign-in / sign-up. The user enters their email, we hit
/// `/auth/email/check` once, and the form expands to either "enter your
/// password" (returning user) or "set a password + tell us your name" (new
/// user). Replaces the old pair of SignupView + LoginView.
struct EmailAuthView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    enum Mode { case initial, existing, new }

    @State private var email = ""
    @State private var password = ""
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var mode: Mode = .initial
    @State private var isChecking = false
    @State private var localError: String?
    @FocusState private var focusedField: Field?

    private enum Field: Hashable { case email, password, first, last }

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

                        if mode != .initial {
                            field("Password") {
                                SecureField(mode == .new ? "Create a password" : "Enter your password", text: $password)
                                    .textContentType(mode == .new ? .newPassword : .password)
                                    .focused($focusedField, equals: .password)
                                    .submitLabel(.go)
                                    .onSubmit { Task { await primary() } }
                                    .keTextField()
                                    .accessibilityLabel("Password")
                                    .accessibilityHint(mode == .new ? "Minimum 6 characters" : "")
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
        case .new: return "Create your account"
        }
    }

    private var subtitle: String {
        switch mode {
        case .initial: return "We'll check if you already have an account."
        case .existing: return "Enter the password for \(email)."
        case .new: return "Just a few details to get you set up."
        }
    }

    private var primaryLabel: String {
        switch mode {
        case .initial: return "Continue"
        case .existing: return "Sign in"
        case .new: return "Create account"
        }
    }

    private var canSubmit: Bool {
        switch mode {
        case .initial:
            return isEmailShapedValid
        case .existing:
            return !password.isEmpty
        case .new:
            return !password.isEmpty && password.count >= 6
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
        localError = nil
        authVM.errorMessage = nil
        focusedField = .email
    }

    private func primary() async {
        localError = nil
        switch mode {
        case .initial:
            await check()
        case .existing:
            await authVM.login(email: email.trimmingCharacters(in: .whitespaces), password: password)
        case .new:
            await authVM.register(
                email: email.trimmingCharacters(in: .whitespaces),
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
            let result = try await APIService.shared.checkEmail(email.trimmingCharacters(in: .whitespaces))
            mode = result.exists ? .existing : .new
            focusedField = result.exists ? .password : .first
        } catch {
            localError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}
