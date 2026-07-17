"use client";

import { Header } from "@/components/layout/Header";
import { CourierRatingModal } from "@/components/orders/CourierRatingModal";
import { cart as cartApi, orders as ordersApi } from "@/lib/api";
import { formatUSD } from "@/lib/format";
import {
  CANCELLABLE_ORDER_STATUSES,
  ORDER_STATUS_META,
  TERMINAL_ORDER_STATUSES,
} from "@/lib/orderStatus";
import type { Order, OrderStatus } from "@/types";
import { ClipboardList, Loader2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
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
  // Order currently being rated in the modal, if any. The list payload has
  // no courier/rating info (that only comes on single-order GETs), so we
  // track ids rated this session to hide the button after submission.
  const [ratingOrderId, setRatingOrderId] = useState<string | null>(null);
  const [ratedIds, setRatedIds] = useState<Set<string>>(new Set());

  useEffect(() => {
    const t = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!t) {
      router.replace("/auth");
      return;
    }
    setToken(t);
    void loadOrders(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
              return (
                <div key={order.id} className="card p-5 hover:border-dark-600 transition-colors">
                  <div className="flex items-start justify-between mb-3">
                    <div>
                      <Link href={`/orders/${order.id}`} className="hover:text-brand-400 transition-colors">
                        <h3 className="font-bold text-lg">{order.restaurant_name}</h3>
                      </Link>
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
                      <div className="flex justify-between text-xs text-dark-500 mb-1">
                        <span>Order placed</span>
                        <span>Preparing</span>
                        <span>Ready</span>
                        <span>Delivered</span>
                      </div>
                      <div className="h-1.5 bg-dark-800 rounded-full overflow-hidden">
                        <div
                          className="h-full bg-brand-500 rounded-full transition-all"
                          style={{ width: activeProgressWidth(order.status) }}
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
                  <div className="mt-3 flex gap-3">
                    {isActive && (
                      <Link
                        href={`/orders/${order.id}`}
                        className="btn-primary py-2 px-4 text-sm inline-block"
                      >
                        Track Order
                      </Link>
                    )}
                    {isActive && canCancel && (
                      <button
                        onClick={() => cancelOrder(order.id)}
                        disabled={isCancelling}
                        className="btn-secondary py-2 px-4 text-sm inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
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
                        className="btn-primary py-2 px-4 text-sm inline-flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
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
                          className="btn-secondary py-2 px-4 text-sm"
                        >
                          Rate Courier
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
