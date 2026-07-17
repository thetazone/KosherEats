"use client";

// Seller deals management (S20). Mirrors the iOS seller app's DealsView +
// CreateDealView: GET /seller/deals lists every deal (active, scheduled,
// expired, deactivated), POST /seller/deals creates a percentage / fixed /
// BOGO promotion with an optional linked menu item, min-order floor and a
// required expiry, and DELETE /seller/deals/{dealId} soft-deactivates.
// Money is integer cents on the wire; the form takes dollars and converts
// via parseCents. Validation mirrors deals.go: title <= 200, description
// <= 2000, percentage 1-100, fixed 1..10000 cents, expiry in the future.

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { BadgePercent, ImageIcon, Loader2, Plus, Store, X } from "lucide-react";
import { PhotoUpload } from "@/components/seller/PhotoUpload";
import { formatCents, parseCents, sellerApi } from "@/lib/sellerApi";
import type { CreateDealRequest, DiscountType, SellerDeal, SellerMenuItem } from "@/types/seller";

// ── Display helpers ──────────────────────────────────────────

function discountLabel(deal: SellerDeal): string {
  switch (deal.discount_type) {
    case "percentage":
      return `${deal.discount_value}% off`;
    case "fixed":
      return `${formatCents(deal.discount_value)} off`;
    case "bogo":
      return "Buy one, get one";
  }
}

type DealStatus = "active" | "scheduled" | "expired" | "deactivated";

function dealStatus(deal: SellerDeal): DealStatus {
  if (!deal.is_active) return "deactivated";
  const now = Date.now();
  if (new Date(deal.expires_at).getTime() <= now) return "expired";
  if (new Date(deal.starts_at).getTime() > now) return "scheduled";
  return "active";
}

const STATUS_BADGE: Record<DealStatus, { label: string; className: string }> = {
  active: { label: "Active", className: "bg-green-500/15 text-green-400" },
  scheduled: { label: "Scheduled", className: "bg-blue-500/15 text-blue-400" },
  expired: { label: "Expired", className: "bg-amber-500/15 text-amber-400" },
  deactivated: { label: "Deactivated", className: "bg-dark-700 text-dark-400" },
};

function formatExpiry(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

/** Format a Date for a <input type="datetime-local"> value (local time). */
function toDatetimeLocal(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}`;
}

// ── Page ─────────────────────────────────────────────────────

export default function SellerDealsPage() {
  const [deals, setDeals] = useState<SellerDeal[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Mutation failures surface here without touching the page-level load error.
  const [actionError, setActionError] = useState<string | null>(null);

  const [showCreate, setShowCreate] = useState(false);
  const [deactivateTarget, setDeactivateTarget] = useState<SellerDeal | null>(null);
  const [deactivating, setDeactivating] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDeals(await sellerApi.deals.list());
    } catch (err) {
      setError((err as Error).message || "Failed to load deals");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function onCreated(deal: SellerDeal) {
    // Backend lists newest-first (ORDER BY created_at DESC), so prepend.
    setDeals((prev) => [deal, ...prev]);
    setShowCreate(false);
  }

  async function confirmDeactivate() {
    if (!deactivateTarget || deactivating) return;
    setDeactivating(true);
    setActionError(null);
    try {
      await sellerApi.deals.deactivate(deactivateTarget.id);
      const id = deactivateTarget.id;
      setDeals((prev) => prev.map((d) => (d.id === id ? { ...d, is_active: false } : d)));
      setDeactivateTarget(null);
    } catch (err) {
      setActionError((err as Error).message || "Couldn't deactivate the deal");
      setDeactivateTarget(null);
    } finally {
      setDeactivating(false);
    }
  }

  // ── Render ─────────────────────────────────────────────────

  if (loading) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Deals</h1>
        <div className="space-y-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="card p-5 h-28 animate-pulse" aria-hidden="true" />
          ))}
        </div>
      </div>
    );
  }

  if (error) {
    const noRestaurant = error.toLowerCase().includes("restaurant not found");
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Deals</h1>
        <div className="card p-10 text-center">
          {noRestaurant ? (
            <>
              <Store className="w-10 h-10 text-dark-500 mx-auto mb-3" aria-hidden="true" />
              <p className="font-semibold mb-1">No restaurant yet</p>
              <p className="text-sm text-dark-400 mb-5">
                Set up your restaurant before creating deals.
              </p>
              <Link href="/seller/onboarding" className="btn-primary inline-block">
                Set up your restaurant
              </Link>
            </>
          ) : (
            <>
              <p className="text-red-400 mb-4">{error}</p>
              <button onClick={load} className="btn-secondary">
                Try again
              </button>
            </>
          )}
        </div>
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between gap-3 mb-6">
        <h1 className="text-2xl font-bold">Deals</h1>
        <button
          onClick={() => {
            setShowCreate(true);
            setActionError(null);
          }}
          className="flex items-center gap-1.5 text-sm font-semibold text-brand-500 hover:text-brand-400 transition-colors"
        >
          <Plus className="w-4 h-4" aria-hidden="true" />
          New deal
        </button>
      </div>

      {actionError && (
        <div className="flex items-center justify-between gap-4 mb-4 px-4 py-2.5 rounded-xl border border-red-500/30 bg-red-500/10 text-red-300 text-sm">
          <span>{actionError}</span>
          <button
            onClick={() => setActionError(null)}
            aria-label="Dismiss error"
            className="p-1 rounded-lg hover:bg-red-500/20 transition-colors shrink-0"
          >
            <X className="w-4 h-4" aria-hidden="true" />
          </button>
        </div>
      )}

      {deals.length === 0 ? (
        <div className="card p-10 text-center">
          <BadgePercent className="w-10 h-10 text-dark-500 mx-auto mb-3" aria-hidden="true" />
          <p className="font-semibold mb-1">No deals yet</p>
          <p className="text-sm text-dark-400 mb-5">
            Create a limited-time promotion to bring in more orders.
          </p>
          <button onClick={() => setShowCreate(true)} className="btn-primary">
            Create a deal
          </button>
        </div>
      ) : (
        <ul className="space-y-4">
          {deals.map((deal) => (
            <DealCard key={deal.id} deal={deal} onDeactivate={() => setDeactivateTarget(deal)} />
          ))}
        </ul>
      )}

      {showCreate && <CreateDealModal onCreated={onCreated} onClose={() => setShowCreate(false)} />}

      {deactivateTarget && (
        <ConfirmDeactivateDialog
          deal={deactivateTarget}
          busy={deactivating}
          onCancel={() => setDeactivateTarget(null)}
          onConfirm={confirmDeactivate}
        />
      )}
    </div>
  );
}

// ── Deal card ────────────────────────────────────────────────

function DealCard({ deal, onDeactivate }: { deal: SellerDeal; onDeactivate: () => void }) {
  const status = dealStatus(deal);
  const badge = STATUS_BADGE[status];
  const dimmed = status === "deactivated" || status === "expired";

  return (
    <li className="card p-4 sm:p-5 flex items-start gap-4">
      {/* Thumbnail */}
      <div
        className={`w-16 h-16 rounded-lg overflow-hidden bg-dark-800 border border-dark-700 shrink-0 ${
          dimmed ? "opacity-50" : ""
        }`}
      >
        {deal.image_url ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={deal.image_url} alt={deal.title} className="w-full h-full object-cover" />
        ) : (
          <div className="w-full h-full flex items-center justify-center text-dark-600">
            <ImageIcon className="w-5 h-5" aria-hidden="true" />
          </div>
        )}
      </div>

      <div className={`flex-1 min-w-0 ${dimmed ? "opacity-50" : ""}`}>
        <div className="flex items-center gap-2 flex-wrap">
          <span className="font-semibold truncate">{deal.title}</span>
          <span className={`text-[11px] font-semibold px-1.5 py-0.5 rounded ${badge.className}`}>
            {badge.label}
          </span>
        </div>
        <div className="text-sm text-brand-400 font-semibold mt-0.5">{discountLabel(deal)}</div>
        {deal.description && (
          <p className="text-xs text-dark-400 mt-0.5 line-clamp-1">{deal.description}</p>
        )}
        <p className="text-xs text-dark-500 mt-1.5">
          {(deal.min_order_amount ?? 0) > 0 && (
            <>
              Min order {formatCents(deal.min_order_amount ?? 0)}
              <span className="mx-1.5" aria-hidden="true">
                ·
              </span>
            </>
          )}
          {status === "expired" ? "Expired" : "Expires"} {formatExpiry(deal.expires_at)}
        </p>
      </div>

      {status === "active" || status === "scheduled" ? (
        <button
          onClick={onDeactivate}
          className="text-xs font-semibold text-red-400 hover:text-red-300 px-2 py-1.5 rounded-lg hover:bg-red-500/10 transition-colors shrink-0"
        >
          Deactivate
        </button>
      ) : null}
    </li>
  );
}

// ── Create deal modal ────────────────────────────────────────

const DISCOUNT_TYPES: { value: DiscountType; label: string }[] = [
  { value: "percentage", label: "% off" },
  { value: "fixed", label: "$ off" },
  { value: "bogo", label: "BOGO" },
];

function CreateDealModal({
  onCreated,
  onClose,
}: {
  onCreated: (deal: SellerDeal) => void;
  onClose: () => void;
}) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [discountType, setDiscountType] = useState<DiscountType>("percentage");
  const [discountValue, setDiscountValue] = useState("");
  const [minOrder, setMinOrder] = useState("");
  const [expiresAt, setExpiresAt] = useState(() =>
    toDatetimeLocal(new Date(Date.now() + 7 * 24 * 60 * 60 * 1000)),
  );
  const [imageUrl, setImageUrl] = useState("");
  const [menuItemId, setMenuItemId] = useState("");

  // Optional menu-item link — the backend validates ownership and borrows the
  // item's photo when the deal has none (CreateDeal in deals.go).
  const [menuItems, setMenuItems] = useState<SellerMenuItem[]>([]);
  const [menuLoaded, setMenuLoaded] = useState(false);

  const [uploadsInFlight, setUploadsInFlight] = useState(0);
  const trackUpload = {
    start: () => setUploadsInFlight((n) => n + 1),
    end: () => setUploadsInFlight((n) => Math.max(0, n - 1)),
  };

  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const categories = await sellerApi.menu.get();
        if (cancelled) return;
        setMenuItems(categories.flatMap((c) => c.items ?? []));
      } catch {
        // Non-fatal — the picker just stays empty and the deal is general.
      } finally {
        if (!cancelled) setMenuLoaded(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  function validate(): { body: CreateDealRequest } | { problem: string } {
    const trimmedTitle = title.trim();
    if (!trimmedTitle) return { problem: "Title is required." };
    if (trimmedTitle.length > 200) return { problem: "Title is too long (max 200 characters)." };
    if (description.trim().length > 2000) {
      return { problem: "Description is too long (max 2000 characters)." };
    }

    let value = 0;
    if (discountType === "percentage") {
      const v = Number(discountValue.trim());
      if (!Number.isInteger(v) || v < 1 || v > 100) {
        return { problem: "Percentage discount must be a whole number between 1 and 100." };
      }
      value = v;
    } else if (discountType === "fixed") {
      const cents = parseCents(discountValue);
      if (cents === null || cents < 1) {
        return { problem: "Enter a valid discount amount in dollars, e.g. 5.00." };
      }
      if (cents > 10000) return { problem: "Fixed discount cannot exceed $100." };
      value = cents;
    }

    let minOrderCents: number | undefined;
    if (minOrder.trim()) {
      const cents = parseCents(minOrder);
      if (cents === null) {
        return { problem: "Enter a valid minimum order amount in dollars, e.g. 20.00." };
      }
      minOrderCents = cents;
    }

    const expiry = new Date(expiresAt);
    if (!expiresAt || Number.isNaN(expiry.getTime())) {
      return { problem: "Pick an expiration date and time." };
    }
    if (expiry.getTime() <= Date.now()) {
      return { problem: "Expiration must be in the future." };
    }

    return {
      body: {
        title: trimmedTitle,
        description: description.trim() || undefined,
        image_url: imageUrl || undefined,
        menu_item_id: menuItemId || undefined,
        discount_type: discountType,
        discount_value: value,
        min_order_amount: minOrderCents,
        expires_at: expiry.toISOString(),
      },
    };
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    if (uploadsInFlight > 0) {
      setFormError("The photo is still uploading — one moment.");
      return;
    }
    const result = validate();
    if ("problem" in result) {
      setFormError(result.problem);
      return;
    }
    setSubmitting(true);
    setFormError(null);
    try {
      onCreated(await sellerApi.deals.create(result.body));
    } catch (err) {
      setFormError((err as Error).message || "Failed to create the deal. Please try again.");
      setSubmitting(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 bg-black/70 flex items-start sm:items-center justify-center p-4 overflow-y-auto"
      role="dialog"
      aria-modal="true"
      aria-label="Create deal"
    >
      <form onSubmit={submit} className="card w-full max-w-lg my-4">
        <div className="flex items-center justify-between px-6 py-4 border-b border-dark-800">
          <h2 className="text-lg font-bold">Create deal</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="p-2 rounded-lg text-dark-400 hover:bg-dark-800 hover:text-white transition-colors"
          >
            <X className="w-4 h-4" aria-hidden="true" />
          </button>
        </div>

        <div className="p-6 space-y-5">
          <div>
            <label htmlFor="deal-title" className="block text-sm text-dark-300 mb-1.5">
              Title <span className="text-brand-500">*</span>
            </label>
            <input
              id="deal-title"
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="e.g. 20% Off First Order"
              maxLength={200}
              autoFocus
              className="input w-full"
            />
          </div>

          <div>
            <label htmlFor="deal-description" className="block text-sm text-dark-300 mb-1.5">
              Description <span className="text-dark-500">(optional)</span>
            </label>
            <textarea
              id="deal-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
              maxLength={2000}
              placeholder="Optional details customers see on the deal"
              className="input w-full resize-none"
            />
          </div>

          {/* Discount type + value */}
          <div>
            <span className="block text-sm text-dark-300 mb-2">Discount</span>
            <div
              className="grid grid-cols-3 gap-2 mb-3"
              role="radiogroup"
              aria-label="Discount type"
            >
              {DISCOUNT_TYPES.map((t) => (
                <button
                  key={t.value}
                  type="button"
                  role="radio"
                  aria-checked={discountType === t.value}
                  onClick={() => {
                    setDiscountType(t.value);
                    setDiscountValue("");
                  }}
                  className={`py-2 px-3 rounded-xl text-sm font-semibold transition-colors ${
                    discountType === t.value
                      ? "bg-brand-500 text-white"
                      : "bg-dark-800 text-dark-300 border border-dark-700 hover:bg-dark-700 hover:text-white"
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>
            {discountType === "bogo" ? (
              <p className="text-xs text-dark-500">
                Buy one, get one free — the cheapest item in the cart is free when the order has
                at least 2 items.
              </p>
            ) : (
              <div>
                <label htmlFor="deal-value" className="block text-sm text-dark-300 mb-1.5">
                  {discountType === "percentage" ? "Percentage (1–100)" : "Amount ($, max $100)"}
                </label>
                <input
                  id="deal-value"
                  type="text"
                  inputMode="decimal"
                  value={discountValue}
                  onChange={(e) => setDiscountValue(e.target.value)}
                  placeholder={discountType === "percentage" ? "e.g. 20" : "e.g. 5.00"}
                  className="input w-full"
                />
              </div>
            )}
          </div>

          <div>
            <label htmlFor="deal-min-order" className="block text-sm text-dark-300 mb-1.5">
              Minimum order ($) <span className="text-dark-500">(optional)</span>
            </label>
            <input
              id="deal-min-order"
              type="text"
              inputMode="decimal"
              value={minOrder}
              onChange={(e) => setMinOrder(e.target.value)}
              placeholder="e.g. 20.00"
              className="input w-full"
            />
            <p className="text-xs text-dark-500 mt-1.5">
              The deal only applies to orders at or above this subtotal.
            </p>
          </div>

          <div>
            <label htmlFor="deal-expires" className="block text-sm text-dark-300 mb-1.5">
              Expires <span className="text-brand-500">*</span>
            </label>
            <input
              id="deal-expires"
              type="datetime-local"
              value={expiresAt}
              min={toDatetimeLocal(new Date())}
              onChange={(e) => setExpiresAt(e.target.value)}
              className="input w-full"
            />
            <p className="text-xs text-dark-500 mt-1.5">
              When this deal stops being available to customers.
            </p>
          </div>

          {/* Optional menu-item link */}
          <div>
            <label htmlFor="deal-item" className="block text-sm text-dark-300 mb-1.5">
              Linked menu item <span className="text-dark-500">(optional)</span>
            </label>
            <select
              id="deal-item"
              value={menuItemId}
              onChange={(e) => setMenuItemId(e.target.value)}
              disabled={!menuLoaded || menuItems.length === 0}
              className="input w-full cursor-pointer disabled:opacity-50"
            >
              <option value="">General deal — not tied to a menu item</option>
              {menuItems.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name} ({formatCents(item.price)})
                </option>
              ))}
            </select>
            <p className="text-xs text-dark-500 mt-1.5">
              {menuLoaded && menuItems.length === 0
                ? "No menu items yet — the deal will be a general one."
                : "Linking an item shows it on the deal; its photo is used if you don't add one."}
            </p>
          </div>

          <PhotoUpload
            label="Photo (optional)"
            kind="deal"
            value={imageUrl}
            onChange={setImageUrl}
            aspectClass="aspect-video"
            track={trackUpload}
          />

          {formError && (
            <div
              role="alert"
              className="bg-red-900/30 border border-red-800 text-red-400 rounded-xl px-4 py-3 text-sm"
            >
              {formError}
            </div>
          )}
        </div>

        <div className="flex gap-3 px-6 py-4 border-t border-dark-800">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="btn-secondary flex-1 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={submitting || uploadsInFlight > 0}
            className="btn-primary flex-1 flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
            {submitting ? "Creating…" : "Create deal"}
          </button>
        </div>
      </form>
    </div>
  );
}

// ── Deactivate confirmation ──────────────────────────────────

function ConfirmDeactivateDialog({
  deal,
  busy,
  onCancel,
  onConfirm,
}: {
  deal: SellerDeal;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div
      className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Deactivate deal"
    >
      <div className="card w-full max-w-sm p-6">
        <h2 className="text-lg font-bold mb-2">Deactivate deal</h2>
        <p className="text-sm text-dark-400 mb-6">
          Are you sure you want to deactivate &ldquo;{deal.title}&rdquo;? Customers will no longer
          be able to use it. This cannot be undone.
        </p>
        <div className="flex gap-3">
          <button
            onClick={onCancel}
            disabled={busy}
            className="btn-secondary flex-1 py-2.5 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Cancel
          </button>
          <button
            onClick={onConfirm}
            disabled={busy}
            className="flex-1 py-2.5 px-6 rounded-xl font-semibold bg-red-500 hover:bg-red-600 text-white transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {busy ? "Deactivating…" : "Deactivate"}
          </button>
        </div>
      </div>
    </div>
  );
}
