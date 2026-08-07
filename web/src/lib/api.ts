const API_BASE = process.env.NEXT_PUBLIC_API_URL || "https://koshereats-api.fly.dev/api/v1";

interface FetchOptions extends RequestInit {
  token?: string;
}

// localStorage keys the app already uses (see app/auth/page.tsx).
const TOKEN_KEY = "token";
const REFRESH_TOKEN_KEY = "refresh_token";
const USER_KEY = "user";

function getStored(key: string): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(key);
}

function clearAuthTokens() {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem(REFRESH_TOKEN_KEY);
  window.localStorage.removeItem(USER_KEY);
}

// Single in-flight refresh shared across concurrent 401s so a burst of
// requests (e.g. mid-checkout) only triggers one /auth/refresh round-trip.
let refreshInFlight: Promise<string | null> | null = null;

async function runRefresh(): Promise<string | null> {
  const refreshToken = getStored(REFRESH_TOKEN_KEY);
  if (!refreshToken) return null;

  try {
    const data = (await auth.refresh(refreshToken)) as {
      token?: string;
      refresh_token?: string;
    };
    if (typeof window !== "undefined" && data?.token) {
      window.localStorage.setItem(TOKEN_KEY, data.token);
      if (data.refresh_token) {
        window.localStorage.setItem(REFRESH_TOKEN_KEY, data.refresh_token);
      }
    }
    return data?.token ?? null;
  } catch {
    // Refresh itself failed — drop the dead session so the next guarded
    // read sees a logged-out state, and let the original 401 propagate.
    clearAuthTokens();
    return null;
  }
}

// Resolve the shared refresh once, then clear it so a later expiry can retry.
function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = runRefresh().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

function buildHeaders(token: string | undefined, extra: HeadersInit | undefined): HeadersInit {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(extra as Record<string, string> | undefined),
  };
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }
  return headers;
}

async function fetchAPI<T>(endpoint: string, options: FetchOptions = {}): Promise<T> {
  const { token, ...fetchOptions } = options;

  // Idempotent GETs are safe to retry once on a transient network failure.
  const method = (fetchOptions.method || "GET").toUpperCase();
  const isIdempotent = method === "GET";
  const isRefreshCall = endpoint === "/auth/refresh";
  const callerSignal = options.signal;

  // Bug B: never hang forever on a stalled network. Each attempt gets its OWN
  // fresh timeout so a retry isn't handed an already-aborted signal (a fired
  // AbortSignal.timeout stays aborted, which makes fetch reject instantly).
  // A caller-supplied signal is reused verbatim and we never retry past it.
  const doFetch = async (authToken: string | undefined): Promise<Response> => {
    const signal = callerSignal ?? AbortSignal.timeout(15000);
    try {
      return await fetch(`${API_BASE}${endpoint}`, {
        ...fetchOptions,
        headers: buildHeaders(authToken, options.headers),
        signal,
      });
    } catch (err) {
      if (err instanceof Error && (err.name === "AbortError" || err.name === "TimeoutError")) {
        throw new Error("Connection timed out");
      }
      throw err;
    }
  };

  let res: Response;
  try {
    res = await doFetch(token);
  } catch (err) {
    // Bug B: one automatic retry with short backoff for idempotent GETs only.
    // Retry on a fresh-timeout abort AND on genuine transient network errors
    // (TypeError: Failed to fetch — DNS hiccup, connection reset). Never retry
    // when the caller's own signal aborted — that's an intentional cancel.
    const retriable =
      isIdempotent &&
      !(callerSignal?.aborted ?? false) &&
      ((err instanceof Error && err.message === "Connection timed out") ||
        err instanceof TypeError);
    if (retriable) {
      await new Promise((resolve) => setTimeout(resolve, 300));
      res = await doFetch(token);
    } else {
      throw err;
    }
  }

  // Bug A: silent 15-min access-token expiry. On a 401, try one refresh and
  // replay the original request with the new access token.
  if (res.status === 401 && !isRefreshCall && getStored(REFRESH_TOKEN_KEY)) {
    const newToken = await refreshAccessToken();
    if (newToken) {
      res = await doFetch(newToken);
    }
  }

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
//
// Every consumer restaurant read opts into preview listings (agency-certified
// restaurants seeded before their owners onboard) with include_previews=1,
// added HERE and only here — the backend defaults to the old orderable-only
// feed for clients that don't send it. The stored access token also rides
// along: these routes sit behind OptionalAuthMiddleware, so a valid token
// personalizes `requested_by_me` while an expired/absent one silently means
// anonymous results — never an error.
function restaurantQuery(params: Record<string, string | number | undefined> = {}): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") query.set(key, String(value));
  }
  query.set("include_previews", "1");
  return `?${query.toString()}`;
}

function optionalToken(): string | undefined {
  return getStored(TOKEN_KEY) ?? undefined;
}

export const restaurants = {
  list: (params?: { lat?: number; lng?: number; cuisine?: string }) =>
    fetchAPI(`/restaurants${restaurantQuery(params)}`, { token: optionalToken() }),

  get: (id: string) => fetchAPI(`/restaurants/${id}${restaurantQuery()}`, { token: optionalToken() }),

  getMenu: (id: string) => fetchAPI(`/restaurants/${id}/menu${restaurantQuery()}`, { token: optionalToken() }),

  search: (q: string) => fetchAPI(`/restaurants/search${restaurantQuery({ q })}`, { token: optionalToken() }),

  // "Request restaurant" toggle on a preview listing (tap on = request, tap
  // again = retract). Auth required; live restaurants 400.
  request: (token: string, id: string) =>
    fetchAPI(`/restaurants/${id}/request`, { method: "POST", token }),
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
  createIntent: (token: string, data: { tip?: number; delivery_address?: string } = {}) =>
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
