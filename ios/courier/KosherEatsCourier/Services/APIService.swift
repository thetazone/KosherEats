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

    #if DEBUG
    private var baseURL = "http://localhost:8080/api/v1"
    #else
    private var baseURL = "https://koshereats-api.fly.dev/api/v1"
    #endif

    private var token: String? {
        get { UserDefaults.standard.string(forKey: "courier_auth_token") }
        set { UserDefaults.standard.set(newValue, forKey: "courier_auth_token") }
    }

    private var refreshToken: String? {
        get { UserDefaults.standard.string(forKey: "courier_refresh_token") }
        set { UserDefaults.standard.set(newValue, forKey: "courier_refresh_token") }
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

    struct LoginBody: Encodable { let email: String; let password: String }

    func register(email: String, password: String, firstName: String, lastName: String, phone: String) async throws -> AuthResponse {
        let body = CourierRegisterBody(email: email, password: password, firstName: firstName, lastName: lastName, phone: phone)
        let res: AuthResponse = try await request(method: "POST", path: "/courier/auth/register", body: body, authenticated: false)
        setToken(res.token, refresh: res.refreshToken)
        return res
    }

    func login(email: String, password: String) async throws -> AuthResponse {
        let body = LoginBody(email: email, password: password)
        let res: AuthResponse = try await request(method: "POST", path: "/auth/login", body: body, authenticated: false)
        guard res.user.role == "courier" else {
            clearToken()
            throw APIError.httpError(403, "This account is not a courier account.")
        }
        setToken(res.token, refresh: res.refreshToken)
        return res
    }

    func logout() { clearToken() }

    // MARK: - Courier profile / onboarding

    func getProfile() async throws -> CourierProfile {
        try await request(method: "GET", path: "/courier/profile")
    }

    func verifyPhone() async throws {
        struct Empty: Encodable {}
        let _: [String: Bool] = try await request(method: "POST", path: "/courier/onboarding/phone/verify", body: Empty())
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
        let lat: Double; let lng: Double; let heading: Double; let speed: Double
    }

    func sendLocation(lat: Double, lng: Double, heading: Double = 0, speed: Double = 0) async throws {
        let _: [String: String] = try await request(method: "POST", path: "/courier/location",
                                                    body: LocationBody(lat: lat, lng: lng, heading: heading, speed: speed))
    }

    // MARK: - Deliveries

    func listAvailable() async throws -> [AvailableDelivery] {
        let res: AvailableDeliveriesResponse = try await request(method: "GET", path: "/courier/deliveries/available")
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

    func claim(orderId: String) async throws {
        let _: [String: String] = try await request(method: "POST", path: "/courier/orders/\(orderId)/claim", body: EmptyBody())
    }

    func pickup(orderId: String) async throws {
        let _: [String: String] = try await request(method: "POST", path: "/courier/orders/\(orderId)/pickup", body: EmptyBody())
    }

    func deliver(orderId: String) async throws {
        let _: [String: String] = try await request(method: "POST", path: "/courier/orders/\(orderId)/deliver", body: EmptyBody())
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
        try await request(method: "GET", path: "/orders/\(orderID)/chat")
    }

    func sendChatMessage(orderID: String, text: String) async throws -> ChatMessage {
        struct Body: Encodable { let text: String }
        return try await request(method: "POST", path: "/orders/\(orderID)/chat",
                                 body: Body(text: text))
    }
}
