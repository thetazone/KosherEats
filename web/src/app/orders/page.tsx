"use client";

import { Header } from "@/components/layout/Header";
import { cart as cartApi, orders as ordersApi } from "@/lib/api";
import type { Order, OrderStatus } from "@/types";
import { RefreshCw } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

const STATUS_CONFIG: Record<OrderStatus, { label: string; color: string; bg: string }> = {
  scheduled: { label: "Scheduled", color: "text-warning-300", bg: "bg-warning-900/30" },
  pending: { label: "Pending", color: "text-warning-400", bg: "bg-warning-900/30" },
  accepted: { label: "Accepted", color: "text-info-400", bg: "bg-info-900/30" },
  preparing: { label: "Preparing", color: "text-brand-400", bg: "bg-brand-900/30" },
  ready: { label: "Ready", color: "text-success-400", bg: "bg-success-900/30" },
  picked_up: { label: "On the way", color: "text-transit-300", bg: "bg-transit-900/30" },
  delivered: { label: "Delivered", color: "text-success-400", bg: "bg-success-900/30" },
  cancelled: { label: "Cancelled", color: "text-danger-400", bg: "bg-danger-900/30" },
  rejected: { label: "Rejected", color: "text-danger-400", bg: "bg-danger-900/30" },
};

const TERMINAL_STATUSES: OrderStatus[] = ["delivered", "cancelled", "rejected"];
const CANCELLABLE_STATUSES: OrderStatus[] = ["pending", "accepted"];

/** How often an open tab re-checks the kitchen while an order is still moving. */
const ACTIVE_POLL_MS = 20_000;

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

function formatUSD(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
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

  const activeOrders = orders.filter((o) => !TERMINAL_STATUSES.includes(o.status));
  const pastOrders = orders.filter((o) => TERMINAL_STATUSES.includes(o.status));
  const visibleOrders = filter === "active" ? activeOrders : pastOrders;

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
          <div className="card p-12 text-center text-dark-400">Loading your orders…</div>
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
              className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${
                filter === "active"
                  ? "bg-brand-500 text-white"
                  : "text-dark-400 hover:text-white"
              }`}
            >
              Active ({activeOrders.length})
            </button>
            <button
              onClick={() => setFilter("past")}
              className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${
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
            className="btn-secondary py-2 px-4 text-sm flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
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
            <svg className="w-16 h-16 text-dark-600 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
            </svg>
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
              const statusConfig = STATUS_CONFIG[order.status];
              const isActive = !TERMINAL_STATUSES.includes(order.status);
              const progressPercent = activeProgressPercent(order.status);
              const canCancel = CANCELLABLE_STATUSES.includes(order.status);
              const isCancelling = cancellingId === order.id;
              const isReordering = reorderingId === order.id;
              const isExpanded = expandedId === order.id;
              const isConfirmingCancel = confirmingCancelId === order.id;
              return (
                <div key={order.id} className="card p-5 hover:border-dark-600 transition-colors">
                  <div className="flex items-start justify-between mb-3">
                    <div>
                      <h3 className="font-bold text-lg">{order.restaurant_name}</h3>
                      <p className="text-dark-500 text-sm">
                        {new Date(order.created_at).toLocaleDateString("en-US", {
                          month: "short",
                          day: "numeric",
                          hour: "numeric",
                          minute: "2-digit",
                        })}
                      </p>
                    </div>
                    <span className={`${statusConfig.bg} ${statusConfig.color} text-sm font-medium px-3 py-1 rounded-full`}>
                      {statusConfig.label}
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
                        aria-valuetext={statusConfig.label}
                        aria-label={`Order status: ${statusConfig.label}`}
                      >
                        <div
                          className="h-full bg-brand-500 rounded-full transition-all"
                          style={{ width: `${progressPercent}%` }}
                        />
                      </div>
                    </div>
                  )}

                  <div className="flex items-center justify-between">
                    <div className="text-sm text-dark-400">
                      {order.items.map((item) => `${item.quantity}x ${item.name}`).join(", ")}
                    </div>
                    <span className="font-semibold text-brand-400">
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

                  {/* Actions */}
                  <div className="mt-3 flex items-center gap-3">
                    {isActive && canCancel && isConfirmingCancel && (
                      <div className="flex items-center gap-2 text-sm">
                        <span className="text-dark-300">Cancel order?</span>
                        <button
                          onClick={() => cancelOrder(order.id)}
                          disabled={isCancelling}
                          className="bg-danger-600 hover:bg-danger-700 text-white font-semibold py-2 px-4 rounded-xl text-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                          {isCancelling ? "Cancelling…" : "Cancel Order"}
                        </button>
                        <button
                          onClick={() => setConfirmingCancelId(null)}
                          disabled={isCancelling}
                          className="btn-secondary py-2 px-4 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                          Keep Order
                        </button>
                      </div>
                    )}
                    {isActive && canCancel && !isConfirmingCancel && (
                      <button
                        onClick={() => setConfirmingCancelId(order.id)}
                        className="border border-danger-800 text-danger-400 hover:bg-danger-900/30 font-semibold py-2 px-4 rounded-xl text-sm transition-colors"
                      >
                        Cancel Order
                      </button>
                    )}
                    {!isActive && (
                      <button
                        onClick={() => reorder(order)}
                        disabled={isReordering}
                        className="btn-primary py-2 px-4 text-sm disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        {isReordering ? "Adding to cart…" : "Reorder"}
                      </button>
                    )}
                    <button
                      onClick={() => setExpandedId(isExpanded ? null : order.id)}
                      className="btn-secondary py-2 px-4 text-sm"
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
    </>
  );
}
