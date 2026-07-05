import SwiftUI

/// Optional profile form, opened only when the user taps the dashboard
/// banner ("Complete your profile"). Never auto-presented — App Review
/// Guideline 4 forbids demanding name/email after Sign in with Apple, so
/// everything here is user-initiated and skippable.
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

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Complete your profile")
                            .font(.system(size: 26, weight: .bold))
                            .foregroundColor(.keTextPrimary)
                        Text("Add your name and a contact email so payout and order updates reach you. You can always do this later.")
                            .font(.body)
                            .foregroundColor(.keTextSecondary)
                    }

                    VStack(alignment: .leading, spacing: 16) {
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
                        labeledField("Email (optional)") {
                            TextField("you@example.com", text: $email)
                                .textContentType(.emailAddress)
                                .keyboardType(.emailAddress)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .keTextFieldStyle()
                            if authVM.hasPlaceholderEmail {
                                Text("Your account doesn't have a reachable email yet. Add one to get payout and order updates.")
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
                                Text("Save").font(.headline)
                            }
                        }
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(canSubmit ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                        .cornerRadius(12)
                    }
                    .disabled(!canSubmit || authVM.isLoading)

                    // Optional form — closing without saving is always allowed.
                    Button("Not now") { dismiss() }
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.keTextSecondary)
                        .frame(maxWidth: .infinity, minHeight: 44)
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
        // Email is optional — only validate when the user typed one.
        if !trimmedEmail.isEmpty, !trimmedEmail.contains("@") {
            localError = "Please enter a valid email address."
            return
        }
        let ok = await authVM.updateProfile(
            firstName: trimmedFirst,
            lastName: trimmedLast,
            phone: authVM.user?.phone ?? "",
            email: trimmedEmail.isEmpty ? nil : trimmedEmail
        )
        // Presented via a plain $showProfileSheet binding now — nothing
        // auto-closes it, so dismiss ourselves on success.
        if ok { dismiss() }
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
