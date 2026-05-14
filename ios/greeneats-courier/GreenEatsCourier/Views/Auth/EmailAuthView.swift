import SwiftUI

/// Unified email sign-in / sign-up for the courier app. One email field; we
/// call `/auth/email/check` and then show either "enter password" (existing
/// user) or "set a password + name" (new courier). Replaces the need to pick
/// between SignupView and LoginView up front.
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
                                .padding(.horizontal, 14)
                                .frame(height: 50)
                                .background(Color.keCard)
                                .cornerRadius(12)
                                .foregroundColor(.keTextPrimary)
                                .onChange(of: email) { _, _ in
                                    // Any edit invalidates the previous check.
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
                                    .padding(.horizontal, 14)
                                    .frame(height: 50)
                                    .background(Color.keCard)
                                    .cornerRadius(12)
                                    .foregroundColor(.keTextPrimary)
                            }
                            field("Last name") {
                                TextField("Last name", text: $lastName)
                                    .textContentType(.familyName)
                                    .textInputAutocapitalization(.words)
                                    .focused($focusedField, equals: .last)
                                    .padding(.horizontal, 14)
                                    .frame(height: 50)
                                    .background(Color.keCard)
                                    .cornerRadius(12)
                                    .foregroundColor(.keTextPrimary)
                            }
                        }

                        if mode != .initial {
                            field("Password") {
                                SecureField(mode == .new ? "Create a password" : "Enter your password", text: $password)
                                    .textContentType(mode == .new ? .newPassword : .password)
                                    .focused($focusedField, equals: .password)
                                    .submitLabel(.go)
                                    .onSubmit { Task { await primary() } }
                                    .padding(.horizontal, 14)
                                    .frame(height: 50)
                                    .background(Color.keCard)
                                    .cornerRadius(12)
                                    .foregroundColor(.keTextPrimary)
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
                                ProgressView().tint(.keTextPrimary)
                            } else {
                                Text(primaryLabel).font(.headline)
                            }
                        }
                        .foregroundColor(canSubmit ? .keTextPrimary : .keTextMuted)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Color.keCard)
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(canSubmit ? Color.kePrimaryDark : Color.keTextMuted, lineWidth: 1.5)
                        )
                    }
                    .disabled(!canSubmit || isChecking || authVM.isLoading)

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
        case .new: return "Create your courier account"
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
            await authVM.login(
                email: email.trimmingCharacters(in: .whitespaces),
                password: password
            )
        case .new:
            await authVM.signup(
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
