import Foundation

enum APIError: LocalizedError {
    case invalidURL
    case invalidResponse
    case httpError(Int, String)
    case decodingError(Error)
    case networkError(Error)
    case unauthorized

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .invalidResponse: return "Invalid response from server"
        case .httpError(let code, let msg): return "Error \(code): \(msg)"
        case .decodingError(let err): return "Decoding error: \(err.localizedDescription)"
        case .networkError(let err): return err.localizedDescription
        case .unauthorized: return "Please log in again"
        }
    }

    /// True when the backend rejected a transaction because the consumer hasn't
    /// finished phone + email verification (403 {"error":"verification_required"}).
    /// Clients use this to route into the verification flow instead of showing a
    /// raw error.
    var isVerificationRequired: Bool {
        if case let .httpError(code, msg) = self { return code == 403 && msg == "verification_required" }
        return false
    }
}

@MainActor
class APIService: ObservableObject {
    static let shared = APIService()

    // ISO8601DateFormatter is not Sendable, so wrap each instance in a
    // lock-protected box to avoid a data-race if the custom dateDecodingStrategy
    // closure is ever invoked off the main actor.
    private static let iso8601Fractional: LockedFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return LockedFormatter(formatter)
    }()

    private static let iso8601Plain: LockedFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return LockedFormatter(formatter)
    }()

    /// Thread-safe wrapper around ISO8601DateFormatter (which is not Sendable).
    private final class LockedFormatter: @unchecked Sendable {
        private let formatter: ISO8601DateFormatter
        private let lock = NSLock()

        init(_ formatter: ISO8601DateFormatter) {
            self.formatter = formatter
        }

        func date(from string: String) -> Date? {
            lock.lock()
            defer { lock.unlock() }
            return formatter.date(from: string)
        }

        func string(from date: Date) -> String {
            lock.lock()
            defer { lock.unlock() }
            return formatter.string(from: date)
        }
    }

    // URL configuration:
    // Both DEBUG and RELEASE point at the production Fly.io backend. To test
    // against a local backend, manually change the DEBUG value below to
    // "http://localhost:8080/api/v1" (or whatever your local stack uses).
    //
    // On Android the equivalent lives in build.gradle.kts as
    // BuildConfig.BASE_URL — one per build type (debug / release).
    //
    // TODO: Read from an environment variable or Xcode scheme so switching
    // doesn't require a code change (e.g. KOSHEREATS_API_URL).
    #if DEBUG
    private var baseURL = ProcessInfo.processInfo.environment["KOSHEREATS_API_URL"]
        ?? "https://koshereats-api.fly.dev/api/v1"
    #else
    private var baseURL = "https://koshereats-api.fly.dev/api/v1"
    #endif

    // In-memory copies are the session source of truth; the keychain is the
    // durable backup. Keychain writes can silently fail on unsigned/dev builds
    // (SecItemAdd -> errSecMissingEntitlement), and the setter below discards
    // that Bool result — without an in-memory copy that would drop the session
    // and 401 every authenticated call right after a successful login. The
    // seller app already keeps token state in memory for the same reason.
    private var cachedToken: String?
    private var cachedRefreshToken: String?

    private var token: String? {
        get { cachedToken ?? KeychainHelper.load(forKey: "auth_token") }
        set {
            cachedToken = newValue
            if let v = newValue { _ = KeychainHelper.save(v, forKey: "auth_token") }
            else { KeychainHelper.delete(forKey: "auth_token") }
        }
    }

    private var refreshToken: String? {
        get { cachedRefreshToken ?? KeychainHelper.load(forKey: "refresh_token") }
        set {
            cachedRefreshToken = newValue
            if let v = newValue { _ = KeychainHelper.save(v, forKey: "refresh_token") }
            else { KeychainHelper.delete(forKey: "refresh_token") }
        }
    }

    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let value = try container.decode(String.self)

            if let date = APIService.iso8601Fractional.date(from: value) {
                return date
            }

            if let date = APIService.iso8601Plain.date(from: value) {
                return date
            }

            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Invalid ISO8601 date: \(value)"
            )
        }
        return d
    }()

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    // MARK: - Configuration

    func setBaseURL(_ url: String) {
        baseURL = url
    }

    func setToken(_ token: String, refresh: String) {
        self.token = token
        self.refreshToken = refresh
    }

    func clearToken() {
        self.token = nil
        self.refreshToken = nil
    }

    var isAuthenticated: Bool {
        token != nil
    }

    // MARK: - Core Request

    private func request<T: Decodable>(
        method: String,
        path: String,
        body: (any Encodable)? = nil,
        authenticated: Bool = false,
        allowAuthRefresh: Bool = true
    ) async throws -> T {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw APIError.invalidURL
        }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 15
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if authenticated, let token = token {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body = body {
            req.httpBody = try encoder.encode(body)
        }

        // URLSession.shared is used intentionally — APIService is an app-scoped
        // singleton so the session lives for the process lifetime and never needs
        // explicit invalidation.
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw APIError.networkError(error)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }

        if httpResponse.statusCode == 401 {
            // Try token refresh
            guard path != "/auth/refresh" else {
                logout()
                throw APIError.unauthorized
            }
            if allowAuthRefresh, authenticated, refreshToken != nil {
                do {
                    let refreshed = try await performTokenRefresh()
                    if refreshed {
                        return try await request(
                            method: method,
                            path: path,
                            body: body,
                            authenticated: authenticated,
                            allowAuthRefresh: false
                        )
                    }
                } catch APIError.unauthorized {
                    // Refresh token itself is dead — fall through to throw
                } catch {
                    // Transient error (5xx, network) — don't logout, propagate
                    throw error
                }
            }
            throw APIError.unauthorized
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorMsg = (try? decoder.decode(APIErrorResponse.self, from: data))?.error ?? "Unknown error"
            throw APIError.httpError(httpResponse.statusCode, errorMsg)
        }

        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decodingError(error)
        }
    }

    private func requestVoid(
        method: String,
        path: String,
        body: (any Encodable)? = nil,
        authenticated: Bool = false,
        allowAuthRefresh: Bool = true
    ) async throws {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw APIError.invalidURL
        }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 15
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if authenticated, let token = token {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body = body {
            req.httpBody = try encoder.encode(body)
        }

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw APIError.networkError(error)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }

        // Match request<T>'s refresh behavior — otherwise a 401 on any void
        // endpoint (DELETE /user/account, device registration, etc.) kicks
        // the user back to login with a perfectly refreshable token still
        // sitting in UserDefaults.
        if httpResponse.statusCode == 401 {
            guard path != "/auth/refresh" else {
                logout()
                throw APIError.unauthorized
            }
            if allowAuthRefresh, authenticated, refreshToken != nil {
                do {
                    let refreshed = try await performTokenRefresh()
                    if refreshed {
                        try await requestVoid(
                            method: method,
                            path: path,
                            body: body,
                            authenticated: authenticated,
                            allowAuthRefresh: false
                        )
                        return
                    }
                } catch APIError.unauthorized {
                    // Refresh token dead — fall through
                } catch {
                    throw error
                }
            }
            throw APIError.unauthorized
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorMsg = (try? decoder.decode(APIErrorResponse.self, from: data))?.error ?? "Unknown error"
            throw APIError.httpError(httpResponse.statusCode, errorMsg)
        }
    }

    private var refreshTask: Task<Bool, Error>?

    func performTokenRefresh() async throws -> Bool {
        // Coalesce concurrent refresh attempts into a single in-flight request.
        // Since this class is @MainActor, the check-then-set below is safe —
        // no suspension point between reading refreshTask and assigning it.
        if let existing = refreshTask {
            return try await existing.value
        }
        let task = Task { @MainActor in
            defer { self.refreshTask = nil }
            struct RefreshBody: Encodable {
                let refreshToken: String
                enum CodingKeys: String, CodingKey { case refreshToken = "refresh_token" }
            }
            // The backend's /auth/refresh returns just `{token, refresh_token}`,
            // not a full AuthResponse with a `user` field. Decoding into
            // AuthResponse blew up on the missing `user` key, the refresh
            // appeared to fail to the caller, and every authenticated request
            // afterwards stayed 401 with a misleading "decoding error" message.
            struct RefreshResponse: Decodable {
                let token: String
                let refreshToken: String
                enum CodingKeys: String, CodingKey {
                    case token
                    case refreshToken = "refresh_token"
                }
            }
            guard let refresh = self.refreshToken else { return false }
            let response: RefreshResponse = try await self.request(
                method: "POST", path: "/auth/refresh",
                body: RefreshBody(refreshToken: refresh))
            self.setToken(response.token, refresh: response.refreshToken)
            return true
        }
        refreshTask = task
        return try await task.value
    }

    // MARK: - Auth

    struct EmailCheckResponse: Decodable {
        let exists: Bool
        let role: String
    }

    /// Returns `exists=true` iff a consumer account with this email is already
    /// in the database. Scoped by role since (email, role) is the new unique
    /// key — a seller-side account with the same email won't false-positive.
    func checkEmail(_ email: String) async throws -> EmailCheckResponse {
        struct Body: Encodable { let email: String; let role: String }
        return try await request(method: "POST", path: "/auth/email/check",
            body: Body(email: email, role: "consumer"), authenticated: false)
    }

    func login(email: String, password: String) async throws -> AuthResponse {
        let body = LoginRequest(email: email, password: password, role: "consumer")
        let response: AuthResponse = try await request(method: "POST", path: "/auth/login", body: body)
        setToken(response.token, refresh: response.refreshToken)
        return response
    }

    func register(email: String, password: String, firstName: String, lastName: String, phone: String) async throws -> AuthResponse {
        let body = RegisterRequest(email: email, password: password, firstName: firstName, lastName: lastName, phone: phone, role: "consumer")
        let response: AuthResponse = try await request(method: "POST", path: "/auth/register", body: body)
        setToken(response.token, refresh: response.refreshToken)
        return response
    }

    func socialLogin(provider: String, token: String, firstName: String, lastName: String, nonce: String? = nil) async throws -> AuthResponse {
        let body = SocialLoginRequest(provider: provider, token: token, firstName: firstName, lastName: lastName, role: "consumer", nonce: nonce)
        let response: AuthResponse = try await request(method: "POST", path: "/auth/social", body: body)
        setToken(response.token, refresh: response.refreshToken)
        return response
    }

    // MARK: - Phone OTP login
    //
    // Backend is wired to Twilio Verify. /auth/phone/start triggers the SMS,
    // /auth/phone/verify trades a valid code for a JWT. Phone must be E.164.
    // The verify call sends role="consumer" so the backend creates a brand
    // new consumer account for first-time phone signups. Existing sellers or
    // couriers keep their elevated role (backend never demotes), so a courier
    // logging into the consumer app via phone still gets their courier token
    // — which is fine, the consumer app's endpoints work for any role.

    struct PhoneStartBody: Encodable { let phone: String }
    struct PhoneVerifyBody: Encodable {
        let phone: String
        let code: String
        let role: String
        let firstName: String?
        let lastName: String?
        enum CodingKeys: String, CodingKey {
            case phone, code, role
            case firstName = "first_name"
            case lastName = "last_name"
        }
    }

    func startPhoneLogin(phone: String) async throws {
        let _: [String: String] = try await request(
            method: "POST", path: "/auth/phone/start",
            body: PhoneStartBody(phone: phone))
    }

    func verifyPhoneLogin(phone: String, code: String,
                          firstName: String? = nil, lastName: String? = nil) async throws -> AuthResponse {
        let response: AuthResponse = try await request(
            method: "POST", path: "/auth/phone/verify",
            body: PhoneVerifyBody(phone: phone, code: code, role: "consumer",
                                  firstName: firstName, lastName: lastName))
        setToken(response.token, refresh: response.refreshToken)
        return response
    }

    // MARK: - Email verification
    //
    // Two flows share the backend email_otp store. The pre-register pair
    // (/auth/email/*) verifies an email BEFORE the account exists, for the
    // email-signup flow. The authenticated pair (/user/email/*) attaches and
    // verifies a real inbox onto an existing account — used by the phone-first
    // and Apple onboarding flows. Codes are 6 digits.

    struct EmailBody: Encodable { let email: String }
    struct EmailCodeBody: Encodable { let email: String; let code: String }

    func startEmailSignup(email: String) async throws {
        try await requestVoid(method: "POST", path: "/auth/email/start", body: EmailBody(email: email))
    }

    func verifyEmailSignup(email: String, code: String) async throws {
        try await requestVoid(method: "POST", path: "/auth/email/verify", body: EmailCodeBody(email: email, code: code))
    }

    func startEmailChange(email: String) async throws {
        try await requestVoid(method: "POST", path: "/user/email/start", body: EmailBody(email: email), authenticated: true)
    }

    func verifyEmailChange(email: String, code: String) async throws {
        try await requestVoid(method: "POST", path: "/user/email/verify", body: EmailCodeBody(email: email, code: code), authenticated: true)
    }

    // MARK: - Phone change / add-phone (post sign-in onboarding)
    //
    // The "add a verified phone after social/email sign-in" step. Same Twilio
    // Verify OTP as login, but authenticated and writing onto the existing
    // account (UpdateProfile no longer writes phone). Code length matches login.

    struct PhoneChangeVerifyBody: Encodable { let phone: String; let code: String }

    func startPhoneChange(phone: String) async throws {
        try await requestVoid(method: "POST", path: "/user/phone/change/start", body: PhoneStartBody(phone: phone), authenticated: true)
    }

    func verifyPhoneChange(phone: String, code: String) async throws {
        try await requestVoid(method: "POST", path: "/user/phone/change/verify", body: PhoneChangeVerifyBody(phone: phone, code: code), authenticated: true)
    }

    func logout() {
        clearToken()
        // Drop any in-flight PaymentIntent marker so a charged-but-unrecovered
        // checkout from this user can't block the next user on this device.
        CheckoutViewModel.clearInflightMarker()
    }

    // MARK: - Password reset
    //
    // forgotPassword/resetPassword send role="consumer" + vertical="kosher" —
    // the same scope this app uses for /login — so the backend targets the
    // exact consumer account when an email also has a seller-side account.
    // Without this, the reset could land on the wrong row (email is unique
    // per (email, role, vertical), not globally).

    struct MessageResponse: Decodable { let message: String }

    @discardableResult
    func forgotPassword(email: String) async throws -> MessageResponse {
        struct Body: Encodable {
            let email: String
            let role: String
            let vertical: String
        }
        return try await request(method: "POST", path: "/auth/password/forgot",
                                 body: Body(email: email, role: "consumer", vertical: "kosher"))
    }

    @discardableResult
    func resetPassword(email: String, code: String, newPassword: String) async throws -> MessageResponse {
        struct Body: Encodable {
            let email: String
            let code: String
            let newPassword: String
            let role: String
            let vertical: String
            enum CodingKeys: String, CodingKey {
                case email, code, role, vertical
                case newPassword = "new_password"
            }
        }
        return try await request(method: "POST", path: "/auth/password/reset",
                                 body: Body(email: email, code: code, newPassword: newPassword,
                                            role: "consumer", vertical: "kosher"))
    }

    // MARK: - Restaurants

    func listRestaurants() async throws -> [Restaurant] {
        try await request(method: "GET", path: "/restaurants")
    }

    func getRestaurant(id: String) async throws -> Restaurant {
        try await request(method: "GET", path: "/restaurants/\(id)")
    }

    func getMenu(restaurantID: String) async throws -> [MenuCategory] {
        try await request(method: "GET", path: "/restaurants/\(restaurantID)/menu")
    }

    func searchRestaurants(query: String) async throws -> [Restaurant] {
        let allowed = CharacterSet.urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&+=?#"))
        let encoded = query.addingPercentEncoding(withAllowedCharacters: allowed) ?? query
        return try await request(method: "GET", path: "/restaurants/search?q=\(encoded)")
    }

    // MARK: - Deals

    func getNearbyDeals() async throws -> [Deal] {
        try await request(method: "GET", path: "/deals/nearby")
    }

    func getRestaurantDeals(restaurantID: String) async throws -> [Deal] {
        try await request(method: "GET", path: "/restaurants/\(restaurantID)/deals")
    }

    // MARK: - Favorites

    func listFavoriteIDs() async throws -> [String] {
        try await request(method: "GET", path: "/favorites/ids", authenticated: true)
    }

    func addFavorite(restaurantID: String) async throws {
        let _: [String: String] = try await request(method: "POST", path: "/favorites/\(restaurantID)", authenticated: true)
    }

    func removeFavorite(restaurantID: String) async throws {
        let _: [String: String] = try await request(method: "DELETE", path: "/favorites/\(restaurantID)", authenticated: true)
    }

    // MARK: - Cart

    func getCart() async throws -> Cart {
        try await request(method: "GET", path: "/cart", authenticated: true)
    }

    func addToCart(
        menuItemID: String,
        quantity: Int,
        notes: String?,
        restaurantID: String,
        modifierIDs: [String] = [],
    ) async throws -> Cart {
        let body = AddToCartRequest(
            menuItemID: menuItemID,
            quantity: quantity,
            notes: notes,
            restaurantID: restaurantID,
            modifierIDs: modifierIDs,
        )
        return try await request(method: "POST", path: "/cart/items", body: body, authenticated: true)
    }

    func updateCartItem(id: String, quantity: Int) async throws -> Cart {
        let body = UpdateCartItemRequest(quantity: quantity)
        return try await request(method: "PATCH", path: "/cart/items/\(id)", body: body, authenticated: true)
    }

    func removeCartItem(id: String) async throws -> Cart {
        try await request(method: "DELETE", path: "/cart/items/\(id)", authenticated: true)
    }

    func clearCart() async throws {
        try await requestVoid(method: "DELETE", path: "/cart", authenticated: true)
    }

    // MARK: - Orders

    func createOrder(
        deliveryAddress: String,
        lat: Double,
        lng: Double,
        paymentIntentId: String,
        tip: Int,
        scheduledFor: Date? = nil,
        fulfillmentType: String = "delivery",
        appliedDealId: String? = nil
    ) async throws -> Order {
        struct Body: Encodable {
            let deliveryAddress: String
            let deliveryLat: Double
            let deliveryLng: Double
            let paymentIntentId: String
            let tip: Int
            let scheduledFor: Date?
            let fulfillmentType: String
            let appliedDealId: String?
            enum CodingKeys: String, CodingKey {
                case deliveryAddress = "delivery_address"
                case deliveryLat = "delivery_lat"
                case deliveryLng = "delivery_lng"
                case paymentIntentId = "payment_intent_id"
                case tip
                case scheduledFor = "scheduled_for"
                case fulfillmentType = "fulfillment_type"
                case appliedDealId = "applied_deal_id"
            }
        }
        let body = Body(deliveryAddress: deliveryAddress, deliveryLat: lat, deliveryLng: lng,
                        paymentIntentId: paymentIntentId, tip: tip, scheduledFor: scheduledFor,
                        fulfillmentType: fulfillmentType, appliedDealId: appliedDealId)
        return try await request(method: "POST", path: "/orders", body: body, authenticated: true)
    }

    // MARK: - Checkout (Stripe PaymentSheet)

    struct PaymentSheetBundle: Decodable {
        let paymentIntentSecret: String
        let ephemeralKeySecret: String
        let customerId: String
        let publishableKey: String
        let subtotal: Int
        let deliveryFee: Int
        // How the order will be delivered: "uber_direct" | "doordash_drive" |
        // "self_delivery" | "flat_rate". Optional for resilience during rollout.
        let deliveryMethod: String?
        let serviceFee: Int
        let tax: Int
        let tip: Int
        let total: Int
        let discount: Int?
        let appliedDealId: String?
        let defaultCardBrand: String?
        let defaultCardLast4: String?

        enum CodingKeys: String, CodingKey {
            case paymentIntentSecret = "payment_intent_secret"
            case ephemeralKeySecret = "ephemeral_key_secret"
            case customerId = "customer_id"
            case publishableKey = "publishable_key"
            case subtotal, tax, tip, total, discount
            case deliveryFee = "delivery_fee"
            case deliveryMethod = "delivery_method"
            case serviceFee = "service_fee"
            case appliedDealId = "applied_deal_id"
            case defaultCardBrand = "default_card_brand"
            case defaultCardLast4 = "default_card_last4"
        }

        var isStub: Bool { paymentIntentSecret.hasPrefix("pi_stub_") }
    }

    func createPaymentSheet(tip: Int, fulfillmentType: String = "delivery", appliedDealId: String? = nil, deliveryAddress: String? = nil) async throws -> PaymentSheetBundle {
        struct Body: Encodable {
            let tip: Int
            let fulfillmentType: String
            let appliedDealId: String?
            // Sent so the PaymentIntent prices the real courier delivery quote
            // instead of the flat fallback. The server stamps that fee onto the
            // PaymentIntent and CreateOrder reuses it, so the charge and the
            // recorded order total always agree. Omitted for pickup.
            let deliveryAddress: String?
            enum CodingKeys: String, CodingKey {
                case tip
                case fulfillmentType = "fulfillment_type"
                case appliedDealId = "applied_deal_id"
                case deliveryAddress = "delivery_address"
            }
        }
        return try await request(method: "POST", path: "/payments/intent",
                                 body: Body(tip: tip, fulfillmentType: fulfillmentType, appliedDealId: appliedDealId, deliveryAddress: deliveryAddress), authenticated: true)
    }

    // CustomerSheet bundle — used by Profile → Payment Methods to let the
    // user manage saved cards outside of a checkout flow. No PaymentIntent
    // because CustomerSheet uses SetupIntents to tokenize new cards.
    struct CustomerBundle: Decodable {
        let customerId: String
        let ephemeralKeySecret: String
        let publishableKey: String

        enum CodingKeys: String, CodingKey {
            case customerId = "customer_id"
            case ephemeralKeySecret = "ephemeral_key_secret"
            case publishableKey = "publishable_key"
        }

        var isStub: Bool { customerId.hasPrefix("cus_stub_") }
    }

    func getPaymentCustomer() async throws -> CustomerBundle {
        try await request(method: "GET", path: "/payments/customer", authenticated: true)
    }

    struct SetupIntentResponse: Decodable {
        let clientSecret: String
        enum CodingKeys: String, CodingKey { case clientSecret = "client_secret" }
    }

    func createSetupIntent() async throws -> String {
        let resp: SetupIntentResponse = try await request(method: "POST", path: "/payments/setup-intent",
                                                          body: EmptyBody(), authenticated: true)
        return resp.clientSecret
    }

    private struct EmptyBody: Encodable {}

    func listOrders() async throws -> [Order] {
        try await request(method: "GET", path: "/orders", authenticated: true)
    }

    /// Keyset-paginated order history. Pass the `createdAt` of the last row from
    /// the previous page as `cursor` to fetch older orders; nil fetches the
    /// newest page. The backend expects an RFC3339Nano timestamp and returns up
    /// to `limit` (max 100) rows in descending created_at order.
    func listOrders(cursor: Date?, limit: Int = 50) async throws -> [Order] {
        var items = [URLQueryItem(name: "limit", value: String(limit))]
        if let cursor {
            items.append(URLQueryItem(name: "cursor",
                                      value: APIService.iso8601Fractional.string(from: cursor)))
        }
        var components = URLComponents()
        components.path = "/orders"
        components.queryItems = items
        let path = "/orders\(components.percentEncodedQuery.map { "?\($0)" } ?? "")"
        return try await request(method: "GET", path: path, authenticated: true)
    }

    func getOrder(id: String) async throws -> Order {
        try await request(method: "GET", path: "/orders/\(id)", authenticated: true)
    }

    /// Recover the order created for a given Stripe PaymentIntent. Used as an
    /// idempotent fallback when `createOrder` fails to return after a confirmed
    /// payment (e.g. network drop): the client retries with the PaymentIntent id
    /// it just confirmed. The backend scopes the lookup to the calling user and
    /// returns the full Order (with order_items) or 404 if none exists.
    func getOrderByPaymentIntent(_ pi: String) async throws -> Order {
        let encoded = pi.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? pi
        return try await request(method: "GET", path: "/orders/by-payment-intent/\(encoded)", authenticated: true)
    }

    func cancelOrder(id: String) async throws -> Order {
        try await request(method: "PATCH", path: "/orders/\(id)/cancel", authenticated: true)
    }

    func rateCourier(orderId: String, stars: Int, comment: String?) async throws {
        struct Body: Encodable { let stars: Int; let comment: String }
        try await requestVoid(
            method: "POST",
            path: "/orders/\(orderId)/rating",
            body: Body(stars: stars, comment: comment ?? ""),
            authenticated: true,
        )
    }

    // MARK: - Courier location stream (SSE)

    struct CourierLocationEvent: Decodable {
        let orderID: String
        let lat: Double
        let lng: Double
        let heading: Double
        let speed: Double
        let at: Date

        enum CodingKeys: String, CodingKey {
            case lat, lng, heading, speed, at
            case orderID = "order_id"
        }
    }

    // streamOrderLocation opens an SSE connection to the backend and emits
    // decoded CourierLocationEvents as they arrive. The stream ends when the
    // caller cancels the enclosing Task (e.g. the tracking view disappears)
    // or the server closes the connection (e.g. order reaches a terminal state).
    //
    // Throws APIError.unauthorized on a 401, so callers can decide whether
    // to refresh the token and retry rather than treating it as a generic
    // network error and burning into an exponential-backoff hole.
    func streamOrderLocation(id: String) -> AsyncThrowingStream<CourierLocationEvent, Error> {
        let url = URL(string: "\(baseURL)/orders/\(id)/location/stream")
        let authToken = self.token
        let decoder = self.decoder
        return AsyncThrowingStream { continuation in
            let task = Task {
                guard let url = url else {
                    continuation.finish(throwing: APIError.invalidURL)
                    return
                }
                guard let authToken else {
                    continuation.finish(throwing: APIError.unauthorized)
                    return
                }
                var req = URLRequest(url: url)
                req.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization")
                req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                // Backend sends a comment ping every 25s (sseHeartbeatInterval in
                // orders.go). timeoutInterval acts as a no-activity timeout that
                // resets on each received byte, so 90s gives ~3 missed pings of
                // headroom before declaring the connection dead. The old
                // greatestFiniteMagnitude meant a silent cellular drop would
                // freeze the courier pin until the user backgrounded the app.
                req.timeoutInterval = 90

                do {
                    let (bytes, response) = try await URLSession.shared.bytes(for: req)
                    guard let http = response as? HTTPURLResponse else {
                        throw APIError.invalidResponse
                    }
                    if http.statusCode == 401 {
                        throw APIError.unauthorized
                    }
                    guard (200...299).contains(http.statusCode) else {
                        throw APIError.httpError(http.statusCode, "stream open failed")
                    }

                    // Minimal SSE parser: accumulate `data:` lines per event,
                    // flush on blank line. We only care about events whose
                    // payload decodes as a CourierLocationEvent.
                    var dataBuf = ""
                    for try await line in bytes.lines {
                        if Task.isCancelled { break }
                        if line.isEmpty {
                            if !dataBuf.isEmpty, let payload = dataBuf.data(using: .utf8) {
                                do {
                                    let event = try decoder.decode(CourierLocationEvent.self, from: payload)
                                    continuation.yield(event)
                                } catch {
                                    // Don't tear down the stream on a malformed
                                    // event — backend may add new event types
                                    // we don't yet know about. Log so we can
                                    // notice schema drift in TestFlight builds.
                                    print("[sse] decode error: \(error) payload=\(dataBuf)")
                                }
                            }
                            dataBuf = ""
                            continue
                        }
                        if line.hasPrefix("data:") {
                            let piece = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                            if dataBuf.isEmpty { dataBuf = piece } else { dataBuf += "\n" + piece }
                        }
                        // `event:` and `: ping` lines are ignored — we only
                        // publish one event type and the periodic ping resets
                        // URLSession's per-request timeout for us.
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - User Profile

    func getProfile() async throws -> User {
        try await request(method: "GET", path: "/user/profile", authenticated: true)
    }

    func updateProfile(firstName: String, lastName: String, phone: String, email: String? = nil) async throws -> User {
        struct Body: Encodable {
            let firstName: String
            let lastName: String
            let phone: String
            let email: String?
            enum CodingKeys: String, CodingKey {
                case firstName = "first_name"
                case lastName = "last_name"
                case phone
                case email
            }
        }
        return try await request(method: "PUT", path: "/user/profile",
            body: Body(firstName: firstName, lastName: lastName, phone: phone, email: email),
            authenticated: true)
    }

    func listAddresses() async throws -> [Address] {
        try await request(method: "GET", path: "/user/addresses", authenticated: true)
    }

    func addAddress(_ address: Address) async throws -> Address {
        try await request(method: "POST", path: "/user/addresses", body: address, authenticated: true)
    }

    func deleteAddress(id: String) async throws {
        try await requestVoid(method: "DELETE", path: "/user/addresses/\(id)", authenticated: true)
    }

    func setDefaultAddress(id: String) async throws {
        try await requestVoid(method: "PATCH", path: "/user/addresses/\(id)/default", authenticated: true)
    }

    func deleteAccount() async throws {
        try await requestVoid(method: "DELETE", path: "/user/account", authenticated: true)
    }

    // MARK: - Linked Providers (Account Linking)

    func listLinkedProviders() async throws -> [LinkedProvider] {
        try await request(method: "GET", path: "/user/linked-providers", authenticated: true)
    }

    func linkProvider(provider: String, token: String, nonce: String? = nil) async throws {
        var body: [String: String] = ["provider": provider, "token": token]
        if let nonce { body["nonce"] = nonce }
        try await requestVoid(method: "POST", path: "/user/linked-providers", body: body, authenticated: true)
    }

    func linkPhone(phone: String, code: String) async throws {
        let body: [String: String] = ["provider": "phone", "phone": phone, "code": code]
        try await requestVoid(method: "POST", path: "/user/linked-providers", body: body, authenticated: true)
    }

    func unlinkProvider(_ provider: String) async throws {
        try await requestVoid(method: "DELETE", path: "/user/linked-providers/\(provider)", authenticated: true)
    }

    // MARK: - Device tokens (push)

    func registerDevice(token: String, platform: String, app: String) async throws {
        struct Body: Encodable { let token: String; let platform: String; let app: String }
        try await requestVoid(method: "POST", path: "/devices/register",
                              body: Body(token: token, platform: platform, app: app),
                              authenticated: true)
    }

    /// Detaches this device's APNs token from the current user so the next
    /// account that signs in on the same install doesn't inherit its pushes.
    /// Mirrors registerDevice's body; requires auth, so logout must call this
    /// before clearing the token.
    func unregisterDevice(token: String, platform: String, app: String) async throws {
        struct Body: Encodable { let token: String; let platform: String; let app: String }
        try await requestVoid(method: "POST", path: "/devices/unregister",
                              body: Body(token: token, platform: platform, app: app),
                              authenticated: true)
    }

    // MARK: - Notification preferences

    struct NotificationPreferences: Codable, Equatable {
        var orderUpdates: Bool
        var chatMessages: Bool
        var promotions: Bool

        enum CodingKeys: String, CodingKey {
            case orderUpdates = "order_updates"
            case chatMessages = "chat_messages"
            case promotions
        }

        static let allOn = NotificationPreferences(orderUpdates: true, chatMessages: true, promotions: true)
    }

    func getNotificationPreferences() async throws -> NotificationPreferences {
        try await request(method: "GET", path: "/user/notification-preferences", authenticated: true)
    }

    func updateNotificationPreferences(_ prefs: NotificationPreferences) async throws -> NotificationPreferences {
        try await request(method: "PUT", path: "/user/notification-preferences",
                          body: prefs, authenticated: true)
    }

    // MARK: - Chat

    func listChatMessages(orderID: String) async throws -> [ChatMessage] {
        try await request(method: "GET", path: "/orders/\(orderID)/chat", authenticated: true)
    }

    func sendChatMessage(orderID: String, text: String) async throws -> ChatMessage {
        struct Body: Encodable { let text: String }
        return try await request(method: "POST", path: "/orders/\(orderID)/chat",
                                 body: Body(text: text), authenticated: true)
    }
}
