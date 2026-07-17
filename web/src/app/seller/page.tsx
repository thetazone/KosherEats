"use client";

// Seller dashboard (S16). Today's stats grid, the open/closed toggle
// (approval-gated), the default delivery-method selector, and live
// active-order cards with quick actions. Mirrors the iOS seller app's
// DashboardView; polls every 15s while the tab is visible (same pattern
// as app/admin/orders — a failed refresh keeps the last good data behind
// a reconnecting banner, only the very first load can hard-fail).

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  Car,
  ChevronRight,
  Clock,
  DollarSign,
  Flame,
  Inbox,
  Loader2,
  ShoppingBag,
  Store,
  X,
} from "lucide-react";
import { ActiveOrderCard, type OrderQuickAction } from "@/components/seller/ActiveOrderCard";
import { formatCents, sellerApi } from "@/lib/sellerApi";
import type {
  DashboardStats,
  OrderStatus,
  OrderStatusAck,
  SellerOrder,
  SellerRestaurant,
} from "@/types/seller";

/** Statuses that keep an order on the dashboard (Order.isActive on iOS —
 *  picked_up stays visible so deliveries en route aren't lost). */
const ACTIVE_STATUSES: ReadonlySet<OrderStatus> = new Set([
  "scheduled",
  "pending",
  "accepted",
  "preparing",
  "ready",
  "picked_up",
]);

/** pickup/deliver return a bare {status} ack rather than the updated order
 *  (SellerPickupOrder/SellerDeliverOrder in handlers/orders.go) — the
 *  follow-up load() below reconciles those. */
const QUICK_ACTION_CALLS: Record<
  OrderQuickAction,
  (id: string) => Promise<SellerOrder | OrderStatusAck>
> = {
  accept: sellerApi.orders.accept,
  reject: sellerApi.orders.reject,
  preparing: sellerApi.orders.markPreparing,
  ready: sellerApi.orders.markReady,
  complete: sellerApi.orders.complete,
  pickup: sellerApi.orders.pickup,
  deliver: sellerApi.orders.deliver,
};

export default function SellerDashboardPage() {
  const [restaurant, setRestaurant] = useState<SellerRestaurant | null>(null);
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [orders, setOrders] = useState<SellerOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hasData, setHasData] = useState(false);

  // Busy flags for mutations; actionError surfaces their failures without
  // touching the page-level load error.
  const [toggling, setToggling] = useState(false);
  const [savingMode, setSavingMode] = useState(false);
  const [actingOrderId, setActingOrderId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  // Guards against a slow earlier poll overwriting a newer one's results.
  const requestSeq = useRef(0);

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    try {
      const [rest, statsRes, orderList] = await Promise.all([
        sellerApi.restaurants.get(),
        sellerApi.dashboard.stats(),
        sellerApi.orders.list({ limit: 100 }),
      ]);
      if (seq !== requestSeq.current) return;
      setRestaurant(rest);
      setStats(statsRes);
      setOrders(orderList ?? []);
      setHasData(true);
      setError(null);
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setError((err as Error).message || "Failed to load dashboard");
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, []);

  // Initial load + 15s poll, paused while the tab is hidden and refreshed
  // immediately on return so the dashboard is never 15s stale on focus.
  useEffect(() => {
    load();

    let timer: ReturnType<typeof setInterval> | null = null;
    const start = () => {
      if (timer === null) timer = setInterval(load, 15_000);
    };
    const stop = () => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };
    const onVisibility = () => {
      if (document.hidden) {
        stop();
      } else {
        load();
        start();
      }
    };

    if (!document.hidden) start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [load]);

  // ── Mutations ──────────────────────────────────────────────

  async function toggleOpen() {
    if (!restaurant || toggling) return;
    setToggling(true);
    setActionError(null);
    try {
      // ToggleRestaurantStatus returns the fresh restaurant record.
      setRestaurant(await sellerApi.restaurants.setOpen(!restaurant.is_open));
    } catch (err) {
      setActionError((err as Error).message || "Couldn't update restaurant status");
    } finally {
      setToggling(false);
    }
  }

  async function setDeliveryMode(mode: "external" | "restaurant") {
    if (!restaurant || savingMode || restaurant.delivery_mode === mode) return;
    setSavingMode(true);
    setActionError(null);
    try {
      setRestaurant(await sellerApi.restaurants.update({ delivery_mode: mode }));
    } catch (err) {
      setActionError((err as Error).message || "Couldn't update delivery method");
    } finally {
      setSavingMode(false);
    }
  }

  async function runOrderAction(order: SellerOrder, action: OrderQuickAction) {
    if (actingOrderId) return;
    setActingOrderId(order.id);
    setActionError(null);
    try {
      const updated = await QUICK_ACTION_CALLS[action](order.id);
      // Reconcile the acted-on order in place immediately (when the endpoint
      // returned the full order — pickup/deliver only ack), then do a full
      // silent refresh so the stats row catches up too.
      if ("id" in updated) {
        setOrders((prev) => prev.map((o) => (o.id === updated.id ? updated : o)));
      }
      void load();
    } catch (err) {
      setActionError((err as Error).message || "Couldn't update the order");
    } finally {
      setActingOrderId(null);
    }
  }

  // ── Render ─────────────────────────────────────────────────

  const activeOrders = orders.filter((o) => ACTIVE_STATUSES.has(o.status));

  if (loading) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Dashboard</h1>
        <div className="card p-5 h-20 animate-pulse mb-4" aria-hidden="true" />
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="card p-5 h-32 animate-pulse" aria-hidden="true" />
          ))}
        </div>
        <div className="card p-5 h-40 animate-pulse mt-6" aria-hidden="true" />
      </div>
    );
  }

  // Only block the whole view when the very first load failed with no good
  // data. A seller with no restaurant yet gets pointed at onboarding instead
  // of a dead retry loop (the layout's picker offers the same link).
  if (error && !hasData) {
    const noRestaurant = error.toLowerCase().includes("restaurant not found");
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Dashboard</h1>
        <div className="card p-10 text-center">
          {noRestaurant ? (
            <>
              <Store className="w-10 h-10 text-dark-500 mx-auto mb-3" aria-hidden="true" />
              <p className="font-semibold mb-1">No restaurant yet</p>
              <p className="text-sm text-dark-400 mb-5">
                Set up your restaurant to start receiving orders.
              </p>
              <Link href="/seller/onboarding" className="btn-primary inline-block">
                Set up your restaurant
              </Link>
            </>
          ) : (
            <>
              <p className="text-red-400 mb-4">{error}</p>
              <button onClick={load} className="btn-secondary">
                Try again
              </button>
            </>
          )}
        </div>
      </div>
    );
  }

  const isApproved = restaurant?.approval_status === "approved";
  const isOpen = Boolean(isApproved && restaurant?.is_open);

  return (
    <div>
      <div className="flex items-center justify-between gap-3 mb-6">
        <h1 className="text-2xl font-bold">Dashboard</h1>
        <span className="text-xs text-dark-500">Refreshes every 15s</span>
      </div>

      {/* Transient poll failure — keep the last good data on screen. */}
      {error && (
        <div className="flex items-center justify-between gap-4 mb-4 px-4 py-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 text-amber-300 text-sm">
          <span>Reconnecting… showing last loaded data.</span>
          <button
            onClick={load}
            className="text-xs px-3 min-h-[44px] -my-2 rounded-lg bg-amber-500/20 hover:bg-amber-500/30 transition-colors shrink-0"
          >
            Retry
          </button>
        </div>
      )}

      {/* Mutation failures (toggle / delivery mode / quick actions). */}
      {actionError && (
        <div className="flex items-center justify-between gap-4 mb-4 px-4 py-2.5 rounded-xl border border-red-500/30 bg-red-500/10 text-red-300 text-sm">
          <span>{actionError}</span>
          <button
            onClick={() => setActionError(null)}
            aria-label="Dismiss error"
            className="min-w-[44px] min-h-[44px] -my-2 -mr-3 flex items-center justify-center rounded-lg hover:bg-red-500/20 transition-colors shrink-0"
          >
            <X className="w-4 h-4" aria-hidden="true" />
          </button>
        </div>
      )}

      {/* Open/closed status card */}
      {restaurant && (
        <div className="card p-5 mb-4">
          <div className="flex items-center justify-between gap-4">
            <div className="min-w-0">
              <div className="font-semibold truncate">{restaurant.name}</div>
              <div
                className={`text-sm mt-0.5 ${
                  !isApproved ? "text-amber-400" : isOpen ? "text-green-400" : "text-dark-400"
                }`}
              >
                {!isApproved ? "Pending approval" : isOpen ? "Open for orders" : "Closed"}
              </div>
            </div>
            {/* 44px hit area around the visual track (account-page pattern). */}
            <button
              role="switch"
              aria-checked={isOpen}
              aria-label={isOpen ? "Close restaurant for orders" : "Open restaurant for orders"}
              disabled={!isApproved || toggling}
              onClick={toggleOpen}
              className="shrink-0 -m-2 p-2 min-w-[44px] min-h-[44px] flex items-center justify-center disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <span
                className={`relative block w-12 h-7 rounded-full transition-colors ${
                  isOpen ? "bg-green-500" : "bg-dark-700"
                }`}
              >
                <span
                  className={`absolute top-1 left-1 w-5 h-5 rounded-full bg-white shadow transition-transform ${
                    isOpen ? "translate-x-5" : ""
                  }`}
                />
              </span>
            </button>
          </div>
          {!isApproved && (
            <p className="text-xs text-dark-500 mt-3">
              We&apos;ll email you once the KosherEats team reviews your application. You can
              build your menu and settings while you wait.
            </p>
          )}
        </div>
      )}

      {/* Stats grid + delivery-method tile */}
      {stats && (
        <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
          <StatCard
            icon={<Flame className="w-5 h-5" aria-hidden="true" />}
            iconClass="text-brand-500"
            label="Active orders"
            value={String(stats.active_orders)}
          />
          <StatCard
            icon={<ShoppingBag className="w-5 h-5" aria-hidden="true" />}
            iconClass="text-green-400"
            label="Today's orders"
            value={String(stats.today_orders)}
          />
          <StatCard
            icon={<DollarSign className="w-5 h-5" aria-hidden="true" />}
            iconClass="text-amber-400"
            label="Today's revenue"
            value={formatCents(stats.today_revenue)}
          />
          <StatCard
            icon={<Car className="w-5 h-5" aria-hidden="true" />}
            iconClass="text-green-400"
            label="Delivery earnings"
            value={formatCents(stats.today_delivery_earnings)}
          />
          <StatCard
            icon={<Clock className="w-5 h-5" aria-hidden="true" />}
            iconClass="text-dark-400"
            label="Avg prep time"
            value={`${Math.round(stats.avg_prep_time)} min`}
          />
          {restaurant && (
            <DeliveryModeTile
              restaurant={restaurant}
              saving={savingMode}
              onSelect={setDeliveryMode}
            />
          )}
        </div>
      )}

      {/* Active orders */}
      <div className="flex items-center gap-2.5 mt-8 mb-4">
        <h2 className="text-lg font-bold">Active orders</h2>
        {activeOrders.length > 0 && (
          <span className="text-xs font-bold bg-brand-500 text-white px-2 py-0.5 rounded-lg">
            {activeOrders.length}
          </span>
        )}
      </div>

      {activeOrders.length === 0 ? (
        <div className="card p-10 text-center">
          <Inbox className="w-10 h-10 text-dark-500 mx-auto mb-3" aria-hidden="true" />
          <p className="font-semibold mb-1">No active orders</p>
          <p className="text-sm text-dark-400">
            New orders will show up here the moment they come in.
          </p>
        </div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {activeOrders.map((order) => (
            <ActiveOrderCard
              key={order.id}
              order={order}
              acting={actingOrderId === order.id}
              onAction={runOrderAction}
            />
          ))}
        </div>
      )}

      {/* The server-side active count is authoritative — if it exceeds what we
          fetched (limit 100) and filtered, point at the full Orders page so the
          badge never silently undercounts vs the stat card. */}
      {stats && stats.active_orders > activeOrders.length && activeOrders.length > 0 && (
        <Link
          href="/seller/orders"
          className="card flex items-center justify-between gap-3 mt-4 px-5 py-3.5 text-sm hover:bg-dark-800 transition-colors"
        >
          <span className="text-dark-400">
            Showing {activeOrders.length} of {stats.active_orders} active orders
          </span>
          <span className="flex items-center gap-1 text-brand-500 font-semibold shrink-0">
            See all orders
            <ChevronRight className="w-4 h-4" aria-hidden="true" />
          </span>
        </Link>
      )}
    </div>
  );
}

// ── Stat card ────────────────────────────────────────────────

function StatCard({
  icon,
  iconClass,
  label,
  value,
}: {
  icon: React.ReactNode;
  iconClass: string;
  label: string;
  value: string;
}) {
  return (
    <div className="card p-5">
      <div className={`mb-3 ${iconClass}`}>{icon}</div>
      <div className="text-2xl font-bold">{value}</div>
      <div className="text-sm text-dark-400 mt-0.5">{label}</div>
    </div>
  );
}

// ── Delivery method tile ─────────────────────────────────────

/**
 * Default delivery method for NEW orders (existing orders keep their mode).
 * "Uber Direct" (external) vs "Self-delivery" (restaurant) — the legacy
 * "platform" (KosherEats couriers) mode is never offered as a choice, but if
 * it's the current mode we say so instead of showing a wrong selection.
 */
function DeliveryModeTile({
  restaurant,
  saving,
  onSelect,
}: {
  restaurant: SellerRestaurant;
  saving: boolean;
  onSelect: (mode: "external" | "restaurant") => void;
}) {
  const mode = restaurant.delivery_mode;

  // The update is NOT optimistic (the restaurant only changes on response),
  // so remember which pill was pressed to spin exactly that one in flight.
  const [pendingMode, setPendingMode] = useState<"external" | "restaurant" | null>(null);
  useEffect(() => {
    if (!saving) setPendingMode(null);
  }, [saving]);

  const pill = (label: string, value: "external" | "restaurant") => {
    const selected = mode === value;
    const inFlight = saving && pendingMode === value;
    return (
      <button
        onClick={() => {
          setPendingMode(value);
          onSelect(value);
        }}
        disabled={saving}
        aria-pressed={selected}
        className={`w-full flex items-center justify-center gap-1.5 py-2 px-3 min-h-[44px] rounded-lg text-xs font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
          selected
            ? "bg-green-500 text-white"
            : "bg-dark-800 text-dark-300 hover:bg-dark-700"
        }`}
      >
        {inFlight && <Loader2 className="w-3.5 h-3.5 animate-spin" aria-hidden="true" />}
        {label}
      </button>
    );
  };

  return (
    <div className="card p-5">
      <div className="mb-3 text-brand-500">
        <Car className="w-5 h-5" aria-hidden="true" />
      </div>
      <div className="text-sm text-dark-400 mb-2.5">Delivery method</div>
      <div className="space-y-1.5">
        {pill("Uber Direct", "external")}
        {pill("Self-delivery", "restaurant")}
      </div>
      {mode === "platform" && (
        <p className="text-[11px] text-dark-500 mt-2">
          Currently: KosherEats couriers — pick a method above to change.
        </p>
      )}
    </div>
  );
}
