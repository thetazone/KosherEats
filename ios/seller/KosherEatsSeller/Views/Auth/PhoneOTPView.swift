import SwiftUI

/// Second step of phone login: user enters the verification code Twilio just
/// SMSed them. Twilio Verify often uses 6 digits by default, but the exact
/// length is service-configurable, so the UI accepts a reasonable numeric
/// range instead of hard-coding 4 digits.
struct PhoneOTPView: View {
    private let minCodeLength = 4
    private let maxCodeLength = 10

    let phoneE164: String
    let phoneDisplay: String
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var code = ""
    @State private var isResending = false
    @State private var isSubmitting = false
    @FocusState private var codeFieldFocused: Bool

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Enter the code")
                            .font(.system(size: 28, weight: .bold))
                            .foregroundColor(.keTextPrimary)
                        Text("We sent a verification code to \(phoneDisplay).")
                            .font(.body)
                            .foregroundColor(.keTextSecondary)
                    }

                    TextField("1234", text: $code)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .font(.system(size: 28, weight: .semibold, design: .monospaced))
                        .foregroundColor(.keTextPrimary)
                        .padding()
                        .background(Color.keCard)
                        .cornerRadius(12)
                        .focused($codeFieldFocused)
                        .onChange(of: code) { _, newValue in
                            // Keep only digits. Twilio Verify code length is
                            // configured on the service, so accept a sensible
                            // numeric range instead of truncating to four.
                            let digits = newValue.filter(\.isNumber)
                            if digits != newValue { code = digits }
                            if code.count > maxCodeLength {
                                code = String(code.prefix(maxCodeLength))
                            }
                        }

                    if let error = authVM.errorMessage {
                        Text(error)
                            .font(.system(size: 14))
                            .foregroundColor(.keError)
                    }

                    Button {
                        Task { await submit() }
                    } label: {
                        Group {
                            if authVM.isLoading || isSubmitting {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            } else {
                                Text("Verify")
                                    .font(.headline)
                            }
                        }
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(code.count >= minCodeLength ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                        .cornerRadius(12)
                    }
                    .disabled(code.count < minCodeLength || authVM.isLoading || isSubmitting)

                    HStack(spacing: 4) {
                        Text("Didn't get a code?")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                        Button {
                            Task { await resend() }
                        } label: {
                            Text(isResending ? "Sending…" : "Resend")
                                .font(.caption.bold())
                                .foregroundColor(.kePrimary)
                        }
                        .disabled(isResending)
                    }

                    Spacer()
                }
                .padding(24)
                .adaptiveContentWidth(520)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { codeFieldFocused = true }
    }

    private func submit() async {
        guard code.count >= minCodeLength, !isSubmitting, !authVM.isLoading else { return }
        isSubmitting = true
        defer { isSubmitting = false }
        _ = await authVM.verifyPhoneLogin(phone: phoneE164, code: code)
    }

    private func resend() async {
        isResending = true
        defer { isResending = false }
        _ = await authVM.startPhoneLogin(phone: phoneE164)
    }
}
