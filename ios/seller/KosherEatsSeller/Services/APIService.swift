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
    private var token: String?

    private init() {
        #if DEBUG
        self.baseURL = "http://localhost:8080/api/v1"
        #else
        self.baseURL = "https://api.koshereats.com/api/v1"
        #endif

        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()
        self.encoder.keyEncodingStrategy = .convertToSnakeCase
    }

    func setToken(_ token: String?) {
        self.token = token
    }

    // MARK: - Core Request

    private func request<T: Decodable>(
        _ method: String,
        path: String,
        body: Encodable? = nil
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

        if let body = body {
            req.httpBody = try encoder.encode(AnyEncodable(body))
        }

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw APIError.networkError(error.localizedDescription)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.networkError("Invalid response")
        }

        if httpResponse.statusCode == 401 {
            throw APIError.unauthorized
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorMsg = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "Unknown error"
            throw APIError.serverError(httpResponse.statusCode, errorMsg)
        }

        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIError.decodingError(error.localizedDescription)
        }
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

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch {
            throw APIError.networkError(error.localizedDescription)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.networkError("Invalid response")
        }

        if httpResponse.statusCode == 401 {
            throw APIError.unauthorized
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let errorMsg = (try? JSONDecoder().decode([String: String].self, from: data))?["error"] ?? "Unknown error"
            throw APIError.serverError(httpResponse.statusCode, errorMsg)
        }
    }

    // MARK: - Auth

    func login(email: String, password: String) async throws -> AuthResponse {
        let body = ["email": email, "password": password]
        return try await request("POST", path: "/auth/login", body: body)
    }

    // MARK: - Restaurant

    func getRestaurant() async throws -> Restaurant {
        try await request("GET", path: "/seller/restaurant")
    }

    func updateRestaurant(_ data: Restaurant) async throws -> Restaurant {
        try await request("PUT", path: "/seller/restaurant", body: data)
    }

    func toggleOpen(_ isOpen: Bool) async throws -> Restaurant {
        try await request("PATCH", path: "/seller/restaurant/status", body: ["is_open": isOpen])
    }

    // MARK: - Menu

    func getMenu() async throws -> [MenuCategory] {
        try await request("GET", path: "/seller/menu")
    }

    func createMenuItem(_ item: CreateMenuItemRequest) async throws -> MenuItem {
        try await request("POST", path: "/seller/menu/items", body: item)
    }

    func updateMenuItem(id: String, _ item: CreateMenuItemRequest) async throws -> MenuItem {
        try await request("PUT", path: "/seller/menu/items/\(id)", body: item)
    }

    func deleteMenuItem(id: String) async throws {
        try await requestVoid("DELETE", path: "/seller/menu/items/\(id)")
    }

    func toggleItemAvailability(id: String, available: Bool) async throws -> MenuItem {
        try await request("PATCH", path: "/seller/menu/items/\(id)/availability", body: ["is_available": available])
    }

    func createCategory(_ name: String) async throws -> MenuCategory {
        try await request("POST", path: "/seller/menu/categories", body: ["name": name])
    }

    func deleteCategory(id: String) async throws {
        try await requestVoid("DELETE", path: "/seller/menu/categories/\(id)")
    }

    // MARK: - Orders

    func getOrders(status: String? = nil) async throws -> [Order] {
        let path = status != nil ? "/seller/orders?status=\(status!)" : "/seller/orders"
        return try await request("GET", path: path)
    }

    func getOrder(id: String) async throws -> Order {
        try await request("GET", path: "/seller/orders/\(id)")
    }

    func acceptOrder(id: String) async throws -> Order {
        try await request("PATCH", path: "/seller/orders/\(id)/accept")
    }

    func rejectOrder(id: String, reason: String? = nil) async throws -> Order {
        let body: [String: String]? = reason != nil ? ["reason": reason!] : nil
        return try await request("PATCH", path: "/seller/orders/\(id)/reject", body: body)
    }

    func markOrderReady(id: String) async throws -> Order {
        try await request("PATCH", path: "/seller/orders/\(id)/ready")
    }

    func completeOrder(id: String) async throws -> Order {
        try await request("PATCH", path: "/seller/orders/\(id)/complete")
    }

    // MARK: - Dashboard

    func getDashboardStats() async throws -> DashboardStats {
        try await request("GET", path: "/seller/dashboard/stats")
    }
}

// MARK: - Request Models

struct CreateMenuItemRequest: Encodable {
    let categoryId: String
    let name: String
    let description: String
    let price: Double
    let imageUrl: String?
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
