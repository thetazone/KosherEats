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
}

@MainActor
class APIService: ObservableObject {
    static let shared = APIService()

    nonisolated(unsafe) private static let iso8601Fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    nonisolated(unsafe) private static let iso8601Plain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    #if DEBUG
    private var baseURL = "http://localhost:8080/api/v1"
    #else
    private var baseURL = "https://koshereats-api.fly.dev/api/v1"
    #endif

    private var token: String? {
        get { KeychainHelper.load(forKey: "auth_token") }
        set {
            if let v = newValue { KeychainHelper.save(v, forKey: "auth_token") }
            else { KeychainHelper.delete(forKey: "auth_token") }
        }
    }

    private var refreshToken: String? {
        get { KeychainHelper.load(forKey: "refresh_token") }
        set {
            if let v = newValue { KeychainHelper.save(v, forKey: "refresh_token") }
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
        authenticated: Bool = false
    ) async throws -> T {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw APIError.invalidURL
        }

        var req = URLRequest(url: url)
        req.httpMethod = method
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

        if httpResponse.statusCode == 401 {
            // Try token refresh
            if authenticated, let _ = refreshToken {
                let refreshed = try? await performTokenRefresh()
                if refreshed == true {
                    return try await request(method: method, path: path, body: body, authenticated: authenticated)
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
        authenticated: Bool = false
    ) async throws {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw APIError.invalidURL
        }

        var req = URLRequest(url: url)
        req.httpMethod = method
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
            if authenticated, refreshToken != nil {
                let refreshed = try? await performTokenRefresh()
                if refreshed == true {
                    try await requestVoid(method: method, path: path, body: body, authenticated: authenticated)
                    return
                }
            }
            throw APIError.unauthorized
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorMsg = (try? decoder.decode(APIErrorResponse.self, from: data))?.error ?? "Unknown error"
            throw APIError.httpError(httpResponse.statusCode, errorMsg)
        }
    }

    private var isRefreshing = false

    private func performTokenRefresh() async throws -> Bool {
        if isRefreshing { return token != nil }
        isRefreshing = true
        defer { isRefreshing = false }
        guard let refresh = refreshToken else { return false }

        struct RefreshBody: Encodable { let refreshToken: String
            enum CodingKeys: String, CodingKey { case refreshToken = "refresh_token" }
        }

        let body = RefreshBody(refreshToken: refresh)
        let response: AuthResponse = try await request(method: "POST", path: "/auth/refresh", body: body)
        setToken(response.token, refresh: response.refreshToken)
        return true
    }

    // MARK: - Auth

    struct EmailCheckResponse: Decodable {
        let exists: Bool
        let role: String
    }

    /// Returns `exists=true` iff an account with this email is already in the
    /// database. Used by the unified email-entry flow to decide between
    /// sign-in and sign-up without making the user pick first.
    func checkEmail(_ email: String) async throws -> EmailCheckResponse {
        struct Body: Encodable { let email: String }
        return try await request(method: "POST", path: "/auth/email/check",
            body: Body(email: email), authenticated: false)
    }

    func login(email: String, password: String) async throws -> AuthResponse {
        let body = LoginRequest(email: email, password: password)
        let response: AuthResponse = try await request(method: "POST", path: "/auth/login", body: body)
        setToken(response.token, refresh: response.refreshToken)
        return response
    }

    func register(email: String, password: String, firstName: String, lastName: String, phone: String) async throws -> AuthResponse {
        let body = RegisterRequest(email: email, password: password, firstName: firstName, lastName: lastName, phone: phone)
        let response: AuthResponse = try await request(method: "POST", path: "/auth/register", body: body)
        setToken(response.token, refresh: response.refreshToken)
        return response
    }

    func socialLogin(provider: String, token: String, firstName: String, lastName: String, nonce: String? = nil) async throws -> AuthResponse {
        let body = SocialLoginRequest(provider: provider, token: token, firstName: firstName, lastName: lastName, nonce: nonce)
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

    func logout() {
        clearToken()
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
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        return try await request(method: "GET", path: "/restaurants/search?q=\(encoded)")
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
    ) async throws -> Order {
        struct Body: Encodable {
            let deliveryAddress: String
            let deliveryLat: Double
            let deliveryLng: Double
            let paymentIntentId: String
            let tip: Int
            let scheduledFor: Date?
            enum CodingKeys: String, CodingKey {
                case deliveryAddress = "delivery_address"
                case deliveryLat = "delivery_lat"
                case deliveryLng = "delivery_lng"
                case paymentIntentId = "payment_intent_id"
                case tip
                case scheduledFor = "scheduled_for"
            }
        }
        let body = Body(deliveryAddress: deliveryAddress, deliveryLat: lat, deliveryLng: lng,
                        paymentIntentId: paymentIntentId, tip: tip, scheduledFor: scheduledFor)
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
        let serviceFee: Int
        let tax: Int
        let tip: Int
        let total: Int
        let defaultCardBrand: String?
        let defaultCardLast4: String?

        enum CodingKeys: String, CodingKey {
            case paymentIntentSecret = "payment_intent_secret"
            case ephemeralKeySecret = "ephemeral_key_secret"
            case customerId = "customer_id"
            case publishableKey = "publishable_key"
            case subtotal, tax, tip, total
            case deliveryFee = "delivery_fee"
            case serviceFee = "service_fee"
            case defaultCardBrand = "default_card_brand"
            case defaultCardLast4 = "default_card_last4"
        }

        /// Dev-stub mode when the backend has no STRIPE_SECRET_KEY. iOS should
        /// skip presenting PaymentSheet in this case and go straight to createOrder.
        var isStub: Bool { paymentIntentSecret.hasPrefix("pi_stub_") }
    }

    func createPaymentSheet(tip: Int) async throws -> PaymentSheetBundle {
        struct Body: Encodable { let tip: Int }
        return try await request(method: "POST", path: "/payments/intent",
                                 body: Body(tip: tip), authenticated: true)
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

    func getOrder(id: String) async throws -> Order {
        try await request(method: "GET", path: "/orders/\(id)", authenticated: true)
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
        return AsyncThrowingStream { continuation in
            let task = Task {
                guard let url = url else {
                    continuation.finish(throwing: APIError.invalidURL)
                    return
                }
                guard let authToken = await self.token else {
                    continuation.finish(throwing: APIError.unauthorized)
                    return
                }
                var req = URLRequest(url: url)
                req.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization")
                req.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                // Backend sends a comment ping every 15s. 60s gives us 4 pings
                // of headroom before declaring the connection dead. The old
                // greatestFiniteMagnitude meant a silent cellular drop would
                // freeze the courier pin until the user backgrounded the app.
                req.timeoutInterval = 60

                let decoder = await self.decoder

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

    // MARK: - Device tokens (push)

    func registerDevice(token: String, platform: String, app: String) async throws {
        struct Body: Encodable { let token: String; let platform: String; let app: String }
        try await requestVoid(method: "POST", path: "/devices/register",
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

