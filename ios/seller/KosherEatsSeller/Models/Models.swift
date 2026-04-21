import Foundation

// MARK: - Currency Formatting

/// Locale-aware currency formatter for cents → display string.
/// Uses the device locale so "$12.34" renders correctly in all regions.
enum CurrencyFormat {
    private static let formatter: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = "USD"
        return f
    }()

    static func string(fromCents cents: Int) -> String {
        formatter.string(from: NSNumber(value: Double(cents) / 100)) ?? "$0.00"
    }
}

// MARK: - Enums

enum UserRole: String, Codable {
    // `.courier` must decode here even though the seller app doesn't grant
    // access to courier accounts — the App Review tester's Apple ID may
    // already be role=courier from signing into the courier app first.
    // Access is still gated by `hasSellerAccess` in AuthViewModel, which
    // only admits seller/admin. Without this case, decoding throws
    // DecodingError.dataCorrupted ("The data couldn't be read because it
    // isn't in the correct format.") and the user sees "Data error: …"
    // with no path forward.
    case consumer, seller, admin, courier
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
    // `scheduled` is set by the backend when a consumer schedules an order
    // more than 30 minutes in the future (orders.go ~L95). The background
    // dispatcher flips it to `pending` close to the delivery window. Without
    // this case, decoding the seller orders list would throw for the whole
    // batch as soon as a single scheduled order shows up.
    case scheduled
    case pending, accepted, preparing, ready
    case pickedUp = "picked_up"
    case delivered, completed, cancelled, rejected

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .scheduled: return "Scheduled"
        case .pending: return "Pending"
        case .accepted: return "Accepted"
        case .preparing: return "Preparing"
        case .ready: return "Ready"
        case .pickedUp: return "Picked Up"
        case .delivered, .completed: return "Delivered"
        case .cancelled: return "Cancelled"
        case .rejected: return "Rejected"
        }
    }

    var icon: String {
        switch self {
        case .scheduled: return "calendar"
        case .pending: return "clock"
        case .accepted: return "checkmark.circle"
        case .preparing: return "flame"
        case .ready: return "bag.fill"
        case .pickedUp: return "car"
        case .delivered, .completed: return "checkmark.seal.fill"
        case .cancelled: return "xmark.circle"
        case .rejected: return "xmark.octagon"
        }
    }

    var color: String {
        switch self {
        case .scheduled: return "primary"
        case .pending: return "warning"
        case .accepted, .preparing: return "primary"
        case .ready: return "success"
        case .pickedUp, .delivered, .completed: return "success"
        case .cancelled, .rejected: return "error"
        }
    }

    var isActive: Bool {
        switch self {
        // `.pickedUp` belongs here too: the seller needs to see orders that
        // have left the kitchen but aren't delivered yet, otherwise they lose
        // visibility into deliveries en route once the courier grabs the bag.
        case .pending, .accepted, .preparing, .ready, .pickedUp:
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
    /// Cents — matches backend (restaurants.delivery_fee is INTEGER). UI converts
    /// to dollars with `Double(deliveryFee) / 100`. Previously decoded as Double,
    /// which made a $3.99 fee render as "399.00" in Settings.
    var deliveryFee: Int
    /// Cents — matches backend. See deliveryFee note.
    var minOrder: Int
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
    /// Modifier groups attached to this item (options, extras, size choices).
    /// Optional because pre-modifier items and the POST/PUT responses return
    /// items without the groups array — the seller editor loads them
    /// separately after creating a brand-new item.
    var modifierGroups: [ModifierGroup]?

    var priceFormatted: String { CurrencyFormat.string(fromCents: price) }

    enum CodingKeys: String, CodingKey {
        case id, name, description, price
        case restaurantId = "restaurant_id"
        case categoryId = "category_id"
        case imageUrl = "image_url"
        case isMeat = "is_meat"
        case isDairy = "is_dairy"
        case isPareve = "is_pareve"
        case isAvailable = "is_available"
        case modifierGroups = "modifier_groups"
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

struct ModifierGroup: Codable, Identifiable, Equatable {
    let id: String
    let menuItemId: String
    var name: String
    var description: String
    var isRequired: Bool
    var minSelections: Int
    var maxSelections: Int
    var sortOrder: Int
    var modifiers: [Modifier]

    enum CodingKeys: String, CodingKey {
        case id, name, description, modifiers
        case menuItemId = "menu_item_id"
        case isRequired = "is_required"
        case minSelections = "min_selections"
        case maxSelections = "max_selections"
        case sortOrder = "sort_order"
    }

    init(id: String = "", menuItemId: String = "", name: String = "", description: String = "",
         isRequired: Bool = false, minSelections: Int = 0, maxSelections: Int = 1,
         sortOrder: Int = 0, modifiers: [Modifier] = []) {
        self.id = id
        self.menuItemId = menuItemId
        self.name = name
        self.description = description
        self.isRequired = isRequired
        self.minSelections = minSelections
        self.maxSelections = maxSelections
        self.sortOrder = sortOrder
        self.modifiers = modifiers
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.menuItemId = try c.decode(String.self, forKey: .menuItemId)
        self.name = try c.decode(String.self, forKey: .name)
        self.description = try c.decodeIfPresent(String.self, forKey: .description) ?? ""
        self.isRequired = try c.decode(Bool.self, forKey: .isRequired)
        self.minSelections = try c.decode(Int.self, forKey: .minSelections)
        self.maxSelections = try c.decode(Int.self, forKey: .maxSelections)
        self.sortOrder = try c.decode(Int.self, forKey: .sortOrder)
        self.modifiers = try c.decodeIfPresent([Modifier].self, forKey: .modifiers) ?? []
    }
}

struct Modifier: Codable, Identifiable, Equatable {
    let id: String
    let groupId: String
    var name: String
    var priceDelta: Int
    var isDefault: Bool
    var isAvailable: Bool
    var sortOrder: Int

    enum CodingKeys: String, CodingKey {
        case id, name
        case groupId = "group_id"
        case priceDelta = "price_delta"
        case isDefault = "is_default"
        case isAvailable = "is_available"
        case sortOrder = "sort_order"
    }

    init(id: String = "", groupId: String = "", name: String = "", priceDelta: Int = 0,
         isDefault: Bool = false, isAvailable: Bool = true, sortOrder: Int = 0) {
        self.id = id
        self.groupId = groupId
        self.name = name
        self.priceDelta = priceDelta
        self.isDefault = isDefault
        self.isAvailable = isAvailable
        self.sortOrder = sortOrder
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.groupId = try c.decode(String.self, forKey: .groupId)
        self.name = try c.decode(String.self, forKey: .name)
        self.priceDelta = try c.decode(Int.self, forKey: .priceDelta)
        self.isDefault = try c.decode(Bool.self, forKey: .isDefault)
        self.isAvailable = try c.decode(Bool.self, forKey: .isAvailable)
        self.sortOrder = try c.decode(Int.self, forKey: .sortOrder)
    }

    var priceDeltaFormatted: String {
        let abs = Double(Swift.abs(priceDelta)) / 100
        if priceDelta == 0 { return "" }
        return String(format: "%@$%.2f", priceDelta < 0 ? "-" : "+", abs)
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
    let customerName: String?
    let customerPhone: String?
    let courierTip: Int?
    let courierPayout: Int?
    /// "delivery" (default) or "pickup". Drives the seller UI's branching for
    /// pickup-shaped orders — no courier card, "Mark Picked Up" button at
    /// status='ready'. Decoded from the backend's `fulfillment_type` column
    /// (migration 021); defaults to "delivery" when older responses omit it.
    let fulfillmentType: String

    var isPickup: Bool { fulfillmentType == "pickup" }

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
        case customerName = "customer_name"
        case customerPhone = "customer_phone"
        case courierTip = "courier_tip"
        case courierPayout = "courier_payout"
        case fulfillmentType = "fulfillment_type"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        userId = try c.decode(String.self, forKey: .userId)
        restaurantId = try c.decode(String.self, forKey: .restaurantId)
        restaurantName = try c.decode(String.self, forKey: .restaurantName)
        status = try c.decode(OrderStatus.self, forKey: .status)
        items = try c.decode([OrderItem].self, forKey: .items)
        subtotal = try c.decode(Int.self, forKey: .subtotal)
        deliveryFee = try c.decode(Int.self, forKey: .deliveryFee)
        serviceFee = try c.decode(Int.self, forKey: .serviceFee)
        tax = try c.decode(Int.self, forKey: .tax)
        total = try c.decode(Int.self, forKey: .total)
        deliveryAddress = try c.decode(String.self, forKey: .deliveryAddress)
        estDeliveryTime = try c.decodeIfPresent(String.self, forKey: .estDeliveryTime)
        createdAt = try c.decode(String.self, forKey: .createdAt)
        updatedAt = try c.decode(String.self, forKey: .updatedAt)
        courier = try c.decodeIfPresent(CourierPublic.self, forKey: .courier)
        customerName = try c.decodeIfPresent(String.self, forKey: .customerName)
        customerPhone = try c.decodeIfPresent(String.self, forKey: .customerPhone)
        courierTip = try c.decodeIfPresent(Int.self, forKey: .courierTip)
        courierPayout = try c.decodeIfPresent(Int.self, forKey: .courierPayout)
        // Default to "delivery" so responses from pre-migration-021 backends
        // (or any handler that doesn't yet emit the field) still decode.
        fulfillmentType = (try c.decodeIfPresent(String.self, forKey: .fulfillmentType)) ?? "delivery"
    }

    /// Dollars display for the total. Every UI using $%.2f on order.total
    /// should use this instead.
    var totalFormatted: String { CurrencyFormat.string(fromCents: total) }
    var subtotalFormatted: String { CurrencyFormat.string(fromCents: subtotal) }
    var deliveryFeeFormatted: String { CurrencyFormat.string(fromCents: deliveryFee) }
    var serviceFeeFormatted: String { CurrencyFormat.string(fromCents: serviceFee) }
    var taxFormatted: String { CurrencyFormat.string(fromCents: tax) }

    var formattedDate: String {
        guard let date = createdAtDate else { return createdAt }
        return Self.displayFormatter.string(from: date)
    }

    /// Parsed `createdAt` as a Date, or nil if the backend sent something
    /// we can't decode. Tries fractional-seconds ISO8601 first (Postgres
    /// TIMESTAMPTZ default) then falls back to plain ISO8601.
    var createdAtDate: Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = formatter.date(from: createdAt) { return d }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: createdAt)
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
        CurrencyFormat.string(fromCents: price * quantity)
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

// MARK: - Linked Providers (Account Linking)

struct LinkedProvider: Codable, Identifiable {
    let provider: String
    let createdAt: String

    var id: String { provider }

    var displayName: String {
        switch provider {
        case "apple": return "Apple"
        case "google": return "Google"
        case "phone": return "Phone"
        default: return provider.capitalized
        }
    }

    var iconName: String {
        switch provider {
        case "apple": return "apple.logo"
        case "google": return "g.circle.fill"
        case "phone": return "phone.fill"
        default: return "person.fill"
        }
    }

    enum CodingKeys: String, CodingKey {
        case provider
        case createdAt = "created_at"
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
