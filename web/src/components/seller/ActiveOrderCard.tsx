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
  OctagonX,
  ShoppingBag,
  X,
} from "lucide-react";
import { formatCents } from "@/lib/sellerApi";
import type { OrderStatus, SellerOrder } from "@/types/seller";

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

  const items = order.items ?? [];
  const itemCount = items.reduce((sum, item) => sum + item.quantity, 0);
  const placedAt = new Date(order.created_at);

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
          className={`shrink-0 text-xs px-2.5 py-1 rounded-lg font-semibold ${STATUS_PILL[order.status] ?? "bg-dark-700 text-dark-300"}`}
        >
          {STATUS_LABEL[order.status] ?? order.status}
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
        confirmingReject={confirmingReject}
        setConfirmingReject={setConfirmingReject}
        onAction={onAction}
      />
    </div>
  );
}

// ── Quick actions ────────────────────────────────────────────

function QuickActions({
  order,
  acting,
  confirmingReject,
  setConfirmingReject,
  onAction,
}: {
  order: SellerOrder;
  acting: boolean;
  confirmingReject: boolean;
  setConfirmingReject: (v: boolean) => void;
  onAction: (order: SellerOrder, action: OrderQuickAction) => void;
}) {
  const btn =
    "flex-1 flex items-center justify-center gap-2 py-2.5 px-3 rounded-xl text-sm font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed";

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
              <OctagonX className="w-4 h-4" aria-hidden="true" />
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
            <Check className="w-4 h-4" aria-hidden="true" />
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
            <Flame className="w-4 h-4" aria-hidden="true" />
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
            <ChefHat className="w-4 h-4" aria-hidden="true" />
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
              <Check className="w-4 h-4" aria-hidden="true" />
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
              <Bike className="w-4 h-4" aria-hidden="true" />
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
              <Check className="w-4 h-4" aria-hidden="true" />
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
