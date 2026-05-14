import SwiftUI

struct SellerLoginView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @State private var phoneDigits = ""
    @State private var navigateToOTP = false
    @State private var navigateToEmail = false
    @State private var pendingPhoneE164 = ""
    @State private var pendingPhoneDisplay = ""
    @State private var selectedCountry: Country = .defaultCountry
    @State private var showCountryPicker = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(spacing: 28) {
                        Spacer().frame(height: 40)

                        // Logo
                        VStack(spacing: 12) {
                            ZStack {
                                Circle()
                                    .fill(Color.kePrimary.opacity(0.15))
                                    .frame(width: 100, height: 100)
                                Image(systemName: "storefront.fill")
                                    .font(.system(size: 56))
                                    .foregroundColor(.kePrimary)
                            }

                            Text("Get started with GreenEats")
                                .font(.system(size: 24, weight: .bold))
                                .foregroundColor(.keTextPrimary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 24)
                        }

                        // Phone entry (top, Uber-style)
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Work email or mobile number")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(.keTextPrimary)

                            HStack(spacing: 10) {
                                // Tapping the pill opens the country picker
                                // sheet. Default is US; search covers name,
                                // ISO, and dial code. Digit caps are country-
                                // agnostic per E.164 (max 15 digits nationally).
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
                                        // 15 is the E.164 national-number
                                        // ceiling. Tight per-country caps
                                        // would be nicer but require a
                                        // format library we don't ship.
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
                                .foregroundColor(.keTextOnAccent)
                                .frame(maxWidth: .infinity)
                                .frame(height: 52)
                                .background(isPhoneValid ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                                .cornerRadius(12)
                            }
                            .disabled(!isPhoneValid || authVM.isLoading)
                        }
                        .padding(.horizontal, 24)

                        // "or" divider
                        HStack(spacing: 12) {
                            Rectangle().fill(Color.keBorder).frame(height: 1)
                            Text("or")
                                .font(.caption)
                                .foregroundColor(.keTextSecondary)
                            Rectangle().fill(Color.keBorder).frame(height: 1)
                        }
                        .padding(.horizontal, 24)

                        // Social / email sign-in options
                        VStack(spacing: 12) {
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
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color.kePrimary, lineWidth: 1.5)
                                )
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
                                .frame(maxWidth: .infinity, minHeight: 50)
                                .foregroundColor(.keTextOnAccent)
                                .background(Color.keCard)
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color.kePrimary, lineWidth: 1.5)
                                )
                            }

                            // Unified email entry. EmailAuthView calls
                            // /auth/email/check to pick sign-in vs sign-up.
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
                                .cornerRadius(12)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(Color.kePrimary, lineWidth: 1.5)
                                )
                            }
                        }
                        .padding(.horizontal, 24)

                        // Error message (non-OTP flows)
                        if let error = authVM.errorMessage, !navigateToOTP {
                            Text(error)
                                .font(.system(size: 14))
                                .foregroundColor(.keError)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal)
                        }

                        Spacer().frame(height: 40)
                    }
                    .adaptiveContentWidth(520)
                }
            }
            .navigationDestination(isPresented: $navigateToOTP) {
                PhoneOTPView(
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
        }
    }

    /// Minimum national-number length of 7 handles short E.164 cases (e.g.
    /// some Caribbean +1 subscribers) without opening up obviously-bogus
    /// short inputs. Most countries land in the 7-11 range.
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
            // Clear any previous verify error before entering OTP screen.
            authVM.errorMessage = nil
            navigateToOTP = true
        }
    }

    /// Light formatting: "+1 315 664 5801" for US 10-digit numbers, otherwise
    /// "+<code> <digits>". Good enough for the OTP confirmation string without
    /// a proper libphonenumber dependency.
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
