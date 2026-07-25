"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import {
  BadgePercent,
  LayoutDashboard,
  LogOut,
  ReceiptText,
  Settings,
  Store,
  UtensilsCrossed,
} from "lucide-react";
import { sellerApi, sellerAuth } from "@/lib/sellerApi";
import type { SellerRestaurant } from "@/types/seller";

const NAV_ITEMS = [
  { href: "/seller", label: "Dashboard", icon: LayoutDashboard },
  { href: "/seller/orders", label: "Orders", icon: ReceiptText },
  { href: "/seller/menu", label: "Menu", icon: UtensilsCrossed },
  { href: "/seller/deals", label: "Deals", icon: BadgePercent },
  { href: "/seller/settings", label: "Settings", icon: Settings },
];

/**
 * Seller area shell. Guards every /seller/* page behind a valid seller
 * session (ke_seller_token + role === "seller"), renders the sidebar nav,
 * and owns the restaurant picker: the selected id is persisted via
 * sellerAuth.setActiveRestaurantId and injected as ?restaurant_id= on every
 * /seller/* API call by lib/sellerApi. Switching restaurants remounts the
 * page subtree (key= below) so client pages re-run their fetches against
 * the newly selected restaurant.
 */
export default function SellerLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const isLoginPage = pathname === "/seller/login";

  const [ready, setReady] = useState(false);
  const [restaurants, setRestaurants] = useState<SellerRestaurant[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [restaurantsLoading, setRestaurantsLoading] = useState(true);
  const [restaurantsError, setRestaurantsError] = useState<string | null>(null);

  // Client-side guard. The seller area is a single-page-app experience behind
  // this boundary, so a localStorage check is the whole gate (the API rejects
  // non-seller tokens server-side regardless).
  useEffect(() => {
    if (isLoginPage) {
      setReady(true);
      return;
    }
    const token = sellerAuth.getToken();
    const user = sellerAuth.getUser();
    if (!token || user?.role !== "seller") {
      sellerAuth.clear();
      router.replace("/seller/login");
      return;
    }
    setReady(true);
  }, [isLoginPage, pathname, router]);

  const loadRestaurants = useCallback(async () => {
    setRestaurantsLoading(true);
    setRestaurantsError(null);
    try {
      const list = await sellerApi.restaurants.list();
      setRestaurants(list);
      // Keep the persisted selection when it's still one of ours; otherwise
      // fall back to the first restaurant (matches the backend's fallback).
      const stored = sellerAuth.getActiveRestaurantId();
      const valid = list.find((r) => r.id === stored) ?? list[0];
      if (valid) {
        sellerAuth.setActiveRestaurantId(valid.id);
        setActiveId(valid.id);
      } else {
        sellerAuth.setActiveRestaurantId(null);
        setActiveId(null);
      }
    } catch (err) {
      setRestaurantsError((err as Error).message || "Failed to load restaurants");
    } finally {
      setRestaurantsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!ready || isLoginPage) return;
    loadRestaurants();
  }, [ready, isLoginPage, loadRestaurants]);

  if (!ready) return null;

  if (isLoginPage) {
    return <>{children}</>;
  }

  function selectRestaurant(id: string) {
    sellerAuth.setActiveRestaurantId(id);
    setActiveId(id);
  }

  function signOut() {
    sellerAuth.clear();
    router.replace("/seller/login");
  }

  const activeRestaurant = restaurants.find((r) => r.id === activeId) ?? null;

  const picker = restaurantsLoading ? (
    <div className="h-11 rounded-xl bg-dark-800 animate-pulse" aria-hidden="true" />
  ) : restaurantsError ? (
    <div className="text-sm">
      <p className="text-red-400 mb-1.5">{restaurantsError}</p>
      <button
        onClick={loadRestaurants}
        className="inline-flex items-center min-h-[44px] -my-2 text-brand-500 hover:text-brand-400 font-medium transition-colors"
      >
        Try again
      </button>
    </div>
  ) : restaurants.length === 0 ? (
    <div className="text-sm">
      <p className="text-dark-400 mb-1.5">No restaurants yet</p>
      <Link
        href="/seller/onboarding"
        className="inline-flex items-center min-h-[44px] -my-2 text-brand-500 hover:text-brand-400 font-medium transition-colors"
      >
        Set up your restaurant
      </Link>
    </div>
  ) : restaurants.length === 1 ? (
    <div className="flex items-center gap-2.5 px-3 py-2.5 rounded-xl bg-dark-800 border border-dark-700">
      <Store className="w-4 h-4 text-brand-500 shrink-0" />
      <span className="text-sm font-medium text-white truncate">{restaurants[0].name}</span>
    </div>
  ) : (
    // .input supplies text-base (16px) so iOS Safari never auto-zooms the
    // picker on focus; min-h keeps it a 44px touch target.
    <select
      value={activeId ?? ""}
      onChange={(e) => selectRestaurant(e.target.value)}
      aria-label="Restaurant"
      className="input w-full py-2.5 min-h-[44px] cursor-pointer"
    >
      {restaurants.map((r) => (
        <option key={r.id} value={r.id}>
          {r.name}
        </option>
      ))}
    </select>
  );

  const nav = NAV_ITEMS.map((item) => {
    const active =
      pathname === item.href || (item.href !== "/seller" && pathname.startsWith(item.href));
    const Icon = item.icon;
    return (
      <Link
        key={item.href}
        href={item.href}
        className={`flex items-center gap-3 px-3 py-2.5 min-h-[44px] rounded-xl text-sm font-medium transition-colors whitespace-nowrap ${
          active
            ? "bg-brand-500/15 text-brand-400"
            : "text-dark-400 hover:bg-dark-800 hover:text-white"
        }`}
      >
        <Icon className="w-4 h-4 shrink-0" />
        {item.label}
      </Link>
    );
  });

  return (
    <div className="min-h-screen bg-dark-950 text-white md:flex">
      {/* Desktop sidebar */}
      <aside className="hidden md:flex w-64 shrink-0 bg-dark-900 border-r border-dark-800 flex-col sticky top-0 h-screen">
        <div className="px-5 py-6 border-b border-dark-800">
          <Link href="/seller" className="text-xl font-extrabold">
            <span className="text-brand-500">Kosher</span>
            <span className="text-white">Eats</span>
          </Link>
          <div className="text-xs text-dark-500 mt-0.5 mb-4">Seller dashboard</div>
          {picker}
        </div>
        <nav className="flex-1 p-4 space-y-1 overflow-y-auto">{nav}</nav>
        <div className="p-4 border-t border-dark-800">
          <button
            onClick={signOut}
            className="flex items-center gap-3 w-full px-3 py-2.5 min-h-[44px] rounded-xl text-sm font-medium text-dark-400 hover:bg-dark-800 hover:text-white transition-colors"
          >
            <LogOut className="w-4 h-4 shrink-0" />
            Sign out
          </button>
        </div>
      </aside>

      {/* Mobile header */}
      <div className="md:hidden sticky top-0 z-20 bg-dark-900 border-b border-dark-800">
        <div className="flex items-center justify-between gap-3 px-4 pt-4">
          <Link href="/seller" className="text-lg font-extrabold shrink-0">
            <span className="text-brand-500">Kosher</span>
            <span className="text-white">Eats</span>
          </Link>
          <div className="flex-1 min-w-0 max-w-[14rem]">{picker}</div>
          <button
            onClick={signOut}
            aria-label="Sign out"
            className="min-w-[44px] min-h-[44px] flex items-center justify-center -mr-2 rounded-xl text-dark-400 hover:bg-dark-800 hover:text-white transition-colors shrink-0"
          >
            <LogOut className="w-4 h-4" />
          </button>
        </div>
        <nav className="flex gap-1 px-3 py-2 overflow-x-auto">{nav}</nav>
      </div>

      {/* Page content — keyed by restaurant so switching remounts pages and
          their fetches re-run against the newly selected restaurant. */}
      <main className="flex-1 min-w-0">
        <div key={activeId ?? "none"} className="max-w-6xl mx-auto p-4 md:p-8">
          {activeRestaurant?.approval_status === "pending" && (
            <div className="mb-6 bg-brand-500/10 border border-brand-500/30 text-brand-300 rounded-xl px-4 py-3 text-sm">
              {activeRestaurant.name} is awaiting approval. You can build your menu now — it
              will go live once the KosherEats team approves it.
            </div>
          )}
          {children}
        </div>
      </main>
    </div>
  );
}
