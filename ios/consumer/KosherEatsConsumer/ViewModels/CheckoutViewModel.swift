import Foundation
import StripePaymentSheet
import SwiftUI
import UIKit

/// CheckoutViewModel owns the state of the checkout screen:
///   - selected address
///   - tip choice
///   - fetched PaymentSheetBundle (totals + Stripe credentials)
///   - Stripe PaymentSheet lifecycle
///
/// It is intentionally dumb about the order creation step — the view wires
/// `placeOrder` in after PaymentSheet completes successfully.
@MainActor
final class CheckoutViewModel: ObservableObject {

    // MARK: - Tip selection

    enum TipChoice: Equatable, Hashable {
        case none
        case percent(Double)
        case custom

        static let presets: [TipChoice] = [.none, .percent(0.15), .percent(0.18), .percent(0.20), .custom]

        func label(for subtotal: Int) -> String {
            switch self {
            case .none: return "None"
            case .percent(let p):
                let cents = Int(Double(subtotal) * p)
                return "\(Int(p * 100))%\n$\(String(format: "%.2f", Double(cents) / 100))"
            case .custom: return "Custom"
            }
        }
    }

    @Published var addresses: [Address] = []
    @Published var selectedAddress: Address?
    @Published var tipSelection: TipChoice = .percent(0.18)
    @Published var customTipText: String = ""

    /// nil = ASAP. Any future Date triggers backend's 'scheduled' status.
    @Published var scheduledFor: Date?

    @Published var bundle: APIService.PaymentSheetBundle?
    @Published var isLoadingBundle: Bool = false
    @Published var isProcessing: Bool = false
    @Published var errorMessage: String?
    @Published var paymentSucceeded: Bool = false

    private let api = APIService.shared
    private var activeSheet: PaymentSheet?
    private var sheetContinuation: CheckedContinuation<Void, Never>?

    // MARK: - Addresses

    func loadAddresses() async {
        do {
            let list = try await api.listAddresses()
            addresses = list
            selectedAddress = list.first(where: { $0.isDefault }) ?? list.first
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Tip

    func selectTip(_ choice: TipChoice) {
        tipSelection = choice
        if choice != .custom { customTipText = "" }
    }

    /// Converts the current tip selection into a cents value using the
    /// subtotal from the most recent bundle (or 0 if we haven't loaded yet).
    private func currentTipCents() -> Int {
        let subtotal = bundle?.subtotal ?? 0
        switch tipSelection {
        case .none: return 0
        case .percent(let p): return Int(Double(subtotal) * p)
        case .custom:
            let dollars = Double(customTipText) ?? 0
            return Int(dollars * 100)
        }
    }

    // MARK: - Backend totals

    /// Refreshes the PaymentSheet bundle from the backend. Called on screen
    /// load and any time the tip selection changes so the totals match what
    /// Stripe will actually charge.
    func refreshBundle() async {
        isLoadingBundle = true
        errorMessage = nil
        defer { isLoadingBundle = false }

        do {
            let tip = currentTipCents()
            let fresh = try await api.createPaymentSheet(tip: tip)
            bundle = fresh
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    // MARK: - Stripe PaymentSheet

    /// Presents Stripe's hosted PaymentSheet UI and awaits the result. In
    /// dev stub mode (no Stripe key configured server-side) this short-
    /// circuits and marks the payment as succeeded immediately so the rest
    /// of the flow stays testable locally.
    func presentAndChargePaymentSheet(bundle: APIService.PaymentSheetBundle) async {
        isProcessing = true
        errorMessage = nil
        defer { isProcessing = false }

        if bundle.isStub {
            paymentSucceeded = true
            return
        }

        StripeAPI.defaultPublishableKey = bundle.publishableKey

        var config = PaymentSheet.Configuration()
        config.merchantDisplayName = "KosherEats"
        config.customer = .init(id: bundle.customerId, ephemeralKeySecret: bundle.ephemeralKeySecret)
        config.allowsDelayedPaymentMethods = true
        config.returnURL = "koshereats://stripe-redirect"

        let sheet = PaymentSheet(paymentIntentClientSecret: bundle.paymentIntentSecret, configuration: config)

        guard let rootVC = Self.topViewController() else {
            errorMessage = "Could not present payment sheet"
            return
        }

        // Store sheet so it isn't deallocated before the callback fires.
        self.activeSheet = sheet

        // Use a continuation-free approach: store the result in a published
        // property and let the caller poll or observe.
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            self.sheetContinuation = continuation
            sheet.present(from: rootVC) { [weak self] result in
                guard let self else { return }
                Task { @MainActor in
                    switch result {
                    case .completed:
                        self.paymentSucceeded = true
                    case .canceled:
                        self.paymentSucceeded = false
                    case .failed(let error):
                        self.paymentSucceeded = false
                        self.errorMessage = error.localizedDescription
                    }
                    self.activeSheet = nil
                    self.sheetContinuation?.resume()
                    self.sheetContinuation = nil
                }
            }
        }
    }

    // MARK: - Order creation

    func placeOrder(address: Address, bundle: APIService.PaymentSheetBundle) async -> Order? {
        isProcessing = true
        defer { isProcessing = false }

        // In stub mode we don't have a real payment intent id; the backend
        // tolerates an empty string.
        let paymentIntentId = bundle.isStub ? "stub_intent" : extractIntentId(from: bundle.paymentIntentSecret)

        do {
            return try await api.createOrder(
                deliveryAddress: address.formatted,
                lat: address.lat,
                lng: address.lng,
                paymentIntentId: paymentIntentId,
                tip: bundle.tip,
                scheduledFor: scheduledFor,
            )
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    /// PaymentIntent client secrets are `pi_xxxxxx_secret_yyyy`. We only
    /// want the `pi_xxxxxx` portion for our DB.
    private func extractIntentId(from clientSecret: String) -> String {
        clientSecret.components(separatedBy: "_secret_").first ?? clientSecret
    }

    // MARK: - UIKit bridging

    /// Finds the topmost presented view controller so PaymentSheet has
    /// somewhere to attach itself to. SwiftUI apps don't expose this
    /// directly, so we walk the key window's scene.
    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes
        let windowScene = scenes.first { $0.activationState == .foregroundActive } as? UIWindowScene
        let root = windowScene?.windows.first(where: \.isKeyWindow)?.rootViewController
        var top = root
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}
