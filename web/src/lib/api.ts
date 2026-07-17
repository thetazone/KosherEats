import type {
  AuthResponse,
  ChatMessage,
  CourierLocationEvent,
  Deal,
  DeliveryQuote,
  LinkedProvider,
  NotificationPreferences,
  Order,
  Restaurant,
  User,
} from "@/types";

// Single source of truth for the API origin — import this instead of reading
// process.env.NEXT_PUBLIC_API_URL directly (see app/auth/page.tsx).
export const API_BASE = process.env.NEXT_PUBLIC_API_URL || "https://koshereats-api.fly.dev/api/v1";

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
    fetchAPI<AuthResponse>("/auth/register", { method: "POST", body: JSON.stringify(data) }),

  login: (data: { email: string; password: string }) =>
    fetchAPI<AuthResponse>("/auth/login", { method: "POST", body: JSON.stringify(data) }),

  refresh: (refresh_token: string) =>
    fetchAPI("/auth/refresh", { method: "POST", body: JSON.stringify({ refresh_token }) }),

  // Routes the unified email-entry UI: exists=true → "enter password",
  // exists=false → "create account". role is always "" (not exposed).
  emailCheck: (data: { email: string; role?: string; vertical?: string }) =>
    fetchAPI<{ exists: boolean; role: string }>("/auth/email/check", {
      method: "POST",
      body: JSON.stringify(data),
    }),

  // Password reset: forgot emails a 6-digit code (always 200 — no account
  // enumeration); reset trades code + new password for an updated credential.
  // role/vertical scope the lookup to the exact account and must match
  // between the two calls (defaults: consumer/kosher).
  forgot: (data: { email: string; role?: string; vertical?: string }) =>
    fetchAPI<{ message: string }>("/auth/password/forgot", {
      method: "POST",
      body: JSON.stringify(data),
    }),

  reset: (data: { email: string; code: string; new_password: string; role?: string; vertical?: string }) =>
    fetchAPI("/auth/password/reset", { method: "POST", body: JSON.stringify(data) }),

  // Email OTP for the email-signup flow: start emails a 6-digit code, verify
  // stamps the proof that /auth/register checks before creating the account.
  emailOtp: {
    start: (email: string) =>
      fetchAPI<{ status: string }>("/auth/email/start", {
        method: "POST",
        body: JSON.stringify({ email }),
      }),

    verify: (email: string, code: string) =>
      fetchAPI<{ status: string }>("/auth/email/verify", {
        method: "POST",
        body: JSON.stringify({ email, code }),
      }),
  },

  // Phone OTP login. start sends the SMS (E.164 phone required); verify
  // trades a valid code for tokens — signs in the existing (phone, role)
  // account or creates a new one using the optional profile fields.
  phoneOtp: {
    start: (phone: string) =>
      fetchAPI<{ status: string }>("/auth/phone/start", {
        method: "POST",
        body: JSON.stringify({ phone }),
      }),

    verify: (data: {
      phone: string;
      code: string;
      role?: string;
      vertical?: string;
      first_name?: string;
      last_name?: string;
      email?: string;
    }) => fetchAPI("/auth/phone/verify", { method: "POST", body: JSON.stringify(data) }),
  },
};

// Restaurants
export const restaurants = {
  // Pass lat/lng to get the list distance-ordered (nearest first, LIMIT 50);
  // without coords the backend falls back to rating order. Both coords must
  // be finite or we send neither — "?lat=undefined" would fail the backend's
  // ParseFloat and silently lose the distance sort.
  list: (params?: { lat: number; lng: number }) => {
    const query =
      params && Number.isFinite(params.lat) && Number.isFinite(params.lng)
        ? `?lat=${params.lat}&lng=${params.lng}`
        : "";
    return fetchAPI<Restaurant[]>(`/restaurants${query}`);
  },

  get: (id: string) => fetchAPI(`/restaurants/${id}`),

  getMenu: (id: string) => fetchAPI(`/restaurants/${id}/menu`),

  search: (q: string) =>
    fetchAPI<Restaurant[]>(`/restaurants/search?q=${encodeURIComponent(q)}`),

  // Personalised alternating familiar/unfamiliar list; falls back to
  // top-rated for guests. Pass the token to get personalisation (the route
  // uses optional auth). limit: 1-50, default 10.
  suggested: (params: { limit?: number; token?: string } = {}) =>
    fetchAPI<Restaurant[]>(
      `/restaurants/suggested${params.limit ? `?limit=${params.limit}` : ""}`,
      { token: params.token }
    ),
};

// Deals (consumer "Deals" tab — public routes with optional auth)
export const deals = {
  nearby: (token?: string) => fetchAPI<Deal[]>("/deals/nearby", { token }),

  forRestaurant: (restaurantId: string, token?: string) =>
    fetchAPI<Deal[]>(`/restaurants/${restaurantId}/deals`, { token }),
};

// Favorites
export const favorites = {
  list: (token: string) => fetchAPI<Restaurant[]>("/favorites", { token }),

  // Lightweight id list for heart-toggle state on browse screens.
  ids: (token: string) => fetchAPI<string[]>("/favorites/ids", { token }),

  add: (token: string, restaurantId: string) =>
    fetchAPI<{ status: string }>(`/favorites/${restaurantId}`, { method: "POST", token }),

  remove: (token: string, restaurantId: string) =>
    fetchAPI<{ status: string }>(`/favorites/${restaurantId}`, { method: "DELETE", token }),
};

// Delivery-fee quote for a restaurant → address route. Checkout calls this
// before creating the PaymentIntent so the displayed fee matches the charge.
export const deliveryQuote = (
  token: string,
  data: { restaurant_id: string; delivery_address: string; delivery_lat?: number; delivery_lng?: number }
) => fetchAPI<DeliveryQuote>("/delivery-quote", { method: "POST", token, body: JSON.stringify(data) });

// Cart
export const cart = {
  get: (token: string) => fetchAPI("/cart", { token }),

  // modifier_ids are the selected modifier option ids; the backend validates
  // each id belongs to the menu item, snapshots name/price_delta into
  // selected_modifiers, and bakes the deltas into the stored unit price.
  addItem: (
    token: string,
    data: {
      menu_item_id: string;
      restaurant_id: string;
      quantity: number;
      notes?: string;
      modifier_ids?: string[];
    }
  ) => fetchAPI("/cart/items", { method: "POST", token, body: JSON.stringify(data) }),

  updateItem: (token: string, itemId: string, data: { quantity: number; notes?: string }) =>
    fetchAPI(`/cart/items/${itemId}`, { method: "PATCH", token, body: JSON.stringify(data) }),

  removeItem: (token: string, itemId: string) =>
    fetchAPI(`/cart/items/${itemId}`, { method: "DELETE", token }),

  clear: (token: string) => fetchAPI("/cart", { method: "DELETE", token }),
};

// Live courier-location SSE stream (GET /orders/{id}/location/stream).
// EventSource cannot send an Authorization header, so we consume the stream
// with fetch + a minimal SSE parser over the response body. The server emits
// `event: location` frames with a JSON body plus `: ping` heartbeat comments
// every 25s. Resolves when the server closes the stream cleanly; rejects on
// HTTP/network errors and on abort (callers distinguish an intentional stop
// via `signal.aborted`). On a 401 we run the shared single-flight refresh
// once and retry with the new token, mirroring fetchAPI's replay behaviour.
async function streamOrderLocation(
  token: string,
  orderId: string,
  onEvent: (event: CourierLocationEvent) => void,
  signal: AbortSignal
): Promise<void> {
  const open = (authToken: string) =>
    fetch(`${API_BASE}/orders/${orderId}/location/stream`, {
      headers: {
        Authorization: `Bearer ${authToken}`,
        Accept: "text/event-stream",
      },
      cache: "no-store",
      signal,
    });

  let res = await open(token);
  if (res.status === 401 && getStored(REFRESH_TOKEN_KEY)) {
    const newToken = await refreshAccessToken();
    if (newToken) {
      res = await open(newToken);
    }
  }
  if (!res.ok || !res.body) {
    throw new Error(`Location stream failed (HTTP ${res.status})`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventName = "";
  let dataLines: string[] = [];

  // End of one SSE event (blank line): dispatch and reset per-event state.
  const dispatch = () => {
    const name = eventName || "message";
    const data = dataLines.join("\n");
    eventName = "";
    dataLines = [];
    if (name !== "location" || data === "") return;
    try {
      onEvent(JSON.parse(data) as CourierLocationEvent);
    } catch {
      // Malformed frame — skip it; the next ping will be along shortly.
    }
  };

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // Process complete lines; a trailing partial line stays buffered until
    // the next chunk completes it.
    let newlineIdx: number;
    while ((newlineIdx = buffer.indexOf("\n")) !== -1) {
      let line = buffer.slice(0, newlineIdx);
      buffer = buffer.slice(newlineIdx + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);

      if (line === "") {
        dispatch();
        continue;
      }
      if (line.startsWith(":")) continue; // heartbeat comment

      const colonIdx = line.indexOf(":");
      const field = colonIdx === -1 ? line : line.slice(0, colonIdx);
      let fieldValue = colonIdx === -1 ? "" : line.slice(colonIdx + 1);
      if (fieldValue.startsWith(" ")) fieldValue = fieldValue.slice(1);

      if (field === "event") eventName = fieldValue;
      else if (field === "data") dataLines.push(fieldValue);
      // id/retry fields are unused by this stream.
    }
  }
  dispatch(); // flush a final unterminated event, if any
}

// Orders
export const orders = {
  // tip is in cents. scheduled_for is RFC3339 (omit for ASAP).
  // applied_deal_id must match the id passed to payments.createIntent so the
  // recorded total agrees with the Stripe charge.
  create: (
    token: string,
    data: {
      restaurant_id: string;
      delivery_address: string;
      delivery_lat: number;
      delivery_lng: number;
      payment_intent_id: string;
      tip?: number;
      scheduled_for?: string;
      fulfillment_type?: "delivery" | "pickup";
      applied_deal_id?: string;
    }
  ) => fetchAPI("/orders", { method: "POST", token, body: JSON.stringify(data) }),

  list: (token: string) => fetchAPI("/orders", { token }),

  get: (token: string, id: string) => fetchAPI<Order>(`/orders/${id}`, { token }),

  // Returns the refreshed (cancelled) order so callers can update local
  // state without a follow-up GET.
  cancel: (token: string, id: string) =>
    fetchAPI<Order>(`/orders/${id}/cancel`, { method: "PATCH", token }),

  // Live courier location while the tracking screen is open. See
  // streamOrderLocation above for the SSE contract.
  streamLocation: streamOrderLocation,

  // Idempotent post-payment recovery: resolve the order created for a
  // PaymentIntent the client just confirmed (e.g. after a redirect or a
  // dropped create-order response).
  byPaymentIntent: (token: string, paymentIntentId: string) =>
    fetchAPI<Order>(`/orders/by-payment-intent/${encodeURIComponent(paymentIntentId)}`, { token }),

  // Rate the courier on a delivered order. stars: 1-5.
  rate: (token: string, id: string, data: { stars: number; comment?: string }) =>
    fetchAPI<{ stars: number }>(`/orders/${id}/rating`, {
      method: "POST",
      token,
      body: JSON.stringify(data),
    }),

  // Order-scoped chat thread shared by consumer, seller, and courier.
  // Poll list() every few seconds while the chat view is open.
  chat: {
    list: (token: string, orderId: string) =>
      fetchAPI<ChatMessage[]>(`/orders/${orderId}/chat`, { token }),

    send: (token: string, orderId: string, text: string) =>
      fetchAPI<ChatMessage>(`/orders/${orderId}/chat`, {
        method: "POST",
        token,
        body: JSON.stringify({ text }),
      }),
  },
};

// Payments
export const payments = {
  // tip is in cents. restaurant_id + delivery_address enable dynamic
  // delivery-fee quoting; applied_deal_id applies a deal's discount before
  // tax and must be repeated on orders.create; pickup skips delivery fee+tip.
  createIntent: (
    token: string,
    data: {
      tip?: number;
      delivery_address?: string;
      restaurant_id?: string;
      fulfillment_type?: "delivery" | "pickup";
      applied_deal_id?: string;
    } = {}
  ) => fetchAPI("/payments/intent", { method: "POST", token, body: JSON.stringify(data) }),

  // Server-side confirmation is a no-op (Stripe confirms client-side; the
  // webhook is the real signal) — kept for parity with the mobile clients.
  confirm: (token: string) =>
    fetchAPI<{ status: string }>("/payments/confirm", {
      method: "POST",
      token,
      body: JSON.stringify({}),
    }),

  // Stripe Customer bundle for the saved-payment-methods screen.
  customer: (token: string) => fetchAPI("/payments/customer", { token }),

  // Fresh SetupIntent client_secret for each "add a new card" flow.
  setupIntent: (token: string) =>
    fetchAPI<{ client_secret: string }>("/payments/setup-intent", {
      method: "POST",
      token,
      body: JSON.stringify({}),
    }),
};

// User
export const user = {
  // Includes email_verified/phone_verified — the flags that drive the
  // /account/verify gate (see RequireVerifiedMiddleware backend-side).
  getProfile: (token: string) => fetchAPI<User>("/user/profile", { token }),

  updateProfile: (token: string, data: { first_name: string; last_name: string; phone: string }) =>
    fetchAPI("/user/profile", { method: "PUT", token, body: JSON.stringify(data) }),

  listAddresses: (token: string) => fetchAPI("/user/addresses", { token }),

  addAddress: (token: string, data: { label: string; street: string; city: string; state: string; zip_code: string; lat: number; lng: number }) =>
    fetchAPI("/user/addresses", { method: "POST", token, body: JSON.stringify(data) }),

  deleteAddress: (token: string, id: string) =>
    fetchAPI(`/user/addresses/${id}`, { method: "DELETE", token }),

  setDefaultAddress: (token: string, id: string) =>
    fetchAPI(`/user/addresses/${id}/default`, { method: "PATCH", token }),

  // Verified add/change-email flow — the only way to change the account
  // email. start sends a 6-digit code to the new inbox; verify writes it
  // with email_verified=true (409 if another account owns it).
  emailChange: {
    start: (token: string, email: string) =>
      fetchAPI<{ status: string }>("/user/email/start", {
        method: "POST",
        token,
        body: JSON.stringify({ email }),
      }),

    verify: (token: string, email: string, code: string) =>
      fetchAPI<{ status: string }>("/user/email/verify", {
        method: "POST",
        token,
        body: JSON.stringify({ email, code }),
      }),
  },

  // Verified phone-change flow — the only way to change the account phone
  // (UpdateProfile no longer writes it). Phone must be E.164 (+15551234567).
  phoneChange: {
    start: (token: string, phone: string) =>
      fetchAPI<{ status: string }>("/user/phone/change/start", {
        method: "POST",
        token,
        body: JSON.stringify({ phone }),
      }),

    verify: (token: string, phone: string, code: string) =>
      fetchAPI<{ status: string }>("/user/phone/change/verify", {
        method: "POST",
        token,
        body: JSON.stringify({ phone, code }),
      }),
  },
};

// Notification preferences (per-user push opt-ins; defaults all true).
// PUT requires all three fields — partial updates are rejected by design.
export const notificationPreferences = {
  get: (token: string) =>
    fetchAPI<NotificationPreferences>("/user/notification-preferences", { token }),

  update: (token: string, prefs: NotificationPreferences) =>
    fetchAPI<NotificationPreferences>("/user/notification-preferences", {
      method: "PUT",
      token,
      body: JSON.stringify(prefs),
    }),
};

// Linked auth providers (account linking). For phone linking, first run
// auth.phoneOtp.start on the number, then pass phone + code here. Google and
// Apple linking pass the provider ID token (and nonce for Apple).
export const linkedProviders = {
  list: (token: string) => fetchAPI<LinkedProvider[]>("/user/linked-providers", { token }),

  link: (
    token: string,
    data: { provider: "google" | "apple" | "phone"; token?: string; phone?: string; code?: string; nonce?: string }
  ) => fetchAPI("/user/linked-providers", { method: "POST", token, body: JSON.stringify(data) }),

  // The backend refuses to remove the last remaining sign-in method (400).
  unlink: (token: string, provider: string) =>
    fetchAPI<{ status: string }>(`/user/linked-providers/${encodeURIComponent(provider)}`, {
      method: "DELETE",
      token,
    }),
};
