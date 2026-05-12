import Foundation

enum APIError: LocalizedError {
    case invalidURL
    case unauthorized
    case serverError(Int, String)
    case decodingError(String)
    case networkError(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .unauthorized: return "Session expired. Please log in again."
        case .serverError(let code, let msg): return "Server error (\(code)): \(msg)"
        case .decodingError(let msg): return "Data error: \(msg)"
        case .networkError(let msg): return "Network error: \(msg)"
        }
    }
}

actor APIService {
    static let shared = APIService()

    private let baseURL: String
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        return URLSession(configuration: config)
    }()
    private var token: String?
    /// Held inside the actor so request/refresh can coordinate without
    /// reaching into UserDefaults on every call. AuthViewModel still owns
    /// the durable persistence (UserDefaults) and hydrates this on launch
    /// via `setRefreshToken(_:)`.
    private var refreshToken: String?

    private init() {
        self.baseURL = "https://koshereats-api.fly.dev/api/v1"
        // Pre-load synchronously so any API call that races the AuthViewModel
        // init Task still carries an Authorization header and refresh token.
        self.token = KeychainHelper.load(forKey: "ke_seller_token")
        self.refreshToken = KeychainHelper.load(forKey: "ke_seller_refresh_token")

        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
        self.encoder.keyEncodingStrategy = .convertToSnakeCase
    }

    func setToken(_ token: String?) {
        self.token = token
    }

    func setRefreshToken(_ refresh: String?) {
        self.refreshToken = refresh
    }

    // MARK: - Core Request

    /// Maximum retries for transient server errors (502/503/504) caused by
    /// Fly.io cold-starts or momentary unavailability.
    private let maxRetries = 1

    private func request<T: Decodable>(
        _ method: String,
        path: String,
        body: Encodable? = nil,
        headers: [String: String]? = nil
    ) async throws -> T {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw APIError.invalidURL
        }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = token {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let headers {
            for (key, value) in headers {
                req.setValue(value, forHTTPHeaderField: key)
            }
        }

        if let body = body {
            req.httpBody = try encoder.encode(AnyEncodable(body))
        }

        var lastError: Error = APIError.networkError("Unknown error")
        for attempt in 0...maxRetries {
            let (data, response): (Data, URLResponse)
            do {
                (data, response) = try await session.data(for: req)
            } catch let urlError as URLError where attempt < maxRetries &&
                [.timedOut, .networkConnectionLost, .notConnectedToInternet].contains(urlError.code) {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                continue
            } catch {
                throw APIError.networkError(error.localizedDescription)
            }

            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIError.networkError("Invalid response")
            }

            // Retry on transient server errors (cold-start 502/503/504)
            if [502, 503, 504].contains(httpResponse.statusCode), attempt < maxRetries {
                try? await Task.sleep(nanoseconds: 1_500_000_000) // 1.5s
                continue
            }

            if httpResponse.statusCode == 401 {
                // Try refreshing the access token once before giving up. A
                // bare 401 here would otherwise log the seller out whenever
                // the JWT quietly expired between calls, even though the
                // refresh token is still valid.
                if refreshToken != nil {
                    let refreshed = await performTokenRefresh()
                    if refreshed {
                        return try await request(method, path: path, body: body)
                    }
                }
                throw APIError.unauthorized
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                let errorMsg = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "Unknown error"
                lastError = APIError.serverError(httpResponse.statusCode, errorMsg)
                break
            }

            do {
                return try decoder.decode(T.self, from: data)
            } catch {
                throw APIError.decodingError(error.localizedDescription)
            }
        }
        throw lastError
    }

    /// Swaps the stored refresh token for a fresh access + refresh pair. A
    /// `false` return means the refresh token itself is expired — the caller
    /// should treat that as a hard 401 and route the user back to login.
    private var refreshTask: Task<Bool, Never>?

    private func performTokenRefresh() async -> Bool {
        if let task = refreshTask { return await task.value }
        let task = Task<Bool, Never> {
            defer { self.refreshTask = nil }

            guard let refresh = self.refreshToken,
                  let url = URL(string: "\(self.baseURL)/auth/refresh") else { return false }

            struct RefreshResponse: Decodable {
                let token: String
                let refreshToken: String
                enum CodingKeys: String, CodingKey {
                    case token
                    case refreshToken = "refresh_token"
                }
            }

            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: ["refresh_token": refresh])

            do {
                let (data, response) = try await session.data(for: req)
                guard let http = response as? HTTPURLResponse,
                      (200...299).contains(http.statusCode) else { return false }
                let decoded = try self.decoder.decode(RefreshResponse.self, from: data)
                self.token = decoded.token
                self.refreshToken = decoded.refreshToken
                KeychainHelper.save(decoded.token, forKey: "ke_seller_token")
                KeychainHelper.save(decoded.refreshToken, forKey: "ke_seller_refresh_token")
                return true
            } catch {
                return false
            }
        }
        refreshTask = task
        return await task.value
    }

    private func requestVoid(
        _ method: String,
        path: String,
        body: Encodable? = nil
    ) async throws {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw APIError.invalidURL
        }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if let token = token {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body = body {
            req.httpBody = try encoder.encode(AnyEncodable(body))
        }

        var lastError: Error = APIError.networkError("Unknown error")
        for attempt in 0...maxRetries {
            let (data, response): (Data, URLResponse)
            do {
                (data, response) = try await session.data(for: req)
            } catch let urlError as URLError where attempt < maxRetries &&
                [.timedOut, .networkConnectionLost, .notConnectedToInternet].contains(urlError.code) {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                continue
            } catch {
                throw APIError.networkError(error.localizedDescription)
            }

            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIError.networkError("Invalid response")
            }

            // Retry on transient server errors (cold-start 502/503/504)
            if [502, 503, 504].contains(httpResponse.statusCode), attempt < maxRetries {
                try? await Task.sleep(nanoseconds: 1_500_000_000) // 1.5s
                continue
            }

            if httpResponse.statusCode == 401 {
                if refreshToken != nil {
                    let refreshed = await performTokenRefresh()
                    if refreshed {
                        try await requestVoid(method, path: path, body: body)
                        return
                    }
                }
                throw APIError.unauthorized
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                let errorMsg = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "Unknown error"
                lastError = APIError.serverError(httpResponse.statusCode, errorMsg)
                break
            }

            return // success
        }
        throw lastError
    }

    // MARK: - Auth

    // All auth requests send role="seller" so the backend's scoped lookups
    // (see migration 019) find or create the seller-side account specifically,
    // not a consumer account that happens to share the same email/phone.

    func login(email: String, password: String) async throws -> AuthResponse {
        let body = ["email": email, "password": password, "role": "seller"]
        return try await request("POST", path: "/auth/login", body: body)
    }

    func register(email: String, password: String, firstName: String, lastName: String) async throws -> AuthResponse {
        let body = [
            "email": email,
            "password": password,
            "first_name": firstName,
            "last_name": lastName,
            "phone": "",
            "role": "seller",
        ]
        return try await request("POST", path: "/auth/register", body: body)
    }

    struct EmailCheckResponse: Decodable { let exists: Bool; let role: String }

    func checkEmail(_ email: String) async throws -> EmailCheckResponse {
        let body = ["email": email, "role": "seller"]
        return try await request("POST", path: "/auth/email/check", body: body)
    }

    /// Returns the currently authenticated user. Used on cold start to restore
    /// `user` (and therefore `hasSellerAccess`) after a saved token is loaded —
    /// without this, `AuthViewModel.user` stays nil and real sellers get routed
    /// into SellerOnboardingView on relaunch.
    func getProfile() async throws -> User {
        try await request("GET", path: "/user/profile")
    }

    struct UpdateProfileBody: Encodable {
        let firstName: String
        let lastName: String
        let phone: String
        let email: String?
        enum CodingKeys: String, CodingKey {
            case firstName = "first_name"
            case lastName = "last_name"
            case phone, email
        }
    }

    func updateProfile(firstName: String, lastName: String, phone: String, email: String? = nil) async throws -> User {
        let body = UpdateProfileBody(firstName: firstName, lastName: lastName, phone: phone, email: email)
        return try await request("PUT", path: "/user/profile", body: body)
    }

    func socialLogin(provider: String, token: String, firstName: String, lastName: String, nonce: String? = nil) async throws -> AuthResponse {
        var body: [String: String] = [
            "provider": provider,
            "token": token,
            "first_name": firstName,
            "last_name": lastName,
            "role": "seller",
        ]
        if let nonce { body["nonce"] = nonce }
        return try await request("POST", path: "/auth/social", body: body)
    }

    // MARK: - Phone OTP Login
    //
    // The backend is wired to Twilio Verify — /auth/phone/start triggers the
    // SMS, /auth/phone/verify trades a valid code for a JWT. Phone must be
    // E.164 ("+15551234567"); the caller formats it that way before sending.
    //
    // The verify call advertises role="seller" so the backend can validate
    // seller access. Public seller creation/promotion is intentionally not
    // performed here; AuthViewModel still enforces a role guard on success.

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
        try await requestVoid("POST", path: "/auth/phone/start", body: ["phone": phone])
    }

    func verifyPhoneLogin(phone: String, code: String,
                          firstName: String? = nil, lastName: String? = nil) async throws -> AuthResponse {
        let body = PhoneVerifyBody(phone: phone, code: code, role: "seller",
                                   firstName: firstName, lastName: lastName)
        return try await request("POST", path: "/auth/phone/verify", body: body)
    }

    // MARK: - App Store Reviewer Bypass
    //
    // Signs in as the shared App Review demo seller. Paired with the
    // /auth/reviewer/seller backend handler — exists only so Apple's
    // reviewer can reach the dashboard without a human approving a real
    // seller application. Remove once a self-serve demo path ships.

    func reviewerSellerLogin() async throws -> AuthResponse {
        try await request("POST", path: "/auth/reviewer/seller", headers: ["X-Reviewer-Secret": "ke-review-2026"])
    }

    // MARK: - Restaurant

    // Every seller call below runs through `sellerPath(_:)` which appends the
    // currently-selected restaurant id as a query param. The backend reads it
    // via resolveSellerRestaurant. When nothing is selected (first launch) the
    // backend falls back to the seller's first owned restaurant.
    @MainActor
    private func sellerPath(_ path: String) async -> String {
        SelectedRestaurant.shared.appendQuery(to: path)
    }

    // MARK: - Restaurants

    /// Every restaurant this seller owns. Drives the restaurant picker sheet.
    func listRestaurants() async throws -> [Restaurant] {
        try await request("GET", path: "/seller/restaurants")
    }

    /// Body the seller submits via the first-restaurant onboarding form.
    /// Mirrors the backend's CreateRestaurantRequest field-for-field.
    struct CreateRestaurantBody: Encodable {
        let name: String
        let description: String
        let imageUrl: String
        let logoUrl: String
        let phone: String
        let email: String
        let street: String
        let city: String
        let state: String
        let zipCode: String
        let kosherCertification: String
        let certifyingAgency: String
        let kosherCertificateUrl: String
        let cuisineType: [String]
        let isCholovYisroel: Bool
        let isPasYisroel: Bool
        let isGlattKosher: Bool

        enum CodingKeys: String, CodingKey {
            case name, description, phone, email, street, city, state
            case imageUrl = "image_url"
            case logoUrl = "logo_url"
            case zipCode = "zip_code"
            case kosherCertification = "kosher_certification"
            case certifyingAgency = "certifying_agency"
            case kosherCertificateUrl = "kosher_certificate_url"
            case cuisineType = "cuisine_type"
            case isCholovYisroel = "is_cholov_yisroel"
            case isPasYisroel = "is_pas_yisroel"
            case isGlattKosher = "is_glatt_kosher"
        }
    }

    func createRestaurant(_ body: CreateRestaurantBody) async throws -> Restaurant {
        try await request("POST", path: "/seller/restaurants", body: body)
    }

    func getRestaurant() async throws -> Restaurant {
        try await request("GET", path: await sellerPath("/seller/restaurant"))
    }

    func updateRestaurant(_ data: Restaurant) async throws -> Restaurant {
        try await request("PUT", path: await sellerPath("/seller/restaurant"), body: data)
    }

    func toggleOpen(_ isOpen: Bool) async throws -> Restaurant {
        try await request("PATCH", path: await sellerPath("/seller/restaurant/status"),
                          body: ["is_open": isOpen])
    }

    // MARK: - Menu

    func getMenu() async throws -> [MenuCategory] {
        try await request("GET", path: await sellerPath("/seller/menu"))
    }

    func createMenuItem(_ item: CreateMenuItemRequest) async throws -> MenuItem {
        try await request("POST", path: await sellerPath("/seller/menu/items"), body: item)
    }

    func updateMenuItem(id: String, _ item: CreateMenuItemRequest) async throws -> MenuItem {
        try await request("PUT", path: await sellerPath("/seller/menu/items/\(id)"), body: item)
    }

    func deleteMenuItem(id: String) async throws {
        try await requestVoid("DELETE", path: await sellerPath("/seller/menu/items/\(id)"))
    }

    func toggleItemAvailability(id: String, available: Bool) async throws -> MenuItem {
        try await request("PATCH", path: await sellerPath("/seller/menu/items/\(id)/availability"), body: ["is_available": available])
    }

    func createCategory(_ name: String) async throws -> MenuCategory {
        try await request("POST", path: await sellerPath("/seller/menu/categories"),
                          body: ["name": name])
    }

    func deleteCategory(id: String) async throws {
        try await requestVoid("DELETE", path: await sellerPath("/seller/menu/categories/\(id)"))
    }

    // MARK: - POS Integrations

    struct POSIntegration: Codable, Identifiable {
        let id: String
        let provider: String
        let merchantId: String
        let isActive: Bool
        let createdAt: Date
        let lastUsedAt: Date?

        enum CodingKeys: String, CodingKey {
            case id, provider
            case merchantId = "merchant_id"
            case isActive = "is_active"
            case createdAt = "created_at"
            case lastUsedAt = "last_used_at"
        }
    }

    func listIntegrations() async throws -> [POSIntegration] {
        try await request("GET", path: await sellerPath("/seller/integrations"))
    }

    private struct ConnectURLResponse: Codable { let connectUrl: String; enum CodingKeys: String, CodingKey { case connectUrl = "connect_url" } }

    func cloverConnectURL() async throws -> String {
        let r: ConnectURLResponse = try await request("GET", path: await sellerPath("/seller/integrations/clover/connect-url"))
        return r.connectUrl
    }

    func testIntegration(id: String) async throws {
        try await requestVoid("POST", path: await sellerPath("/seller/integrations/\(id)/test"))
    }

    func disconnectIntegration(id: String) async throws {
        try await requestVoid("DELETE", path: await sellerPath("/seller/integrations/\(id)"))
    }

    // MARK: - Menu item modifiers

    func createModifierGroup(itemID: String, _ body: ModifierGroupRequest) async throws -> ModifierGroup {
        try await request("POST",
                          path: await sellerPath("/seller/menu/items/\(itemID)/modifier-groups"),
                          body: body)
    }

    func updateModifierGroup(groupID: String, _ body: ModifierGroupRequest) async throws -> ModifierGroup {
        try await request("PUT",
                          path: await sellerPath("/seller/menu/modifier-groups/\(groupID)"),
                          body: body)
    }

    func deleteModifierGroup(groupID: String) async throws {
        try await requestVoid("DELETE", path: await sellerPath("/seller/menu/modifier-groups/\(groupID)"))
    }

    // MARK: - Deals

    func getDeals() async throws -> [Deal] {
        try await request("GET", path: await sellerPath("/seller/deals"))
    }

    func createDeal(_ deal: CreateDealRequest) async throws -> Deal {
        try await request("POST", path: await sellerPath("/seller/deals"), body: deal)
    }

    func deactivateDeal(id: String) async throws {
        try await requestVoid("DELETE", path: await sellerPath("/seller/deals/\(id)"))
    }

    // MARK: - Linked Providers (Account Linking)

    func listLinkedProviders() async throws -> [LinkedProvider] {
        try await request("GET", path: "/user/linked-providers")
    }

    func linkProvider(provider: String, token: String, nonce: String? = nil) async throws {
        var body: [String: String] = ["provider": provider, "token": token]
        if let nonce { body["nonce"] = nonce }
        try await requestVoid("POST", path: "/user/linked-providers", body: body)
    }

    func linkPhone(phone: String, code: String) async throws {
        let body: [String: String] = ["provider": "phone", "phone": phone, "code": code]
        try await requestVoid("POST", path: "/user/linked-providers", body: body)
    }

    func unlinkProvider(_ provider: String) async throws {
        try await requestVoid("DELETE", path: "/user/linked-providers/\(provider)")
    }

    // MARK: - Account

    func deleteAccount() async throws {
        try await requestVoid("DELETE", path: "/user/account")
    }

    // MARK: - Orders

    func getOrders(status: String? = nil) async throws -> [Order] {
        var path = "/seller/orders"
        if let s = status { path += "?status=\(s)" }
        return try await request("GET", path: await sellerPath(path))
    }

    func getOrder(id: String) async throws -> Order {
        try await request("GET", path: await sellerPath("/seller/orders/\(id)"))
    }

    func acceptOrder(id: String) async throws -> Order {
        try await request("PATCH", path: await sellerPath("/seller/orders/\(id)/accept"))
    }

    func rejectOrder(id: String, reason: String? = nil) async throws -> Order {
        let body: [String: String]? = reason.map { ["reason": $0] }
        return try await request("PATCH", path: await sellerPath("/seller/orders/\(id)/reject"), body: body)
    }

    func markOrderPreparing(id: String) async throws -> Order {
        try await request("PATCH", path: await sellerPath("/seller/orders/\(id)/preparing"))
    }

    func markOrderReady(id: String) async throws -> Order {
        try await request("PATCH", path: await sellerPath("/seller/orders/\(id)/ready"))
    }

    /// Marks a pickup-fulfillment order completed when the customer arrives
    /// to collect it. No-op for delivery orders — the courier owns the
    /// picked_up → delivered transition. Backend's CompleteOrder handler
    /// enforces status='ready' so a misfire on a non-pickup order will 400.
    func markOrderCompleted(id: String) async throws -> Order {
        try await request("PATCH", path: await sellerPath("/seller/orders/\(id)/complete"))
    }

    // NOTE: Sellers no longer mark delivery orders delivered. Once
    // status == 'ready' on a delivery order, a courier claims it and drives
    // it through picked_up -> delivered. Pickup orders use markOrderCompleted
    // above instead.

    // MARK: - Dashboard

    func getDashboardStats() async throws -> DashboardStats {
        try await request("GET", path: await sellerPath("/seller/dashboard/stats"))
    }

    // MARK: - Uploads (menu item photos)

    struct PresignResponse: Decodable {
        let uploadUrl: String
        let publicUrl: String
        let key: String
        let expiresIn: Int

        enum CodingKeys: String, CodingKey {
            case key
            case uploadUrl = "upload_url"
            case publicUrl = "public_url"
            case expiresIn = "expires_in"
        }

        var isStub: Bool { uploadUrl.hasPrefix("stub://") }
    }

    func presignUpload(kind: String, contentType: String) async throws -> PresignResponse {
        struct Body: Encodable {
            let kind: String
            let contentType: String
            enum CodingKeys: String, CodingKey {
                case kind
                case contentType = "content_type"
            }
        }
        return try await request("POST", path: "/uploads/presign",
                                 body: Body(kind: kind, contentType: contentType))
    }

    // MARK: - Device tokens (push)

    func registerDevice(token: String, platform: String, app: String) async throws {
        struct Body: Encodable { let token: String; let platform: String; let app: String }
        try await requestVoid("POST", path: "/devices/register",
                              body: Body(token: token, platform: platform, app: app))
    }
}

// MARK: - Request Models

struct CreateMenuItemRequest: Encodable {
    let categoryId: String
    let name: String
    let description: String
    /// Cents — matches backend contract. Form converts dollars → cents before submit.
    let price: Int
    let imageUrl: String
    let isMeat: Bool
    let isDairy: Bool
    let isPareve: Bool
    let isAvailable: Bool

    enum CodingKeys: String, CodingKey {
        case name, description, price
        case categoryId = "category_id"
        case imageUrl = "image_url"
        case isMeat = "is_meat"
        case isDairy = "is_dairy"
        case isPareve = "is_pareve"
        case isAvailable = "is_available"
    }
}

// MARK: - Modifier group request payload
//
// Mirrors `handlers.ModifierGroupRequest` in the backend. `ModifierOptionRequest`
// reuses `id` as an optional marker: set it for existing options (update) or
// leave empty for new options (insert). The server reconciles the list
// against the stored options and deletes anything not referenced.

struct ModifierGroupRequest: Encodable {
    let name: String
    let description: String
    let isRequired: Bool
    let minSelections: Int
    let maxSelections: Int
    let sortOrder: Int
    let modifiers: [ModifierOptionRequest]

    enum CodingKeys: String, CodingKey {
        case name, description, modifiers
        case isRequired = "is_required"
        case minSelections = "min_selections"
        case maxSelections = "max_selections"
        case sortOrder = "sort_order"
    }
}

struct ModifierOptionRequest: Encodable {
    let id: String?
    let name: String
    let priceDelta: Int
    let isDefault: Bool
    let isAvailable: Bool
    let sortOrder: Int

    enum CodingKeys: String, CodingKey {
        case id, name
        case priceDelta = "price_delta"
        case isDefault = "is_default"
        case isAvailable = "is_available"
        case sortOrder = "sort_order"
    }
}

// MARK: - AnyEncodable

struct AnyEncodable: Encodable {
    private let _encode: (Encoder) throws -> Void

    init(_ wrapped: Encodable) {
        _encode = { encoder in
            try wrapped.encode(to: encoder)
        }
    }

    func encode(to encoder: Encoder) throws {
        try _encode(encoder)
    }
}
