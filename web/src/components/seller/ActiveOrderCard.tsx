"use client";

// Active-order card for the seller dashboard: items summary, status pill,
// live pending-countdown, and the status-appropriate quick action(s).
// Mirrors ios/seller Views/Dashboard/ActiveOrderCard.swift (card layout +
// countdown) and Views/Orders/SellerOrderDetailView.swift (action ladder).

import { useEffect, useState } from "react";
import {
  Bike,
  Check,
  ChefHat,
  Clock,
  Flame,
  Loader2,
  OctagonX,
  ShoppingBag,
  X,
} from "lucide-react";
import { ORDER_STATUS_META } from "@/lib/orderStatus";
import { formatCents } from "@/lib/sellerApi";
import type { SellerOrder } from "@/types/seller";

/** Quick actions the dashboard can fire. Maps 1:1 to sellerApi.orders.* */
export type OrderQuickAction =
  | "accept"
  | "reject"
  | "preparing"
  | "ready"
  | "complete" // pickup fulfillment: ready -> completed (customer collected)
  | "pickup" // self-delivery: ready -> picked_up (own driver left)
  | "deliver"; // self-delivery: picked_up -> delivered

/** Matches pendingOrderTTL in backend/internal/scheduler/dispatcher.go —
 *  after 10 minutes in 'pending' the backend auto-rejects and refunds. */
const PENDING_TTL_MS = 10 * 60 * 1000;
/** Countdown flips to red inside the last 2 minutes. Purely visual. */
const PENDING_URGENT_MS = 2 * 60 * 1000;

function isPickup(order: SellerOrder): boolean {
  return order.fulfillment_type === "pickup";
}

/** The seller's own driver runs this order (no platform courier, no
 *  Uber/DoorDash handoff — once escalated, external_delivery_id is set). */
function isSelfDelivery(order: SellerOrder): boolean {
  return order.delivery_mode === "restaurant" && !order.courier && !order.external_delivery_id;
}

/** Fulfillment-aware label for the preparing -> ready transition
 *  (readyButtonTitle in SellerOrderDetailView.swift). */
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

export function ActiveOrderCard({
  order,
  acting,
  onAction,
}: {
  order: SellerOrder;
  /** True while a quick action for THIS order is in flight — disables all buttons. */
  acting: boolean;
  onAction: (order: SellerOrder, action: OrderQuickAction) => void;
}) {
  // Reject is destructive (refunds the customer), so it takes a second click.
  const [confirmingReject, setConfirmingReject] = useState(false);

  // Which quick action was just fired — pairs with `acting` so only the
  // pressed button shows the in-flight spinner (the parent only tracks the
  // order id, not the action).
  const [pendingAction, setPendingAction] = useState<OrderQuickAction | null>(null);
  useEffect(() => {
    if (!acting) setPendingAction(null);
  }, [acting]);

  const items = order.items ?? [];
  const itemCount = items.reduce((sum, item) => sum + item.quantity, 0);
  const placedAt = new Date(order.created_at);
  const statusMeta = ORDER_STATUS_META[order.status];

  return (
    <div
      className={`card p-5 ${order.status === "pending" ? "border-amber-500/40" : ""}`}
    >
      {/* Header */}
      <div className="flex items-start justify-between gap-3 mb-3">
        <div className="min-w-0">
          <div className="font-semibold">Order #{order.id.slice(0, 8)}</div>
          <div className="text-xs text-dark-400 mt-0.5">
            {placedAt.toLocaleString([], {
              month: "short",
              day: "numeric",
              hour: "numeric",
              minute: "2-digit",
            })}
            {order.customer_name ? ` • ${order.customer_name}` : ""}
            {isPickup(order) ? " • Pickup" : " • Delivery"}
          </div>
        </div>
        <span
          className={`shrink-0 text-xs px-2.5 py-1 rounded-lg font-semibold ${statusMeta?.pill ?? "bg-dark-700 text-dark-300"}`}
        >
          {statusMeta?.sellerLabel ?? order.status}
        </span>
      </div>

      {/* Items summary */}
      <div className="border-t border-dark-800 pt-3 space-y-1">
        {items.slice(0, 3).map((item) => (
          <div key={item.id} className="flex items-baseline gap-2 text-sm">
            <span className="text-brand-500 font-bold text-xs shrink-0">{item.quantity}x</span>
            <span className="truncate">{item.name}</span>
          </div>
        ))}
        {items.length > 3 && (
          <div className="text-xs text-dark-500">+{items.length - 3} more items</div>
        )}
      </div>

      {/* Footer */}
      <div className="border-t border-dark-800 mt-3 pt-3 flex items-center justify-between gap-3">
        <span className="flex items-center gap-1.5 text-xs text-dark-400">
          <ShoppingBag className="w-3.5 h-3.5" aria-hidden="true" />
          {itemCount} {itemCount === 1 ? "item" : "items"}
        </span>
        <span className="font-bold text-brand-400">{formatCents(order.total)}</span>
      </div>

      {order.status === "pending" && (
        <PendingCountdown
          placedAt={new Date(order.updated_at || order.created_at).getTime()}
        />
      )}

      {order.status === "scheduled" && order.scheduled_for && (
        <div className="flex items-center gap-1.5 mt-3 text-xs text-sky-300">
          <Clock className="w-3.5 h-3.5" aria-hidden="true" />
          Scheduled for{" "}
          {new Date(order.scheduled_for).toLocaleString([], {
            month: "short",
            day: "numeric",
            hour: "numeric",
            minute: "2-digit",
          })}
        </div>
      )}

      <QuickActions
        order={order}
        acting={acting}
        pendingAction={pendingAction}
        confirmingReject={confirmingReject}
        setConfirmingReject={setConfirmingReject}
        onAction={(o, action) => {
          setPendingAction(action);
          onAction(o, action);
        }}
      />
    </div>
  );
}

// ── Quick actions ────────────────────────────────────────────

function QuickActions({
  order,
  acting,
  pendingAction,
  confirmingReject,
  setConfirmingReject,
  onAction,
}: {
  order: SellerOrder;
  acting: boolean;
  /** The action in flight (when `acting`) — that button shows the spinner. */
  pendingAction: OrderQuickAction | null;
  confirmingReject: boolean;
  setConfirmingReject: (v: boolean) => void;
  onAction: (order: SellerOrder, action: OrderQuickAction) => void;
}) {
  // min-h-[44px] keeps every status action a full-size touch target at 375px.
  const btn =
    "flex-1 flex items-center justify-center gap-2 py-2.5 px-3 min-h-[44px] rounded-xl text-sm font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed";

  /** The pressed button's icon swaps to a spinner while its PATCH runs. */
  const icon = (action: OrderQuickAction, idle: React.ReactNode) =>
    acting && pendingAction === action ? (
      <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
    ) : (
      idle
    );

  switch (order.status) {
    case "pending":
      if (confirmingReject) {
        return (
          <div className="flex gap-2 mt-4">
            <button
              onClick={() => onAction(order, "reject")}
              disabled={acting}
              className={`${btn} bg-red-500/15 text-red-400 hover:bg-red-500/25`}
            >
              {icon("reject", <OctagonX className="w-4 h-4" aria-hidden="true" />)}
              Confirm reject &amp; refund
            </button>
            <button
              onClick={() => setConfirmingReject(false)}
              disabled={acting}
              className={`${btn} bg-dark-800 text-dark-300 hover:bg-dark-700`}
            >
              Keep order
            </button>
          </div>
        );
      }
      return (
        <div className="flex gap-2 mt-4">
          <button
            onClick={() => onAction(order, "accept")}
            disabled={acting}
            className={`${btn} bg-green-500/15 text-green-400 hover:bg-green-500/25`}
          >
            {icon("accept", <Check className="w-4 h-4" aria-hidden="true" />)}
            Accept
          </button>
          <button
            onClick={() => setConfirmingReject(true)}
            disabled={acting}
            className={`${btn} bg-red-500/15 text-red-400 hover:bg-red-500/25`}
          >
            <X className="w-4 h-4" aria-hidden="true" />
            Reject
          </button>
        </div>
      );

    case "accepted":
      return (
        <div className="flex mt-4">
          <button
            onClick={() => onAction(order, "preparing")}
            disabled={acting}
            className={`${btn} bg-brand-500/15 text-brand-400 hover:bg-brand-500/25`}
          >
            {icon("preparing", <Flame className="w-4 h-4" aria-hidden="true" />)}
            Start preparing
          </button>
        </div>
      );

    case "preparing":
      return (
        <div className="flex mt-4">
          <button
            onClick={() => onAction(order, "ready")}
            disabled={acting}
            className={`${btn} bg-green-500/15 text-green-400 hover:bg-green-500/25`}
          >
            {icon("ready", <ChefHat className="w-4 h-4" aria-hidden="true" />)}
            {readyLabel(order)}
          </button>
        </div>
      );

    case "ready":
      // Pickup orders never get a courier — completing when the customer
      // arrives is the terminal step (backend CompleteOrder guard).
      if (isPickup(order)) {
        return (
          <div className="flex mt-4">
            <button
              onClick={() => onAction(order, "complete")}
              disabled={acting}
              className={`${btn} bg-green-500/15 text-green-400 hover:bg-green-500/25`}
            >
              {icon("complete", <Check className="w-4 h-4" aria-hidden="true" />)}
              Customer picked up
            </button>
          </div>
        );
      }
      if (isSelfDelivery(order)) {
        return (
          <div className="flex mt-4">
            <button
              onClick={() => onAction(order, "pickup")}
              disabled={acting}
              className={`${btn} bg-brand-500/15 text-brand-400 hover:bg-brand-500/25`}
            >
              {icon("pickup", <Bike className="w-4 h-4" aria-hidden="true" />)}
              Driver picked up
            </button>
          </div>
        );
      }
      // Courier / external provider owns the handoff from here.
      return (
        <StatusHint
          text={
            order.courier
              ? `${order.courier.first_name} is picking up`
              : order.external_delivery_id
                ? `Waiting for ${providerName(order)} pickup`
                : "Waiting for courier pickup"
          }
        />
      );

    case "picked_up":
      if (isSelfDelivery(order)) {
        return (
          <div className="flex mt-4">
            <button
              onClick={() => onAction(order, "deliver")}
              disabled={acting}
              className={`${btn} bg-green-500/15 text-green-400 hover:bg-green-500/25`}
            >
              {icon("deliver", <Check className="w-4 h-4" aria-hidden="true" />)}
              Mark delivered
            </button>
          </div>
        );
      }
      return (
        <StatusHint
          text={
            order.courier
              ? `${order.courier.first_name} is delivering`
              : order.external_delivery_id
                ? `${providerName(order)} is delivering`
                : "Out for delivery"
          }
        />
      );

    // Scheduled orders promote to pending automatically; nothing to act on.
    default:
      return null;
  }
}

function StatusHint({ text }: { text: string }) {
  return (
    <div className="flex items-center justify-center gap-2 mt-4 py-2.5 rounded-xl bg-dark-800/60 text-sm text-dark-400">
      <Bike className="w-4 h-4" aria-hidden="true" />
      {text}
    </div>
  );
}

// ── Pending countdown ────────────────────────────────────────

/**
 * Live countdown to the backend's auto-reject deadline. Keyed off updated_at
 * (the pending-transition timestamp) so a scheduled order promoted hours
 * after checkout counts from the promotion, matching the backend clock.
 */
function PendingCountdown({ placedAt }: { placedAt: number }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const elapsed = Math.max(0, now - placedAt);
  const remaining = Math.max(0, PENDING_TTL_MS - elapsed);
  const expired = remaining <= 0;
  const urgent = remaining <= PENDING_URGENT_MS;

  const fmt = (ms: number) => {
    const s = Math.floor(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  };

  return (
    <div
      className={`flex items-center gap-1.5 mt-3 text-xs font-semibold ${
        expired || urgent ? "text-red-400" : "text-amber-400"
      }`}
    >
      {expired ? (
        <OctagonX className="w-3.5 h-3.5" aria-hidden="true" />
      ) : (
        <Clock className="w-3.5 h-3.5" aria-hidden="true" />
      )}
      {expired
        ? "Auto-rejecting…"
        : `Respond in ${fmt(remaining)} • pending ${fmt(elapsed)}`}
    </div>
  );
}
