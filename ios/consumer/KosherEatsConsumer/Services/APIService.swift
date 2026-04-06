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

    #if DEBUG
    private var baseURL = "http://localhost:8080/api/v1"
    #else
    private var baseURL = "https://koshereats-api.fly.dev/api/v1"
    #endif

    private var token: String? {
        get { UserDefaults.standard.string(forKey: "auth_token") }
        set { UserDefaults.standard.set(newValue, forKey: "auth_token") }
    }

    private var refreshToken: String? {
        get { UserDefaults.standard.string(forKey: "refresh_token") }
        set { UserDefaults.standard.set(newValue, forKey: "refresh_token") }
    }

    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
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

        let (data, response) = try await URLSession.shared.data(for: req)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }

        if httpResponse.statusCode == 401 {
            throw APIError.unauthorized
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorMsg = (try? decoder.decode(APIErrorResponse.self, from: data))?.error ?? "Unknown error"
            throw APIError.httpError(httpResponse.statusCode, errorMsg)
        }
    }

    private func performTokenRefresh() async throws -> Bool {
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

    func socialLogin(provider: String, token: String, firstName: String, lastName: String) async throws -> AuthResponse {
        let body = SocialLoginRequest(provider: provider, token: token, firstName: firstName, lastName: lastName)
        let response: AuthResponse = try await request(method: "POST", path: "/auth/social", body: body)
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

        enum CodingKeys: String, CodingKey {
            case paymentIntentSecret = "payment_intent_secret"
            case ephemeralKeySecret = "ephemeral_key_secret"
            case customerId = "customer_id"
            case publishableKey = "publishable_key"
            case subtotal, tax, tip, total
            case deliveryFee = "delivery_fee"
            case serviceFee = "service_fee"
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

    func listOrders() async throws -> [Order] {
        try await request(method: "GET", path: "/orders", authenticated: true)
    }

    func getOrder(id: String) async throws -> Order {
        try await request(method: "GET", path: "/orders/\(id)", authenticated: true)
    }

    func cancelOrder(id: String) async throws -> Order {
        try await request(method: "PATCH", path: "/orders/\(id)/cancel", authenticated: true)
    }

    // MARK: - User Profile

    func getProfile() async throws -> User {
        try await request(method: "GET", path: "/user/profile", authenticated: true)
    }

    func updateProfile(firstName: String, lastName: String, phone: String) async throws -> User {
        struct Body: Encodable {
            let firstName: String
            let lastName: String
            let phone: String
            enum CodingKeys: String, CodingKey {
                case firstName = "first_name"
                case lastName = "last_name"
                case phone
            }
        }
        return try await request(method: "PUT", path: "/user/profile", body: Body(firstName: firstName, lastName: lastName, phone: phone), authenticated: true)
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

    // MARK: - Device tokens (push)

    func registerDevice(token: String, platform: String, app: String) async throws {
        struct Body: Encodable { let token: String; let platform: String; let app: String }
        try await requestVoid(method: "POST", path: "/devices/register",
                              body: Body(token: token, platform: platform, app: app),
                              authenticated: true)
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

