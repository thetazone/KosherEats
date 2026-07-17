"use client";

import { cart as cartApi } from "@/lib/api";
import type { Cart, User } from "@/types";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

function readUser(): User | null {
  if (typeof window === "undefined") return null;
  const raw = window.localStorage.getItem("user");
  if (!raw) return null;
  try {
    return JSON.parse(raw) as User;
  } catch {
    return null;
  }
}

export function Header() {
  const router = useRouter();
  const pathname = usePathname();
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [user, setUser] = useState<User | null>(null);
  const [cartCount, setCartCount] = useState(0);

  // Hydrate auth + cart on mount and whenever the route changes so the badge
  // and nav stay in sync after login, sign-out, or cart mutations.
  const refresh = useCallback(async () => {
    const u = readUser();
    setUser(u);

    const token =
      typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!token) {
      setCartCount(0);
      return;
    }
    try {
      const c = (await cartApi.get(token)) as Cart;
      setCartCount(c.items.reduce((sum, item) => sum + item.quantity, 0));
    } catch {
      // Non-fatal — leave the badge at its last known value rather than
      // breaking the header if the cart fetch fails (e.g. expired token).
      setCartCount(0);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh, pathname]);

  function handleSignOut() {
    if (typeof window !== "undefined") {
      window.localStorage.removeItem("token");
      window.localStorage.removeItem("refresh_token");
      window.localStorage.removeItem("user");
    }
    setUser(null);
    setCartCount(0);
    setIsMenuOpen(false);
    router.replace("/");
  }

  const addressHref = user ? "/cart" : "/auth";

  return (
    <header className="bg-dark-900/80 backdrop-blur-md border-b border-dark-800 sticky top-0 z-50">
      <div className="max-w-7xl mx-auto px-4 h-16 flex items-center justify-between">
        {/* Logo */}
        <Link href="/" className="flex items-center gap-2">
          <span className="text-2xl font-extrabold text-brand-500">
            Kosher
          </span>
          <span className="text-2xl font-extrabold text-white">Eats</span>
        </Link>

        {/* Delivery Address */}
        <Link
          href={addressHref}
          className="hidden md:flex items-center gap-2 bg-dark-800 rounded-full px-4 py-2 text-sm hover:bg-dark-700 transition-colors"
        >
          <svg
            className="w-4 h-4 text-brand-500"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"
            />
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"
            />
          </svg>
          <span className="text-dark-300">Enter delivery address</span>
        </Link>

        {/* Nav */}
        <nav className="hidden md:flex items-center gap-6">
          <Link
            href="/search"
            className="text-dark-300 hover:text-white transition-colors text-sm font-medium"
          >
            Search
          </Link>
          <Link
            href="/deals"
            className="text-dark-300 hover:text-white transition-colors text-sm font-medium"
          >
            Deals
          </Link>
          <Link
            href="/orders"
            className="text-dark-300 hover:text-white transition-colors text-sm font-medium"
          >
            Orders
          </Link>
          <Link
            href="/cart"
            className="relative text-dark-300 hover:text-white transition-colors"
          >
            <svg
              className="w-6 h-6"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.293 2.293c-.63.63-.184 1.707.707 1.707H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 100 4 2 2 0 000-4z"
              />
            </svg>
            {cartCount > 0 && (
              <span className="absolute -top-1 -right-2 bg-brand-500 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center font-bold">
                {cartCount}
              </span>
            )}
          </Link>
          {user ? (
            <div className="flex items-center gap-4">
              <Link
                href="/account"
                className="text-white hover:text-brand-400 transition-colors text-sm font-medium"
              >
                {user.first_name}
              </Link>
              <button
                onClick={handleSignOut}
                className="text-dark-300 hover:text-white transition-colors text-sm font-medium"
              >
                Sign out
              </button>
            </div>
          ) : (
            <Link href="/auth" className="btn-primary text-sm py-2 px-4">
              Sign in
            </Link>
          )}
        </nav>

        {/* Mobile menu button */}
        <button
          className="md:hidden text-dark-300"
          onClick={() => setIsMenuOpen(!isMenuOpen)}
        >
          <svg
            className="w-6 h-6"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d={
                isMenuOpen
                  ? "M6 18L18 6M6 6l12 12"
                  : "M4 6h16M4 12h16M4 18h16"
              }
            />
          </svg>
        </button>
      </div>

      {/* Mobile menu */}
      {isMenuOpen && (
        <div className="md:hidden bg-dark-900 border-t border-dark-800 px-4 py-4 space-y-4">
          <Link href="/search" className="block text-dark-300 text-sm font-medium">
            Search
          </Link>
          <Link href="/deals" className="block text-dark-300 text-sm font-medium">
            Deals
          </Link>
          <Link href="/orders" className="block text-dark-300 text-sm font-medium">
            Orders
          </Link>
          <Link href="/cart" className="block text-dark-300 text-sm font-medium">
            Cart{cartCount > 0 ? ` (${cartCount})` : ""}
          </Link>
          {user && (
            <Link href="/account" className="block text-dark-300 text-sm font-medium">
              Account
            </Link>
          )}
          {user ? (
            <button
              onClick={handleSignOut}
              className="btn-primary text-sm py-2 px-4 inline-block"
            >
              Sign out
            </button>
          ) : (
            <Link href="/auth" className="btn-primary text-sm py-2 px-4 inline-block">
              Sign in
            </Link>
          )}
        </div>
      )}
    </header>
  );
}
