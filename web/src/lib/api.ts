const API_BASE = process.env.NEXT_PUBLIC_API_URL || "https://koshereats-api.fly.dev/api/v1";

interface FetchOptions extends RequestInit {
  token?: string;
}

async function fetchAPI<T>(endpoint: string, options: FetchOptions = {}): Promise<T> {
  const { token, ...fetchOptions } = options;

  const headers: HeadersInit = {
    "Content-Type": "application/json",
    ...options.headers,
  };

  if (token) {
    (headers as Record<string, string>)["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(`${API_BASE}${endpoint}`, {
    ...fetchOptions,
    headers,
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Request failed" }));
    throw new Error(error.error || `HTTP ${res.status}`);
  }

  return res.json();
}

// Auth
export const auth = {
  register: (data: { email: string; password: string; first_name: string; last_name: string; phone: string }) =>
    fetchAPI("/auth/register", { method: "POST", body: JSON.stringify(data) }),

  login: (data: { email: string; password: string }) =>
    fetchAPI("/auth/login", { method: "POST", body: JSON.stringify(data) }),

  refresh: (refresh_token: string) =>
    fetchAPI("/auth/refresh", { method: "POST", body: JSON.stringify({ refresh_token }) }),
};

// Restaurants
export const restaurants = {
  list: (params?: { lat?: number; lng?: number }) => {
    const query = params ? `?lat=${params.lat}&lng=${params.lng}` : "";
    return fetchAPI(`/restaurants${query}`);
  },

  get: (id: string) => fetchAPI(`/restaurants/${id}`),

  getMenu: (id: string) => fetchAPI(`/restaurants/${id}/menu`),

  search: (q: string) => fetchAPI(`/restaurants/search?q=${encodeURIComponent(q)}`),
};

// Cart
export const cart = {
  get: (token: string) => fetchAPI("/cart", { token }),

  addItem: (token: string, data: { menu_item_id: string; restaurant_id: string; quantity: number; notes?: string }) =>
    fetchAPI("/cart/items", { method: "POST", token, body: JSON.stringify(data) }),

  updateItem: (token: string, itemId: string, data: { quantity: number; notes?: string }) =>
    fetchAPI(`/cart/items/${itemId}`, { method: "PATCH", token, body: JSON.stringify(data) }),

  removeItem: (token: string, itemId: string) =>
    fetchAPI(`/cart/items/${itemId}`, { method: "DELETE", token }),

  clear: (token: string) => fetchAPI("/cart", { method: "DELETE", token }),
};

// Orders
export const orders = {
  create: (token: string, data: { restaurant_id: string; delivery_address: string; delivery_lat: number; delivery_lng: number; payment_intent_id: string }) =>
    fetchAPI("/orders", { method: "POST", token, body: JSON.stringify(data) }),

  list: (token: string) => fetchAPI("/orders", { token }),

  get: (token: string, id: string) => fetchAPI(`/orders/${id}`, { token }),

  cancel: (token: string, id: string) =>
    fetchAPI(`/orders/${id}/cancel`, { method: "PATCH", token }),
};

// Payments
export const payments = {
  createIntent: (token: string, data: { tip?: number } = {}) =>
    fetchAPI("/payments/intent", { method: "POST", token, body: JSON.stringify(data) }),
};

// User
export const user = {
  getProfile: (token: string) => fetchAPI("/user/profile", { token }),

  updateProfile: (token: string, data: { first_name: string; last_name: string; phone: string }) =>
    fetchAPI("/user/profile", { method: "PUT", token, body: JSON.stringify(data) }),

  listAddresses: (token: string) => fetchAPI("/user/addresses", { token }),

  addAddress: (token: string, data: { label: string; street: string; city: string; state: string; zip_code: string; lat: number; lng: number }) =>
    fetchAPI("/user/addresses", { method: "POST", token, body: JSON.stringify(data) }),

  deleteAddress: (token: string, id: string) =>
    fetchAPI(`/user/addresses/${id}`, { method: "DELETE", token }),
};
