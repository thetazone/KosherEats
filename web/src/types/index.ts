export type UserRole = "consumer" | "seller" | "admin";
export type KosherCertification = "OU" | "OK" | "Kof-K" | "Star-K" | "cRc" | "Badatz" | "Chof-K" | "other";
export type OrderStatus = "scheduled" | "pending" | "accepted" | "preparing" | "ready" | "picked_up" | "delivered" | "cancelled" | "rejected";

export interface User {
  id: string;
  email: string;
  first_name: string;
  last_name: string;
  phone: string;
  role: UserRole;
  avatar_url?: string;
  created_at: string;
  updated_at: string;
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
  cuisine_type: string[];
  rating: number;
  review_count: number;
  delivery_fee: number;
  min_order: number;
  est_delivery_min: number;
  est_delivery_max: number;
  is_open: boolean;
  is_active: boolean;
  // Preview-listing fields (API may omit every one of them — old responses
  // and zero values are dropped server-side). Absent means: orderable,
  // standard listing, no requests. Read via the isPreviewListing helper or
  // with explicit defaults (`r.request_count ?? 0`), never bare.
  orderable?: boolean;
  listing_visibility?: "standard" | "preview";
  request_count?: number;
  requested_by_me?: boolean;
}

// A preview listing is browsable but not orderable: seeded before the owner
// onboards, it renders grayed out with a "Request restaurant" control and no
// cart. Absent `orderable` defaults to true (old API responses).
export function isPreviewListing(r: Pick<Restaurant, "orderable">): boolean {
  return r.orderable === false;
}

export interface RestaurantRequestState {
  requested: boolean;
  request_count: number;
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
  price: number;
  quantity: number;
  notes?: string;
}

export interface Order {
  id: string;
  user_id: string;
  restaurant_id: string;
  restaurant_name: string;
  status: OrderStatus;
  items: OrderItem[];
  subtotal: number;
  delivery_fee: number;
  service_fee: number;
  tax: number;
  total: number;
  delivery_address: string;
  est_delivery_time: string;
  created_at: string;
  updated_at: string;
}

export interface OrderItem {
  id: string;
  order_id: string;
  menu_item_id: string;
  name: string;
  price: number;
  quantity: number;
  notes?: string;
}
