import SwiftUI

struct SellerLoginView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 32) {
                    Spacer().frame(height: 60)

                    // Logo
                    VStack(spacing: 12) {
                        Image(systemName: "storefront.fill")
                            .font(.system(size: 56))
                            .foregroundColor(.kePrimary)

                        Text("KosherEats")
                            .font(.system(size: 34, weight: .bold))
                            .foregroundColor(.keTextPrimary)

                        Text("Restaurant Dashboard")
                            .font(.subheadline)
                            .foregroundColor(.keTextSecondary)
                    }

                    // Social Login Buttons
                    VStack(spacing: 12) {
                        // Continue with Google
                        Button {
                            authVM.signInWithGoogle()
                        } label: {
                            HStack(spacing: 10) {
                                Text("G")
                                    .font(.system(size: 20, weight: .bold))
                                    .foregroundColor(.red)
                                Text("Continue with Google")
                                    .font(.system(size: 16, weight: .medium))
                            }
                            .foregroundColor(.keTextPrimary)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(Color.white)
                            .cornerRadius(12)
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.gray.opacity(0.3), lineWidth: 1)
                            )
                        }

                        // Continue with Apple
                        Button {
                            authVM.signInWithApple()
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "apple.logo")
                                    .font(.system(size: 20))
                                Text("Continue with Apple")
                                    .font(.system(size: 16, weight: .medium))
                            }
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(Color.black)
                            .cornerRadius(12)
                        }

                        // Continue with Facebook
                        Button {
                            authVM.signInWithFacebook()
                        } label: {
                            HStack(spacing: 10) {
                                Text("f")
                                    .font(.system(size: 22, weight: .bold))
                                Text("Continue with Facebook")
                                    .font(.system(size: 16, weight: .medium))
                            }
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(Color(red: 0.094, green: 0.467, blue: 0.949))
                            .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal, 24)

                    // Or divider
                    HStack(spacing: 16) {
                        Rectangle()
                            .frame(height: 1)
                            .foregroundColor(.keTextSecondary.opacity(0.3))
                        Text("or")
                            .font(.subheadline)
                            .foregroundColor(.keTextSecondary)
                        Rectangle()
                            .frame(height: 1)
                            .foregroundColor(.keTextSecondary.opacity(0.3))
                    }
                    .padding(.horizontal, 24)

                    // Form
                    VStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Email")
                                .font(.caption)
                                .foregroundColor(.keTextSecondary)

                            TextField("", text: $email)
                                .textFieldStyle(.plain)
                                .keyboardType(.emailAddress)
                                .textContentType(.emailAddress)
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                                .padding()
                                .background(Color.keCard)
                                .cornerRadius(12)
                                .foregroundColor(.keTextPrimary)
                        }

                        VStack(alignment: .leading, spacing: 8) {
                            Text("Password")
                                .font(.caption)
                                .foregroundColor(.keTextSecondary)

                            SecureField("", text: $password)
                                .textFieldStyle(.plain)
                                .textContentType(.password)
                                .padding()
                                .background(Color.keCard)
                                .cornerRadius(12)
                                .foregroundColor(.keTextPrimary)
                        }
                    }
                    .padding(.horizontal, 24)

                    if let error = authVM.errorMessage {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.keError)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }

                    // Login Button
                    Button {
                        Task {
                            await authVM.login(email: email, password: password)
                        }
                    } label: {
                        Group {
                            if authVM.isLoading {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            } else {
                                Text("Sign In")
                                    .font(.headline)
                            }
                        }
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(
                            canSubmit ? Color.kePrimary : Color.kePrimary.opacity(0.4)
                        )
                        .cornerRadius(12)
                    }
                    .disabled(!canSubmit || authVM.isLoading)
                    .padding(.horizontal, 24)

                    Spacer()
                }
            }
        }
    }

    private var canSubmit: Bool {
        !email.isEmpty && !password.isEmpty
    }
}
