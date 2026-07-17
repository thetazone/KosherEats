"use client";

import { Search } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";

export function SearchBar() {
  const [query, setQuery] = useState("");
  const router = useRouter();

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = query.trim();
    // /search reads ?q=; send the trimmed term so the box there matches what
    // was actually searched. An empty submit still opens the browse list.
    router.push(trimmed ? `/search?q=${encodeURIComponent(trimmed)}` : "/search");
  };

  return (
    <form onSubmit={handleSearch} className="max-w-2xl w-full" role="search">
      <div className="relative">
        <Search
          className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-dark-400"
          aria-hidden="true"
        />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search kosher restaurants"
          placeholder="Search for kosher restaurants, cuisines, or dishes..."
          className="w-full input rounded-2xl pl-12 pr-4 py-4 text-lg"
        />
      </div>
    </form>
  );
}
