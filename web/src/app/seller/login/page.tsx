"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { Store } from "lucide-react";
import { sellerApi, sellerAuth } from "@/lib/sellerApi";

/**
 * Seller sign-in. Hits the shared /auth/login endpoint scoped to role=seller
 * ((email, role) is the account key on the backend) and rejects any token
 * whose user isn't a seller so a consumer/admin credential can't land in the
 * seller dashboard with a role it can't use.
 */
export default function SellerLoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const res = await sellerApi.auth.login(email.trim(), password);
      if (res.user?.role !== "seller") {
        throw new Error("This account is not a seller account.");
      }
      sellerAuth.save(res);
      router.replace("/seller");
    } catch (err) {
      setError((err as Error).message || "Sign in failed. Please try again.");
      setLoading(false);
    }
  }

  return (
    <main className="min-h-screen flex items-center justify-center px-4 py-16">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-brand-500/15 text-brand-500 mb-4">
            <Store className="w-6 h-6" />
          </div>
          <h1 className="text-3xl font-extrabold">
            <span className="text-brand-500">Kosher</span>
            <span className="text-white">Eats</span>
          </h1>
          <p className="text-dark-400 mt-2">Seller dashboard</p>
        </div>

        {/* Error */}
        {error && (
          <div className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 mb-6 text-sm">
            {error}
          </div>
        )}

        {/* Form */}
        <form onSubmit={onSubmit} className="card p-6 space-y-4">
          <div>
            <label htmlFor="seller-email" className="block text-sm text-dark-300 mb-1.5">
              Email
            </label>
            <input
              id="seller-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="input w-full"
              placeholder="you@restaurant.com"
              autoComplete="email"
              required
            />
          </div>

          <div>
            <label htmlFor="seller-password" className="block text-sm text-dark-300 mb-1.5">
              Password
            </label>
            <input
              id="seller-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="input w-full"
              placeholder="••••••••"
              autoComplete="current-password"
              required
              minLength={8}
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="btn-primary w-full disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <p className="text-center text-dark-500 text-sm mt-8">
          Looking to order food?{" "}
          <Link href="/" className="text-brand-500 hover:text-brand-400 transition-colors">
            Go to KosherEats
          </Link>
        </p>
      </div>
    </main>
  );
}
