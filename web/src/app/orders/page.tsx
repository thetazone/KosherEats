"use client";

import { Header } from "@/components/layout/Header";
import { CourierRatingModal } from "@/components/orders/CourierRatingModal";
import { RestaurantCertChip } from "@/components/restaurant/RestaurantCertChip";
import { cart as cartApi, orders as ordersApi } from "@/lib/api";
import { formatUSD } from "@/lib/format";
import {
  CANCELLABLE_ORDER_STATUSES,
  ORDER_STATUS_META,
  TERMINAL_ORDER_STATUSES,
} from "@/lib/orderStatus";
import type { Order, OrderStatus } from "@/types";
import { ClipboardList, Loader2, RefreshCw } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

/** How often an open tab re-checks the kitchen while an order is still moving. */
const ACTIVE_POLL_MS = 20_000;

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

function activeProgressPercent(status: OrderStatus): number {
  switch (status) {
    case "scheduled":
      return 4;
    case "pending":
      return 12;
    case "accepted":
      return 25;
    case "preparing":
      return 50;
    case "ready":
      return 75;
    case "picked_up":
      return 90;
    default:
      return 100;
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
  const [confirmingCancelId, setConfirmingCancelId] = useState<string | null>(null);
  const [reorderingId, setReorderingId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const inFlightRef = useRef(false);

  // "initial" owns the full-page loading/error states; "manual" and "poll" refresh
  // in place so the list never blinks. A failed poll keeps the last good data —
  // the next tick retries — while a failed manual refresh reports inline.
  const loadOrders = useCallback(
    async (t: string, mode: "initial" | "manual" | "poll" = "initial") => {
      if (inFlightRef.current) return;
      inFlightRef.current = true;
      if (mode === "initial") {
        setLoading(true);
        setLoadError(null);
      } else {
        setRefreshing(true);
        setActionError(null);
      }
      try {
        const list = (await ordersApi.list(t)) as Order[];
        setOrders(list);
      } catch (err) {
        if (isUnauthorized(err)) {
          window.localStorage.removeItem("token");
          router.replace("/auth");
          return;
        }
        const message = err instanceof Error ? err.message : "Failed to load orders";
        if (mode === "initial") setLoadError(message);
        else if (mode === "manual") setActionError(message);
      } finally {
        inFlightRef.current = false;
        setLoading(false);
        setRefreshing(false);
      }
    },
    [router],
  );

  const activeOrders = orders.filter((o) => !TERMINAL_ORDER_STATUSES.includes(o.status));
  const pastOrders = orders.filter((o) => TERMINAL_ORDER_STATUSES.includes(o.status));
  const visibleOrders = filter === "active" ? activeOrders : pastOrders;

  // Order currently being rated in the modal, if any. The list payload has
  // no courier/rating info (that only comes on single-order GETs), so we
  // track ids rated this session to hide the button after submission.
  const [ratingOrderId, setRatingOrderId] = useState<string | null>(null);
  const [ratedIds, setRatedIds] = useState<Set<string>>(new Set());

  useEffect(() => {
    const t = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!t) {
      router.replace("/auth?next=/orders");
      return;
    }
    setToken(t);
    void loadOrders(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Keep active orders honest: poll only while something is still in flight and
  // the tab is actually on screen, and stop the timer on unmount.
  useEffect(() => {
    if (!token || activeOrders.length === 0) return;
    const id = window.setInterval(() => {
      if (document.visibilityState !== "visible") return;
      void loadOrders(token, "poll");
    }, ACTIVE_POLL_MS);
    return () => window.clearInterval(id);
  }, [token, activeOrders.length, loadOrders]);

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
      setConfirmingCancelId(null);
    }
  }

  async function reorder(order: Order) {
    if (!token) return;
    setReorderingId(order.id);
    setActionError(null);
    try {
      for (const item of order.items) {
        await cartApi.addItem(token, {
          menu_item_id: item.menu_item_id,
          restaurant_id: order.restaurant_id,
          quantity: item.quantity,
          notes: item.notes,
        });
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

        {/* Filter Tabs + manual refresh */}
        <div className="flex items-center gap-3 mb-8">
          <div className="flex bg-dark-800 rounded-xl p-1 max-w-xs flex-1">
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
          <button
            onClick={() => token && loadOrders(token, "manual")}
            disabled={refreshing}
            className="btn-secondary py-2 px-4 text-sm min-h-[44px] flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <RefreshCw className={`w-4 h-4 ${refreshing ? "animate-spin" : ""}`} aria-hidden="true" />
            {refreshing ? "Refreshing…" : "Refresh"}
          </button>
        </div>

        {actionError && (
          <div className="card p-3 mb-4 border border-danger-800 bg-danger-900/20 text-danger-300 text-sm">
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
            <Link href="/search" className="btn-primary inline-block">
              Browse Restaurants
            </Link>
          </div>
        ) : (
          <div className="space-y-4">
            {visibleOrders.map((order) => {
              const statusMeta = ORDER_STATUS_META[order.status];
              const isActive = !TERMINAL_ORDER_STATUSES.includes(order.status);
              const progressPercent = activeProgressPercent(order.status);
              // Mirrors backend CancelOrder: scheduled/pending/accepted, and
              // never once an external provider owns the delivery.
              const canCancel =
                CANCELLABLE_ORDER_STATUSES.includes(order.status) && !order.external_delivery_id;
              const isCancelling = cancellingId === order.id;
              const isReordering = reorderingId === order.id;
              const isExpanded = expandedId === order.id;
              const isConfirmingCancel = confirmingCancelId === order.id;
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
                      {statusMeta.label}
                    </span>
                  </div>

                  {/* Progress bar for active orders */}
                  {isActive && (
                    <div className="mb-4">
                      {/* Labels sit on the stops they mark (see activeProgressPercent);
                          the outer two anchor to the track edges so they can't clip. */}
                      <div className="relative h-4 text-xs text-dark-500">
                        <span className="absolute left-0">Order placed</span>
                        <span className="absolute left-1/2 -translate-x-1/2">Preparing</span>
                        <span className="absolute left-3/4 -translate-x-1/2">Ready</span>
                        <span className="absolute right-0">Delivered</span>
                      </div>
                      <div
                        className="h-1.5 bg-dark-800 rounded-full overflow-hidden"
                        role="progressbar"
                        aria-valuenow={progressPercent}
                        aria-valuemin={0}
                        aria-valuemax={100}
                        aria-valuetext={statusMeta.label}
                        aria-label={`Order status: ${statusMeta.label}`}
                      >
                        <div
                          className="h-full bg-brand-500 rounded-full transition-all"
                          style={{ width: `${progressPercent}%` }}
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
                      <div className="flex justify-between text-dark-400">
                        <span>Delivery fee</span>
                        <span>{formatUSD(order.delivery_fee)}</span>
                      </div>
                      <div className="flex justify-between text-dark-400">
                        <span>Service fee</span>
                        <span>{formatUSD(order.service_fee)}</span>
                      </div>
                      <div className="flex justify-between text-dark-400">
                        <span>Tax</span>
                        <span>{formatUSD(order.tax)}</span>
                      </div>
                      <div className="flex justify-between font-bold text-base border-t border-dark-700 pt-2">
                        <span>Total</span>
                        <span className="text-brand-400">{formatUSD(order.total)}</span>
                      </div>
                      <p className="text-dark-500 text-xs pt-1">
                        Delivered to {order.delivery_address}
                      </p>
                    </div>
                  )}

                  {/* Actions — wrap so three buttons never force horizontal
                      scroll at 375px; every target keeps a >=44px hit area. */}
                  <div className="mt-3 flex flex-wrap items-center gap-3">
                    {isActive && (
                      <Link
                        href={`/orders/${order.id}`}
                        className="btn-primary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center"
                      >
                        Track Order
                      </Link>
                    )}
                    {isActive && canCancel && isConfirmingCancel && (
                      <div className="flex items-center gap-2 text-sm">
                        <span className="text-dark-300">Cancel order?</span>
                        <button
                          onClick={() => cancelOrder(order.id)}
                          disabled={isCancelling}
                          className="bg-danger-600 hover:bg-danger-700 text-white font-semibold py-2 px-4 rounded-xl text-sm min-h-[44px] inline-flex items-center justify-center gap-2 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                          {isCancelling && (
                            <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
                          )}
                          {isCancelling ? "Cancelling…" : "Cancel Order"}
                        </button>
                        <button
                          onClick={() => setConfirmingCancelId(null)}
                          disabled={isCancelling}
                          className="btn-secondary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                          Keep Order
                        </button>
                      </div>
                    )}
                    {isActive && canCancel && !isConfirmingCancel && (
                      <button
                        onClick={() => setConfirmingCancelId(order.id)}
                        className="border border-danger-800 text-danger-400 hover:bg-danger-900/30 font-semibold py-2 px-4 rounded-xl text-sm min-h-[44px] inline-flex items-center justify-center transition-colors"
                      >
                        Cancel Order
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
                    {order.status === "delivered" &&
                      order.fulfillment_type !== "pickup" &&
                      !ratedIds.has(order.id) && (
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

      {/* Courier rating modal. No courier name here — the list payload
          doesn't carry courier info (only single-order GETs do). */}
      {ratingOrderId && token && (
        <CourierRatingModal
          token={token}
          orderId={ratingOrderId}
          onSubmitted={() => {
            setRatedIds((prev) => {
              const next = new Set(prev);
              next.add(ratingOrderId);
              return next;
            });
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
