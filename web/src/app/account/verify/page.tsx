"use client";

import { VerificationGate } from "@/components/auth/VerificationGate";
import { Header } from "@/components/layout/Header";
import { user as userApi } from "@/lib/api";
import type { User } from "@/types";
import { ShieldCheck } from "lucide-react";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useState } from "react";

// Account verification gate page. The backend blocks consumer transactions
// (payments.intent, orders.create) with 403 "verification_required" until both
// email_verified and phone_verified are true on GET /user/profile — the cart
// routes here with ?next=/cart when it trips that gate. Also reachable
// directly for users who want to verify ahead of time.

// Only allow internal same-origin paths for the post-verify redirect —
// anything else (absolute URLs, protocol-relative "//host") is dropped.
function sanitizeNext(raw: string | null): string {
  if (raw && raw.startsWith("/") && !raw.startsWith("//")) return raw;
  return "/";
}

function VerifyAccount() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = sanitizeNext(searchParams.get("next"));
  const cameFromCheckout = searchParams.get("next") !== null;

  const [token, setToken] = useState<string | null>(null);
  const [profile, setProfile] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [completed, setCompleted] = useState(false);

  const loadProfile = useCallback(
    async (t: string) => {
      setLoading(true);
      setLoadError(null);
      try {
        const p = await userApi.getProfile(t);
        setProfile(p);
        // Keep the cached header user in sync (name/email/phone may have
        // changed through the verified-change flows).
        window.localStorage.setItem("user", JSON.stringify(p));
      } catch (err) {
        const msg = String(err instanceof Error ? err.message : err).toLowerCase();
        if (msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token")) {
          window.localStorage.removeItem("token");
          router.replace("/auth");
          return;
        }
        setLoadError(err instanceof Error ? err.message : "Failed to load your account");
      } finally {
        setLoading(false);
      }
    },
    [router]
  );

  useEffect(() => {
    const t = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!t) {
      router.replace("/auth");
      return;
    }
    setToken(t);
    void loadProfile(t);
  }, [router, loadProfile]);

  // Both legs done: silently refresh the cached profile so the rest of the
  // app (header, a retried checkout) sees the new flags/email/phone — without
  // flipping the loading skeleton over the success card.
  const handleComplete = useCallback(() => {
    setCompleted(true);
    if (!token) return;
    void userApi
      .getProfile(token)
      .then((p) => {
        setProfile(p);
        window.localStorage.setItem("user", JSON.stringify(p));
      })
      .catch(() => {
        // Non-fatal — verification already succeeded server-side.
      });
  }, [token]);

  const emailVerified = profile?.email_verified === true;
  const phoneVerified = profile?.phone_verified === true;
  const fullyVerified = completed || (emailVerified && phoneVerified);

  return (
    <>
      <Header />
      <main className="flex-1 w-full max-w-lg mx-auto px-4 py-8">
        <h1 className="text-3xl font-extrabold mb-2">Verify your account</h1>
        <p className="text-dark-400 mb-6">
          Confirm your email and phone number to secure your account.
        </p>

        {cameFromCheckout && !fullyVerified && !loading && !loadError && (
          <div
            className="card p-4 mb-6 border border-brand-700 bg-brand-900/10 text-sm text-dark-200"
            role="status"
          >
            Before you can place an order, we need to verify your contact info. It only takes a
            minute — we&apos;ll send you right back to checkout when you&apos;re done.
          </div>
        )}

        {loading ? (
          <div className="card p-6 animate-pulse space-y-4" aria-hidden="true">
            <div className="h-5 bg-dark-800 rounded w-1/3" />
            <div className="h-4 bg-dark-800 rounded w-2/3" />
            <div className="h-12 bg-dark-800 rounded-xl" />
            <div className="h-12 bg-dark-800 rounded-xl w-1/2" />
          </div>
        ) : loadError ? (
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load your account</h2>
            <p className="text-dark-400 mb-6">{loadError}</p>
            <button
              onClick={() => token && loadProfile(token)}
              className="btn-primary inline-block"
            >
              Retry
            </button>
          </div>
        ) : fullyVerified ? (
          <div className="card p-12 text-center">
            <ShieldCheck className="w-16 h-16 text-brand-400 mx-auto mb-4" aria-hidden="true" />
            <h2 className="text-xl font-bold mb-2">You&apos;re verified</h2>
            <p className="text-dark-400 mb-6">
              Your email and phone number are confirmed — you&apos;re all set to order.
            </p>
            <button onClick={() => router.push(next)} className="btn-primary inline-block">
              {next === "/cart" ? "Back to checkout" : "Continue"}
            </button>
          </div>
        ) : token && profile ? (
          <VerificationGate
            token={token}
            emailVerified={emailVerified}
            phoneVerified={phoneVerified}
            initialEmail={profile.email}
            onComplete={handleComplete}
          />
        ) : null}
      </main>
    </>
  );
}

export default function VerifyAccountPage() {
  // useSearchParams requires a Suspense boundary during prerender (same
  // pattern as /auth).
  return (
    <Suspense
      fallback={
        <>
          <Header />
          <main className="flex-1 w-full max-w-lg mx-auto px-4 py-8">
            <div className="card p-12 text-center text-dark-400">Loading…</div>
          </main>
        </>
      }
    >
      <VerifyAccount />
    </Suspense>
  );
}
