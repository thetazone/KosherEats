"use client";

import { Header } from "@/components/layout/Header";
import { RestaurantCard } from "@/components/restaurant/RestaurantCard";
import { useState } from "react";

const CERTIFICATIONS = ["All", "OU", "OK", "Star-K", "Kof-K", "cRc", "Badatz", "Chof-K"];

const MOCK_RESULTS = [
  {
    id: "1",
    name: "Jerusalem Grill",
    image_url: "",
    kosher_certification: "OU",
    cuisine_type: ["Israeli", "Middle Eastern"],
    rating: 4.8,
    review_count: 324,
    delivery_fee: 399,
    est_delivery_min: 25,
    est_delivery_max: 40,
    is_glatt_kosher: true,
    is_open: true,
  },
  {
    id: "2",
    name: "Shalom Sushi",
    image_url: "",
    kosher_certification: "OK",
    cuisine_type: ["Japanese", "Sushi"],
    rating: 4.6,
    review_count: 189,
    delivery_fee: 499,
    est_delivery_min: 30,
    est_delivery_max: 45,
    is_glatt_kosher: false,
    is_open: true,
  },
  {
    id: "3",
    name: "Kosher Burger Co.",
    image_url: "",
    kosher_certification: "Star-K",
    cuisine_type: ["American", "Burgers"],
    rating: 4.5,
    review_count: 412,
    delivery_fee: 299,
    est_delivery_min: 20,
    est_delivery_max: 35,
    is_glatt_kosher: true,
    is_open: true,
  },
  {
    id: "5",
    name: "Pita Palace",
    image_url: "",
    kosher_certification: "OU",
    cuisine_type: ["Israeli", "Fast Food"],
    rating: 4.3,
    review_count: 156,
    delivery_fee: 349,
    est_delivery_min: 15,
    est_delivery_max: 30,
    is_glatt_kosher: true,
    is_open: true,
  },
  {
    id: "6",
    name: "Cholent House",
    image_url: "",
    kosher_certification: "cRc",
    cuisine_type: ["Jewish", "Traditional"],
    rating: 4.7,
    review_count: 89,
    delivery_fee: 449,
    est_delivery_min: 35,
    est_delivery_max: 50,
    is_glatt_kosher: true,
    is_open: false,
  },
];

export default function SearchPage() {
  const [query, setQuery] = useState("");
  const [selectedCert, setSelectedCert] = useState("All");
  const [glattOnly, setGlattOnly] = useState(false);
  const [sortBy, setSortBy] = useState<"rating" | "delivery_time" | "delivery_fee">("rating");

  let filtered = MOCK_RESULTS;
  if (query) {
    filtered = filtered.filter(
      (r) =>
        r.name.toLowerCase().includes(query.toLowerCase()) ||
        r.cuisine_type.some((c) => c.toLowerCase().includes(query.toLowerCase()))
    );
  }
  if (selectedCert !== "All") {
    filtered = filtered.filter((r) => r.kosher_certification === selectedCert);
  }
  if (glattOnly) {
    filtered = filtered.filter((r) => r.is_glatt_kosher);
  }

  filtered.sort((a, b) => {
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

        {/* Results */}
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
      </main>
    </>
  );
}
