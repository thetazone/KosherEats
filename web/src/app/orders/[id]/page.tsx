"use client";

import { Header } from "@/components/layout/Header";
import { CourierRatingModal } from "@/components/orders/CourierRatingModal";
import { OrderChat } from "@/components/orders/OrderChat";
import { orders as ordersApi } from "@/lib/api";
import { formatUSD } from "@/lib/format";
import {
  CANCELLABLE_ORDER_STATUSES,
  ORDER_STATUS_META,
  TERMINAL_ORDER_STATUSES,
} from "@/lib/orderStatus";
import type { Order, OrderStatus } from "@/types";
import type { LucideIcon } from "lucide-react";
import {
  AlertTriangle,
  ArrowLeft,
  Ban,
  Bike,
  CalendarClock,
  Check,
  ChefHat,
  Clock,
  ExternalLink,
  Home,
  Loader2,
  MapPin,
  Package,
  Phone,
  ShoppingBag,
  Star,
  Store,
} from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

const POLL_INTERVAL_MS = 15_000;

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" });
}

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

// Great-circle distance in km (same haversine as /search).
function distanceKm(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
}

function isPickup(order: Order): boolean {
  return order.fulfillment_type === "pickup";
}

function isExternalDelivery(order: Order): boolean {
  return Boolean(order.external_delivery_id || order.external_provider);
}

function isSelfDelivery(order: Order): boolean {
  // Restaurant self-delivery — only meaningful while no platform courier or
  // external provider has actually taken the order over (escalation path).
  return order.delivery_mode === "restaurant" && !order.courier && !isExternalDelivery(order);
}

// Human-friendly name for an external delivery provider key (mirrors iOS).
function providerName(provider: string | null | undefined): string {
  switch (provider) {
    case "uber_direct":
      return "Uber";
    case "doordash_drive":
      return "DoorDash";
    default:
      return "our delivery partner";
  }
}

// Returns the ETA only when it's a plausible time — guards against
// epoch-zero / stale garbage values (mirrors iOS sanitizedETA).
function sanitizedETA(iso: string): Date | null {
  const eta = new Date(iso);
  if (Number.isNaN(eta.getTime())) return null;
  if (eta.getTime() <= 1000) return null;
  if (eta.getTime() <= Date.now() - 24 * 3600 * 1000) return null;
  return eta;
}

// Status headline / subtext — ported from iOS OrderTrackingView so the two
// clients narrate the order identically.
function statusHeadline(order: Order): string {
  const status = order.status;
  if (isExternalDelivery(order)) {
    const provider = providerName(order.external_provider);
    if (status === "ready") return `Handing off to ${provider}`;
    if (status === "picked_up") return `On its way with ${provider}`;
  } else if (isPickup(order)) {
    if (status === "ready") return "Ready for pickup";
    if (status === "picked_up") return "Picked up";
  }
  switch (status) {
    case "scheduled":
      return "Scheduled for later";
    case "pending":
      return "Waiting for the restaurant";
    case "accepted":
      return "Restaurant accepted your order";
    case "preparing":
      return "Your food is being prepared";
    case "ready":
      return "Waiting for a courier";
    case "picked_up":
      return "Your order is on the way";
    case "delivered":
      return "Delivered — enjoy!";
    case "cancelled":
      return "Order was cancelled";
    case "rejected":
      return "Order was rejected";
  }
}

function statusSubtext(order: Order): string {
  const status = order.status;
  if (isExternalDelivery(order)) {
    const provider = providerName(order.external_provider);
    if (status === "ready")
      return `Your order is ready and a courier from ${provider} is on the way to pick it up.`;
    if (status === "picked_up") return `Your order is on its way with ${provider}.`;
  } else if (isPickup(order)) {
    if (status === "ready") return "Your order is ready to collect at the restaurant.";
    if (status === "picked_up") return "You've collected your order.";
    if (status === "preparing") return "Your meal is being cooked and packed for pickup.";
  }
  switch (status) {
    case "scheduled":
      return "Your order is queued and will move into the kitchen closer to the scheduled time.";
    case "pending":
      return "The restaurant is reviewing the order and will confirm it shortly.";
    case "accepted":
      return "The kitchen has the order and will begin preparing it.";
    case "preparing":
      return "Your meal is being cooked and packed.";
    case "ready":
      return "The order is ready and we're matching it with a courier.";
    case "picked_up":
      return "Your courier has the order and is heading to your delivery address.";
    case "delivered":
      return "The dropoff is complete.";
    case "cancelled":
      return "The order will not be fulfilled. If you were charged, your payment will be refunded.";
    case "rejected":
      return "The restaurant could not accept this order.";
  }
}

// --- Timeline -----------------------------------------------------------
// One step per OrderStatus. The stepper itself renders the progression
// statuses (scheduled → pending → accepted → preparing → ready → picked_up →
// delivered); the two terminal failure statuses (cancelled / rejected) render
// as a red banner instead of a step — together the timeline accounts for all
// 8 non-overlapping states an order can land in after placement.

interface TimelineStep {
  key: OrderStatus;
  label: string;
  icon: LucideIcon;
  at?: string | null;
}

function buildTimeline(order: Order): { steps: TimelineStep[]; activeIndex: number } {
  const pickup = isPickup(order);
  // Only orders placed as scheduled show the Scheduled step; ASAP orders
  // start the story at "Order placed".
  const includeScheduled = order.status === "scheduled" || Boolean(order.scheduled_for);

  const steps: TimelineStep[] = [
    ...(includeScheduled
      ? [{ key: "scheduled" as const, label: "Scheduled", icon: CalendarClock, at: order.scheduled_for }]
      : []),
    { key: "pending", label: "Order placed", icon: ShoppingBag, at: order.created_at },
    { key: "accepted", label: "Accepted", icon: Check },
    { key: "preparing", label: "Preparing", icon: ChefHat },
    { key: "ready", label: pickup ? "Ready for pickup" : "Ready", icon: Package },
    ...(pickup
      ? [{ key: "picked_up" as const, label: "Picked up", icon: ShoppingBag, at: order.picked_up_at }]
      : [
          { key: "picked_up" as const, label: "On the way", icon: Bike, at: order.picked_up_at },
          { key: "delivered" as const, label: "Delivered", icon: Home, at: order.delivered_at },
        ]),
  ];

  let activeIndex: number;
  if (order.status === "cancelled" || order.status === "rejected") {
    activeIndex = -1; // stepper hidden — terminal banner takes over
  } else if (pickup && order.status === "delivered") {
    // A pickup order can be closed out as delivered; its last step is "Picked up".
    activeIndex = steps.length - 1;
  } else {
    activeIndex = steps.findIndex((s) => s.key === order.status);
    if (activeIndex === -1) activeIndex = 0;
  }
  return { steps, activeIndex };
}

function Timeline({ order }: { order: Order }) {
  const { steps, activeIndex } = buildTimeline(order);
  if (activeIndex < 0) return null;

  return (
    <ol aria-label="Order progress">
      {steps.map((step, i) => {
        const done = i < activeIndex;
        const active = i === activeIndex;
        const last = i === steps.length - 1;
        const Icon = step.icon;
        return (
          <li key={step.key} className="flex gap-4">
            <div className="flex flex-col items-center">
              <div
                className={`w-9 h-9 rounded-full flex items-center justify-center flex-shrink-0 border transition-colors ${
                  done
                    ? "bg-brand-500 border-brand-500 text-white"
                    : active
                      ? "bg-brand-500 border-brand-500 text-white animate-pulse"
                      : "bg-dark-800 border-dark-700 text-dark-500"
                }`}
              >
                {done ? <Check className="w-4 h-4" /> : <Icon className="w-4 h-4" />}
              </div>
              {!last && (
                <div className={`w-0.5 flex-1 min-h-[20px] ${done ? "bg-brand-500" : "bg-dark-700"}`} />
              )}
            </div>
            <div className={last ? "pb-1" : "pb-6"}>
              <p className={`font-semibold leading-9 ${done || active ? "text-white" : "text-dark-500"}`}>
                {step.label}
                {step.at && (done || active) && (
                  <span className="ml-2 text-xs font-normal text-dark-500">
                    {step.key === "scheduled" ? formatDateTime(step.at) : formatTime(step.at)}
                  </span>
                )}
              </p>
            </div>
          </li>
        );
      })}
    </ol>
  );
}

// --- Page ----------------------------------------------------------------

interface LiveLocation {
  lat: number;
  lng: number;
  receivedAt: number; // Date.now() when the event arrived
}

export default function OrderTrackingPage() {
  const params = useParams<{ id: string }>();
  const orderId = params?.id ?? "";
  const router = useRouter();

  const [token, setToken] = useState<string | null>(null);
  const [order, setOrder] = useState<Order | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  // Background poll failure: keep showing the last known order, banner on top.
  const [stale, setStale] = useState(false);

  const [liveLocation, setLiveLocation] = useState<LiveLocation | null>(null);
  // Re-render tick so "updated Xs ago" stays honest while the stream is live.
  const [, setAgoTick] = useState(0);

  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [showRating, setShowRating] = useState(false);

  const handleUnauthorized = useCallback(() => {
    window.localStorage.removeItem("token");
    router.replace("/auth");
  }, [router]);

  const loadOrder = useCallback(
    async (t: string, opts: { background?: boolean } = {}) => {
      if (!opts.background) {
        setLoading(true);
        setLoadError(null);
      }
      try {
        const fetched = await ordersApi.get(t, orderId);
        setOrder(fetched);
        setStale(false);
        setLoadError(null);
      } catch (err) {
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        if (opts.background) {
          setStale(true); // keep last known state on screen
        } else {
          setLoadError(err instanceof Error ? err.message : "Failed to load order");
        }
      } finally {
        if (!opts.background) setLoading(false);
      }
    },
    [orderId, handleUnauthorized]
  );

  // Mount: hydrate auth and do the initial load.
  useEffect(() => {
    const t = window.localStorage.getItem("token");
    if (!t) {
      router.replace("/auth");
      return;
    }
    setToken(t);
    if (orderId) void loadOrder(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId]);

  const isActive = order != null && !TERMINAL_ORDER_STATUSES.includes(order.status);

  // Visibility-aware 15s polling while the order is active: pause when the
  // tab is hidden, refresh immediately + resume on return.
  useEffect(() => {
    if (!token || !orderId || !isActive) return;

    let interval: number | null = null;
    const start = () => {
      if (interval == null) {
        interval = window.setInterval(() => void loadOrder(token, { background: true }), POLL_INTERVAL_MS);
      }
    };
    const stop = () => {
      if (interval != null) {
        window.clearInterval(interval);
        interval = null;
      }
    };
    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        void loadOrder(token, { background: true });
        start();
      } else {
        stop();
      }
    };

    if (document.visibilityState === "visible") start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [token, orderId, isActive, loadOrder]);

  // Live courier-location SSE stream. Platform deliveries only — pickup
  // orders have no courier and external (Uber/DoorDash) orders are tracked in
  // the provider's app (mirrors iOS). Reconnects with capped exponential
  // backoff on failure, short fixed delay on a clean server close.
  const shouldStream = Boolean(token && order && isActive && !isPickup(order) && !isExternalDelivery(order));
  useEffect(() => {
    if (!shouldStream || !token || !orderId) return;

    const controller = new AbortController();
    let stopped = false;
    let failures = 0;
    const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

    void (async () => {
      while (!stopped) {
        try {
          await ordersApi.streamLocation(
            token,
            orderId,
            (event) => {
              failures = 0;
              // Same plausibility guard as iOS: drop garbage coordinates.
              if (
                event.lat < -90 ||
                event.lat > 90 ||
                event.lng < -180 ||
                event.lng > 180 ||
                (event.lat === 0 && event.lng === 0)
              ) {
                return;
              }
              setLiveLocation({ lat: event.lat, lng: event.lng, receivedAt: Date.now() });
            },
            controller.signal
          );
          if (stopped || controller.signal.aborted) return;
          // Clean server close (LB recycle) on a still-active order — not a
          // failure; reconnect after a short fixed delay.
          failures = 0;
          await sleep(1000);
        } catch {
          if (stopped || controller.signal.aborted) return;
          failures += 1;
          await sleep(Math.min(3000 * 2 ** (failures - 1), 15000));
        }
      }
    })();

    return () => {
      stopped = true;
      controller.abort();
    };
  }, [shouldStream, token, orderId]);

  // Keep the "updated Xs ago" label ticking while we have a live position.
  useEffect(() => {
    if (!liveLocation) return;
    const tick = window.setInterval(() => setAgoTick((n) => n + 1), 5000);
    return () => window.clearInterval(tick);
  }, [liveLocation]);

  async function cancelOrder() {
    if (!token || !order) return;
    setCancelling(true);
    setActionError(null);
    try {
      const updated = await ordersApi.cancel(token, order.id);
      setOrder(updated);
      setConfirmingCancel(false);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setActionError(err instanceof Error ? err.message : "Failed to cancel order");
    } finally {
      setCancelling(false);
    }
  }

  // --- Render states (skeleton / error / content triad) ---

  if (loading) {
    return (
      <>
        <Header />
        <main className="flex-1 max-w-3xl mx-auto px-4 py-8 w-full">
          <div className="card p-6 animate-pulse space-y-4">
            <div className="h-6 w-1/2 bg-dark-800 rounded" />
            <div className="h-4 w-2/3 bg-dark-800 rounded" />
            <div className="h-4 w-1/3 bg-dark-800 rounded" />
          </div>
          <div className="card p-6 mt-4 animate-pulse space-y-4">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex items-center gap-4">
                <div className="w-9 h-9 bg-dark-800 rounded-full flex-shrink-0" />
                <div className="h-4 w-1/3 bg-dark-800 rounded" />
              </div>
            ))}
          </div>
          <div className="card p-6 mt-4 animate-pulse space-y-3">
            <div className="h-4 w-2/3 bg-dark-800 rounded" />
            <div className="h-4 w-1/2 bg-dark-800 rounded" />
          </div>
        </main>
      </>
    );
  }

  if (loadError || !order) {
    return (
      <>
        <Header />
        <main className="flex-1 max-w-3xl mx-auto px-4 py-8 w-full">
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load order</h2>
            <p className="text-dark-400 mb-6">{loadError ?? "Order not found."}</p>
            <div className="flex gap-3 justify-center">
              <button onClick={() => token && loadOrder(token)} className="btn-primary">
                Retry
              </button>
              <Link href="/orders" className="btn-secondary inline-block">
                Back to Orders
              </Link>
            </div>
          </div>
        </main>
      </>
    );
  }

  const badge = ORDER_STATUS_META[order.status];
  const pickup = isPickup(order);
  const external = isExternalDelivery(order);
  const failed = order.status === "cancelled" || order.status === "rejected";
  const canCancel =
    CANCELLABLE_ORDER_STATUSES.includes(order.status) && !order.external_delivery_id;
  const eta = isActive && order.status !== "pending" ? sanitizedETA(order.est_delivery_time) : null;

  // Live courier position: prefer the SSE stream, fall back to the courier's
  // last snapshot from the order payload.
  const courier = order.courier ?? null;
  const courierPos =
    liveLocation ??
    (courier && (courier.lat !== 0 || courier.lng !== 0)
      ? { lat: courier.lat, lng: courier.lng, receivedAt: null }
      : null);
  const dropoff =
    order.delivery_lat != null &&
    order.delivery_lng != null &&
    (order.delivery_lat !== 0 || order.delivery_lng !== 0)
      ? { lat: order.delivery_lat, lng: order.delivery_lng }
      : null;
  const courierDistanceKm = courierPos && dropoff ? distanceKm(courierPos, dropoff) : null;
  const updatedAgoSec =
    liveLocation != null ? Math.max(0, Math.round((Date.now() - liveLocation.receivedAt) / 1000)) : null;

  const trackingUrl =
    external && order.external_tracking_url && order.external_tracking_url !== ""
      ? order.external_tracking_url
      : null;

  // The chat input is fixed to the viewport bottom below md: (see OrderChat),
  // so reserve space under the page content for it on mobile.
  const showChat = !failed && Boolean(token);

  return (
    <>
      <Header />
      <main
        className={`flex-1 max-w-3xl mx-auto px-4 pt-8 w-full ${
          showChat ? "pb-32 md:pb-8" : "pb-8"
        }`}
      >
        <Link
          href="/orders"
          className="inline-flex items-center min-h-[44px] gap-1.5 text-sm text-dark-400 hover:text-white transition-colors mb-2"
        >
          <ArrowLeft className="w-4 h-4" />
          Back to Orders
        </Link>

        {/* Stale-data banner: background refresh failing, showing last known state */}
        {stale && (
          <div className="card p-3 mb-4 border border-yellow-800 bg-yellow-900/20 text-yellow-300 text-sm flex items-center gap-2">
            <AlertTriangle className="w-4 h-4 flex-shrink-0" />
            Unable to update — showing last known status
          </div>
        )}

        {/* Status header */}
        <div className="card p-6 mb-4">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h1 className="text-2xl font-extrabold">{statusHeadline(order)}</h1>
              <p className="text-dark-400 mt-1">{statusSubtext(order)}</p>
              <p className="text-dark-500 text-sm mt-2">
                {order.restaurant_name} · {formatDateTime(order.created_at)}
              </p>
            </div>
            <span
              className={`${badge.pill} text-sm font-medium px-3 py-1 rounded-full whitespace-nowrap`}
            >
              {badge.label}
            </span>
          </div>
          {eta && (
            <p className="mt-3 inline-flex items-center gap-1.5 text-brand-400 font-semibold text-sm">
              <Clock className="w-4 h-4" />
              Estimated {pickup ? "pickup" : "delivery"}: {formatTime(eta.toISOString())}
            </p>
          )}
        </div>

        {/* Terminal failure banner (cancelled / rejected) replaces the stepper */}
        {failed ? (
          <div className="card p-6 mb-4 border border-red-800 bg-red-900/20">
            <div className="flex items-center gap-3">
              <Ban className="w-6 h-6 text-red-400 flex-shrink-0" />
              <div>
                <h2 className="font-bold text-red-300">
                  {order.status === "cancelled" ? "Order cancelled" : "Order rejected"}
                </h2>
                <p className="text-red-300/80 text-sm">{statusSubtext(order)}</p>
              </div>
            </div>
          </div>
        ) : (
          <div className="card p-6 mb-4">
            <Timeline order={order} />
          </div>
        )}

        {/* External-provider delivery card */}
        {external && !failed && (
          <div className="card p-5 mb-4">
            <div className="flex items-start gap-4">
              <div className="w-12 h-12 rounded-full bg-dark-800 flex items-center justify-center flex-shrink-0">
                <Package className="w-6 h-6 text-brand-400" />
              </div>
              <div className="flex-1">
                <h3 className="font-bold">
                  {order.status === "delivered"
                    ? `Delivered by ${providerName(order.external_provider)}`
                    : `Delivery by ${providerName(order.external_provider)}`}
                </h3>
                <p className="text-dark-400 text-sm mt-0.5">
                  {statusSubtext(order)}
                </p>
                {trackingUrl && (
                  <a
                    href={trackingUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="btn-primary inline-flex items-center justify-center gap-2 py-2 px-4 text-sm min-h-[44px] mt-3"
                  >
                    Track delivery
                    <ExternalLink className="w-4 h-4" />
                  </a>
                )}
              </div>
            </div>
          </div>
        )}

        {/* Self-delivery: the restaurant delivers with its own courier */}
        {isSelfDelivery(order) && !pickup && !failed && (
          <div className="card p-5 mb-4">
            <div className="flex items-start gap-4">
              <div className="w-12 h-12 rounded-full bg-dark-800 flex items-center justify-center flex-shrink-0">
                <Store className="w-6 h-6 text-brand-400" />
              </div>
              <div>
                <h3 className="font-bold">{order.restaurant_name} delivers this order</h3>
                <p className="text-dark-400 text-sm mt-0.5">
                  {order.status === "picked_up"
                    ? "The restaurant's own courier has your order and is on the way."
                    : "The restaurant delivers with their own courier, so live courier tracking isn't available for this order."}
                </p>
              </div>
            </div>
          </div>
        )}

        {/* Courier card (platform deliveries) */}
        {courier && !failed && (
          <div className="card p-5 mb-4">
            <div className="flex items-center gap-4">
              {courier.avatar_url ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={courier.avatar_url}
                  alt={courier.first_name}
                  className="w-[52px] h-[52px] rounded-full object-cover flex-shrink-0"
                />
              ) : (
                <div className="w-[52px] h-[52px] rounded-full bg-dark-800 flex items-center justify-center flex-shrink-0">
                  <span className="text-brand-400 text-lg font-bold">
                    {courier.first_name.slice(0, 1).toUpperCase()}
                  </span>
                </div>
              )}
              <div className="flex-1 min-w-0">
                <p className="font-bold">{courier.first_name}</p>
                <p className="text-sm text-dark-400 flex items-center gap-1">
                  <Star className="w-3.5 h-3.5 text-yellow-400 fill-yellow-400" />
                  {courier.rating.toFixed(1)}
                  {courier.total_deliveries > 0 && (
                    <span className="text-dark-500">· {courier.total_deliveries} deliveries</span>
                  )}
                </p>
                {(courier.vehicle_color || courier.vehicle_make || courier.vehicle_model || courier.license_plate) && (
                  <p className="text-xs text-dark-500 truncate">
                    {[courier.vehicle_color, courier.vehicle_make, courier.vehicle_model]
                      .filter(Boolean)
                      .join(" ")}
                    {courier.license_plate ? ` · ${courier.license_plate}` : ""}
                  </p>
                )}
              </div>
              {courier.phone && (
                <a
                  href={`tel:${courier.phone.replace(/[^0-9+]/g, "")}`}
                  className="w-11 h-11 rounded-full bg-dark-800 hover:bg-dark-700 flex items-center justify-center transition-colors flex-shrink-0"
                  aria-label={`Call ${courier.first_name}`}
                >
                  <Phone className="w-4 h-4 text-brand-400" />
                </a>
              )}
            </div>

            {/* Live position from the SSE stream */}
            {courierPos && order.status === "picked_up" && (
              <div className="mt-4 pt-4 border-t border-dark-800 flex items-center gap-2 text-sm">
                {liveLocation && (
                  <span className="relative flex h-2.5 w-2.5" aria-hidden>
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-60" />
                    <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-green-500" />
                  </span>
                )}
                <span className="text-dark-300">
                  {courierDistanceKm != null
                    ? courierDistanceKm < 0.15
                      ? `${courier.first_name} is arriving now`
                      : `${courier.first_name} is ${
                          courierDistanceKm < 1
                            ? `${Math.round(courierDistanceKm * 1000)} m`
                            : `${courierDistanceKm.toFixed(1)} km`
                        } away`
                    : `${courier.first_name} is on the move`}
                </span>
                {updatedAgoSec != null && (
                  <span className="text-dark-500 text-xs ml-auto">
                    {updatedAgoSec < 10 ? "updated just now" : `updated ${updatedAgoSec}s ago`}
                  </span>
                )}
              </div>
            )}
          </div>
        )}

        {/* Courier rating (delivered orders): prompt once, then show the
            submitted stars. courier_rating arrives on the order payload once
            a rating is on file (mirrors iOS OrderTrackingView). */}
        {order.status === "delivered" && order.courier && (
          <div className="card p-5 mb-4">
            {order.courier_rating == null ? (
              <div className="flex items-center justify-between gap-4">
                <div>
                  <h3 className="font-bold">How was {order.courier.first_name}?</h3>
                  <p className="text-dark-400 text-sm mt-0.5">
                    Rate your courier to help them keep doing great work.
                  </p>
                </div>
                <button
                  onClick={() => setShowRating(true)}
                  className="btn-primary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center whitespace-nowrap flex-shrink-0"
                >
                  Rate courier
                </button>
              </div>
            ) : (
              <div className="flex items-center justify-between gap-4">
                <p className="text-sm text-dark-300">You rated {order.courier.first_name}</p>
                <div className="flex gap-1" aria-label={`Your rating: ${order.courier_rating} of 5 stars`}>
                  {[1, 2, 3, 4, 5].map((i) => (
                    <Star
                      key={i}
                      className={`w-5 h-5 ${
                        i <= (order.courier_rating ?? 0)
                          ? "text-yellow-400 fill-yellow-400"
                          : "text-dark-600"
                      }`}
                      aria-hidden="true"
                    />
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Address card */}
        <div className="card p-5 mb-4">
          <div className="flex items-start gap-3">
            <MapPin className="w-5 h-5 text-brand-400 flex-shrink-0 mt-0.5" />
            <div>
              <p className="text-xs text-dark-500">{pickup ? "Pickup from" : "Delivering to"}</p>
              <p className="text-white">{pickup ? order.restaurant_name : order.delivery_address}</p>
              {pickup && order.delivery_address && (
                <p className="text-dark-400 text-sm">{order.delivery_address}</p>
              )}
            </div>
          </div>
        </div>

        {/* Order chat — shared thread with the restaurant and courier.
            Hidden on cancelled/rejected orders where there's nobody left to
            coordinate with. */}
        {!failed && token && (
          <OrderChat token={token} orderId={order.id} onUnauthorized={handleUnauthorized} />
        )}

        {/* Delivery proof photo */}
        {order.status === "delivered" && order.delivery_proof_url && (
          <div className="card p-5 mb-4">
            <p className="text-xs text-dark-500 mb-2">Delivery photo</p>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={order.delivery_proof_url}
              alt="Photo of your delivered order"
              className="rounded-xl max-h-72 w-full object-cover"
            />
          </div>
        )}

        {/* Receipt */}
        <div className="card p-5 mb-4">
          <h3 className="font-bold mb-3">Order summary</h3>
          <div className="space-y-2 text-sm">
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
            {(order.discount ?? 0) > 0 && (
              <div className="flex justify-between text-green-400">
                <span>Discount</span>
                <span>-{formatUSD(order.discount ?? 0)}</span>
              </div>
            )}
            {!pickup && (
              <div className="flex justify-between text-dark-400">
                <span>Delivery fee</span>
                <span>{formatUSD(order.delivery_fee)}</span>
              </div>
            )}
            <div className="flex justify-between text-dark-400">
              <span>Service fee</span>
              <span>{formatUSD(order.service_fee)}</span>
            </div>
            <div className="flex justify-between text-dark-400">
              <span>Tax</span>
              <span>{formatUSD(order.tax)}</span>
            </div>
            {(order.courier_tip ?? 0) > 0 && (
              <div className="flex justify-between text-dark-400">
                <span>Courier tip</span>
                <span>{formatUSD(order.courier_tip ?? 0)}</span>
              </div>
            )}
            <div className="flex justify-between font-bold text-base border-t border-dark-700 pt-2">
              <span>Total</span>
              <span className="text-brand-400">{formatUSD(order.total)}</span>
            </div>
          </div>
        </div>

        {actionError && (
          <div className="card p-3 mb-4 border border-red-800 bg-red-900/20 text-red-300 text-sm">
            {actionError}
          </div>
        )}

        {/* Cancel action */}
        {canCancel && (
          <div className="card p-5">
            {!confirmingCancel ? (
              <button
                onClick={() => setConfirmingCancel(true)}
                className="btn-secondary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center"
              >
                Cancel Order
              </button>
            ) : (
              <div>
                <p className="text-sm text-dark-300 mb-3">
                  Cancel this order? You&apos;ll receive a full refund of {formatUSD(order.total)}.
                </p>
                <div className="flex flex-wrap gap-3">
                  <button
                    onClick={() => void cancelOrder()}
                    disabled={cancelling}
                    className="bg-red-600 hover:bg-red-700 text-white font-semibold py-2 px-4 rounded-xl text-sm min-h-[44px] transition-colors inline-flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {cancelling && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
                    {cancelling ? "Cancelling…" : "Yes, cancel order"}
                  </button>
                  <button
                    onClick={() => setConfirmingCancel(false)}
                    disabled={cancelling}
                    className="btn-secondary py-2 px-4 text-sm min-h-[44px] inline-flex items-center justify-center disabled:opacity-50"
                  >
                    Keep order
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </main>

      {/* Courier rating modal */}
      {showRating && token && order.courier && (
        <CourierRatingModal
          token={token}
          orderId={order.id}
          courierFirstName={order.courier.first_name}
          onSubmitted={(stars) => {
            // Reflect the rating locally so the card flips to the submitted
            // state immediately; the next poll carries it from the server too.
            setOrder((prev) => (prev ? { ...prev, courier_rating: stars } : prev));
            setShowRating(false);
          }}
          onClose={() => setShowRating(false)}
          onUnauthorized={handleUnauthorized}
        />
      )}
    </>
  );
}
