"use client";

// The checkout side of the cart page: fulfillment toggle, delivery address,
// courier tip, ASAP/scheduled delivery time, deals, the server-authoritative
// money breakdown, and the Stripe Payment Element modal. Extracted from
// app/cart/page.tsx (which retains cart loading/mutations and the post-charge
// pending-order recovery machinery).

import {
  clearPreselectedDeal,
  formatAddress,
  isDealError,
  isUnauthorized,
  isVerificationRequired,
  loadPreselectedDeal,
  parseCents,
  providerLabel,
  savePendingOrder,
  VERIFY_ROUTE,
  type PendingOrder,
} from "@/components/checkout/checkoutShared";
import {
  deals as dealsApi,
  deliveryQuote as deliveryQuoteApi,
  payments as paymentsApi,
  user as userApi,
} from "@/lib/api";
import { formatUSD, percentOfCents } from "@/lib/format";
import { AddressGeocodeField } from "@/components/ui/AddressGeocodeField";
import type { Address, Cart, Deal, DeliveryQuote } from "@/types";
import { Elements, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import { loadStripe, type Stripe } from "@stripe/stripe-js";
import { Loader2, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";

// Server-computed money bundle from POST /payments/intent. Every amount is
// integer cents and authoritative — the client never recomputes any of them
// (see backend/internal/handlers/payments.go CreatePaymentIntent).
interface PaymentIntentBundle {
  payment_intent_secret: string;
  publishable_key: string;
  subtotal: number;
  discount?: number;
  applied_deal_id?: string;
  delivery_fee: number;
  delivery_method?: string;
  service_fee: number;
  tax: number;
  tip: number;
  total: number;
}

type Fulfillment = "delivery" | "pickup";
type Timing = "asap" | "scheduled";

// Matches iOS CheckoutViewModel: preset percentages (of the cart subtotal),
// default 18%, custom capped at $500 and at the subtotal (backend rejects
// tip > subtotal with a 400).
const TIP_PRESETS = [
  { key: "none", label: "None", percent: 0 },
  { key: "15", label: "15%", percent: 15 },
  { key: "18", label: "18%", percent: 18 },
  { key: "20", label: "20%", percent: 20 },
  { key: "custom", label: "Custom", percent: 0 },
] as const;
type TipChoice = (typeof TIP_PRESETS)[number]["key"];
const MAX_TIP_CENTS = 50_000; // $500 — same flat cap as iOS

// Debounce for POST /payments/intent re-quotes. Each re-quote creates a new
// PaymentIntent server-side, so rapid tip typing / toggling must coalesce.
const REQUOTE_DEBOUNCE_MS = 450;

// Scheduling window — mirrors the iOS DeliveryTimeCard picker bounds
// (now+45min … now+7days). The backend flips any order scheduled >30 min out
// into 'scheduled' status; the dispatcher promotes it to 'pending' 30 minutes
// before the window so the kitchen has time to prepare.
const MIN_SCHEDULE_LEAD_MS = 45 * 60 * 1000;
const MAX_SCHEDULE_AHEAD_MS = 7 * 24 * 60 * 60 * 1000;

// Date → datetime-local input value in the user's local timezone.
function toLocalInputValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

// Consumer-facing one-liner for a deal's discount. The server computes the
// actual discount (intent.discount) — this is display copy only.
function dealSummary(d: Deal): string {
  switch (d.discount_type) {
    case "percentage":
      return `${d.discount_value}% off`;
    case "fixed":
      return `${formatUSD(d.discount_value)} off`;
    case "bogo":
      return "Buy one, get one free";
    default:
      return "Deal";
  }
}

export interface CheckoutPanelProps {
  token: string;
  /** Non-empty cart (the page only mounts the panel when items exist). */
  cart: Cart;
  /** Dead session detected mid-checkout — clear auth and route to /auth. */
  onUnauthorized: () => void;
  /**
   * Called AFTER the charge is captured and the PendingOrder persisted to
   * localStorage. The page owns submission/retries/navigation from here.
   */
  onPaymentCaptured: (pending: PendingOrder) => void;
}

export function CheckoutPanel({ token, cart, onUnauthorized, onPaymentCaptured }: CheckoutPanelProps) {
  const router = useRouter();

  const [addresses, setAddresses] = useState<Address[]>([]);
  const [addressesLoading, setAddressesLoading] = useState(true);
  const [selectedAddressId, setSelectedAddressId] = useState<string>("");

  // Fulfillment + tip drive the server re-quote. Pickup hides the address and
  // tip UI and prices without delivery fee or tip (the backend zeroes both
  // server-side too). The tip choice itself is preserved across a
  // delivery → pickup → delivery round-trip since we never mutate it here.
  const [fulfillment, setFulfillment] = useState<Fulfillment>("delivery");
  const [tipChoice, setTipChoice] = useState<TipChoice>("18");
  const [customTip, setCustomTip] = useState("");
  const [tipError, setTipError] = useState<string | null>(null);

  // ASAP vs scheduled. scheduledAt is the raw datetime-local value (local tz);
  // it is converted to RFC3339 only when the order is built. Scheduling does
  // NOT affect pricing, so it never triggers a re-quote. scheduleError is the
  // on-selection validation result (the input's min/max are advisory in most
  // browsers, so the 45-min/7-day bounds must be enforced in code).
  const [timing, setTiming] = useState<Timing>("asap");
  const [scheduledAt, setScheduledAt] = useState("");
  const [scheduleError, setScheduleError] = useState<string | null>(null);

  // Deals for the cart's restaurant (active + unexpired, server-filtered).
  // Applying one re-quotes the intent with applied_deal_id so the discount
  // preview is the server's, and the same id rides the order create.
  const [dealsList, setDealsList] = useState<Deal[]>([]);
  const [appliedDealId, setAppliedDealId] = useState<string | null>(null);
  const [dealError, setDealError] = useState<string | null>(null);

  // Standalone delivery-fee quote (POST /delivery-quote) for the selected
  // address — surfaces provider + ETA under the address picker.
  const [quote, setQuote] = useState<DeliveryQuote | null>(null);
  const [quoteLoading, setQuoteLoading] = useState(false);

  // The server-authoritative money bundle. previewPending is true from the
  // moment a re-quote is scheduled until its response lands, so "Place Order"
  // can never charge against a breakdown the user isn't currently seeing.
  const [intent, setIntent] = useState<PaymentIntentBundle | null>(null);
  const [previewPending, setPreviewPending] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  // The exact address the current intent was quoted against. CreateOrder
  // rejects any order whose delivery_address doesn't hash-match the PI stamp,
  // so the order MUST be created with this address, never a fresher selection.
  const [quotedAddress, setQuotedAddress] = useState<Address | null>(null);
  // The deal id the current intent was priced with (server echo). The order
  // must be created with THIS id — CreateOrder re-resolves the discount and
  // the totals must reconcile with the Stripe charge.
  const [quotedDealId, setQuotedDealId] = useState<string | null>(null);
  const [needsVerification, setNeedsVerification] = useState(false);

  const [stripePromise, setStripePromise] = useState<Promise<Stripe | null> | null>(null);
  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [checkoutError, setCheckoutError] = useState<string | null>(null);
  // True from the moment confirmPayment is submitted until the attempt
  // settles (reported up by CheckoutForm). While true the modal must be
  // un-dismissable — closing mid-confirmation could hide a charge that is
  // about to capture. Backdrop clicks and Escape never dismiss this modal by
  // design; this flag locks the remaining path, the X button.
  const [paymentBusy, setPaymentBusy] = useState(false);

  const [showAddressForm, setShowAddressForm] = useState(false);
  const [addrForm, setAddrForm] = useState({
    label: "Home",
    street: "",
    city: "",
    state: "",
    zip_code: "",
    lat: "",
    lng: "",
  });
  const [savingAddress, setSavingAddress] = useState(false);
  const [addressError, setAddressError] = useState<string | null>(null);

  // Generation counters discard stale async responses (an old re-quote landing
  // after a newer one must never clobber the newer breakdown).
  const intentGen = useRef(0);
  const quoteGen = useRef(0);
  // loadStripe must run exactly once — repeated calls spin up extra Stripe.js
  // instances.
  const stripeLoadedRef = useRef(false);

  // Saved addresses (default preselected). A load failure is non-fatal — the
  // user can still add an address inline or switch to pickup.
  useEffect(() => {
    let cancelled = false;
    userApi
      .listAddresses(token)
      .then((a) => {
        if (cancelled) return;
        const list = a as Address[];
        setAddresses(list);
        const preferred = list.find((x) => x.is_default) ?? list[0];
        if (preferred) setSelectedAddressId(preferred.id);
      })
      .catch((err) => {
        if (cancelled) return;
        if (isUnauthorized(err)) onUnauthorized();
      })
      .finally(() => {
        if (!cancelled) setAddressesLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  // Active deals for this restaurant. Non-fatal on failure — checkout works
  // without the deals rail.
  useEffect(() => {
    if (!cart.restaurant_id) {
      setDealsList([]);
      return;
    }
    let cancelled = false;
    dealsApi
      .forRestaurant(cart.restaurant_id, token)
      .then((ds) => {
        if (cancelled) return;
        setDealsList(ds);
        // One-shot handoff from the Deals-page tap-through (persisted by the
        // restaurant page): auto-apply the preselected deal when it's still
        // live for this cart's restaurant AND currently eligible. An
        // ineligible deal (below min order / BOGO with <2 units) stays
        // unapplied — the rail still shows it with the blocking hint instead
        // of surfacing a raw server rejection. A preselection for a different
        // restaurant is left in storage for its own checkout.
        const pre = loadPreselectedDeal();
        if (!pre || pre.restaurant_id !== cart.restaurant_id) return;
        clearPreselectedDeal();
        const match = ds.find((d) => d.id === pre.deal_id);
        if (!match) return;
        const units = cart.items.reduce((sum, i) => sum + i.quantity, 0);
        const eligible =
          (match.min_order_amount ?? 0) <= cart.subtotal &&
          !(match.discount_type === "bogo" && units < 2);
        if (eligible) {
          setAppliedDealId((prev) => prev ?? match.id);
        }
      })
      .catch(() => {
        if (!cancelled) setDealsList([]);
      });
    return () => {
      cancelled = true;
    };
    // cart.items/subtotal are only read for the one-shot eligibility check at
    // deals-load time — they must not re-trigger the fetch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, cart.restaurant_id]);

  const subtotal = cart.subtotal;
  const totalUnits = useMemo(
    () => cart.items.reduce((sum, i) => sum + i.quantity, 0),
    [cart.items]
  );
  const tipCap = Math.min(MAX_TIP_CENTS, subtotal);

  // Tip in cents, computed from the server-provided cart subtotal. The backend
  // only honours an absolute cents tip (it never recomputes a percentage), so
  // percent presets resolve to cents client-side — same as iOS. Pickup always
  // sends 0 (no courier, no tip lane).
  const tipCents = useMemo(() => {
    if (fulfillment === "pickup" || subtotal === 0) return 0;
    if (tipChoice === "none") return 0;
    if (tipChoice === "custom") {
      const cents = parseCents(customTip) ?? 0;
      return Math.min(Math.max(0, cents), tipCap);
    }
    const percent = TIP_PRESETS.find((p) => p.key === tipChoice)?.percent ?? 0;
    return Math.min(percentOfCents(subtotal, percent), tipCap);
  }, [fulfillment, tipChoice, customTip, subtotal, tipCap]);

  // Debounced server re-quote: any change to the cart, fulfillment, address,
  // tip, or applied deal re-POSTs /payments/intent so the rendered breakdown
  // is always the server's. Suppressed while the Stripe modal is up (a refresh
  // would swap the PaymentIntent out from under the live payment form) and
  // until the saved addresses have loaded (the first delivery quote should be
  // against the default address, not the flat-rate fallback).
  useEffect(() => {
    if (!token || cart.items.length === 0) return;
    if (checkoutOpen || needsVerification) return;
    if (fulfillment === "delivery" && addressesLoading) return;
    setPreviewPending(true);
    const handle = setTimeout(() => {
      void refreshIntent();
    }, REQUOTE_DEBOUNCE_MS);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, cart, fulfillment, selectedAddressId, tipCents, appliedDealId, checkoutOpen, needsVerification, addressesLoading]);

  // Immediate provider quote for the selected address (delivery only) —
  // POST /delivery-quote returns fee + ETA + provider for the route.
  useEffect(() => {
    if (!token || !cart.restaurant_id || fulfillment !== "delivery") {
      setQuote(null);
      return;
    }
    const addr = addresses.find((a) => a.id === selectedAddressId);
    if (!addr) {
      setQuote(null);
      return;
    }
    const gen = ++quoteGen.current;
    setQuoteLoading(true);
    deliveryQuoteApi(token, {
      restaurant_id: cart.restaurant_id,
      delivery_address: formatAddress(addr),
      delivery_lat: addr.lat,
      delivery_lng: addr.lng,
    })
      .then((q) => {
        if (gen === quoteGen.current) setQuote(q);
      })
      .catch(() => {
        // Non-fatal — the intent breakdown still carries the authoritative fee.
        if (gen === quoteGen.current) setQuote(null);
      })
      .finally(() => {
        if (gen === quoteGen.current) setQuoteLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, cart.restaurant_id, fulfillment, selectedAddressId, addresses]);

  // Lock body scroll while the Stripe payment sheet is up (same pattern as
  // MenuItemModal / KosherFilterPanel). Closing stays explicit (the X button)
  // — no backdrop/Escape dismissal that could interrupt a live payment.
  useEffect(() => {
    if (!checkoutOpen) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [checkoutOpen]);

  // POST /payments/intent with the current tip/fulfillment/address/deal and
  // adopt the server's breakdown verbatim. Also pins the address and deal the
  // quote was made against (see quotedAddress / quotedDealId).
  async function refreshIntent() {
    if (!token || cart.items.length === 0) return;
    const gen = ++intentGen.current;
    setPreviewError(null);
    const addr =
      fulfillment === "delivery"
        ? addresses.find((a) => a.id === selectedAddressId) ?? null
        : null;
    const dealIdSent = appliedDealId;
    // Stays false except on the deal-rejected path, where dropping the deal
    // immediately schedules a follow-up re-quote — previewPending must hold
    // true across that gap so "Place Order" can't fire against the stale
    // (deal-priced) intent in between.
    let requoteFollows = false;
    try {
      const bundle = (await paymentsApi.createIntent(token, {
        tip: tipCents,
        fulfillment_type: fulfillment,
        ...(addr ? { delivery_address: formatAddress(addr) } : {}),
        ...(dealIdSent ? { applied_deal_id: dealIdSent } : {}),
      })) as PaymentIntentBundle;
      if (gen !== intentGen.current) return;
      setIntent(bundle);
      setQuotedAddress(addr);
      // Pin the deal from the server's echo — exactly what the PI was priced
      // with (empty string when none).
      setQuotedDealId(bundle.applied_deal_id ? bundle.applied_deal_id : null);
      if (!stripeLoadedRef.current && bundle.publishable_key) {
        stripeLoadedRef.current = true;
        setStripePromise(loadStripe(bundle.publishable_key));
      }
    } catch (err) {
      if (gen !== intentGen.current) return;
      if (isUnauthorized(err)) {
        onUnauthorized();
        return;
      }
      if (isVerificationRequired(err)) {
        setNeedsVerification(true);
        return;
      }
      // Deal rejected server-side (expired / already used / below minimum /
      // wrong restaurant). Drop it and let the effect re-quote without it —
      // the breakdown recovers on its own, so this isn't a preview failure.
      if (dealIdSent && isDealError(err)) {
        requoteFollows = true;
        setAppliedDealId(null);
        setDealError(err instanceof Error ? err.message : "This deal can't be applied");
        return;
      }
      setPreviewError(err instanceof Error ? err.message : "Couldn't update your total");
    } finally {
      if (gen === intentGen.current && !requoteFollows) setPreviewPending(false);
    }
  }

  // Mirrors iOS updateCustomTip: digits + one decimal point only, hard-capped
  // at min($500, subtotal) so a value the backend would 400 can't be entered.
  function updateCustomTip(text: string) {
    const filtered = text.replace(/,/g, ".").replace(/[^0-9.]/g, "");
    if (filtered.split(".").length > 2) return;
    const cents = parseCents(filtered);
    if (cents !== null && cents > tipCap) {
      setTipError(`Tip can't exceed ${formatUSD(tipCap)}`);
      return;
    }
    setTipError(null);
    setCustomTip(filtered);
  }

  function selectTip(choice: TipChoice) {
    setTipChoice(choice);
    if (choice !== "custom") {
      setCustomTip("");
      setTipError(null);
    }
  }

  function selectDeal(id: string | null) {
    setDealError(null);
    setAppliedDealId(id);
  }

  // Validates a raw datetime-local value against the advertised bounds —
  // INCLUDING the 45-minute minimum lead the picker copy promises. Runs on
  // selection (inline scheduleError), BEFORE opening the Stripe modal, AND
  // again immediately before confirmPayment — a time that slipped inside the
  // lead window must stop the flow before any card is charged (same
  // pre-charge guard as iOS CheckoutViewModel.scheduledTimeIsValid).
  function scheduleProblemFor(value: string): string | null {
    if (!value) return "Pick a time for your scheduled order.";
    const d = new Date(value);
    if (isNaN(d.getTime())) return "Pick a valid time for your scheduled order.";
    if (d.getTime() <= Date.now()) {
      return "Your scheduled time has passed. Please select a new time.";
    }
    if (d.getTime() < Date.now() + MIN_SCHEDULE_LEAD_MS) {
      return "Scheduled orders need at least 45 minutes of lead time — pick a later time.";
    }
    if (d.getTime() > Date.now() + MAX_SCHEDULE_AHEAD_MS) {
      return "Scheduled orders can be at most 7 days ahead.";
    }
    return null;
  }

  function scheduleProblem(): string | null {
    if (timing !== "scheduled") return null;
    return scheduleProblemFor(scheduledAt);
  }

  // Open the Stripe modal against the CURRENT intent — the totals the user is
  // looking at are exactly what confirmPayment will charge. The button is
  // disabled while a re-quote is pending/in-flight, so intent, quotedAddress,
  // and quotedDealId are always consistent with the visible breakdown here.
  function beginCheckout() {
    if (!token) return;
    setCheckoutError(null);
    // The verification gate blocks intent creation itself (403), so this must
    // route BEFORE the intent guard — there is no intent to check yet.
    if (needsVerification) {
      router.push(VERIFY_ROUTE);
      return;
    }
    if (!intent) return;
    if (!intent.publishable_key || !intent.payment_intent_secret) {
      setCheckoutError("Payment provider is not configured. Please try again later.");
      return;
    }
    if (fulfillment === "delivery" && !quotedAddress) {
      setCheckoutError("Select a delivery address first.");
      return;
    }
    const problem = scheduleProblem();
    if (problem) {
      setCheckoutError(problem);
      return;
    }
    setPaymentBusy(false);
    setCheckoutOpen(true);
  }

  // Called only AFTER Stripe confirmPayment has captured the charge. We
  // persist the order intent to localStorage FIRST — before ANY step that can
  // throw — so a captured charge can never miss its recovery record: if
  // orders.create fails for any reason the order is re-attempted on the next
  // mount and the customer is never charged with no order. Submission itself
  // belongs to the page (onPaymentCaptured), which owns the finalizing/retry
  // UI. In particular there is NO pre-save guard that throws: quotedAddress is
  // guaranteed for delivery by beginCheckout (and re-quotes are suppressed
  // while the modal is open), but even if it were somehow missing, throwing
  // here would strand the charge with no snapshot — instead the snapshot is
  // saved with what we have and the page's retry loop + "don't pay again"
  // banner own the convergence.
  async function handlePaymentSucceeded(paymentIntentId: string) {
    const isPickup = fulfillment === "pickup";
    const addr = quotedAddress;

    // RFC3339 timestamp for the backend; null = ASAP (field omitted).
    const scheduledForISO =
      timing === "scheduled" && scheduledAt && !isNaN(new Date(scheduledAt).getTime())
        ? new Date(scheduledAt).toISOString()
        : null;

    const pending: PendingOrder = {
      payment_intent_id: paymentIntentId,
      restaurant_id: cart.restaurant_id,
      // Pickup carries no address (backend skips the address check when
      // fulfillment_type='pickup'). Delivery must send the EXACT string the
      // intent was quoted against — CreateOrder hash-matches it to the PI.
      delivery_address: !isPickup && addr ? formatAddress(addr) : "",
      delivery_lat: !isPickup && addr ? addr.lat : 0,
      delivery_lng: !isPickup && addr ? addr.lng : 0,
      // Echo the server's tip (intent.tip), not the client computation — it is
      // what the PI total was built from and what CreateOrder must record.
      tip: intent?.tip ?? 0,
      fulfillment_type: isPickup ? "pickup" : "delivery",
      ...(scheduledForISO ? { scheduled_for: scheduledForISO } : {}),
      // The deal the PI was PRICED with (server echo) — CreateOrder re-resolves
      // the discount from this id and the totals must reconcile with the charge.
      ...(quotedDealId ? { applied_deal_id: quotedDealId } : {}),
    };
    savePendingOrder(pending);

    // Parity with the mobile clients: POST /payments/confirm after the
    // Payment Element succeeds. Server-side it is a documented no-op (the
    // webhook is the real signal), so it must never block or fail the order.
    void paymentsApi.confirm(token).catch(() => {});

    // Close the Stripe modal; the charged intent is consumed — drop it so a
    // later re-quote starts clean. The page unmounts this panel while it
    // submits the persisted order.
    setCheckoutOpen(false);
    setIntent(null);
    onPaymentCaptured(pending);
  }

  async function saveAddress(e: React.FormEvent) {
    e.preventDefault();
    if (!token) return;
    const street = addrForm.street.trim();
    const city = addrForm.city.trim();
    const state = addrForm.state.trim();
    const zip = addrForm.zip_code.trim();
    if (!street || !city || !state || !zip) {
      setAddressError("Please fill in street, city, state, and ZIP.");
      return;
    }
    // The backend does NOT geocode (it stores lat/lng verbatim). Coordinates
    // come from the /api/geocode Census lookup (AddressGeocodeField fills the
    // same lat/lng strings) or from manual entry. Either way they feed
    // delivery routing and the distance-based delivery-fee quote, so we reject
    // placeholder/out-of-range values (especially null-island 0,0) rather
    // than send junk.
    const lat = parseFloat(addrForm.lat);
    const lng = parseFloat(addrForm.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
      setAddressError("Enter the address's latitude and longitude.");
      return;
    }
    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
      setAddressError("Latitude must be between -90 and 90, longitude between -180 and 180.");
      return;
    }
    if (lat === 0 && lng === 0) {
      setAddressError("Coordinates can't be (0, 0). Enter the address's real latitude and longitude.");
      return;
    }
    setSavingAddress(true);
    setAddressError(null);
    try {
      const created = await userApi.addAddress(token, {
        label: addrForm.label.trim() || "Home",
        street,
        city,
        state,
        zip_code: zip,
        lat,
        lng,
      });
      const fresh = (await userApi.listAddresses(token)) as Address[];
      // Select the address the user JUST entered — POST /user/addresses
      // returns the created row, so pin its id. (Preferring is_default here
      // used to snap the picker back to the OLD default address whenever the
      // user already had one.)
      setAddresses(fresh.some((x) => x.id === created.id) ? fresh : [...fresh, created]);
      setSelectedAddressId(created.id);
      setShowAddressForm(false);
      setAddrForm({ label: "Home", street: "", city: "", state: "", zip_code: "", lat: "", lng: "" });
    } catch (err) {
      if (isUnauthorized(err)) {
        onUnauthorized();
        return;
      }
      setAddressError(err instanceof Error ? err.message : "Failed to save address");
    } finally {
      setSavingAddress(false);
    }
  }

  const isDelivery = fulfillment === "delivery";

  // One-line geocoder query composed from the add-address form's own fields
  // (the form intentionally has no separate address search box). Street is
  // required for a meaningful Census match, so a blank street disables lookup.
  const geocodeQuery = addrForm.street.trim()
    ? [addrForm.street.trim(), addrForm.city.trim(), addrForm.state.trim(), addrForm.zip_code.trim()]
        .filter(Boolean)
        .join(", ")
    : "";

  const canPlaceOrder =
    cart.items.length > 0 &&
    (fulfillment === "pickup" || !!selectedAddressId) &&
    // A scheduled order needs a chosen time that passed on-selection
    // validation. (beginCheckout/validateBeforePay still re-validate against
    // the clock — scheduleError only reflects the moment of selection.)
    (timing === "asap" || (!!scheduledAt && !scheduleError)) &&
    (needsVerification || (!!intent && !previewPending && !previewError));

  const stripeOptions = useMemo(
    () =>
      intent
        ? ({
            clientSecret: intent.payment_intent_secret,
            appearance: { theme: "night" as const, labels: "floating" as const },
          })
        : null,
    [intent]
  );

  // A money row rendered ONLY from server values; an em-dash placeholder while
  // the authoritative breakdown is (re)loading. No client-side fee math.
  function moneyCell(value: number | undefined): React.ReactNode {
    if (value === undefined) return <span className="text-dark-600">—</span>;
    return formatUSD(value);
  }

  // datetime-local bounds, recomputed per render (mirrors the iOS picker's
  // now+45min … now+7d range).
  const scheduleMin = toLocalInputValue(new Date(Date.now() + MIN_SCHEDULE_LEAD_MS));
  const scheduleMax = toLocalInputValue(new Date(Date.now() + MAX_SCHEDULE_AHEAD_MS));

  return (
    <>
      <div className="card p-5 sticky top-24">
        <h3 className="font-bold text-lg mb-4">Order Summary</h3>

        {/* Delivery / Pickup toggle */}
        <div className="flex bg-dark-800 rounded-xl p-1 mb-4" role="group" aria-label="Fulfillment type">
          <button
            type="button"
            onClick={() => setFulfillment("delivery")}
            aria-pressed={isDelivery}
            className={`flex-1 rounded-lg py-2 min-h-[44px] text-sm font-semibold transition-colors ${
              isDelivery ? "bg-brand-500 text-white" : "text-dark-300 hover:text-white"
            }`}
          >
            Delivery
          </button>
          <button
            type="button"
            onClick={() => setFulfillment("pickup")}
            aria-pressed={!isDelivery}
            className={`flex-1 rounded-lg py-2 min-h-[44px] text-sm font-semibold transition-colors ${
              !isDelivery ? "bg-brand-500 text-white" : "text-dark-300 hover:text-white"
            }`}
          >
            Pickup
          </button>
        </div>

        {/* Delivery Address (delivery only — pickup needs no address) */}
        {isDelivery && (
          <div className="mb-4">
            <label htmlFor="cart-address" className="block text-sm text-dark-300 mb-2">
              Delivery address
            </label>
            {addressesLoading ? (
              <div className="text-sm text-dark-500">Loading addresses…</div>
            ) : addresses.length === 0 ? (
              !showAddressForm && (
                <div className="text-sm text-dark-400">
                  You have no saved addresses. Add one below to place an order.
                </div>
              )
            ) : (
              <select
                id="cart-address"
                className="input w-full"
                value={selectedAddressId}
                onChange={(e) => setSelectedAddressId(e.target.value)}
              >
                {addresses.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.label}: {formatAddress(a)}
                  </option>
                ))}
              </select>
            )}

            {/* Route quote (POST /delivery-quote): provider + ETA */}
            {addresses.length > 0 && (
              <p className="text-xs text-dark-500 mt-1.5" aria-live="polite">
                {quoteLoading
                  ? "Getting delivery estimate…"
                  : quote
                    ? `${providerLabel(quote.provider)} · est. ${quote.est_minutes} min`
                    : " "}
              </p>
            )}

            {showAddressForm ? (
              <form onSubmit={saveAddress} className="mt-3 space-y-2">
                <input
                  className="input w-full"
                  placeholder="Label (e.g. Home)"
                  value={addrForm.label}
                  onChange={(e) => setAddrForm((f) => ({ ...f, label: e.target.value }))}
                />
                <input
                  className="input w-full"
                  placeholder="Street address"
                  autoComplete="address-line1"
                  value={addrForm.street}
                  onChange={(e) => setAddrForm((f) => ({ ...f, street: e.target.value }))}
                />
                <input
                  className="input w-full"
                  placeholder="City"
                  autoComplete="address-level2"
                  value={addrForm.city}
                  onChange={(e) => setAddrForm((f) => ({ ...f, city: e.target.value }))}
                />
                <div className="flex gap-2">
                  <input
                    className="input w-full"
                    placeholder="State"
                    autoComplete="address-level1"
                    value={addrForm.state}
                    onChange={(e) => setAddrForm((f) => ({ ...f, state: e.target.value }))}
                  />
                  <input
                    className="input w-full"
                    placeholder="ZIP"
                    autoComplete="postal-code"
                    value={addrForm.zip_code}
                    onChange={(e) => setAddrForm((f) => ({ ...f, zip_code: e.target.value }))}
                  />
                </div>
                {/* Coordinates via the Census geocoder (/api/geocode), composed
                    from the street/city/state/zip fields above — on success it
                    fills the same addrForm.lat/lng strings saveAddress
                    parseFloat-validates. Manual entry stays available in the
                    component's collapsible fallback section. */}
                <AddressGeocodeField
                  query={geocodeQuery}
                  showQueryInput={false}
                  buttonLabel="Find coordinates from address"
                  lat={addrForm.lat}
                  lng={addrForm.lng}
                  onLatChange={(v) => setAddrForm((f) => ({ ...f, lat: v }))}
                  onLngChange={(v) => setAddrForm((f) => ({ ...f, lng: v }))}
                  onResolved={(r) =>
                    setAddrForm((f) => ({ ...f, lat: String(r.lat), lng: String(r.lng) }))
                  }
                />
                <p className="text-xs text-dark-500">
                  Used for delivery routing and to estimate your delivery fee.
                </p>
                {addressError && <div className="text-sm text-red-400">{addressError}</div>}
                <div className="flex gap-2 pt-1">
                  <button
                    type="submit"
                    disabled={savingAddress}
                    className="btn-primary flex-1 flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {savingAddress && (
                      <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
                    )}
                    {savingAddress ? "Saving…" : "Save address"}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setShowAddressForm(false);
                      setAddressError(null);
                    }}
                    className="btn-secondary text-center"
                  >
                    Cancel
                  </button>
                </div>
              </form>
            ) : (
              <button
                type="button"
                onClick={() => setShowAddressForm(true)}
                className="mt-1 inline-flex items-center min-h-[44px] text-sm text-brand-400 underline"
              >
                + Add delivery address
              </button>
            )}
          </div>
        )}

        {/* ASAP vs scheduled — mirrors the iOS DeliveryTimeCard (relabelled
            for pickup). Scheduling doesn't change pricing, only order status. */}
        <div className="mb-4">
          <span className="block text-sm text-dark-300 mb-2">
            {isDelivery ? "Delivery time" : "Pickup time"}
          </span>
          <div
            className="flex bg-dark-800 rounded-xl p-1"
            role="group"
            aria-label={isDelivery ? "Delivery time" : "Pickup time"}
          >
            <button
              type="button"
              onClick={() => {
                setTiming("asap");
                setScheduleError(null);
              }}
              aria-pressed={timing === "asap"}
              className={`flex-1 rounded-lg py-2 min-h-[44px] text-sm font-semibold transition-colors ${
                timing === "asap" ? "bg-brand-500 text-white" : "text-dark-300 hover:text-white"
              }`}
            >
              ASAP
            </button>
            <button
              type="button"
              onClick={() => {
                setTiming("scheduled");
                // Re-validate a previously chosen time — it may have slipped
                // inside the 45-min window while ASAP was selected.
                setScheduleError(scheduledAt ? scheduleProblemFor(scheduledAt) : null);
              }}
              aria-pressed={timing === "scheduled"}
              className={`flex-1 rounded-lg py-2 min-h-[44px] text-sm font-semibold transition-colors ${
                timing === "scheduled" ? "bg-brand-500 text-white" : "text-dark-300 hover:text-white"
              }`}
            >
              Schedule
            </button>
          </div>
          {timing === "scheduled" && (
            <>
              <input
                type="datetime-local"
                className="input w-full mt-2"
                aria-label={isDelivery ? "Scheduled delivery time" : "Scheduled pickup time"}
                min={scheduleMin}
                max={scheduleMax}
                value={scheduledAt}
                onChange={(e) => {
                  const v = e.target.value;
                  setScheduledAt(v);
                  // Validate on selection — min/max above are advisory in
                  // most browsers, so a time inside the 45-minute lead window
                  // must surface its error immediately, not at Place Order.
                  setScheduleError(v ? scheduleProblemFor(v) : null);
                }}
              />
              {scheduleError ? (
                <p className="text-xs text-red-400 mt-1" role="alert">
                  {scheduleError}
                </p>
              ) : (
                <p className="text-xs text-dark-500 mt-1">
                  At least 45 minutes from now, up to 7 days ahead.
                </p>
              )}
            </>
          )}
        </div>

        {/* Deals for this restaurant — applying one re-quotes the intent so
            the discount row below is the server's number, never client math. */}
        {dealsList.length > 0 && (
          <div className="mb-4">
            <span className="block text-sm text-dark-300 mb-2">Deals</span>
            <div className="space-y-1.5">
              {dealsList.map((d) => {
                const applied = appliedDealId === d.id;
                const belowMin = (d.min_order_amount ?? 0) > subtotal;
                const bogoNeedsUnits = d.discount_type === "bogo" && totalUnits < 2;
                const blocked = !applied && (belowMin || bogoNeedsUnits);
                return (
                  <div
                    key={d.id}
                    className={`rounded-lg border p-2.5 flex items-center justify-between gap-2 ${
                      applied ? "border-brand-500 bg-brand-900/10" : "border-dark-700 bg-dark-800"
                    }`}
                  >
                    <div className="min-w-0">
                      <p className="text-sm font-semibold truncate">{d.title}</p>
                      <p className="text-xs text-dark-400">
                        {dealSummary(d)}
                        {belowMin && ` · Min order ${formatUSD(d.min_order_amount ?? 0)}`}
                        {bogoNeedsUnits && " · Add 2+ items to use"}
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => selectDeal(applied ? null : d.id)}
                      disabled={blocked}
                      aria-pressed={applied}
                      className={`shrink-0 rounded-lg px-3 py-1.5 min-h-[44px] text-xs font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                        applied
                          ? "bg-dark-700 text-dark-200 hover:bg-dark-600"
                          : "bg-brand-500 text-white hover:bg-brand-600"
                      }`}
                    >
                      {applied ? "Remove" : "Apply"}
                    </button>
                  </div>
                );
              })}
            </div>
            {dealError && <div className="mt-1 text-xs text-red-400">{dealError}</div>}
          </div>
        )}

        {/* Courier tip (delivery only — pickup has no courier) */}
        {isDelivery && (
          <div className="mb-4">
            <span className="block text-sm text-dark-300 mb-2">Courier tip</span>
            <div className="grid grid-cols-5 gap-1.5" role="group" aria-label="Tip amount">
              {TIP_PRESETS.map((p) => {
                const selected = tipChoice === p.key;
                const presetCents =
                  p.percent > 0 ? Math.min(percentOfCents(subtotal, p.percent), tipCap) : null;
                return (
                  <button
                    key={p.key}
                    type="button"
                    onClick={() => selectTip(p.key)}
                    aria-pressed={selected}
                    className={`rounded-lg py-1.5 px-0.5 min-h-[44px] text-xs font-semibold transition-colors ${
                      selected
                        ? "bg-brand-500 text-white"
                        : "bg-dark-800 text-dark-300 hover:bg-dark-700"
                    }`}
                  >
                    <span className="block">{p.label}</span>
                    {presetCents !== null && (
                      <span className={`block text-[10px] font-normal ${selected ? "text-white/80" : "text-dark-500"}`}>
                        {formatUSD(presetCents)}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
            {tipChoice === "custom" && (
              <input
                className="input w-full mt-2"
                inputMode="decimal"
                placeholder="Custom tip (e.g. 3.50)"
                aria-label="Custom tip in dollars"
                value={customTip}
                onChange={(e) => updateCustomTip(e.target.value)}
              />
            )}
            {tipError && <div className="mt-1 text-xs text-red-400">{tipError}</div>}
          </div>
        )}

        {/* Server-authoritative breakdown — every figure comes from the
            POST /payments/intent response (integer cents). While a
            re-quote is pending the stale figures give way to "—". */}
        <div className="space-y-2 text-sm border-t border-dark-700 pt-4" aria-live="polite" aria-busy={previewPending}>
          <div className="flex justify-between text-dark-400">
            <span>Subtotal</span>
            <span>{formatUSD(intent?.subtotal ?? subtotal)}</span>
          </div>
          {intent && (intent.discount ?? 0) > 0 && (
            <div className="flex justify-between text-brand-400">
              <span>Deal discount</span>
              <span>-{formatUSD(intent.discount ?? 0)}</span>
            </div>
          )}
          {isDelivery && (
            <div className="flex justify-between text-dark-400">
              <span>Delivery fee</span>
              <span>{moneyCell(previewPending ? undefined : intent?.delivery_fee)}</span>
            </div>
          )}
          <div className="flex justify-between text-dark-400">
            <span>Service fee</span>
            <span>{moneyCell(previewPending ? undefined : intent?.service_fee)}</span>
          </div>
          <div className="flex justify-between text-dark-400">
            <span>Tax</span>
            <span>{moneyCell(previewPending ? undefined : intent?.tax)}</span>
          </div>
          {isDelivery && (
            <div className="flex justify-between text-dark-400">
              <span>Tip</span>
              <span>{moneyCell(previewPending ? undefined : intent?.tip)}</span>
            </div>
          )}
          <div className="border-t border-dark-700 pt-2 mt-2 flex justify-between font-bold text-base">
            <span>Total</span>
            <span className="text-brand-400">
              {previewPending || !intent ? (
                <span className="text-dark-500 font-normal">Updating…</span>
              ) : (
                formatUSD(intent.total)
              )}
            </span>
          </div>
        </div>

        {needsVerification && (
          <div className="mt-4 text-sm text-dark-300 border border-brand-700 bg-brand-900/10 rounded-lg p-3">
            Verify your email and phone number to check out.
          </div>
        )}

        {previewError && (
          <div className="mt-4 text-sm text-red-400">
            {previewError}{" "}
            <button
              type="button"
              onClick={() => {
                setPreviewPending(true);
                void refreshIntent();
              }}
              className="text-brand-400 underline"
            >
              Retry
            </button>
          </div>
        )}

        {checkoutError && (
          <div className="mt-4 text-sm text-red-400">{checkoutError}</div>
        )}

        <button
          onClick={beginCheckout}
          disabled={!canPlaceOrder}
          className="btn-primary w-full text-center mt-4 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {needsVerification
            ? "Verify to place order"
            : intent && !previewPending
              ? `Place Order · ${formatUSD(intent.total)}`
              : "Place Order"}
        </button>
      </div>

      {checkoutOpen && intent && stripePromise && stripeOptions && (
        <div
          className="fixed inset-0 z-50 bg-black/70 flex items-stretch md:items-center justify-center md:p-4"
          role="dialog"
          aria-modal="true"
          aria-label="Checkout"
        >
          {/* Below md: a full-height bottom sheet (h-full against the inset-0
              overlay); at md+ a centered dialog capped at 85vh — same shell as
              MenuItemModal. Header and action bar stay pinned; the Payment
              Element scrolls between them. */}
          <div className="card w-full md:max-w-md h-full md:h-auto md:max-h-[85vh] flex flex-col rounded-none md:rounded-2xl">
            <div className="flex items-start justify-between gap-3 px-5 py-4 md:px-6 border-b border-dark-800">
              <div className="min-w-0">
                <h2 className="text-xl font-bold">Checkout</h2>
                <p className="text-dark-400 text-sm mt-0.5">
                  Pay {formatUSD(intent.total)} to complete your order.
                </p>
              </div>
              {/* Close is disabled the whole time a confirmPayment attempt is
                  in flight — dismissing mid-confirmation could hide a charge
                  that is about to capture. (Backdrop/Escape never dismiss.) */}
              <button
                onClick={() => {
                  if (!paymentBusy) setCheckoutOpen(false);
                }}
                disabled={paymentBusy}
                className="w-11 h-11 -mr-2 -mt-1 rounded-xl text-dark-400 hover:text-white hover:bg-dark-800 transition-colors flex-shrink-0 flex items-center justify-center disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-dark-400"
                aria-label={paymentBusy ? "Processing payment — checkout can't be closed" : "Close checkout"}
              >
                <X className="w-5 h-5" aria-hidden="true" />
              </button>
            </div>
            <Elements stripe={stripePromise} options={stripeOptions}>
              <CheckoutForm
                total={intent.total}
                validateBeforePay={scheduleProblem}
                onSuccess={handlePaymentSucceeded}
                onError={setCheckoutError}
                onBusyChange={setPaymentBusy}
              />
            </Elements>
          </div>
        </div>
      )}
    </>
  );
}

function CheckoutForm({
  total,
  validateBeforePay,
  onSuccess,
  onError,
  onBusyChange,
}: {
  total: number;
  /** Last pre-charge gate — returns an error message to abort, null to pay. */
  validateBeforePay: () => string | null;
  onSuccess: (paymentIntentId: string) => Promise<void>;
  onError: (msg: string) => void;
  /**
   * Mirrors the submitting flag up to the panel so it can lock the modal's
   * close affordance while confirmation/finalize handoff is in flight.
   */
  onBusyChange: (busy: boolean) => void;
}) {
  const stripe = useStripe();
  const elements = useElements();
  const [submitting, setSubmitting] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!stripe || !elements) return;
    // Pre-charge validation: a stale scheduled time must abort BEFORE the
    // card is charged — once the charge is captured there's no clean undo.
    const problem = validateBeforePay();
    if (problem) {
      setLocalError(problem);
      onError(problem);
      return;
    }
    setSubmitting(true);
    onBusyChange(true);
    setLocalError(null);
    try {
      const { error, paymentIntent } = await stripe.confirmPayment({
        elements,
        redirect: "if_required",
      });
      if (error) {
        const msg = error.message ?? "Payment failed";
        setLocalError(msg);
        onError(msg);
        return;
      }
      // 'succeeded' is the normal outcome, but 'processing' (async settlement
      // — Stripe accepted the confirmation and the charge is in flight) and
      // 'requires_capture' (authorized, pending capture) also mean money is
      // committed. Those MUST route into the PendingOrder finalize/recovery
      // path too, or a later webhook settlement would leave the customer
      // charged with no order — the exact failure PendingOrder exists to
      // prevent. Anything else is a genuine failure.
      const capturedStatuses = ["succeeded", "processing", "requires_capture"];
      if (!paymentIntent || !capturedStatuses.includes(paymentIntent.status)) {
        const msg = `Payment not completed (status: ${paymentIntent?.status ?? "unknown"})`;
        setLocalError(msg);
        onError(msg);
        return;
      }
      await onSuccess(paymentIntent.id);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Payment failed";
      setLocalError(msg);
      onError(msg);
    } finally {
      setSubmitting(false);
      onBusyChange(false);
    }
  }

  // The form fills the sheet: the Payment Element scrolls in the middle while
  // the Pay bar stays pinned below it, padded past the home indicator on
  // notched phones (pb-[env(safe-area-inset-bottom)]).
  return (
    <form onSubmit={handleSubmit} className="flex-1 min-h-0 flex flex-col">
      <div className="flex-1 min-h-0 overflow-y-auto px-5 py-4 md:px-6 space-y-4">
        <PaymentElement />
        {localError && <div className="text-sm text-red-400">{localError}</div>}
      </div>
      <div className="border-t border-dark-800 px-5 pt-4 pb-[calc(1rem+env(safe-area-inset-bottom))] md:px-6 md:pb-4">
        <button
          type="submit"
          disabled={!stripe || !elements || submitting}
          className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {submitting && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
          {submitting ? "Processing…" : `Pay ${formatUSD(total)}`}
        </button>
      </div>
    </form>
  );
}
