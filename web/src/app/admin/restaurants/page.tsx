"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { adminApi, AdminRestaurant, formatCents } from "@/lib/adminApi";

/**
 * Admin restaurants list. Shows every restaurant on the platform with
 * a link to create a new one. Open/closed and active/inactive flags are
 * shown inline so the admin can spot dormant listings.
 */
export default function RestaurantsPage() {
  const [restaurants, setRestaurants] = useState<AdminRestaurant[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  useEffect(() => {
    adminApi
      .restaurants()
      .then(setRestaurants)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, []);

  async function override(r: AdminRestaurant, status: "approved" | "rejected" | "pending") {
    const current = r.approval_status ?? "approved";
    if (status === current) return;
    const verb = status === "pending" ? "reset to pending" : status;
    let notes = "";
    if (status === "rejected" || (status === "approved" && current === "rejected")) {
      const prompt = status === "approved"
        ? `Reverse the rejection of ${r.name}? Optional notes for the seller:`
        : `Reject ${r.name}? Optional notes for the seller:`;
      const entered = window.prompt(prompt, "");
      if (entered === null) return;
      notes = entered;
    } else if (!window.confirm(`${verb[0].toUpperCase() + verb.slice(1)} ${r.name}?`)) {
      return;
    }
    setBusyId(r.id);
    try {
      await adminApi.setRestaurantApproval(r.id, status, notes);
      const fresh = await adminApi.restaurants();
      setRestaurants(fresh);
    } catch (e) {
      window.alert((e as Error).message);
    } finally {
      setBusyId(null);
    }
  }

  if (loading) return <div className="text-neutral-500">Loading…</div>;
  if (error) return <div className="text-red-400">Failed: {error}</div>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Restaurants</h1>
          <p className="text-neutral-400 mt-1">{restaurants.length} total</p>
        </div>
        <Link
          href="/admin/restaurants/new"
          className="bg-orange-500 hover:bg-orange-600 text-white font-semibold rounded-lg px-4 py-2 transition"
        >
          + New Restaurant
        </Link>
      </div>

      <div className="bg-neutral-900 border border-neutral-800 rounded-xl overflow-hidden">
        <table className="w-full">
          <thead className="bg-neutral-800/50">
            <tr>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Name</th>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Cert</th>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Location</th>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Delivery Fee</th>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Rating</th>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Status</th>
              <th className="text-left px-4 py-3 text-xs text-neutral-400 uppercase">Approval</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-neutral-800">
            {restaurants.map((r) => {
              const approval = r.approval_status ?? "approved";
              const approvalColor =
                approval === "approved"
                  ? "bg-green-500/20 text-green-400"
                  : approval === "rejected"
                  ? "bg-red-500/20 text-red-400"
                  : "bg-amber-500/20 text-amber-300";
              const busy = busyId === r.id;
              return (
                <tr key={r.id} className="hover:bg-neutral-800/30 transition">
                  <td className="px-4 py-3">
                    <div className="font-medium">{r.name}</div>
                    <div className="text-xs text-neutral-500">{r.cuisine_type.join(" • ")}</div>
                  </td>
                  <td className="px-4 py-3 text-sm">
                    {r.kosher_certification}
                    {r.is_glatt_kosher && <div className="text-xs text-orange-400">Glatt</div>}
                  </td>
                  <td className="px-4 py-3 text-sm">
                    {r.city}, {r.state}
                  </td>
                  <td className="px-4 py-3 text-sm">{formatCents(r.delivery_fee)}</td>
                  <td className="px-4 py-3 text-sm">
                    ★ {r.rating.toFixed(1)}{" "}
                    <span className="text-neutral-500">({r.review_count})</span>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex gap-1">
                      <span
                        className={`text-xs px-2 py-1 rounded ${
                          r.is_active ? "bg-green-500/20 text-green-400" : "bg-neutral-500/20 text-neutral-400"
                        }`}
                      >
                        {r.is_active ? "Active" : "Inactive"}
                      </span>
                      <span
                        className={`text-xs px-2 py-1 rounded ${
                          r.is_open ? "bg-blue-500/20 text-blue-400" : "bg-neutral-700/50 text-neutral-500"
                        }`}
                      >
                        {r.is_open ? "Open" : "Closed"}
                      </span>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-col gap-1">
                      <span className={`text-xs px-2 py-1 rounded w-fit ${approvalColor}`}>{approval}</span>
                      <div className="flex gap-1 text-xs">
                        {approval !== "approved" && (
                          <button
                            disabled={busy}
                            onClick={() => override(r, "approved")}
                            className="px-2 py-0.5 rounded bg-green-600/80 hover:bg-green-600 text-white disabled:opacity-50"
                          >
                            Approve
                          </button>
                        )}
                        {approval !== "rejected" && (
                          <button
                            disabled={busy}
                            onClick={() => override(r, "rejected")}
                            className="px-2 py-0.5 rounded bg-red-600/80 hover:bg-red-600 text-white disabled:opacity-50"
                          >
                            Reject
                          </button>
                        )}
                        {approval !== "pending" && (
                          <button
                            disabled={busy}
                            onClick={() => override(r, "pending")}
                            className="px-2 py-0.5 rounded bg-neutral-700 hover:bg-neutral-600 text-white disabled:opacity-50"
                          >
                            Reset
                          </button>
                        )}
                      </div>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
