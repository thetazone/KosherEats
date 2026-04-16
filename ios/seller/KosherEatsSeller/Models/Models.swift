import Foundation

// MARK: - Enums

enum UserRole: String, Codable {
    case consumer, seller, admin
}

enum KosherCertification: String, Codable, CaseIterable, Identifiable {
    case OU, OK
    case KofK = "Kof-K"
    case StarK = "Star-K"
    case cRc
    case Badatz
    case ChofK = "Chof-K"
    case other

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .OU: return "OU"
        case .OK: return "OK"
        case .KofK: return "Kof-K"
        case .StarK: return "Star-K"
        case .cRc: return "cRc"
        case .Badatz: return "Badatz"
        case .ChofK: return "Chof-K"
        case .other: return "Other"
        }
    }
}

enum OrderStatus: String, Codable, CaseIterable, Identifiable {
    case scheduled, pending, accepted, preparing, ready
    case pickedUp = "picked_up"
    case delivered, cancelled, rejected

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .scheduled: return "Scheduled"
        case .pending: return "Pending"
        case .accepted: return "Accepted"
        case .preparing: return "Preparing"
        case .ready: return "Ready"
        case .pickedUp: return "Picked Up"
        case .delivered: return "Delivered"
        case .cancelled: return "Cancelled"
        case .rejected: return "Rejected"
        }
    }

    var icon: String {
        switch self {
        case .scheduled: return "clock.badge"
        case .pending: return "clock"
        case .accepted: return "checkmark.circle"
        case .preparing: return "flame"
        case .ready: return "bag.fill"
        case .pickedUp: return "car"
        case .delivered: return "checkmark.seal.fill"
        case .cancelled: return "xmark.circle"
        case .rejected: return "xmark.octagon"
        }
    }

    var color: String {
        switch self {
        case .scheduled, .pending: return "warning"
        case .accepted, .preparing: return "primary"
        case .ready: return "success"
        case .pickedUp, .delivered: return "success"
        case .cancelled, .rejected: return "error"
        }
    }

    var isActive: Bool {
        switch self {
        case .scheduled, .pending, .accepted, .preparing, .ready:
            return true
        default:
            return false
        }
    }
}

// MARK: - Models

struct User: Codable, Identifiable {
    let id: String
    let email: String
    let firstName: String
    let lastName: String
    let phone: String
    let role: UserRole
    let avatarUrl: String?
    let createdAt: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case id, email, phone, role
        case firstName = "first_name"
        case lastName = "last_name"
        case avatarUrl = "avatar_url"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }

    var fullName: String { "\(firstName) \(lastName)" }
}

struct Restaurant: Codable, Identifiable {
    let id: String
    let ownerId: String
    var name: String
    var description: String
    var imageUrl: String
    var coverImageUrl: String?
    var phone: String
    var email: String
    var street: String
    var city: String
    var state: String
    var zipCode: String
    var lat: Double
    var lng: Double
    var kosherCertification: KosherCertification
    var certifyingAgency: String
    var isCholovYisroel: Bool
    var isPasYisroel: Bool
    var isGlattKosher: Bool
    var cuisineType: [String]
    var rating: Double
    var reviewCount: Int
    var deliveryFee: Double
    var minOrder: Double
    var estDeliveryMin: Int
    var estDeliveryMax: Int
    var isOpen: Bool
    var isActive: Bool

    enum CodingKeys: String, CodingKey {
        case id, name, description, phone, email, street, city, state, lat, lng, rating
        case ownerId = "owner_id"
        case imageUrl = "image_url"
        case coverImageUrl = "cover_image_url"
        case zipCode = "zip_code"
        case kosherCertification = "kosher_certification"
        case certifyingAgency = "certifying_agency"
        case isCholovYisroel = "is_cholov_yisroel"
        case isPasYisroel = "is_pas_yisroel"
        case isGlattKosher = "is_glatt_kosher"
        case cuisineType = "cuisine_type"
        case reviewCount = "review_count"
        case deliveryFee = "delivery_fee"
        case minOrder = "min_order"
        case estDeliveryMin = "est_delivery_min"
        case estDeliveryMax = "est_delivery_max"
        case isOpen = "is_open"
        case isActive = "is_active"
    }
}

struct MenuCategory: Codable, Identifiable {
    let id: String
    let restaurantId: String
    var name: String
    var sortOrder: Int
    var items: [MenuItem]?

    enum CodingKeys: String, CodingKey {
        case id, name, items
        case restaurantId = "restaurant_id"
        case sortOrder = "sort_order"
    }
}

struct MenuItem: Codable, Identifiable, Equatable {
    let id: String
    let restaurantId: String
    var categoryId: String
    var name: String
    var description: String
    var imageUrl: String?
    /// Cents (matches backend). Divide by 100 at display via priceFormatted.
    var price: Int
    var isMeat: Bool
    var isDairy: Bool
    var isPareve: Bool
    var isAvailable: Bool

    var priceFormatted: String { String(format: "$%.2f", Double(price) / 100) }

    enum CodingKeys: String, CodingKey {
        case id, name, description, price
        case restaurantId = "restaurant_id"
        case categoryId = "category_id"
        case imageUrl = "image_url"
        case isMeat = "is_meat"
        case isDairy = "is_dairy"
        case isPareve = "is_pareve"
        case isAvailable = "is_available"
    }

    var kosherTag: String {
        if isMeat { return "Meat" }
        if isDairy { return "Dairy" }
        if isPareve { return "Pareve" }
        return "Unset"
    }

    static func == (lhs: MenuItem, rhs: MenuItem) -> Bool {
        lhs.id == rhs.id
    }
}

struct CourierPublic: Codable {
    let id: String
    let firstName: String
    let phone: String
    let avatarUrl: String?
    let vehicleType: String
    let vehicleMake: String?
    let vehicleModel: String?
    let vehicleColor: String?
    let licensePlate: String?
    let rating: Double
    let totalDeliveries: Int
    let lat: Double
    let lng: Double

    enum CodingKeys: String, CodingKey {
        case id, phone, rating, lat, lng
        case firstName = "first_name"
        case avatarUrl = "avatar_url"
        case vehicleType = "vehicle_type"
        case vehicleMake = "vehicle_make"
        case vehicleModel = "vehicle_model"
        case vehicleColor = "vehicle_color"
        case licensePlate = "license_plate"
        case totalDeliveries = "total_deliveries"
    }

    var vehicleSummary: String {
        let parts = [vehicleColor, vehicleMake, vehicleModel].compactMap { $0 }.filter { !$0.isEmpty }
        return parts.isEmpty ? vehicleType.capitalized : parts.joined(separator: " ")
    }
}

struct Order: Codable, Identifiable {
    let id: String
    let userId: String
    let restaurantId: String
    let restaurantName: String
    var status: OrderStatus
    let items: [OrderItem]
    // All money fields are cents (matches backend + consumer app). Divide by
    // 100 at display time via the *Formatted computed properties below.
    let subtotal: Int
    let deliveryFee: Int
    let serviceFee: Int
    let tax: Int
    let total: Int
    let deliveryAddress: String
    let estDeliveryTime: String?
    let createdAt: String
    let updatedAt: String
    let courier: CourierPublic?

    enum CodingKeys: String, CodingKey {
        case id, status, items, subtotal, tax, total, courier
        case userId = "user_id"
        case restaurantId = "restaurant_id"
        case restaurantName = "restaurant_name"
        case deliveryFee = "delivery_fee"
        case serviceFee = "service_fee"
        case deliveryAddress = "delivery_address"
        case estDeliveryTime = "est_delivery_time"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }

    /// Dollars display for the total. Every UI using $%.2f on order.total
    /// should use this instead.
    var totalFormatted: String { String(format: "$%.2f", Double(total) / 100) }
    var subtotalFormatted: String { String(format: "$%.2f", Double(subtotal) / 100) }
    var deliveryFeeFormatted: String { String(format: "$%.2f", Double(deliveryFee) / 100) }
    var serviceFeeFormatted: String { String(format: "$%.2f", Double(serviceFee) / 100) }
    var taxFormatted: String { String(format: "$%.2f", Double(tax) / 100) }

    var formattedDate: String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: createdAt) else {
            // Try without fractional seconds
            formatter.formatOptions = [.withInternetDateTime]
            guard let date = formatter.date(from: createdAt) else { return createdAt }
            return Self.displayFormatter.string(from: date)
        }
        return Self.displayFormatter.string(from: date)
    }

    private static let displayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        return f
    }()

    var itemCount: Int {
        items.reduce(0) { $0 + $1.quantity }
    }
}

struct OrderItem: Codable, Identifiable {
    let id: String
    let orderId: String
    let menuItemId: String
    let name: String
    let price: Int // cents per-unit, includes modifier deltas
    let quantity: Int
    let notes: String?
    let selectedModifiers: [SelectedModifier]?

    var lineTotalFormatted: String {
        String(format: "$%.2f", Double(price * quantity) / 100)
    }

    var modifierSummary: String? {
        guard let m = selectedModifiers, !m.isEmpty else { return nil }
        return m.map(\.name).joined(separator: " • ")
    }

    enum CodingKeys: String, CodingKey {
        case id, name, price, quantity, notes
        case orderId = "order_id"
        case menuItemId = "menu_item_id"
        case selectedModifiers = "selected_modifiers"
    }
}

/// Snapshot of a modifier selection on an order item. Matches the backend
/// JSONB shape so the seller can see "Large + Extra hummus" on each line.
struct SelectedModifier: Codable, Hashable, Identifiable {
    let id: String
    let groupName: String
    let name: String
    let priceDelta: Int

    enum CodingKeys: String, CodingKey {
        case id, name
        case groupName = "group_name"
        case priceDelta = "price_delta"
    }
}

// MARK: - Auth Response

struct AuthResponse: Codable {
    let token: String
    let refreshToken: String
    let user: User

    enum CodingKeys: String, CodingKey {
        case token, user
        case refreshToken = "refresh_token"
    }
}

// MARK: - Dashboard Stats

struct DashboardStats: Codable {
    var todayOrders: Int = 0
    /// Cents. Divide by 100 to display.
    var todayRevenue: Int = 0
    var activeOrders: Int = 0
    /// Minutes, averaged across today's delivered orders.
    var avgPrepTime: Double = 0

    enum CodingKeys: String, CodingKey {
        case todayOrders = "today_orders"
        case todayRevenue = "today_revenue"
        case activeOrders = "active_orders"
        case avgPrepTime = "avg_prep_time"
    }
}
