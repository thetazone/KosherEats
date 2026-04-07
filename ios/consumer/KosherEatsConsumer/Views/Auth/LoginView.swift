import SwiftUI

struct LoginView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @State private var email = ""
    @State private var password = ""
    @State private var showRegister = false

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

                            Text("KosherEats")
                                .font(.system(size: 34, weight: .bold))
                                .foregroundColor(.kePrimary)

                            Text("Kosher food delivery,\ndone right.")
                                .font(.body)
                                .foregroundColor(.keTextSecondary)
                                .multilineTextAlignment(.center)
                        }

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
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Email")
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundColor(.keTextSecondary)
                                TextField("you@example.com", text: $email)
                                    .keTextField()
                                    .textContentType(.emailAddress)
                                    .keyboardType(.emailAddress)
                                    .autocapitalization(.none)
                                    .autocorrectionDisabled()
                            }

                            VStack(alignment: .leading, spacing: 6) {
                                Text("Password")
                                    .font(.system(size: 14, weight: .medium))
                                    .foregroundColor(.keTextSecondary)
                                SecureField("Enter your password", text: $password)
                                    .keTextField()
                                    .textContentType(.password)
                            }
                        }
                        .padding(.horizontal)

                        // Error message
                        if let error = authVM.errorMessage {
                            Text(error)
                                .font(.system(size: 14))
                                .foregroundColor(.keError)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal)
                        }

                        // Login button
                        Button {
                            Task {
                                await authVM.login(email: email, password: password)
                            }
                        } label: {
                            HStack {
                                if authVM.isLoading {
                                    ProgressView()
                                        .tint(.white)
                                } else {
                                    Text("Sign In")
                                }
                            }
                        }
                        .buttonStyle(KEPrimaryButtonStyle(isEnabled: isFormValid && !authVM.isLoading))
                        .disabled(!isFormValid || authVM.isLoading)
                        .padding(.horizontal)

                        // Register link
                        Button {
                            showRegister = true
                        } label: {
                            Text("Create an Account")
                        }
                        .buttonStyle(KESecondaryButtonStyle())
                        .padding(.horizontal)

                        Spacer().frame(height: 40)
                    }
                }
            }
            .navigationDestination(isPresented: $showRegister) {
                RegisterView()
            }
        }
    }

    private var isFormValid: Bool {
        !email.trimmingCharacters(in: .whitespaces).isEmpty &&
        !password.isEmpty
    }
}
