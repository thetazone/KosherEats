import SwiftUI

struct LoginView: View {
    var dismissLabel: String = "Continue as Guest"
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.dismiss) var dismiss
    // Phone login
    @State private var phoneDigits = ""
    @State private var selectedCountry: Country = .defaultCountry
    @State private var showCountryPicker = false
    @State private var navigateToOTP = false
    @State private var navigateToEmail = false
    @State private var pendingPhoneE164 = ""
    @State private var pendingPhoneDisplay = ""

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(spacing: Theme.spacingXL) {
                        Spacer().frame(height: 40)

                        // Logo
                        VStack(spacing: 12) {
                            ZStack {
                                Circle()
                                    .fill(Color.kePrimary.opacity(0.15))
                                    .frame(width: 100, height: 100)
                                Image(systemName: "fork.knife.circle.fill")
                                    .font(.system(size: 56))
                                    .foregroundColor(.kePrimary)
                            }

                            Text(String(localized: "Welcome to KosherEats"))
                                .font(.system(size: 28, weight: .bold))
                                .foregroundColor(.keTextPrimary)

                            Text(String(localized: "Kosher food delivery,\ndone right."))
                                .font(.body)
                                .foregroundColor(.keTextSecondary)
                                .multilineTextAlignment(.center)
                        }

                        // Phone entry (Uber-style: top of the login screen).
                        // Backend /auth/phone/verify auto-creates a consumer
                        // account when the phone isn't yet registered, so
                        // this is a unified sign-in / sign-up path — no
                        // separate flow needed.
                        VStack(alignment: .leading, spacing: 10) {
                            Text(String(localized: "Mobile number"))
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(.keTextPrimary)

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
                                    .frame(height: 50)
                                    .background(Color.keCard)
                                    .cornerRadius(Theme.cornerRadiusMedium)
                                }

                                TextField("Mobile number", text: $phoneDigits)
                                    .keyboardType(.numberPad)
                                    .textContentType(.telephoneNumber)
                                    .foregroundColor(.keTextPrimary)
                                    .padding(.horizontal, 14)
                                    .frame(height: 50)
                                    .background(Color.keCard)
                                    .cornerRadius(Theme.cornerRadiusMedium)
                                    .toolbar {
                                        ToolbarItemGroup(placement: .keyboard) {
                                            Spacer()
                                            Button("Done") {
                                                UIApplication.shared.sendAction(
                                                    #selector(UIResponder.resignFirstResponder),
                                                    to: nil, from: nil, for: nil)
                                            }
                                        }
                                    }
                                    .onChange(of: phoneDigits) { _, newValue in
                                        let digits = newValue.filter(\.isNumber)
                                        if digits != newValue { phoneDigits = digits }
                                        if phoneDigits.count > 15 {
                                            phoneDigits = String(phoneDigits.prefix(15))
                                        }
                                    }
                            }

                            Button {
                                Task { await startPhoneFlow() }
                            } label: {
                                Group {
                                    if authVM.isLoading && !navigateToOTP {
                                        ProgressView()
                                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                    } else {
                                        Text(String(localized: "Continue"))
                                            .font(.headline)
                                    }
                                }
                                .foregroundColor(.keTextOnAccent)
                                .frame(maxWidth: .infinity)
                                .frame(height: 50)
                                .background(isPhoneValid ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                                .cornerRadius(Theme.cornerRadiusMedium)
                            }
                            .disabled(!isPhoneValid || authVM.isLoading)
                        }
                        .padding(.horizontal)

                        // "or" divider
                        HStack(spacing: 12) {
                            Rectangle().fill(Color.keTextSecondary.opacity(0.25)).frame(height: 1)
                            Text("or")
                                .font(.caption)
                                .foregroundColor(.keTextSecondary)
                            Rectangle().fill(Color.keTextSecondary.opacity(0.25)).frame(height: 1)
                        }
                        .padding(.horizontal)

                        // Continue-with options: Apple, Google, Email, Guest.
                        // Apple/Google already act as sign-up for new users
                        // (see social_auth.go upsert), and Email routes
                        // through /auth/email/check in EmailAuthView to pick
                        // sign-in vs sign-up — so no separate "Create Account"
                        // CTA is needed.
                        VStack(spacing: 12) {
                            // Continue with Apple
                            Button {
                                authVM.signInWithApple()
                            } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: "apple.logo")
                                        .font(.system(size: 18, weight: .medium))
                                    Text("Continue with Apple")
                                        .font(.system(size: 16, weight: .semibold))
                                }
                                .frame(maxWidth: .infinity, minHeight: 50)
                                .foregroundColor(.keTextOnAccent)
                                .background(Color.keCard)
                                .cornerRadius(Theme.cornerRadiusMedium)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color.kePrimary, lineWidth: 1.5)
                                )
                            }

                            // Continue with Google
                            Button {
                                authVM.signInWithGoogle()
                            } label: {
                                HStack(spacing: 10) {
                                    Text("G")
                                        .font(.system(size: 20, weight: .bold))
                                        .foregroundColor(.red)
                                    Text("Continue with Google")
                                        .font(.system(size: 16, weight: .semibold))
                                }
                                .frame(maxWidth: .infinity, minHeight: 50)
                                .foregroundColor(.keTextOnAccent)
                                .background(Color.keCard)
                                .cornerRadius(Theme.cornerRadiusMedium)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color.kePrimary, lineWidth: 1.5)
                                )
                            }

                            // Continue with Email — unified sign-in / sign-up.
                            // EmailAuthView calls /auth/email/check to branch.
                            Button {
                                authVM.errorMessage = nil
                                navigateToEmail = true
                            } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: "envelope.fill")
                                        .font(.system(size: 16))
                                    Text("Continue with Email")
                                        .font(.system(size: 16, weight: .semibold))
                                }
                                .frame(maxWidth: .infinity, minHeight: 50)
                                .foregroundColor(.keTextOnAccent)
                                .background(Color.keCard)
                                .cornerRadius(Theme.cornerRadiusMedium)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color.kePrimary, lineWidth: 1.5)
                                )
                            }

                            // Continue as Guest — same card style as the
                            // OAuth options above (gray fill + orange
                            // border) with orange text so it reads as a
                            // peer option, not a hidden-away escape hatch.
                            // Checkout still gates on auth in CartView, so
                            // guests are prompted at order time.
                            Button {
                                dismiss()
                            } label: {
                                Text(dismissLabel)
                                    .font(.system(size: 16, weight: .semibold))
                                    .frame(maxWidth: .infinity, minHeight: 50)
                                    .foregroundColor(.kePrimary)
                                    .background(Color.keCard)
                                    .cornerRadius(Theme.cornerRadiusMedium)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 12)
                                            .stroke(Color.kePrimary, lineWidth: 1.5)
                                    )
                            }
                        }
                        .padding(.horizontal)

                        // Error from social login (Apple/Google).
                        if let error = authVM.errorMessage {
                            Text(error)
                                .font(.system(size: 14))
                                .foregroundColor(.keError)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal)
                        }

                        Spacer().frame(height: 40)
                    }
                }
            }
            .navigationDestination(isPresented: $navigateToOTP) {
                ConsumerPhoneOTPView(
                    phoneE164: pendingPhoneE164,
                    phoneDisplay: pendingPhoneDisplay
                )
            }
            .navigationDestination(isPresented: $navigateToEmail) {
                EmailAuthView()
            }
            .sheet(isPresented: $showCountryPicker) {
                CountryCodePickerSheet(
                    selected: $selectedCountry,
                    isPresented: $showCountryPicker
                )
            }
            .onChange(of: authVM.isAuthenticated) { _, authenticated in
                if authenticated { dismiss() }
            }
        }
    }

    private var isPhoneValid: Bool {
        phoneDigits.count >= 7
    }

    private func startPhoneFlow() async {
        let e164 = selectedCountry.dialCode + phoneDigits
        let display = formatDisplay(dialCode: selectedCountry.dialCode, digits: phoneDigits)
        let ok = await authVM.startPhoneLogin(phone: e164)
        if ok {
            pendingPhoneE164 = e164
            pendingPhoneDisplay = display
            authVM.errorMessage = nil
            navigateToOTP = true
        }
    }

    private func formatDisplay(dialCode: String, digits: String) -> String {
        if dialCode == "+1", digits.count == 10 {
            let area = digits.prefix(3)
            let mid = digits.dropFirst(3).prefix(3)
            let last = digits.dropFirst(6)
            return "\(dialCode) \(area) \(mid) \(last)"
        }
        return "\(dialCode) \(digits)"
    }
}
