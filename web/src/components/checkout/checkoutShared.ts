// Shared checkout plumbing used by app/cart/page.tsx (pending-order recovery)
// and components/checkout/CheckoutPanel.tsx (the live checkout flow). All
// money values are integer cents.

import { auth as authApi, orders as ordersApi } from "@/lib/api";
import type { Address } from "@/types";

export const VERIFY_ROUTE = "/account/verify?next=/cart";

// Persisted between confirmPayment (charge captured) and a successful
// orders.create so a network blip / expired token / 5xx can never leave the
// customer charged with no order — the order is re-attempted on next mount.
// orders.create is idempotent on payment_intent_id, so retrying is safe.
// tip/fulfillment_type must echo what the PaymentIntent was priced with —
// CreateOrder validates both against the PI's server-side stamp. They are
// optional only for pending orders persisted by an older build.
// scheduled_for (RFC3339, omitted = ASAP) and applied_deal_id ride along so a
// recovered order keeps its schedule and its deal (CreateOrder re-resolves the
// deal to reconcile the recorded total with the charge).
export interface PendingOrder {
  payment_intent_id: string;
  restaurant_id: string;
  delivery_address: string;
  delivery_lat: number;
  delivery_lng: number;
  tip?: number;
  fulfillment_type?: "delivery" | "pickup";
  scheduled_for?: string;
  applied_deal_id?: string;
}

const PENDING_ORDER_KEY = "pending_order";
const ORDER_RETRY_DELAYS_MS = [500, 1500, 4000];

export function loadPendingOrder(): PendingOrder | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(PENDING_ORDER_KEY);
    return raw ? (JSON.parse(raw) as PendingOrder) : null;
  } catch {
    return null;
  }
}

export function savePendingOrder(p: PendingOrder): void {
  window.localStorage.setItem(PENDING_ORDER_KEY, JSON.stringify(p));
}

export function clearPendingOrder(): void {
  window.localStorage.removeItem(PENDING_ORDER_KEY);
}

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

// The backend hard-gates consumer transactions (payments.intent AND
// orders.create) with 403 {"error":"verification_required",...} until both
// email_verified and phone_verified are true — fetchAPI surfaces that body's
// error string as the Error message. We intercept it and route into
// /account/verify?next=/cart instead of showing a raw error.
export function isVerificationRequired(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("verification_required");
}

// orders.create is idempotent on payment_intent_id: if the FIRST POST committed
// server-side but its response was lost (network blip / timeout / 5xx-after-
// commit), every replay of the same payment_intent_id returns HTTP 409
// "order already created for this payment" (or 200 with the existing order via
// the backend's replay short-circuit). The order DOES exist, so this conflict
// is a SUCCESS for our retry/recovery loop — we must clear the persisted
// intent and proceed, never treat it as a retriable failure.
export function isAlreadyCreated(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("409") || msg.includes("already created for this payment");
}

// Deal-validation 400s from POST /payments/intent or /orders — the backend's
// resolveDealDiscount error strings all contain "deal" ("deal not found",
// "deal has expired", "below the deal minimum", "deal already used", …).
// Callers drop the applied deal and re-quote without it.
export function isDealError(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("deal");
}

// The once-per-user deal index fired inside CreateOrder AFTER the charge was
// captured — the backend refunds the PaymentIntent and returns 409 "this deal
// has already been used — your payment was refunded". No order exists and no
// money is owed, so the pending order must be cleared, not retried.
export function isDealConflictRefunded(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("payment was refunded");
}

export function formatUSD(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

export function formatAddress(a: Address): string {
  return `${a.street}${a.apt ? ` ${a.apt}` : ""}, ${a.city}, ${a.state} ${a.zip_code}`;
}

// "3.50" / "3,50" → 350¢. Rounds (not truncates) so exact-cent inputs like
// 4.10 stay 410 — mirrors iOS Money.parseCents.
export function parseCents(text: string): number | null {
  const cleaned = text.replace(/,/g, ".").replace(/[^0-9.]/g, "");
  if (!cleaned) return null;
  const v = parseFloat(cleaned);
  return Number.isFinite(v) ? Math.round(v * 100) : null;
}

// Consumer-facing label for the quote/intent delivery provider slug.
export function providerLabel(provider: string): string {
  switch (provider) {
    case "uber_direct":
      return "Uber Direct";
    case "doordash_drive":
      return "DoorDash";
    case "self_delivery":
      return "Restaurant delivery";
    case "flat_rate":
      return "Standard delivery";
    default:
      return "Delivery";
  }
}

// Refresh the 15-min access token in place (the order POST can outlive it).
// Returns the new token, or null if refresh is unavailable/failed.
async function refreshToken(onTokenRefreshed?: (t: string) => void): Promise<string | null> {
  const rt = typeof window !== "undefined" ? window.localStorage.getItem("refresh_token") : null;
  if (!rt) return null;
  try {
    const data = (await authApi.refresh(rt)) as { token: string; refresh_token?: string };
    if (!data?.token) return null;
    window.localStorage.setItem("token", data.token);
    if (data.refresh_token) window.localStorage.setItem("refresh_token", data.refresh_token);
    onTokenRefreshed?.(data.token);
    return data.token;
  } catch {
    return null;
  }
}

export type SubmitOutcome = "ok" | "failed" | "verify" | "refunded";

// Re-POST a persisted order with backoff. On 401, refresh the token once and
// retry with the fresh one. Clears the persisted intent on success; leaves it
// in place on exhaustion so the next mount can try again. "verify" means the
// account tripped the verification gate — the pending order stays persisted
// so it is re-attempted when the user returns from /account/verify.
// "refunded" means the once-per-user deal guard fired server-side and the
// charge was refunded — the pending order is cleared, nothing to retry.
//
// probeExisting: recovery paths (page remount / manual retry) first ask
// GET /orders/by-payment-intent/{pi} whether the original POST actually
// committed before re-running CreateOrder. A 200 resolves the crash without
// touching the (possibly already-cleared) cart; a 404 or any network failure
// falls through to the idempotent POST loop. Fresh checkouts skip the probe —
// the order can't exist yet.
export async function submitPendingOrder(
  pending: PendingOrder,
  initialToken: string,
  options: { probeExisting?: boolean; onTokenRefreshed?: (t: string) => void } = {}
): Promise<SubmitOutcome> {
  let activeToken = initialToken;

  if (options.probeExisting) {
    try {
      await ordersApi.byPaymentIntent(activeToken, pending.payment_intent_id);
      clearPendingOrder();
      return "ok";
    } catch {
      // 404 (no order yet) or transient failure — fall through to the POST loop.
    }
  }

  for (let attempt = 0; attempt <= ORDER_RETRY_DELAYS_MS.length; attempt++) {
    try {
      await ordersApi.create(activeToken, {
        restaurant_id: pending.restaurant_id,
        delivery_address: pending.delivery_address,
        delivery_lat: pending.delivery_lat,
        delivery_lng: pending.delivery_lng,
        payment_intent_id: pending.payment_intent_id,
        ...(pending.tip !== undefined ? { tip: pending.tip } : {}),
        ...(pending.fulfillment_type ? { fulfillment_type: pending.fulfillment_type } : {}),
        ...(pending.scheduled_for ? { scheduled_for: pending.scheduled_for } : {}),
        ...(pending.applied_deal_id ? { applied_deal_id: pending.applied_deal_id } : {}),
      });
      clearPendingOrder();
      return "ok";
    } catch (err) {
      // The order already exists for this payment (lost-response replay).
      // Idempotency conflict == success: clear the intent and stop retrying.
      if (isAlreadyCreated(err)) {
        clearPendingOrder();
        return "ok";
      }
      // Deal-conflict refund: charge reversed server-side, no order to chase.
      if (isDealConflictRefunded(err)) {
        clearPendingOrder();
        return "refunded";
      }
      // 403 verification gate — retrying can't clear it; the user must
      // verify first. Keep the pending order for the post-verify retry.
      if (isVerificationRequired(err)) {
        return "verify";
      }
      if (isUnauthorized(err)) {
        const fresh = await refreshToken(options.onTokenRefreshed);
        if (fresh) {
          activeToken = fresh;
          continue; // retry immediately with the refreshed token
        }
      }
      if (attempt < ORDER_RETRY_DELAYS_MS.length) {
        await sleep(ORDER_RETRY_DELAYS_MS[attempt]);
        continue;
      }
      return "failed";
    }
  }
  return "failed";
}
