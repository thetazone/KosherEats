import SwiftUI

/// Mandatory post-sign-in verification. Every new consumer must confirm BOTH a
/// real email (6-digit emailed code) and a real phone (Twilio SMS code) before
/// they can transact — the backend hard-gates order/payment creation on the
/// same two flags. Presented as a non-dismissible full-screen cover driven by
/// `authVM.needsVerification`; the only escape is signing out (→ guest browsing).
///
/// The view walks whichever steps are still missing, in order email → phone:
///   • Phone signup   → email needed   → email step only
///   • Google         → phone needed   → phone step only
///   • Apple sign-in  → phone only     → phone step only (backend trusts
///                                       Apple's verified-email claim)
/// (Email *signup* verifies its email pre-account in EmailAuthView, so those
/// users land here needing only the phone.)
struct AccountVerificationView: View {
    @EnvironmentObject var authVM: AuthViewModel

    enum Step { case emailEntry, emailCode, phoneEntry, phoneCode }
    @State private var step: Step = .emailEntry

    @State private var email = ""
    @State private var emailCode = ""

    @State private var country: Country = .defaultCountry
    @State private var phoneDigits = ""
    @State private var phoneCode = ""
    @State private var sentPhoneE164 = ""
    @State private var sentPhoneDisplay = ""
    @State private var showCountryPicker = false

    @State private var localError: String?

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    content
                    if let err = localError ?? authVM.errorMessage {
                        Text(err)
                            .font(.system(size: 14))
                            .foregroundColor(.keError)
                    }
                    Spacer(minLength: 12)
                    Button("Not now — sign out") { authVM.logout() }
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.keTextSecondary)
                        .frame(maxWidth: .infinity)
                }
                .padding(24)
            }
        }
        .interactiveDismissDisabled(true)
        .onAppear(perform: configureInitialStep)
        // If the backend marks the email verified while this cover is up on an
        // email step (Apple JWT self-heal via a profile refresh), jump to phone
        // — don't ask for an email Apple already vouched for (Guideline 4).
        .onChange(of: authVM.user?.emailVerified) { _, verified in
            if verified == true, step == .emailEntry || step == .emailCode {
                step = .phoneEntry
            }
        }
        .sheet(isPresented: $showCountryPicker) {
            CountryCodePickerSheet(selected: $country, isPresented: $showCountryPicker)
        }
    }

    // MARK: - Steps

    @ViewBuilder
    private var content: some View {
        switch step {
        case .emailEntry: emailEntry
        case .emailCode:  emailCodeEntry
        case .phoneEntry: phoneEntry
        case .phoneCode:  phoneCodeEntry
        }
    }

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

    private var title: String {
        switch step {
        case .emailEntry, .emailCode: return "Verify your email"
        case .phoneEntry, .phoneCode: return "Verify your phone"
        }
    }

    private var subtitle: String {
        switch step {
        case .emailEntry: return "We'll send a 6-digit code to confirm it's really you."
        case .emailCode:  return "Enter the 6-digit code we sent to \(email)."
        case .phoneEntry: return "We'll text you a code to confirm your number."
        case .phoneCode:  return "Enter the code we sent to \(sentPhoneDisplay)."
        }
    }

    // MARK: Email

    private var emailEntry: some View {
        VStack(alignment: .leading, spacing: 14) {
            labeledField("Email") {
                TextField("you@example.com", text: $email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keTextField()
                    .accessibilityLabel("Email address")
            }
            primaryButton("Send code", enabled: isEmailShaped) {
                localError = nil
                if await authVM.sendEmailCode(email: normalizedEmail) { step = .emailCode; emailCode = "" }
            }
        }
    }

    private var emailCodeEntry: some View {
        VStack(alignment: .leading, spacing: 16) {
            CodeField(code: $emailCode, length: 6, placeholder: "123456") {
                await submitEmailCode()
            }
            primaryButton("Verify", enabled: emailCode.count == 6) { await submitEmailCode() }
            resendRow { _ = await authVM.sendEmailCode(email: normalizedEmail) }
            changeRow("Use a different email") { step = .emailEntry; authVM.errorMessage = nil }
        }
    }

    private func submitEmailCode() async {
        guard emailCode.count == 6, !authVM.isLoading else { return }
        localError = nil
        let ok = await authVM.confirmEmail(email: normalizedEmail, code: emailCode)
        if ok {
            advanceAfterEmail()
        } else {
            emailCode = ""
        }
    }

    private func advanceAfterEmail() {
        // If a phone still needs verifying, continue to it; otherwise the parent
        // cover dismisses (needsVerification is now false).
        if !(authVM.user?.phoneVerified ?? false) {
            step = .phoneEntry
        }
    }

    // MARK: Phone

    private var phoneEntry: some View {
        VStack(alignment: .leading, spacing: 14) {
            labeledField("Phone number") {
                HStack(spacing: 10) {
                    Button { showCountryPicker = true } label: {
                        HStack(spacing: 6) {
                            Text(country.flag)
                            Text(country.dialCode).foregroundColor(.keTextPrimary)
                            Image(systemName: "chevron.down").font(.caption2).foregroundColor(.keTextSecondary)
                        }
                        .padding(.horizontal, 12).frame(height: 52)
                        .background(Color.keCard).cornerRadius(Theme.cornerRadiusMedium)
                    }
                    .accessibilityLabel("Country code: \(country.name) \(country.dialCode)")
                    TextField("Phone number", text: $phoneDigits)
                        .textContentType(.telephoneNumber)
                        .keyboardType(.numberPad)
                        .keTextField()
                        .accessibilityLabel("Phone number")
                        .onChange(of: phoneDigits) { _, v in
                            let digits = v.filter(\.isNumber)
                            if digits != v { phoneDigits = digits }
                        }
                }
            }
            primaryButton("Send code", enabled: phoneDigits.count >= 7) {
                localError = nil
                let e164 = country.dialCode + phoneDigits
                if await authVM.sendPhoneCode(phone: e164) {
                    sentPhoneE164 = e164
                    sentPhoneDisplay = "\(country.dialCode) \(phoneDigits)"
                    phoneCode = ""
                    step = .phoneCode
                }
            }
        }
    }

    private var phoneCodeEntry: some View {
        VStack(alignment: .leading, spacing: 16) {
            CodeField(code: $phoneCode, length: 4, placeholder: "1234") {
                await submitPhoneCode()
            }
            primaryButton("Verify", enabled: phoneCode.count == 4) { await submitPhoneCode() }
            resendRow { _ = await authVM.sendPhoneCode(phone: sentPhoneE164) }
            changeRow("Use a different number") { step = .phoneEntry; authVM.errorMessage = nil }
        }
    }

    private func submitPhoneCode() async {
        guard phoneCode.count == 4, !authVM.isLoading else { return }
        localError = nil
        let ok = await authVM.confirmPhone(phone: sentPhoneE164, code: phoneCode)
        // On success the parent dismisses (needsVerification false). On failure,
        // clear the digits so a retype can re-submit.
        if !ok { phoneCode = "" }
    }

    // MARK: - Helpers

    private func configureInitialStep() {
        if !(authVM.user?.emailVerified ?? true) {
            email = authVM.prefillableEmail
            step = .emailEntry
        } else {
            step = .phoneEntry
        }
    }

    private var normalizedEmail: String { email.trimmingCharacters(in: .whitespaces).lowercased() }

    private var isEmailShaped: Bool {
        let e = normalizedEmail
        return e.contains("@") && e.contains(".") && e.count >= 5
    }

    @ViewBuilder
    private func labeledField<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 14, weight: .medium)).foregroundColor(.keTextSecondary)
            content()
        }
    }

    private func primaryButton(_ label: String, enabled: Bool, action: @escaping () async -> Void) -> some View {
        Button { Task { await action() } } label: {
            Group {
                if authVM.isLoading {
                    ProgressView().tint(.keTextOnAccent)
                } else {
                    Text(label).font(.headline)
                }
            }
            .foregroundColor(.keTextOnAccent)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(enabled ? Color.kePrimary : Color.kePrimary.opacity(0.4))
            .cornerRadius(Theme.cornerRadiusMedium)
        }
        .disabled(!enabled || authVM.isLoading)
    }

    private func resendRow(_ action: @escaping () async -> Void) -> some View {
        HStack(spacing: 4) {
            Text("Didn't get a code?").font(.caption).foregroundColor(.keTextSecondary)
            Button("Resend") { Task { localError = nil; await action() } }
                .font(.caption.bold()).foregroundColor(.kePrimary)
        }
    }

    private func changeRow(_ label: String, _ action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .font(.system(size: 14, weight: .medium))
            .foregroundColor(.keTextSecondary)
            .frame(maxWidth: .infinity)
    }
}

/// A single-field numeric OTP entry that auto-submits when `length` digits are
/// entered. Used for both the 6-digit email code and 4-digit SMS code.
private struct CodeField: View {
    @Binding var code: String
    let length: Int
    let placeholder: String
    let onComplete: () async -> Void

    @FocusState private var focused: Bool
    @State private var hasAutoSubmitted = false

    var body: some View {
        TextField(placeholder, text: $code)
            .keyboardType(.numberPad)
            .textContentType(.oneTimeCode)
            .font(.system(size: 28, weight: .semibold, design: .monospaced))
            .foregroundColor(.keTextPrimary)
            .padding()
            .background(Color.keCard)
            .cornerRadius(Theme.cornerRadiusMedium)
            .focused($focused)
            .accessibilityLabel("Verification code")
            .onAppear { focused = true }
            .onChange(of: code) { _, newValue in
                let digits = newValue.filter(\.isNumber)
                if digits != newValue { code = digits }
                if code.count > length { code = String(code.prefix(length)) }
                if code.count == length, !hasAutoSubmitted {
                    hasAutoSubmitted = true
                    Task { await onComplete() }
                } else if code.count < length {
                    hasAutoSubmitted = false
                }
            }
    }
}
