import SwiftUI

/// Presented after verification only when the user is missing a name.
/// Captures first / last / optional email and PUTs them to `/user/profile`.
/// Fully skippable — "Not now" (or a swipe-down) dismisses it, and nothing
/// here gates using the app.
struct ProfileCompletionSheet: View {
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var firstName = ""
    @State private var lastName = ""
    @State private var email = ""
    @State private var localError: String?

    private var canSubmit: Bool {
        !firstName.trimmingCharacters(in: .whitespaces).isEmpty
            && !lastName.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private var isRelayEmail: Bool {
        (authVM.user?.email ?? "").lowercased().hasSuffix("@privaterelay.appleid.com")
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.spacingLG) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(String(localized: "Finish setting up your account"))
                            .font(.system(size: 26, weight: .bold))
                            .foregroundColor(.keTextPrimary)
                        Text("Add a few details so deliveries and receipts reach the right person. You can skip this for now.")
                            .font(.body)
                            .foregroundColor(.keTextSecondary)
                    }

                    VStack(alignment: .leading, spacing: Theme.spacingMD) {
                        labeledField("First name") {
                            TextField("First name", text: $firstName)
                                .textContentType(.givenName)
                                .textInputAutocapitalization(.words)
                                .keTextFieldStyle()
                                .accessibilityLabel("First name")
                        }

                        labeledField("Last name") {
                            TextField("Last name", text: $lastName)
                                .textContentType(.familyName)
                                .textInputAutocapitalization(.words)
                                .keTextFieldStyle()
                                .accessibilityLabel("Last name")
                        }

                        labeledField("Email (optional)") {
                            TextField("you@example.com", text: $email)
                                .textContentType(.emailAddress)
                                .keyboardType(.emailAddress)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .keTextFieldStyle()
                                .accessibilityLabel("Email address")
                            if isRelayEmail {
                                Text("Apple gave us a forwarding address. You can replace it with the one you'd like receipts sent to.")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
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
                    .accessibilityLabel("Continue")
                    .accessibilityHint(authVM.isLoading ? "Loading" : "Save your profile information")

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
        // Email is optional — only validate it when the user typed one, and
        // send nil when empty (the backend treats a missing email as
        // don't-touch). Phone is collected via OTP verification, so we just
        // echo back whatever's on file.
        guard trimmedEmail.isEmpty || trimmedEmail.contains("@") else {
            localError = "Please enter a valid email address."
            return
        }
        _ = await authVM.updateProfile(
            firstName: trimmedFirst,
            lastName: trimmedLast,
            phone: authVM.user?.phone ?? "",
            email: trimmedEmail.isEmpty ? nil : trimmedEmail
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
