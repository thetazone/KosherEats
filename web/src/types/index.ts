export type UserRole = "consumer" | "seller" | "admin";
export type KosherCertification = "OU" | "OK" | "Kof-K" | "Star-K" | "cRc" | "Badatz" | "Chof-K" | "other";
export type OrderStatus = "scheduled" | "pending" | "accepted" | "preparing" | "ready" | "picked_up" | "delivered" | "cancelled" | "rejected";
export type DiscountType = "percentage" | "fixed" | "bogo";

export interface User {
  id: string;
  email: string;
  first_name: string;
  last_name: string;
  phone: string;
  role: UserRole;
  vertical?: string; // "kosher" | "vegan" — brand the account belongs to
  avatar_url?: string;
  // Consumer verification gate: the backend blocks payments.intent and
  // orders.create with 403 "verification_required" until BOTH are true.
  // Optional because pre-gate sessions may have a cached user without them.
  email_verified?: boolean;
  phone_verified?: boolean;
  created_at: string;
  updated_at: string;
}

// Token bundle returned by /auth/login, /auth/register, and the OTP verify
// sign-in paths (backend handlers/auth.go AuthResponse).
export interface AuthResponse {
  token: string;
  refresh_token: string;
  user: User;
}

export interface Restaurant {
  id: string;
  owner_id: string;
  name: string;
  description: string;
  image_url: string;
  cover_image_url?: string;
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
  // Photo of the kosher certificate (backend always sends the field; empty
  // string when the seller hasn't uploaded one yet).
  kosher_certificate_url?: string;
  cuisine_type: string[];
  rating: number;
  review_count: number;
  delivery_fee: number;
  min_order: number;
  est_delivery_min: number;
  est_delivery_max: number;
  is_open: boolean;
  is_active: boolean;
}

export interface MenuCategory {
  id: string;
  restaurant_id: string;
  name: string;
  sort_order: number;
  items?: MenuItem[];
}

export interface MenuItem {
  id: string;
  restaurant_id: string;
  category_id: string;
  name: string;
  description: string;
  image_url?: string;
  price: number;
  is_meat: boolean;
  is_dairy: boolean;
  is_pareve: boolean;
  is_available: boolean;
  sort_order?: number;
  modifier_groups?: ModifierGroup[];
}

// ModifierGroup is a set of selectable options on a menu item — e.g.
// "Choose your size" (required, exactly 1) or "Add-ons" (optional, up to 5).
export interface ModifierGroup {
  id: string;
  menu_item_id: string;
  name: string;
  description?: string;
  is_required: boolean;
  min_selections: number;
  max_selections: number;
  sort_order: number;
  modifiers: Modifier[];
}

// Modifier is a single option inside a ModifierGroup. price_delta (cents) is
// added to the base item price when selected; zero or negative is valid.
export interface Modifier {
  id: string;
  group_id: string;
  name: string;
  price_delta: number;
  is_default: boolean;
  is_available: boolean;
  sort_order: number;
}

// SelectedModifier is the snapshot shape stored on cart/order items — name and
// price are copied at selection time so display/pricing stay stable even if
// the seller later edits the underlying modifier.
export interface SelectedModifier {
  id: string;
  group_id: string;
  group_name: string;
  name: string;
  price_delta: number;
}

export interface Cart {
  id: string;
  user_id: string;
  restaurant_id: string;
  items: CartItem[];
  subtotal: number;
}

export interface CartItem {
  id: string;
  cart_id: string;
  menu_item_id: string;
  name: string;
  price: number; // per-unit, already includes modifier deltas
  quantity: number;
  notes?: string;
  selected_modifiers?: SelectedModifier[];
}

export interface Order {
  id: string;
  user_id: string;
  restaurant_id: string;
  restaurant_name: string;
  // Present on single-order loads (GetOrder joins the restaurant row);
  // omitted when 0 (Go omitempty).
  restaurant_lat?: number;
  restaurant_lng?: number;
  status: OrderStatus;
  items: OrderItem[];
  subtotal: number;
  // Deal discount applied to the subtotal before tax, in cents (0 when no
  // deal). subtotal - discount + delivery_fee + service_fee + tax +
  // courier_tip == total.
  discount?: number;
  delivery_fee: number;
  service_fee: number;
  tax: number;
  total: number;
  delivery_address: string;
  // (0,0) for pickup orders — mirror the iOS guard and treat that as "no
  // delivery coordinate" rather than a real point.
  delivery_lat?: number;
  delivery_lng?: number;
  est_delivery_time: string;
  // Consumer-chosen scheduled delivery time (RFC3339); absent for ASAP.
  scheduled_for?: string | null;
  // Courier assignment (platform deliveries only).
  courier_id?: string | null;
  courier?: CourierPublic | null;
  claimed_at?: string | null;
  picked_up_at?: string | null;
  delivered_at?: string | null;
  courier_payout?: number;
  courier_tip?: number;
  // Populated once the consumer has rated their courier (1-5).
  courier_rating?: number | null;
  // Drop-off photo from the courier, when one was taken.
  delivery_proof_url?: string;
  // "delivery" (default) or "pickup".
  fulfillment_type?: "delivery" | "pickup";
  // External courier dispatch (Uber Direct / DoorDash Drive fallback).
  external_delivery_id?: string | null;
  external_provider?: string | null;
  external_tracking_url?: string | null;
  // Delivery mode for this order: "platform" | "external" | "restaurant"
  // (self-delivery). Backend COALESCEs to the restaurant default.
  delivery_mode?: string;
  created_at: string;
  updated_at: string;
}

// CourierPublic is the consumer-visible slice of the assigned courier
// (backend models.CourierPublic). lat/lng are the courier's last known
// position snapshot; live updates arrive via the order location SSE stream.
export interface CourierPublic {
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

// CourierLocationEvent is one SSE "location" event on
// GET /orders/{id}/location/stream (backend broker.LocationEvent).
export interface CourierLocationEvent {
  order_id: string;
  lat: number;
  lng: number;
  heading: number;
  speed: number;
  at: string;
}

export interface OrderItem {
  id: string;
  order_id: string;
  menu_item_id: string;
  name: string;
  price: number;
  quantity: number;
  notes?: string;
  selected_modifiers?: SelectedModifier[];
}

// Deal matches the backend's consumer-facing DealWithItem: the deal row plus
// restaurant and (optionally) linked menu-item info for rendering deal cards.
// All money fields are integer cents.
export interface Deal {
  id: string;
  restaurant_id: string;
  title: string;
  description: string;
  image_url: string;
  menu_item_id?: string | null;
  discount_type: DiscountType;
  discount_value: number; // percentage: 0-100; fixed: cents; bogo: unused
  min_order_amount?: number | null; // cents
  starts_at: string;
  expires_at: string;
  is_active: boolean;
  created_at: string;
  updated_at: string;
  restaurant_name: string;
  restaurant_image_url: string;
  menu_item_name?: string | null;
  menu_item_price?: number | null; // cents
  menu_item_image_url?: string | null;
}

// Address is a saved consumer delivery address (backend handlers/user.go).
// lat/lng are stored verbatim (the backend does not geocode) and feed
// delivery routing plus the distance-based delivery-fee quote.
export interface Address {
  id: string;
  label: string;
  street: string;
  apt?: string;
  city: string;
  state: string;
  zip_code: string;
  lat: number;
  lng: number;
  is_default: boolean;
}

// ChatMessage is one message in an order-scoped chat thread shared by the
// consumer, seller, and assigned courier. sender_role comes from the JWT
// server-side and cannot be spoofed by clients.
export interface ChatMessage {
  id: string;
  order_id: string;
  sender_user_id: string;
  sender_role: string; // "consumer" | "seller" | "courier" | "admin"
  text: string;
  created_at: string;
}

// NotificationPreferences is the per-user push opt-in state. Backend defaults
// all three to true until the user toggles one off. PUT requires all fields.
export interface NotificationPreferences {
  order_updates: boolean;
  chat_messages: boolean;
  promotions: boolean;
}

// LinkedProvider is one linked sign-in method on the account.
export interface LinkedProvider {
  provider: string; // "google" | "apple" | "phone" | "email"
  created_at: string;
}

// DeliveryQuote is the dynamic delivery-fee quote for a restaurant → address
// route, fetched by checkout before payment. Fees are integer cents.
export interface DeliveryQuote {
  delivery_fee: number;
  est_minutes: number;
  provider: string; // "uber_direct" | "doordash_drive" | "self_delivery" | "flat_rate"
  provider_fee: number;
}
