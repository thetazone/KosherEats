"use client";

import { Header } from "@/components/layout/Header";
import { RestaurantCard } from "@/components/restaurant/RestaurantCard";
import { restaurants as restaurantsApi } from "@/lib/api";
import type { Restaurant } from "@/types";
import { useCallback, useEffect, useRef, useState } from "react";

const CERTIFICATIONS = ["All", "OU", "OK", "Star-K", "Kof-K", "cRc", "Badatz", "Chof-K"];

export default function SearchPage() {
  const [query, setQuery] = useState("");
  const [selectedCert, setSelectedCert] = useState("All");
  const [glattOnly, setGlattOnly] = useState(false);
  const [sortBy, setSortBy] = useState<"rating" | "delivery_time" | "delivery_fee">("rating");

  const [results, setResults] = useState<Restaurant[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Guards against a slow earlier request overwriting a newer one's results.
  const requestSeq = useRef(0);

  // Fetch the real discovery results for the current query. An empty/whitespace
  // query lists everything; a non-empty query hits the search endpoint (which
  // 400s on an empty `q`, so we must branch here rather than always searching).
  const loadResults = useCallback(async (q: string) => {
    const seq = ++requestSeq.current;
    setLoading(true);
    setLoadError(null);
    try {
      const trimmed = q.trim();
      const data = (trimmed
        ? await restaurantsApi.search(trimmed)
        : await restaurantsApi.list()) as Restaurant[];
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
  // This also drives the initial load: it runs on mount with an empty query,
  // which lists every restaurant.
  useEffect(() => {
    const handle = setTimeout(() => {
      void loadResults(query);
    }, 300);
    return () => clearTimeout(handle);
  }, [query, loadResults]);

  // Client-side refinement over the REAL results from the API.
  let filtered = results;
  if (selectedCert !== "All") {
    filtered = filtered.filter((r) => r.kosher_certification === selectedCert);
  }
  if (glattOnly) {
    filtered = filtered.filter((r) => r.is_glatt_kosher);
  }

  filtered = [...filtered].sort((a, b) => {
    if (sortBy === "rating") return b.rating - a.rating;
    if (sortBy === "delivery_time") return a.est_delivery_min - b.est_delivery_min;
    return a.delivery_fee - b.delivery_fee;
  });

  return (
    <>
      <Header />
      <main className="flex-1 max-w-7xl mx-auto px-4 py-8">
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

        {/* Filters */}
        <div className="flex flex-wrap items-center gap-4 mb-8">
          {/* Certification Filter */}
          <div className="flex gap-2 overflow-x-auto pb-2">
            {CERTIFICATIONS.map((cert) => (
              <button
                key={cert}
                onClick={() => setSelectedCert(cert)}
                className={`px-4 py-2 rounded-full text-sm font-medium whitespace-nowrap transition-colors ${
                  selectedCert === cert
                    ? "bg-brand-500 text-white"
                    : "bg-dark-800 text-dark-300 hover:bg-dark-700"
                }`}
              >
                {cert}
              </button>
            ))}
          </div>

          {/* Glatt Toggle */}
          <button
            onClick={() => setGlattOnly(!glattOnly)}
            className={`px-4 py-2 rounded-full text-sm font-medium transition-colors border ${
              glattOnly
                ? "bg-brand-500/20 text-brand-400 border-brand-500"
                : "bg-dark-800 text-dark-300 border-dark-700 hover:bg-dark-700"
            }`}
          >
            Glatt Only
          </button>

          {/* Sort */}
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as typeof sortBy)}
            className="input py-2 text-sm"
          >
            <option value="rating">Sort by Rating</option>
            <option value="delivery_time">Fastest Delivery</option>
            <option value="delivery_fee">Lowest Delivery Fee</option>
          </select>
        </div>

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
            <button onClick={() => loadResults(query)} className="btn-primary inline-block">
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
                {filtered.map((restaurant) => (
                  <RestaurantCard key={restaurant.id} restaurant={restaurant} />
                ))}
              </div>
            )}
          </>
        )}
      </main>
    </>
  );
}
