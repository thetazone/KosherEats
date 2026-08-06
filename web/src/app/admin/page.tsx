"use client";

import { useEffect, useState } from "react";
import { adminApi, AdminStats, formatCents } from "@/lib/adminApi";

/**
 * Admin dashboard home — high-level stats across the whole platform.
 * Pulls GET /admin/stats which aggregates live counts in a single query.
 */
export default function AdminDashboard() {
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    adminApi.stats().then(setStats).catch((e) => setError(e.message));
  }, []);

  if (error) {
    return <div className="text-danger-400">Failed to load stats: {error}</div>;
  }
  if (!stats) {
    return <div className="text-dark-500">Loading…</div>;
  }

  const cards: { label: string; value: string; accent: string; sub?: string }[] = [
    {
      label: "Restaurants",
      value: stats.active_restaurants.toString(),
      sub: `${stats.total_restaurants} total`,
      accent: "text-brand-400",
    },
    {
      label: "Approved Couriers",
      value: stats.approved_couriers.toString(),
      sub: stats.pending_couriers > 0 ? `${stats.pending_couriers} awaiting review` : "none pending",
      accent: stats.pending_couriers > 0 ? "text-warning-400" : "text-success-400",
    },
    {
      label: "Today's Orders",
      value: stats.today_orders.toString(),
      sub: `${stats.lifetime_orders} lifetime`,
      accent: "text-info-400",
    },
    {
      label: "Today's Revenue",
      value: formatCents(stats.today_revenue),
      sub: "across all restaurants",
      accent: "text-success-400",
    },
  ];

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-3xl font-bold">Dashboard</h1>
        <p className="text-dark-400 mt-1">Platform overview at a glance.</p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {cards.map((card) => (
          <div key={card.label} className="bg-dark-900 border border-dark-800 rounded-xl p-6">
            <div className="text-sm text-dark-400">{card.label}</div>
            <div className={`mt-2 text-3xl font-bold ${card.accent}`}>{card.value}</div>
            {card.sub && <div className="text-xs text-dark-500 mt-1">{card.sub}</div>}
          </div>
        ))}
      </div>

      {stats.pending_couriers > 0 && (
        <div className="bg-warning-500/10 border border-warning-500/30 rounded-xl p-4">
          <div className="flex items-center gap-2 text-warning-400 font-semibold">
            ⚠ {stats.pending_couriers} courier{stats.pending_couriers === 1 ? "" : "s"} waiting for approval
          </div>
          <a href="/admin/couriers" className="text-warning-300 text-sm underline mt-1 inline-block">
            Review them →
          </a>
        </div>
      )}
    </div>
  );
}
