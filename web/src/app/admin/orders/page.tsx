"use client";

import { useEffect, useState } from "react";
import { adminApi, AdminOrder, formatCents } from "@/lib/adminApi";

/**
 * Admin orders overview — last 100 orders across the platform. Auto-refreshes
 * every 15s so an admin watching the dashboard sees new orders come in live.
 */
export default function OrdersPage() {
  const [orders, setOrders] = useState<AdminOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      const data = await adminApi.orders();
      setOrders(data);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    const t = setInterval(load, 15_000);
    return () => clearInterval(t);
  }, []);

  if (loading) return <div className="text-neutral-500">Loading orders…</div>;
  if (error) return <div className="text-red-400">Failed: {error}</div>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-3xl font-bold">Orders</h1>
        <p className="text-neutral-400 mt-1">
          Last 100 orders across the platform • refreshes every 15s
        </p>
      </div>

      <div className="bg-neutral-900 border border-neutral-800 rounded-xl overflow-hidden">
        <table className="w-full">
          <thead className="bg-neutral-800/50">
            <tr>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Order</th>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Restaurant</th>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Delivery Address</th>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Status</th>
              <th className="text-right px-4 py-3 text-xs text-neutral-400 uppercase">Total</th>
              <th className="text-right px-4 py-3 text-xs text-neutral-400 uppercase">Tip</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-neutral-800">
            {orders.map((o) => (
              <tr key={o.id} className="hover:bg-neutral-800/30 transition">
                <td className="px-4 py-3">
                  <div className="font-mono text-xs text-neutral-500">#{o.id.substring(0, 8)}</div>
                  <div className="text-xs text-neutral-600 mt-0.5">
                    {new Date(o.created_at).toLocaleString()}
                  </div>
                </td>
                <td className="px-4 py-3 text-sm">{o.restaurant_name}</td>
                <td className="px-4 py-3 text-sm text-neutral-400 max-w-xs truncate">{o.delivery_address}</td>
                <td className="px-4 py-3">
                  <OrderStatusPill status={o.status} />
                </td>
                <td className="px-4 py-3 text-right font-semibold">{formatCents(o.total)}</td>
                <td className="px-4 py-3 text-right text-sm text-neutral-400">
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
    pending: "bg-neutral-500/20 text-neutral-300",
    accepted: "bg-blue-500/20 text-blue-300",
    preparing: "bg-yellow-500/20 text-yellow-300",
    ready: "bg-orange-500/20 text-orange-300",
    picked_up: "bg-purple-500/20 text-purple-300",
    delivered: "bg-green-500/20 text-green-300",
    cancelled: "bg-red-500/20 text-red-300",
    rejected: "bg-red-500/20 text-red-300",
  };
  return (
    <span className={`text-xs px-2 py-1 rounded font-medium ${map[status] || "bg-neutral-500/20 text-neutral-300"}`}>
      {status.replace("_", " ")}
    </span>
  );
}
