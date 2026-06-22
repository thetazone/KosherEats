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

    /// Apple Pay merchant identifier from the entitlements file.
    private static let merchantIdentifier = "merchant.com.koshereats.consumer"
    /// URL scheme the Stripe SDK redirects back to after 3DS / bank auth.
    private static let stripeReturnURL = "koshereats://stripe-redirect"

    /// Maximum custom tip in cents ($500). Matches the backend cap in
    /// orders.go / payments.go (tip <= subtotal).
    static let maxTipCents = 50_000

    // MARK: - Tip selection

    enum TipChoice: Equatable, Hashable {
        case none
        /// Tip percentage stored as basis points (e.g. 1500 = 15%).
        case percent(Int)
        case custom

        static let presets: [TipChoice] = [.none, .percent(1500), .percent(1800), .percent(2000), .custom]

        func label(for subtotal: Int) -> String {
            switch self {
            case .none: return "None"
            case .percent(let bps):
                let cents = (subtotal * bps) / 10_000
                return "\(bps / 100)%\n$\(String(format: "%.2f", Double(cents) / 100))"
            case .custom: return "Custom"
            }
        }
    }

    @Published var addresses: [Address] = []
    @Published var selectedAddress: Address?
    @Published var tipSelection: TipChoice = .percent(1800)
    @Published var customTipText: String = ""

    /// nil = ASAP. Any future Date triggers backend's 'scheduled' status.
    @Published var scheduledFor: Date?

    /// "delivery" (default) or "pickup". When "pickup", the address card +
    /// tip selector hide and the bundle excludes both the delivery fee and
    /// any tip (the backend zeros them server-side too).
    @Published var fulfillmentType: String = "delivery" {
        didSet {
            if fulfillmentType == "pickup" {
                tipSelection = .none
                customTipText = ""
            } else if oldValue == "pickup", tipSelection == .none {
                // Returning to delivery FROM pickup (which zeroed the tip): restore
                // the default so a delivery -> pickup -> delivery toggle doesn't
                // silently charge $0. Gated on oldValue == "pickup" so a deliberate
                // "no tip" chosen while already in delivery is never overridden.
                tipSelection = .percent(1800)
            }
            Task { @MainActor [weak self] in
                await self?.refreshBundle()
            }
        }
    }

    @Published var bundle: APIService.PaymentSheetBundle?
    @Published var isLoadingBundle: Bool = false
    @Published var isProcessing: Bool = false
    @Published var errorMessage: String?
    @Published var paymentSucceeded: Bool = false
    @Published var orderCreationFailed: Bool = false

    var appliedDealId: String?

    private let api = APIService.shared
    private var activeSheet: PaymentSheet?
    private var bundleGeneration = 0
    private var sheetContinuation: CheckedContinuation<Void, Error>?
    private var applePayContext: STPApplePayContext?
    private var applePayContinuation: CheckedContinuation<Void, Error>?
    /// The exact bundle whose Apple Pay sheet is currently presented. The
    /// delegate confirms THIS PaymentIntent, not `self.bundle`, which a
    /// concurrent refreshBundle() can swap out from under the live sheet
    /// (charging PI B while placeOrder records PI A).
    private var applePayBundle: APIService.PaymentSheetBundle?

    deinit {
        // Resume any pending continuations so their Tasks don't leak.
        // The VM can be deallocated while PaymentSheet or Apple Pay is
        // still presented (e.g. parent view dismissed); an un-resumed
        // CheckedContinuation is a fatal error in debug builds.
        sheetContinuation?.resume(throwing: CancellationError())
        sheetContinuation = nil
        applePayContinuation?.resume(throwing: CancellationError())
        applePayContinuation = nil
    }

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

    /// Validates and stores a custom tip value. Negative values are
    /// clamped to "" so the user can't enter a negative tip.
    func updateCustomTip(_ text: String) {
        if text.isEmpty { customTipText = text; return }
        let filtered = text.replacingOccurrences(of: ",", with: ".").filter { $0.isNumber || $0 == "." }
        if filtered.components(separatedBy: ".").count > 2 { return }
        if let value = Double(filtered), value > Double(Self.maxTipCents) / 100.0 {
            errorMessage = "Maximum tip is $\(Self.maxTipCents / 100)"
            return
        }
        errorMessage = nil
        customTipText = filtered
    }

    /// Converts the current tip selection into a cents value using the
    /// subtotal from the most recent bundle (or 0 if we haven't loaded yet).
    private func currentTipCents() -> Int {
        let subtotal = bundle?.subtotal ?? 0
        switch tipSelection {
        case .none: return 0
        case .percent(let bps): return (subtotal * bps) / 10_000
        case .custom:
            let dollars = max(0, Double(customTipText) ?? 0)
            // Round, don't truncate: binary float makes many exact-cent inputs
            // land just below the integer (4.10 * 100 == 409.9999…) and Int()
            // truncates toward zero, so $4.10 would become 409¢. Rounding keeps
            // the charged tip equal to what the user typed.
            return min(Int((dollars * 100).rounded()), Self.maxTipCents)
        }
    }

    // MARK: - Backend totals

    /// Refreshes the PaymentSheet bundle from the backend. Called on screen
    /// load and any time the tip selection changes so the totals match what
    /// Stripe will actually charge.
    func refreshBundle() async {
        // Never refresh while a charge is in flight. A refresh creates a brand-
        // new PaymentIntent server-side and swaps `self.bundle`; if that lands
        // while the PaymentSheet or Apple Pay sheet is up, the live sheet ends
        // up confirming a different PaymentIntent than the one placeOrder will
        // record (charge / order mismatch). The totals are locked once the user
        // taps pay, so a stale-tip refresh has nothing useful to do here.
        guard !isProcessing else { return }

        // Snapshot whether we're about to compute a percent tip against a
        // not-yet-loaded bundle (subtotal == 0). The backend only honours an
        // absolute cents tip — it never recomputes a percentage — so a percent
        // tip evaluated here against a nil bundle bakes tip=0 into the
        // PaymentIntent. On the first load we re-refresh once the real subtotal
        // is known so the default tip isn't silently dropped.
        let neededReRefresh: Bool
        if case .percent = tipSelection, (bundle?.subtotal ?? 0) == 0 {
            neededReRefresh = true
        } else {
            neededReRefresh = false
        }

        bundleGeneration += 1
        let gen = bundleGeneration
        isLoadingBundle = true
        errorMessage = nil

        defer { if gen == bundleGeneration { isLoadingBundle = false } }

        do {
            let tip = currentTipCents()
            let fresh = try await api.createPaymentSheet(tip: tip, fulfillmentType: fulfillmentType, appliedDealId: appliedDealId)
            guard gen == bundleGeneration, !Task.isCancelled else { return }
            bundle = fresh

            // The percent tip was computed against a zero subtotal but we now
            // have a real one — recompute and refresh once so the PaymentIntent
            // reflects the intended tip. Re-entry is bounded: the second pass
            // sees a non-zero subtotal and won't loop.
            if neededReRefresh, fresh.tip == 0, fresh.subtotal > 0, currentTipCents() > 0 {
                await refreshBundle()
            }
        } catch {
            guard gen == bundleGeneration, !Task.isCancelled else { return }
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    // MARK: - Stripe PaymentSheet

    /// Presents Stripe's hosted PaymentSheet UI and awaits the result. In
    /// dev stub mode (no Stripe key configured server-side) this short-
    /// circuits and marks the payment as succeeded immediately so the rest
    /// of the flow stays testable locally.
    /// Validates the chosen scheduled time BEFORE any card is charged. A
    /// just-passed scheduled time must stop the flow here — once the card is
    /// charged, blocking order creation leaves an unrecoverable
    /// 'payment received, no order' state. Returns false (and sets
    /// errorMessage) when the time has already passed.
    private func scheduledTimeIsValid() -> Bool {
        if let scheduled = scheduledFor, scheduled < Date() {
            errorMessage = "Your scheduled time has passed. Please select a new time."
            return false
        }
        return true
    }

    func presentAndChargePaymentSheet(bundle: APIService.PaymentSheetBundle) async {
        paymentSucceeded = false
        isProcessing = true
        errorMessage = nil
        defer { isProcessing = false }

        // Pre-charge validation: a stale scheduled time must abort before the
        // card is charged, never after (see placeOrder).
        guard scheduledTimeIsValid() else { return }

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
            merchantId: Self.merchantIdentifier,
            merchantCountryCode: "US",
        )
        // Saved card support needs allowsDelayedPaymentMethods = false; keeping
        // delayed methods off also matches the backend restriction to card only
        // (no ACH/bank debits).
        config.allowsDelayedPaymentMethods = false
        config.returnURL = Self.stripeReturnURL

        let sheet = PaymentSheet(paymentIntentClientSecret: bundle.paymentIntentSecret, configuration: config)

        guard let rootVC = Self.topViewController() else {
            errorMessage = "Could not present payment sheet"
            return
        }

        // Store sheet so it isn't deallocated before the callback fires.
        self.activeSheet = sheet

        // Use a continuation-free approach: store the result in a published
        // property and let the caller poll or observe.
        try? await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                self.sheetContinuation = continuation
                sheet.present(from: rootVC) { [weak self] result in
                    guard let self else {
                        continuation.resume(throwing: CancellationError())
                        return
                    }
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
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.sheetContinuation?.resume(throwing: CancellationError())
                self?.sheetContinuation = nil
            }
        }
    }

    // MARK: - Apple Pay express

    /// One-tap Apple Pay from the checkout screen — skips the PaymentSheet
    /// entirely. STPApplePayContext handles the PaymentIntent confirmation
    /// once the user authorizes in the native Apple Pay sheet; we just hand
    /// it our existing client_secret via the delegate.
    func presentApplePayExpress(bundle: APIService.PaymentSheetBundle) async {
        paymentSucceeded = false
        isProcessing = true
        errorMessage = nil
        defer { isProcessing = false }

        // Pre-charge validation: a stale scheduled time must abort before the
        // card is charged, never after (see placeOrder).
        guard scheduledTimeIsValid() else { return }

        if bundle.isStub {
            // Mirror the PaymentSheet dev-stub behaviour so reviewers without
            // real Stripe keys still get through the flow.
            paymentSucceeded = true
            return
        }

        StripeAPI.defaultPublishableKey = bundle.publishableKey

        let request = StripeAPI.paymentRequest(
            withMerchantIdentifier: Self.merchantIdentifier,
            country: "US",
            currency: "USD",
        )
        request.paymentSummaryItems = applePaySummaryItems(bundle: bundle)

        guard let context = STPApplePayContext(paymentRequest: request, delegate: self) else {
            errorMessage = "Apple Pay is not available on this device"
            return
        }

        self.applePayContext = context
        // Pin the exact bundle being charged so the delegate confirms THIS
        // PaymentIntent regardless of any later refreshBundle() that replaces
        // self.bundle.
        self.applePayBundle = bundle

        try? await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                self.applePayContinuation = continuation
                context.presentApplePay(completion: nil)
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.applePayContinuation?.resume(throwing: CancellationError())
                self?.applePayContinuation = nil
            }
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
        ]
        if let discount = bundle.discount, discount > 0 {
            items.append(PKPaymentSummaryItem(
                label: "Deal discount",
                amount: NSDecimalNumber(value: -Double(discount) / 100)
            ))
        }
        items.append(contentsOf: [
            item("Tax", bundle.tax),
            item("Service fee", bundle.serviceFee),
            item("Delivery", bundle.deliveryFee),
        ])
        if bundle.tip > 0 {
            items.append(item("Driver tip", bundle.tip))
        }
        items.append(item("KosherEats", bundle.total))
        return items
    }

    // MARK: - Order creation

    func placeOrder(address: Address?, bundle: APIService.PaymentSheetBundle) async -> Order? {
        guard !isProcessing else { return nil }

        // NOTE: the scheduled-time check lives in presentAndChargePaymentSheet /
        // presentApplePayExpress, BEFORE the card is charged. We must NOT block
        // here post-charge — that would strand a paid customer with no order.
        // If the scheduled time has just passed the backend falls back to ASAP
        // (status 'pending'), so we always send the request and let the server
        // decide.

        isProcessing = true
        defer { isProcessing = false }

        // Pickup orders don't carry an address; pass empty strings + (0, 0)
        // and let the backend's validation skip the delivery-address check
        // because fulfillment_type='pickup'.
        let addressString = address?.formatted ?? ""
        let lat = address?.lat ?? 0
        let lng = address?.lng ?? 0

        do {
            // In stub mode we don't have a real payment intent id; the backend
            // tolerates an empty string.
            let paymentIntentId = bundle.isStub ? "stub_intent" : try extractIntentId(from: bundle.paymentIntentSecret)
            // Retry createOrder up to 3 times — the card is already charged,
            // so dropping the order on a transient network failure is worse
            // than a duplicate-order guard on the backend (idempotent via
            // payment_intent_id unique constraint).
            var lastError: Error?
            for attempt in 0..<3 {
                do {
                    return try await api.createOrder(
                        deliveryAddress: addressString,
                        lat: lat,
                        lng: lng,
                        paymentIntentId: paymentIntentId,
                        tip: bundle.tip,
                        scheduledFor: scheduledFor,
                        fulfillmentType: fulfillmentType,
                        appliedDealId: appliedDealId
                    )
                } catch let APIError.httpError(code, _) where code == 409 {
                    // Duplicate payment_intent_id — the order was already created
                    // on a previous attempt whose response was lost. Fetch it.
                    if let orders = try? await api.listOrders(),
                       let existing = orders.first(where: { $0.stripePaymentID == paymentIntentId }) {
                        return existing
                    }
                    lastError = NSError(
                        domain: "Checkout",
                        code: -3,
                        userInfo: [
                            NSLocalizedDescriptionKey: "Payment was received, but we couldn't recover the created order. Please retry or contact support."
                        ]
                    )
                } catch {
                    lastError = error
                    if attempt < 2 {
                        let delaySec = min(pow(2.0, Double(attempt)), 30)
                        try? await Task.sleep(nanoseconds: UInt64(delaySec) * 1_000_000_000)
                    }
                }
            }
            paymentSucceeded = false
            orderCreationFailed = true
            errorMessage = lastError?.localizedDescription ?? "Order creation failed"
            return nil
        } catch {
            paymentSucceeded = false
            errorMessage = error.localizedDescription
            return nil
        }
    }

    /// PaymentIntent client secrets are `pi_xxxxxx_secret_yyyy`. We only
    /// want the `pi_xxxxxx` portion for our DB.
    private func extractIntentId(from clientSecret: String) throws -> String {
        guard clientSecret.hasPrefix("pi_"), clientSecret.contains("_secret_") else {
            throw NSError(
                domain: "Checkout",
                code: -2,
                userInfo: [NSLocalizedDescriptionKey: "Malformed payment intent secret — expected pi_<id>_secret_<key> format"]
            )
        }
        let parts = clientSecret.components(separatedBy: "_secret_")
        guard parts.count == 2, !parts[0].isEmpty else {
            throw NSError(
                domain: "Checkout",
                code: -2,
                userInfo: [NSLocalizedDescriptionKey: "Malformed payment intent secret — cannot extract intent ID"]
            )
        }
        return parts[0]
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
        // Use the bundle pinned at present time, NOT self.bundle — a concurrent
        // refreshBundle() may have replaced self.bundle with a different
        // PaymentIntent, which would charge a PI that placeOrder never records.
        guard let secret = self.applePayBundle?.paymentIntentSecret else {
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
            self.applePayBundle = nil
            self.applePayContinuation?.resume()
            self.applePayContinuation = nil
        }
    }
}
