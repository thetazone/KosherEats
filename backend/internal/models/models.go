package models

import "time"

type UserRole string

const (
	RoleConsumer UserRole = "consumer"
	RoleSeller   UserRole = "seller"
	RoleAdmin    UserRole = "admin"
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
	OrderPending    OrderStatus = "pending"
	OrderAccepted   OrderStatus = "accepted"
	OrderPreparing  OrderStatus = "preparing"
	OrderReady      OrderStatus = "ready"
	OrderPickedUp   OrderStatus = "picked_up"
	OrderDelivered  OrderStatus = "delivered"
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
	CuisineType         []string            `json:"cuisine_type"`
	Rating              float64             `json:"rating"`
	ReviewCount         int                 `json:"review_count"`
	DeliveryFee         int                 `json:"delivery_fee"`
	MinOrder            int                 `json:"min_order"`
	EstDeliveryMin      int                 `json:"est_delivery_min"`
	EstDeliveryMax      int                 `json:"est_delivery_max"`
	IsOpen              bool                `json:"is_open"`
	IsActive            bool                `json:"is_active"`
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
	ID           string `json:"id"`
	RestaurantID string `json:"restaurant_id"`
	CategoryID   string `json:"category_id"`
	Name         string `json:"name"`
	Description  string `json:"description"`
	ImageURL     string `json:"image_url,omitempty"`
	Price        int    `json:"price"` // cents
	IsMeat       bool   `json:"is_meat"`
	IsDairy      bool   `json:"is_dairy"`
	IsPareve     bool   `json:"is_pareve"`
	IsAvailable  bool   `json:"is_available"`
	SortOrder    int    `json:"sort_order"`
}

type Cart struct {
	ID           string     `json:"id"`
	UserID       string     `json:"user_id"`
	RestaurantID string     `json:"restaurant_id"`
	Items        []CartItem `json:"items"`
	Subtotal     int        `json:"subtotal"`
}

type CartItem struct {
	ID         string `json:"id"`
	CartID     string `json:"cart_id"`
	MenuItemID string `json:"menu_item_id"`
	Name       string `json:"name"`
	Price      int    `json:"price"`
	Quantity   int    `json:"quantity"`
	Notes      string `json:"notes,omitempty"`
}

type Order struct {
	ID               string      `json:"id"`
	UserID           string      `json:"user_id"`
	RestaurantID     string      `json:"restaurant_id"`
	RestaurantName   string      `json:"restaurant_name"`
	Status           OrderStatus `json:"status"`
	Items            []OrderItem `json:"items"`
	Subtotal         int         `json:"subtotal"`
	DeliveryFee      int         `json:"delivery_fee"`
	ServiceFee       int         `json:"service_fee"`
	Tax              int         `json:"tax"`
	Total            int         `json:"total"`
	DeliveryAddress  string      `json:"delivery_address"`
	DeliveryLat      float64     `json:"delivery_lat"`
	DeliveryLng      float64     `json:"delivery_lng"`
	StripePaymentID  string      `json:"stripe_payment_id,omitempty"`
	EstDeliveryTime  time.Time   `json:"est_delivery_time"`
	CreatedAt        time.Time   `json:"created_at"`
	UpdatedAt        time.Time   `json:"updated_at"`
}

type OrderItem struct {
	ID         string `json:"id"`
	OrderID    string `json:"order_id"`
	MenuItemID string `json:"menu_item_id"`
	Name       string `json:"name"`
	Price      int    `json:"price"`
	Quantity   int    `json:"quantity"`
	Notes      string `json:"notes,omitempty"`
}
