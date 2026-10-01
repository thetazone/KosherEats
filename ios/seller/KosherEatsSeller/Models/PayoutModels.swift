import Foundation
import SwiftUI

// MARK: - Restaurant payouts (Stripe Connect) + Partner Agreement
//
// Wire models for the /seller/payouts/* and /seller/agreement endpoints.
// Every optional field uses decodeIfPresent, and every money field falls back
// to 0, so a backend that omits a key (or ships ahead of / behind this app)
// degrades to "$0.00" instead of failing the whole decode. Timestamps are kept
// as raw strings and parsed leniently for display via `PayoutDates` — a format
// quirk must never blank the payouts list.

// MARK: - Shared copy
//
// Fee / payout copy shown on several screens (Payouts, Partner Agreement,
// Dashboard delivery tile, Settings). Kept in one place so the numbers can't
// drift between screens — and must match Android + web word-for-word.

enum PayoutCopy {
    static let feeExplainer = "KosherEats keeps 10% of the food subtotal on orders delivered by our courier partners, and 5% plus card processing on pickup and self-delivered orders. You receive 100% of the sales tax collected on your orders — you're responsible for reporting and remitting it."

    static let selfDeliveryFees = "You keep the full delivery fee and tip; KosherEats keeps 5% + card processing."

    static let courierDeliveryFees = "Our courier partner delivers; KosherEats keeps 10% of the food subtotal."

    static let setupPrompt = "Set up payouts to get paid automatically for orders"
}

// MARK: - Lenient decoding helpers

private extension KeyedDecodingContainer {
    /// Decodes a value the backend may send as either a string or a number
    /// (ids, order numbers) and normalizes it to a String.
    func lenientString(forKey key: Key) -> String? {
        if let s = try? decodeIfPresent(String.self, forKey: key) { return s }
        if let i = try? decodeIfPresent(Int64.self, forKey: key) { return String(i) }
        return nil
    }

    /// Cents field: missing / null / wrong type → 0.
    func cents(forKey key: Key) -> Int {
        if let i = try? decodeIfPresent(Int.self, forKey: key) { return i }
        if let d = try? decodeIfPresent(Double.self, forKey: key) { return Int(d.rounded()) }
        return 0
    }
}

enum PayoutDates {
    private static let fractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// America/New_York — the zone the backend buckets payout periods in.
    static let restaurantTimeZone = TimeZone(identifier: "America/New_York") ?? .current

    static func parse(_ raw: String?) -> Date? {
        guard let raw, !raw.isEmpty else { return nil }
        return fractional.date(from: raw) ?? plain.date(from: raw)
    }

    private static let displayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.timeZone = restaurantTimeZone
        f.dateFormat = "MMM d, h:mm a"
        return f
    }()

    private static let shortFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.timeZone = restaurantTimeZone
        f.dateFormat = "MMM d"
        return f
    }()

    /// "Oct 1, 7:42 PM" (restaurant-local), or the raw string if unparseable.
    static func display(_ raw: String?) -> String {
        guard let raw else { return "—" }
        guard let date = parse(raw) else { return raw }
        return displayFormatter.string(from: date)
    }

    /// "Oct 1" (restaurant-local), or nil if unparseable.
    static func short(_ raw: String?) -> String? {
        parse(raw).map { shortFormatter.string(from: $0) }
    }
}

// MARK: - Payout account status

/// POST /seller/payouts/account and GET /seller/payouts/status.
struct SellerPayoutStatus: Decodable, Equatable {
    let payoutReady: Bool
    let connectId: String?
    let detailsSubmitted: Bool

    enum CodingKeys: String, CodingKey {
        case payoutReady = "payout_ready"
        case connectId = "connect_id"
        case detailsSubmitted = "details_submitted"
    }

    init(payoutReady: Bool, connectId: String?, detailsSubmitted: Bool) {
        self.payoutReady = payoutReady
        self.connectId = connectId
        self.detailsSubmitted = detailsSubmitted
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        payoutReady = (try? c.decodeIfPresent(Bool.self, forKey: .payoutReady)) ?? false
        connectId = try? c.decodeIfPresent(String.self, forKey: .connectId)
        detailsSubmitted = (try? c.decodeIfPresent(Bool.self, forKey: .detailsSubmitted)) ?? false
    }

    enum SetupState: Equatable {
        case ready
        case pendingVerification
        case notSetUp
    }

    /// Ready → Stripe can pay out. Pending verification → the seller finished
    /// Stripe's form but Stripe is still reviewing. Not set up → no account
    /// yet, or the hosted form was abandoned before submission.
    var setupState: SetupState {
        if payoutReady { return .ready }
        if detailsSubmitted { return .pendingVerification }
        return .notSetUp
    }
}

/// GET /seller/payouts/link
struct SellerPayoutLink: Decodable {
    let url: String
}

// MARK: - Payout lines (per-order ledger)

enum PayoutFulfillment: Equatable {
    case courierDelivery
    case pickup
    case selfDelivery
    case other(String)

    init(raw: String) {
        switch raw {
        case "courier_delivery": self = .courierDelivery
        case "pickup": self = .pickup
        case "self_delivery": self = .selfDelivery
        default: self = .other(raw)
        }
    }

    var label: String {
        switch self {
        case .courierDelivery: return "Delivered by courier"
        case .pickup: return "Pickup"
        case .selfDelivery: return "Self-delivery"
        case .other(let raw):
            return raw.replacingOccurrences(of: "_", with: " ").capitalized
        }
    }

    var icon: String {
        switch self {
        case .courierDelivery: return "car.fill"
        case .pickup: return "bag.fill"
        case .selfDelivery: return "figure.walk"
        case .other: return "shippingbox.fill"
        }
    }
}

enum PayoutLineStatus: Equatable {
    case awaitingAccount
    case pending
    case paid
    case failed
    case reversed
    case void
    case other(String)

    init(raw: String) {
        switch raw {
        case "awaiting_account": self = .awaitingAccount
        case "pending": self = .pending
        case "paid": self = .paid
        case "failed": self = .failed
        case "reversed": self = .reversed
        case "void": self = .void
        default: self = .other(raw)
        }
    }

    var label: String {
        switch self {
        case .awaitingAccount: return "Set up payouts to receive"
        case .pending: return "Processing"
        case .paid: return "Paid"
        case .failed: return "Failed – we're retrying"
        case .reversed: return "Reversed (refund)"
        case .void: return "Voided"
        case .other(let raw):
            return raw.replacingOccurrences(of: "_", with: " ").capitalized
        }
    }

    /// Semantic state colors only (rubric: success / warning / danger, brand
    /// orange reserved for the one actionable state).
    var color: Color {
        switch self {
        case .awaitingAccount: return .kePrimary
        case .pending: return .keWarning
        case .paid: return .keSuccess
        case .failed: return .keError
        case .reversed, .void, .other: return .keTextTertiary
        }
    }
}

struct PayoutLine: Decodable, Identifiable, Equatable {
    let id: String
    let orderId: String
    let orderNumber: String?
    let completedAt: String?
    let fulfillmentRaw: String
    let foodSubtotalCents: Int
    let salesTaxCents: Int
    let deliveryFeeCents: Int
    let tipCents: Int
    let keFeeCents: Int
    let processingFeeCents: Int
    let netCents: Int
    let statusRaw: String
    let transferId: String?
    let paidAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case orderId = "order_id"
        case orderNumber = "order_number"
        case completedAt = "completed_at"
        case fulfillmentRaw = "fulfillment"
        case foodSubtotalCents = "food_subtotal_cents"
        case salesTaxCents = "sales_tax_cents"
        case deliveryFeeCents = "delivery_fee_cents"
        case tipCents = "tip_cents"
        case keFeeCents = "ke_fee_cents"
        case processingFeeCents = "processing_fee_cents"
        case netCents = "net_cents"
        case statusRaw = "status"
        case transferId = "transfer_id"
        case paidAt = "paid_at"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let orderId = c.lenientString(forKey: .orderId) ?? ""
        self.orderId = orderId
        // id is the ledger row id; fall back to the order id so Identifiable
        // stays stable even if a backend build omits it.
        id = c.lenientString(forKey: .id) ?? orderId
        orderNumber = c.lenientString(forKey: .orderNumber)
        completedAt = try? c.decodeIfPresent(String.self, forKey: .completedAt)
        fulfillmentRaw = (try? c.decodeIfPresent(String.self, forKey: .fulfillmentRaw)) ?? ""
        foodSubtotalCents = c.cents(forKey: .foodSubtotalCents)
        salesTaxCents = c.cents(forKey: .salesTaxCents)
        deliveryFeeCents = c.cents(forKey: .deliveryFeeCents)
        tipCents = c.cents(forKey: .tipCents)
        keFeeCents = c.cents(forKey: .keFeeCents)
        processingFeeCents = c.cents(forKey: .processingFeeCents)
        netCents = c.cents(forKey: .netCents)
        statusRaw = (try? c.decodeIfPresent(String.self, forKey: .statusRaw)) ?? ""
        transferId = try? c.decodeIfPresent(String.self, forKey: .transferId)
        paidAt = try? c.decodeIfPresent(String.self, forKey: .paidAt)
    }

    var fulfillment: PayoutFulfillment { PayoutFulfillment(raw: fulfillmentRaw) }
    var status: PayoutLineStatus { PayoutLineStatus(raw: statusRaw) }

    /// "Order #1042" when the backend sends a human order number, otherwise
    /// the same 8-char id prefix the Orders tab uses.
    var orderLabel: String {
        if let n = orderNumber, !n.isEmpty { return "Order #\(n)" }
        return "Order #\(String(orderId.prefix(8)))"
    }
}

/// GET /seller/payouts?limit=&cursor=
struct PayoutLinesPage: Decodable {
    let lines: [PayoutLine]
    let nextCursor: String?

    enum CodingKeys: String, CodingKey {
        case lines
        case nextCursor = "next_cursor"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        lines = (try c.decodeIfPresent([PayoutLine].self, forKey: .lines)) ?? []
        let cursor = try? c.decodeIfPresent(String.self, forKey: .nextCursor)
        nextCursor = (cursor?.isEmpty ?? true) ? nil : cursor
    }
}

// MARK: - Period summary

/// GET /seller/payouts/summary?from=YYYY-MM-DD&to=YYYY-MM-DD
struct PayoutSummary: Decodable, Equatable {
    let from: String
    let to: String
    let orders: Int
    let foodSubtotalCents: Int
    let salesTaxCents: Int
    let deliveryFeeCents: Int
    let tipCents: Int
    let keFeeCents: Int
    let processingFeeCents: Int
    let netCents: Int
    let paidCents: Int
    let pendingCents: Int

    enum CodingKeys: String, CodingKey {
        case from, to, orders
        case foodSubtotalCents = "food_subtotal_cents"
        case salesTaxCents = "sales_tax_cents"
        case deliveryFeeCents = "delivery_fee_cents"
        case tipCents = "tip_cents"
        case keFeeCents = "ke_fee_cents"
        case processingFeeCents = "processing_fee_cents"
        case netCents = "net_cents"
        case paidCents = "paid_cents"
        case pendingCents = "pending_cents"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        from = (try? c.decodeIfPresent(String.self, forKey: .from)) ?? ""
        to = (try? c.decodeIfPresent(String.self, forKey: .to)) ?? ""
        orders = (try? c.decodeIfPresent(Int.self, forKey: .orders)) ?? 0
        foodSubtotalCents = c.cents(forKey: .foodSubtotalCents)
        salesTaxCents = c.cents(forKey: .salesTaxCents)
        deliveryFeeCents = c.cents(forKey: .deliveryFeeCents)
        tipCents = c.cents(forKey: .tipCents)
        keFeeCents = c.cents(forKey: .keFeeCents)
        processingFeeCents = c.cents(forKey: .processingFeeCents)
        netCents = c.cents(forKey: .netCents)
        paidCents = c.cents(forKey: .paidCents)
        pendingCents = c.cents(forKey: .pendingCents)
    }
}

/// The three summary windows on the Payouts screen, computed in
/// America/New_York so they match the backend's bucketing regardless of the
/// device's time zone. Both `from` and `to` are inclusive calendar dates and
/// must stay identical to Android/web so totals match across apps:
///   - This week:  Monday of the current week → today
///   - Last week:  the previous Monday → Sunday
///   - This month: the 1st of the current month → today
enum PayoutPeriod: String, CaseIterable, Identifiable {
    case thisWeek
    case lastWeek
    case thisMonth

    var id: String { rawValue }

    var title: String {
        switch self {
        case .thisWeek: return "This week"
        case .lastWeek: return "Last week"
        case .thisMonth: return "This month"
        }
    }

    private static var calendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = PayoutDates.restaurantTimeZone
        cal.firstWeekday = 2 // Monday
        cal.locale = Locale(identifier: "en_US_POSIX")
        return cal
    }

    private static let wireFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = PayoutDates.restaurantTimeZone
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private static let rangeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.timeZone = PayoutDates.restaurantTimeZone
        f.dateFormat = "MMM d"
        return f
    }()

    /// Inclusive [start, end] calendar days for this period.
    func dateRange(now: Date = Date()) -> (start: Date, end: Date) {
        let cal = Self.calendar
        let today = cal.startOfDay(for: now)
        let thisWeekStart = cal.dateInterval(of: .weekOfYear, for: now)?.start ?? today
        switch self {
        case .thisWeek:
            return (thisWeekStart, today)
        case .lastWeek:
            let start = cal.date(byAdding: .day, value: -7, to: thisWeekStart) ?? thisWeekStart
            let end = cal.date(byAdding: .day, value: 6, to: start) ?? start
            return (start, end)
        case .thisMonth:
            let start = cal.dateInterval(of: .month, for: now)?.start ?? today
            return (start, today)
        }
    }

    /// ("2026-09-28", "2026-10-01") — the wire values for ?from=&to=.
    func wireRange(now: Date = Date()) -> (from: String, to: String) {
        let r = dateRange(now: now)
        return (Self.wireFormatter.string(from: r.start), Self.wireFormatter.string(from: r.end))
    }

    /// "Sep 28 – Oct 1" for the summary card subtitle ("Oct 1" when the
    /// range is a single day, e.g. "This month" on the 1st).
    func displayRange(now: Date = Date()) -> String {
        let r = dateRange(now: now)
        let start = Self.rangeFormatter.string(from: r.start)
        let end = Self.rangeFormatter.string(from: r.end)
        return start == end ? start : "\(start) – \(end)"
    }
}

// MARK: - Restaurant Partner Agreement

/// GET /seller/agreement and POST /seller/agreement/accept.
struct SellerAgreement: Decodable, Equatable {
    let required: Bool
    let accepted: Bool
    let currentVersion: String
    let acceptedVersion: String?
    let acceptedAt: String?
    let termsUrl: String

    enum CodingKeys: String, CodingKey {
        case required, accepted
        case currentVersion = "current_version"
        case acceptedVersion = "accepted_version"
        case acceptedAt = "accepted_at"
        case termsUrl = "terms_url"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        required = (try? c.decodeIfPresent(Bool.self, forKey: .required)) ?? false
        accepted = (try? c.decodeIfPresent(Bool.self, forKey: .accepted)) ?? false
        currentVersion = c.lenientString(forKey: .currentVersion) ?? ""
        acceptedVersion = c.lenientString(forKey: .acceptedVersion)
        acceptedAt = try? c.decodeIfPresent(String.self, forKey: .acceptedAt)
        termsUrl = (try? c.decodeIfPresent(String.self, forKey: .termsUrl)) ?? ""
    }

    /// True when the seller must accept before using the app: the backend
    /// says the agreement applies to this restaurant AND the current version
    /// hasn't been accepted. Grandfathered restaurants get required=false.
    var needsAcceptance: Bool {
        guard required else { return false }
        if !accepted { return true }
        if let v = acceptedVersion, !currentVersion.isEmpty, v != currentVersion { return true }
        return false
    }

    /// Falls back to the public terms page if the backend omits terms_url.
    var termsURL: URL {
        URL(string: termsUrl).flatMap { $0.scheme == nil ? nil : $0 } ?? LegalURLs.termsOfService
    }
}
