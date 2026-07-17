"use client";

// Seller orders list (S19a). Mirrors the iOS SellerOrdersView: Active /
// Completed / All filter tabs over a newest-first ticket list, with a
// ticking "Waiting m:ss" counter on in-kitchen orders and each row linking
// to /seller/orders/[id] (detail ships in the next slice).
//
// Data flow: GET /seller/orders is cursor-paginated (cursor = created_at of
// the last row, RFC3339; a full page means there may be more) and has NO
// status param server-side, so the filter tabs slice the loaded rows
// client-side — "Load more" pulls older pages regardless of the active tab.
//
// Polling copies the app/admin/orders pattern: refresh every 15s while the
// tab is visible, pause when hidden, refresh immediately on return. A failed
// poll keeps the last good list behind a "reconnecting" banner; only the very
// first load can hard-fail the page. Each poll re-fetches the newest slice
// (sized to cover what's loaded, capped at the backend's max of 100) and
// merges it over the loaded list so older paginated rows aren't dropped.
// Orders first seen by a poll (not the initial load) get a "New" highlight.

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronRight, Clock, Inbox, Store } from "lucide-react";
import { formatCents, sellerApi } from "@/lib/sellerApi";
import type { OrderStatus, SellerOrder } from "@/types/seller";

const PAGE_SIZE = 50;
/** Backend caps ?limit= at 100 (ListSellerOrders in handlers/orders.go). */
const MAX_LIMIT = 100;
/** How long a poll-discovered order keeps its "New" highlight. */
const NEW_HIGHLIGHT_MS = 60_000;

/** Statuses on the Active tab (Order.isActive on iOS — picked_up stays
 *  visible so deliveries en route aren't lost). */
const ACTIVE_STATUSES: ReadonlySet<OrderStatus> = new Set([
  "scheduled",
  "pending",
  "accepted",
  "preparing",
  "ready",
  "picked_up",
]);

/** Statuses that show the ticking "Waiting" counter — the kitchen's lane. */
const IN_KITCHEN_STATUSES: ReadonlySet<OrderStatus> = new Set([
  "pending",
  "accepted",
  "preparing",
  "ready",
]);

type OrderFilter = "active" | "completed" | "all";

const FILTER_TABS: { key: OrderFilter; label: string }[] = [
  { key: "active", label: "Active" },
  { key: "completed", label: "Completed" },
  { key: "all", label: "All" },
];

const STATUS_PILL: Record<OrderStatus, string> = {
  scheduled: "bg-sky-500/15 text-sky-300",
  pending: "bg-amber-500/15 text-amber-300",
  accepted: "bg-blue-500/15 text-blue-300",
  preparing: "bg-yellow-500/15 text-yellow-300",
  ready: "bg-orange-500/15 text-orange-300",
  picked_up: "bg-purple-500/15 text-purple-300",
  delivered: "bg-green-500/15 text-green-300",
  completed: "bg-green-500/15 text-green-300",
  cancelled: "bg-red-500/15 text-red-300",
  rejected: "bg-red-500/15 text-red-300",
};

const STATUS_LABEL: Record<OrderStatus, string> = {
  scheduled: "Scheduled",
  pending: "New order",
  accepted: "Accepted",
  preparing: "Preparing",
  ready: "Ready",
  picked_up: "Out for delivery",
  delivered: "Delivered",
  completed: "Completed",
  cancelled: "Cancelled",
  rejected: "Rejected",
};

/**
 * Merge a freshly-polled newest slice over the loaded list: fresh rows win
 * by id (status transitions land), rows beyond the polled window keep their
 * last-known copy (their live state is the detail page's job), and the
 * result stays strictly newest-first to match the backend's ordering.
 */
function mergeFresh(prev: SellerOrder[], fresh: SellerOrder[]): SellerOrder[] {
  const seen = new Set<string>();
  const merged: SellerOrder[] = [];
  for (const order of fresh) {
    if (!seen.has(order.id)) {
      seen.add(order.id);
      merged.push(order);
    }
  }
  for (const order of prev) {
    if (!seen.has(order.id)) {
      seen.add(order.id);
      merged.push(order);
    }
  }
  return merged.sort(
    (a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime(),
  );
}

export default function SellerOrdersPage() {
  const [orders, setOrders] = useState<SellerOrder[]>([]);
  const [filter, setFilter] = useState<OrderFilter>("active");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hasData, setHasData] = useState(false);

  // Cursor pagination ("Load more"). Its errors surface inline by the button
  // so they never masquerade as the poll's reconnecting banner.
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  // Orders first seen by a poll (not the initial load) — "New" highlight.
  const [newIds, setNewIds] = useState<ReadonlySet<string>>(new Set());

  /** Guards against a slow earlier poll overwriting a newer one's results. */
  const requestSeq = useRef(0);
  /** Current list for callbacks (poll sizing, load-more cursor) without
   *  making them re-create — the poll interval holds one stable `load`. */
  const ordersRef = useRef<SellerOrder[]>([]);
  /** Every order id we've ever seen; null until the first successful load
   *  so the initial batch is never flagged as "new". */
  const knownIds = useRef<Set<string> | null>(null);
  const highlightTimers = useRef<ReturnType<typeof setTimeout>[]>([]);

  useEffect(() => {
    ordersRef.current = orders;
  }, [orders]);

  useEffect(
    () => () => {
      highlightTimers.current.forEach(clearTimeout);
    },
    [],
  );

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    try {
      // Cover everything currently loaded (up to the backend cap) so status
      // transitions on already-listed rows land, not just brand-new orders.
      const limit = Math.min(MAX_LIMIT, Math.max(PAGE_SIZE, ordersRef.current.length));
      const fresh = (await sellerApi.orders.list({ limit })) ?? [];
      if (seq !== requestSeq.current) return;

      const firstLoad = knownIds.current === null;
      if (firstLoad) {
        knownIds.current = new Set(fresh.map((o) => o.id));
        // A full first page means older orders may exist past the cursor.
        setHasMore(fresh.length === limit);
        setOrders(fresh);
      } else {
        const brandNew = fresh.filter((o) => !knownIds.current!.has(o.id));
        if (brandNew.length > 0) {
          const ids = brandNew.map((o) => o.id);
          ids.forEach((id) => knownIds.current!.add(id));
          setNewIds((prev) => new Set([...prev, ...ids]));
          highlightTimers.current.push(
            setTimeout(() => {
              setNewIds((prev) => {
                const next = new Set(prev);
                ids.forEach((id) => next.delete(id));
                return next;
              });
            }, NEW_HIGHLIGHT_MS),
          );
        }
        setOrders((prev) => mergeFresh(prev, fresh));
      }
      setHasData(true);
      setError(null);
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setError((err as Error).message || "Failed to load orders");
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, []);

  // Initial load + 15s poll, paused while the tab is hidden and refreshed
  // immediately on return so the list is never 15s stale on focus.
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

  async function loadMore() {
    if (loadingMore || !hasMore) return;
    const last = ordersRef.current[ordersRef.current.length - 1];
    if (!last) return;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const page =
        (await sellerApi.orders.list({ limit: PAGE_SIZE, cursor: last.created_at })) ?? [];
      // Older rows are known-but-not-new — register them so a subsequent
      // poll widening its window never flags them as fresh arrivals.
      page.forEach((o) => knownIds.current?.add(o.id));
      setOrders((prev) => {
        const seen = new Set(prev.map((o) => o.id));
        return [...prev, ...page.filter((o) => !seen.has(o.id))];
      });
      setHasMore(page.length === PAGE_SIZE);
    } catch (err) {
      setLoadMoreError((err as Error).message || "Couldn't load more orders");
    } finally {
      setLoadingMore(false);
    }
  }

  // ── Render ─────────────────────────────────────────────────

  const filtered =
    filter === "all"
      ? orders
      : orders.filter((o) => ACTIVE_STATUSES.has(o.status) === (filter === "active"));

  if (loading) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Orders</h1>
        <div className="flex gap-2 mb-6">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-9 w-24 rounded-xl bg-dark-800 animate-pulse" aria-hidden="true" />
          ))}
        </div>
        <div className="space-y-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="card h-20 animate-pulse" aria-hidden="true" />
          ))}
        </div>
      </div>
    );
  }

  // Only block the whole view when the very first load failed with no good
  // data. A seller with no restaurant yet gets pointed at onboarding instead
  // of a dead retry loop (same shape as the dashboard).
  if (error && !hasData) {
    const noRestaurant = error.toLowerCase().includes("restaurant not found");
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Orders</h1>
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

  return (
    <div>
      <div className="flex items-center justify-between gap-3 mb-6">
        <h1 className="text-2xl font-bold">Orders</h1>
        <span className="text-xs text-dark-500">Refreshes every 15s</span>
      </div>

      {/* Transient poll failure — keep the last good list on screen. */}
      {error && (
        <div className="flex items-center justify-between gap-4 mb-4 px-4 py-2.5 rounded-xl border border-amber-500/30 bg-amber-500/10 text-amber-300 text-sm">
          <span>Reconnecting… showing last loaded orders.</span>
          <button
            onClick={load}
            className="text-xs px-2.5 py-1 rounded-lg bg-amber-500/20 hover:bg-amber-500/30 transition-colors shrink-0"
          >
            Retry
          </button>
        </div>
      )}

      {/* Filter tabs (client-side — the endpoint has no status param). */}
      <div className="flex gap-2 mb-6">
        {FILTER_TABS.map((tab) => {
          const selected = filter === tab.key;
          return (
            <button
              key={tab.key}
              onClick={() => setFilter(tab.key)}
              aria-pressed={selected}
              className={`px-4 py-2 rounded-xl text-sm font-semibold transition-colors ${
                selected
                  ? "bg-brand-500 text-white"
                  : "bg-dark-800 text-dark-300 hover:bg-dark-700 hover:text-white"
              }`}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      {filtered.length === 0 ? (
        <div className="card p-10 text-center">
          <Inbox className="w-10 h-10 text-dark-500 mx-auto mb-3" aria-hidden="true" />
          <p className="font-semibold mb-1">
            {filter === "all" ? "No orders yet" : `No ${filter} orders`}
          </p>
          <p className="text-sm text-dark-400">New orders will appear here automatically.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map((order) => (
            <OrderRow key={order.id} order={order} isNew={newIds.has(order.id)} />
          ))}
        </div>
      )}

      {/* Older pages — always offered while more exist, even under a filter:
          loading more may reveal more rows for the current tab. */}
      {hasMore && (
        <div className="mt-6 text-center">
          {loadMoreError && <p className="text-sm text-red-400 mb-3">{loadMoreError}</p>}
          <button onClick={loadMore} disabled={loadingMore} className="btn-secondary disabled:opacity-50">
            {loadingMore ? "Loading…" : "Load older orders"}
          </button>
        </div>
      )}
    </div>
  );
}

// ── Order row ────────────────────────────────────────────────

function OrderRow({ order, isNew }: { order: SellerOrder; isNew: boolean }) {
  const items = order.items ?? [];
  const itemCount = items.reduce((sum, item) => sum + item.quantity, 0);

  return (
    <Link
      href={`/seller/orders/${order.id}`}
      className={`card flex items-center gap-4 p-4 hover:bg-dark-800 transition-colors ${
        isNew ? "border-amber-500/60" : ""
      }`}
    >
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="font-semibold">Order #{order.id.slice(0, 8)}</span>
          {isNew && (
            <span className="shrink-0 text-[10px] font-bold uppercase tracking-wide bg-amber-500/15 text-amber-300 px-1.5 py-0.5 rounded-md">
              New
            </span>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs text-dark-400 mt-1">
          <span>
            {new Date(order.created_at).toLocaleString([], {
              month: "short",
              day: "numeric",
              hour: "numeric",
              minute: "2-digit",
            })}
          </span>
          <span aria-hidden="true">•</span>
          <span>
            {itemCount} {itemCount === 1 ? "item" : "items"}
          </span>
          <span aria-hidden="true">•</span>
          <span>{order.fulfillment_type === "pickup" ? "Pickup" : "Delivery"}</span>
          {order.customer_name && (
            <>
              <span aria-hidden="true">•</span>
              <span className="truncate">{order.customer_name}</span>
            </>
          )}
        </div>
        {IN_KITCHEN_STATUSES.has(order.status) && (
          <div className="mt-1.5">
            <WaitingTimer since={order.updated_at || order.created_at} />
          </div>
        )}
      </div>

      <div className="flex flex-col items-end gap-1.5 shrink-0">
        <span className="font-bold">{formatCents(order.total)}</span>
        <span
          className={`text-xs px-2 py-0.5 rounded-md font-semibold ${
            STATUS_PILL[order.status] ?? "bg-dark-700 text-dark-300"
          }`}
        >
          {STATUS_LABEL[order.status] ?? order.status}
        </span>
      </div>

      <ChevronRight className="w-4 h-4 text-dark-500 shrink-0" aria-hidden="true" />
    </Link>
  );
}

/**
 * Ticking time-in-current-state counter for orders still in the kitchen's
 * lane, so the seller can spot stale tickets at a glance (OrderRowView on
 * iOS). Keyed off updated_at (the last status-transition timestamp), not
 * created_at: a scheduled order promoted to pending hours after checkout
 * must show time-in-state, not a bogus multi-hour wait. Flips red past
 * 15 minutes. 10s tick per row matches the card-owns-its-clock precedent
 * set by ActiveOrderCard's PendingCountdown.
 */
function WaitingTimer({ since }: { since: string }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 10_000);
    return () => clearInterval(timer);
  }, []);

  const elapsed = Math.max(0, now - new Date(since).getTime());
  const mins = Math.floor(elapsed / 60_000);
  const secs = Math.floor((elapsed % 60_000) / 1000);
  const overdue = elapsed >= 15 * 60_000;

  return (
    <span
      className={`inline-flex items-center gap-1 text-xs font-semibold tabular-nums ${
        overdue ? "text-red-400" : "text-dark-500"
      }`}
    >
      <Clock className="w-3 h-3" aria-hidden="true" />
      Waiting {mins}:{String(secs).padStart(2, "0")}
    </span>
  );
}
