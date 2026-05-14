import SwiftUI

struct LoginView: View {
    @EnvironmentObject var auth: AuthViewModel
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingMD) {
            Text("Welcome back")
                .font(.title.bold())
                .foregroundColor(.keTextPrimary)

            TextField("Email", text: $email)
                .keTextField()
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .autocapitalization(.none)

            SecureField("Password", text: $password)
                .keTextField()
                .textContentType(.password)

            if let err = auth.errorMessage {
                Text(err).font(.footnote).foregroundColor(.keError)
            }

            Button {
                Task { await auth.login(email: email, password: password) }
            } label: {
                if auth.isLoading { ProgressView().tint(.keTextOnAccent) } else { Text("Log in") }
            }
            .buttonStyle(KEAuthButtonStyle(isEnabled: !email.isEmpty && !password.isEmpty && !auth.isLoading))
            .disabled(email.isEmpty || password.isEmpty || auth.isLoading)

            Spacer()
        }
        .padding(Theme.spacingLG)
        .background(Color.keBackground.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
    }
}
