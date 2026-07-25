"use client";

// Consumer Deals page — mirrors the iOS DealsView ("Deals Near You" tab).
// GET /deals/nearby returns active, non-expired deals (server-interleaved so
// one restaurant doesn't dominate). Geolocation is a client-side enhancement:
// the endpoint takes no coords, so when the browser grants a position we
// fetch the nearest-first /restaurants list, join restaurant coordinates by
// id, and re-sort the deals nearest-first. Denied/unsupported geolocation
// falls back to the server's expiry-interleaved order.

import { Header } from "@/components/layout/Header";
import { deals as dealsApi, restaurants as restaurantsApi } from "@/lib/api";
import { formatUSD } from "@/lib/format";
import type { Deal } from "@/types";
import { Clock, MapPin, Tag } from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";

type Coords = { lat: number; lng: number };

// Great-circle distance in km — same helper the /search page uses for its
// client-side "Nearest First" sort.
function distanceKm(a: Coords, b: { lat: number; lng: number }): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
}

// Discount badge copy — mirrors the iOS Deal.discountBadge computed property
// (and the restaurant page's dealBadge).
function dealBadge(deal: Deal): string {
  switch (deal.discount_type) {
    case "percentage":
      return `${deal.discount_value}% Off`;
    case "fixed":
      return `${formatUSD(deal.discount_value)} Off`;
    case "bogo":
      return "Buy 1 Get 1 Free";
    default:
      return "";
  }
}

// "3d left" / "5h left" / "12m left" — mirrors the iOS ExpiryLabel. Returns
// null for past or unparseable timestamps (the row is simply omitted).
function timeLeft(expiresAt: string): string | null {
  const expiry = new Date(expiresAt).getTime();
  if (!Number.isFinite(expiry)) return null;
  const diffMs = expiry - Date.now();
  if (diffMs <= 0) return null;
  const mins = Math.floor(diffMs / 60_000);
  const hours = Math.floor(mins / 60);
  const days = Math.floor(hours / 24);
  if (days > 0) return `${days}d left`;
  if (hours > 0) return `${hours}h left`;
  if (mins > 0) return `${mins}m left`;
  return null;
}

// Client-side expiry guard (iOS DealsViewModel.isExpired parity): the server
// already filters, but a stale cached response shouldn't show a dead deal.
// Unparseable expiry keeps the deal visible rather than hiding it.
function isExpired(deal: Deal): boolean {
  const expiry = new Date(deal.expires_at).getTime();
  return Number.isFinite(expiry) && expiry <= Date.now();
}

// The deal's display image — deal image first, then the linked menu item's,
// then the restaurant's (iOS Deal.displayImageUrl).
function displayImageUrl(deal: Deal): string | null {
  return deal.image_url || deal.menu_item_image_url || deal.restaurant_image_url || null;
}

const KM_PER_MILE = 1.60934;

function DealListCard({ deal, miles }: { deal: Deal; miles: number | null }) {
  const badge = dealBadge(deal);
  const remaining = timeLeft(deal.expires_at);
  const image = displayImageUrl(deal);
  return (
    <Link
      href={`/restaurant/${deal.restaurant_id}?deal=${deal.id}`}
      className="card group block hover:border-dark-600 transition-all duration-200"
      aria-label={`${deal.title} at ${deal.restaurant_name}`}
    >
      {/* Image */}
      <div className="relative h-40 bg-dark-800 overflow-hidden">
        {image ? (
          <Image
            src={image}
            alt={deal.title}
            fill
            sizes="(max-width: 768px) 100vw, 33vw"
            className="object-cover"
          />
        ) : (
          <div className="absolute inset-0 bg-gradient-to-br from-brand-900/40 to-dark-800 flex items-center justify-center">
            <Tag className="w-10 h-10 text-dark-600" aria-hidden="true" />
          </div>
        )}
        <div className="absolute inset-0 bg-gradient-to-t from-dark-900/80 to-transparent z-10" />
        {badge && (
          <span className="absolute top-3 left-3 z-20 bg-brand-500 text-white text-xs font-bold px-2 py-1 rounded-lg">
            {badge}
          </span>
        )}
        {remaining && (
          <span className="absolute top-3 right-3 z-20 flex items-center gap-1 bg-dark-900/80 text-amber-400 text-xs font-medium px-2 py-1 rounded-lg">
            <Clock className="w-3 h-3" aria-hidden="true" />
            {remaining}
          </span>
        )}
      </div>

      {/* Info */}
      <div className="p-4">
        <h3 className="font-bold text-lg group-hover:text-brand-400 transition-colors line-clamp-1">
          {deal.title}
        </h3>
        <div className="flex items-center gap-2 mt-1 text-sm text-dark-400">
          <span className="truncate">{deal.restaurant_name}</span>
          {miles !== null && (
            <>
              <span className="text-dark-600">·</span>
              <span className="flex items-center gap-0.5 flex-shrink-0">
                <MapPin className="w-3.5 h-3.5" aria-hidden="true" />
                {miles.toFixed(1)} mi
              </span>
            </>
          )}
        </div>
        {deal.description && (
          <p className="text-dark-400 text-sm mt-2 line-clamp-2">{deal.description}</p>
        )}
        <div className="text-xs text-dark-500 mt-2">
          {deal.menu_item_name && <span>On {deal.menu_item_name}</span>}
          {deal.menu_item_name && deal.min_order_amount != null && deal.min_order_amount > 0 && (
            <span> · </span>
          )}
          {deal.min_order_amount != null && deal.min_order_amount > 0 && (
            <span>Min. order {formatUSD(deal.min_order_amount)}</span>
          )}
        </div>
      </div>
    </Link>
  );
}

export default function DealsPage() {
  const [dealsList, setDealsList] = useState<Deal[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // null until granted; stays null on denial/unsupported — the page degrades
  // to the server's expiry-interleaved order.
  const [coords, setCoords] = useState<Coords | null>(null);
  // restaurant_id → distance in miles, joined from the nearest-first
  // /restaurants list once geolocation resolves. null until available.
  const [distances, setDistances] = useState<Map<string, number> | null>(null);

  const loadDeals = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const token =
        typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
      const ds = await dealsApi.nearby(token ?? undefined);
      setDealsList(ds.filter((d) => !isExpired(d)));
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : "Failed to load deals");
    } finally {
      setLoading(false);
    }
  }, []);

  // Mount: fetch deals, and ask for a position in parallel. Geolocation is a
  // pure enhancement — every failure path silently keeps the fallback order.
  useEffect(() => {
    void loadDeals();
    if ("geolocation" in navigator) {
      navigator.geolocation.getCurrentPosition(
        (pos) => setCoords({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
        () => {
          // Denied or unavailable — keep the server order.
        },
        { maximumAge: 300_000, timeout: 10_000 }
      );
    }
  }, [loadDeals]);

  // When a position lands, join restaurant coordinates from the distance-
  // ordered /restaurants list (LIMIT 50 nearest). Deals whose restaurant
  // falls outside that window sort after the located ones, keeping the
  // server's order among themselves.
  useEffect(() => {
    if (!coords) return;
    let cancelled = false;
    restaurantsApi
      .list(coords)
      .then((rs) => {
        if (cancelled) return;
        const map = new Map<string, number>();
        for (const r of rs) {
          map.set(r.id, distanceKm(coords, r) / KM_PER_MILE);
        }
        setDistances(map);
      })
      .catch(() => {
        // Non-critical — leave the fallback order in place.
      });
    return () => {
      cancelled = true;
    };
  }, [coords]);

  const sortedDeals = useMemo(() => {
    if (!distances) return dealsList;
    return [...dealsList].sort((a, b) => {
      const da = distances.get(a.restaurant_id) ?? Number.POSITIVE_INFINITY;
      const db = distances.get(b.restaurant_id) ?? Number.POSITIVE_INFINITY;
      if (da !== db) return da - db;
      // Same restaurant / both unlocated — soonest-expiring first.
      return new Date(a.expires_at).getTime() - new Date(b.expires_at).getTime();
    });
  }, [dealsList, distances]);

  return (
    <>
      <Header />
      <main className="flex-1 max-w-7xl mx-auto px-4 py-8 w-full">
        <h1 className="text-3xl font-extrabold mb-2">Deals Near You</h1>
        <p className="text-dark-400 mb-8">
          {distances
            ? "Limited-time offers, nearest first."
            : "Limited-time offers from kosher restaurants."}
        </p>

        {loading ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="card overflow-hidden animate-pulse">
                <div className="h-40 bg-dark-800" />
                <div className="p-4 space-y-3">
                  <div className="h-5 w-2/3 bg-dark-800 rounded" />
                  <div className="h-4 w-1/2 bg-dark-800 rounded" />
                  <div className="h-4 w-1/3 bg-dark-800 rounded" />
                </div>
              </div>
            ))}
          </div>
        ) : loadError ? (
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load deals</h2>
            <p className="text-dark-400 mb-6">{loadError}</p>
            <button onClick={() => void loadDeals()} className="btn-primary inline-block">
              Retry
            </button>
          </div>
        ) : sortedDeals.length === 0 ? (
          <div className="card p-12 text-center">
            <Tag className="w-16 h-16 text-dark-600 mx-auto mb-4" aria-hidden="true" />
            <h2 className="text-xl font-bold mb-2">No deals right now</h2>
            <p className="text-dark-400 mb-6">
              Check back later for deals from nearby restaurants.
            </p>
            <Link href="/search" className="btn-primary inline-block">
              Browse Restaurants
            </Link>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {sortedDeals.map((deal) => (
              <DealListCard
                key={deal.id}
                deal={deal}
                miles={distances?.get(deal.restaurant_id) ?? null}
              />
            ))}
          </div>
        )}
      </main>
    </>
  );
}
