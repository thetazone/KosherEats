import SwiftUI
import AuthenticationServices
import GoogleSignIn

struct ConnectedAccountsView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @StateObject private var vm = ConnectedAccountsViewModel()
    @State private var providerToUnlink: String?

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 20) {
                    Text("Connect multiple sign-in methods so you can access your restaurants from any of them.")
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    providerRow("Apple", icon: "apple.logo", provider: "apple")
                    providerRow("Google", icon: "g.circle.fill", provider: "google")
                    providerRow("Phone", icon: "phone.fill", provider: "phone")

                    if let error = vm.errorMessage {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.keError)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding()
            }
        }
        .navigationTitle("Connected Accounts")
        .navigationBarTitleDisplayMode(.large)
        .task { await vm.load() }
        .sheet(isPresented: $vm.showPhoneLinkSheet) {
            PhoneLinkSheet(vm: vm)
        }
        .alert("Remove Account", isPresented: Binding(
            get: { providerToUnlink != nil },
            set: { if !$0 { providerToUnlink = nil } }
        )) {
            Button("Cancel", role: .cancel) { providerToUnlink = nil }
            Button("Remove", role: .destructive) {
                if let provider = providerToUnlink {
                    Task { await vm.unlink(provider) }
                }
                providerToUnlink = nil
            }
        } message: {
            Text("You will no longer be able to sign in with this method. Are you sure?")
        }
    }

    @ViewBuilder
    private func providerRow(_ name: String, icon: String, provider: String) -> some View {
        let isLinked = vm.linkedProviders.contains { $0.provider == provider }

        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(.keTextPrimary)
                .frame(width: 32)

            Text(name)
                .font(.body.weight(.medium))
                .foregroundColor(.keTextPrimary)

            Spacer()

            if vm.loadingProvider == provider {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle())
            } else if isLinked {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.keSuccess)
                        .accessibilityLabel("\(name) connected")
                    if vm.linkedProviders.count > 1 {
                        Button("Remove") {
                            providerToUnlink = provider
                        }
                        .font(.caption.bold())
                        .foregroundColor(.keError)
                        .accessibilityLabel("Remove \(name) account")
                    }
                }
            } else {
                Button("Connect") {
                    Task { await connect(provider) }
                }
                .font(.subheadline.bold())
                .foregroundColor(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.kePrimary)
                .cornerRadius(8)
                .accessibilityLabel("Connect \(name) account")
            }
        }
        .padding()
        .background(Color.keSurface)
        .cornerRadius(12)
    }

    private func connect(_ provider: String) async {
        switch provider {
        case "apple":
            vm.connectApple()
        case "google":
            vm.connectGoogle()
        case "phone":
            vm.showPhoneLinkSheet = true
        default:
            break
        }
    }
}

// MARK: - Phone Link Sheet

private struct PhoneLinkSheet: View {
    @ObservedObject var vm: ConnectedAccountsViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var phone = ""
    @State private var code = ""
    @State private var codeSent = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if !codeSent {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Phone Number")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                        TextField("+1 (555) 123-4567", text: $phone)
                            .keyboardType(.phonePad)
                            .padding()
                            .background(Color.keCard)
                            .cornerRadius(10)
                    }

                    Button {
                        Task {
                            codeSent = await vm.startPhoneLink(phone: phone)
                        }
                    } label: {
                        if vm.loadingProvider == "phone" {
                            ProgressView().progressViewStyle(CircularProgressViewStyle(tint: .white))
                        } else {
                            Text("Send Code")
                        }
                    }
                    .font(.headline)
                    .foregroundColor(.keTextOnAccent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.kePrimary)
                    .cornerRadius(12)
                    .disabled(phone.isEmpty || vm.loadingProvider != nil)
                } else {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Verification Code")
                            .font(.caption)
                            .foregroundColor(.keTextSecondary)
                        TextField("1234", text: $code)
                            .keyboardType(.numberPad)
                            .padding()
                            .background(Color.keCard)
                            .cornerRadius(10)
                    }

                    Button {
                        Task {
                            let success = await vm.verifyPhoneLink(phone: phone, code: code)
                            if success { dismiss() }
                        }
                    } label: {
                        if vm.loadingProvider == "phone" {
                            ProgressView().progressViewStyle(CircularProgressViewStyle(tint: .white))
                        } else {
                            Text("Verify & Link")
                        }
                    }
                    .font(.headline)
                    .foregroundColor(.keTextOnAccent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.kePrimary)
                    .cornerRadius(12)
                    .disabled(code.isEmpty || vm.loadingProvider != nil)
                }

                if let error = vm.errorMessage {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(.keError)
                }

                Spacer()
            }
            .padding()
            .navigationTitle("Link Phone Number")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - ViewModel

@MainActor
class ConnectedAccountsViewModel: ObservableObject {
    @Published var linkedProviders: [LinkedProvider] = []
    @Published var loadingProvider: String?
    @Published var errorMessage: String?
    @Published var showPhoneLinkSheet = false

    private var appleCoordinator: AppleSignInCoordinator?
    private var appleController: ASAuthorizationController?

    func load() async {
        do {
            linkedProviders = try await APIService.shared.listLinkedProviders()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func unlink(_ provider: String) async {
        loadingProvider = provider
        errorMessage = nil
        defer { loadingProvider = nil }
        do {
            try await APIService.shared.unlinkProvider(provider)
            linkedProviders.removeAll { $0.provider == provider }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Apple

    func connectApple() {
        loadingProvider = "apple"
        errorMessage = nil

        let (rawNonce, hashedNonce) = AppleSignInNonce.generate()
        let provider = ASAuthorizationAppleIDProvider()
        let request = provider.createRequest()
        request.requestedScopes = [.email]
        request.nonce = hashedNonce

        let coordinator = AppleSignInCoordinator(rawNonce: rawNonce) { [weak self] result in
            guard let self else { return }
            Task { @MainActor in
                defer {
                    self.appleCoordinator = nil
                    self.appleController = nil
                }
                switch result {
                case .success(let (token, _, _, nonce)):
                    await self.linkApple(token: token, nonce: nonce)
                case .failure(let error):
                    if let authError = error as? ASAuthorizationError, authError.code == .canceled {
                        self.loadingProvider = nil
                        return
                    }
                    self.errorMessage = "Apple sign-in failed."
                    self.loadingProvider = nil
                }
            }
        }

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = coordinator
        controller.presentationContextProvider = coordinator
        self.appleCoordinator = coordinator
        self.appleController = controller
        controller.performRequests()
    }

    private func linkApple(token: String, nonce: String) async {
        defer { loadingProvider = nil }
        do {
            try await APIService.shared.linkProvider(provider: "apple", token: token, nonce: nonce)
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Google

    func connectGoogle() {
        loadingProvider = "google"
        errorMessage = nil

        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let activeScene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        guard let rootVC = activeScene?.windows.first(where: \.isKeyWindow)?.rootViewController
                ?? activeScene?.windows.first?.rootViewController else {
            errorMessage = "Google sign-in failed."
            loadingProvider = nil
            return
        }

        GIDSignIn.sharedInstance.signIn(withPresenting: rootVC) { [weak self] result, error in
            guard let self else { return }
            Task { @MainActor in
                if let error {
                    let nsError = error as NSError
                    if nsError.domain == "com.google.GIDSignIn" && nsError.code == -5 {
                        self.loadingProvider = nil
                        return
                    }
                    self.errorMessage = "Google sign-in failed."
                    self.loadingProvider = nil
                    return
                }
                guard let idToken = result?.user.idToken?.tokenString else {
                    self.errorMessage = "Google sign-in failed."
                    self.loadingProvider = nil
                    return
                }
                await self.linkGoogle(token: idToken)
            }
        }
    }

    private func linkGoogle(token: String) async {
        defer { loadingProvider = nil }
        do {
            try await APIService.shared.linkProvider(provider: "google", token: token)
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Phone

    func startPhoneLink(phone: String) async -> Bool {
        loadingProvider = "phone"
        errorMessage = nil
        defer { loadingProvider = nil }
        do {
            try await APIService.shared.startPhoneLogin(phone: phone)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func verifyPhoneLink(phone: String, code: String) async -> Bool {
        loadingProvider = "phone"
        errorMessage = nil
        defer { loadingProvider = nil }
        do {
            try await APIService.shared.linkPhone(phone: phone, code: code)
            await load()
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }
}
