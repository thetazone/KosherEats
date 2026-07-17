"use client";

import {
  clearPendingOrder,
  isUnauthorized,
  loadPendingOrder,
  submitPendingOrder,
  VERIFY_ROUTE,
  type PendingOrder,
  type SubmitOutcome,
} from "@/components/checkout/checkoutShared";
import { CheckoutPanel } from "@/components/checkout/CheckoutPanel";
import { Header } from "@/components/layout/Header";
import { KosherBadge } from "@/components/restaurant/KosherBadge";
import { cart as cartApi, restaurants as restaurantsApi } from "@/lib/api";
import { formatUSD } from "@/lib/format";
import type { Cart, Restaurant } from "@/types";
import { Loader2, ShoppingCart, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

// The cart page owns cart loading/item mutations and the post-charge
// pending-order recovery machinery (a captured charge must ALWAYS converge to
// an order — see checkoutShared.PendingOrder). Everything checkout-shaped
// (fulfillment, address, tip, schedule, deals, Stripe modal) lives in
// components/checkout/CheckoutPanel.tsx.
export default function CartPage() {
  const router = useRouter();
  const [token, setToken] = useState<string | null>(null);
  const [cart, setCart] = useState<Cart | null>(null);
  // Full restaurant row (not just the name): the cart header repeats the
  // certification chip so kosher trust continues from browse → detail → cart.
  const [restaurant, setRestaurant] = useState<Restaurant | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [mutatingItemId, setMutatingItemId] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);

  // "finalizing" → charge captured, order POST in flight/retrying. We surface a
  // clear state instead of silently navigating away if orders.create fails.
  const [finalizing, setFinalizing] = useState(false);
  const [finalizeError, setFinalizeError] = useState<string | null>(null);

  useEffect(() => {
    const t = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!t) {
      router.replace("/auth");
      return;
    }
    setToken(t);
    void loadAll(t);
    // A charge was captured on a previous visit but the order POST never
    // confirmed — re-attempt it now so the customer is never charged with no
    // order. Idempotent on payment_intent_id (and probed first via
    // GET /orders/by-payment-intent, which resolves lost-response replays
    // without re-running CreateOrder).
    void recoverPendingOrder(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function handleUnauthorized() {
    window.localStorage.removeItem("token");
    router.replace("/auth");
  }

  // Shared landing for every submit path (fresh checkout, mount recovery,
  // manual retry). stillRetrying picks the first-failure vs retry copy.
  function handleOutcome(outcome: SubmitOutcome, stillRetrying: boolean) {
    setFinalizing(false);
    if (outcome === "ok") {
      router.push("/orders");
    } else if (outcome === "verify") {
      router.push(VERIFY_ROUTE);
    } else if (outcome === "refunded") {
      // The once-per-user deal guard fired inside CreateOrder — the backend
      // refunded the charge and no order exists. Nothing to retry; the cart
      // is still intact server-side.
      setFinalizeError(
        "That deal was already used, so no order was placed and your payment was refunded."
      );
    } else {
      setFinalizeError(
        stillRetrying
          ? "Still couldn't confirm your order. We'll keep retrying — please don't pay again."
          : "Your payment went through but we couldn't confirm your order. We'll keep retrying — please don't pay again."
      );
    }
  }

  async function recoverPendingOrder(t: string) {
    const pending = loadPendingOrder();
    if (!pending) return;
    setFinalizing(true);
    setFinalizeError(null);
    const outcome = await submitPendingOrder(pending, t, {
      probeExisting: true,
      onTokenRefreshed: setToken,
    });
    handleOutcome(outcome, false);
  }

  // Charge captured in the panel; the PendingOrder is already persisted to
  // localStorage. Submit it with retries — on exhaustion the banner keeps the
  // user here and the persisted intent re-attempts on the next mount.
  async function handlePaymentCaptured(pending: PendingOrder) {
    if (!token) return;
    setFinalizing(true);
    setFinalizeError(null);
    const outcome = await submitPendingOrder(pending, token, {
      onTokenRefreshed: setToken,
    });
    handleOutcome(outcome, false);
  }

  async function retryFinalize() {
    if (!token) return;
    const pending = loadPendingOrder();
    if (!pending) return;
    setFinalizing(true);
    setFinalizeError(null);
    const outcome = await submitPendingOrder(pending, token, {
      probeExisting: true,
      onTokenRefreshed: setToken,
    });
    handleOutcome(outcome, true);
  }

  async function loadAll(t: string) {
    setLoading(true);
    setLoadError(null);
    try {
      const c = (await cartApi.get(t)) as Cart;
      setCart(c);
      if (c.restaurant_id) {
        try {
          const r = (await restaurantsApi.get(c.restaurant_id)) as Restaurant;
          setRestaurant(r);
        } catch {
          // Non-fatal — cart is still usable without the restaurant label.
        }
      }
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setLoadError(err instanceof Error ? err.message : "Failed to load cart");
    } finally {
      setLoading(false);
    }
  }

  async function mutateQuantity(itemId: string, delta: number) {
    if (!token || !cart) return;
    const item = cart.items.find((i) => i.id === itemId);
    if (!item) return;
    const next = item.quantity + delta;

    setMutatingItemId(itemId);
    setMutationError(null);
    try {
      if (next <= 0) {
        await cartApi.removeItem(token, itemId);
      } else {
        await cartApi.updateItem(token, itemId, { quantity: next, notes: item.notes ?? "" });
      }
      const fresh = (await cartApi.get(token)) as Cart;
      setCart(fresh);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setMutationError(err instanceof Error ? err.message : "Failed to update cart");
    } finally {
      setMutatingItemId(null);
    }
  }

  if (loading) {
    return (
      <>
        <Header />
        <main className="flex-1 max-w-4xl mx-auto px-4 py-8">
          <h1 className="text-3xl font-extrabold mb-8">Your Cart</h1>
          <div className="flex flex-col lg:flex-row gap-8 animate-pulse" aria-hidden="true">
            <div className="flex-1 space-y-4">
              {Array.from({ length: 3 }).map((_, i) => (
                <div
                  key={i}
                  className="card p-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div className="flex-1 space-y-2 sm:pr-3">
                    <div className="h-5 w-1/2 bg-dark-800 rounded" />
                    <div className="h-4 w-16 bg-dark-800 rounded" />
                  </div>
                  <div className="h-[52px] w-32 bg-dark-800 rounded-xl" />
                </div>
              ))}
            </div>
            <div className="lg:w-80">
              <div className="card p-5 space-y-3">
                <div className="h-5 w-1/2 bg-dark-800 rounded" />
                <div className="h-10 bg-dark-800 rounded-xl" />
                <div className="h-10 bg-dark-800 rounded-xl" />
                <div className="h-12 bg-dark-800 rounded-xl" />
              </div>
            </div>
          </div>
        </main>
      </>
    );
  }

  if (loadError) {
    return (
      <>
        <Header />
        <main className="flex-1 max-w-4xl mx-auto px-4 py-8">
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load your cart</h2>
            <p className="text-dark-400 mb-6">{loadError}</p>
            <button onClick={() => token && loadAll(token)} className="btn-primary inline-block">
              Retry
            </button>
          </div>
        </main>
      </>
    );
  }

  const items = cart?.items ?? [];

  // While a captured charge has not yet converged to an order (finalize in
  // flight, a pending order persisted, or a finalize error showing), the
  // checkout panel must stay hidden: re-mounting it would quote a brand-new
  // PaymentIntent and let the user pay a SECOND time for the same cart while
  // the first charge still awaits recovery. Only the recovery banner (with
  // its retry/dismiss actions) may render in that state.
  const recovering = finalizing || finalizeError !== null || loadPendingOrder() !== null;

  return (
    <>
      <Header />
      <main className="flex-1 max-w-4xl mx-auto px-4 py-8">
        <h1 className="text-3xl font-extrabold mb-2">Your Cart</h1>
        {restaurant && (
          <div className="flex flex-wrap items-center gap-2 mb-8">
            <p className="text-dark-400">
              From <span className="text-brand-400 font-medium">{restaurant.name}</span>
            </p>
            {/* Certification chip repeats here so kashrus trust carries from
                the restaurant page into checkout. */}
            <KosherBadge restaurant={restaurant} size="compact" />
          </div>
        )}

        {(finalizing || finalizeError) && (
          <div
            className={`card p-6 mb-6 border ${
              finalizeError ? "border-red-800 bg-red-900/20" : "border-brand-700 bg-brand-900/10"
            }`}
            role="status"
            aria-live="polite"
          >
            {finalizing ? (
              <p className="text-dark-200">Finishing your order… please don&apos;t close this tab.</p>
            ) : loadPendingOrder() ? (
              <>
                <p className="text-red-300 mb-4">{finalizeError}</p>
                <button onClick={retryFinalize} className="btn-primary inline-block">
                  Retry confirming order
                </button>
              </>
            ) : (
              <>
                <p className="text-red-300 mb-4">{finalizeError}</p>
                <button onClick={() => setFinalizeError(null)} className="btn-secondary inline-block">
                  Dismiss
                </button>
              </>
            )}
          </div>
        )}

        {recovering ? null : items.length === 0 ? (
          <div className="card p-12 text-center">
            <ShoppingCart
              className="w-16 h-16 text-dark-600 mx-auto mb-4"
              strokeWidth={1.5}
              aria-hidden="true"
            />
            <h2 className="text-xl font-bold mb-2">Your cart is empty</h2>
            <p className="text-dark-400 mb-6">Add items from a restaurant to get started.</p>
            <a href="/search" className="btn-primary inline-block">Browse Restaurants</a>
          </div>
        ) : (
          <div className="flex flex-col lg:flex-row gap-8">
            {/* Items */}
            <div className="flex-1 space-y-4">
              {mutationError && (
                <div className="card p-3 border border-red-800 bg-red-900/20 text-red-300 text-sm">
                  {mutationError}
                </div>
              )}
              {items.map((item) => {
                const isPending = mutatingItemId === item.id;
                return (
                  // Below sm: the 44px stepper row stacks under the item info
                  // so long names keep full width at 375px; from sm: it's the
                  // usual single row.
                  <div
                    key={item.id}
                    className="card p-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"
                  >
                    <div className="flex-1 min-w-0 sm:pr-3">
                      <h3 className="font-semibold">{item.name}</h3>
                      {/* Selected modifiers as a single dot-separated line —
                          same pattern as the iOS CartView row. item.price is
                          the unit-price snapshot that already includes the
                          modifier deltas, so the line totals stay correct. */}
                      {item.selected_modifiers && item.selected_modifiers.length > 0 && (
                        <p className="text-dark-400 text-xs mt-0.5">
                          {item.selected_modifiers.map((m) => m.name).join(" • ")}
                        </p>
                      )}
                      <p className="text-brand-400 text-sm font-medium mt-0.5">
                        {formatUSD(item.price)}
                      </p>
                      {item.notes && (
                        <p className="text-dark-400 text-xs mt-1 italic">{item.notes}</p>
                      )}
                    </div>
                    <div className="flex items-center justify-between gap-3 sm:justify-end">
                      {/* 44px stepper buttons (w-11 h-11) — minimum touch target. */}
                      <div className="flex items-center gap-1 bg-dark-800 rounded-xl p-1">
                        <button
                          onClick={() => mutateQuantity(item.id, -1)}
                          disabled={isPending}
                          aria-label={item.quantity === 1 ? "Remove item" : "Decrease quantity"}
                          className="w-11 h-11 rounded-full bg-dark-700 hover:bg-dark-600 disabled:opacity-50 flex items-center justify-center text-white transition-colors"
                        >
                          {item.quantity === 1 ? (
                            <Trash2 className="w-4 h-4" aria-hidden="true" />
                          ) : (
                            "-"
                          )}
                        </button>
                        <span className="font-semibold w-8 text-center inline-flex items-center justify-center">
                          {isPending ? (
                            <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
                          ) : (
                            item.quantity
                          )}
                        </span>
                        <button
                          onClick={() => mutateQuantity(item.id, 1)}
                          disabled={isPending}
                          aria-label="Increase quantity"
                          className="w-11 h-11 rounded-full bg-brand-500 hover:bg-brand-600 disabled:opacity-50 flex items-center justify-center text-white transition-colors"
                        >
                          +
                        </button>
                      </div>
                      <span className="text-sm font-semibold w-16 text-right">
                        {formatUSD(item.price * item.quantity)}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Order Summary + checkout (fulfillment, address, tip, schedule,
                deals, Stripe modal) */}
            <div className="lg:w-80">
              {token && cart && (
                <CheckoutPanel
                  token={token}
                  cart={cart}
                  onUnauthorized={handleUnauthorized}
                  onPaymentCaptured={handlePaymentCaptured}
                />
              )}
            </div>
          </div>
        )}
      </main>
    </>
  );
}
