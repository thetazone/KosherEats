"use client";

import { Header } from "@/components/layout/Header";
import { CourierRatingModal } from "@/components/orders/CourierRatingModal";
import { RestaurantCertChip } from "@/components/restaurant/RestaurantCertChip";
import { cart as cartApi, orders as ordersApi, restaurants as restaurantsApi } from "@/lib/api";
import { formatUSD } from "@/lib/format";
import {
  CANCELLABLE_ORDER_STATUSES,
  ORDER_STATUS_META,
  TERMINAL_ORDER_STATUSES,
} from "@/lib/orderStatus";
import type { Cart, Order, OrderStatus, Restaurant } from "@/types";
import { ClipboardList, Loader2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

// Orders whose courier the user has already rated, persisted across sessions.
// The backend 200s on re-rates (silently overwriting the earlier rating), so
// showing "Rate Courier" again after a reload would let a second submit
// clobber the first without any error. localStorage is a cache — the server's
// courier_rating (seen when we hydrate order details) is the source of truth
// and re-seeds this set for ratings made on other devices.
const RATED_ORDERS_KEY = "rated_order_ids";

function loadRatedIds(): Set<string> {
  try {
    const parsed: unknown = JSON.parse(
      window.localStorage.getItem(RATED_ORDERS_KEY) ?? "[]"
    );
    return new Set(
      Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === "string") : []
    );
  } catch {
    return new Set();
  }
}

function persistRatedIds(ids: Set<string>): void {
  try {
    window.localStorage.setItem(RATED_ORDERS_KEY, JSON.stringify([...ids]));
  } catch {
    // Storage unavailable (private mode / quota) — in-session state still applies.
  }
}

function activeProgressWidth(status: OrderStatus): string {
  switch (status) {
    case "scheduled":
      return "4%";
    case "pending":
      return "12%";
    case "accepted":
      return "25%";
    case "preparing":
      return "50%";
    case "ready":
      return "75%";
    default:
      return "100%";
  }
}

export default function OrdersPage() {
  const router = useRouter();
  const [token, setToken] = useState<string | null>(null);
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [filter, setFilter] = useState<"active" | "past">("active");
  const [cancellingId, setCancellingId] = useState<string | null>(null);
  const [reorderingId, setReorderingId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  // Order currently being rated in the modal, if any.
  const [ratingOrderId, setRatingOrderId] = useState<string | null>(null);
  // Ids already rated — seeded from localStorage on mount, extended when a
  // rating is submitted here or discovered server-side during hydration.
  const [ratedIds, setRatedIds] = useState<Set<string>>(new Set());
  // The /orders list payload has no courier / rating / delivery-mode info
  // (only single-order GETs carry it), so delivered delivery orders are
  // hydrated with a per-order GET before "Rate Courier" is offered.
  const [orderDetails, setOrderDetails] = useState<Record<string, Order>>({});
  // Ids with a hydration GET already issued this mount (success or failure) —
  // keeps the effect from refetching in a loop.
  const hydratingRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    const t = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!t) {
      router.replace("/auth");
      return;
    }
    setToken(t);
    setRatedIds(loadRatedIds());
    void loadOrders(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const markRated = useCallback((id: string) => {
    setRatedIds((prev) => {
      if (prev.has(id)) return prev;
      const next = new Set(prev);
      next.add(id);
      persistRatedIds(next);
      return next;
    });
  }, []);

  // Hydrate courier info for delivered delivery orders on the Past tab (the
  // only place the rate button can appear). Already-rated ids are skipped —
  // no button either way — so the extra GETs shrink as orders get rated.
  useEffect(() => {
    if (!token || filter !== "past") return;
    for (const order of orders) {
      if (
        order.status !== "delivered" ||
        order.fulfillment_type === "pickup" ||
        ratedIds.has(order.id) ||
        hydratingRef.current.has(order.id)
      ) {
        continue;
      }
      hydratingRef.current.add(order.id);
      void ordersApi
        .get(token, order.id)
        .then((detail) => {
          setOrderDetails((prev) => ({ ...prev, [order.id]: detail }));
          if (detail.courier_rating != null) {
            // Rated in a prior session / on another device — remember it so
            // the button stays hidden and this GET is skipped next visit.
            markRated(order.id);
          }
        })
        .catch(() => {
          // Hydration failed: the button stays hidden, which is the safe
          // default (never offer a rating we can't verify applies).
        });
    }
  }, [token, filter, orders, ratedIds, markRated]);

  async function loadOrders(t: string) {
    setLoading(true);
    setLoadError(null);
    try {
      const list = (await ordersApi.list(t)) as Order[];
      setOrders(list);
    } catch (err) {
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        router.replace("/auth");
        return;
      }
      setLoadError(err instanceof Error ? err.message : "Failed to load orders");
    } finally {
      setLoading(false);
    }
  }

  async function cancelOrder(id: string) {
    if (!token) return;
    setCancellingId(id);
    setActionError(null);
    try {
      await ordersApi.cancel(token, id);
      const list = (await ordersApi.list(token)) as Order[];
      setOrders(list);
    } catch (err) {
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        router.replace("/auth");
        return;
      }
      setActionError(err instanceof Error ? err.message : "Failed to cancel order");
    } finally {
      setCancellingId(null);
    }
  }

  async function reorder(order: Order) {
    if (!token) return;
    setReorderingId(order.id);
    setActionError(null);
    try {
      // The backend silently wipes the existing cart when an add comes in for
      // a different restaurant — never let that happen without an explicit
      // confirmation (same guard + copy as the restaurant page). Best-effort:
      // if the cart can't be read we proceed without the prompt.
      let hadSameRestaurantItems = false;
      try {
        const c = (await cartApi.get(token)) as Cart;
        if (c.restaurant_id && (c.items ?? []).length > 0) {
          if (c.restaurant_id !== order.restaurant_id) {
            // Fetch the other restaurant's name for the confirmation copy —
            // best-effort, fall back to generic wording.
            const name = await restaurantsApi
              .get(c.restaurant_id)
              .then((r) => (r as Restaurant).name)
              .catch(() => null);
            const clears = name
              ? `This clears your items from ${name}.`
              : "This clears the items already in your cart from another restaurant.";
            const confirmed = window.confirm(
              `Start a new cart from ${order.restaurant_name}? ${clears}`
            );
            if (!confirmed) {
              setReorderingId(null);
              return;
            }
          } else {
            hadSameRestaurantItems = true;
          }
        }
      } catch (err) {
        if (isUnauthorized(err)) throw err;
        // Cart unavailable — skip the pre-add check rather than block reorder.
      }

      let added = 0;
      try {
        for (const item of order.items) {
          await cartApi.addItem(token, {
            menu_item_id: item.menu_item_id,
            restaurant_id: order.restaurant_id,
            quantity: item.quantity,
            notes: item.notes,
            // Re-add with the original customization — omitting these silently
            // rebuilt the bare base item at the wrong price. Stale modifier ids
            // 400 server-side and land in the partial-failure handling below.
            modifier_ids: item.selected_modifiers?.map((m) => m.id),
          });
          added += 1;
        }
      } catch (err) {
        if (isUnauthorized(err)) throw err;
        const reason =
          err instanceof Error ? err.message : "an item could not be added";
        if (added === 0) {
          setActionError(`Couldn't reorder — no items were added to your cart. (${reason})`);
        } else if (hadSameRestaurantItems) {
          // Can't roll back without touching the items the user already had
          // in this restaurant's cart — surface exactly what happened.
          setActionError(
            `Only ${added} of ${order.items.length} items from this order could be added — review your cart before checking out. (${reason})`
          );
        } else {
          // Nothing pre-existing to preserve: clear the half-built cart so
          // reorder stays all-or-nothing.
          await cartApi.clear(token).catch(() => {});
          setActionError(
            `Couldn't add all items from this order, so your cart was left empty. (${reason})`
          );
        }
        setReorderingId(null);
        return;
      }

      router.push("/cart");
    } catch (err) {
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        router.replace("/auth");
        return;
      }
      setActionError(err instanceof Error ? err.message : "Failed to reorder");
      setReorderingId(null);
    }
  }

  const activeOrders = orders.filter((o) => !TERMINAL_ORDER_STATUSES.includes(o.status));
  const pastOrders = orders.filter((o) => TERMINAL_ORDER_STATUSES.includes(o.status));
  const visibleOrders = filter === "active" ? activeOrders : pastOrders;

  if (loading) {
    return (
      <>
        <Header />
        <main className="flex-1 max-w-4xl mx-auto px-4 py-8">
          <h1 className="text-3xl font-extrabold mb-6">Your Orders</h1>
          <div className="space-y-4" aria-hidden="true">
            {Array.from({ length: 3 }).map((_, i) => (
              <div key={i} className="card p-5 animate-pulse space-y-3">
                <div className="flex items-start justify-between">
                  <div className="space-y-2">
                    <div className="h-5 w-40 bg-dark-800 rounded" />
                    <div className="h-4 w-24 bg-dark-800 rounded" />
                  </div>
                  <div className="h-6 w-20 bg-dark-800 rounded-full" />
                </div>
                <div className="h-4 w-2/3 bg-dark-800 rounded" />
                <div className="h-8 w-48 bg-dark-800 rounded-xl" />
              </div>
            ))}
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
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load your orders</h2>
            <p className="text-dark-400 mb-6">{loadError}</p>
            <button onClick={() => token && loadOrders(token)} className="btn-primary inline-block">
              Retry
            </button>
          </div>
        </main>
      </>
    );
  }

  return (
    <>
      <Header />
      <main className="flex-1 max-w-4xl mx-auto px-4 py-8">
        <h1 className="text-3xl font-extrabold mb-6">Your Orders</h1>

        {/* Filter Tabs */}
        <div className="flex bg-dark-800 rounded-xl p-1 mb-8 max-w-xs">
          <button
            onClick={() => setFilter("active")}
            className={`flex-1 py-2 min-h-[44px] rounded-lg text-sm font-medium transition-colors ${
              filter === "active"
                ? "bg-brand-500 text-white"
                : "text-dark-400 hover:text-white"
            }`}
          >
            Active ({activeOrders.length})
          </button>
          <button
            onClick={() => setFilter("past")}
            className={`flex-1 py-2 min-h-[44px] rounded-lg text-sm font-medium transition-colors ${
              filter === "past"
                ? "bg-brand-500 text-white"
                : "text-dark-400 hover:text-white"
            }`}
          >
            Past ({pastOrders.length})
          </button>
        </div>

        {actionError && (
          <div className="card p-3 mb-4 border border-red-800 bg-red-900/20 text-red-300 text-sm">
            {actionError}
          </div>
        )}

        {visibleOrders.length === 0 ? (
          <div className="card p-12 text-center">
            <ClipboardList
              className="w-16 h-16 text-dark-600 mx-auto mb-4"
              strokeWidth={1.5}
              aria-hidden="true"
            />
            <h2 className="text-xl font-bold mb-2">
              No {filter} orders
            </h2>
            <p className="text-dark-400 mb-6">
              {filter === "active"
                ? "You don't have any active orders right now."
                : "Your order history will appear here."}
            </p>
            <a href="/" className="btn-primary inline-block">
              Browse Restaurants
            </a>
          </div>
        ) : (
          <div className="space-y-4">
            {visibleOrders.map((order) => {
              const statusMeta = ORDER_STATUS_META[order.status];
              const isActive = !TERMINAL_ORDER_STATUSES.includes(order.status);
              // Mirrors backend CancelOrder: scheduled/pending/accepted, and
              // never once an external provider owns the delivery.
              const canCancel =
                CANCELLABLE_ORDER_STATUSES.includes(order.status) && !order.external_delivery_id;
              const isCancelling = cancellingId === order.id;
              const isReordering = reorderingId === order.id;
              const isExpanded = expandedId === order.id;
              const pickup = order.fulfillment_type === "pickup";
              // "On the way" is delivery copy — a picked_up pickup order was
              // collected by the customer (mirrors the tracking headline).
              const statusLabel =
                pickup && order.status === "picked_up" ? "Picked up" : statusMeta.label;
              // Rate Courier requires a hydrated single-order payload proving
              // there's an actual platform courier to rate — self-delivery
              // (delivery_mode "restaurant") and external-provider orders
              // never get one — and no rating already on file server-side.
              const detail = orderDetails[order.id];
              const canRate =
                order.status === "delivered" &&
                !pickup &&
                !ratedIds.has(order.id) &&
                detail != null &&
                detail.courier != null &&
                detail.courier_rating == null;
              return (
                <div key={order.id} className="card p-5 hover:border-dark-600 transition-colors">
                  <div className="flex items-start justify-between mb-3">
                    <div>
                      <div className="flex flex-wrap items-center gap-2">
                        <Link href={`/orders/${order.id}`} className="hover:text-brand-400 transition-colors">
                          <h3 className="font-bold text-lg">{order.restaurant_name}</h3>
                        </Link>
                        {/* Certification chip repeats on history rows — the
                            kosher trust story continues after the sale. */}
                        <RestaurantCertChip restaurantId={order.restaurant_id} />
                      </div>
                      <p className="text-dark-500 text-sm">
                        {new Date(order.created_at).toLocaleDateString("en-US", {
                          month: "short",
                          day: "numeric",
                          hour: "numeric",
                          minute: "2-digit",
                        })}
                      </p>
                    </div>
                    <span className={`${statusMeta.pill} text-sm font-medium px-3 py-1 rounded-full`}>
                      {statusLabel}
                    </span>
                  </div>

                  {/* Progress bar for active orders */}
                  {isActive && (
                    <div className="mb-4">
                      <div className="flex justify-between text-xs text-dark-500 mb-1">
                        <span>Order placed</span>
                        <span>Preparing</span>
                        <span>Ready</span>
                        {/* Pickup orders end with the customer collecting the
                            food, not a delivery. */}
                        <span>{pickup ? "Picked up" : "Delivered"}</span>
                      </div>
                      <div className="h-1.5 bg-dark-800 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-brand-500 rounded-full transition-all"
                          style={{ width: activeProgressWidth(order.status) }}
                        />
                      </div>
                    </div>
                  )}

                  <div className="flex items-center justify-between gap-3">
                    <div className="text-sm text-dark-400 min-w-0">
                      {order.items.map((item) => `${item.quantity}x ${item.name}`).join(", ")}
                    </div>
                    <span className="font-semibold text-brand-400 flex-shrink-0">
                      {formatUSD(order.total)}
                    </span>
                  </div>

                  {/* Receipt breakdown (toggled by View Receipt) */}
                  {isExpanded && (
                    <div className="mt-4 pt-4 border-t border-dark-700 space-y-2 text-sm">
                      {order.items.map((item) => (
                        <div key={item.id} className="flex justify-between text-dark-400">
                          <span>
                            {item.quantity}x {item.name}
                          </span>
                          <span>{formatUSD(item.price * item.quantity)}</span>
                        </div>
                      ))}
                      <div className="flex justify-between text-dark-400 border-t border-dark-700 pt-2">
                        <span>Subtotal</span>
                        <span>{formatUSD(order.subtotal)}</span>
                      </div>
                      {/* Every non-zero money component renders so the rows
                          always sum to Total (subtotal - discount + fees +
                          tax + tip == total) — mirrors the /orders/[id]
                          receipt. */}
                      {(order.discount ?? 0) > 0 && (
                        <div className="flex justify-between text-green-400">
                          <span>Discount</span>
                          <span>-{formatUSD(order.discount ?? 0)}</span>
                        </div>
                      )}
                      {/* Pickup orders have no delivery fee — hide the row
                          unless a non-zero fee is present (keeps the sum
                          honest even on anomalous data). */}
                      {(!pickup || order.delivery_fee > 0) && (
                        <div className="flex justify-between text-dark-400">
                          <span>Delivery fee</span>
                          <span>{formatUSD(order.delivery_fee)}</span>
                        </div>
                      )}
                      <div className="flex justify-between text-dark-400">
                        <span>Service fee</span>
                        <span>{formatUSD(order.service_fee)}</span>
                      </div>
                      <div className="flex justify-between text-dark-400">
                        <span>Tax</span>
                        <span>{formatUSD(order.tax)}</span>
                      </div>
                      {(order.courier_tip ?? 0) > 0 && (
                        <div className="flex justify-between text-dark-400">
                          <span>Courier tip</span>
                          <span>{formatUSD(order.courier_tip ?? 0)}</span>
                        </div>
                      )}
                      <div className="flex justify-between font-bold text-base border-t border-dark-700 pt-2">
                        <span>Total</span>
                        <span className="text-brand-400">{formatUSD(order.total)}</span>
                      </div>
                      <p className="text-dark-500 text-xs pt-1">
                        {pickup
                          ? `Pickup from ${order.restaurant_name}`
                          : `Delivered to ${order.delivery_address}`}
                      </p>
                    </div>
                  )}

                  {/* Actions — wrap so three buttons never force horizontal
                      scroll at 375px; every target keeps a >=44px hit area. */}
                  <div className="mt-3 flex flex-wrap gap-3">
                    {isActive && (
                      <Link
                        href={`/orders/${order.id}`}
                        className="btn-primary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center"
                      >
                        Track Order
                      </Link>
                    )}
                    {isActive && canCancel && (
                      <button
                        onClick={() => cancelOrder(order.id)}
                        disabled={isCancelling}
                        className="btn-secondary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {isCancelling && (
                          <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
                        )}
                        {isCancelling ? "Cancelling…" : "Cancel Order"}
                      </button>
                    )}
                    {!isActive && (
                      <button
                        onClick={() => reorder(order)}
                        disabled={isReordering}
                        className="btn-primary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {isReordering && (
                          <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
                        )}
                        {isReordering ? "Adding to cart…" : "Reorder"}
                      </button>
                    )}
                    {canRate && (
                      <button
                        onClick={() => setRatingOrderId(order.id)}
                        className="btn-secondary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center"
                      >
                        Rate Courier
                      </button>
                    )}
                    <button
                      onClick={() => setExpandedId(isExpanded ? null : order.id)}
                      className="btn-secondary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center"
                    >
                      {isExpanded ? "Hide Receipt" : "View Receipt"}
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </main>

      {/* Courier rating modal — courier name comes from the hydrated
          single-order payload (the button only renders once it's loaded). */}
      {ratingOrderId && token && (
        <CourierRatingModal
          token={token}
          orderId={ratingOrderId}
          courierFirstName={orderDetails[ratingOrderId]?.courier?.first_name}
          onSubmitted={() => {
            markRated(ratingOrderId);
            setRatingOrderId(null);
          }}
          onClose={() => setRatingOrderId(null)}
          onUnauthorized={() => {
            window.localStorage.removeItem("token");
            router.replace("/auth");
          }}
        />
      )}
    </>
  );
}
