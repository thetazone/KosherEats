"use client";

// Seller order detail + actions (S19b). Mirrors the iOS SellerOrderDetailView:
// status header, items with modifiers, customer + delivery info, money
// breakdown, and the status-appropriate action ladder — plus a status
// timeline, the per-order delivery-mode override, and the escalate-to-Uber
// handoff for overwhelmed self-delivery sellers.
//
// Every mutation is optimistic: the status flips locally the moment the
// button is pressed, and rolls back with an error toast if the PATCH fails.
// accept/reject/preparing/ready/complete/delivery-mode return the full
// refreshed order; pickup/deliver/escalate don't (bare acks — see
// handlers/orders.go), so those re-fetch after success.
//
// Polls every 15s while the tab is visible (same pattern as the orders list)
// so courier-driven transitions (picked_up → delivered) land without a manual
// refresh; polling pauses while an action is in flight so a stale snapshot
// never clobbers an optimistic update.

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  ArrowLeft,
  ArrowUpRight,
  Bike,
  Check,
  ChefHat,
  Clock,
  CreditCard,
  Flame,
  Loader2,
  MapPin,
  OctagonX,
  Phone,
  ShoppingBag,
  Star,
  Store,
  Truck,
  User,
  X,
} from "lucide-react";
import { ORDER_STATUS_META } from "@/lib/orderStatus";
import { formatCents, sellerApi } from "@/lib/sellerApi";
import type { OrderStatus, SellerCourierPublic, SellerOrder } from "@/types/seller";

/** Matches pendingOrderTTL in backend/internal/scheduler/dispatcher.go —
 *  after 10 minutes in 'pending' the backend auto-rejects and refunds. */
const PENDING_TTL_MS = 10 * 60 * 1000;
const PENDING_URGENT_MS = 2 * 60 * 1000;

/** Statuses during which the per-order routing override / escalate are open
 *  (matches SetOrderDeliveryMode + EscalateToUber eligibility in orders.go). */
const ROUTABLE_STATUSES: ReadonlySet<OrderStatus> = new Set([
  "accepted",
  "preparing",
  "ready",
]);

type OrderAction =
  | "accept"
  | "reject"
  | "preparing"
  | "ready"
  | "complete" // pickup fulfillment: ready -> completed (customer collected)
  | "pickup" // self-delivery: ready -> picked_up (own driver left)
  | "deliver"; // self-delivery: picked_up -> delivered

/** What the UI flips the status to the instant the button is pressed. */
const ACTION_TARGET: Record<OrderAction, OrderStatus> = {
  accept: "accepted",
  reject: "rejected",
  preparing: "preparing",
  ready: "ready",
  complete: "completed",
  pickup: "picked_up",
  deliver: "delivered",
};

type Busy = OrderAction | "mode" | "escalate" | null;

// ── Shared helpers (parity with ActiveOrderCard / iOS) ───────

function isPickup(order: SellerOrder): boolean {
  return order.fulfillment_type === "pickup";
}

/** The seller's own driver runs this order (no platform courier, no
 *  Uber/DoorDash handoff — once escalated, external_delivery_id is set). */
function isSelfDelivery(order: SellerOrder): boolean {
  return order.delivery_mode === "restaurant" && !order.courier && !order.external_delivery_id;
}

function readyLabel(order: SellerOrder): string {
  if (isPickup(order)) return "Ready for customer pickup";
  if (isSelfDelivery(order)) return "Ready for your driver";
  if (order.delivery_mode === "external") return "Ready for Uber pickup";
  return "Ready for courier pickup";
}

function providerName(order: SellerOrder): string {
  switch (order.external_provider) {
    case "uber_direct":
      return "Uber";
    case "doordash_drive":
      return "DoorDash";
    default:
      return "a delivery partner";
  }
}

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

/** Go time.Time zero value ("0001-01-01T00:00:00Z") means "not set". */
function isRealDate(iso: string | undefined): iso is string {
  if (!iso) return false;
  const d = new Date(iso);
  return !Number.isNaN(d.getTime()) && d.getFullYear() > 2000;
}

/** The backend masks customer phones to the last 4 digits ("*******1234") —
 *  a tel: link built from that would dial garbage, so only link unmasked. */
function telHref(phone: string | undefined): string | null {
  if (!phone || phone.includes("*")) return null;
  const cleaned = phone.replace(/[^\d+]/g, "");
  return cleaned ? `tel:${cleaned}` : null;
}

// ── Page ─────────────────────────────────────────────────────

export default function SellerOrderDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;

  const [order, setOrder] = useState<SellerOrder | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [busy, setBusy] = useState<Busy>(null);
  const [toast, setToast] = useState<string | null>(null);

  // Reject is destructive (refunds the customer) → inline confirm panel with
  // the optional-reason field (iOS reject alert parity). Escalate is a paid,
  // one-way dispatch → second click too.
  const [confirmingReject, setConfirmingReject] = useState(false);
  const [rejectReason, setRejectReason] = useState("");
  const [confirmingEscalate, setConfirmingEscalate] = useState(false);

  /** Guards against a slow earlier fetch overwriting a newer one's result. */
  const requestSeq = useRef(0);
  /** Mirror of `busy` for the poll timer, which holds one stable callback. */
  const busyRef = useRef<Busy>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    busyRef.current = busy;
  }, [busy]);

  useEffect(
    () => () => {
      if (toastTimer.current) clearTimeout(toastTimer.current);
    },
    [],
  );

  function showToast(message: string) {
    setToast(message);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 6000);
  }

  const load = useCallback(
    async (opts: { background?: boolean } = {}) => {
      if (!id) return;
      // Never let a background poll race an in-flight action — its snapshot
      // predates the optimistic update and would visually revert the status.
      if (opts.background && busyRef.current) return;
      const seq = ++requestSeq.current;
      try {
        const fresh = await sellerApi.orders.get(id);
        if (seq !== requestSeq.current) return;
        if (opts.background && busyRef.current) return;
        setOrder(fresh);
        setError(null);
      } catch (err) {
        if (seq !== requestSeq.current) return;
        // A failed background poll keeps the last good order on screen.
        if (!opts.background) setError((err as Error).message || "Failed to load order");
      } finally {
        if (seq === requestSeq.current) setLoading(false);
      }
    },
    [id],
  );

  // Initial load + 15s poll, paused while the tab is hidden and refreshed
  // immediately on return (same pattern as the orders list).
  useEffect(() => {
    load();

    let timer: ReturnType<typeof setInterval> | null = null;
    const start = () => {
      if (timer === null) timer = setInterval(() => load({ background: true }), 15_000);
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
        load({ background: true });
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

  // ── Mutations (optimistic, rollback + toast on failure) ────

  async function runAction(action: OrderAction) {
    if (!order || busy) return;
    const snapshot = order;
    setBusy(action);
    setToast(null);
    // Bump the seq so any in-flight poll response from before this action
    // can't land on top of the optimistic state.
    requestSeq.current++;
    setOrder({ ...order, status: ACTION_TARGET[action] });
    try {
      if (action === "pickup" || action === "deliver") {
        // These two return a bare {status} ack, not the order — re-fetch so
        // the new timestamps (picked_up_at / delivered_at) reach the timeline.
        await (action === "pickup"
          ? sellerApi.orders.pickup(order.id)
          : sellerApi.orders.deliver(order.id));
        await load();
      } else {
        const res =
          action === "reject"
            ? await sellerApi.orders.reject(order.id, rejectReason)
            : action === "accept"
              ? await sellerApi.orders.accept(order.id)
              : action === "preparing"
                ? await sellerApi.orders.markPreparing(order.id)
                : action === "ready"
                  ? await sellerApi.orders.markReady(order.id)
                  : await sellerApi.orders.complete(order.id);
        setOrder(res);
      }
      if (action === "reject") {
        setConfirmingReject(false);
        setRejectReason("");
      }
    } catch (err) {
      setOrder(snapshot);
      showToast((err as Error).message || "Couldn't update the order");
    } finally {
      setBusy(null);
    }
  }

  async function runSetMode(mode: "restaurant" | "external") {
    if (!order || busy || order.delivery_mode === mode) return;
    const snapshot = order;
    setBusy("mode");
    setToast(null);
    requestSeq.current++;
    setOrder({ ...order, delivery_mode: mode });
    try {
      setOrder(await sellerApi.orders.setDeliveryMode(order.id, mode));
    } catch (err) {
      setOrder(snapshot);
      showToast((err as Error).message || "Couldn't update the delivery choice");
    } finally {
      setBusy(null);
    }
  }

  async function runEscalate() {
    if (!order || busy) return;
    setBusy("escalate");
    setToast(null);
    try {
      // No optimistic flip — status doesn't change; the external_* fields do.
      await sellerApi.orders.escalate(order.id);
      await load(); // pulls external_delivery_id / provider / tracking URL
      setConfirmingEscalate(false);
    } catch (err) {
      showToast((err as Error).message || "Couldn't hand the order to Uber Direct");
    } finally {
      setBusy(null);
    }
  }

  // ── Render ─────────────────────────────────────────────────

  const heading = (
    <div className="mb-6">
      <Link
        href="/seller/orders"
        className="inline-flex items-center gap-1.5 text-sm text-dark-400 hover:text-white transition-colors mb-3"
      >
        <ArrowLeft className="w-4 h-4" aria-hidden="true" />
        Orders
      </Link>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-bold">Order #{id ? id.slice(0, 8) : ""}</h1>
        {order && (
          <>
            <span
              className={`text-xs px-2.5 py-1 rounded-lg font-semibold ${
                ORDER_STATUS_META[order.status]?.pill ?? "bg-dark-700 text-dark-300"
              }`}
            >
              {ORDER_STATUS_META[order.status]?.sellerLabel ?? order.status}
            </span>
            <span className="inline-flex items-center gap-1.5 text-xs font-bold uppercase tracking-wide text-brand-400 bg-brand-500/15 px-2.5 py-1 rounded-lg">
              {isPickup(order) ? (
                <ShoppingBag className="w-3.5 h-3.5" aria-hidden="true" />
              ) : (
                <Bike className="w-3.5 h-3.5" aria-hidden="true" />
              )}
              {isPickup(order) ? "Pickup" : "Delivery"}
            </span>
          </>
        )}
      </div>
      {order && (
        <p className="text-xs text-dark-500 mt-1.5">Placed {formatWhen(order.created_at)}</p>
      )}
    </div>
  );

  if (loading) {
    return (
      <div>
        {heading}
        <div className="flex flex-col lg:grid lg:grid-cols-3 gap-6">
          <div className="space-y-6 lg:col-span-2">
            <div className="card h-64 animate-pulse" aria-hidden="true" />
            <div className="card h-48 animate-pulse" aria-hidden="true" />
          </div>
          <div className="space-y-6">
            <div className="card h-40 animate-pulse" aria-hidden="true" />
            <div className="card h-32 animate-pulse" aria-hidden="true" />
          </div>
        </div>
      </div>
    );
  }

  if (error && !order) {
    const notFound = error.toLowerCase().includes("order not found");
    return (
      <div>
        {heading}
        <div className="card p-10 text-center">
          {notFound ? (
            <>
              <Store className="w-10 h-10 text-dark-500 mx-auto mb-3" aria-hidden="true" />
              <p className="font-semibold mb-1">Order not found</p>
              <p className="text-sm text-dark-400 mb-5">
                It may belong to a different restaurant — check the picker in the sidebar.
              </p>
              <Link href="/seller/orders" className="btn-secondary inline-block">
                Back to orders
              </Link>
            </>
          ) : (
            <>
              <p className="text-red-400 mb-4">{error}</p>
              <button onClick={() => load()} className="btn-secondary">
                Try again
              </button>
            </>
          )}
        </div>
      </div>
    );
  }

  if (!order) return null;

  const items = order.items ?? [];
  const canRoute =
    !isPickup(order) &&
    ROUTABLE_STATUSES.has(order.status) &&
    !order.courier &&
    !order.external_delivery_id;
  const showPartnerCard =
    Boolean(order.external_delivery_id) &&
    !["delivered", "completed", "cancelled", "rejected"].includes(order.status);
  const showCourierCard =
    Boolean(order.courier) && ["ready", "picked_up"].includes(order.status);

  return (
    <div>
      {heading}

      <div className="flex flex-col lg:grid lg:grid-cols-3 gap-6">
        {/* Actions + routing + people — first on mobile so the next step is
            always above the fold, right rail on desktop. */}
        <div className="order-1 lg:order-2 space-y-6">
          <ActionsCard
            order={order}
            busy={busy}
            confirmingReject={confirmingReject}
            setConfirmingReject={setConfirmingReject}
            rejectReason={rejectReason}
            setRejectReason={setRejectReason}
            onAction={runAction}
          />

          {canRoute && (
            <RoutingCard
              order={order}
              busy={busy}
              confirmingEscalate={confirmingEscalate}
              setConfirmingEscalate={setConfirmingEscalate}
              onSetMode={runSetMode}
              onEscalate={runEscalate}
            />
          )}

          {showCourierCard && order.courier && (
            <CourierCard courier={order.courier} pickedUp={order.status === "picked_up"} />
          )}
          {!showCourierCard && showPartnerCard && <PartnerCard order={order} />}

          {order.customer_name && (
            <CustomerCard name={order.customer_name} phone={order.customer_phone} />
          )}

          <FulfillmentCard order={order} />
        </div>

        {/* Items, money, timeline. */}
        <div className="order-2 lg:order-1 lg:col-span-2 space-y-6">
          <ItemsCard order={order} />
          <PaymentCard order={order} />
          <TimelineCard order={order} />
        </div>
      </div>

      {/* Error toast — action failures roll the status back and land here. */}
      {toast && (
        <div
          role="alert"
          className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 flex items-center gap-3 max-w-[calc(100vw-2rem)] px-4 py-3 rounded-xl border border-red-500/40 bg-dark-900 shadow-lg shadow-black/40 text-sm text-red-300"
        >
          <OctagonX className="w-4 h-4 shrink-0" aria-hidden="true" />
          <span className="min-w-0">{toast}</span>
          <button
            onClick={() => setToast(null)}
            aria-label="Dismiss error"
            className="p-1 rounded-lg hover:bg-red-500/20 transition-colors shrink-0"
          >
            <X className="w-4 h-4" aria-hidden="true" />
          </button>
        </div>
      )}
    </div>
  );
}

// ── Actions card ─────────────────────────────────────────────

function ActionsCard({
  order,
  busy,
  confirmingReject,
  setConfirmingReject,
  rejectReason,
  setRejectReason,
  onAction,
}: {
  order: SellerOrder;
  busy: Busy;
  confirmingReject: boolean;
  setConfirmingReject: (v: boolean) => void;
  rejectReason: string;
  setRejectReason: (v: string) => void;
  onAction: (action: OrderAction) => void;
}) {
  const acting = busy !== null;
  const btn =
    "w-full flex items-center justify-center gap-2 py-3 px-4 rounded-xl text-sm font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed";

  let body: React.ReactNode;
  switch (order.status) {
    case "scheduled":
      body = (
        <StatusHint
          icon={<Clock className="w-4 h-4" aria-hidden="true" />}
          text={
            order.scheduled_for
              ? `Scheduled for ${formatWhen(order.scheduled_for)} — it becomes a new order automatically.`
              : "Scheduled — it becomes a new order automatically."
          }
        />
      );
      break;

    case "pending":
      body = confirmingReject ? (
        <div className="space-y-2.5">
          <input
            type="text"
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
            placeholder="Reason (optional)"
            maxLength={200}
            className="input w-full text-sm"
          />
          <button
            onClick={() => onAction("reject")}
            disabled={acting}
            className={`${btn} bg-red-500/15 text-red-400 hover:bg-red-500/25`}
          >
            {busy === "reject" ? (
              <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
            ) : (
              <OctagonX className="w-4 h-4" aria-hidden="true" />
            )}
            {busy === "reject" ? "Rejecting…" : "Confirm reject & refund"}
          </button>
          <button
            onClick={() => {
              setConfirmingReject(false);
              setRejectReason("");
            }}
            disabled={acting}
            className={`${btn} bg-dark-800 text-dark-300 hover:bg-dark-700`}
          >
            Keep order
          </button>
        </div>
      ) : (
        <div className="space-y-2.5">
          <PendingCountdown since={order.updated_at || order.created_at} />
          <button
            onClick={() => onAction("accept")}
            disabled={acting}
            className={`${btn} bg-green-500/15 text-green-400 hover:bg-green-500/25`}
          >
            {busy === "accept" ? (
              <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
            ) : (
              <Check className="w-4 h-4" aria-hidden="true" />
            )}
            {busy === "accept" ? "Accepting…" : "Accept order"}
          </button>
          <button
            onClick={() => setConfirmingReject(true)}
            disabled={acting}
            className={`${btn} bg-red-500/15 text-red-400 hover:bg-red-500/25`}
          >
            <X className="w-4 h-4" aria-hidden="true" />
            Reject order
          </button>
        </div>
      );
      break;

    case "accepted":
      body = (
        <button
          onClick={() => onAction("preparing")}
          disabled={acting}
          className={`${btn} bg-brand-500/15 text-brand-400 hover:bg-brand-500/25`}
        >
          {busy === "preparing" ? (
            <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
          ) : (
            <Flame className="w-4 h-4" aria-hidden="true" />
          )}
          {busy === "preparing" ? "Updating…" : "Start preparing"}
        </button>
      );
      break;

    case "preparing":
      body = (
        <button
          onClick={() => onAction("ready")}
          disabled={acting}
          className={`${btn} bg-green-500/15 text-green-400 hover:bg-green-500/25`}
        >
          {busy === "ready" ? (
            <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
          ) : (
            <ChefHat className="w-4 h-4" aria-hidden="true" />
          )}
          {busy === "ready" ? "Updating…" : readyLabel(order)}
        </button>
      );
      break;

    case "ready":
      if (isPickup(order)) {
        body = (
          <button
            onClick={() => onAction("complete")}
            disabled={acting}
            className={`${btn} bg-green-500/15 text-green-400 hover:bg-green-500/25`}
          >
            {busy === "complete" ? (
              <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
            ) : (
              <Check className="w-4 h-4" aria-hidden="true" />
            )}
            {busy === "complete" ? "Updating…" : "Customer picked up"}
          </button>
        );
      } else if (isSelfDelivery(order)) {
        body = (
          <button
            onClick={() => onAction("pickup")}
            disabled={acting}
            className={`${btn} bg-brand-500/15 text-brand-400 hover:bg-brand-500/25`}
          >
            {busy === "pickup" ? (
              <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
            ) : (
              <Bike className="w-4 h-4" aria-hidden="true" />
            )}
            {busy === "pickup" ? "Updating…" : "Driver picked up"}
          </button>
        );
      } else {
        body = (
          <StatusHint
            icon={<Bike className="w-4 h-4" aria-hidden="true" />}
            text={
              order.courier
                ? `${order.courier.first_name} is picking up`
                : order.external_delivery_id
                  ? `Waiting for ${providerName(order)} pickup`
                  : "Waiting for courier pickup"
            }
          />
        );
      }
      break;

    case "picked_up":
      if (isSelfDelivery(order)) {
        body = (
          <button
            onClick={() => onAction("deliver")}
            disabled={acting}
            className={`${btn} bg-green-500/15 text-green-400 hover:bg-green-500/25`}
          >
            {busy === "deliver" ? (
              <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
            ) : (
              <Check className="w-4 h-4" aria-hidden="true" />
            )}
            {busy === "deliver" ? "Updating…" : "Mark delivered"}
          </button>
        );
      } else {
        body = (
          <StatusHint
            icon={<Bike className="w-4 h-4" aria-hidden="true" />}
            text={
              order.courier
                ? `${order.courier.first_name} is delivering`
                : order.external_delivery_id
                  ? `${providerName(order)} is delivering`
                  : "Out for delivery"
            }
          />
        );
      }
      break;

    default:
      // Terminal: delivered / completed / cancelled / rejected.
      body = (
        <StatusHint
          icon={
            order.status === "cancelled" || order.status === "rejected" ? (
              <OctagonX className="w-4 h-4 text-red-400" aria-hidden="true" />
            ) : (
              <Check className="w-4 h-4 text-green-400" aria-hidden="true" />
            )
          }
          text={ORDER_STATUS_META[order.status]?.sellerLabel ?? order.status}
        />
      );
  }

  return (
    <div className="card p-5">
      <SectionHeader icon={<Flame className="w-4 h-4" aria-hidden="true" />} title="Actions" />
      {body}
    </div>
  );
}

function StatusHint({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-3 px-3 rounded-xl bg-dark-800/60 text-sm text-dark-400 text-center">
      <span className="shrink-0">{icon}</span>
      {text}
    </div>
  );
}

/** Live countdown to the backend's pending auto-reject deadline. Keyed off
 *  updated_at (the pending-transition timestamp) so a scheduled order promoted
 *  hours after checkout counts from the promotion (ActiveOrderCard parity). */
function PendingCountdown({ since }: { since: string }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const elapsed = Math.max(0, now - new Date(since).getTime());
  const remaining = Math.max(0, PENDING_TTL_MS - elapsed);
  const expired = remaining <= 0;
  const urgent = remaining <= PENDING_URGENT_MS;

  const fmt = (ms: number) => {
    const s = Math.floor(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  };

  return (
    <div
      className={`flex items-center justify-center gap-1.5 text-xs font-semibold tabular-nums ${
        expired || urgent ? "text-red-400" : "text-amber-400"
      }`}
    >
      {expired ? (
        <OctagonX className="w-3.5 h-3.5" aria-hidden="true" />
      ) : (
        <Clock className="w-3.5 h-3.5" aria-hidden="true" />
      )}
      {expired ? "Auto-rejecting…" : `Respond in ${fmt(remaining)} or it auto-rejects`}
    </div>
  );
}

// ── Delivery routing card ────────────────────────────────────

/**
 * Per-order override of who delivers ("Uber Direct" vs self-delivery) plus
 * the escalate handoff. Only rendered while the order is still routable:
 * delivery fulfillment, status accepted/preparing/ready, no courier claim,
 * no external dispatch (mirrors the backend eligibility guards).
 */
function RoutingCard({
  order,
  busy,
  confirmingEscalate,
  setConfirmingEscalate,
  onSetMode,
  onEscalate,
}: {
  order: SellerOrder;
  busy: Busy;
  confirmingEscalate: boolean;
  setConfirmingEscalate: (v: boolean) => void;
  onSetMode: (mode: "restaurant" | "external") => void;
  onEscalate: () => void;
}) {
  const acting = busy !== null;
  const mode = order.delivery_mode;

  const pill = (label: string, value: "external" | "restaurant") => {
    const selected = mode === value;
    // The mode flips optimistically, so while the PATCH is in flight the
    // selected pill IS the one that was just clicked — spin there.
    const inFlight = busy === "mode" && selected;
    return (
      <button
        onClick={() => onSetMode(value)}
        disabled={acting || selected}
        aria-pressed={selected}
        className={`flex-1 flex items-center justify-center gap-1.5 py-2 px-3 rounded-lg text-xs font-semibold transition-colors disabled:cursor-not-allowed ${
          selected
            ? "bg-green-500 text-white"
            : "bg-dark-800 text-dark-300 hover:bg-dark-700 disabled:opacity-50"
        }`}
      >
        {inFlight && <Loader2 className="w-3.5 h-3.5 animate-spin" aria-hidden="true" />}
        {label}
      </button>
    );
  };

  return (
    <div className="card p-5">
      <SectionHeader icon={<Truck className="w-4 h-4" aria-hidden="true" />} title="Delivery routing" />
      <p className="text-xs text-dark-400 mb-2.5">Who delivers this order:</p>
      <div className="flex gap-1.5">
        {pill("Uber Direct", "external")}
        {pill("Self-delivery", "restaurant")}
      </div>
      {mode === "platform" && (
        <p className="text-[11px] text-dark-500 mt-2">
          Currently: KosherEats couriers — pick a method above to change.
        </p>
      )}

      {/* Escalate: hand a self-delivery order to Uber Direct right now. Only
          offered while self-delivering and gated (with the courier check in
          canRoute) on external_delivery_id being absent — once dispatched
          it's one-way and this whole card disappears. */}
      {mode === "restaurant" && (
        <div className="border-t border-dark-800 mt-4 pt-4">
          {confirmingEscalate ? (
            <div className="space-y-2.5">
              <p className="text-xs text-dark-400">
                A driver is dispatched immediately and the handoff can&apos;t be undone.
              </p>
              <button
                onClick={onEscalate}
                disabled={acting}
                className="w-full flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-sm font-semibold bg-brand-500/15 text-brand-400 hover:bg-brand-500/25 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {busy === "escalate" ? (
                  <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
                ) : (
                  <Truck className="w-4 h-4" aria-hidden="true" />
                )}
                {busy === "escalate" ? "Dispatching…" : "Confirm — dispatch a driver"}
              </button>
              <button
                onClick={() => setConfirmingEscalate(false)}
                disabled={acting}
                className="w-full py-2.5 px-3 rounded-xl text-sm font-semibold bg-dark-800 text-dark-300 hover:bg-dark-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                Keep self-delivery
              </button>
            </div>
          ) : (
            <>
              <button
                onClick={() => setConfirmingEscalate(true)}
                disabled={acting}
                className="w-full flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-sm font-semibold bg-dark-800 text-dark-200 hover:bg-dark-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Truck className="w-4 h-4" aria-hidden="true" />
                Hand off to Uber Direct
              </button>
              <p className="text-[11px] text-dark-500 mt-2">
                No driver available? Dispatch an Uber Direct courier for this order.
              </p>
            </>
          )}
        </div>
      )}
    </div>
  );
}

// ── Courier / partner cards ──────────────────────────────────

function vehicleSummary(c: SellerCourierPublic): string {
  const vehicle = [c.vehicle_color, c.vehicle_make, c.vehicle_model]
    .filter(Boolean)
    .join(" ");
  return [vehicle || c.vehicle_type, c.license_plate].filter(Boolean).join(" • ");
}

function CourierCard({
  courier,
  pickedUp,
}: {
  courier: SellerCourierPublic;
  pickedUp: boolean;
}) {
  const href = telHref(courier.phone);
  return (
    <div className="card p-5">
      <SectionHeader
        icon={<Bike className="w-4 h-4" aria-hidden="true" />}
        title={pickedUp ? "Courier is delivering" : "Courier on the way to pick up"}
      />
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-full bg-dark-800 flex items-center justify-center shrink-0">
          <span className="font-bold text-brand-400">
            {courier.first_name.slice(0, 1).toUpperCase()}
          </span>
        </div>
        <div className="min-w-0 flex-1">
          <div className="font-semibold truncate">{courier.first_name}</div>
          <div className="flex items-center gap-1 text-xs text-dark-400 mt-0.5">
            <Star className="w-3 h-3 text-amber-400 fill-amber-400" aria-hidden="true" />
            {courier.rating.toFixed(1)}
            <span aria-hidden="true">•</span>
            {courier.total_deliveries} deliveries
          </div>
          {vehicleSummary(courier) && (
            <div className="text-xs text-dark-500 mt-0.5 truncate">{vehicleSummary(courier)}</div>
          )}
        </div>
        {href && (
          <a
            href={href}
            aria-label={`Call ${courier.first_name}`}
            className="p-2.5 rounded-full bg-dark-800 text-brand-400 hover:bg-dark-700 transition-colors shrink-0"
          >
            <Phone className="w-4 h-4" aria-hidden="true" />
          </a>
        )}
      </div>
    </div>
  );
}

function PartnerCard({ order }: { order: SellerOrder }) {
  return (
    <div className="card p-5">
      <SectionHeader
        icon={<Truck className="w-4 h-4" aria-hidden="true" />}
        title={
          order.status === "picked_up"
            ? `Out for delivery with ${providerName(order)}`
            : `Handed to ${providerName(order)} — a courier is on the way`
        }
      />
      {order.external_tracking_url && (
        <a
          href={order.external_tracking_url}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-2 py-2.5 px-3 rounded-xl bg-dark-800 text-brand-400 text-sm font-semibold hover:bg-dark-700 transition-colors"
        >
          <MapPin className="w-4 h-4" aria-hidden="true" />
          Track delivery
          <ArrowUpRight className="w-3.5 h-3.5 ml-auto" aria-hidden="true" />
        </a>
      )}
      {order.external_delivery_id && (
        <p className="text-[11px] text-dark-500 mt-2.5 break-all">
          Delivery ID: {order.external_delivery_id}
        </p>
      )}
    </div>
  );
}

// ── Customer + fulfillment cards ─────────────────────────────

function CustomerCard({ name, phone }: { name: string; phone?: string }) {
  const href = telHref(phone);
  return (
    <div className="card p-5">
      <SectionHeader icon={<User className="w-4 h-4" aria-hidden="true" />} title="Customer" />
      <div className="flex items-center gap-3">
        <div className="w-11 h-11 rounded-full bg-dark-800 flex items-center justify-center shrink-0">
          <span className="font-bold text-brand-400">{name.slice(0, 1).toUpperCase()}</span>
        </div>
        <div className="min-w-0 flex-1">
          <div className="font-semibold truncate">{name}</div>
          {phone && <div className="text-xs text-dark-400 mt-0.5">{phone}</div>}
        </div>
        {href && (
          <a
            href={href}
            aria-label={`Call ${name}`}
            className="p-2.5 rounded-full bg-dark-800 text-brand-400 hover:bg-dark-700 transition-colors shrink-0"
          >
            <Phone className="w-4 h-4" aria-hidden="true" />
          </a>
        )}
      </div>
    </div>
  );
}

/** Pickup orders get a counter-pickup note (the address the backend stores is
 *  the customer's home, not a destination); delivery orders get address + ETA. */
function FulfillmentCard({ order }: { order: SellerOrder }) {
  if (isPickup(order)) {
    return (
      <div className="card p-5">
        <SectionHeader icon={<ShoppingBag className="w-4 h-4" aria-hidden="true" />} title="Pickup" />
        <div className="flex items-center gap-2.5 text-sm text-dark-300">
          <Store className="w-4 h-4 text-brand-400 shrink-0" aria-hidden="true" />
          Customer collects at the counter
        </div>
      </div>
    );
  }
  return (
    <div className="card p-5">
      <SectionHeader icon={<MapPin className="w-4 h-4" aria-hidden="true" />} title="Delivery" />
      <div className="space-y-2 text-sm">
        <div className="flex items-start gap-2.5">
          <MapPin className="w-4 h-4 text-brand-400 shrink-0 mt-0.5" aria-hidden="true" />
          <span className="text-dark-200">{order.delivery_address}</span>
        </div>
        {isRealDate(order.est_delivery_time) && (
          <div className="flex items-center gap-2.5 text-xs text-dark-400">
            <Clock className="w-4 h-4 shrink-0" aria-hidden="true" />
            ETA: {formatWhen(order.est_delivery_time)}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Items card ───────────────────────────────────────────────

function ItemsCard({ order }: { order: SellerOrder }) {
  const items = order.items ?? [];
  const itemCount = items.reduce((sum, item) => sum + item.quantity, 0);

  return (
    <div className="card p-5">
      <SectionHeader
        icon={<ShoppingBag className="w-4 h-4" aria-hidden="true" />}
        title={`Order items (${itemCount})`}
      />
      {items.length === 0 ? (
        <p className="text-sm text-dark-400">No items on this order.</p>
      ) : (
        <div className="divide-y divide-dark-800">
          {items.map((item) => (
            <div key={item.id} className="flex items-start gap-3 py-3 first:pt-0 last:pb-0">
              <span className="text-brand-500 font-bold text-sm shrink-0 w-8">
                {item.quantity}x
              </span>
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium">{item.name}</div>
                {(item.selected_modifiers?.length ?? 0) > 0 && (
                  <div className="text-xs text-dark-400 mt-0.5">
                    {item.selected_modifiers!.map((m) => m.name).join(" • ")}
                  </div>
                )}
                {item.notes && (
                  <div className="text-xs text-dark-500 italic mt-0.5">{item.notes}</div>
                )}
              </div>
              {/* Per-unit price already includes modifier deltas. */}
              <span className="text-sm text-dark-300 shrink-0">
                {formatCents(item.price * item.quantity)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── Payment card ─────────────────────────────────────────────

function PaymentCard({ order }: { order: SellerOrder }) {
  const row = (label: string, cents: number) => (
    <div className="flex items-center justify-between text-sm">
      <span className="text-dark-400">{label}</span>
      <span>{formatCents(cents)}</span>
    </div>
  );

  return (
    <div className="card p-5">
      <SectionHeader icon={<CreditCard className="w-4 h-4" aria-hidden="true" />} title="Payment" />
      <div className="space-y-2">
        {row("Subtotal", order.subtotal)}
        {order.discount > 0 && (
          <div className="flex items-center justify-between text-sm text-green-400">
            <span>Savings</span>
            <span>-{formatCents(order.discount)}</span>
          </div>
        )}
        {/* Pickup orders carry no delivery fee (backend forces 0) and the
            service fee is always 0 today — suppress meaningless $0.00 rows. */}
        {!isPickup(order) && order.delivery_fee > 0 && row("Delivery fee", order.delivery_fee)}
        {order.service_fee > 0 && row("Service fee", order.service_fee)}
        {row("Tax", order.tax)}
        {order.courier_tip > 0 && row("Courier tip", order.courier_tip)}
        <div className="border-t border-dark-800 pt-2.5 flex items-center justify-between">
          <span className="font-semibold">Total</span>
          <span className="font-bold text-brand-400">{formatCents(order.total)}</span>
        </div>
      </div>
    </div>
  );
}

// ── Status timeline ──────────────────────────────────────────

interface TimelineStep {
  label: string;
  /** Timestamp shown under the label, when one is known for this step. */
  time?: string;
}

/**
 * The ladder for a healthy order. Only placed / picked-up / delivered have
 * dedicated timestamp columns; the step matching the CURRENT status gets
 * updated_at (the last transition time) — intermediate steps that have
 * already passed show no time rather than a made-up one.
 */
function buildSteps(order: SellerOrder): { steps: TimelineStep[]; reached: number } {
  const pickup = isPickup(order);
  const steps: TimelineStep[] = [
    { label: "Placed", time: order.created_at },
    { label: "Accepted" },
    { label: "Preparing" },
    { label: pickup ? "Ready for pickup" : "Ready" },
    ...(pickup
      ? [{ label: "Picked up by customer" }]
      : [{ label: "Out for delivery" }, { label: "Delivered" }]),
  ];

  const rank: Partial<Record<OrderStatus, number>> = {
    scheduled: 0,
    pending: 0,
    accepted: 1,
    preparing: 2,
    ready: 3,
    picked_up: 4,
    delivered: pickup ? 4 : 5,
    completed: pickup ? 4 : 5,
  };
  const reached = rank[order.status] ?? 0;

  if (!pickup) {
    if (isRealDate(order.picked_up_at)) steps[4].time = order.picked_up_at;
    if (isRealDate(order.delivered_at)) steps[5].time = order.delivered_at;
  }
  // The current step's transition time is updated_at, unless a dedicated
  // column already filled it in.
  if (reached > 0 && !steps[reached].time && isRealDate(order.updated_at)) {
    steps[reached].time = order.updated_at;
  }

  return { steps, reached };
}

function TimelineCard({ order }: { order: SellerOrder }) {
  const dead = order.status === "cancelled" || order.status === "rejected";
  const { steps, reached } = buildSteps(order);
  const terminal = reached === steps.length - 1;

  // A cancelled/rejected order shows Placed plus a red terminal node instead
  // of pretending it marched down the ladder.
  const rows: { label: string; time?: string; state: "done" | "current" | "todo" | "dead" }[] =
    dead
      ? [
          { label: "Placed", time: order.created_at, state: "done" },
          {
            label:
              order.status === "rejected" ? "Rejected — customer refunded" : "Cancelled",
            time: isRealDate(order.updated_at) ? order.updated_at : undefined,
            state: "dead",
          },
        ]
      : steps.map((step, i) => ({
          label: step.label,
          time: step.time,
          state: i < reached ? "done" : i === reached ? (terminal ? "done" : "current") : "todo",
        }));

  return (
    <div className="card p-5">
      <SectionHeader icon={<Clock className="w-4 h-4" aria-hidden="true" />} title="Status timeline" />
      <ol className="space-y-0">
        {rows.map((row, i) => (
          <li key={row.label} className="flex gap-3">
            {/* Dot + connector */}
            <div className="flex flex-col items-center">
              <span
                className={`w-5 h-5 rounded-full flex items-center justify-center shrink-0 ${
                  row.state === "done"
                    ? "bg-green-500/20 text-green-400"
                    : row.state === "current"
                      ? "bg-brand-500/25 text-brand-400 ring-2 ring-brand-500/40"
                      : row.state === "dead"
                        ? "bg-red-500/20 text-red-400"
                        : "bg-dark-800 text-dark-600"
                }`}
              >
                {row.state === "done" ? (
                  <Check className="w-3 h-3" aria-hidden="true" />
                ) : row.state === "dead" ? (
                  <X className="w-3 h-3" aria-hidden="true" />
                ) : (
                  <span className="w-1.5 h-1.5 rounded-full bg-current" aria-hidden="true" />
                )}
              </span>
              {i < rows.length - 1 && (
                <span
                  className={`w-px flex-1 min-h-[1.25rem] ${
                    row.state === "done" ? "bg-green-500/30" : "bg-dark-800"
                  }`}
                  aria-hidden="true"
                />
              )}
            </div>
            {/* Label + time */}
            <div className={i === rows.length - 1 ? "" : "pb-4"}>
              <div
                className={`text-sm font-medium leading-5 ${
                  row.state === "todo"
                    ? "text-dark-500"
                    : row.state === "dead"
                      ? "text-red-400"
                      : row.state === "current"
                        ? "text-brand-400"
                        : ""
                }`}
              >
                {row.label}
              </div>
              {row.time && (
                <div className="text-xs text-dark-500 mt-0.5">{formatWhen(row.time)}</div>
              )}
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

// ── Shared bits ──────────────────────────────────────────────

function SectionHeader({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div className="flex items-center gap-2 mb-4">
      <span className="text-brand-500">{icon}</span>
      <h2 className="font-semibold">{title}</h2>
    </div>
  );
}
