"use client";

// Seller dashboard (R1 stub). Renders today's stats so the shell is
// verifiable end-to-end after login; a later round builds the full
// dashboard (live orders feed, open/closed toggle, charts).

import { useCallback, useEffect, useState } from "react";
import { CookingPot, DollarSign, ReceiptText, Timer } from "lucide-react";
import { formatCents, sellerApi } from "@/lib/sellerApi";
import type { DashboardStats } from "@/types/seller";

export default function SellerDashboardPage() {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setStats(await sellerApi.dashboard.stats());
    } catch (err) {
      setError((err as Error).message || "Failed to load dashboard");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Dashboard</h1>

      {loading ? (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="card p-5 h-28 animate-pulse" aria-hidden="true" />
          ))}
        </div>
      ) : error ? (
        <div className="card p-8 text-center">
          <p className="text-red-400 mb-4">{error}</p>
          <button onClick={load} className="btn-secondary">
            Try again
          </button>
        </div>
      ) : stats ? (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard
            icon={<ReceiptText className="w-5 h-5" />}
            label="Orders today"
            value={String(stats.today_orders)}
          />
          <StatCard
            icon={<DollarSign className="w-5 h-5" />}
            label="Revenue today"
            value={formatCents(stats.today_revenue)}
          />
          <StatCard
            icon={<CookingPot className="w-5 h-5" />}
            label="Active orders"
            value={String(stats.active_orders)}
          />
          <StatCard
            icon={<Timer className="w-5 h-5" />}
            label="Avg prep time"
            value={`${Math.round(stats.avg_prep_time)} min`}
          />
        </div>
      ) : null}
    </div>
  );
}

function StatCard({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="card p-5">
      <div className="flex items-center gap-2 text-dark-400 text-sm mb-3">
        <span className="text-brand-500">{icon}</span>
        {label}
      </div>
      <div className="text-2xl font-bold">{value}</div>
    </div>
  );
}
