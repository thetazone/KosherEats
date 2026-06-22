import SwiftUI

/// Second step of phone login: consumer enters the 4-digit code Twilio just
/// SMSed them. On success the view model flips `isAuthenticated`, which
/// LoginView watches to dismiss back to the root.
struct ConsumerPhoneOTPView: View {
    let phoneE164: String
    let phoneDisplay: String
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var code = ""
    @State private var isResending = false
    @State private var autoResendTask: Task<Void, Never>?
    @State private var resendCountdown: Int = 0
    @State private var countdownTimer: Task<Void, Never>?
    @State private var hasAutoSubmitted = false
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
                        Text("We sent a 4-digit code to \(phoneDisplay).")
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
                        .cornerRadius(Theme.cornerRadiusMedium)
                        .focused($codeFieldFocused)
                        .accessibilityLabel("Verification code")
                        .accessibilityHint("Enter the 4-digit code sent to \(phoneDisplay)")
                        .onChange(of: code) { _, newValue in
                            let digits = newValue.filter(\.isNumber)
                            if digits != newValue { code = digits }
                            if code.count > 4 { code = String(code.prefix(4)) }
                            if code.count == 4, !hasAutoSubmitted {
                                hasAutoSubmitted = true
                                Task { await submit() }
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
                            if authVM.isLoading {
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
                        .background(code.count == 4 ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                        .cornerRadius(Theme.cornerRadiusMedium)
                    }
                    .disabled(code.count != 4 || authVM.isLoading)

                    HStack(spacing: 4) {
                        Text("Didn't get a code?")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                        Button {
                            Task { await resend() }
                        } label: {
                            if isResending {
                                Text("Sending…")
                                    .font(.caption.bold())
                                    .foregroundColor(.kePrimary)
                            } else if resendCountdown > 0 {
                                Text("Resend in \(resendCountdown)s")
                                    .font(.caption.bold())
                                    .foregroundColor(.keTextSecondary)
                            } else {
                                Text("Resend")
                                    .font(.caption.bold())
                                    .foregroundColor(.kePrimary)
                            }
                        }
                        .disabled(isResending || resendCountdown > 0)
                        .accessibilityLabel(resendCountdown > 0 ? "Resend code available in \(resendCountdown) seconds" : "Resend code")
                    }

                    Spacer()
                }
                .padding(24)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            codeFieldFocused = true
            startResendCountdown()
            autoResendTask = Task {
                try? await Task.sleep(for: .seconds(15))
                if !Task.isCancelled && code.isEmpty {
                    await authVM.silentResendOTP(phone: phoneE164)
                }
            }
        }
        .onDisappear {
            autoResendTask?.cancel()
            countdownTimer?.cancel()
        }
    }

    private func submit() async {
        guard code.count == 4, !authVM.isLoading else { return }
        _ = await authVM.verifyPhoneLogin(phone: phoneE164, code: code)
        // On a failed verification, clear the stale digits so the user can retype
        // a fresh code instead of an edit re-firing auto-submit with the same wrong code.
        if !authVM.isAuthenticated {
            code = ""
            hasAutoSubmitted = false
        }
    }

    private func resend() async {
        isResending = true
        defer { isResending = false }
        _ = await authVM.startPhoneLogin(phone: phoneE164)
        startResendCountdown()
    }

    private func startResendCountdown() {
        resendCountdown = 30
        countdownTimer?.cancel()
        countdownTimer = Task {
            while resendCountdown > 0, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if !Task.isCancelled { resendCountdown -= 1 }
            }
        }
    }
}
