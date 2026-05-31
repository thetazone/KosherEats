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
        case .httpError(_, let msg): return msg
        case .decodingError(let err): return "Decoding error: \(err.localizedDescription)"
        case .networkError(let err): return err.localizedDescription
        case .unauthorized: return "Please log in again"
        }
    }
}

@MainActor
final class APIService: ObservableObject {
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

    // Pointing DEBUG at prod Fly so simulator builds round-trip through the
    // same backend the consumer/seller apps use. Switch back to localhost when
    // running a local Docker stack with its own creds.
    #if DEBUG
    private var baseURL = "https://koshereats-api.fly.dev/api/v1"
    #else
    private var baseURL = "https://koshereats-api.fly.dev/api/v1"
    #endif

    private var token: String? {
        get { KeychainHelper.load(forKey: "courier_auth_token") }
        set {
            if let v = newValue { KeychainHelper.save(v, forKey: "courier_auth_token") }
            else { KeychainHelper.delete(forKey: "courier_auth_token") }
        }
    }

    private var refreshToken: String? {
        get { KeychainHelper.load(forKey: "courier_refresh_token") }
        set {
            if let v = newValue { KeychainHelper.save(v, forKey: "courier_refresh_token") }
            else { KeychainHelper.delete(forKey: "courier_refresh_token") }
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

    var isAuthenticated: Bool { token != nil }

    func setToken(_ token: String, refresh: String) {
        self.token = token
        self.refreshToken = refresh
    }

    func clearToken() {
        self.token = nil
        self.refreshToken = nil
    }

    // MARK: - Core request

    /// Retry once on 502/503/504. These come from Fly.io cold-starting the
    /// backend machine after idle — a claim/pickup call that fails here would
    /// otherwise leave the courier stuck on "Server error 503" with no recovery
    /// path but a manual retry. Matches the seller/consumer behavior.
    private let maxRetries = 1

    private func request<T: Decodable>(
        method: String,
        path: String,
        body: (any Encodable)? = nil,
        authenticated: Bool = true
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

        for attempt in 0...maxRetries {
            let (data, response): (Data, URLResponse)
            do {
                (data, response) = try await URLSession.shared.data(for: req)
            } catch {
                throw APIError.networkError(error)
            }

            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIError.invalidResponse
            }

            // Fly cold-start retry window.
            if [502, 503, 504].contains(httpResponse.statusCode), attempt < maxRetries {
                try? await Task.sleep(nanoseconds: 1_500_000_000) // 1.5s
                continue
            }

            if httpResponse.statusCode == 401 {
                // Try once to refresh with the stored refresh token; if it
                // works, replay the original request with the new access
                // token. Only do this when the caller was authenticated and
                // a refresh token is actually available, and break out of
                // the cold-start retry loop either way — the refresh path
                // has its own semantics.
                if authenticated, refreshToken != nil {
                    if await performTokenRefresh() {
                        return try await request(method: method, path: path, body: body, authenticated: authenticated)
                    }
                }
                throw APIError.unauthorized
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                let msg = (try? decoder.decode(APIErrorResponse.self, from: data))?.error ?? "Unknown error"
                throw APIError.httpError(httpResponse.statusCode, msg)
            }

            do {
                return try decoder.decode(T.self, from: data)
            } catch {
                throw APIError.decodingError(error)
            }
        }
        // Unreachable — the loop either returns or throws on every iteration.
        throw APIError.invalidResponse
    }

    private var refreshTask: Task<Bool, Never>?

    private func performTokenRefresh() async -> Bool {
        if let existing = refreshTask {
            return await existing.value
        }

        let task = Task {
            defer { self.refreshTask = nil }
            guard let refresh = self.refreshToken,
                  let url = URL(string: "\(self.baseURL)/auth/refresh") else { return false }

            struct RefreshBody: Encodable {
                let refreshToken: String
                enum CodingKeys: String, CodingKey { case refreshToken = "refresh_token" }
            }
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
            do {
                req.httpBody = try self.encoder.encode(RefreshBody(refreshToken: refresh))
            } catch {
                #if DEBUG
                print("[APIService] Failed to encode refresh body: \(error)")
                #endif
                return false
            }

            do {
                let (data, response) = try await URLSession.shared.data(for: req)
                guard let http = response as? HTTPURLResponse,
                      (200...299).contains(http.statusCode) else { return false }
                let decoded = try self.decoder.decode(RefreshResponse.self, from: data)
                self.setToken(decoded.token, refresh: decoded.refreshToken)
                return true
            } catch {
                return false
            }
        }
        refreshTask = task
        return await task.value
    }

    // MARK: - Void request
    //
    // Some endpoints (e.g. DELETE /user/account) return an empty body or a
    // non-decodable shape. We don't want to force a dummy struct on every
    // caller just to satisfy the generic.
    private func requestVoid(
        method: String,
        path: String,
        body: (any Encodable)? = nil,
        authenticated: Bool = true
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

        for attempt in 0...maxRetries {
            let (data, response): (Data, URLResponse)
            do {
                (data, response) = try await URLSession.shared.data(for: req)
            } catch {
                throw APIError.networkError(error)
            }

            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIError.invalidResponse
            }

            if [502, 503, 504].contains(httpResponse.statusCode), attempt < maxRetries {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                continue
            }

            if httpResponse.statusCode == 401 {
                if authenticated, refreshToken != nil {
                    if await performTokenRefresh() {
                        try await requestVoid(method: method, path: path, body: body, authenticated: authenticated)
                        return
                    }
                }
                throw APIError.unauthorized
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                let msg = (try? decoder.decode(APIErrorResponse.self, from: data))?.error ?? "Unknown error"
                throw APIError.httpError(httpResponse.statusCode, msg)
            }

            return
        }
        throw APIError.invalidResponse
    }

    // MARK: - Auth

    struct CourierRegisterBody: Encodable {
        let email: String
        let password: String
        let firstName: String
        let lastName: String
        let phone: String
        enum CodingKeys: String, CodingKey {
            case email, password, phone
            case firstName = "first_name"
            case lastName = "last_name"
        }
    }

    struct LoginBody: Encodable {
        let email: String
        let password: String
        // Backend uniqueness is now (email, role) — see migration 019. Sending
        // role="courier" makes the lookup find the courier-side account, not
        // a consumer account that happens to share the same email.
        let role: String
    }

    func register(email: String, password: String, firstName: String, lastName: String, phone: String) async throws -> AuthResponse {
        let body = CourierRegisterBody(email: email, password: password, firstName: firstName, lastName: lastName, phone: phone)
        let res: AuthResponse = try await request(method: "POST", path: "/courier/auth/register", body: body, authenticated: false)
        setToken(res.token, refresh: res.refreshToken)
        return res
    }

    func login(email: String, password: String) async throws -> AuthResponse {
        let body = LoginBody(email: email, password: password, role: "courier")
        let res: AuthResponse = try await request(method: "POST", path: "/auth/login", body: body, authenticated: false)
        guard res.user.role == "courier" else {
            clearToken()
            throw APIError.httpError(403, "This account is not a courier account.")
        }
        setToken(res.token, refresh: res.refreshToken)
        return res
    }

    func logout() { clearToken() }

    // MARK: - Email existence check (for unified email entry UI)

    struct EmailCheckBody: Encodable {
        let email: String
        let role: String
    }
    struct EmailCheckResponse: Decodable { let exists: Bool; let role: String }

    func checkEmail(_ email: String) async throws -> EmailCheckResponse {
        try await request(method: "POST", path: "/auth/email/check",
                          body: EmailCheckBody(email: email, role: "courier"), authenticated: false)
    }

    // MARK: - Phone OTP login
    //
    // Backend: Twilio Verify. /auth/phone/start triggers the SMS,
    // /auth/phone/verify trades a valid code for a JWT. Phone must be E.164
    // ("+15551234567"); the caller builds it from the country picker.
    //
    // The verify call sends role="courier" so the backend knows how to
    // (a) create a new courier account if this phone has never been seen,
    // and (b) promote an existing consumer → courier. Cross-role promotion
    // (seller ↔ courier) is refused server-side, so the role guard below
    // still blocks a seller's phone from slipping into the courier app.

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
            body: PhoneStartBody(phone: phone), authenticated: false)
    }

    func verifyPhoneLogin(phone: String, code: String,
                          firstName: String? = nil, lastName: String? = nil) async throws -> AuthResponse {
        let res: AuthResponse = try await request(
            method: "POST", path: "/auth/phone/verify",
            body: PhoneVerifyBody(phone: phone, code: code, role: "courier",
                                  firstName: firstName, lastName: lastName),
            authenticated: false)
        // Role guard still matters: the backend only promotes consumer →
        // courier, so a seller/admin phone will come back with a non-courier
        // role. Don't persist the token in that case — otherwise we'd land
        // on a cryptic 403 the moment the app hits /courier/*.
        guard res.user.role == "courier" else {
            throw APIError.httpError(403, "This phone number is not registered as a courier account.")
        }
        setToken(res.token, refresh: res.refreshToken)
        return res
    }

    // MARK: - Social auth

    struct SocialLoginBody: Encodable {
        let provider: String
        let token: String
        let firstName: String
        let lastName: String
        // Courier accounts get created with role=courier when the backend
        // doesn't find an existing user by email. Without this, the backend's
        // `/auth/social` handler falls back to `consumer` and the courier app
        // would then reject the login for role mismatch.
        let role: String
        let nonce: String?
        enum CodingKeys: String, CodingKey {
            case provider, token, role, nonce
            case firstName = "first_name"
            case lastName = "last_name"
        }
    }

    func socialLogin(provider: String, token: String, firstName: String, lastName: String, nonce: String? = nil) async throws -> AuthResponse {
        let body = SocialLoginBody(
            provider: provider, token: token,
            firstName: firstName, lastName: lastName, role: "courier", nonce: nonce
        )
        // The backend handles role promotion + auto-approval when `role:
        // "courier"` is sent from this app (see SocialLogin in
        // backend/internal/handlers/social_auth.go). No role guard here —
        // rejecting client-side would lock out the App Review tester's
        // Apple ID, which may already exist as a consumer.
        let res: AuthResponse = try await request(method: "POST", path: "/auth/social", body: body, authenticated: false)
        setToken(res.token, refresh: res.refreshToken)
        return res
    }

    // MARK: - Account deletion (App Store requirement)

    func deleteAccount() async throws {
        try await requestVoid(method: "DELETE", path: "/user/account")
    }

    // MARK: - User profile (generic)

    func getUser() async throws -> User {
        try await request(method: "GET", path: "/user/profile")
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

    func updateUserProfile(firstName: String, lastName: String, phone: String, email: String? = nil) async throws -> User {
        try await request(method: "PUT", path: "/user/profile",
            body: UpdateProfileBody(firstName: firstName, lastName: lastName, phone: phone, email: email))
    }

    // MARK: - Courier profile / onboarding

    func getProfile() async throws -> CourierProfile {
        try await request(method: "GET", path: "/courier/profile")
    }

    struct PhoneVerifyCodeBody: Encodable { let code: String }

    func verifyPhone(code: String) async throws {
        let _: [String: Bool] = try await request(method: "POST", path: "/courier/onboarding/phone/verify", body: PhoneVerifyCodeBody(code: code))
    }

    struct VehicleBody: Encodable {
        let vehicleType: String
        let vehicleMake: String
        let vehicleModel: String
        let vehicleYear: Int
        let vehicleColor: String
        let licensePlate: String
        enum CodingKeys: String, CodingKey {
            case vehicleType = "vehicle_type"
            case vehicleMake = "vehicle_make"
            case vehicleModel = "vehicle_model"
            case vehicleYear = "vehicle_year"
            case vehicleColor = "vehicle_color"
            case licensePlate = "license_plate"
        }
    }

    func updateVehicle(_ body: VehicleBody) async throws -> CourierProfile {
        try await request(method: "PUT", path: "/courier/onboarding/vehicle", body: body)
    }

    struct DocumentsBody: Encodable {
        let driversLicenseUrl: String
        let driversLicenseNumber: String
        let insuranceUrl: String
        let vehicleRegistrationUrl: String
        let profilePhotoUrl: String
        enum CodingKeys: String, CodingKey {
            case driversLicenseUrl = "drivers_license_url"
            case driversLicenseNumber = "drivers_license_number"
            case insuranceUrl = "insurance_url"
            case vehicleRegistrationUrl = "vehicle_registration_url"
            case profilePhotoUrl = "profile_photo_url"
        }
    }

    func updateDocuments(_ body: DocumentsBody) async throws -> CourierProfile {
        try await request(method: "PUT", path: "/courier/onboarding/documents", body: body)
    }

    // MARK: - Live state

    struct OnlineBody: Encodable { let online: Bool; let lat: Double; let lng: Double }

    func setOnline(_ online: Bool, lat: Double, lng: Double) async throws {
        let _: [String: Bool] = try await request(method: "POST", path: "/courier/online",
                                                  body: OnlineBody(online: online, lat: lat, lng: lng))
    }

    struct LocationBody: Encodable {
        let lat: Double; let lng: Double; let heading: Double?; let speed: Double
    }

    func sendLocation(lat: Double, lng: Double, heading: Double? = nil, speed: Double = 0) async throws {
        let _: [String: String] = try await request(method: "POST", path: "/courier/location",
                                                    body: LocationBody(lat: lat, lng: lng, heading: heading, speed: speed))
    }

    // MARK: - Deliveries

    func listAvailable() async throws -> [AvailableDelivery] {
        let res: AvailableDeliveriesResponse = try await request(method: "GET", path: "/courier/deliveries/available")
        return res.deliveries
    }

    /// Orders the seller has accepted but not yet marked ready. Couriers can't
    /// claim these yet (the backend's claim endpoint enforces status='ready'),
    /// but seeing them lets a courier head toward the restaurant ahead of the
    /// kitchen finishing — cuts dwell time at pickup.
    func listUpcoming() async throws -> [AvailableDelivery] {
        let res: AvailableDeliveriesResponse = try await request(method: "GET", path: "/courier/deliveries/upcoming")
        return res.deliveries
    }

    func listActive() async throws -> [CourierOrder] {
        let res: CourierOrdersResponse = try await request(method: "GET", path: "/courier/orders/active")
        return res.orders
    }

    func listHistory() async throws -> [HistoryOrder] {
        let res: HistoryResponse = try await request(method: "GET", path: "/courier/orders/history")
        return res.orders
    }

    struct EmptyBody: Encodable {}

    private func validateOrderId(_ id: String) throws {
        guard id.range(of: "^[a-zA-Z0-9_-]+$", options: .regularExpression) != nil else {
            throw APIError.invalidURL
        }
    }

    func claim(orderId: String) async throws {
        try validateOrderId(orderId)
        let _: [String: String] = try await request(method: "POST", path: "/courier/orders/\(orderId)/claim", body: EmptyBody())
    }

    func pickup(orderId: String) async throws {
        try validateOrderId(orderId)
        let _: [String: String] = try await request(method: "POST", path: "/courier/orders/\(orderId)/pickup", body: EmptyBody())
    }

    func deliver(orderId: String, proofURL: String? = nil) async throws {
        try validateOrderId(orderId)
        struct DeliverBody: Encodable {
            let proofUrl: String?
            enum CodingKeys: String, CodingKey { case proofUrl = "proof_url" }
        }
        let _: [String: String] = try await request(method: "POST", path: "/courier/orders/\(orderId)/deliver",
                                                      body: DeliverBody(proofUrl: proofURL))
    }

    // MARK: - Device tokens (push)

    struct DeviceBody: Encodable { let token: String; let platform: String; let app: String }

    func registerDevice(token: String, platform: String, app: String) async throws {
        let _: [String: String] = try await request(method: "POST", path: "/devices/register",
                                                    body: DeviceBody(token: token, platform: platform, app: app))
    }

    // MARK: - Payouts (Stripe Connect)

    struct PayoutStatus: Decodable {
        let payoutReady: Bool
        let connectId: String?
        let detailsSubmitted: Bool

        enum CodingKeys: String, CodingKey {
            case payoutReady = "payout_ready"
            case connectId = "connect_id"
            case detailsSubmitted = "details_submitted"
        }
    }

    struct PayoutLink: Decodable { let url: String }

    func createPayoutAccount() async throws -> PayoutStatus {
        try await request(method: "POST", path: "/courier/payouts/account", body: EmptyBody())
    }

    func getPayoutStatus() async throws -> PayoutStatus {
        try await request(method: "GET", path: "/courier/payouts/status")
    }

    func getPayoutLink() async throws -> PayoutLink {
        try await request(method: "GET", path: "/courier/payouts/link")
    }

    // MARK: - Uploads

    struct PresignBody: Encodable {
        let kind: String
        let contentType: String
        enum CodingKeys: String, CodingKey {
            case kind
            case contentType = "content_type"
        }
    }

    func presignUpload(kind: String, contentType: String) async throws -> UploadService.PresignResponse {
        try await request(method: "POST", path: "/uploads/presign",
                          body: PresignBody(kind: kind, contentType: contentType))
    }

    // MARK: - Chat

    func listChatMessages(orderID: String) async throws -> [ChatMessage] {
        try validateOrderId(orderID)
        return try await request(method: "GET", path: "/orders/\(orderID)/chat")
    }

    func sendChatMessage(orderID: String, text: String) async throws -> ChatMessage {
        try validateOrderId(orderID)
        struct Body: Encodable { let text: String }
        return try await request(method: "POST", path: "/orders/\(orderID)/chat",
                                 body: Body(text: text))
    }
}
