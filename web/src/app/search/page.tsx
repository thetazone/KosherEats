"use client";

import { Header } from "@/components/layout/Header";
import { RestaurantCard } from "@/components/restaurant/RestaurantCard";
import { useEffect, useState } from "react";
import { restaurants as restaurantsApi } from "@/lib/api";

const CERTIFICATIONS = ["All", "OU", "OK", "Star-K", "Kof-K", "cRc", "Badatz", "Chof-K"];

interface Restaurant {
  id: string;
  name: string;
  image_url?: string;
  kosher_certification?: string;
  cuisine_type?: string[];
  rating?: number;
  review_count?: number;
  delivery_fee?: number;
  est_delivery_min?: number;
  est_delivery_max?: number;
  is_glatt_kosher?: boolean;
  is_open?: boolean;
}

export default function SearchPage() {
  const [all, setAll] = useState<Restaurant[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [query, setQuery] = useState("");
  const [selectedCert, setSelectedCert] = useState("All");
  const [glattOnly, setGlattOnly] = useState(false);
  const [sortBy, setSortBy] = useState<"rating" | "delivery_time" | "delivery_fee">("rating");

  useEffect(() => {
    restaurantsApi
      .list()
      .then((data) => setAll(((data as Restaurant[]) || []).filter(Boolean)))
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load"))
      .finally(() => setLoading(false));
  }, []);

  let filtered = [...all];
  if (query) {
    const q = query.toLowerCase();
    filtered = filtered.filter(
      (r) =>
        r.name.toLowerCase().includes(q) ||
        (r.cuisine_type || []).some((c) => c.toLowerCase().includes(q))
    );
  }
  if (selectedCert !== "All") {
    filtered = filtered.filter((r) => r.kosher_certification === selectedCert);
  }
  if (glattOnly) {
    filtered = filtered.filter((r) => r.is_glatt_kosher);
  }

  filtered.sort((a, b) => {
    if (sortBy === "rating") return (b.rating || 0) - (a.rating || 0);
    if (sortBy === "delivery_time") return (a.est_delivery_min || 0) - (b.est_delivery_min || 0);
    return (a.delivery_fee || 0) - (b.delivery_fee || 0);
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

        {/* Results */}
        {loading ? (
          <div className="text-dark-400 text-sm py-12 text-center">Loading restaurants…</div>
        ) : error ? (
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load restaurants</h2>
            <p className="text-dark-400">{error}</p>
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
                {filtered.map((r) => (
                  <RestaurantCard
                    key={r.id}
                    restaurant={{
                      id: r.id,
                      name: r.name,
                      image_url: r.image_url || "",
                      kosher_certification: r.kosher_certification || "Kosher",
                      cuisine_type: r.cuisine_type || [],
                      rating: r.rating || 0,
                      review_count: r.review_count || 0,
                      delivery_fee: r.delivery_fee || 0,
                      est_delivery_min: r.est_delivery_min || 0,
                      est_delivery_max: r.est_delivery_max || 0,
                      is_glatt_kosher: !!r.is_glatt_kosher,
                      is_open: r.is_open !== false,
                    }}
                  />
                ))}
              </div>
            )}
          </>
        )}
      </main>
    </>
  );
}
