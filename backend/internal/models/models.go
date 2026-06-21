package models

import "time"

type UserRole string

const (
	RoleConsumer UserRole = "consumer"
	RoleSeller   UserRole = "seller"
	RoleAdmin    UserRole = "admin"
	RoleCourier  UserRole = "courier"
)

type CourierOnboardingStatus string

const (
	OnboardingPendingInfo       CourierOnboardingStatus = "pending_info"
	OnboardingPendingDocuments  CourierOnboardingStatus = "pending_documents"
	OnboardingPendingBackground CourierOnboardingStatus = "pending_background"
	OnboardingApproved          CourierOnboardingStatus = "approved"
	OnboardingRejected          CourierOnboardingStatus = "rejected"
	OnboardingSuspended         CourierOnboardingStatus = "suspended"
)

type VehicleType string

const (
	VehicleCar        VehicleType = "car"
	VehicleBike       VehicleType = "bike"
	VehicleScooter    VehicleType = "scooter"
	VehicleMotorcycle VehicleType = "motorcycle"
	VehicleWalk       VehicleType = "walk"
)

type KosherCertification string

const (
	CertOU       KosherCertification = "OU"
	CertOK       KosherCertification = "OK"
	CertKof      KosherCertification = "Kof-K"
	CertStar     KosherCertification = "Star-K"
	CertCRC      KosherCertification = "cRc"
	CertBadatz   KosherCertification = "Badatz"
	CertChofetz  KosherCertification = "Chof-K"
	CertOther    KosherCertification = "other"
)

type OrderStatus string

const (
	OrderScheduled  OrderStatus = "scheduled"
	OrderPending    OrderStatus = "pending"
	OrderAccepted   OrderStatus = "accepted"
	OrderPreparing  OrderStatus = "preparing"
	OrderReady      OrderStatus = "ready"
	OrderPickedUp   OrderStatus = "picked_up"
	OrderDelivered  OrderStatus = "delivered"
	OrderCompleted  OrderStatus = "completed"
	OrderCancelled  OrderStatus = "cancelled"
	OrderRejected   OrderStatus = "rejected"
)

type User struct {
	ID           string    `json:"id"`
	Email        string    `json:"email"`
	PasswordHash string    `json:"-"`
	FirstName    string    `json:"first_name"`
	LastName     string    `json:"last_name"`
	Phone        string    `json:"phone"`
	Role         UserRole  `json:"role"`
	// Vertical scopes the account to a specific branded app. Values:
	// 'kosher' (KosherEats) | 'vegan' (GreenEats). The (email, vertical)
	// pair is unique, so the same email can register on both apps and
	// gets a fully independent account in each.
	Vertical     string    `json:"vertical"`
	AvatarURL    string    `json:"avatar_url,omitempty"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

type Address struct {
	ID        string  `json:"id"`
	UserID    string  `json:"user_id"`
	Label     string  `json:"label"`
	Street    string  `json:"street"`
	Apt       string  `json:"apt,omitempty"`
	City      string  `json:"city"`
	State     string  `json:"state"`
	ZipCode   string  `json:"zip_code"`
	Lat       float64 `json:"lat"`
	Lng       float64 `json:"lng"`
	IsDefault bool    `json:"is_default"`
}

type Restaurant struct {
	ID                  string              `json:"id"`
	OwnerID             string              `json:"owner_id"`
	Name                string              `json:"name"`
	Description         string              `json:"description"`
	ImageURL            string              `json:"image_url"`
	CoverImageURL       string              `json:"cover_image_url,omitempty"`
	LogoURL             string              `json:"logo_url"`
	Phone               string              `json:"phone"`
	Email               string              `json:"email"`
	Street              string              `json:"street"`
	City                string              `json:"city"`
	State               string              `json:"state"`
	ZipCode             string              `json:"zip_code"`
	Lat                 float64             `json:"lat"`
	Lng                 float64             `json:"lng"`
	KosherCertification KosherCertification `json:"kosher_certification"`
	CertifyingAgency    string              `json:"certifying_agency"`
	IsCholovYisroel     bool                `json:"is_cholov_yisroel"`
	IsPasYisroel        bool                `json:"is_pas_yisroel"`
	IsGlattKosher       bool                `json:"is_glatt_kosher"`
	KosherCertificateURL string             `json:"kosher_certificate_url"`
	CuisineType         []string            `json:"cuisine_type"`
	Rating              float64             `json:"rating"`
	ReviewCount         int                 `json:"review_count"`
	DeliveryFee         int                 `json:"delivery_fee"`
	MinOrder            int                 `json:"min_order"`
	EstDeliveryMin      int                 `json:"est_delivery_min"`
	EstDeliveryMax      int                 `json:"est_delivery_max"`
	IsOpen              bool                `json:"is_open"`
	IsActive            bool                `json:"is_active"`
	ApprovalStatus      string              `json:"approval_status,omitempty"`
	DeliveryMode        string              `json:"delivery_mode"`
	CreatedAt           time.Time           `json:"created_at"`
	UpdatedAt           time.Time           `json:"updated_at"`
}

type MenuCategory struct {
	ID           string     `json:"id"`
	RestaurantID string     `json:"restaurant_id"`
	Name         string     `json:"name"`
	SortOrder    int        `json:"sort_order"`
	Items        []MenuItem `json:"items,omitempty"`
}

type MenuItem struct {
	ID             string          `json:"id"`
	RestaurantID   string          `json:"restaurant_id"`
	CategoryID     string          `json:"category_id"`
	Name           string          `json:"name"`
	Description    string          `json:"description"`
	ImageURL       string          `json:"image_url,omitempty"`
	Price          int             `json:"price"` // cents
	IsMeat         bool            `json:"is_meat"`
	IsDairy        bool            `json:"is_dairy"`
	IsPareve       bool            `json:"is_pareve"`
	IsAvailable    bool            `json:"is_available"`
	SortOrder      int             `json:"sort_order"`
	ModifierGroups []ModifierGroup `json:"modifier_groups,omitempty"`
}

// MenuImport tracks an async menu-import job (e.g. from an UberEats store URL).
// Created when a seller opts into import during onboarding; an out-of-process
// worker drains pending rows, scrapes the source, writes menu_items, and
// updates status + counts here. Read by the app to show import progress.
type MenuImport struct {
	ID           string    `json:"id"`
	RestaurantID string    `json:"restaurant_id"`
	Source       string    `json:"source"` // "ubereats"
	SourceURL    string    `json:"source_url"`
	Status       string    `json:"status"` // pending | running | done | failed
	ItemsTotal   int       `json:"items_total"`
	ItemsCreated int       `json:"items_created"`
	Error        string    `json:"error,omitempty"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

// ModifierGroup is a set of selectable options on a menu item — e.g.
// "Choose your size" (required, exactly 1) or "Add-ons" (optional, up to 5).
type ModifierGroup struct {
	ID            string     `json:"id"`
	MenuItemID    string     `json:"menu_item_id"`
	Name          string     `json:"name"`
	Description   string     `json:"description,omitempty"`
	IsRequired    bool       `json:"is_required"`
	MinSelections int        `json:"min_selections"`
	MaxSelections int        `json:"max_selections"`
	SortOrder     int        `json:"sort_order"`
	Modifiers     []Modifier `json:"modifiers"`
}

// Modifier is a single option inside a ModifierGroup. PriceDelta is added
// to the base menu item price when selected; it can be zero or negative
// (e.g. "remove onions" stays at 0; "small size" could be -200 cents).
type Modifier struct {
	ID          string `json:"id"`
	GroupID     string `json:"group_id"`
	Name        string `json:"name"`
	PriceDelta  int    `json:"price_delta"`
	IsDefault   bool   `json:"is_default"`
	IsAvailable bool   `json:"is_available"`
	SortOrder   int    `json:"sort_order"`
}

// SelectedModifier is the JSONB shape we snapshot into cart_items.selected_modifiers
// and order_items.selected_modifiers. We copy the name + price at selection
// time so the display + pricing stay stable even if the seller later edits
// the underlying modifier row.
type SelectedModifier struct {
	ID         string `json:"id"`
	GroupID    string `json:"group_id"`
	GroupName  string `json:"group_name"`
	Name       string `json:"name"`
	PriceDelta int    `json:"price_delta"`
}

type Cart struct {
	ID           string     `json:"id"`
	UserID       string     `json:"user_id"`
	RestaurantID string     `json:"restaurant_id"`
	Items        []CartItem `json:"items"`
	Subtotal     int        `json:"subtotal"`
}

type CartItem struct {
	ID                string             `json:"id"`
	CartID            string             `json:"cart_id"`
	MenuItemID        string             `json:"menu_item_id"`
	Name              string             `json:"name"`
	Price             int                `json:"price"` // already includes modifier deltas, per-unit
	Quantity          int                `json:"quantity"`
	Notes             string             `json:"notes,omitempty"`
	SelectedModifiers []SelectedModifier `json:"selected_modifiers,omitempty"`
}

type Order struct {
	ID              string      `json:"id"`
	UserID          string      `json:"user_id"`
	RestaurantID    string      `json:"restaurant_id"`
	RestaurantName  string      `json:"restaurant_name"`
	RestaurantLat   float64     `json:"restaurant_lat,omitempty"`
	RestaurantLng   float64     `json:"restaurant_lng,omitempty"`
	Status          OrderStatus `json:"status"`
	Items           []OrderItem `json:"items"`
	Subtotal        int         `json:"subtotal"`
	DeliveryFee     int         `json:"delivery_fee"`
	ServiceFee      int         `json:"service_fee"`
	Tax             int         `json:"tax"`
	Total           int         `json:"total"`
	DeliveryAddress string      `json:"delivery_address"`
	DeliveryLat     float64     `json:"delivery_lat"`
	DeliveryLng     float64     `json:"delivery_lng"`
	StripePaymentID string      `json:"stripe_payment_id,omitempty"`
	EstDeliveryTime time.Time   `json:"est_delivery_time"`

	// Courier assignment
	CourierID     *string        `json:"courier_id,omitempty"`
	Courier       *CourierPublic `json:"courier,omitempty"`
	ClaimedAt     *time.Time     `json:"claimed_at,omitempty"`
	PickedUpAt    *time.Time     `json:"picked_up_at,omitempty"`
	DeliveredAt   *time.Time     `json:"delivered_at,omitempty"`
	CourierPayout int            `json:"courier_payout"`
	CourierTip    int            `json:"courier_tip"`

	// Populated once the consumer has rated their courier. iOS uses this
	// to decide whether to present the rating prompt after a delivery.
	CourierRating *int `json:"courier_rating,omitempty"`

	// Consumer contact (populated for seller + courier views only).
	CustomerName  string `json:"customer_name,omitempty"`
	CustomerPhone string `json:"customer_phone,omitempty"`

	// Drop-off photo from courier
	DeliveryProofURL string `json:"delivery_proof_url,omitempty"`

	// "delivery" (default) or "pickup". Lets all clients branch their UI on
	// pickup-shaped orders — no courier card on the seller side, no
	// "couriers nearby" entry for couriers, etc.
	FulfillmentType string `json:"fulfillment_type"`

	// External courier dispatch (Uber Direct fallback).
	ExternalDeliveryID  *string `json:"external_delivery_id,omitempty"`
	ExternalProvider    *string `json:"external_provider,omitempty"`
	ExternalTrackingURL *string `json:"external_tracking_url,omitempty"`

	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

// CourierProfile is the full record returned to the courier themselves.
type CourierProfile struct {
	ID                     string                  `json:"id"`
	UserID                 string                  `json:"user_id"`
	OnboardingStatus       CourierOnboardingStatus `json:"onboarding_status"`
	PhoneVerified          bool                    `json:"phone_verified"`
	VehicleType            VehicleType             `json:"vehicle_type"`
	VehicleMake            string                  `json:"vehicle_make"`
	VehicleModel           string                  `json:"vehicle_model"`
	VehicleYear            int                     `json:"vehicle_year"`
	VehicleColor           string                  `json:"vehicle_color"`
	LicensePlate           string                  `json:"license_plate"`
	DriversLicenseURL      string                  `json:"drivers_license_url,omitempty"`
	DriversLicenseNumber   string                  `json:"drivers_license_number,omitempty"`
	InsuranceURL           string                  `json:"insurance_url,omitempty"`
	VehicleRegistrationURL string                  `json:"vehicle_registration_url,omitempty"`
	ProfilePhotoURL        string                  `json:"profile_photo_url,omitempty"`
	BackgroundCheckStatus  string                  `json:"background_check_status"`
	PayoutReady            bool                    `json:"payout_ready"`
	IsOnline               bool                    `json:"is_online"`
	LastLat                float64                 `json:"last_lat"`
	LastLng                float64                 `json:"last_lng"`
	LastLocationAt         *time.Time              `json:"last_location_at,omitempty"`
	TotalDeliveries        int                     `json:"total_deliveries"`
	Rating                 float64                 `json:"rating"`
	CreatedAt              time.Time               `json:"created_at"`
	UpdatedAt              time.Time               `json:"updated_at"`
}

// CourierPublic is the trimmed view of a courier that consumers and sellers see
// once a courier is assigned to their order. Never exposes license / background / payout.
type CourierPublic struct {
	ID              string      `json:"id"`
	FirstName       string      `json:"first_name"`
	Phone           string      `json:"phone"`
	AvatarURL       string      `json:"avatar_url,omitempty"`
	VehicleType     VehicleType `json:"vehicle_type"`
	VehicleMake     string      `json:"vehicle_make,omitempty"`
	VehicleModel    string      `json:"vehicle_model,omitempty"`
	VehicleColor    string      `json:"vehicle_color,omitempty"`
	LicensePlate    string      `json:"license_plate,omitempty"`
	Rating          float64     `json:"rating"`
	TotalDeliveries int         `json:"total_deliveries"`
	Lat             float64     `json:"lat"`
	Lng             float64     `json:"lng"`
}

// CourierLocationPing is a single GPS heartbeat from the courier app.
type CourierLocationPing struct {
	Lat     float64 `json:"lat"`
	Lng     float64 `json:"lng"`
	Heading float64 `json:"heading"`
	Speed   float64 `json:"speed"`
}

// DashboardStats is what GET /seller/dashboard/stats returns. Matches the
// shape expected by the iOS seller client's StatCard layout.
type DashboardStats struct {
	TodayOrders       int     `json:"today_orders"`
	TodayRevenueCents int     `json:"today_revenue"`
	ActiveOrders      int     `json:"active_orders"`
	AvgPrepTime       float64 `json:"avg_prep_time"`
}

type OrderItem struct {
	ID                string             `json:"id"`
	OrderID           string             `json:"order_id"`
	MenuItemID        string             `json:"menu_item_id"`
	Name              string             `json:"name"`
	Price             int                `json:"price"` // per-unit, includes modifier deltas
	Quantity          int                `json:"quantity"`
	Notes             string             `json:"notes,omitempty"`
	SelectedModifiers []SelectedModifier `json:"selected_modifiers,omitempty"`
}

// ── Deals ────────────────────────────────────────────────────

type DiscountType string

const (
	DiscountPercentage DiscountType = "percentage"
	DiscountFixed      DiscountType = "fixed"
	DiscountBOGO       DiscountType = "bogo"
)

// Deal is a limited-time promotion a seller posts for their restaurant.
// Consumers see active, non-expired deals on the Deals tab.
type Deal struct {
	ID             string       `json:"id"`
	RestaurantID   string       `json:"restaurant_id"`
	Title          string       `json:"title"`
	Description    string       `json:"description"`
	ImageURL       string       `json:"image_url"`
	MenuItemID     *string      `json:"menu_item_id,omitempty"`
	DiscountType   DiscountType `json:"discount_type"`
	DiscountValue  int          `json:"discount_value"`
	MinOrderAmount *int         `json:"min_order_amount,omitempty"`
	StartsAt       time.Time    `json:"starts_at"`
	ExpiresAt      time.Time    `json:"expires_at"`
	IsActive       bool         `json:"is_active"`
	CreatedAt      time.Time    `json:"created_at"`
	UpdatedAt      time.Time    `json:"updated_at"`
}

// DealWithItem is the consumer-facing shape that includes restaurant
// and (optionally) linked menu item info for rendering deal cards.
type DealWithItem struct {
	Deal
	RestaurantName     string  `json:"restaurant_name"`
	RestaurantImageURL string  `json:"restaurant_image_url"`
	MenuItemName       *string `json:"menu_item_name,omitempty"`
	MenuItemPrice      *int    `json:"menu_item_price,omitempty"`
	MenuItemImageURL   *string `json:"menu_item_image_url,omitempty"`
}
