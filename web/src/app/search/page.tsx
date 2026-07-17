"use client";

import { Header } from "@/components/layout/Header";
import {
  EMPTY_KOSHER_FILTERS,
  KosherFilterPanel,
  matchesKosherFilters,
  type KosherFilters,
} from "@/components/restaurant/KosherFilterPanel";
import { RestaurantCard } from "@/components/restaurant/RestaurantCard";
import { favorites as favoritesApi, restaurants as restaurantsApi } from "@/lib/api";
import type { Restaurant } from "@/types";
import { useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";

type Coords = { lat: number; lng: number };
type SortBy = "distance" | "rating" | "delivery_time" | "delivery_fee";

// Great-circle distance in km. Used for the client-side "Nearest First" sort
// so proximity ordering survives kosher filtering and text search (the
// backend only distance-orders the unfiltered /restaurants list).
function distanceKm(a: Coords, b: { lat: number; lng: number }): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
}

function SearchPageInner() {
  const searchParams = useSearchParams();
  const urlQ = searchParams.get("q") ?? "";

  const [query, setQuery] = useState(urlQ);
  const [filters, setFilters] = useState<KosherFilters>(EMPTY_KOSHER_FILTERS);
  const [sortBy, setSortBy] = useState<SortBy>("rating");
  // Once the user picks a sort themselves we stop auto-switching to
  // "Nearest First" when geolocation resolves.
  const sortTouched = useRef(false);

  const [results, setResults] = useState<Restaurant[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [suggested, setSuggested] = useState<Restaurant[]>([]);

  const [token, setToken] = useState<string | null>(null);
  const [favIds, setFavIds] = useState<Set<string>>(new Set());
  // Per-restaurant in-flight guard so double-taps can't race the optimistic
  // toggle (mirrors iOS RestaurantStore.togglingIDs).
  const togglingIds = useRef<Set<string>>(new Set());

  // null until granted; stays null on denial/unsupported — every consumer of
  // coords degrades gracefully to the rating-ordered fallback.
  const [coords, setCoords] = useState<Coords | null>(null);

  // Guards against a slow earlier request overwriting a newer one's results.
  const requestSeq = useRef(0);

  // Keep the input in sync when the header SearchBar pushes a new ?q= while
  // this page is already mounted.
  useEffect(() => {
    setQuery(urlQ);
  }, [urlQ]);

  // Mount: hydrate auth, then kick off the non-critical extras — favorites
  // hearts, the suggested row, and geolocation. Each fails independently and
  // silently; none of them may break the core search experience.
  useEffect(() => {
    const t = window.localStorage.getItem("token");
    setToken(t);

    if (t) {
      favoritesApi
        .ids(t)
        .then((ids) => setFavIds(new Set(ids)))
        .catch(() => {
          // Logged-out/expired session — leave hearts empty.
        });
    }

    restaurantsApi
      .suggested({ limit: 12, token: t ?? undefined })
      .then(setSuggested)
      .catch(() => {
        // Non-critical — just hide the row.
      });

    if ("geolocation" in navigator) {
      navigator.geolocation.getCurrentPosition(
        (pos) => setCoords({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
        () => {
          // Denied or unavailable — keep the rating-ordered fallback.
        },
        { maximumAge: 300_000, timeout: 10_000 }
      );
    }
  }, []);

  // When geolocation resolves and the user hasn't picked a sort yet, default
  // to nearest-first (that's why we asked for their location).
  useEffect(() => {
    if (coords && !sortTouched.current) setSortBy("distance");
  }, [coords]);

  // Fetch the real discovery results for the current query. An empty/whitespace
  // query lists everything (distance-ordered when we have coords); a non-empty
  // query hits the search endpoint (which 400s on an empty `q`, so we must
  // branch here rather than always searching).
  const loadResults = useCallback(async (q: string, c: Coords | null) => {
    const seq = ++requestSeq.current;
    setLoading(true);
    setLoadError(null);
    try {
      const trimmed = q.trim();
      const data = trimmed
        ? await restaurantsApi.search(trimmed)
        : await restaurantsApi.list(c ?? undefined);
      if (seq !== requestSeq.current) return; // a newer request superseded this one
      setResults(data);
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setLoadError(err instanceof Error ? err.message : "Failed to load restaurants");
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, []);

  // Debounce query input (~300ms) so we don't fire a request per keystroke.
  // This also drives the initial load (empty query → full list) and the
  // re-fetch when geolocation resolves mid-session.
  useEffect(() => {
    const handle = setTimeout(() => {
      void loadResults(query, coords);
    }, 300);
    return () => clearTimeout(handle);
  }, [query, coords, loadResults]);

  // Optimistic favorite toggle with revert-on-failure (mirrors iOS
  // RestaurantStore.toggleFavorite).
  const toggleFavorite = useCallback(
    async (restaurantId: string) => {
      if (!token || togglingIds.current.has(restaurantId)) return;
      togglingIds.current.add(restaurantId);

      const wasFavorite = favIds.has(restaurantId);
      const flip = (fav: boolean) =>
        setFavIds((prev) => {
          const next = new Set(prev);
          if (fav) next.add(restaurantId);
          else next.delete(restaurantId);
          return next;
        });

      flip(!wasFavorite);
      try {
        if (wasFavorite) await favoritesApi.remove(token, restaurantId);
        else await favoritesApi.add(token, restaurantId);
      } catch {
        flip(wasFavorite); // revert
      } finally {
        togglingIds.current.delete(restaurantId);
      }
    },
    [token, favIds]
  );

  // Client-side refinement over the REAL results from the API.
  let filtered = results.filter((r) => matchesKosherFilters(r, filters));

  filtered = [...filtered].sort((a, b) => {
    if (sortBy === "distance") {
      // Server order is already nearest-first for the unfiltered list; the
      // haversine re-sort keeps that guarantee for search results too.
      if (!coords) return 0;
      return distanceKm(coords, a) - distanceKm(coords, b);
    }
    if (sortBy === "rating") return b.rating - a.rating;
    if (sortBy === "delivery_time") return a.est_delivery_min - b.est_delivery_min;
    return a.delivery_fee - b.delivery_fee;
  });

  const isBrowsing = query.trim() === "";

  const renderCard = (restaurant: Restaurant) => (
    <RestaurantCard
      key={restaurant.id}
      restaurant={restaurant}
      isFavorite={favIds.has(restaurant.id)}
      onToggleFavorite={token ? () => toggleFavorite(restaurant.id) : undefined}
    />
  );

  return (
    <>
      <Header />
      <main className="flex-1 max-w-7xl mx-auto px-4 py-8 w-full">
        {/* Search Input */}
        <div className="relative mb-6">
          <svg className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-dark-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search restaurants, cuisines, or dishes..."
            className="w-full input pl-12 py-4 text-lg"
            autoFocus
          />
        </div>

        {/* Filters + sort. flex-wrap so the expanded filter panel (w-full)
            drops to its own row below the controls. */}
        <div className="flex flex-wrap items-center gap-4 mb-8">
          <KosherFilterPanel
            allRestaurants={results}
            filters={filters}
            onApply={setFilters}
          />

          {/* Sort */}
          <select
            value={sortBy}
            onChange={(e) => {
              sortTouched.current = true;
              setSortBy(e.target.value as SortBy);
            }}
            className="input py-2 text-sm"
            aria-label="Sort results"
          >
            {coords && <option value="distance">Nearest First</option>}
            <option value="rating">Sort by Rating</option>
            <option value="delivery_time">Fastest Delivery</option>
            <option value="delivery_fee">Lowest Delivery Fee</option>
          </select>
        </div>

        {/* Suggested row — browse mode only; hidden while an active text
            search is narrowing results. */}
        {isBrowsing && suggested.length > 0 && (
          <section className="mb-10">
            <h2 className="text-xl font-bold mb-4">Suggested for you</h2>
            <div className="flex gap-4 overflow-x-auto pb-2">
              {suggested.map((restaurant) => (
                <div key={restaurant.id} className="w-72 flex-shrink-0">
                  <RestaurantCard
                    restaurant={restaurant}
                    isFavorite={favIds.has(restaurant.id)}
                    onToggleFavorite={token ? () => toggleFavorite(restaurant.id) : undefined}
                  />
                </div>
              ))}
            </div>
          </section>
        )}

        {loading ? (
          <>
            <div className="mb-4 text-dark-400 text-sm">Searching restaurants…</div>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {Array.from({ length: 6 }).map((_, i) => (
                <div key={i} className="card overflow-hidden animate-pulse">
                  <div className="h-48 bg-dark-800" />
                  <div className="p-4 space-y-3">
                    <div className="h-5 w-2/3 bg-dark-800 rounded" />
                    <div className="h-4 w-1/2 bg-dark-800 rounded" />
                    <div className="h-4 w-1/3 bg-dark-800 rounded" />
                  </div>
                </div>
              ))}
            </div>
          </>
        ) : loadError ? (
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load restaurants</h2>
            <p className="text-dark-400 mb-6">{loadError}</p>
            <button onClick={() => loadResults(query, coords)} className="btn-primary inline-block">
              Retry
            </button>
          </div>
        ) : (
          <>
            <div className="mb-4 text-dark-400 text-sm">
              {filtered.length} restaurant{filtered.length !== 1 ? "s" : ""} found
            </div>

            {filtered.length === 0 ? (
              <div className="card p-12 text-center">
                <svg className="w-16 h-16 text-dark-600 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
                <h2 className="text-xl font-bold mb-2">No results found</h2>
                <p className="text-dark-400">Try adjusting your search or filters.</p>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {filtered.map(renderCard)}
              </div>
            )}
          </>
        )}
      </main>
    </>
  );
}

export default function SearchPage() {
  return (
    // useSearchParams (?q= from the header SearchBar) requires a Suspense
    // boundary — same pattern as /auth.
    <Suspense
      fallback={
        <>
          <Header />
          <main className="flex-1 max-w-7xl mx-auto px-4 py-8 w-full" />
        </>
      }
    >
      <SearchPageInner />
    </Suspense>
  );
}
