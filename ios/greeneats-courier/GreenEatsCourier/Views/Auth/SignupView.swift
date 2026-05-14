import SwiftUI

struct SignupView: View {
    @EnvironmentObject var auth: AuthViewModel

    @State private var firstName = ""
    @State private var lastName = ""
    @State private var phone = ""
    @State private var email = ""
    @State private var password = ""

    private var formValid: Bool {
        !firstName.isEmpty && !phone.isEmpty && !email.isEmpty && password.count >= 8
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.spacingMD) {
                Text("Create your driver account")
                    .font(.title.bold())
                    .foregroundColor(.keTextPrimary)

                Text("Step 1 of 4 • Basic info")
                    .font(.caption)
                    .foregroundColor(.keTextTertiary)

                Group {
                    TextField("First name", text: $firstName).keTextField().textContentType(.givenName)
                    TextField("Last name", text: $lastName).keTextField().textContentType(.familyName)
                    TextField("Phone", text: $phone).keTextField().keyboardType(.phonePad).textContentType(.telephoneNumber)
                    TextField("Email", text: $email).keTextField().keyboardType(.emailAddress).textContentType(.emailAddress).autocapitalization(.none)
                    SecureField("Password (8+ characters)", text: $password).keTextField().textContentType(.newPassword)
                }

                if let err = auth.errorMessage {
                    Text(err).font(.footnote).foregroundColor(.keError)
                }

                Button {
                    Task {
                        await auth.signup(email: email, password: password,
                                          firstName: firstName, lastName: lastName, phone: phone)
                    }
                } label: {
                    if auth.isLoading {
                        ProgressView().tint(.keTextOnAccent)
                    } else {
                        Text("Continue")
                    }
                }
                .buttonStyle(KEAuthButtonStyle(isEnabled: formValid && !auth.isLoading))
                .disabled(!formValid || auth.isLoading)

                Text("By continuing you agree to a background check and GreenEats' Courier Agreement.")
                    .font(.caption2)
                    .foregroundColor(.keTextMuted)
                    .padding(.top, Theme.spacingSM)
            }
            .padding(Theme.spacingLG)
        }
        .background(Color.keBackground.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
    }
}
