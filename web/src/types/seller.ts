// Seller-side types for the /seller dashboard. Mirrors the Go structs in
// backend/internal/models/models.go and the request types in
// backend/internal/handlers/{seller,deals,orders}.go. All money is integer
// cents. Kept separate from types/index.ts — the consumer lane owns that file.

export type SellerRole = "consumer" | "seller" | "admin" | "courier";

export type KosherCertification =
  | "OU"
  | "OK"
  | "Kof-K"
  | "Star-K"
  | "cRc"
  | "Badatz"
  | "Chof-K"
  | "other";

export type OrderStatus =
  | "scheduled"
  | "pending"
  | "accepted"
  | "preparing"
  | "ready"
  | "picked_up"
  | "delivered"
  | "completed"
  | "cancelled"
  | "rejected";

export type DiscountType = "percentage" | "fixed" | "bogo";

export type ApprovalStatus = "pending" | "approved" | "rejected";

/** "restaurant" = seller self-delivers, "external" = provider, "platform" = KosherEats couriers. */
export type DeliveryMode = "platform" | "restaurant" | "external";

export type FulfillmentType = "delivery" | "pickup";

// ── Auth ─────────────────────────────────────────────────────

export interface SellerUser {
  id: string;
  email: string;
  first_name: string;
  last_name: string;
  phone: string;
  role: SellerRole;
  vertical: string;
  avatar_url?: string;
  email_verified: boolean;
  phone_verified: boolean;
  created_at: string;
  updated_at: string;
}

/** POST /auth/login and /auth/register response (AuthResponse in auth.go). */
export interface SellerAuthResponse {
  token: string;
  refresh_token: string;
  user: SellerUser;
}

// ── Restaurant ───────────────────────────────────────────────

export interface SellerRestaurant {
  id: string;
  owner_id: string;
  name: string;
  description: string;
  image_url: string;
  cover_image_url?: string;
  logo_url: string;
  phone: string;
  email: string;
  street: string;
  city: string;
  state: string;
  zip_code: string;
  lat: number;
  lng: number;
  kosher_certification: KosherCertification;
  certifying_agency: string;
  is_cholov_yisroel: boolean;
  is_pas_yisroel: boolean;
  is_glatt_kosher: boolean;
  kosher_certificate_url: string;
  cuisine_type: string[];
  rating: number;
  review_count: number;
  delivery_fee: number; // cents
  min_order: number; // cents
  est_delivery_min: number;
  est_delivery_max: number;
  is_open: boolean;
  is_active: boolean;
  approval_status?: ApprovalStatus;
  delivery_mode: DeliveryMode;
  created_at: string;
  updated_at: string;
}

/** POST /seller/restaurants body (CreateRestaurantRequest in seller.go). */
export interface CreateRestaurantRequest {
  name: string;
  description?: string;
  image_url?: string;
  logo_url?: string;
  phone?: string;
  email: string;
  street?: string;
  city?: string;
  state?: string;
  zip_code?: string;
  /**
   * Collected + null-island-validated client-side and sent forward-compatibly.
   * The backend currently ignores these (defaults new restaurants to NYC
   * center until geocoding lands) — unknown JSON fields are tolerated.
   */
  lat?: number;
  lng?: number;
  kosher_certification: string;
  certifying_agency?: string;
  kosher_certificate_url?: string;
  cuisine_type?: string[];
  is_cholov_yisroel?: boolean;
  is_pas_yisroel?: boolean;
  is_glatt_kosher?: boolean;
  from_import?: boolean;
}

/** PUT /seller/restaurant body — all fields optional (pointer fields in Go). */
export interface UpdateRestaurantRequest {
  name?: string;
  description?: string;
  phone?: string;
  email?: string;
  street?: string;
  city?: string;
  state?: string;
  zip_code?: string;
  cuisine_type?: string[];
  delivery_fee?: number; // cents
  min_order?: number; // cents
  est_delivery_min?: number;
  est_delivery_max?: number;
  is_open?: boolean;
  kosher_certification?: string;
  certifying_agency?: string;
  is_cholov_yisroel?: boolean;
  is_pas_yisroel?: boolean;
  is_glatt_kosher?: boolean;
  kosher_certificate_url?: string;
  delivery_mode?: DeliveryMode;
  image_url?: string;
  logo_url?: string;
}

// ── Dashboard ────────────────────────────────────────────────

/** GET /seller/dashboard/stats (models.DashboardStats). All money cents. */
export interface DashboardStats {
  today_orders: number;
  today_revenue: number;
  today_delivery_earnings: number;
  active_orders: number;
  avg_prep_time: number; // minutes
}

// ── Menu ─────────────────────────────────────────────────────

export interface SellerModifier {
  id: string;
  group_id: string;
  name: string;
  price_delta: number; // cents
  is_default: boolean;
  is_available: boolean;
  sort_order: number;
}

export interface SellerModifierGroup {
  id: string;
  menu_item_id: string;
  name: string;
  description?: string;
  is_required: boolean;
  min_selections: number;
  max_selections: number;
  sort_order: number;
  modifiers: SellerModifier[];
}

export interface SellerMenuItem {
  id: string;
  restaurant_id: string;
  category_id: string;
  name: string;
  description: string;
  image_url?: string;
  price: number; // cents
  is_meat: boolean;
  is_dairy: boolean;
  is_pareve: boolean;
  is_available: boolean;
  sort_order: number;
  modifier_groups?: SellerModifierGroup[];
}

/** GET /seller/menu returns a raw array of these (includes paused items). */
export interface SellerMenuCategory {
  id: string;
  restaurant_id: string;
  name: string;
  sort_order: number;
  items?: SellerMenuItem[];
}

/** POST /seller/menu/items and PUT /seller/menu/items/{id} body. */
export interface MenuItemRequest {
  category_id: string;
  name: string;
  description?: string;
  image_url?: string;
  price: number; // cents
  is_meat?: boolean;
  is_dairy?: boolean;
  is_pareve?: boolean;
}

/** Modifier option within a ModifierGroupRequest. id present on updates only. */
export interface ModifierOptionRequest {
  id?: string;
  name: string;
  price_delta: number; // cents
  is_default: boolean;
  is_available: boolean;
  sort_order: number;
}

/** POST /seller/menu/items/{itemId}/modifier-groups and PUT .../modifier-groups/{groupId} body. */
export interface ModifierGroupRequest {
  name: string;
  description?: string;
  is_required: boolean;
  min_selections: number;
  max_selections: number;
  sort_order: number;
  modifiers: ModifierOptionRequest[];
}

/** Menu import job (models.MenuImport) — self-serve UberEats import. */
export interface MenuImport {
  id: string;
  restaurant_id: string;
  source: string; // "ubereats"
  source_url: string;
  status: "pending" | "running" | "done" | "failed";
  items_total: number;
  items_created: number;
  error?: string;
  created_at: string;
  updated_at: string;
}

// ── Orders ───────────────────────────────────────────────────

export interface SelectedModifier {
  id: string;
  group_id: string;
  group_name: string;
  name: string;
  price_delta: number; // cents
}

export interface SellerOrderItem {
  id: string;
  order_id: string;
  menu_item_id: string;
  name: string;
  price: number; // per-unit cents, includes modifier deltas
  quantity: number;
  notes?: string;
  selected_modifiers?: SelectedModifier[];
}

export interface SellerCourierPublic {
  id: string;
  first_name: string;
  phone: string;
  avatar_url?: string;
  vehicle_type: string;
  vehicle_make?: string;
  vehicle_model?: string;
  vehicle_color?: string;
  license_plate?: string;
  rating: number;
  total_deliveries: number;
  lat: number;
  lng: number;
}

/** models.Order as returned by /seller/orders (list + detail). Money cents. */
export interface SellerOrder {
  id: string;
  user_id: string;
  restaurant_id: string;
  restaurant_name: string;
  restaurant_lat?: number;
  restaurant_lng?: number;
  status: OrderStatus;
  items: SellerOrderItem[];
  subtotal: number;
  discount: number;
  delivery_fee: number;
  service_fee: number;
  tax: number;
  total: number;
  delivery_address: string;
  delivery_lat?: number;
  delivery_lng?: number;
  est_delivery_time: string;
  scheduled_for?: string;
  courier_id?: string;
  courier?: SellerCourierPublic;
  claimed_at?: string;
  picked_up_at?: string;
  delivered_at?: string;
  courier_payout?: number;
  courier_tip: number;
  customer_name?: string;
  customer_phone?: string;
  fulfillment_type: FulfillmentType;
  external_delivery_id?: string;
  external_provider?: string;
  external_tracking_url?: string;
  delivery_mode?: DeliveryMode;
  created_at: string;
  updated_at: string;
}

/**
 * PATCH /seller/orders/{id}/pickup and /deliver response — these two
 * self-delivery transitions return a bare status ack, NOT the full order
 * (SellerPickupOrder / SellerDeliverOrder in handlers/orders.go). Callers
 * re-fetch the order to pick up the new timestamps.
 */
export interface OrderStatusAck {
  status: string; // "picked_up" | "delivered"
}

/** PATCH /seller/orders/{id}/escalate response (EscalateToUber in orders.go). */
export interface EscalateResult {
  status: string; // "dispatched"
  provider: string; // "uber_direct" | "doordash_drive"
  delivery_id: string;
  tracking_url: string;
}

// ── Deals ────────────────────────────────────────────────────

/** models.Deal — a limited-time promotion for the seller's restaurant. */
export interface SellerDeal {
  id: string;
  restaurant_id: string;
  title: string;
  description: string;
  image_url: string;
  menu_item_id?: string;
  discount_type: DiscountType;
  discount_value: number; // percent for "percentage", cents for "fixed"
  min_order_amount?: number; // cents
  starts_at: string;
  expires_at: string;
  is_active: boolean;
  created_at: string;
  updated_at: string;
}

/** POST /seller/deals body (CreateDealRequest in deals.go). */
export interface CreateDealRequest {
  title: string;
  description?: string;
  image_url?: string;
  menu_item_id?: string;
  discount_type: DiscountType;
  discount_value: number;
  min_order_amount?: number;
  starts_at?: string;
  expires_at: string;
}

// ── Uploads ──────────────────────────────────────────────────

/** POST /uploads/presign body (PresignRequest in uploads.go). */
export interface PresignRequest {
  /** Allowlisted key prefix: "restaurant/cover", "restaurant/logo", "restaurant/certificate", "menu_item", "deal". */
  kind: string;
  /** "image/jpeg", "image/png", "image/webp", "image/heic". */
  content_type: string;
}

/** POST /uploads/presign response (storage.PresignResult). */
export interface PresignResult {
  /** Short-lived S3 PUT URL ("stub://…" in dev when no real S3 is wired). */
  upload_url: string;
  /** Durable public URL to persist on the record after the PUT succeeds. */
  public_url: string;
}

// ── POS integrations ─────────────────────────────────────────

/** GET /seller/integrations row (anonymous struct in pos_integrations.go). */
export interface POSIntegration {
  id: string;
  provider: string; // "clover"
  merchant_id: string;
  is_active: boolean;
  created_at: string;
  last_used_at?: string;
}
