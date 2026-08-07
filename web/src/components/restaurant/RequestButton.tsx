"use client";

import { restaurants as restaurantsApi } from "@/lib/api";
import type { RestaurantRequestState } from "@/types";
import { Heart } from "lucide-react";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

// State + toggle logic for the "Request restaurant" heart on a preview
// listing. Optimistic flip, reconciled with the server's response; reverted
// on failure. Requests are auth-gated, so a signed-out tap routes to /auth
// (same pattern as add-to-cart).
export function useRestaurantRequest(
  restaurantId: string,
  initialRequested: boolean,
  initialCount: number
) {
  const router = useRouter();
  const [requested, setRequested] = useState(initialRequested);
  const [count, setCount] = useState(initialCount);
  const [busy, setBusy] = useState(false);

  // Re-sync when the restaurant record (re)loads — detail pages fetch async,
  // so the initial values arrive after mount.
  useEffect(() => {
    setRequested(initialRequested);
    setCount(initialCount);
  }, [initialRequested, initialCount]);

  const toggle = useCallback(async () => {
    if (!restaurantId || busy) return;
    const token = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!token) {
      router.push("/auth");
      return;
    }

    const prevRequested = requested;
    const prevCount = count;
    // Optimistic flip; the response is authoritative.
    setRequested(!prevRequested);
    setCount(Math.max(0, prevCount + (prevRequested ? -1 : 1)));
    setBusy(true);
    try {
      const state = (await restaurantsApi.request(token, restaurantId)) as RestaurantRequestState;
      setRequested(state.requested);
      setCount(state.request_count);
    } catch (err) {
      setRequested(prevRequested);
      setCount(prevCount);
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        router.push("/auth");
      }
    } finally {
      setBusy(false);
    }
  }, [restaurantId, busy, requested, count, router]);

  return { requested, count, busy, toggle };
}

// The heart pill itself. Purely presentational — pair it with
// useRestaurantRequest. preventDefault/stopPropagation so it works inside a
// Link-wrapped card without navigating.
export function RequestButton({
  requested,
  count,
  busy,
  onToggle,
  label,
}: {
  requested: boolean;
  count: number;
  busy: boolean;
  onToggle: () => void;
  label?: string;
}) {
  return (
    <button
      type="button"
      onClick={(e) => {
        e.preventDefault();
        e.stopPropagation();
        onToggle();
      }}
      disabled={busy}
      aria-pressed={requested}
      aria-label={requested ? "Retract restaurant request" : "Request this restaurant"}
      className={`inline-flex items-center gap-2 rounded-full px-4 py-2 text-sm font-semibold border transition-colors disabled:opacity-50 ${
        requested
          ? "bg-brand-500/20 text-brand-400 border-brand-500"
          : "bg-dark-800/90 text-dark-300 border-dark-700 hover:border-brand-500 hover:text-brand-400"
      }`}
    >
      <Heart className="w-4 h-4" fill={requested ? "currentColor" : "none"} />
      {label ?? (requested ? "Requested" : "Request")}
      {count > 0 && <span className={requested ? "text-brand-400" : "text-dark-400"}>{count}</span>}
    </button>
  );
}
