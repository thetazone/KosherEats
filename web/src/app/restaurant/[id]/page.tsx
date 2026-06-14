"use client";

import { Header } from "@/components/layout/Header";
import { cart as cartApi, restaurants as restaurantsApi } from "@/lib/api";
import type { MenuCategory, MenuItem, Restaurant } from "@/types";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

interface LocalCartItem {
  id: string;
  name: string;
  price: number;
  quantity: number;
}

function isUnauthorized(err: unknown): boolean {
  const msg = String(err instanceof Error ? err.message : err).toLowerCase();
  return msg.includes("401") || msg.includes("unauthorized") || msg.includes("invalid token");
}

function DietaryBadge({ label, color }: { label: string; color: string }) {
  return (
    <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${color}`}>
      {label}
    </span>
  );
}

export default function RestaurantPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const router = useRouter();

  const [restaurant, setRestaurant] = useState<Restaurant | null>(null);
  const [menu, setMenu] = useState<MenuCategory[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [cart, setCart] = useState<LocalCartItem[]>([]);
  const [activeCategory, setActiveCategory] = useState<string | undefined>(undefined);
  const [mutatingItemId, setMutatingItemId] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    void loadAll(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  async function loadAll(restaurantId: string) {
    setLoading(true);
    setLoadError(null);
    try {
      const [r, m] = await Promise.all([
        restaurantsApi.get(restaurantId) as Promise<Restaurant>,
        restaurantsApi.getMenu(restaurantId) as Promise<MenuCategory[]>,
      ]);
      setRestaurant(r);
      const categories = [...m].sort((a, b) => a.sort_order - b.sort_order);
      setMenu(categories);
      setActiveCategory(categories[0]?.id);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : "Failed to load restaurant");
    } finally {
      setLoading(false);
    }
  }

  async function addToCart(item: MenuItem) {
    if (!id) return;
    const token = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!token) {
      router.push("/auth");
      return;
    }

    setMutatingItemId(item.id);
    setMutationError(null);
    try {
      await cartApi.addItem(token, {
        menu_item_id: item.id,
        restaurant_id: id,
        quantity: 1,
      });
      setCart((prev) => {
        const existing = prev.find((c) => c.id === item.id);
        if (existing) {
          return prev.map((c) =>
            c.id === item.id ? { ...c, quantity: c.quantity + 1 } : c
          );
        }
        return [...prev, { id: item.id, name: item.name, price: item.price, quantity: 1 }];
      });
    } catch (err) {
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        router.push("/auth");
        return;
      }
      setMutationError(err instanceof Error ? err.message : "Failed to add item to cart");
    } finally {
      setMutatingItemId(null);
    }
  }

  const cartTotal = cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
  const cartCount = cart.reduce((sum, item) => sum + item.quantity, 0);

  if (loading) {
    return (
      <>
        <Header />
        <main className="flex-1 max-w-7xl mx-auto px-4 py-8">
          <div className="card p-12 text-center text-dark-400">Loading restaurant…</div>
        </main>
      </>
    );
  }

  if (loadError || !restaurant) {
    return (
      <>
        <Header />
        <main className="flex-1 max-w-7xl mx-auto px-4 py-8">
          <div className="card p-12 text-center">
            <h2 className="text-xl font-bold mb-2">Couldn&apos;t load this restaurant</h2>
            <p className="text-dark-400 mb-6">{loadError ?? "Restaurant not found."}</p>
            <button
              onClick={() => id && loadAll(id)}
              className="btn-primary inline-block"
            >
              Retry
            </button>
          </div>
        </main>
      </>
    );
  }

  const rest = restaurant;

  return (
    <>
      <Header />
      <main className="flex-1">
        {/* Hero */}
        <div className="relative h-64 bg-gradient-to-br from-brand-900/60 to-dark-900">
          <div className="absolute inset-0 bg-gradient-to-t from-dark-950 to-transparent" />
          <div className="absolute bottom-0 left-0 right-0 p-6 max-w-7xl mx-auto">
            <div className="flex items-center gap-3 mb-2">
              <span className="bg-brand-500 text-white text-sm font-bold px-3 py-1 rounded-lg">
                {rest.kosher_certification}
              </span>
              {rest.is_glatt_kosher && (
                <span className="bg-dark-800 text-brand-400 text-sm font-bold px-3 py-1 rounded-lg border border-dark-700">
                  Glatt Kosher
                </span>
              )}
              {rest.is_pas_yisroel && (
                <span className="bg-dark-800 text-brand-400 text-sm font-bold px-3 py-1 rounded-lg border border-dark-700">
                  Pas Yisroel
                </span>
              )}
            </div>
            <h1 className="text-4xl font-extrabold">{rest.name}</h1>
          </div>
        </div>

        <div className="max-w-7xl mx-auto px-4 py-6">
          {/* Restaurant Info */}
          <div className="flex flex-wrap items-center gap-4 mb-6">
            <div className="flex items-center gap-1">
              <svg className="w-5 h-5 text-brand-400" fill="currentColor" viewBox="0 0 20 20">
                <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
              </svg>
              <span className="font-semibold">{rest.rating}</span>
              <span className="text-dark-400">({rest.review_count} reviews)</span>
            </div>
            <span className="text-dark-600">·</span>
            <span className="text-dark-400">{rest.cuisine_type.join(", ")}</span>
            <span className="text-dark-600">·</span>
            <span className="text-dark-400">
              {rest.est_delivery_min}-{rest.est_delivery_max} min
            </span>
            <span className="text-dark-600">·</span>
            <span className="text-dark-400">
              ${(rest.delivery_fee / 100).toFixed(2)} delivery
            </span>
            {rest.min_order > 0 && (
              <>
                <span className="text-dark-600">·</span>
                <span className="text-dark-400">
                  ${(rest.min_order / 100).toFixed(2)} min order
                </span>
              </>
            )}
          </div>

          <p className="text-dark-300 mb-8 max-w-3xl">{rest.description}</p>

          {/* Kosher Info Card */}
          <div className="card p-4 mb-8">
            <h3 className="font-semibold text-brand-400 mb-2">Kosher Information</h3>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
              <div>
                <span className="text-dark-400">Certification</span>
                <p className="font-medium">{rest.kosher_certification} — {rest.certifying_agency}</p>
              </div>
              <div>
                <span className="text-dark-400">Glatt Kosher</span>
                <p className="font-medium">{rest.is_glatt_kosher ? "Yes" : "No"}</p>
              </div>
              <div>
                <span className="text-dark-400">Cholov Yisroel</span>
                <p className="font-medium">{rest.is_cholov_yisroel ? "Yes" : "N/A"}</p>
              </div>
              <div>
                <span className="text-dark-400">Pas Yisroel</span>
                <p className="font-medium">{rest.is_pas_yisroel ? "Yes" : "No"}</p>
              </div>
            </div>
          </div>

          <div className="flex gap-8">
            {/* Menu */}
            <div className="flex-1">
              {/* Category Tabs */}
              <div className="sticky top-16 bg-dark-950 z-30 py-4 border-b border-dark-800 mb-6">
                <div className="flex gap-3 overflow-x-auto">
                  {menu.map((cat) => (
                    <button
                      key={cat.id}
                      onClick={() => setActiveCategory(cat.id)}
                      className={`px-4 py-2 rounded-full text-sm font-medium whitespace-nowrap transition-colors ${
                        activeCategory === cat.id
                          ? "bg-brand-500 text-white"
                          : "bg-dark-800 text-dark-300 hover:bg-dark-700"
                      }`}
                    >
                      {cat.name}
                    </button>
                  ))}
                </div>
              </div>

              {mutationError && (
                <div className="card p-3 mb-4 border border-red-800 bg-red-900/20 text-red-300 text-sm">
                  {mutationError}
                </div>
              )}

              {menu.length === 0 ? (
                <div className="card p-12 text-center text-dark-400">
                  This restaurant hasn&apos;t published a menu yet.
                </div>
              ) : (
                menu.map((category) => (
                  <div key={category.id} className="mb-8">
                    <h2 className="text-xl font-bold mb-4">{category.name}</h2>
                    <div className="space-y-3">
                      {(category.items ?? []).map((item) => {
                        const cartItem = cart.find((c) => c.id === item.id);
                        const isPending = mutatingItemId === item.id;
                        return (
                          <div
                            key={item.id}
                            className="card p-4 flex justify-between items-start gap-4 hover:border-dark-600 transition-colors"
                          >
                            <div className="flex-1">
                              <div className="flex items-center gap-2 mb-1">
                                <h3 className="font-semibold">{item.name}</h3>
                                {item.is_meat && (
                                  <DietaryBadge label="Meat" color="bg-red-900/40 text-red-400" />
                                )}
                                {item.is_dairy && (
                                  <DietaryBadge label="Dairy" color="bg-blue-900/40 text-blue-400" />
                                )}
                                {item.is_pareve && (
                                  <DietaryBadge label="Pareve" color="bg-green-900/40 text-green-400" />
                                )}
                              </div>
                              <p className="text-dark-400 text-sm mb-2">
                                {item.description}
                              </p>
                              <span className="text-brand-400 font-semibold">
                                ${(item.price / 100).toFixed(2)}
                              </span>
                            </div>

                            <div className="flex items-center gap-2">
                              {cartItem ? (
                                <div className="flex items-center gap-3 bg-dark-800 rounded-xl px-3 py-2">
                                  <span className="font-semibold w-6 text-center">
                                    {cartItem.quantity}
                                  </span>
                                  <button
                                    onClick={() => addToCart(item)}
                                    disabled={isPending || !item.is_available}
                                    className="w-7 h-7 rounded-full bg-brand-500 hover:bg-brand-600 disabled:opacity-50 flex items-center justify-center text-white transition-colors"
                                  >
                                    +
                                  </button>
                                </div>
                              ) : (
                                <button
                                  onClick={() => addToCart(item)}
                                  disabled={isPending || !item.is_available}
                                  className="bg-dark-800 hover:bg-dark-700 border border-dark-700 hover:border-brand-500 disabled:opacity-50 disabled:hover:border-dark-700 text-white px-4 py-2 rounded-xl text-sm font-medium transition-colors"
                                >
                                  {!item.is_available ? "Unavailable" : isPending ? "Adding…" : "Add"}
                                </button>
                              )}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                ))
              )}
            </div>

            {/* Cart Sidebar (desktop) */}
            <div className="hidden lg:block w-80">
              <div className="sticky top-24 card p-5">
                <h3 className="font-bold text-lg mb-4">Your Order</h3>
                {cart.length === 0 ? (
                  <p className="text-dark-400 text-sm text-center py-8">
                    Your cart is empty. Add items from the menu to get started.
                  </p>
                ) : (
                  <>
                    <div className="space-y-3 mb-4">
                      {cart.map((item) => (
                        <div key={item.id} className="flex justify-between items-center">
                          <div>
                            <span className="text-sm font-medium">
                              {item.quantity}x {item.name}
                            </span>
                          </div>
                          <span className="text-sm text-dark-300">
                            ${((item.price * item.quantity) / 100).toFixed(2)}
                          </span>
                        </div>
                      ))}
                    </div>
                    <div className="border-t border-dark-700 pt-3 mb-4">
                      <div className="flex justify-between text-sm text-dark-400 mb-1">
                        <span>Subtotal</span>
                        <span>${(cartTotal / 100).toFixed(2)}</span>
                      </div>
                      <div className="flex justify-between text-sm text-dark-400 mb-1">
                        <span>Delivery fee</span>
                        <span>${(rest.delivery_fee / 100).toFixed(2)}</span>
                      </div>
                      <div className="flex justify-between font-semibold mt-2">
                        <span>Total</span>
                        <span className="text-brand-400">
                          ${((cartTotal + rest.delivery_fee) / 100).toFixed(2)}
                        </span>
                      </div>
                    </div>
                    <a href="/cart" className="btn-primary w-full block text-center">
                      Go to Checkout
                    </a>
                  </>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* Mobile Cart Bar */}
        {cartCount > 0 && (
          <div className="lg:hidden fixed bottom-0 left-0 right-0 bg-dark-900 border-t border-dark-800 p-4 z-50">
            <a
              href="/cart"
              className="btn-primary w-full flex items-center justify-between"
            >
              <span className="bg-brand-600 px-2.5 py-0.5 rounded-lg text-sm font-bold">
                {cartCount}
              </span>
              <span className="font-semibold">Go to Checkout</span>
              <span className="font-semibold">
                ${((cartTotal + rest.delivery_fee) / 100).toFixed(2)}
              </span>
            </a>
          </div>
        )}
      </main>
    </>
  );
}
