"use client";

import { useEffect, useState } from "react";
import { adminApi, AdminOrder, formatCents } from "@/lib/adminApi";

/**
 * Admin orders overview — last 100 orders across the platform. Auto-refreshes
 * every 15s so an admin watching the dashboard sees new orders come in live.
 *
 * A transient poll failure must NOT brick the dashboard: once we have a good
 * table, a failed refresh keeps the last good data on screen behind a small
 * "reconnecting…" banner, and the next successful poll clears it. Only the
 * very first load (no data yet) shows the full-screen failure state. Polling
 * pauses while the tab is hidden and resumes on focus to avoid pointless
 * background fetches.
 */
export default function OrdersPage() {
  const [orders, setOrders] = useState<AdminOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hasData, setHasData] = useState(false);

  async function load() {
    try {
      const data = await adminApi.orders();
      setOrders(data);
      setHasData(true);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();

    let timer: ReturnType<typeof setInterval> | null = null;
    const start = () => {
      if (timer === null) timer = setInterval(load, 15_000);
    };
    const stop = () => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };
    const onVisibility = () => {
      if (document.hidden) {
        stop();
      } else {
        // Refresh immediately on return so the table isn't 15s stale.
        load();
        start();
      }
    };

    if (!document.hidden) start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, []);

  if (loading) return <div className="text-dark-500">Loading orders…</div>;
  // Only block the whole view when the very first load failed and we have no
  // good data to show. After that, errors surface as a non-blocking banner.
  if (error && !hasData)
    return (
      <div className="space-y-4">
        <div className="text-danger-400">Failed: {error}</div>
        <button
          onClick={load}
          className="text-sm px-3 py-1.5 rounded bg-dark-800 hover:bg-dark-700 text-dark-200 transition"
        >
          Retry
        </button>
      </div>
    );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold">Orders</h1>
        <p className="text-dark-400 mt-1">
          Last 100 orders across the platform • refreshes every 15s
        </p>
      </div>

      {error && (
        <div className="flex items-center justify-between gap-4 px-4 py-2 rounded-lg border border-warning-500/30 bg-warning-500/10 text-warning-300 text-sm">
          <span>Reconnecting… showing last loaded orders.</span>
          <button
            onClick={load}
            className="text-xs px-2 py-1 rounded bg-warning-500/20 hover:bg-warning-500/30 text-warning-200 transition"
          >
            Retry
          </button>
        </div>
      )}

      <div className="bg-dark-900 border border-dark-800 rounded-xl overflow-hidden">
        <table className="w-full">
          <thead className="bg-dark-800/50">
            <tr>
              <th className="text-left px-4 py-3 text-xs text-dark-400 uppercase">Order</th>
              <th className="text-left px-4 py-3 text-xs text-dark-400 uppercase">Restaurant</th>
              <th className="text-left px-4 py-3 text-xs text-dark-400 uppercase">Delivery Address</th>
              <th className="text-left px-4 py-3 text-xs text-dark-400 uppercase">Status</th>
              <th className="text-right px-4 py-3 text-xs text-dark-400 uppercase">Total</th>
              <th className="text-right px-4 py-3 text-xs text-dark-400 uppercase">Tip</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-dark-800">
            {orders.map((o) => (
              <tr key={o.id} className="hover:bg-dark-800/30 transition">
                <td className="px-4 py-3">
                  <div className="font-mono text-xs text-dark-500">#{o.id.substring(0, 8)}</div>
                  <div className="text-xs text-dark-600 mt-0.5">
                    {new Date(o.created_at).toLocaleString()}
                  </div>
                </td>
                <td className="px-4 py-3 text-sm">{o.restaurant_name}</td>
                <td className="px-4 py-3 text-sm text-dark-400 max-w-xs truncate">{o.delivery_address}</td>
                <td className="px-4 py-3">
                  <OrderStatusPill status={o.status} />
                </td>
                <td className="px-4 py-3 text-right font-semibold">{formatCents(o.total)}</td>
                <td className="px-4 py-3 text-right text-sm text-dark-400">
                  {o.courier_tip > 0 ? formatCents(o.courier_tip) : "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function OrderStatusPill({ status }: { status: string }) {
  const map: Record<string, string> = {
    pending: "bg-dark-500/20 text-dark-300",
    accepted: "bg-info-500/20 text-info-300",
    preparing: "bg-warning-500/20 text-warning-300",
    ready: "bg-brand-500/20 text-brand-300",
    picked_up: "bg-transit-500/20 text-transit-300",
    delivered: "bg-success-500/20 text-success-300",
    cancelled: "bg-danger-500/20 text-danger-300",
    rejected: "bg-danger-500/20 text-danger-300",
  };
  return (
    <span className={`text-xs px-2 py-1 rounded font-medium ${map[status] || "bg-dark-500/20 text-dark-300"}`}>
      {status.replace("_", " ")}
    </span>
  );
}
