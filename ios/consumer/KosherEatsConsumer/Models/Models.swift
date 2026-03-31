import Foundation

// MARK: - Enums

enum UserRole: String, Codable {
    case consumer
    case seller
    case admin
}

enum KosherCertification: String, Codable, CaseIterable {
    case ou = "OU"
    case ok = "OK"
    case kofK = "Kof-K"
    case starK = "Star-K"
    case crc = "cRc"
    case badatz = "Badatz"
    case chofK = "Chof-K"
    case other

    var displayName: String { rawValue }

    var symbolName: String {
        switch self {
        case .ou: return "checkmark.seal.fill"
        case .ok: return "checkmark.seal.fill"
        case .kofK: return "checkmark.seal.fill"
        case .starK: return "star.fill"
        case .crc: return "checkmark.seal.fill"
        case .badatz: return "checkmark.seal.fill"
        case .chofK: return "checkmark.seal.fill"
        case .other: return "checkmark.seal"
        }
    }
}

enum OrderStatus: String, Codable {
    case pending
    case accepted
    case preparing
    case ready
    case pickedUp = "picked_up"
    case delivered
    case cancelled
    case rejected

    var displayName: String {
        switch self {
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

    var iconName: String {
        switch self {
        case .pending: return "clock.fill"
        case .accepted: return "checkmark.circle.fill"
        case .preparing: return "flame.fill"
        case .ready: return "bag.fill"
        case .pickedUp: return "car.fill"
        case .delivered: return "house.fill"
        case .cancelled: return "xmark.circle.fill"
        case .rejected: return "exclamationmark.circle.fill"
        }
    }

    var isActive: Bool {
        switch self {
        case .pending, .accepted, .preparing, .ready, .pickedUp:
            return true
        default:
            return false
        }
    }

    var stepIndex: Int {
        switch self {
        case .pending: return 0
        case .accepted: return 1
        case .preparing: return 2
        case .ready: return 3
        case .pickedUp: return 4
        case .delivered: return 5
        case .cancelled, .rejected: return -1
        }
    }
}

// MARK: - User

struct User: Codable, Identifiable {
    let id: String
    var email: String
    var firstName: String
    var lastName: String
    var phone: String
    var role: UserRole
    var avatarURL: String?
    var createdAt: Date
    var updatedAt: Date

    var fullName: String { "\(firstName) \(lastName)" }

    enum CodingKeys: String, CodingKey {
        case id, email, phone, role
        case firstName = "first_name"
        case lastName = "last_name"
        case avatarURL = "avatar_url"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

// MARK: - Address

struct Address: Codable, Identifiable {
    let id: String
    var userID: String
    var label: String
    var street: String
    var apt: String?
    var city: String
    var state: String
    var zipCode: String
    var lat: Double
    var lng: Double
    var isDefault: Bool

    var formatted: String {
        let aptStr = apt.map { ", \($0)" } ?? ""
        return "\(street)\(aptStr), \(city), \(state) \(zipCode)"
    }

    enum CodingKeys: String, CodingKey {
        case id, label, street, apt, city, state, lat, lng
        case userID = "user_id"
        case zipCode = "zip_code"
        case isDefault = "is_default"
    }
}

// MARK: - Restaurant

struct Restaurant: Codable, Identifiable {
    let id: String
    var ownerID: String
    var name: String
    var description: String
    var imageURL: String
    var coverImageURL: String?
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
    var deliveryFee: Int
    var minOrder: Int
    var estDeliveryMin: Int
    var estDeliveryMax: Int
    var isOpen: Bool
    var isActive: Bool
    var createdAt: Date
    var updatedAt: Date

    var deliveryFeeFormatted: String {
        deliveryFee == 0 ? "Free Delivery" : "$\(String(format: "%.2f", Double(deliveryFee) / 100))"
    }

    var minOrderFormatted: String {
        "$\(String(format: "%.0f", Double(minOrder) / 100))"
    }

    var deliveryTimeFormatted: String {
        "\(estDeliveryMin)-\(estDeliveryMax) min"
    }

    var ratingFormatted: String {
        String(format: "%.1f", rating)
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description, phone, email, street, city, state, lat, lng, rating
        case ownerID = "owner_id"
        case imageURL = "image_url"
        case coverImageURL = "cover_image_url"
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
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

// MARK: - Menu

struct MenuCategory: Codable, Identifiable {
    let id: String
    var restaurantID: String
    var name: String
    var sortOrder: Int
    var items: [MenuItem]?

    enum CodingKeys: String, CodingKey {
        case id, name, items
        case restaurantID = "restaurant_id"
        case sortOrder = "sort_order"
    }
}

struct MenuItem: Codable, Identifiable {
    let id: String
    var restaurantID: String
    var categoryID: String
    var name: String
    var description: String
    var imageURL: String?
    var price: Int // cents
    var isMeat: Bool
    var isDairy: Bool
    var isPareve: Bool
    var isAvailable: Bool
    var sortOrder: Int

    var priceFormatted: String {
        "$\(String(format: "%.2f", Double(price) / 100))"
    }

    var kashrusType: String {
        if isMeat { return "Meat" }
        if isDairy { return "Dairy" }
        if isPareve { return "Pareve" }
        return ""
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description, price
        case restaurantID = "restaurant_id"
        case categoryID = "category_id"
        case imageURL = "image_url"
        case isMeat = "is_meat"
        case isDairy = "is_dairy"
        case isPareve = "is_pareve"
        case isAvailable = "is_available"
        case sortOrder = "sort_order"
    }
}

// MARK: - Cart

struct Cart: Codable, Identifiable {
    let id: String
    var userID: String
    var restaurantID: String
    var items: [CartItem]
    var subtotal: Int

    var subtotalFormatted: String {
        "$\(String(format: "%.2f", Double(subtotal) / 100))"
    }

    var itemCount: Int {
        items.reduce(0) { $0 + $1.quantity }
    }

    enum CodingKeys: String, CodingKey {
        case id, items, subtotal
        case userID = "user_id"
        case restaurantID = "restaurant_id"
    }
}

struct CartItem: Codable, Identifiable {
    let id: String
    var cartID: String
    var menuItemID: String
    var name: String
    var price: Int
    var quantity: Int
    var notes: String?

    var totalFormatted: String {
        "$\(String(format: "%.2f", Double(price * quantity) / 100))"
    }

    enum CodingKeys: String, CodingKey {
        case id, name, price, quantity, notes
        case cartID = "cart_id"
        case menuItemID = "menu_item_id"
    }
}

// MARK: - Order

struct Order: Codable, Identifiable {
    let id: String
    var userID: String
    var restaurantID: String
    var restaurantName: String
    var status: OrderStatus
    var items: [OrderItem]
    var subtotal: Int
    var deliveryFee: Int
    var serviceFee: Int
    var tax: Int
    var total: Int
    var deliveryAddress: String
    var deliveryLat: Double
    var deliveryLng: Double
    var stripePaymentID: String?
    var estDeliveryTime: Date
    var createdAt: Date
    var updatedAt: Date

    var totalFormatted: String {
        "$\(String(format: "%.2f", Double(total) / 100))"
    }

    var subtotalFormatted: String {
        "$\(String(format: "%.2f", Double(subtotal) / 100))"
    }

    var deliveryFeeFormatted: String {
        "$\(String(format: "%.2f", Double(deliveryFee) / 100))"
    }

    var serviceFeeFormatted: String {
        "$\(String(format: "%.2f", Double(serviceFee) / 100))"
    }

    var taxFormatted: String {
        "$\(String(format: "%.2f", Double(tax) / 100))"
    }

    enum CodingKeys: String, CodingKey {
        case id, status, items, subtotal, tax, total
        case userID = "user_id"
        case restaurantID = "restaurant_id"
        case restaurantName = "restaurant_name"
        case deliveryFee = "delivery_fee"
        case serviceFee = "service_fee"
        case deliveryAddress = "delivery_address"
        case deliveryLat = "delivery_lat"
        case deliveryLng = "delivery_lng"
        case stripePaymentID = "stripe_payment_id"
        case estDeliveryTime = "est_delivery_time"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

struct OrderItem: Codable, Identifiable {
    let id: String
    var orderID: String
    var menuItemID: String
    var name: String
    var price: Int
    var quantity: Int
    var notes: String?

    var totalFormatted: String {
        "$\(String(format: "%.2f", Double(price * quantity) / 100))"
    }

    enum CodingKeys: String, CodingKey {
        case id, name, price, quantity, notes
        case orderID = "order_id"
        case menuItemID = "menu_item_id"
    }
}

// MARK: - Auth

struct AuthResponse: Codable {
    let token: String
    let refreshToken: String
    let user: User

    enum CodingKeys: String, CodingKey {
        case token, user
        case refreshToken = "refresh_token"
    }
}

struct LoginRequest: Codable {
    let email: String
    let password: String
}

struct RegisterRequest: Codable {
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

struct AddToCartRequest: Codable {
    let menuItemID: String
    let quantity: Int
    let notes: String?
    let restaurantID: String

    enum CodingKeys: String, CodingKey {
        case quantity, notes
        case menuItemID = "menu_item_id"
        case restaurantID = "restaurant_id"
    }
}

struct UpdateCartItemRequest: Codable {
    let quantity: Int
}

struct CreateOrderRequest: Codable {
    let deliveryAddress: String
    let deliveryLat: Double
    let deliveryLng: Double

    enum CodingKeys: String, CodingKey {
        case deliveryAddress = "delivery_address"
        case deliveryLat = "delivery_lat"
        case deliveryLng = "delivery_lng"
    }
}

// MARK: - API Error Response

struct APIErrorResponse: Codable {
    let error: String
}
