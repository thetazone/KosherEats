import SwiftUI
import StripePaymentSheet

/// Profile → Payment Methods. Wraps Stripe's STPCustomerSheet so the user
/// can list / add / delete their saved cards without having to go through
/// checkout. The customer is persisted server-side (see
/// users.stripe_customer_id) so whatever gets saved here shows up at
/// checkout and vice versa.
///
/// In dev-stub mode (no STRIPE_SECRET_KEY on the backend) this degrades to
/// an info message — the customer bundle has no real ephemeral key so
/// presenting the sheet would just fail.
struct PaymentMethodsView: View {
    @State private var bundle: APIService.CustomerBundle?
    @State private var selectedPaymentMethod: STPPaymentMethod?
    @State private var selectedPaymentOptionDisplay: PaymentOptionDisplayData?
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var showSheet = false

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            if isLoading {
                ProgressView().tint(.kePrimary)
            } else if let err = errorMessage {
                errorState(message: err)
            } else if let bundle, bundle.isStub {
                stubState
            } else {
                content
            }
        }
        .navigationTitle("Payment Methods")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .background(
            CustomerSheetPresenter(
                isPresented: $showSheet,
                bundle: bundle,
                onCompletion: handleCustomerSheetResult
            )
        )
    }

    @ViewBuilder
    private var content: some View {
        ScrollView {
            VStack(spacing: Theme.spacingLG) {
                savedMethodCard

                Button { showSheet = true } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "creditcard.fill")
                        Text(selectedPaymentOptionDisplay == nil ? "Add Payment Method" : "Manage Payment Methods")
                    }
                }
                .buttonStyle(KEPrimaryButtonStyle())
                .padding(.horizontal)

                Text("Payments are processed securely by Stripe. GreenEats never sees your full card number.")
                    .font(.caption)
                    .foregroundColor(.keTextMuted)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                    .padding(.top, 4)

                Spacer(minLength: 40)
            }
            .padding(.top, Theme.spacingMD)
        }
    }

    private var savedMethodCard: some View {
        VStack(spacing: 10) {
            HStack(spacing: 14) {
                Image(systemName: selectedPaymentOptionDisplay == nil ? "creditcard" : "checkmark.seal.fill")
                    .font(.system(size: 26))
                    .foregroundColor(selectedPaymentOptionDisplay == nil ? .keTextMuted : .keSuccess)
                    .frame(width: 44, height: 44)
                    .background(Color.kePrimary.opacity(0.1))
                    .clipShape(Circle())

                VStack(alignment: .leading, spacing: 4) {
                    Text(selectedPaymentOptionDisplay?.label ?? "No default method")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.keTextPrimary)
                    Text(selectedPaymentOptionDisplay?.sublabel ?? "Add a card to check out faster.")
                        .font(.caption)
                        .foregroundColor(.keTextSecondary)
                }
                Spacer()
            }
            .padding(16)
        }
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .padding(.horizontal)
    }

    private var stubState: some View {
        VStack(spacing: Theme.spacingMD) {
            Image(systemName: "wrench.and.screwdriver.fill")
                .font(.system(size: 48))
                .foregroundColor(.keTextMuted)
            Text("Payments not configured")
                .font(.headline)
                .foregroundColor(.keTextPrimary)
            Text("The server is running without Stripe keys, so there's nothing to manage here yet.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
    }

    private func errorState(message: String) -> some View {
        VStack(spacing: Theme.spacingMD) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 40))
                .foregroundColor(.keError)
            Text(message)
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button("Try again") {
                Task { await load() }
            }
            .buttonStyle(KESecondaryButtonStyle())
            .frame(maxWidth: 280)
        }
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            bundle = try await APIService.shared.getPaymentCustomer()
            if let bundle, !bundle.isStub {
                STPAPIClient.shared.publishableKey = bundle.publishableKey
            }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func handleCustomerSheetResult(_ result: CustomerSheet.CustomerSheetResult) {
        switch result {
        case .selected(let option):
            selectedPaymentMethod = extractPaymentMethod(option)
            selectedPaymentOptionDisplay = optionDisplayData(from: option)
        case .canceled(let option):
            selectedPaymentMethod = extractPaymentMethod(option)
            selectedPaymentOptionDisplay = optionDisplayData(from: option)
        case .error(let error):
            errorMessage = error.localizedDescription
        }
    }

    private func extractPaymentMethod(_ option: CustomerSheet.PaymentOptionSelection?) -> STPPaymentMethod? {
        guard let option else { return nil }
        switch option {
        case .paymentMethod(let pm, _): return pm
        case .applePay: return nil
        @unknown default: return nil
        }
    }

    private func optionDisplayData(from option: CustomerSheet.PaymentOptionSelection?) -> PaymentOptionDisplayData? {
        guard let option else { return nil }
        switch option {
        case .paymentMethod(let pm, _):
            if let card = pm.card {
                let brand = STPCardBrandUtilities.stringFrom(card.brand) ?? "Card"
                return PaymentOptionDisplayData(
                    label: "\(brand) •••• \(card.last4 ?? "")",
                    sublabel: "Expires \(String(format: "%02d", card.expMonth))/\(card.expYear % 100)"
                )
            }
            return PaymentOptionDisplayData(label: "Saved payment method", sublabel: "Tap Manage to edit")
        case .applePay:
            return PaymentOptionDisplayData(label: "Apple Pay", sublabel: "Default method")
        @unknown default:
            return nil
        }
    }
}

struct PaymentOptionDisplayData: Equatable {
    let label: String
    let sublabel: String
}

// MARK: - CustomerSheet bridge

/// Presents Stripe's STPCustomerSheet when `isPresented` flips true. Backed
/// by a UIViewControllerRepresentable so the SwiftUI view tree doesn't have
/// to know about UIKit presentation. We rebuild the adapter each time the
/// sheet opens so the ephemeral key is fresh (they're short-lived).
private struct CustomerSheetPresenter: UIViewControllerRepresentable {
    @Binding var isPresented: Bool
    let bundle: APIService.CustomerBundle?
    let onCompletion: (CustomerSheet.CustomerSheetResult) -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        UIViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        guard isPresented,
              let bundle = bundle,
              !bundle.isStub,
              uiViewController.presentedViewController == nil else {
            return
        }

        let adapter = StripeCustomerAdapter(
            customerEphemeralKeyProvider: {
                return CustomerEphemeralKey(customerId: bundle.customerId,
                                            ephemeralKeySecret: bundle.ephemeralKeySecret)
            },
            setupIntentClientSecretProvider: {
                return try await APIService.shared.createSetupIntent()
            }
        )

        var config = CustomerSheet.Configuration()
        config.headerTextForSelectionScreen = "Payment methods"
        config.merchantDisplayName = "GreenEats"
        config.applePayEnabled = true
        config.returnURL = "greeneats://stripe-redirect"

        let sheet = CustomerSheet(configuration: config, customer: adapter)

        DispatchQueue.main.async {
            sheet.present(from: uiViewController) { result in
                isPresented = false
                onCompletion(result)
            }
        }
    }
}
