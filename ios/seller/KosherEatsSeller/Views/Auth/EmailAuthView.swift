import SwiftUI

/// Unified email sign-in / sign-up for the seller app. Calls
/// `/auth/email/check` to decide whether to show "enter password" (existing
/// user) or "set a password + name" (new user). New accounts are created with
/// role=consumer — the app then routes them to SellerOnboardingView (same as
/// non-seller Apple/Google sign-ups).
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
    @State private var showReset = false
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
                            TextField("you@restaurant.com", text: $email)
                                .textContentType(.username)
                                .keyboardType(.emailAddress)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focusedField, equals: .email)
                                .padding()
                                .background(Color.keCard)
                                .cornerRadius(12)
                                .foregroundColor(.keTextPrimary)
                                .onChange(of: email) { _, _ in
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
                                    .padding()
                                    .background(Color.keCard)
                                    .cornerRadius(12)
                                    .foregroundColor(.keTextPrimary)
                            }
                            field("Last name") {
                                TextField("Last name", text: $lastName)
                                    .textContentType(.familyName)
                                    .textInputAutocapitalization(.words)
                                    .focused($focusedField, equals: .last)
                                    .padding()
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
                                    .padding()
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
                                ProgressView().tint(.keTextOnAccent)
                            } else {
                                Text(primaryLabel).font(.headline)
                            }
                        }
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(canSubmit ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                        .cornerRadius(12)
                    }
                    .disabled(!canSubmit || isChecking || authVM.isLoading)

                    if mode != .initial {
                        Button("Use a different email") { reset() }
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.keTextSecondary)
                            .frame(maxWidth: .infinity)
                    }

                    if mode == .existing {
                        Button("Forgot password?") { showReset = true }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.kePrimary)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(24)
                .adaptiveContentWidth(520)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { focusedField = .email }
        .sheet(isPresented: $showReset) {
            NavigationStack { PasswordResetView(email: email) }
                .presentationDetents([.medium, .large])
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
        // Split on "@" and require exactly one "@" with non-empty local part
        // and a domain part that contains at least one dot.
        let parts = e.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return false }
        let local = parts[0]
        let domain = parts[1]
        guard !local.isEmpty, domain.contains(".") else { return false }
        // Domain must not start or end with a dot, and must have text after
        // the last dot (the TLD).
        let domainParts = domain.split(separator: ".", omittingEmptySubsequences: false)
        return domainParts.count >= 2 && domainParts.allSatisfy({ !$0.isEmpty })
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
                lastName: lastName.trimmingCharacters(in: .whitespaces)
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

/// Email-code password reset, presented from EmailAuthView's "Forgot password?".
/// Step 1 emails a 6-digit code; step 2 takes the code + a new password.
struct PasswordResetView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var email: String
    @State private var code = ""
    @State private var newPassword = ""
    @State private var codeSent = false
    @State private var isWorking = false
    @State private var error: String?
    @State private var info: String?

    init(email: String) {
        _email = State(initialValue: email)
    }

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Reset password")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(.keTextPrimary)
                    Text(codeSent
                         ? "Enter the 6-digit code we emailed to \(email) and choose a new password."
                         : "We'll email a 6-digit code to reset your password.")
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)

                    labeled("Email") {
                        TextField("you@restaurant.com", text: $email)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .disabled(codeSent)
                            .modifier(ResetFieldStyle())
                    }

                    if codeSent {
                        labeled("Code") {
                            TextField("123456", text: $code)
                                .keyboardType(.numberPad)
                                .modifier(ResetFieldStyle())
                        }
                        labeled("New password") {
                            SecureField("At least 6 characters", text: $newPassword)
                                .textContentType(.newPassword)
                                .modifier(ResetFieldStyle())
                        }
                    }

                    if let error { Text(error).font(.caption).foregroundColor(.keError) }
                    if let info { Text(info).font(.caption).foregroundColor(.kePrimary) }

                    Button {
                        Task { if codeSent { await reset() } else { await sendCode() } }
                    } label: {
                        Group {
                            if isWorking { ProgressView().tint(.keTextOnAccent) }
                            else { Text(codeSent ? "Reset password" : "Send reset code").font(.headline) }
                        }
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(canSubmit ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                        .cornerRadius(12)
                    }
                    .disabled(!canSubmit || isWorking)

                    if codeSent {
                        Button("Didn't get it? Send again") { Task { await sendCode() } }
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.keTextSecondary)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(24)
                .adaptiveContentWidth(520)
            }
        }
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        .navigationBarTitleDisplayMode(.inline)
    }

    private var canSubmit: Bool {
        codeSent ? (code.count >= 4 && newPassword.count >= 6) : email.contains("@")
    }

    private func sendCode() async {
        error = nil; info = nil; isWorking = true
        defer { isWorking = false }
        do {
            _ = try await APIService.shared.forgotPassword(email: email.trimmingCharacters(in: .whitespaces))
            codeSent = true
            info = "If an account exists, a code is on its way."
        } catch {
            self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func reset() async {
        error = nil; info = nil; isWorking = true
        defer { isWorking = false }
        do {
            let resp = try await APIService.shared.resetPassword(
                email: email.trimmingCharacters(in: .whitespaces),
                code: code.trimmingCharacters(in: .whitespaces),
                newPassword: newPassword)
            info = resp.message
            try? await Task.sleep(nanoseconds: 900_000_000)
            dismiss()
        } catch {
            self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    @ViewBuilder
    private func labeled<C: View>(_ label: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 14, weight: .medium)).foregroundColor(.keTextSecondary)
            content()
        }
    }
}

private struct ResetFieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding()
            .background(Color.keCard)
            .cornerRadius(12)
            .foregroundColor(.keTextPrimary)
    }
}
