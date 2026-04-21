import SwiftUI

/// Captures name/email/phone after Apple sign-in when Apple left us with an
/// empty name or a @privaterelay.appleid.com forwarding address. Blocks the
/// rest of the app until submitted — RootView presents it full-screen for
/// exactly that reason (onboarding starts immediately after).
struct ProfileCompletionSheet: View {
    @EnvironmentObject var auth: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var firstName = ""
    @State private var lastName = ""
    @State private var email = ""
    @State private var phoneDigits = ""
    @State private var selectedCountry: Country = .defaultCountry
    @State private var showCountryPicker = false
    @State private var localError: String?

    private var canSubmit: Bool {
        !firstName.trimmingCharacters(in: .whitespaces).isEmpty
            && !lastName.trimmingCharacters(in: .whitespaces).isEmpty
            && !email.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private var isRelayEmail: Bool {
        (auth.user?.email ?? "").lowercased().hasSuffix("@privaterelay.appleid.com")
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.spacingLG) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Finish setting up your account")
                            .font(.system(size: 26, weight: .bold))
                            .foregroundColor(.keTextPrimary)
                        Text("We need a few details to match you with deliveries and route payouts.")
                            .font(.body)
                            .foregroundColor(.keTextSecondary)
                    }

                    VStack(alignment: .leading, spacing: Theme.spacingMD) {
                        labeledField("First name") {
                            TextField("First name", text: $firstName)
                                .textContentType(.givenName)
                                .textInputAutocapitalization(.words)
                                .keTextFieldStyle()
                        }
                        labeledField("Last name") {
                            TextField("Last name", text: $lastName)
                                .textContentType(.familyName)
                                .textInputAutocapitalization(.words)
                                .keTextFieldStyle()
                        }
                        labeledField("Email") {
                            TextField("you@example.com", text: $email)
                                .textContentType(.emailAddress)
                                .keyboardType(.emailAddress)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .keTextFieldStyle()
                            if isRelayEmail {
                                Text("Apple gave us a forwarding address. Enter the email you'd like payout confirmations sent to.")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                            }
                        }
                        labeledField("Mobile number (optional)") {
                            HStack(spacing: 10) {
                                Button {
                                    showCountryPicker = true
                                } label: {
                                    HStack(spacing: 6) {
                                        Text(selectedCountry.flag)
                                        Text(selectedCountry.dialCode)
                                            .foregroundColor(.keTextPrimary)
                                        Image(systemName: "chevron.down")
                                            .font(.system(size: 11, weight: .semibold))
                                            .foregroundColor(.keTextSecondary)
                                    }
                                    .padding(.horizontal, 14)
                                    .frame(height: 52)
                                    .background(Color.keCard)
                                    .cornerRadius(12)
                                }

                                TextField("Mobile number", text: $phoneDigits)
                                    .keyboardType(.numberPad)
                                    .textContentType(.telephoneNumber)
                                    .keTextFieldStyle()
                                    .onChange(of: phoneDigits) { _, newValue in
                                        let digits = newValue.filter(\.isNumber)
                                        if digits != newValue { phoneDigits = digits }
                                        if phoneDigits.count > 15 {
                                            phoneDigits = String(phoneDigits.prefix(15))
                                        }
                                    }
                            }
                        }
                    }

                    if let err = localError ?? auth.errorMessage {
                        Text(err)
                            .font(.system(size: 14))
                            .foregroundColor(.keError)
                    }

                    Button {
                        Task { await submit() }
                    } label: {
                        Group {
                            if auth.isLoading {
                                ProgressView().progressViewStyle(CircularProgressViewStyle(tint: .keTextPrimary))
                            } else {
                                Text("Continue").font(.headline)
                            }
                        }
                        .foregroundColor(canSubmit ? .keTextPrimary : .keTextMuted)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(Color.keCard)
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(canSubmit ? Color.kePrimaryDark : Color.keTextMuted, lineWidth: 1.5)
                        )
                    }
                    .disabled(!canSubmit || auth.isLoading)

                    // TEMPORARY — App Review escape hatch. Couriers can't
                    // actually accept deliveries without a name/email on file,
                    // so this should be removed once the app is live and
                    // reviewers aren't driving the Apple sign-in flow blind.
                    Button("Not now") { dismiss() }
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.keTextSecondary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .padding(24)
            }
            .background(Color.keBackground.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showCountryPicker) {
                CountryCodePickerSheet(
                    selected: $selectedCountry,
                    isPresented: $showCountryPicker
                )
                .presentationDetents([.medium, .large])
            }
        }
        .onAppear(perform: prefill)
    }

    @ViewBuilder
    private func labeledField<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(.keTextPrimary)
            content()
        }
    }

    private func prefill() {
        guard let u = auth.user else { return }
        if firstName.isEmpty { firstName = sanitizedName(u.firstName) }
        if lastName.isEmpty { lastName = sanitizedName(u.lastName) }
        if email.isEmpty {
            // Skip both synthesized email forms so the user types a real
            // address: Apple's privaterelay forwarder, and the phone-OTP
            // placeholder the backend generates when no email was supplied.
            let lower = u.email.lowercased()
            if lower.hasSuffix("@privaterelay.appleid.com")
                || lower.hasSuffix("@phone.koshereats.local") {
                email = ""
            } else {
                email = u.email
            }
        }
        // Phone: if the user signed in via phone OTP, the backend already
        // has their E.164 number — pre-populate the digits field.
        if phoneDigits.isEmpty, !u.phone.isEmpty {
            if u.phone.hasPrefix(selectedCountry.dialCode) {
                phoneDigits = String(u.phone.dropFirst(selectedCountry.dialCode.count))
            } else if u.phone.hasPrefix("+") {
                phoneDigits = u.phone.filter(\.isNumber)
            }
        }
    }

    /// Older Apple sign-ins stored "Apple"/"User" as placeholders whenever
    /// Apple withheld the real name on a return authorization; the
    /// phone-auth new-user path wrote "New"/"User" for the same reason.
    /// Treat those as empty so the user doesn't have to backspace through
    /// junk before typing their real name.
    private func sanitizedName(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        switch trimmed.lowercased() {
        case "apple", "user", "new": return ""
        default: return trimmed
        }
    }

    private func submit() async {
        localError = nil
        let trimmedEmail = email.trimmingCharacters(in: .whitespaces)
        let trimmedFirst = firstName.trimmingCharacters(in: .whitespaces)
        let trimmedLast = lastName.trimmingCharacters(in: .whitespaces)
        guard trimmedEmail.contains("@") else {
            localError = "Please enter a valid email address."
            return
        }
        let phoneE164 = phoneDigits.isEmpty ? (auth.user?.phone ?? "") : (selectedCountry.dialCode + phoneDigits)
        _ = await auth.updateUserProfile(
            firstName: trimmedFirst,
            lastName: trimmedLast,
            phone: phoneE164,
            email: trimmedEmail
        )
    }
}

private extension View {
    func keTextFieldStyle() -> some View {
        self
            .foregroundColor(.keTextPrimary)
            .padding(.horizontal, 14)
            .frame(height: 52)
            .background(Color.keCard)
            .cornerRadius(12)
    }
}
