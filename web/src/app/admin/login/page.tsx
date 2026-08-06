"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { adminApi, adminAuth } from "@/lib/adminApi";

export default function AdminLoginPage() {
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
      const res = await adminApi.login(email, password);
      if (res.user.role !== "admin") {
        throw new Error("This account is not an admin account.");
      }
      adminAuth.save(res.token);
      router.replace("/admin");
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center p-6">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="text-brand-500 text-3xl font-bold">KosherEats</div>
          <div className="text-dark-400 mt-2">Admin dashboard</div>
        </div>

        <form onSubmit={onSubmit} className="bg-dark-900 border border-dark-800 rounded-xl p-6 space-y-4">
          <div>
            <label className="block text-sm text-dark-400 mb-2">Email</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full bg-dark-800 border border-dark-700 rounded-lg px-4 py-3 text-white focus:border-brand-500 focus:outline-none"
              required
            />
          </div>
          <div>
            <label className="block text-sm text-dark-400 mb-2">Password</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full bg-dark-800 border border-dark-700 rounded-lg px-4 py-3 text-white focus:border-brand-500 focus:outline-none"
              required
            />
          </div>

          {error && <div className="text-danger-400 text-sm">{error}</div>}

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-brand-500 hover:bg-brand-600 text-white font-semibold rounded-lg py-3 transition disabled:opacity-50"
          >
            {loading ? "Signing in…" : "Sign in"}
          </button>
        </form>

        {process.env.NODE_ENV === "development" && (
          <p className="text-xs text-dark-600 text-center mt-6">
            Dev login: admin@koshereats.dev / adminpass
          </p>
        )}
      </div>
    </div>
  );
}
