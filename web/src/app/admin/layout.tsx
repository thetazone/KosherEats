"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { adminAuth } from "@/lib/adminApi";

/**
 * Admin layout. Wraps every /admin/* page in a sidebar nav and guards the
 * whole area behind a valid admin token. The login page opts out of the
 * guard by checking the pathname.
 */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const isLoginPage = pathname === "/admin/login";
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (isLoginPage) {
      setReady(true);
      return;
    }
    // Redirect to login if no token. We only check client-side because
    // admin is a single-page-app experience behind this boundary.
    if (!adminAuth.get()) {
      router.replace("/admin/login");
      return;
    }
    setReady(true);
  }, [isLoginPage, router]);

  if (!ready) return null;

  if (isLoginPage) {
    return <div className="min-h-screen bg-dark-950">{children}</div>;
  }

  const navItems = [
    { href: "/admin", label: "Dashboard" },
    { href: "/admin/restaurants", label: "Restaurants" },
    { href: "/admin/couriers", label: "Couriers" },
    { href: "/admin/orders", label: "Orders" },
  ];

  return (
    <div className="min-h-screen bg-dark-950 text-white flex">
      <aside className="w-64 bg-dark-900 border-r border-dark-800 flex flex-col">
        <div className="px-6 py-6 border-b border-dark-800">
          <div className="text-brand-500 font-bold text-xl">KosherEats</div>
          <div className="text-xs text-dark-500">Admin</div>
        </div>
        <nav className="flex-1 p-4 space-y-1">
          {navItems.map((item) => {
            const active = pathname === item.href || (item.href !== "/admin" && pathname.startsWith(item.href));
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`block px-3 py-2 rounded-md text-sm font-medium transition ${
                  active
                    ? "bg-brand-500/15 text-brand-400"
                    : "text-dark-400 hover:bg-dark-800 hover:text-white"
                }`}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>
        <div className="p-4 border-t border-dark-800">
          <button
            onClick={() => {
              adminAuth.clear();
              router.replace("/admin/login");
            }}
            className="w-full text-left text-sm text-dark-400 hover:text-white transition"
          >
            Sign out
          </button>
        </div>
      </aside>

      <main className="flex-1 overflow-y-auto">
        <div className="max-w-7xl mx-auto p-8">{children}</div>
      </main>
    </div>
  );
}
