"use client";

import { Header } from "@/components/layout/Header";
import { cart as cartApi, orders as ordersApi, payments as paymentsApi, restaurants as restaurantsApi, user as userApi } from "@/lib/api";
import type { Cart } from "@/types";
import { Elements, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import { loadStripe, type Stripe } from "@stripe/stripe-js";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

interface Address {
  id: string;
  label: string;
  street: string;
  apt?: string;
  city: string;
  state: string;
  zip_code: string;
  lat: number;
  lng: number;
  is_default: boolean;
}

interface PaymentIntentBundle {
  payment_intent_secret: string;
  publishable_key: string;
  subtotal: number;
  delivery_fee: number;
  service_fee: number;
  tax: number;
  tip: number;
  total: number;
}

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

function formatUSD(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}

function formatAddress(a: Address): string {
  return `${a.street}${a.apt ? ` ${a.apt}` : ""}, ${a.city}, ${a.state} ${a.zip_code}`;
}

export default function CartPage() {
  const router = useRouter();
  const [token, setToken] = useState<string | null>(null);
  const [cart, setCart] = useState<Cart | null>(null);
  const [restaurantName, setRestaurantName] = useState<string>("");
  const [addresses, setAddresses] = useState<Address[]>([]);
  const [selectedAddressId, setSelectedAddressId] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [mutatingItemId, setMutatingItemId] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);

  const [intent, setIntent] = useState<PaymentIntentBundle | null>(null);
  const [stripePromise, setStripePromise] = useState<Promise<Stripe | null> | null>(null);
  const [checkoutStarting, setCheckoutStarting] = useState(false);
  const [checkoutError, setCheckoutError] = useState<string | null>(null);

  useEffect(() => {
    const t = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!t) {
      router.replace("/auth");
      return;
    }
    setToken(t);
    void loadAll(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function loadAll(t: string) {
    setLoading(true);
    setLoadError(null);
    try {
      const [c, a] = await Promise.all([
        cartApi.get(t) as Promise<Cart>,
        userApi.listAddresses(t) as Promise<Address[]>,
      ]);
      setCart(c);
      setAddresses(a);
      const preferred = a.find((x) => x.is_default) ?? a[0];
      if (preferred) setSelectedAddressId(preferred.id);

      if (c.restaurant_id) {
        try {
          const r = (await restaurantsApi.get(c.restaurant_id)) as { name: string };
          setRestaurantName(r.name);
        } catch {
          // Non-fatal — cart is still usable without the restaurant label.
        }
      }
    } catch (err) {
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        router.replace("/auth");
        return;
      }
      setLoadError(err instanceof Error ? err.message : "Failed to load cart");
    } finally {
      setLoading(false);
    }
  }

  async function mutateQuantity(itemId: string, delta: number) {
    if (!token || !cart) return;
    const item = cart.items.find((i) => i.id === itemId);
    if (!item) return;
    const next = item.quantity + delta;

    setMutatingItemId(itemId);
    setMutationError(null);
    try {
      if (next <= 0) {
        await cartApi.removeItem(token, itemId);
      } else {
        await cartApi.updateItem(token, itemId, { quantity: next, notes: item.notes ?? "" });
      }
      const fresh = (await cartApi.get(token)) as Cart;
      setCart(fresh);
    } catch (err) {
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        router.replace("/auth");
        return;
      }
      setMutationError(err instanceof Error ? err.message : "Failed to update cart");
    } finally {
      setMutatingItemId(null);
    }
  }

  async function beginCheckout() {
    if (!token || !cart || !selectedAddressId) return;
    setCheckoutError(null);
    setCheckoutStarting(true);
    try {
      const bundle = (await paymentsApi.createIntent(token, {})) as PaymentIntentBundle;
      if (!bundle.publishable_key || !bundle.payment_intent_secret) {
        throw new Error("Payment provider is not configured. Please try again later.");
      }
      setStripePromise(loadStripe(bundle.publishable_key));
      setIntent(bundle);
    } catch (err) {
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        router.replace("/auth");
        return;
      }
      setCheckoutError(err instanceof Error ? err.message : "Failed to start checkout");
    } finally {
      setCheckoutStarting(false);
    }
  }

  async function finalizeOrder(paymentIntentId: string) {
    if (!token || !cart) throw new Error("Session expired");
    const addr = addresses.find((a) => a.id === selectedAddressId);
    if (!addr) throw new Error("No delivery address selected");

    await ordersApi.create(token, {
      restaurant_id: cart.restaurant_id,
      delivery_address: formatAddress(addr),
      delivery_lat: addr.lat,
      delivery_lng: addr.lng,
      payment_intent_id: paymentIntentId,
    });
    router.push("/orders");
  }

  const subtotal = cart?.subtotal ?? 0;
  // Display estimates mirror backend CreatePaymentIntent so the preview
  // matches the authoritative number returned from /payments/intent.
  const deliveryFee = 399;
  const serviceFee = Math.round(subtotal * 0.15);
  const tax = Math.round(subtotal * 0.09);
  const displayTotal = intent?.total ?? subtotal + deliveryFee + serviceFee + tax;

  const canPlaceOrder = !!cart && cart.items.length > 0 && !!selectedAddressId && !checkoutStarting;

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

  if (loading) {
    return (
      <>
        <Header />
        <main className="flex-1 max-w-4xl mx-auto px-4 py-8">
          <div className="card p-12 text-center text-dark-400">Loading your cart…</div>
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
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load your cart</h2>
            <p className="text-dark-400 mb-6">{loadError}</p>
            <button onClick={() => token && loadAll(token)} className="btn-primary inline-block">
              Retry
            </button>
          </div>
        </main>
      </>
    );
  }

  const items = cart?.items ?? [];

  return (
    <>
      <Header />
      <main className="flex-1 max-w-4xl mx-auto px-4 py-8">
        <h1 className="text-3xl font-extrabold mb-2">Your Cart</h1>
        {restaurantName && (
          <p className="text-dark-400 mb-8">
            From <span className="text-brand-400 font-medium">{restaurantName}</span>
          </p>
        )}

        {items.length === 0 ? (
          <div className="card p-12 text-center">
            <svg className="w-16 h-16 text-dark-600 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 100 4 2 2 0 000-4z" />
            </svg>
            <h2 className="text-xl font-bold mb-2">Your cart is empty</h2>
            <p className="text-dark-400 mb-6">Add items from a restaurant to get started.</p>
            <a href="/" className="btn-primary inline-block">Browse Restaurants</a>
          </div>
        ) : (
          <div className="flex flex-col lg:flex-row gap-8">
            {/* Items */}
            <div className="flex-1 space-y-4">
              {mutationError && (
                <div className="card p-3 border border-red-800 bg-red-900/20 text-red-300 text-sm">
                  {mutationError}
                </div>
              )}
              {items.map((item) => {
                const isPending = mutatingItemId === item.id;
                return (
                  <div key={item.id} className="card p-4 flex items-center justify-between">
                    <div className="flex-1">
                      <h3 className="font-semibold">{item.name}</h3>
                      <p className="text-brand-400 text-sm font-medium mt-0.5">
                        {formatUSD(item.price)}
                      </p>
                      {item.notes && (
                        <p className="text-dark-400 text-xs mt-1 italic">{item.notes}</p>
                      )}
                    </div>
                    <div className="flex items-center gap-3">
                      <div className="flex items-center gap-3 bg-dark-800 rounded-xl px-3 py-2">
                        <button
                          onClick={() => mutateQuantity(item.id, -1)}
                          disabled={isPending}
                          aria-label={item.quantity === 1 ? "Remove item" : "Decrease quantity"}
                          className="w-7 h-7 rounded-full bg-dark-700 hover:bg-dark-600 disabled:opacity-50 flex items-center justify-center text-white transition-colors"
                        >
                          {item.quantity === 1 ? (
                            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                            </svg>
                          ) : (
                            "-"
                          )}
                        </button>
                        <span className="font-semibold w-6 text-center">{item.quantity}</span>
                        <button
                          onClick={() => mutateQuantity(item.id, 1)}
                          disabled={isPending}
                          aria-label="Increase quantity"
                          className="w-7 h-7 rounded-full bg-brand-500 hover:bg-brand-600 disabled:opacity-50 flex items-center justify-center text-white transition-colors"
                        >
                          +
                        </button>
                      </div>
                      <span className="text-sm font-semibold w-16 text-right">
                        {formatUSD(item.price * item.quantity)}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Order Summary */}
            <div className="lg:w-80">
              <div className="card p-5 sticky top-24">
                <h3 className="font-bold text-lg mb-4">Order Summary</h3>

                <div className="space-y-2 text-sm">
                  <div className="flex justify-between text-dark-400">
                    <span>Subtotal</span>
                    <span>{formatUSD(intent?.subtotal ?? subtotal)}</span>
                  </div>
                  <div className="flex justify-between text-dark-400">
                    <span>Delivery fee</span>
                    <span>{formatUSD(intent?.delivery_fee ?? deliveryFee)}</span>
                  </div>
                  <div className="flex justify-between text-dark-400">
                    <span>Service fee</span>
                    <span>{formatUSD(intent?.service_fee ?? serviceFee)}</span>
                  </div>
                  <div className="flex justify-between text-dark-400">
                    <span>Tax</span>
                    <span>{formatUSD(intent?.tax ?? tax)}</span>
                  </div>
                  <div className="border-t border-dark-700 pt-2 mt-2 flex justify-between font-bold text-base">
                    <span>Total</span>
                    <span className="text-brand-400">{formatUSD(displayTotal)}</span>
                  </div>
                </div>

                {/* Delivery Address */}
                <div className="mt-6 mb-4">
                  <label htmlFor="cart-address" className="block text-sm text-dark-300 mb-2">
                    Delivery address
                  </label>
                  {addresses.length === 0 ? (
                    <div className="text-sm text-dark-400">
                      You have no saved addresses. Add one from your{" "}
                      <a href="/" className="text-brand-400 underline">
                        account
                      </a>{" "}
                      to place an order.
                    </div>
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
                </div>

                {checkoutError && (
                  <div className="mb-3 text-sm text-red-400">{checkoutError}</div>
                )}

                <button
                  onClick={beginCheckout}
                  disabled={!canPlaceOrder}
                  className="btn-primary w-full text-center disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {checkoutStarting
                    ? "Starting checkout…"
                    : `Place Order · ${formatUSD(displayTotal)}`}
                </button>
              </div>
            </div>
          </div>
        )}
      </main>

      {intent && stripePromise && stripeOptions && (
        <div
          className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4"
          role="dialog"
          aria-modal="true"
        >
          <div className="card w-full max-w-md p-6 relative">
            <button
              onClick={() => {
                setIntent(null);
                setStripePromise(null);
              }}
              className="absolute top-3 right-3 text-dark-400 hover:text-white"
              aria-label="Close checkout"
            >
              ✕
            </button>
            <h2 className="text-xl font-bold mb-1">Checkout</h2>
            <p className="text-dark-400 text-sm mb-5">
              Pay {formatUSD(intent.total)} to complete your order.
            </p>
            <Elements stripe={stripePromise} options={stripeOptions}>
              <CheckoutForm
                total={intent.total}
                onSuccess={finalizeOrder}
                onError={setCheckoutError}
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
  onSuccess,
  onError,
}: {
  total: number;
  onSuccess: (paymentIntentId: string) => Promise<void>;
  onError: (msg: string) => void;
}) {
  const stripe = useStripe();
  const elements = useElements();
  const [submitting, setSubmitting] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!stripe || !elements) return;
    setSubmitting(true);
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
      if (!paymentIntent || paymentIntent.status !== "succeeded") {
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
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <PaymentElement />
      {localError && <div className="text-sm text-red-400">{localError}</div>}
      <button
        type="submit"
        disabled={!stripe || !elements || submitting}
        className="btn-primary w-full text-center disabled:opacity-50 disabled:cursor-not-allowed"
      >
        {submitting ? "Processing…" : `Pay ${formatUSD(total)}`}
      </button>
    </form>
  );
}
