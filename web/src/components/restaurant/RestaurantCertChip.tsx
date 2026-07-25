"use client";

import { KosherBadge } from "@/components/restaurant/KosherBadge";
import { restaurants as restaurantsApi } from "@/lib/api";
import type { Restaurant } from "@/types";
import { useEffect, useState } from "react";

// The order payload doesn't carry the restaurant's kosher fields (backend
// models.Order has no certification columns), so trust-continuity surfaces —
// order confirmation/tracking and order-history rows — join them client-side
// from the public GET /restaurants/{id}. This chip owns that join: it renders
// the same KosherBadge the cards and the cart header wear, or nothing while
// loading / when the restaurant can't be fetched (the chip is reassurance,
// never a blocker).
type CertInfo = Pick<Restaurant, "kosher_certification" | "is_glatt_kosher">;

// Module-scope cache: an order-history list repeats the same restaurants, so
// fetch each id once and render repeat rows instantly. Entries carry a short
// TTL so a certification change on the backend can't show stale for a whole
// long-lived session — after 5 minutes the next mount refetches.
const CERT_CACHE_TTL_MS = 5 * 60 * 1000;
const certCache = new Map<string, { info: CertInfo; fetchedAt: number }>();
const inflight = new Map<string, Promise<CertInfo | null>>();

// Returns the cached cert only while fresh; evicts and returns null once the
// TTL has lapsed so callers fall through to a refetch.
function getFreshCert(restaurantId: string): CertInfo | null {
  const entry = certCache.get(restaurantId);
  if (!entry) return null;
  if (Date.now() - entry.fetchedAt > CERT_CACHE_TTL_MS) {
    certCache.delete(restaurantId);
    return null;
  }
  return entry.info;
}

function loadCert(restaurantId: string): Promise<CertInfo | null> {
  const cached = getFreshCert(restaurantId);
  if (cached) return Promise.resolve(cached);

  const pending = inflight.get(restaurantId);
  if (pending) return pending;

  const p = (restaurantsApi.get(restaurantId) as Promise<Restaurant>)
    .then((r) => {
      const info: CertInfo = {
        kosher_certification: r.kosher_certification,
        is_glatt_kosher: r.is_glatt_kosher,
      };
      certCache.set(restaurantId, { info, fetchedAt: Date.now() });
      return info;
    })
    .catch(() => null) // non-fatal — the surface renders without the chip
    .finally(() => {
      inflight.delete(restaurantId);
    });
  inflight.set(restaurantId, p);
  return p;
}

export function RestaurantCertChip({
  restaurantId,
  size = "compact",
}: {
  restaurantId: string;
  size?: "compact" | "regular";
}) {
  const [cert, setCert] = useState<CertInfo | null>(() => getFreshCert(restaurantId));

  useEffect(() => {
    let alive = true;
    void loadCert(restaurantId).then((info) => {
      if (alive) setCert(info);
    });
    return () => {
      alive = false;
    };
  }, [restaurantId]);

  if (!cert) return null;
  return <KosherBadge restaurant={cert} size={size} />;
}
