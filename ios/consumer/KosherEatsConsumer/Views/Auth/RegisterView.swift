import SwiftUI

struct RegisterView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.dismiss) var dismiss
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var email = ""
    @State private var phone = ""
    @State private var password = ""
    @State private var confirmPassword = ""

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(spacing: Theme.spacingLG) {
                    // Header
                    VStack(spacing: 8) {
                        Text("Create Account")
                            .font(.system(size: 28, weight: .bold))
                            .foregroundColor(.keTextPrimary)
                        Text("Join KosherEats and start ordering")
                            .font(.body)
                            .foregroundColor(.keTextSecondary)
                    }
                    .padding(.top, Theme.spacingLG)

                    // Social Login Buttons
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
                            .foregroundColor(.white)
                            .background(Color.black)
                            .cornerRadius(12)
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
                                    .foregroundColor(.keTextPrimary)
                            }
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(Color.white)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.keDivider, lineWidth: 1)
                            )
                        }

                    }
                    .padding(.horizontal)

                    // Or divider
                    HStack {
                        Rectangle()
                            .fill(Color.keDivider)
                            .frame(height: 1)
                        Text("or")
                            .font(.system(size: 13))
                            .foregroundColor(.keTextMuted)
                        Rectangle()
                            .fill(Color.keDivider)
                            .frame(height: 1)
                    }
                    .padding(.horizontal, 40)

                    // Form
                    VStack(spacing: 14) {
                        HStack(spacing: 12) {
                            FormField(title: "First Name", text: $firstName, placeholder: "First")
                            FormField(title: "Last Name", text: $lastName, placeholder: "Last")
                        }

                        FormField(title: "Email", text: $email, placeholder: "you@example.com", keyboard: .emailAddress, content: .emailAddress)

                        FormField(title: "Phone", text: $phone, placeholder: "(555) 123-4567", keyboard: .phonePad, content: .telephoneNumber)

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Password")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.keTextSecondary)
                            SecureField("Min. 8 characters", text: $password)
                                .keTextField()
                                .textContentType(.newPassword)
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Confirm Password")
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(.keTextSecondary)
                            SecureField("Re-enter your password", text: $confirmPassword)
                                .keTextField()
                                .textContentType(.newPassword)
                        }

                        if !confirmPassword.isEmpty && password != confirmPassword {
                            Text("Passwords do not match")
                                .font(.system(size: 13))
                                .foregroundColor(.keError)
                        }
                    }
                    .padding(.horizontal)

                    if let error = authVM.errorMessage {
                        Text(error)
                            .font(.system(size: 14))
                            .foregroundColor(.keError)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }

                    // Register button
                    Button {
                        Task {
                            await authVM.register(
                                email: email,
                                password: password,
                                firstName: firstName,
                                lastName: lastName,
                                phone: phone
                            )
                        }
                    } label: {
                        HStack {
                            if authVM.isLoading {
                                ProgressView()
                                    .tint(.white)
                            } else {
                                Text("Create Account")
                            }
                        }
                    }
                    .buttonStyle(KEPrimaryButtonStyle(isEnabled: isFormValid && !authVM.isLoading))
                    .disabled(!isFormValid || authVM.isLoading)
                    .padding(.horizontal)

                    // Login link
                    Button {
                        dismiss()
                    } label: {
                        HStack(spacing: 4) {
                            Text("Already have an account?")
                                .foregroundColor(.keTextSecondary)
                            Text("Sign In")
                                .foregroundColor(.kePrimary)
                                .fontWeight(.semibold)
                        }
                        .font(.system(size: 15))
                    }

                    Spacer().frame(height: 40)
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
    }

    private var isFormValid: Bool {
        !firstName.trimmingCharacters(in: .whitespaces).isEmpty &&
        !lastName.trimmingCharacters(in: .whitespaces).isEmpty &&
        !email.trimmingCharacters(in: .whitespaces).isEmpty &&
        !phone.trimmingCharacters(in: .whitespaces).isEmpty &&
        password.count >= 8 &&
        password == confirmPassword
    }
}

// MARK: - Form Field

struct FormField: View {
    let title: String
    @Binding var text: String
    var placeholder: String = ""
    var keyboard: UIKeyboardType = .default
    var content: UITextContentType? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.keTextSecondary)

            TextField(placeholder, text: $text)
                .keTextField()
                .keyboardType(keyboard)
                .textContentType(content)
                .autocapitalization(keyboard == .emailAddress ? .none : .words)
                .autocorrectionDisabled()
        }
    }
}
