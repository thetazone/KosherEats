import SwiftUI

/// Presented once post-sign-in when the user is missing a name or is still
/// carrying Apple's @privaterelay.appleid.com forwarding address. Captures
/// first / last / optional replacement email / optional phone and POSTs them
/// to `/user/profile`. Interactive dismiss is disabled: the UX promise is
/// "tell us who you are before you use the app."
struct ProfileCompletionSheet: View {
    @EnvironmentObject var authVM: AuthViewModel
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
        (authVM.user?.email ?? "").lowercased().hasSuffix("@privaterelay.appleid.com")
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.spacingLG) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Finish setting up your account")
                            .font(.system(size: 26, weight: .bold))
                            .foregroundColor(.keTextPrimary)
                        Text("We just need a few details so deliveries and receipts reach the right person.")
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
                                Text("Apple gave us a forwarding address. You can replace it with the one you'd like receipts sent to.")
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

                    if let err = localError ?? authVM.errorMessage {
                        Text(err)
                            .font(.system(size: 14))
                            .foregroundColor(.keError)
                    }

                    Button {
                        Task { await submit() }
                    } label: {
                        Group {
                            if authVM.isLoading {
                                ProgressView().progressViewStyle(CircularProgressViewStyle(tint: .white))
                            } else {
                                Text("Continue").font(.headline)
                            }
                        }
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(canSubmit ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                        .cornerRadius(12)
                    }
                    .disabled(!canSubmit || authVM.isLoading)

                    // Consumer app is allowed to skip profile completion — we
                    // don't need their real name/email until checkout. Seller
                    // and courier sheets don't offer this, since they can't
                    // transact at all without identity info on file.
                    Button("Not now") { dismiss() }
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.keTextSecondary)
                        .frame(maxWidth: .infinity, minHeight: 44)

                    Spacer(minLength: 12)
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
        guard let u = authVM.user else { return }
        if firstName.isEmpty { firstName = sanitizedName(u.firstName) }
        if lastName.isEmpty { lastName = sanitizedName(u.lastName) }
        if email.isEmpty {
            // Skip both well-known synthesized email forms so the user types
            // a real address: Apple's privaterelay forwarder, and the
            // phone-OTP placeholder the backend generates from the phone
            // number when no email is supplied at signup.
            let lower = u.email.lowercased()
            if lower.hasSuffix("@privaterelay.appleid.com")
                || lower.hasSuffix("@phone.koshereats.local") {
                email = ""
            } else {
                email = u.email
            }
        }
        // Phone: if the user signed in via phone OTP, the backend already
        // has their E.164 number — strip the country code (defaulting to US
        // +1) and pre-populate the digits field so they don't have to retype
        // what they just verified to get into the app.
        if phoneDigits.isEmpty, !u.phone.isEmpty {
            if u.phone.hasPrefix(selectedCountry.dialCode) {
                phoneDigits = String(u.phone.dropFirst(selectedCountry.dialCode.count))
            } else if u.phone.hasPrefix("+") {
                // Fallback for non-US numbers — drop the leading '+' and any
                // non-digit chars so the field shows just digits.
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
        let phoneE164 = phoneDigits.isEmpty ? (authVM.user?.phone ?? "") : (selectedCountry.dialCode + phoneDigits)
        _ = await authVM.updateProfile(
            firstName: trimmedFirst,
            lastName: trimmedLast,
            phone: phoneE164,
            email: trimmedEmail
        )
    }
}

private extension View {
    /// The same card-bg + rounded-corner treatment used elsewhere in the
    /// consumer auth flow. Keeps this file self-contained — if we ever add a
    /// shared TextFieldStyle we can swap this for it.
    func keTextFieldStyle() -> some View {
        self
            .foregroundColor(.keTextPrimary)
            .padding(.horizontal, 14)
            .frame(height: 52)
            .background(Color.keCard)
            .cornerRadius(12)
    }
}
