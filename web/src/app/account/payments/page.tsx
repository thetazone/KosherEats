"use client";

import { Header } from "@/components/layout/Header";
import { payments as paymentsApi } from "@/lib/api";
import type { PaymentCustomerBundle } from "@/types";
import { Elements, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import { loadStripe, type Stripe } from "@stripe/stripe-js";
import { ArrowLeft, CreditCard, Loader2, Lock, Plus, ShieldCheck, Wrench } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

// Account → Payment methods — the web twin of iOS PaymentMethodsView (which
// wraps Stripe's STPCustomerSheet). The web scope is strictly what the two
// backend endpoints support:
//   GET  /payments/customer     → { customer_id, ephemeral_key_secret,
//                                   publishable_key } (customer context; a
//                                   "cus_stub_" id means Stripe isn't
//                                   configured server-side — dev-stub mode)
//   POST /payments/setup-intent → { client_secret } (a fresh SetupIntent per
//                                   "add a card" flow, card-only server-side)
// There is no list/delete endpoint web-side (the ephemeral key is only
// consumable by the mobile CustomerSheet), so this page is the save-a-card
// flow: SetupIntent → Payment Element → confirmSetup. Whatever is saved here
// is attached to the same persistent Stripe Customer the checkout charges,
// so saved cards surface at checkout and on iOS.

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : "Something went wrong. Please try again.";
}

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

// Dev-stub detection — same signal iOS uses (CustomerBundle.isStub): the
// backend fabricates "cus_stub_…" ids when it has no STRIPE_SECRET_KEY, and
// presenting a payment form against a stub secret would just fail.
function isStubBundle(b: PaymentCustomerBundle): boolean {
  return b.customer_id.startsWith("cus_stub_");
}

// ---------------------------------------------------------------------------
// Add-card form (inside <Elements>): Payment Element + confirmSetup. Card-only
// server-side, so redirect "if_required" never actually leaves the page (3DS
// runs in Stripe's modal) — same confirmation pattern as checkout's
// CheckoutForm, minus the money.
// ---------------------------------------------------------------------------

function AddCardForm({
  onSaved,
  onCancel,
}: {
  onSaved: () => void;
  onCancel: () => void;
}) {
  const stripe = useStripe();
  const elements = useElements();
  const [submitting, setSubmitting] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!stripe || !elements || submitting) return;
    setSubmitting(true);
    setLocalError(null);
    try {
      const { error, setupIntent } = await stripe.confirmSetup({
        elements,
        redirect: "if_required",
      });
      if (error) {
        setLocalError(error.message ?? "Couldn't save your card");
        return;
      }
      if (!setupIntent || setupIntent.status !== "succeeded") {
        setLocalError(
          `Card not saved (status: ${setupIntent?.status ?? "unknown"}). Please try again.`
        );
        return;
      }
      onSaved();
    } catch (err) {
      setLocalError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <PaymentElement />
      {localError && <div className="text-sm text-red-400">{localError}</div>}
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={!stripe || !elements || submitting}
          className="btn-primary flex-1 flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {submitting && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
          {submitting ? "Saving…" : "Save card"}
        </button>
        <button
          type="button"
          onClick={onCancel}
          disabled={submitting}
          className="btn-secondary text-center disabled:opacity-50"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function PaymentMethodsPage() {
  const router = useRouter();

  const [token, setToken] = useState<string | null>(null);
  const [bundle, setBundle] = useState<PaymentCustomerBundle | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Add-card flow: a fresh SetupIntent client_secret per open (the backend
  // mints one per POST — never reuse a confirmed secret).
  const [clientSecret, setClientSecret] = useState<string | null>(null);
  const [opening, setOpening] = useState(false);
  const [openError, setOpenError] = useState<string | null>(null);
  const [savedCount, setSavedCount] = useState(0);

  const [stripePromise, setStripePromise] = useState<Promise<Stripe | null> | null>(null);
  // loadStripe must run exactly once — repeated calls spin up extra Stripe.js
  // instances (same guard as CheckoutPanel).
  const stripeLoadedRef = useRef(false);

  const handleUnauthorized = useCallback(() => {
    window.localStorage.removeItem("token");
    router.replace("/auth");
  }, [router]);

  const loadBundle = useCallback(
    async (t: string) => {
      setLoading(true);
      setLoadError(null);
      try {
        const b = await paymentsApi.customer(t);
        setBundle(b);
        if (!stripeLoadedRef.current && b.publishable_key && !isStubBundle(b)) {
          stripeLoadedRef.current = true;
          setStripePromise(loadStripe(b.publishable_key));
        }
      } catch (err) {
        if (isUnauthorized(err)) {
          handleUnauthorized();
          return;
        }
        setLoadError(errorMessage(err));
      } finally {
        setLoading(false);
      }
    },
    [handleUnauthorized]
  );

  useEffect(() => {
    const t = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!t) {
      router.replace("/auth");
      return;
    }
    setToken(t);
    void loadBundle(t);
  }, [router, loadBundle]);

  // POST /payments/setup-intent, then mount the Payment Element against the
  // returned client_secret.
  async function beginAddCard() {
    if (!token || opening) return;
    setOpening(true);
    setOpenError(null);
    try {
      const { client_secret } = await paymentsApi.setupIntent(token);
      if (!client_secret) {
        setOpenError("Payment provider is not configured. Please try again later.");
        return;
      }
      setClientSecret(client_secret);
    } catch (err) {
      if (isUnauthorized(err)) {
        handleUnauthorized();
        return;
      }
      setOpenError(errorMessage(err));
    } finally {
      setOpening(false);
    }
  }

  function handleSaved() {
    // The confirmed SetupIntent is consumed — drop its secret so the next
    // "add" mints a fresh one.
    setClientSecret(null);
    setSavedCount((n) => n + 1);
  }

  const stub = bundle ? isStubBundle(bundle) : false;

  return (
    <>
      <Header />
      <main className="flex-1 w-full max-w-2xl mx-auto px-4 py-8">
        <Link
          href="/account"
          className="inline-flex items-center gap-1.5 text-sm text-dark-400 hover:text-white transition-colors mb-4"
        >
          <ArrowLeft className="w-4 h-4" aria-hidden="true" />
          Account
        </Link>
        <h1 className="text-3xl font-extrabold mb-6">Payment methods</h1>

        {loading ? (
          <div className="space-y-4" aria-hidden="true">
            {Array.from({ length: 2 }).map((_, i) => (
              <div key={i} className="card p-6 animate-pulse space-y-4">
                <div className="h-5 bg-dark-800 rounded w-1/3" />
                <div className="h-4 bg-dark-800 rounded w-2/3" />
                <div className="h-10 bg-dark-800 rounded-xl" />
              </div>
            ))}
          </div>
        ) : loadError ? (
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load payment methods</h2>
            <p className="text-dark-400 mb-6">{loadError}</p>
            <button
              onClick={() => token && loadBundle(token)}
              className="btn-primary inline-block"
            >
              Retry
            </button>
          </div>
        ) : stub ? (
          // Dev-stub mode — mirrors the iOS stubState copy.
          <div className="card p-12 text-center">
            <Wrench className="w-16 h-16 text-dark-600 mx-auto mb-4" aria-hidden="true" />
            <h2 className="text-xl font-bold mb-2">Payments not configured</h2>
            <p className="text-dark-400">
              The server is running without Stripe keys, so there&apos;s nothing to manage here
              yet.
            </p>
          </div>
        ) : bundle ? (
          <div className="space-y-4">
            {savedCount > 0 && (
              <div
                className="bg-brand-900/20 border border-brand-700 text-brand-400 rounded-xl px-4 py-3 text-sm flex items-center gap-2"
                role="status"
              >
                <ShieldCheck className="w-4 h-4 shrink-0" aria-hidden="true" />
                {savedCount === 1 ? "Card saved." : `${savedCount} cards saved.`} It&apos;ll be
                available the next time you check out.
              </div>
            )}

            {clientSecret && stripePromise ? (
              <div className="card p-6">
                <h2 className="text-lg font-bold mb-1">Add a card</h2>
                <p className="text-sm text-dark-400 mb-5">
                  Your card is saved for future orders — nothing is charged now.
                </p>
                <Elements
                  key={clientSecret}
                  stripe={stripePromise}
                  options={{
                    clientSecret,
                    appearance: { theme: "night" as const, labels: "floating" as const },
                  }}
                >
                  <AddCardForm
                    onSaved={handleSaved}
                    onCancel={() => {
                      setClientSecret(null);
                      setOpenError(null);
                    }}
                  />
                </Elements>
              </div>
            ) : (
              <div className="card p-6">
                <div className="flex items-center gap-3 mb-4">
                  <div className="w-11 h-11 rounded-full bg-brand-900/40 flex items-center justify-center shrink-0">
                    <CreditCard className="w-5 h-5 text-brand-400" aria-hidden="true" />
                  </div>
                  <div>
                    <h2 className="font-bold">Saved cards</h2>
                    <p className="text-sm text-dark-400">
                      Cards you save are available at checkout here and in the app.
                    </p>
                  </div>
                </div>
                {openError && (
                  <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 mb-3 text-sm">
                    {openError}
                  </div>
                )}
                <button
                  type="button"
                  onClick={() => void beginAddCard()}
                  disabled={opening}
                  className="btn-primary w-full flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {opening ? (
                    <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />
                  ) : (
                    <Plus className="w-4 h-4" aria-hidden="true" />
                  )}
                  {opening ? "Preparing…" : "Add payment method"}
                </button>
              </div>
            )}

            <p className="text-xs text-dark-500 text-center flex items-center justify-center gap-1.5 px-6">
              <Lock className="w-3.5 h-3.5 shrink-0" aria-hidden="true" />
              Payments are processed securely by Stripe. KosherEats never sees your full card
              number.
            </p>
          </div>
        ) : null}
      </main>
    </>
  );
}
