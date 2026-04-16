import Foundation
import PassKit
import StripeApplePay
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
final class CheckoutViewModel: NSObject, ObservableObject {

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
    private var bundleGeneration = 0
    private var sheetContinuation: CheckedContinuation<Void, Never>?
    private var applePayContext: STPApplePayContext?
    private var applePayContinuation: CheckedContinuation<Void, Never>?

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
            let dollars = max(0, Double(customTipText) ?? 0)
            return min(Int(dollars * 100), 50_000)
        }
    }

    // MARK: - Backend totals

    /// Refreshes the PaymentSheet bundle from the backend. Called on screen
    /// load and any time the tip selection changes so the totals match what
    /// Stripe will actually charge.
    func refreshBundle() async {
        bundleGeneration += 1
        let gen = bundleGeneration
        isLoadingBundle = true
        errorMessage = nil

        do {
            let tip = currentTipCents()
            let fresh = try await api.createPaymentSheet(tip: tip)
            guard gen == bundleGeneration else { return }
            bundle = fresh
        } catch {
            guard gen == bundleGeneration else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        isLoadingBundle = false
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
        // Apple Pay needs the merchant id from our entitlements; PaymentSheet
        // only shows the "Apple Pay" button when this is wired. Merchant id is
        // registered under the team's App Store Connect account.
        config.applePay = .init(
            merchantId: "merchant.com.koshereats.consumer",
            merchantCountryCode: "US",
        )
        // Saved card support needs allowsDelayedPaymentMethods = false; keeping
        // delayed methods off also matches the backend restriction to card only
        // (no ACH/bank debits).
        config.allowsDelayedPaymentMethods = false
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

    // MARK: - Apple Pay express

    /// One-tap Apple Pay from the checkout screen — skips the PaymentSheet
    /// entirely. STPApplePayContext handles the PaymentIntent confirmation
    /// once the user authorizes in the native Apple Pay sheet; we just hand
    /// it our existing client_secret via the delegate.
    func presentApplePayExpress(bundle: APIService.PaymentSheetBundle) async {
        isProcessing = true
        errorMessage = nil
        defer { isProcessing = false }

        if bundle.isStub {
            // Mirror the PaymentSheet dev-stub behaviour so reviewers without
            // real Stripe keys still get through the flow.
            paymentSucceeded = true
            return
        }

        StripeAPI.defaultPublishableKey = bundle.publishableKey

        let request = StripeAPI.paymentRequest(
            withMerchantIdentifier: "merchant.com.koshereats.consumer",
            country: "US",
            currency: "USD",
        )
        request.paymentSummaryItems = applePaySummaryItems(bundle: bundle)

        guard let context = STPApplePayContext(paymentRequest: request, delegate: self) else {
            errorMessage = "Apple Pay is not available on this device"
            return
        }

        guard let rootVC = Self.topViewController() else {
            errorMessage = "Could not present Apple Pay"
            return
        }

        self.applePayContext = context

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            self.applePayContinuation = continuation
            context.presentApplePay(on: rootVC)
        }
    }

    /// Itemised summary items for the Apple Pay sheet. The last row is the
    /// merchant total, per Apple's guidelines — the prior rows are a
    /// breakdown so the user sees what they're paying for.
    private func applePaySummaryItems(bundle: APIService.PaymentSheetBundle) -> [PKPaymentSummaryItem] {
        func item(_ label: String, _ cents: Int) -> PKPaymentSummaryItem {
            PKPaymentSummaryItem(
                label: label,
                amount: NSDecimalNumber(value: Double(cents) / 100),
            )
        }
        var items: [PKPaymentSummaryItem] = [
            item("Subtotal", bundle.subtotal),
            item("Tax", bundle.tax),
            item("Service fee", bundle.serviceFee),
            item("Delivery", bundle.deliveryFee),
        ]
        if bundle.tip > 0 {
            items.append(item("Driver tip", bundle.tip))
        }
        items.append(item("KosherEats", bundle.total))
        return items
    }

    // MARK: - Order creation

    func placeOrder(address: Address, bundle: APIService.PaymentSheetBundle) async -> Order? {
        guard !isProcessing else { return nil }
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

// MARK: - Apple Pay context delegate

extension CheckoutViewModel: ApplePayContextDelegate {
    /// Hand STPApplePayContext the client_secret from our existing
    /// /payments/create-payment-sheet response. The SDK then confirms the
    /// PaymentIntent with the tokenised Apple Pay card on our behalf.
    func applePayContext(
        _ context: STPApplePayContext,
        didCreatePaymentMethod paymentMethod: StripeAPI.PaymentMethod,
        paymentInformation: PKPayment
    ) async throws -> String {
        guard let secret = self.bundle?.paymentIntentSecret else {
            throw NSError(
                domain: "Checkout",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Missing payment intent"]
            )
        }
        return secret
    }

    func applePayContext(
        _ context: STPApplePayContext,
        didCompleteWith status: STPApplePayContext.PaymentStatus,
        error: Error?
    ) {
        Task { @MainActor in
            switch status {
            case .success:
                self.paymentSucceeded = true
            case .userCancellation:
                self.paymentSucceeded = false
            case .error:
                self.paymentSucceeded = false
                self.errorMessage = error?.localizedDescription ?? "Apple Pay failed"
            @unknown default:
                self.paymentSucceeded = false
            }
            self.applePayContext = nil
            self.applePayContinuation?.resume()
            self.applePayContinuation = nil
        }
    }
}
