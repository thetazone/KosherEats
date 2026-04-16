import SwiftUI

// Entry screen that mirrors the UberEats / DoorDash driver onramp:
// big hero promise, phone entry at top (Uber-style), then Apple / email / login.
struct AuthLandingView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @State private var showEmailAuth = false
    @State private var phoneDigits = ""
    @State private var selectedCountry: Country = .defaultCountry
    @State private var showCountryPicker = false
    @State private var navigateToOTP = false
    @State private var pendingPhoneE164 = ""
    @State private var pendingPhoneDisplay = ""

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(spacing: Theme.spacingLG) {
                    Spacer().frame(height: 32)

                    VStack(spacing: Theme.spacingMD) {
                        Image(systemName: "box.truck.fill")
                            .font(.system(size: 56))
                            .foregroundStyle(Color.kePrimary)

                        Text("Deliver with KosherEats")
                            .font(.largeTitle.bold())
                            .foregroundColor(.keTextPrimary)
                            .multilineTextAlignment(.center)

                        Text("Set your own schedule. Earn on every drop.")
                            .font(.body)
                            .foregroundColor(.keTextSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, Theme.spacingLG)

                    // Phone entry (top, Uber-style)
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Mobile number")
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
                                .frame(height: 52)
                                .background(Color.keCard)
                                .cornerRadius(12)
                            }

                            TextField("Mobile number", text: $phoneDigits)
                                .keyboardType(.numberPad)
                                .textContentType(.telephoneNumber)
                                .foregroundColor(.keTextPrimary)
                                .padding(.horizontal, 14)
                                .frame(height: 52)
                                .background(Color.keCard)
                                .cornerRadius(12)
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
                                    Text("Continue")
                                        .font(.headline)
                                }
                            }
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(isPhoneValid ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                            .cornerRadius(12)
                        }
                        .disabled(!isPhoneValid || authVM.isLoading)
                    }
                    .padding(.horizontal, Theme.spacingLG)

                    // "or" divider
                    HStack(spacing: 12) {
                        Rectangle().fill(Color.keTextSecondary.opacity(0.25)).frame(height: 1)
                        Text("or")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                        Rectangle().fill(Color.keTextSecondary.opacity(0.25)).frame(height: 1)
                    }
                    .padding(.horizontal, Theme.spacingLG)

                    VStack(spacing: Theme.spacingMD) {
                        Button {
                            authVM.signInWithApple()
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "apple.logo")
                                    .font(.system(size: 18, weight: .medium))
                                Text("Continue with Apple")
                                    .font(.system(size: 16, weight: .semibold))
                            }
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .foregroundColor(.white)
                            .background(Color.black)
                            .cornerRadius(12)
                        }

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
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .foregroundColor(.keTextPrimary)
                            .background(Color.keCard)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.kePrimary, lineWidth: 1.5)
                            )
                        }

                        // Unified email entry. EmailAuthView calls
                        // /auth/email/check to pick sign-in vs sign-up, so
                        // there's no separate "I already have an account" CTA.
                        Button("Continue with email") {
                            authVM.errorMessage = nil
                            showEmailAuth = true
                        }
                        .buttonStyle(KEPrimaryButtonStyle())

                        if let error = authVM.errorMessage, !navigateToOTP {
                            Text(error)
                                .font(.system(size: 14))
                                .foregroundColor(.keError)
                                .multilineTextAlignment(.center)
                        }
                    }
                    .padding(.horizontal, Theme.spacingLG)
                    .padding(.bottom, Theme.spacingLG)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.keBackground.ignoresSafeArea())
            .navigationDestination(isPresented: $showEmailAuth) { EmailAuthView() }
            .navigationDestination(isPresented: $navigateToOTP) {
                CourierPhoneOTPView(
                    phoneE164: pendingPhoneE164,
                    phoneDisplay: pendingPhoneDisplay
                )
            }
            .sheet(isPresented: $showCountryPicker) {
                CountryCodePickerSheet(
                    selected: $selectedCountry,
                    isPresented: $showCountryPicker
                )
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
