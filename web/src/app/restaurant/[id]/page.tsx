"use client";

import { savePreselectedDeal } from "@/components/checkout/checkoutShared";
import { Header } from "@/components/layout/Header";
import { KosherBadge } from "@/components/restaurant/KosherBadge";
import { KosherCertificateModal } from "@/components/restaurant/KosherCertificateModal";
import { MenuItemModal, type MenuItemSelection } from "@/components/restaurant/MenuItemModal";
import { cart as cartApi, deals as dealsApi, restaurants as restaurantsApi } from "@/lib/api";
import { formatUSD } from "@/lib/format";
import type { Cart, Deal, MenuCategory, MenuItem, Restaurant, SelectedModifier } from "@/types";
import {
  Building2,
  Cake,
  CheckCircle2,
  Droplets,
  FileText,
  ShieldCheck,
  Star,
  Tag,
  type LucideIcon,
} from "lucide-react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";

// One optimistic sidebar line. The same menu item added with different
// modifier selections becomes distinct lines, so lines are keyed by
// menu_item_id + sorted modifier ids — the same identity the backend's
// cart_items upsert uses (cart_id, menu_item_id, selected_modifiers).
interface LocalCartItem {
  key: string;
  menuItemId: string;
  name: string;
  unitPrice: number; // cents, base price + selected modifier deltas
  quantity: number;
  modifiers: SelectedModifier[];
}

function cartLineKey(menuItemId: string, modifierIds: string[]): string {
  return `${menuItemId}|${[...modifierIds].sort().join(",")}`;
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

// KashrusChip mirrors the iOS KashrusInfoChip: icon + bold title over a muted
// subtitle, one chip per kashrus standard the restaurant meets.
function KashrusChip({
  icon: Icon,
  iconColor,
  title,
  subtitle,
}: {
  icon: LucideIcon;
  iconColor: string;
  title: string;
  subtitle: string;
}) {
  return (
    <div className="flex items-center gap-2 bg-dark-800 rounded-xl px-3 py-2">
      <Icon className={`w-5 h-5 flex-shrink-0 ${iconColor}`} aria-hidden="true" />
      <div>
        <p className="text-sm font-bold leading-tight">{title}</p>
        <p className="text-[11px] text-dark-400 leading-tight">{subtitle}</p>
      </div>
    </div>
  );
}

// Discount badge copy — mirrors the iOS Deal.discountBadge computed property.
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

// DealCard is one card in the horizontal per-restaurant deals strip.
// selected marks the deal the user tapped through from the Deals page — it is
// persisted for checkout, where CheckoutPanel auto-applies it.
function DealCard({ deal, selected = false }: { deal: Deal; selected?: boolean }) {
  const badge = dealBadge(deal);
  return (
    <div
      className={`w-72 flex-shrink-0 card p-4 ${
        selected ? "border-brand-500 bg-brand-900/10" : ""
      }`}
    >
      <div className="flex items-center gap-2 mb-2">
        {badge && (
          <span className="bg-brand-500 text-white text-xs font-bold px-2 py-1 rounded-lg">
            {badge}
          </span>
        )}
        {deal.min_order_amount != null && deal.min_order_amount > 0 && (
          <span className="text-dark-400 text-xs">
            Min. order {formatUSD(deal.min_order_amount)}
          </span>
        )}
      </div>
      <h3 className="font-semibold mb-1">{deal.title}</h3>
      {deal.description && (
        <p className="text-dark-400 text-sm line-clamp-2 mb-2">{deal.description}</p>
      )}
      <div className="text-xs text-dark-500">
        {deal.menu_item_name && <span>On {deal.menu_item_name} · </span>}
        <span>
          Ends{" "}
          {new Date(deal.expires_at).toLocaleDateString(undefined, {
            month: "short",
            day: "numeric",
          })}
        </span>
      </div>
      {selected && (
        <p className="text-brand-400 text-xs font-semibold mt-2">
          Selected — applies at checkout
        </p>
      )}
    </div>
  );
}

function RestaurantPageInner() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const router = useRouter();
  // ?deal= carries a tap-through from the Deals page: highlight that deal in
  // the strip and persist it so checkout can auto-apply it.
  const searchParams = useSearchParams();
  const preselectedDealId = searchParams?.get("deal") ?? null;

  const [restaurant, setRestaurant] = useState<Restaurant | null>(null);
  const [menu, setMenu] = useState<MenuCategory[]>([]);
  const [restaurantDeals, setRestaurantDeals] = useState<Deal[]>([]);
  const [certificateOpen, setCertificateOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [cart, setCart] = useState<LocalCartItem[]>([]);
  const [activeCategory, setActiveCategory] = useState<string | undefined>(undefined);
  const [modalItem, setModalItem] = useState<MenuItem | null>(null);

  // The restaurant a NON-EMPTY server cart belongs to (null = empty cart or
  // not yet loaded). The backend silently wipes the whole cart when an add
  // comes in for a different restaurant, so addToCart uses this to confirm
  // with the user before that happens. A ref, not state: it's only ever read
  // inside the add handler right after awaiting cartReadyRef, where state
  // from the render closure would be stale; nothing renders from it.
  const serverCartRestaurantRef = useRef<{ id: string; name: string | null } | null>(
    null
  );
  // Resolves when the initial server-cart fetch settles (success OR failure).
  // addToCart awaits this so the different-restaurant guard can never race
  // ahead of the fetch and skip the confirmation.
  const cartReadyRef = useRef<Promise<void>>(Promise.resolve());

  useEffect(() => {
    if (!id) return;
    void loadAll(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Hydrate the sidebar / badges / mobile bar from the SERVER cart on load
  // (previously the optimistic state always started empty, so a returning
  // user's cart was invisible here), and record which restaurant a non-empty
  // cart belongs to so the switch-restaurants confirmation has something to
  // check against. Best-effort on failure: no hydration, no pre-add prompt —
  // matching the previous behaviour of never blocking adds.
  useEffect(() => {
    if (!id) return;
    const token =
      typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    let cancelled = false;
    const publish = (value: { id: string; name: string | null } | null) => {
      serverCartRestaurantRef.current = value;
    };
    publish(null);
    setCart([]);
    if (!token) {
      // Logged out — nothing to fetch; adds are unblocked (they redirect to
      // /auth anyway).
      cartReadyRef.current = Promise.resolve();
      return;
    }
    cartReadyRef.current = (async () => {
      try {
        const c = (await cartApi.get(token)) as Cart;
        const items = c.items ?? [];
        if (!c.restaurant_id || items.length === 0) {
          if (!cancelled) publish(null);
          return;
        }
        if (c.restaurant_id === id) {
          // The server cart is THIS restaurant's — reconcile the optimistic
          // state with the server lines. Lines that share an identity key
          // (same item + modifiers, e.g. differing only by notes) merge into
          // one display line.
          const byKey = new Map<string, LocalCartItem>();
          for (const it of items) {
            const mods = it.selected_modifiers ?? [];
            const key = cartLineKey(it.menu_item_id, mods.map((m) => m.id));
            const existing = byKey.get(key);
            if (existing) {
              existing.quantity += it.quantity;
            } else {
              byKey.set(key, {
                key,
                menuItemId: it.menu_item_id,
                name: it.name,
                unitPrice: it.price,
                quantity: it.quantity,
                modifiers: mods,
              });
            }
          }
          if (!cancelled) {
            setCart([...byKey.values()]);
            publish({ id, name: null });
          }
          return;
        }
        // Fetch the other restaurant's name for the confirmation copy —
        // best-effort, fall back to generic wording.
        const name = await restaurantsApi
          .get(c.restaurant_id)
          .then((r) => (r as Restaurant).name)
          .catch(() => null);
        if (!cancelled) publish({ id: c.restaurant_id, name });
      } catch {
        // Cart unavailable — skip hydration and the pre-add check rather
        // than block adds.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  // Persist the Deals-page tap-through once the strip confirms the deal is
  // still live for this restaurant (the strip is server-filtered to active +
  // unexpired). CheckoutPanel consumes this and auto-applies the deal.
  useEffect(() => {
    if (!id || !preselectedDealId) return;
    if (!restaurantDeals.some((d) => d.id === preselectedDealId)) return;
    savePreselectedDeal({ restaurant_id: id, deal_id: preselectedDealId });
  }, [id, preselectedDealId, restaurantDeals]);

  async function loadAll(restaurantId: string) {
    setLoading(true);
    setLoadError(null);
    try {
      const token =
        typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
      const [r, m, d] = await Promise.all([
        restaurantsApi.get(restaurantId) as Promise<Restaurant>,
        restaurantsApi.getMenu(restaurantId) as Promise<MenuCategory[]>,
        // Deals are decorative — a failure here must never take down the
        // whole page, so swallow errors into an empty strip.
        dealsApi.forRestaurant(restaurantId, token ?? undefined).catch(() => [] as Deal[]),
      ]);
      setRestaurant(r);
      const categories = [...m].sort((a, b) => a.sort_order - b.sort_order);
      setMenu(categories);
      setActiveCategory(categories[0]?.id);
      setRestaurantDeals(d);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : "Failed to load restaurant");
    } finally {
      setLoading(false);
    }
  }

  // Logged-out (or expired) users go to /auth with a next= pointing back at
  // this page (path + query, so a ?deal= tap-through survives too) — the auth
  // page returns them here instead of dumping them on home mid-order.
  function redirectToAuth() {
    const next =
      typeof window !== "undefined"
        ? window.location.pathname + window.location.search
        : `/restaurant/${id}`;
    router.push(`/auth?next=${encodeURIComponent(next)}`);
  }

  // Every add goes through the customize modal (mirrors the iOS flow where
  // the menu row opens AddToCartSheet) — even items without modifier groups,
  // so quantity + notes are always available.
  function openItem(item: MenuItem) {
    if (!item.is_available) return;
    const token = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!token) {
      redirectToAuth();
      return;
    }
    setModalItem(item);
  }

  // Called by MenuItemModal on Add. Throws on failure so the modal can show
  // the error inline and stay open; on success we update the optimistic
  // sidebar and close the modal ourselves.
  async function addToCart(item: MenuItem, selection: MenuItemSelection) {
    if (!id) return;
    const token = typeof window !== "undefined" ? window.localStorage.getItem("token") : null;
    if (!token) {
      redirectToAuth();
      return;
    }

    // Never race the initial server-cart fetch: until it settles we don't
    // know whether this add would silently wipe another restaurant's cart.
    // The fetch is bounded by the API timeout, and the modal shows its
    // "Adding…" spinner while we wait. Read the settled value from the ref —
    // the state in this closure may predate the fetch resolving.
    await cartReadyRef.current;
    const existingCart = serverCartRestaurantRef.current;

    // Adding from a different restaurant makes the backend silently clear the
    // existing cart — never let that happen without an explicit confirmation.
    if (existingCart && existingCart.id !== id) {
      const clears = existingCart.name
        ? `This clears your items from ${existingCart.name}.`
        : "This clears the items already in your cart from another restaurant.";
      const confirmed = window.confirm(
        `Start a new cart from ${restaurant?.name ?? "this restaurant"}? ${clears}`
      );
      if (!confirmed) return;
      // The confirmed add replaces the other restaurant's cart server-side;
      // local lines only ever belong to THIS restaurant, so this is purely
      // defensive against a stale sidebar.
      setCart([]);
    }

    try {
      await cartApi.addItem(token, {
        menu_item_id: item.id,
        restaurant_id: id,
        quantity: selection.quantity,
        notes: selection.notes,
        modifier_ids: selection.modifier_ids,
      });
    } catch (err) {
      if (isUnauthorized(err)) {
        window.localStorage.removeItem("token");
        redirectToAuth();
        return;
      }
      throw err;
    }

    // The server cart now belongs to this restaurant and is non-empty.
    serverCartRestaurantRef.current = { id, name: null };

    const key = cartLineKey(item.id, selection.modifier_ids);
    setCart((prev) => {
      const existing = prev.find((c) => c.key === key);
      if (existing) {
        return prev.map((c) =>
          c.key === key ? { ...c, quantity: c.quantity + selection.quantity } : c
        );
      }
      return [
        ...prev,
        {
          key,
          menuItemId: item.id,
          name: item.name,
          unitPrice: selection.unit_price,
          quantity: selection.quantity,
          modifiers: selection.selected_modifiers,
        },
      ];
    });
    setModalItem(null);
  }

  const cartTotal = cart.reduce((sum, item) => sum + item.unitPrice * item.quantity, 0);
  const cartCount = cart.reduce((sum, item) => sum + item.quantity, 0);

  if (loading) {
    return (
      <>
        <Header />
        <main className="flex-1" aria-hidden="true">
          <div className="h-64 bg-dark-900 animate-pulse" />
          <div className="max-w-7xl mx-auto px-4 py-6 animate-pulse">
            <div className="h-4 w-2/3 bg-dark-800 rounded mb-6" />
            <div className="card p-5 mb-8 space-y-3">
              <div className="h-5 w-48 bg-dark-800 rounded" />
              <div className="h-10 w-full bg-dark-800 rounded-xl" />
            </div>
            <div className="space-y-3">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="card p-4 flex justify-between items-start gap-4">
                  <div className="flex-1 space-y-2">
                    <div className="h-5 w-1/3 bg-dark-800 rounded" />
                    <div className="h-4 w-2/3 bg-dark-800 rounded" />
                    <div className="h-4 w-16 bg-dark-800 rounded" />
                  </div>
                  <div className="h-9 w-16 bg-dark-800 rounded-xl" />
                </div>
              ))}
            </div>
          </div>
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
            <div className="mb-2">
              <KosherBadge restaurant={rest} size="regular" />
            </div>
            <h1 className="text-3xl sm:text-4xl font-extrabold break-words">{rest.name}</h1>
          </div>
        </div>

        {/* pb-36 while the fixed mobile cart bar is showing so it can never
            cover the last menu item; lg+ has the sidebar instead of the bar. */}
        <div
          className={`max-w-7xl mx-auto px-4 py-6 ${
            cartCount > 0 ? "pb-36 lg:pb-6" : ""
          }`}
        >
          {/* Restaurant Info */}
          <div className="flex flex-wrap items-center gap-4 mb-6">
            <div className="flex items-center gap-1">
              <Star className="w-5 h-5 text-brand-400 fill-brand-400" aria-hidden="true" />
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
              {formatUSD(rest.delivery_fee)} delivery
            </span>
            {rest.min_order > 0 && (
              <>
                <span className="text-dark-600">·</span>
                <span className="text-dark-400">
                  {formatUSD(rest.min_order)} min order
                </span>
              </>
            )}
          </div>

          <p className="text-dark-300 mb-8 max-w-3xl">{rest.description}</p>

          {/* Kashrus Information — certification-first: this section leads
              the page, above deals and the menu (mirrors the iOS
              kashrusSection). */}
          <section className="card p-5 mb-8" aria-label="Kashrus information">
            <h2 className="text-lg font-bold mb-4">Kashrus Information</h2>

            <div className="flex flex-wrap gap-3 mb-4">
              <KashrusChip
                icon={ShieldCheck}
                iconColor="text-brand-400"
                title={rest.kosher_certification}
                subtitle="Certification"
              />
              {rest.is_glatt_kosher && (
                <KashrusChip
                  icon={CheckCircle2}
                  iconColor="text-green-400"
                  title="Glatt"
                  subtitle="Kosher"
                />
              )}
              {rest.is_cholov_yisroel && (
                <KashrusChip
                  icon={Droplets}
                  iconColor="text-blue-400"
                  title="Cholov"
                  subtitle="Yisroel"
                />
              )}
              {rest.is_pas_yisroel && (
                <KashrusChip
                  icon={Cake}
                  iconColor="text-amber-400"
                  title="Pas"
                  subtitle="Yisroel"
                />
              )}
            </div>

            {rest.certifying_agency && (
              <div className="flex items-center gap-2 text-sm text-dark-300 mb-4">
                <Building2 className="w-4 h-4 text-dark-400 flex-shrink-0" aria-hidden="true" />
                <span>Certifying Agency: {rest.certifying_agency}</span>
              </div>
            )}

            {rest.kosher_certificate_url && rest.kosher_certificate_url.trim() !== "" ? (
              <button
                onClick={() => setCertificateOpen(true)}
                className="w-full sm:w-auto sm:px-6 flex items-center justify-center gap-2 bg-brand-500/10 hover:bg-brand-500/20 text-brand-400 font-semibold text-sm py-2.5 min-h-[44px] rounded-xl transition-colors"
                aria-label={`View kosher certificate for ${rest.name}`}
              >
                <FileText className="w-4 h-4" aria-hidden="true" />
                View Kosher Certificate
              </button>
            ) : (
              // No certificate photo uploaded yet — reassuring fallback in
              // place of the viewer button, never a broken image.
              <div className="flex items-center gap-2 text-sm text-dark-300 bg-dark-800 rounded-xl px-3 py-2.5 min-h-[44px] w-full sm:w-auto sm:inline-flex">
                <FileText className="w-4 h-4 text-dark-400 flex-shrink-0" aria-hidden="true" />
                <span>Certificate on file with KosherEats</span>
              </div>
            )}
          </section>

          {/* Per-restaurant deals strip */}
          {restaurantDeals.length > 0 && (
            <section className="mb-8" aria-label="Deals">
              <h2 className="text-lg font-bold mb-4 flex items-center gap-2">
                <Tag className="w-5 h-5 text-brand-400" aria-hidden="true" />
                Deals
              </h2>
              <div className="flex gap-4 overflow-x-auto pb-2">
                {/* Preselected deal (Deals-page tap-through) leads the strip
                    so it's visible without scrolling. */}
                {[...restaurantDeals]
                  .sort((a, b) =>
                    a.id === preselectedDealId ? -1 : b.id === preselectedDealId ? 1 : 0
                  )
                  .map((deal) => (
                    <DealCard
                      key={deal.id}
                      deal={deal}
                      selected={deal.id === preselectedDealId}
                    />
                  ))}
              </div>
            </section>
          )}

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
                      className={`px-4 py-2 min-h-[44px] rounded-full text-sm font-medium whitespace-nowrap transition-colors ${
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
                        // Total quantity of this menu item across all cart
                        // lines (each modifier combination is its own line).
                        const inCartQty = cart
                          .filter((c) => c.menuItemId === item.id)
                          .reduce((sum, c) => sum + c.quantity, 0);
                        return (
                          <div
                            key={item.id}
                            className="card p-4 flex justify-between items-start gap-4 hover:border-dark-600 transition-colors"
                          >
                            <div className="flex-1">
                              <div className="flex flex-wrap items-center gap-2 mb-1">
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
                                {formatUSD(item.price)}
                              </span>
                            </div>

                            <div className="flex items-center gap-2">
                              {inCartQty > 0 ? (
                                // 44px stepper button (w-11 h-11) — minimum
                                // touch target.
                                <div className="flex items-center gap-2 bg-dark-800 rounded-xl px-1.5 py-1.5">
                                  <span className="font-semibold w-6 text-center">
                                    {inCartQty}
                                  </span>
                                  <button
                                    onClick={() => openItem(item)}
                                    disabled={!item.is_available}
                                    aria-label={`Add another ${item.name}`}
                                    className="w-11 h-11 rounded-full bg-brand-500 hover:bg-brand-600 disabled:opacity-50 flex items-center justify-center text-white transition-colors"
                                  >
                                    +
                                  </button>
                                </div>
                              ) : (
                                <button
                                  onClick={() => openItem(item)}
                                  disabled={!item.is_available}
                                  className="bg-dark-800 hover:bg-dark-700 border border-dark-700 hover:border-brand-500 disabled:opacity-50 disabled:hover:border-dark-700 text-white px-4 py-2 min-h-[44px] rounded-xl text-sm font-medium transition-colors"
                                >
                                  {!item.is_available ? "Unavailable" : "Add"}
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
                        <div key={item.key} className="flex justify-between items-start">
                          <div className="min-w-0 pr-2">
                            <span className="text-sm font-medium">
                              {item.quantity}x {item.name}
                            </span>
                            {item.modifiers.length > 0 && (
                              <p className="text-xs text-dark-400 mt-0.5">
                                {item.modifiers.map((m) => m.name).join(" • ")}
                              </p>
                            )}
                          </div>
                          <span className="text-sm text-dark-300 flex-shrink-0">
                            {formatUSD(item.unitPrice * item.quantity)}
                          </span>
                        </div>
                      ))}
                    </div>
                    {/* Only the subtotal is knowable here — the real
                        delivery fee is a per-address quote, and tax/service
                        fee are computed at checkout. No fake "Total". */}
                    <div className="border-t border-dark-700 pt-3 mb-4">
                      <div className="flex justify-between font-semibold">
                        <span>Subtotal</span>
                        <span className="text-brand-400">{formatUSD(cartTotal)}</span>
                      </div>
                      <p className="text-xs text-dark-400 mt-1.5">
                        Delivery, fees &amp; tax estimated at checkout
                      </p>
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

        {/* Mobile Cart Bar — safe-area padding keeps the checkout button
            clear of the iOS home indicator. */}
        {cartCount > 0 && (
          <div className="lg:hidden fixed bottom-0 left-0 right-0 bg-dark-900 border-t border-dark-800 p-4 pb-[calc(1rem+env(safe-area-inset-bottom))] z-50">
            <a
              href="/cart"
              className="btn-primary w-full flex items-center justify-between"
            >
              <span className="bg-brand-600 px-2.5 py-0.5 rounded-lg text-sm font-bold">
                {cartCount}
              </span>
              <span className="font-semibold">Go to Checkout</span>
              {/* Subtotal only — delivery/fees/tax are quoted at checkout. */}
              <span className="text-right leading-tight">
                <span className="block text-[10px] font-medium opacity-80">
                  Subtotal
                </span>
                <span className="font-semibold">{formatUSD(cartTotal)}</span>
              </span>
            </a>
          </div>
        )}

        {/* Customize & add-to-cart modal */}
        {modalItem && (
          <MenuItemModal
            item={modalItem}
            onClose={() => setModalItem(null)}
            onSubmit={(selection) => addToCart(modalItem, selection)}
          />
        )}

        {/* Full-screen certificate viewer */}
        {certificateOpen && (
          <KosherCertificateModal
            url={rest.kosher_certificate_url}
            restaurantName={rest.name}
            onClose={() => setCertificateOpen(false)}
          />
        )}
      </main>
    </>
  );
}

export default function RestaurantPage() {
  return (
    // useSearchParams (?deal= from the Deals page) requires a Suspense
    // boundary — same pattern as /search.
    <Suspense
      fallback={
        <>
          <Header />
          <main className="flex-1 max-w-7xl mx-auto px-4 py-8 w-full" />
        </>
      }
    >
      <RestaurantPageInner />
    </Suspense>
  );
}
