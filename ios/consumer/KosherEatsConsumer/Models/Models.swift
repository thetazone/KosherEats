import Foundation

// MARK: - Enums

enum UserRole: String, Codable {
    case consumer
    case seller
    case admin
    // `.courier` must decode even though the consumer app only treats
    // consumer accounts as first-class — a tester's Apple ID may have
    // been promoted to role=courier by the courier social-login flow.
    // Without this case, decoding throws DecodingError.dataCorrupted
    // and the user sees the opaque "Data error: …" toast on sign-in.
    case courier
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
    // `scheduled` is set by the backend for orders booked >30 min in the future
    // (orders.go ~L95). The dispatcher flips it to `pending` near the delivery
    // window. If this case is missing, decoding ANY orders list containing a
    // scheduled order will throw and the screen goes blank.
    case scheduled
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

    var iconName: String {
        switch self {
        case .scheduled: return "calendar"
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
        case .scheduled, .pending, .accepted, .preparing, .ready, .pickedUp:
            return true
        default:
            return false
        }
    }

    var stepIndex: Int {
        switch self {
        case .scheduled: return 0
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
    var modifierGroups: [ModifierGroup]?

    var priceFormatted: String {
        "$\(String(format: "%.2f", Double(price) / 100))"
    }

    var kashrusType: String {
        if isMeat { return "Meat" }
        if isDairy { return "Dairy" }
        if isPareve { return "Pareve" }
        return ""
    }

    /// True if the user must select options before adding to cart.
    var hasRequiredModifiers: Bool {
        (modifierGroups ?? []).contains { $0.isRequired }
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
        case modifierGroups = "modifier_groups"
    }
}

// MARK: - Menu item modifiers

/// A group of modifier options on a menu item — e.g. "Choose your size"
/// (required, single-select) or "Add-ons" (optional, multi-select).
struct ModifierGroup: Codable, Identifiable {
    let id: String
    let menuItemID: String
    let name: String
    let description: String?
    let isRequired: Bool
    let minSelections: Int
    let maxSelections: Int
    let sortOrder: Int
    let modifiers: [Modifier]

    /// True if only one modifier may be picked (radio-style).
    var isSingleSelect: Bool { maxSelections == 1 }

    enum CodingKeys: String, CodingKey {
        case id, name, description, modifiers
        case menuItemID = "menu_item_id"
        case isRequired = "is_required"
        case minSelections = "min_selections"
        case maxSelections = "max_selections"
        case sortOrder = "sort_order"
    }
}

/// A single selectable option inside a ModifierGroup.
struct Modifier: Codable, Identifiable, Hashable {
    let id: String
    let groupID: String
    let name: String
    let priceDelta: Int
    let isDefault: Bool
    let isAvailable: Bool
    let sortOrder: Int

    /// "(+$2.00)" / "(−$1.50)" / "" for the side-label display.
    var priceDeltaFormatted: String {
        if priceDelta == 0 { return "" }
        let abs = Double(Swift.abs(priceDelta)) / 100
        let sign = priceDelta > 0 ? "+" : "−"
        return "\(sign)$\(String(format: "%.2f", abs))"
    }

    enum CodingKeys: String, CodingKey {
        case id, name
        case groupID = "group_id"
        case priceDelta = "price_delta"
        case isDefault = "is_default"
        case isAvailable = "is_available"
        case sortOrder = "sort_order"
    }
}

/// Snapshot of a user's modifier selection, stored on cart + order items.
/// Server returns these on GET /cart and GET /orders/:id so the UI can show
/// "Large + Extra hummus + Grilled vegetables" under each line item.
struct SelectedModifier: Codable, Identifiable, Hashable {
    let id: String
    let groupID: String
    let groupName: String
    let name: String
    let priceDelta: Int

    enum CodingKeys: String, CodingKey {
        case id, name
        case groupID = "group_id"
        case groupName = "group_name"
        case priceDelta = "price_delta"
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
    var price: Int // per-unit, includes modifier deltas
    var quantity: Int
    var notes: String?
    var selectedModifiers: [SelectedModifier]?

    var totalFormatted: String {
        "$\(String(format: "%.2f", Double(price * quantity) / 100))"
    }

    /// "Large • Extra hummus • Extra tahini" summary for the cart row.
    var modifierSummary: String? {
        guard let mods = selectedModifiers, !mods.isEmpty else { return nil }
        return mods.map(\.name).joined(separator: " • ")
    }

    enum CodingKeys: String, CodingKey {
        case id, name, price, quantity, notes
        case cartID = "cart_id"
        case menuItemID = "menu_item_id"
        case selectedModifiers = "selected_modifiers"
    }
}

// MARK: - Order

struct CourierPublic: Codable {
    let id: String
    let firstName: String
    let phone: String
    let avatarURL: String?
    let vehicleType: String
    let vehicleMake: String?
    let vehicleModel: String?
    let vehicleColor: String?
    let licensePlate: String?
    let rating: Double
    let totalDeliveries: Int
    var lat: Double
    var lng: Double

    enum CodingKeys: String, CodingKey {
        case id, phone, rating, lat, lng
        case firstName = "first_name"
        case avatarURL = "avatar_url"
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
    var userID: String
    var restaurantID: String
    var restaurantName: String
    var restaurantLat: Double?
    var restaurantLng: Double?
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
    var courier: CourierPublic?
    var courierRating: Int?

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
        case id, status, items, subtotal, tax, total, courier
        case userID = "user_id"
        case restaurantID = "restaurant_id"
        case restaurantName = "restaurant_name"
        case restaurantLat = "restaurant_lat"
        case restaurantLng = "restaurant_lng"
        case deliveryFee = "delivery_fee"
        case serviceFee = "service_fee"
        case deliveryAddress = "delivery_address"
        case deliveryLat = "delivery_lat"
        case deliveryLng = "delivery_lng"
        case stripePaymentID = "stripe_payment_id"
        case estDeliveryTime = "est_delivery_time"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case courierRating = "courier_rating"
    }
}

struct OrderItem: Codable, Identifiable {
    let id: String
    var orderID: String
    var menuItemID: String
    var name: String
    var price: Int // per-unit, includes modifier deltas
    var quantity: Int
    var notes: String?
    var selectedModifiers: [SelectedModifier]?

    var totalFormatted: String {
        "$\(String(format: "%.2f", Double(price * quantity) / 100))"
    }

    var modifierSummary: String? {
        guard let mods = selectedModifiers, !mods.isEmpty else { return nil }
        return mods.map(\.name).joined(separator: " • ")
    }

    enum CodingKeys: String, CodingKey {
        case id, name, price, quantity, notes
        case orderID = "order_id"
        case menuItemID = "menu_item_id"
        case selectedModifiers = "selected_modifiers"
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

struct SocialLoginRequest: Codable {
    let provider: String
    let token: String
    let firstName: String
    let lastName: String
    /// Apple Sign In only — raw nonce we generated client-side. Backend
    /// hashes it and compares to the JWT's nonce claim to block token replay.
    let nonce: String?

    enum CodingKeys: String, CodingKey {
        case provider, token, nonce
        case firstName = "first_name"
        case lastName = "last_name"
    }
}

struct AddToCartRequest: Codable {
    let menuItemID: String
    let quantity: Int
    let notes: String?
    let restaurantID: String
    let modifierIDs: [String]

    enum CodingKeys: String, CodingKey {
        case quantity, notes
        case menuItemID = "menu_item_id"
        case restaurantID = "restaurant_id"
        case modifierIDs = "modifier_ids"
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
