"use client";

// Seller menu management (S17). Mirrors the iOS seller app's
// MenuManagementView: the full menu grouped by category (including paused
// items — GET /seller/menu skips the is_available filter), inline category
// create/delete, per-item availability toggles, and the MenuItemForm modal
// for item create/edit. Deletes confirm first (category deletes cascade to
// their items — migration 001 has ON DELETE CASCADE on menu_items).

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import {
  ImageIcon,
  Pencil,
  Plus,
  Store,
  Trash2,
  UtensilsCrossed,
  X,
} from "lucide-react";
import { MenuItemForm } from "@/components/seller/MenuItemForm";
import { formatCents, sellerApi } from "@/lib/sellerApi";
import type { SellerMenuCategory, SellerMenuItem } from "@/types/seller";

/** The item form modal: editing an existing item, or creating into a category. */
type FormTarget = { item: SellerMenuItem | null; categoryId?: string };

/** The delete-confirmation modal target. */
type DeleteTarget =
  | { kind: "item"; item: SellerMenuItem }
  | { kind: "category"; category: SellerMenuCategory };

export default function SellerMenuPage() {
  const [categories, setCategories] = useState<SellerMenuCategory[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Mutation failures surface here without touching the page-level load error.
  const [actionError, setActionError] = useState<string | null>(null);

  // Inline "new category" composer.
  const [addingCategory, setAddingCategory] = useState(false);
  const [newCategoryName, setNewCategoryName] = useState("");
  const [creatingCategory, setCreatingCategory] = useState(false);

  // Busy flags for row-level mutations.
  const [togglingItemId, setTogglingItemId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget | null>(null);
  const [deleting, setDeleting] = useState(false);

  const [formTarget, setFormTarget] = useState<FormTarget | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCategories(await sellerApi.menu.get());
    } catch (err) {
      setError((err as Error).message || "Failed to load menu");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // ── Category mutations ─────────────────────────────────────

  async function createCategory() {
    const name = newCategoryName.trim();
    if (!name || creatingCategory) return;
    setCreatingCategory(true);
    setActionError(null);
    try {
      const cat = await sellerApi.menu.createCategory(name);
      // Backend appends at MAX(sort_order)+1, so pushing to the end matches.
      setCategories((prev) => [...prev, { ...cat, items: [] }]);
      setNewCategoryName("");
      setAddingCategory(false);
    } catch (err) {
      setActionError((err as Error).message || "Couldn't create the category");
    } finally {
      setCreatingCategory(false);
    }
  }

  async function confirmDelete() {
    if (!deleteTarget || deleting) return;
    setDeleting(true);
    setActionError(null);
    try {
      if (deleteTarget.kind === "item") {
        await sellerApi.menu.deleteItem(deleteTarget.item.id);
        const itemId = deleteTarget.item.id;
        setCategories((prev) =>
          prev.map((cat) => ({
            ...cat,
            items: cat.items?.filter((i) => i.id !== itemId),
          })),
        );
      } else {
        await sellerApi.menu.deleteCategory(deleteTarget.category.id);
        const catId = deleteTarget.category.id;
        setCategories((prev) => prev.filter((cat) => cat.id !== catId));
      }
      setDeleteTarget(null);
    } catch (err) {
      setActionError((err as Error).message || "Couldn't delete — please try again.");
      setDeleteTarget(null);
    } finally {
      setDeleting(false);
    }
  }

  // ── Item mutations ─────────────────────────────────────────

  async function toggleAvailability(item: SellerMenuItem) {
    if (togglingItemId) return;
    setTogglingItemId(item.id);
    setActionError(null);
    try {
      const updated = await sellerApi.menu.setItemAvailability(item.id, !item.is_available);
      replaceItem(updated);
    } catch (err) {
      setActionError((err as Error).message || "Couldn't update availability");
    } finally {
      setTogglingItemId(null);
    }
  }

  function replaceItem(updated: SellerMenuItem) {
    setCategories((prev) =>
      prev.map((cat) => ({
        ...cat,
        items: cat.items?.map((i) => (i.id === updated.id ? { ...i, ...updated } : i)),
      })),
    );
  }

  /** Reconcile a saved item from the form: insert on create, replace on
   *  update — handling recategorization by moving the item between groups.
   *  The saved record is taken wholesale (image_url has omitempty, so a
   *  removed photo means an ABSENT key — a spread-merge onto the old item
   *  would resurrect the stale URL), except modifier_groups, which item
   *  write responses never include and the form doesn't edit. */
  function onItemSaved(saved: SellerMenuItem, wasCreate: boolean) {
    setCategories((prev) => {
      const prevItem = wasCreate
        ? undefined
        : prev.flatMap((c) => c.items ?? []).find((i) => i.id === saved.id);
      const merged: SellerMenuItem = prevItem
        ? { ...saved, modifier_groups: prevItem.modifier_groups }
        : saved;
      return prev.map((cat) => {
        const items = cat.items ?? [];
        const has = items.some((i) => i.id === saved.id);
        if (cat.id === saved.category_id) {
          return {
            ...cat,
            items: has ? items.map((i) => (i.id === saved.id ? merged : i)) : [...items, merged],
          };
        }
        return has ? { ...cat, items: items.filter((i) => i.id !== saved.id) } : cat;
      });
    });
    setFormTarget(null);
  }

  // ── Render ─────────────────────────────────────────────────

  if (loading) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Menu</h1>
        <div className="space-y-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="card p-5 h-40 animate-pulse" aria-hidden="true" />
          ))}
        </div>
      </div>
    );
  }

  if (error) {
    const noRestaurant = error.toLowerCase().includes("restaurant not found");
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Menu</h1>
        <div className="card p-10 text-center">
          {noRestaurant ? (
            <>
              <Store className="w-10 h-10 text-dark-500 mx-auto mb-3" aria-hidden="true" />
              <p className="font-semibold mb-1">No restaurant yet</p>
              <p className="text-sm text-dark-400 mb-5">
                Set up your restaurant before building a menu.
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
        <h1 className="text-2xl font-bold">Menu</h1>
        <button
          onClick={() => {
            setAddingCategory(true);
            setActionError(null);
          }}
          className="flex items-center gap-1.5 text-sm font-semibold text-brand-500 hover:text-brand-400 transition-colors"
        >
          <Plus className="w-4 h-4" aria-hidden="true" />
          New category
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

      {/* Inline new-category composer */}
      {addingCategory && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            createCategory();
          }}
          className="card p-4 mb-4 flex flex-col sm:flex-row gap-3"
        >
          <input
            type="text"
            value={newCategoryName}
            onChange={(e) => setNewCategoryName(e.target.value)}
            placeholder="Category name, e.g. Appetizers"
            autoFocus
            maxLength={80}
            className="input flex-1 py-2.5"
            aria-label="New category name"
          />
          <div className="flex gap-2 shrink-0">
            <button
              type="submit"
              disabled={!newCategoryName.trim() || creatingCategory}
              className="btn-primary py-2.5 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {creatingCategory ? "Creating…" : "Create"}
            </button>
            <button
              type="button"
              onClick={() => {
                setAddingCategory(false);
                setNewCategoryName("");
              }}
              disabled={creatingCategory}
              className="btn-secondary py-2.5 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Cancel
            </button>
          </div>
        </form>
      )}

      {categories.length === 0 && !addingCategory ? (
        <div className="card p-10 text-center">
          <UtensilsCrossed className="w-10 h-10 text-dark-500 mx-auto mb-3" aria-hidden="true" />
          <p className="font-semibold mb-1">No menu yet</p>
          <p className="text-sm text-dark-400 mb-5">
            Create your first category to start building your menu.
          </p>
          <button onClick={() => setAddingCategory(true)} className="btn-primary">
            Add a category
          </button>
        </div>
      ) : (
        <div className="space-y-4">
          {categories.map((cat) => (
            <CategorySection
              key={cat.id}
              category={cat}
              togglingItemId={togglingItemId}
              onAddItem={() => setFormTarget({ item: null, categoryId: cat.id })}
              onEditItem={(item) => setFormTarget({ item })}
              onDeleteItem={(item) => setDeleteTarget({ kind: "item", item })}
              onDeleteCategory={() => setDeleteTarget({ kind: "category", category: cat })}
              onToggleItem={toggleAvailability}
            />
          ))}
        </div>
      )}

      {formTarget && (
        <MenuItemForm
          categories={categories}
          item={formTarget.item}
          defaultCategoryId={formTarget.categoryId}
          onSaved={onItemSaved}
          onClose={() => setFormTarget(null)}
        />
      )}

      {deleteTarget && (
        <ConfirmDeleteDialog
          target={deleteTarget}
          busy={deleting}
          onCancel={() => setDeleteTarget(null)}
          onConfirm={confirmDelete}
        />
      )}
    </div>
  );
}

// ── Category section ─────────────────────────────────────────

function CategorySection({
  category,
  togglingItemId,
  onAddItem,
  onEditItem,
  onDeleteItem,
  onDeleteCategory,
  onToggleItem,
}: {
  category: SellerMenuCategory;
  togglingItemId: string | null;
  onAddItem: () => void;
  onEditItem: (item: SellerMenuItem) => void;
  onDeleteItem: (item: SellerMenuItem) => void;
  onDeleteCategory: () => void;
  onToggleItem: (item: SellerMenuItem) => void;
}) {
  const items = category.items ?? [];

  return (
    <section className="card">
      <div className="flex items-center justify-between gap-3 px-5 py-4 border-b border-dark-800">
        <div className="flex items-baseline gap-2.5 min-w-0">
          <h2 className="text-base font-bold truncate">{category.name}</h2>
          <span className="text-xs text-dark-500 shrink-0">
            {items.length} {items.length === 1 ? "item" : "items"}
          </span>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button
            onClick={onAddItem}
            className="flex items-center gap-1.5 text-sm font-semibold text-brand-500 hover:text-brand-400 px-2 py-1.5 rounded-lg transition-colors"
          >
            <Plus className="w-4 h-4" aria-hidden="true" />
            Add item
          </button>
          <button
            onClick={onDeleteCategory}
            aria-label={`Delete category ${category.name}`}
            className="p-2 rounded-lg text-dark-400 hover:bg-red-500/10 hover:text-red-400 transition-colors"
          >
            <Trash2 className="w-4 h-4" aria-hidden="true" />
          </button>
        </div>
      </div>

      {items.length === 0 ? (
        <div className="px-5 py-6 text-sm text-dark-500 text-center">
          No items in this category yet.
        </div>
      ) : (
        <ul className="divide-y divide-dark-800">
          {items.map((item) => (
            <MenuItemRow
              key={item.id}
              item={item}
              toggling={togglingItemId === item.id}
              onEdit={() => onEditItem(item)}
              onDelete={() => onDeleteItem(item)}
              onToggle={() => onToggleItem(item)}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

// ── Item row ─────────────────────────────────────────────────

function kosherBadge(item: SellerMenuItem): { label: string; className: string } | null {
  if (item.is_meat) return { label: "Meat", className: "bg-red-500/15 text-red-400" };
  if (item.is_dairy) return { label: "Dairy", className: "bg-blue-500/15 text-blue-400" };
  if (item.is_pareve) return { label: "Pareve", className: "bg-green-500/15 text-green-400" };
  return null;
}

function MenuItemRow({
  item,
  toggling,
  onEdit,
  onDelete,
  onToggle,
}: {
  item: SellerMenuItem;
  toggling: boolean;
  onEdit: () => void;
  onDelete: () => void;
  onToggle: () => void;
}) {
  const badge = kosherBadge(item);

  return (
    <li className="flex items-center gap-4 px-5 py-4">
      {/* Thumbnail */}
      <div
        className={`w-14 h-14 rounded-lg overflow-hidden bg-dark-800 border border-dark-700 shrink-0 ${
          item.is_available ? "" : "opacity-50"
        }`}
      >
        {item.image_url ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={item.image_url} alt={item.name} className="w-full h-full object-cover" />
        ) : (
          <div className="w-full h-full flex items-center justify-center text-dark-600">
            <ImageIcon className="w-5 h-5" aria-hidden="true" />
          </div>
        )}
      </div>

      {/* Name / badge / price / description */}
      <div className={`flex-1 min-w-0 ${item.is_available ? "" : "opacity-50"}`}>
        <div className="flex items-center gap-2 flex-wrap">
          <span className="font-semibold truncate">{item.name}</span>
          {badge && (
            <span
              className={`text-[11px] font-semibold px-1.5 py-0.5 rounded ${badge.className}`}
            >
              {badge.label}
            </span>
          )}
          {!item.is_available && (
            <span className="text-[11px] font-semibold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-400">
              Paused
            </span>
          )}
        </div>
        <div className="text-sm text-brand-400 font-semibold mt-0.5">
          {formatCents(item.price)}
        </div>
        {item.description && (
          <p className="text-xs text-dark-400 mt-0.5 line-clamp-1">{item.description}</p>
        )}
      </div>

      {/* Availability switch */}
      <button
        role="switch"
        aria-checked={item.is_available}
        aria-label={item.is_available ? `Pause ${item.name}` : `Make ${item.name} available`}
        disabled={toggling}
        onClick={onToggle}
        className={`relative w-10 h-6 rounded-full transition-colors shrink-0 disabled:opacity-50 disabled:cursor-wait ${
          item.is_available ? "bg-green-500" : "bg-dark-700"
        }`}
      >
        <span
          className={`absolute top-1 left-1 w-4 h-4 rounded-full bg-white shadow transition-transform ${
            item.is_available ? "translate-x-4" : ""
          }`}
        />
      </button>

      {/* Edit / delete */}
      <div className="flex items-center gap-0.5 shrink-0">
        <button
          onClick={onEdit}
          aria-label={`Edit ${item.name}`}
          className="p-2 rounded-lg text-dark-400 hover:bg-dark-800 hover:text-white transition-colors"
        >
          <Pencil className="w-4 h-4" aria-hidden="true" />
        </button>
        <button
          onClick={onDelete}
          aria-label={`Delete ${item.name}`}
          className="p-2 rounded-lg text-dark-400 hover:bg-red-500/10 hover:text-red-400 transition-colors"
        >
          <Trash2 className="w-4 h-4" aria-hidden="true" />
        </button>
      </div>
    </li>
  );
}

// ── Delete confirmation ──────────────────────────────────────

function ConfirmDeleteDialog({
  target,
  busy,
  onCancel,
  onConfirm,
}: {
  target: DeleteTarget;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const isItem = target.kind === "item";
  const name = isItem ? target.item.name : target.category.name;
  const itemCount = isItem ? 0 : target.category.items?.length ?? 0;

  return (
    <div
      className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-label={isItem ? "Delete item" : "Delete category"}
    >
      <div className="card w-full max-w-sm p-6">
        <h2 className="text-lg font-bold mb-2">{isItem ? "Delete item" : "Delete category"}</h2>
        <p className="text-sm text-dark-400 mb-6">
          Are you sure you want to delete &ldquo;{name}&rdquo;?{" "}
          {!isItem && itemCount > 0 && (
            <>
              Its {itemCount} {itemCount === 1 ? "item" : "items"} will be deleted too.{" "}
            </>
          )}
          This cannot be undone.
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
            {busy ? "Deleting…" : "Delete"}
          </button>
        </div>
      </div>
    </div>
  );
}
