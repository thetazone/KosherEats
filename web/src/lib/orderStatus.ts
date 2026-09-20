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

// Pills use the rubric's semantic aliases (danger/warning/success/info/
// transit) rather than raw Tailwind hues — same pixels, greppable intent.
// `pending` uses warning-*: main's 37569c45 already retokenized the raw amber
// classes on the admin restaurants approval pill onto warning-*, so that
// mapping is established. Note warning-* aliases Tailwind yellow, not amber —
// a deliberate (small) hue shift onto an existing token rather than a new one.
// See docs/archive/RUBRIC_SWEEP_REPORT_web-ordering.md.
// `scheduled` sits on the neutral dark-* ramp: it's a dormant, informational
// state (nothing for the kitchen to do until it flips to `pending`), so it
// gets the same quiet treatment as StatusHint on seller/orders/[id] rather
// than a fifth competing hue — info-* stays reserved for `accepted`.
export const ORDER_STATUS_META: Record<OrderStatusKey, OrderStatusMeta> = {
  scheduled: {
    label: "Scheduled",
    sellerLabel: "Scheduled",
    pill: "bg-dark-800 text-dark-300",
  },
  pending: {
    label: "Pending",
    sellerLabel: "New order",
    pill: "bg-warning-500/15 text-warning-300",
  },
  accepted: {
    label: "Accepted",
    sellerLabel: "Accepted",
    pill: "bg-info-500/15 text-info-300",
  },
  preparing: {
    label: "Preparing",
    sellerLabel: "Preparing",
    pill: "bg-warning-500/15 text-warning-300",
  },
  ready: {
    label: "Ready",
    sellerLabel: "Ready",
    pill: "bg-brand-500/15 text-brand-300",
  },
  picked_up: {
    label: "On the way",
    sellerLabel: "Out for delivery",
    pill: "bg-transit-500/15 text-transit-300",
  },
  delivered: {
    label: "Delivered",
    sellerLabel: "Delivered",
    pill: "bg-success-500/15 text-success-300",
  },
  completed: {
    label: "Completed",
    sellerLabel: "Completed",
    pill: "bg-success-500/15 text-success-300",
  },
  cancelled: {
    label: "Cancelled",
    sellerLabel: "Cancelled",
    pill: "bg-danger-500/15 text-danger-300",
  },
  rejected: {
    label: "Rejected",
    sellerLabel: "Rejected",
    pill: "bg-danger-500/15 text-danger-300",
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
