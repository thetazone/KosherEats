import Foundation

// MARK: - Auth

struct User: Codable, Identifiable {
    let id: String
    let email: String
    let firstName: String
    let lastName: String
    let phone: String
    let role: String

    enum CodingKeys: String, CodingKey {
        case id, email, phone, role
        case firstName = "first_name"
        case lastName = "last_name"
    }
}

struct AuthResponse: Codable {
    let token: String
    let refreshToken: String
    let user: User

    enum CodingKeys: String, CodingKey {
        case token, user
        case refreshToken = "refresh_token"
    }
}

struct APIErrorResponse: Codable {
    let error: String
}

// MARK: - Courier Profile

enum OnboardingStatus: String, Codable {
    case pendingInfo = "pending_info"
    case pendingDocuments = "pending_documents"
    case pendingBackground = "pending_background"
    case approved
    case rejected
    case suspended
}

enum VehicleType: String, Codable, CaseIterable, Identifiable {
    case car, bike, scooter, motorcycle, walk
    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .car: return "Car"
        case .bike: return "Bike"
        case .scooter: return "Scooter"
        case .motorcycle: return "Motorcycle"
        case .walk: return "On foot"
        }
    }

    var sfSymbol: String {
        switch self {
        case .car: return "car.fill"
        case .bike: return "bicycle"
        case .scooter: return "scooter"
        case .motorcycle: return "bicycle"
        case .walk: return "figure.walk"
        }
    }
}

struct CourierProfile: Codable {
    let id: String
    let userId: String
    let onboardingStatus: OnboardingStatus
    let phoneVerified: Bool
    let vehicleType: String
    let vehicleMake: String
    let vehicleModel: String
    let vehicleYear: Int
    let vehicleColor: String
    let licensePlate: String
    let backgroundCheckStatus: String
    let payoutReady: Bool
    let isOnline: Bool
    let lastLat: Double
    let lastLng: Double
    let totalDeliveries: Int
    let rating: Double

    enum CodingKeys: String, CodingKey {
        case id
        case userId = "user_id"
        case onboardingStatus = "onboarding_status"
        case phoneVerified = "phone_verified"
        case vehicleType = "vehicle_type"
        case vehicleMake = "vehicle_make"
        case vehicleModel = "vehicle_model"
        case vehicleYear = "vehicle_year"
        case vehicleColor = "vehicle_color"
        case licensePlate = "license_plate"
        case backgroundCheckStatus = "background_check_status"
        case payoutReady = "payout_ready"
        case isOnline = "is_online"
        case lastLat = "last_lat"
        case lastLng = "last_lng"
        case totalDeliveries = "total_deliveries"
        case rating
    }
}

// MARK: - Orders

struct AvailableDelivery: Codable, Identifiable {
    let id: String
    let restaurantId: String
    let restaurantName: String
    let status: String
    let total: Int
    let deliveryFee: Int
    let deliveryAddress: String
    let deliveryLat: Double
    let deliveryLng: Double
    let restaurantLat: Double
    let restaurantLng: Double
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case id, status, total
        case restaurantId = "restaurant_id"
        case restaurantName = "restaurant_name"
        case deliveryFee = "delivery_fee"
        case deliveryAddress = "delivery_address"
        case deliveryLat = "delivery_lat"
        case deliveryLng = "delivery_lng"
        case restaurantLat = "restaurant_lat"
        case restaurantLng = "restaurant_lng"
        case createdAt = "created_at"
    }
}

struct AvailableDeliveriesResponse: Codable {
    let deliveries: [AvailableDelivery]
}

struct CourierOrder: Codable, Identifiable {
    let id: String
    let userId: String
    let restaurantId: String
    let restaurantName: String
    let status: String
    let total: Int
    let deliveryFee: Int
    let deliveryAddress: String
    let deliveryLat: Double
    let deliveryLng: Double
    let claimedAt: Date?
    let pickedUpAt: Date?
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case id, status, total
        case userId = "user_id"
        case restaurantId = "restaurant_id"
        case restaurantName = "restaurant_name"
        case deliveryFee = "delivery_fee"
        case deliveryAddress = "delivery_address"
        case deliveryLat = "delivery_lat"
        case deliveryLng = "delivery_lng"
        case claimedAt = "claimed_at"
        case pickedUpAt = "picked_up_at"
        case createdAt = "created_at"
    }
}

struct CourierOrdersResponse: Codable {
    let orders: [CourierOrder]
}

struct HistoryOrder: Codable, Identifiable {
    let id: String
    let restaurantName: String
    let total: Int
    let deliveryFee: Int
    let courierTip: Int
    let courierPayout: Int
    let deliveredAt: String?

    enum CodingKeys: String, CodingKey {
        case id, total
        case restaurantName = "restaurant_name"
        case deliveryFee = "delivery_fee"
        case courierTip = "courier_tip"
        case courierPayout = "courier_payout"
        case deliveredAt = "delivered_at"
    }
}

struct HistoryResponse: Codable {
    let orders: [HistoryOrder]
}
