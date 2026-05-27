import SwiftUI
import SafariServices

// Settings → Integrations. Lets the seller connect a POS (Clover today;
// Square/Toast follow the same shape once their backend adapters ship)
// so accepted orders auto-print at the kitchen.
//
// The Connect button opens an in-app Safari sheet pointed at our backend's
// /seller/integrations/clover/connect-url, which returns the OAuth
// AuthorizeURL pre-baked with our state token. After Clover redirects to
// /api/v1/integrations/clover/callback, the in-app browser shows a small
// success page; the seller dismisses it manually for now.
struct IntegrationsView: View {
    @State private var integrations: [APIService.POSIntegration] = []
    @State private var isLoading = true
    @State private var errorMessage: String?

    @State private var safariURL: URL?
    @State private var showSafari = false

    @State private var testingID: String?
    @State private var testResultByID: [String: String] = [:]
    @State private var disconnectTarget: APIService.POSIntegration?
    @State private var showDisconnectConfirm = false
    @State private var isConnecting = false

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header

                    if isLoading {
                        ProgressView("Loading integrations...")
                            .tint(.kePrimary)
                            .padding(.top, 32)
                    } else if let errorMessage, integrations.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.system(size: 32))
                                .foregroundColor(.keError)
                                .accessibilityHidden(true)
                            Text(errorMessage)
                                .font(.subheadline)
                                .foregroundColor(.keError)
                                .multilineTextAlignment(.center)
                            Button("Retry") {
                                Task { await load() }
                            }
                            .font(.subheadline.bold())
                            .foregroundColor(.kePrimary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                        .accessibilityElement(children: .combine)
                    } else if integrations.isEmpty {
                        emptyState
                    } else {
                        ForEach(integrations) { integ in
                            integrationCard(integ)
                        }
                    }

                    Button(action: connectClover) {
                        HStack(spacing: 10) {
                            if isConnecting {
                                ProgressView().controlSize(.small).tint(.white)
                            } else {
                                Image(systemName: "plus.circle.fill")
                            }
                            Text("Connect Clover")
                        }
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(isConnecting ? Color.kePrimary.opacity(0.4) : Color.kePrimary)
                        .cornerRadius(12)
                    }
                    .disabled(isConnecting)
                    .accessibilityLabel("Connect Clover POS")

                    if let errorMessage {
                        Text(errorMessage)
                            .font(.caption)
                            .foregroundColor(.keError)
                    }

                    Text("Square and Toast support coming soon. If you use a different POS, let us know.")
                        .font(.caption)
                        .foregroundColor(.keTextSecondary)
                        .padding(.top, 8)
                }
                .padding(20)
                .adaptiveContentWidth(560)
            }
        }
        .navigationTitle("Integrations")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $showSafari) {
            if let safariURL {
                SafariView(url: safariURL).ignoresSafeArea()
            }
        }
        .confirmationDialog(
            "Disconnect \(disconnectTarget?.provider.capitalized ?? "integration")?",
            isPresented: $showDisconnectConfirm,
            titleVisibility: .visible
        ) {
            Button("Disconnect", role: .destructive) {
                if let target = disconnectTarget {
                    Task { await disconnect(target) }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Orders will no longer auto-print to this POS. You can reconnect later.")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Connect your POS")
                .font(.title3.bold())
                .foregroundColor(.keTextPrimary)
            Text("When you tap Accept on a new order, it'll push to your POS so your kitchen printer fires automatically.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "printer.fill")
                .font(.system(size: 36))
                .foregroundColor(.keTextMuted)
            Text("No POS connected yet")
                .font(.subheadline.bold())
                .foregroundColor(.keTextPrimary)
            Text("Connect Clover below to start auto-printing kitchen tickets.")
                .font(.caption)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }

    private func integrationCard(_ integ: APIService.POSIntegration) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(integ.provider.capitalized)
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextPrimary)
                    Text("Merchant \(integ.merchantId)")
                        .font(.caption)
                        .foregroundColor(.keTextSecondary)
                }
                Spacer()
                statusPill(for: integ)
            }

            HStack(spacing: 8) {
                Button {
                    Task { await test(integ) }
                } label: {
                    HStack(spacing: 6) {
                        if testingID == integ.id {
                            ProgressView().controlSize(.small).tint(.kePrimary)
                        } else {
                            Image(systemName: "checkmark.circle")
                        }
                        Text("Test connection")
                    }
                    .font(.caption.bold())
                    .foregroundColor(.kePrimary)
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .background(Color.kePrimary.opacity(0.1))
                    .cornerRadius(8)
                }
                .disabled(testingID == integ.id)

                Button(role: .destructive) {
                    disconnectTarget = integ
                    showDisconnectConfirm = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "xmark.circle")
                        Text("Disconnect")
                    }
                    .font(.caption.bold())
                    .foregroundColor(.keError)
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .background(Color.keError.opacity(0.1))
                    .cornerRadius(8)
                }
            }

            if let result = testResultByID[integ.id] {
                Text(result)
                    .font(.caption)
                    .foregroundColor(result.lowercased().hasPrefix("ok") ? .kePrimary : .keError)
            }
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(12)
    }

    private func statusPill(for integ: APIService.POSIntegration) -> some View {
        Text(integ.isActive ? "Active" : "Disconnected")
            .font(.caption.bold())
            .foregroundColor(integ.isActive ? .keTextOnAccent : .keTextMuted)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(integ.isActive ? Color.kePrimary : Color.keSurface)
            .cornerRadius(6)
            .accessibilityLabel("Status: \(integ.isActive ? "Active" : "Disconnected")")
    }

    // MARK: - Actions

    private func load() async {
        isLoading = true
        errorMessage = nil
        do {
            integrations = try await APIService.shared.listIntegrations()
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    private func connectClover() {
        Task {
            isConnecting = true
            defer { isConnecting = false }
            do {
                let urlString = try await APIService.shared.cloverConnectURL()
                guard let url = URL(string: urlString) else {
                    errorMessage = "Bad connect URL from server."
                    return
                }
                safariURL = url
                showSafari = true
            } catch {
                errorMessage = "Couldn't start Clover connect: \(error.localizedDescription)"
            }
        }
    }

    private func test(_ integ: APIService.POSIntegration) async {
        testingID = integ.id
        testResultByID[integ.id] = nil
        defer { testingID = nil }
        do {
            try await APIService.shared.testIntegration(id: integ.id)
            testResultByID[integ.id] = "OK — connection verified."
        } catch {
            testResultByID[integ.id] = "Fail: \(error.localizedDescription)"
        }
    }

    private func disconnect(_ integ: APIService.POSIntegration) async {
        do {
            try await APIService.shared.disconnectIntegration(id: integ.id)
            await load()
        } catch {
            errorMessage = "Disconnect failed: \(error.localizedDescription)"
        }
    }
}

// Minimal SFSafariViewController wrapper for the OAuth in-app browser.
// We don't try to deep-link out of the seller app on completion — the
// Safari sheet's success page shows "you can close this window" and the
// seller dismisses it; the parent view's .refreshable handles re-fetch
// next time they pull down.
private struct SafariView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController {
        let c = SFSafariViewController(url: url)
        c.preferredControlTintColor = .systemOrange
        return c
    }
    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}
