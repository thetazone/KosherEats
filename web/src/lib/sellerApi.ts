// Seller API client. Clones the fetchAPI semantics from lib/api.ts (15s
// per-attempt timeout, one retry for idempotent GETs, single-flight
// 401 -> refresh -> replay) but against its own token slot ("ke_seller_token")
// so a seller session never collides with a consumer or admin session in the
// same browser. Every /seller/* call also injects the active restaurant id as
// ?restaurant_id= (multi-restaurant support — the backend validates ownership
// and falls back to the seller's first restaurant when absent).

import type {
  CreateDealRequest,
  CreateRestaurantRequest,
  DashboardStats,
  DeliveryMode,
  EscalateResult,
  MenuImport,
  MenuItemRequest,
  ModifierGroupRequest,
  OrderStatusAck,
  POSIntegration,
  PresignResult,
  SellerAuthResponse,
  SellerDeal,
  SellerMenuCategory,
  SellerMenuItem,
  SellerModifierGroup,
  SellerOrder,
  SellerRestaurant,
  SellerUser,
  UpdateRestaurantRequest,
} from "@/types/seller";

const API_BASE = process.env.NEXT_PUBLIC_API_URL || "https://koshereats-api.fly.dev/api/v1";

const TOKEN_KEY = "ke_seller_token";
const REFRESH_TOKEN_KEY = "ke_seller_refresh_token";
const USER_KEY = "ke_seller_user";
const RESTAURANT_KEY = "ke_seller_restaurant_id";

// ── Session storage ──────────────────────────────────────────

export const sellerAuth = {
  save(res: SellerAuthResponse) {
    if (typeof window === "undefined") return;
    localStorage.setItem(TOKEN_KEY, res.token);
    localStorage.setItem(REFRESH_TOKEN_KEY, res.refresh_token);
    localStorage.setItem(USER_KEY, JSON.stringify(res.user));
  },
  getToken(): string | null {
    if (typeof window === "undefined") return null;
    return localStorage.getItem(TOKEN_KEY);
  },
  getRefreshToken(): string | null {
    if (typeof window === "undefined") return null;
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  },
  getUser(): SellerUser | null {
    if (typeof window === "undefined") return null;
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as SellerUser;
    } catch {
      return null;
    }
  },
  clear() {
    if (typeof window === "undefined") return;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(RESTAURANT_KEY);
  },
  /** The restaurant currently selected in the layout's picker. */
  getActiveRestaurantId(): string | null {
    if (typeof window === "undefined") return null;
    return localStorage.getItem(RESTAURANT_KEY);
  },
  setActiveRestaurantId(id: string | null) {
    if (typeof window === "undefined") return;
    if (id) {
      localStorage.setItem(RESTAURANT_KEY, id);
    } else {
      localStorage.removeItem(RESTAURANT_KEY);
    }
  },
};

// ── Refresh (single-flight, shared across concurrent 401s) ───

let refreshInFlight: Promise<string | null> | null = null;

async function runRefresh(): Promise<string | null> {
  const refreshToken = sellerAuth.getRefreshToken();
  if (!refreshToken) return null;

  try {
    const res = await fetch(`${API_BASE}/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refreshToken }),
      signal: AbortSignal.timeout(15000),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = (await res.json()) as { token?: string; refresh_token?: string };
    if (typeof window !== "undefined" && data?.token) {
      localStorage.setItem(TOKEN_KEY, data.token);
      if (data.refresh_token) {
        localStorage.setItem(REFRESH_TOKEN_KEY, data.refresh_token);
      }
    }
    return data?.token ?? null;
  } catch {
    // Refresh itself failed — drop the dead session so the layout guard sees
    // a logged-out state on the next check, and let the original 401 surface.
    sellerAuth.clear();
    return null;
  }
}

function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = runRefresh().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

// ── Core fetch wrapper ───────────────────────────────────────

/**
 * Appends ?restaurant_id=<active> to /seller/* endpoints so every call is
 * scoped to the restaurant picked in the layout. /seller/restaurants itself
 * (list + create) is account-scoped, so it stays untouched.
 */
function withRestaurantId(path: string): string {
  if (!path.startsWith("/seller/")) return path;
  if (path === "/seller/restaurants" || path.startsWith("/seller/restaurants?")) return path;
  const restaurantId = sellerAuth.getActiveRestaurantId();
  if (!restaurantId) return path;
  const sep = path.includes("?") ? "&" : "?";
  return `${path}${sep}restaurant_id=${encodeURIComponent(restaurantId)}`;
}

async function sellerFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const endpoint = withRestaurantId(path);
  const method = (init.method || "GET").toUpperCase();
  const isIdempotent = method === "GET";
  const callerSignal = init.signal;

  // Never hang forever on a stalled network. Each attempt gets its OWN fresh
  // timeout so a retry isn't handed an already-aborted signal (a fired
  // AbortSignal.timeout stays aborted, which makes fetch reject instantly).
  // A caller-supplied signal is reused verbatim and we never retry past it.
  const doFetch = async (authToken: string | null): Promise<Response> => {
    const signal = callerSignal ?? AbortSignal.timeout(15000);
    try {
      return await fetch(`${API_BASE}${endpoint}`, {
        ...init,
        headers: {
          "Content-Type": "application/json",
          ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
          ...(init.headers as Record<string, string> | undefined),
        },
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
    res = await doFetch(sellerAuth.getToken());
  } catch (err) {
    // One automatic retry with short backoff for idempotent GETs only. Retry
    // on a fresh-timeout abort AND on genuine transient network errors
    // (TypeError: Failed to fetch — DNS hiccup, connection reset). Never retry
    // when the caller's own signal aborted — that's an intentional cancel.
    const retriable =
      isIdempotent &&
      !(callerSignal?.aborted ?? false) &&
      ((err instanceof Error && err.message === "Connection timed out") ||
        err instanceof TypeError);
    if (retriable) {
      await new Promise((resolve) => setTimeout(resolve, 300));
      res = await doFetch(sellerAuth.getToken());
    } else {
      throw err;
    }
  }

  // Silent access-token expiry: on a 401, try one refresh and replay the
  // original request with the new access token. /auth/* is exempt — a 401
  // there means bad credentials (or a bad refresh token), not an expired
  // session, so replaying would just repeat the failure.
  const isAuthCall = endpoint.startsWith("/auth/");
  if (res.status === 401 && !isAuthCall && sellerAuth.getRefreshToken()) {
    const newToken = await refreshAccessToken();
    if (newToken) {
      res = await doFetch(newToken);
    }
  }

  if (!res.ok) {
    const error = await res.json().catch(() => ({ error: "Request failed" }));
    throw new Error(error.error || `HTTP ${res.status}`);
  }

  if (res.status === 204) return undefined as T;
  return res.json();
}

// ── Endpoint groups ──────────────────────────────────────────

export const sellerApi = {
  auth: {
    /**
     * Shared /auth/login scoped to role=seller — (email, role) is the unique
     * account key on the backend, so the role field is load-bearing.
     * Callers must still verify user.role === "seller" before saving.
     */
    login: (email: string, password: string) =>
      sellerFetch<SellerAuthResponse>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password, role: "seller" }),
      }),
  },

  restaurants: {
    /** Every restaurant owned by this seller — feeds the layout's picker. */
    list: () => sellerFetch<SellerRestaurant[]>("/seller/restaurants"),
    create: (body: CreateRestaurantRequest) =>
      sellerFetch<SellerRestaurant>("/seller/restaurants", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    /** The active restaurant (resolved via ?restaurant_id= injection). */
    get: () => sellerFetch<SellerRestaurant>("/seller/restaurant"),
    update: (body: UpdateRestaurantRequest) =>
      sellerFetch<SellerRestaurant>("/seller/restaurant", {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    /** The big open/closed toggle. Opening requires admin approval. */
    setOpen: (isOpen: boolean) =>
      sellerFetch<SellerRestaurant>("/seller/restaurant/status", {
        method: "PATCH",
        body: JSON.stringify({ is_open: isOpen }),
      }),
  },

  dashboard: {
    stats: () => sellerFetch<DashboardStats>("/seller/dashboard/stats"),
  },

  menu: {
    /** Full menu including paused items and unavailable modifiers. */
    get: () => sellerFetch<SellerMenuCategory[]>("/seller/menu"),

    createItem: (body: MenuItemRequest) =>
      sellerFetch<SellerMenuItem>("/seller/menu/items", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    updateItem: (itemId: string, body: MenuItemRequest) =>
      sellerFetch<SellerMenuItem>(`/seller/menu/items/${itemId}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    deleteItem: (itemId: string) =>
      sellerFetch<{ status: string }>(`/seller/menu/items/${itemId}`, { method: "DELETE" }),
    setItemAvailability: (itemId: string, isAvailable: boolean) =>
      sellerFetch<SellerMenuItem>(`/seller/menu/items/${itemId}/availability`, {
        method: "PATCH",
        body: JSON.stringify({ is_available: isAvailable }),
      }),

    createModifierGroup: (itemId: string, body: ModifierGroupRequest) =>
      sellerFetch<SellerModifierGroup>(`/seller/menu/items/${itemId}/modifier-groups`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    updateModifierGroup: (groupId: string, body: ModifierGroupRequest) =>
      sellerFetch<SellerModifierGroup>(`/seller/menu/modifier-groups/${groupId}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    deleteModifierGroup: (groupId: string) =>
      sellerFetch<{ status: string }>(`/seller/menu/modifier-groups/${groupId}`, {
        method: "DELETE",
      }),

    createCategory: (name: string) =>
      sellerFetch<SellerMenuCategory>("/seller/menu/categories", {
        method: "POST",
        body: JSON.stringify({ name }),
      }),
    deleteCategory: (categoryId: string) =>
      sellerFetch<{ status: string }>(`/seller/menu/categories/${categoryId}`, {
        method: "DELETE",
      }),

    /** Self-serve UberEats menu import — async job drained out-of-process. */
    createImport: (sourceUrl: string) =>
      sellerFetch<MenuImport>("/seller/menu/imports", {
        method: "POST",
        body: JSON.stringify({ source: "ubereats", source_url: sourceUrl }),
      }),
    listImports: () => sellerFetch<MenuImport[]>("/seller/menu/imports"),
    getImport: (id: string) => sellerFetch<MenuImport>(`/seller/menu/imports/${id}`),
  },

  orders: {
    /** Newest-first, cursor = created_at of the last row (RFC3339). */
    list: (params: { limit?: number; cursor?: string } = {}) => {
      const query = new URLSearchParams();
      if (params.limit) query.set("limit", String(params.limit));
      if (params.cursor) query.set("cursor", params.cursor);
      const qs = query.toString();
      return sellerFetch<SellerOrder[]>(`/seller/orders${qs ? `?${qs}` : ""}`);
    },
    get: (id: string) => sellerFetch<SellerOrder>(`/seller/orders/${id}`),

    accept: (id: string) =>
      sellerFetch<SellerOrder>(`/seller/orders/${id}/accept`, { method: "PATCH" }),
    /** Rejects + refunds. The reason is optional and only feeds the consumer
     *  notification (RejectOrder reads the body with `_ = readJSON`). */
    reject: (id: string, reason?: string) =>
      sellerFetch<SellerOrder>(`/seller/orders/${id}/reject`, {
        method: "PATCH",
        ...(reason?.trim() ? { body: JSON.stringify({ reason: reason.trim() }) } : {}),
      }),
    markPreparing: (id: string) =>
      sellerFetch<SellerOrder>(`/seller/orders/${id}/preparing`, { method: "PATCH" }),
    markReady: (id: string) =>
      sellerFetch<SellerOrder>(`/seller/orders/${id}/ready`, { method: "PATCH" }),
    /** Pickup-order terminal step (customer collects at the counter). */
    complete: (id: string) =>
      sellerFetch<SellerOrder>(`/seller/orders/${id}/complete`, { method: "PATCH" }),
    /** Per-order override of who delivers: "restaurant" or "external". */
    setDeliveryMode: (id: string, mode: Extract<DeliveryMode, "restaurant" | "external">) =>
      sellerFetch<SellerOrder>(`/seller/orders/${id}/delivery-mode`, {
        method: "PATCH",
        body: JSON.stringify({ delivery_mode: mode }),
      }),
    /** Self-delivery flow: the restaurant's own driver picks up / delivers.
     *  Unlike the other transitions these return a bare {status} ack, not the
     *  updated order — re-fetch to see new timestamps (picked_up_at etc). */
    pickup: (id: string) =>
      sellerFetch<OrderStatusAck>(`/seller/orders/${id}/pickup`, { method: "PATCH" }),
    deliver: (id: string) =>
      sellerFetch<OrderStatusAck>(`/seller/orders/${id}/deliver`, { method: "PATCH" }),
    /** Hand an open self-delivery order off to an external provider. One-way;
     *  returns dispatch details, not the order (EscalateToUber in orders.go). */
    escalate: (id: string) =>
      sellerFetch<EscalateResult>(`/seller/orders/${id}/escalate`, { method: "PATCH" }),
  },

  deals: {
    list: () => sellerFetch<SellerDeal[]>("/seller/deals"),
    create: (body: CreateDealRequest) =>
      sellerFetch<SellerDeal>("/seller/deals", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    deactivate: (dealId: string) =>
      sellerFetch<{ status: string }>(`/seller/deals/${dealId}`, { method: "DELETE" }),
  },

  uploads: {
    /**
     * Get a short-lived S3 PUT URL for a photo upload. Seller-role kinds:
     * "restaurant/cover", "restaurant/logo", "restaurant/certificate",
     * "menu_item", "deal". Prefer the uploadImage() helper below, which
     * runs the full presign -> PUT -> public-URL dance.
     */
    presign: (kind: string, contentType: string) =>
      sellerFetch<PresignResult>("/uploads/presign", {
        method: "POST",
        body: JSON.stringify({ kind, content_type: contentType }),
      }),
  },

  integrations: {
    list: () => sellerFetch<POSIntegration[]>("/seller/integrations"),
    /** Returns { url } — where to send the seller for Clover OAuth. */
    cloverConnectUrl: () =>
      sellerFetch<{ url: string }>("/seller/integrations/clover/connect-url"),
    test: (id: string) =>
      sellerFetch<{ status: string; error?: string }>(`/seller/integrations/${id}/test`, {
        method: "POST",
      }),
    disconnect: (id: string) =>
      sellerFetch<{ status: string }>(`/seller/integrations/${id}`, { method: "DELETE" }),
  },
};

// Money DISPLAY helpers live in lib/format.ts — the ONE place cents become
// display dollars. The seller lane re-exports them (formatCents is the
// historical seller-side name) so every price renders through one shared
// formatter and no page carries its own `/ 100` math.
export { formatUSD as formatCents, centsToDollars } from "./format";

/**
 * Parse a dollars string ("12", "12.5", "$12.34", "12,34") into integer
 * cents. Returns null for anything that isn't a plain non-negative dollar
 * amount with at most 2 decimal places — mirrors CurrencyFormat.parseCents
 * in the iOS seller app.
 */
export function parseCents(input: string): number | null {
  const normalized = input.trim().replace(/^\$/, "").replace(",", ".");
  if (!normalized || !/^\d+(\.\d{1,2})?$/.test(normalized)) return null;
  const dollars = Number(normalized);
  if (!Number.isFinite(dollars)) return null;
  return Math.round(dollars * 100);
}

// ── Image upload helper ──────────────────────────────────────

/** Upload kinds the backend allows for the seller role (uploads.go allowlist). */
export type SellerUploadKind =
  | "restaurant/cover"
  | "restaurant/logo"
  | "restaurant/certificate"
  | "menu_item"
  | "deal";

/** Mirrors allowedContentTypes in backend/internal/handlers/uploads.go. */
const UPLOADABLE_CONTENT_TYPES = new Set([
  "image/jpeg",
  "image/jpg",
  "image/png",
  "image/heic",
  "image/webp",
]);

/**
 * Full photo-upload flow: presign -> PUT the bytes straight to S3 (the file
 * never passes through our API server) -> return the durable public URL to
 * persist on the record. Mirrors the iOS UploadService, including the dev
 * stub short-circuit ("stub://…" upload URLs mean no real S3 is wired up, so
 * skip the PUT and use the returned public URL as-is).
 */
export async function uploadImage(file: File, kind: SellerUploadKind): Promise<string> {
  const contentType = file.type.toLowerCase();
  if (!UPLOADABLE_CONTENT_TYPES.has(contentType)) {
    throw new Error("Unsupported image type — use a JPEG, PNG, WebP, or HEIC photo.");
  }

  const presign = await sellerApi.uploads.presign(kind, contentType);
  if (presign.upload_url.startsWith("stub://")) {
    return presign.public_url;
  }

  let res: Response;
  try {
    res = await fetch(presign.upload_url, {
      method: "PUT",
      headers: { "Content-Type": contentType },
      body: file,
      // Photos can be big; give the PUT longer than API calls get.
      signal: AbortSignal.timeout(60000),
    });
  } catch (err) {
    if (err instanceof Error && (err.name === "AbortError" || err.name === "TimeoutError")) {
      throw new Error("Photo upload timed out — check your connection and try again.");
    }
    throw new Error("Photo upload failed — check your connection and try again.");
  }
  if (!res.ok) {
    throw new Error("Photo upload failed — please try again.");
  }
  return presign.public_url;
}
