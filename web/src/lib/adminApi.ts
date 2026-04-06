// Admin API client. Uses a separate token stored under "ke_admin_token" so
// the admin session doesn't collide with any consumer web session.

const API_BASE = process.env.NEXT_PUBLIC_API_URL || "https://koshereats-api.fly.dev/api/v1";

const TOKEN_KEY = "ke_admin_token";

export const adminAuth = {
  save(token: string) {
    if (typeof window !== "undefined") {
      localStorage.setItem(TOKEN_KEY, token);
    }
  },
  get(): string | null {
    if (typeof window === "undefined") return null;
    return localStorage.getItem(TOKEN_KEY);
  },
  clear() {
    if (typeof window !== "undefined") {
      localStorage.removeItem(TOKEN_KEY);
    }
  },
};

async function adminFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = adminAuth.get();
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: `HTTP ${res.status}` }));
    throw new Error(err.error || `HTTP ${res.status}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

export interface AdminStats {
  total_restaurants: number;
  active_restaurants: number;
  total_couriers: number;
  approved_couriers: number;
  pending_couriers: number;
  today_orders: number;
  today_revenue: number; // cents
  lifetime_orders: number;
}

export interface AdminRestaurant {
  id: string;
  owner_id: string;
  name: string;
  description: string;
  image_url: string;
  cover_image_url: string;
  phone: string;
  email: string;
  street: string;
  city: string;
  state: string;
  zip_code: string;
  lat: number;
  lng: number;
  kosher_certification: string;
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
}

export interface AdminCourier {
  id: string;
  email: string;
  first_name: string;
  last_name: string;
  phone: string;
  created_at: string;
  onboarding_status: "pending_info" | "pending_documents" | "pending_background" | "approved" | "rejected" | "suspended";
  vehicle_type: string;
  vehicle_make: string;
  vehicle_model: string;
  license_plate: string;
  drivers_license_number: string;
  background_check_status: string;
  total_deliveries: number;
  rating: number;
  is_online: boolean;
  payout_ready: boolean;
}

/**
 * Full courier record returned by /admin/couriers/{id}. Extends the list
 * payload with the four document URLs so the review modal can render the
 * uploaded license / insurance / registration / selfie images.
 */
export interface AdminCourierDetail extends AdminCourier {
  phone_verified: boolean;
  vehicle_year: number;
  vehicle_color: string;
  drivers_license_url: string;
  insurance_url: string;
  vehicle_registration_url: string;
  profile_photo_url: string;
  background_check_ref: string;
}

export interface AdminOrder {
  id: string;
  user_id: string;
  restaurant_id: string;
  restaurant_name: string;
  status: string;
  subtotal: number;
  delivery_fee: number;
  service_fee: number;
  tax: number;
  total: number;
  courier_tip: number;
  delivery_address: string;
  created_at: string;
}

export const adminApi = {
  login: (email: string, password: string) =>
    fetch(`${API_BASE}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password }),
    }).then(async (r) => {
      if (!r.ok) throw new Error("Invalid credentials");
      return r.json();
    }),

  stats: () => adminFetch<AdminStats>("/admin/stats"),

  restaurants: () => adminFetch<AdminRestaurant[]>("/admin/restaurants"),
  createRestaurant: (body: Partial<AdminRestaurant>) =>
    adminFetch<{ id: string }>("/admin/restaurants", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  createSeller: (body: { email: string; password: string; first_name: string; last_name: string; phone: string }) =>
    adminFetch<{ id: string }>("/admin/sellers", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  couriers: () => adminFetch<AdminCourier[]>("/admin/couriers"),
  courierDetail: (id: string) => adminFetch<AdminCourierDetail>(`/admin/couriers/${id}`),
  approveCourier: (id: string) =>
    adminFetch<{ status: string }>(`/admin/couriers/${id}/approve`, { method: "PATCH" }),
  rejectCourier: (id: string) =>
    adminFetch<{ status: string }>(`/admin/couriers/${id}/reject`, { method: "PATCH" }),

  orders: () => adminFetch<AdminOrder[]>("/admin/orders"),
};

export function formatCents(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}
