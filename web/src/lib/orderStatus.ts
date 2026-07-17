// The single source of truth for order-status presentation (label + badge
// color) across the consumer orders list, the tracking page, and the seller
// portal — one shared map so status colors can never drift per page.
//
// Status set mirrors backend/internal/models/models.go: the ten order
// statuses, including "completed" — the terminal status for pickup orders.

import type { OrderStatus } from "@/types";

/** All order statuses ("completed" is now part of the OrderStatus union). */
export type OrderStatusKey = OrderStatus;

export interface OrderStatusMeta {
  /** Consumer-facing label ("Pending", "On the way", …). */
  label: string;
  /** Kitchen-facing label for the seller portal ("New order", …). */
  sellerLabel: string;
  /** Badge/pill classes — translucent background + readable text color. */
  pill: string;
}

export const ORDER_STATUS_META: Record<OrderStatusKey, OrderStatusMeta> = {
  scheduled: {
    label: "Scheduled",
    sellerLabel: "Scheduled",
    pill: "bg-sky-500/15 text-sky-300",
  },
  pending: {
    label: "Pending",
    sellerLabel: "New order",
    pill: "bg-amber-500/15 text-amber-300",
  },
  accepted: {
    label: "Accepted",
    sellerLabel: "Accepted",
    pill: "bg-blue-500/15 text-blue-300",
  },
  preparing: {
    label: "Preparing",
    sellerLabel: "Preparing",
    pill: "bg-yellow-500/15 text-yellow-300",
  },
  ready: {
    label: "Ready",
    sellerLabel: "Ready",
    pill: "bg-brand-500/15 text-brand-300",
  },
  picked_up: {
    label: "On the way",
    sellerLabel: "Out for delivery",
    pill: "bg-purple-500/15 text-purple-300",
  },
  delivered: {
    label: "Delivered",
    sellerLabel: "Delivered",
    pill: "bg-green-500/15 text-green-300",
  },
  completed: {
    label: "Completed",
    sellerLabel: "Completed",
    pill: "bg-green-500/15 text-green-300",
  },
  cancelled: {
    label: "Cancelled",
    sellerLabel: "Cancelled",
    pill: "bg-red-500/15 text-red-300",
  },
  rejected: {
    label: "Rejected",
    sellerLabel: "Rejected",
    pill: "bg-red-500/15 text-red-300",
  },
};

/** Statuses an order can end in — everything else counts as active. */
export const TERMINAL_ORDER_STATUSES: readonly OrderStatus[] = [
  "delivered",
  "completed",
  "cancelled",
  "rejected",
];

/**
 * Statuses the consumer may still cancel from — mirrors backend CancelOrder
 * (scheduled/pending/accepted; never once the kitchen starts preparing).
 * Callers must ALSO block cancel when an external provider owns the delivery
 * (order.external_delivery_id), which is per-order state, not status.
 */
export const CANCELLABLE_ORDER_STATUSES: readonly OrderStatus[] = [
  "scheduled",
  "pending",
  "accepted",
];
