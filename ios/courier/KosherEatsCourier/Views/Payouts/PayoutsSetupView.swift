import SwiftUI
import SafariServices

// PayoutsSetupView is the Stripe Connect onboarding entry point.
// The actual KYC happens inside Stripe's hosted flow (SFSafariViewController),
// which is the exact pattern DoorDash / UberEats use.
struct PayoutsSetupView: View {
    @EnvironmentObject var auth: AuthViewModel
    @State private var status: APIService.PayoutStatus?
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var onboardingURL: URL?
    @State private var showSafari = false

    var body: some View {
        ScrollView {
            VStack(spacing: Theme.spacingLG) {
                header

                if let s = status, s.payoutReady {
                    readyCard
                } else {
                    setupCard
                }

                if let err = errorMessage {
                    Text(err)
                        .font(.footnote)
                        .foregroundColor(.keError)
                }
            }
            .padding(Theme.spacingLG)
        }
        .background(Color.keBackground.ignoresSafeArea())
        .navigationTitle("Payouts")
        .task { await refreshStatus() }
        .sheet(isPresented: $showSafari, onDismiss: { Task { await refreshStatus() } }) {
            if let url = onboardingURL {
                SafariView(url: url)
                    .ignoresSafeArea()
            }
        }
    }

    private var header: some View {
        VStack(spacing: Theme.spacingSM) {
            Image(systemName: "dollarsign.circle.fill")
                .font(.system(size: 56))
                .foregroundColor(.kePrimary)
            Text("Direct deposit")
                .font(.title2.bold())
                .foregroundColor(.keTextPrimary)
            Text("Get paid for every delivery, straight to your bank account.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, Theme.spacingMD)
    }

    private var setupCard: some View {
        VStack(alignment: .leading, spacing: Theme.spacingMD) {
            Text("Set up with Stripe")
                .font(.headline)
                .foregroundColor(.keTextPrimary)
            Text("Stripe securely handles your bank info and tax forms. It takes about 3 minutes.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)

            bulletRow(icon: "lock.shield.fill", text: "Bank-level security")
            bulletRow(icon: "clock.fill", text: "Same-day or 2-day transfers")
            bulletRow(icon: "doc.text.fill", text: "Automatic tax forms (1099-NEC)")

            Button {
                Task { await startOnboarding() }
            } label: {
                if isLoading {
                    ProgressView().tint(.white)
                } else {
                    Text("Set up payouts")
                }
            }
            .buttonStyle(KEPrimaryButtonStyle(isEnabled: !isLoading))
            .disabled(isLoading)
            .padding(.top, Theme.spacingSM)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private var readyCard: some View {
        VStack(spacing: Theme.spacingMD) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 48))
                .foregroundColor(.keSuccess)
            Text("Payouts are ready")
                .font(.headline)
                .foregroundColor(.keTextPrimary)
            Text("You'll receive earnings from every delivery directly in your bank account.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)

            Button("Update banking info") {
                Task { await startOnboarding() }
            }
            .buttonStyle(KESecondaryButtonStyle())
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private func bulletRow(icon: String, text: String) -> some View {
        HStack(spacing: Theme.spacingSM) {
            Image(systemName: icon)
                .foregroundColor(.kePrimary)
                .frame(width: 20)
            Text(text)
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
        }
    }

    // MARK: - Actions

    private func refreshStatus() async {
        do {
            let fresh = try await APIService.shared.getPayoutStatus()
            status = fresh
            // When Stripe reports the courier is good to go, reload the
            // top-level profile so the dashboard's "set up payouts" banner
            // vanishes without requiring a manual pull-to-refresh.
            if fresh.payoutReady {
                await auth.loadProfile()
            }
        } catch {
            // 401 just means we haven't created an account yet — that's fine
            status = nil
        }
    }

    private func startOnboarding() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            // Create account if needed, then fetch a hosted onboarding link.
            _ = try await APIService.shared.createPayoutAccount()
            let link = try await APIService.shared.getPayoutLink()
            if let url = URL(string: link.url) {
                onboardingURL = url
                showSafari = true
            }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}

// SafariView wraps SFSafariViewController for SwiftUI. Stripe's hosted
// onboarding must render in a web view that preserves their domain.
struct SafariView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }
    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}
