import SwiftUI

/// Second step of phone login: courier enters the 4-digit code Twilio just
/// SMSed them. On success the view model flips `isAuthenticated`, which
/// RootView watches to pop back to the authenticated root.
struct CourierPhoneOTPView: View {
    let phoneE164: String
    let phoneDisplay: String
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var code = ""
    @State private var isResending = false
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
                        .cornerRadius(12)
                        .focused($codeFieldFocused)
                        .onChange(of: code) { _, newValue in
                            let digits = newValue.filter(\.isNumber)
                            if digits != newValue { code = digits }
                            if code.count > 4 { code = String(code.prefix(4)) }
                            if code.count == 4 {
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
                                    .progressViewStyle(CircularProgressViewStyle(tint: .keTextPrimary))
                            } else {
                                Text("Verify")
                                    .font(.headline)
                            }
                        }
                        .foregroundColor(code.count == 4 ? .keTextPrimary : .keTextMuted)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(Color.keCard)
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(code.count == 4 ? Color.kePrimaryDark : Color.keTextMuted, lineWidth: 1.5)
                        )
                    }
                    .disabled(code.count != 4 || authVM.isLoading)

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
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { codeFieldFocused = true }
    }

    private func submit() async {
        guard code.count == 4, !authVM.isLoading else { return }
        _ = await authVM.verifyPhoneLogin(phone: phoneE164, code: code)
    }

    private func resend() async {
        isResending = true
        defer { isResending = false }
        _ = await authVM.startPhoneLogin(phone: phoneE164)
    }
}
