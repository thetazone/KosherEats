"use client";

// Menu item create/edit modal (S17). Mirrors the iOS seller app's
// MenuItemFormView: name, description, price (entered in dollars, stored as
// integer cents), category, a mutually-exclusive Meat/Dairy/Pareve kosher
// type (exactly one required, pareve default), and an optional photo
// uploaded via the presign flow (kind "menu_item"). The form owns the
// create-vs-update API call; the parent gets the saved item back via
// onSaved and reconciles its local menu state. When editing an existing
// item it also hosts the ModifierGroupsEditor (S18) — group mutations save
// to the server immediately and stream up via onModifierGroupsChange so the
// parent's copy of the item never goes stale, even if this form is
// cancelled afterwards.

import { useEffect, useRef, useState } from "react";
import { ImagePlus, Loader2, X } from "lucide-react";
import { ModifierGroupsEditor } from "@/components/seller/ModifierGroupsEditor";
import { sellerApi, uploadImage } from "@/lib/sellerApi";
import type {
  MenuItemRequest,
  SellerMenuCategory,
  SellerMenuItem,
  SellerModifierGroup,
} from "@/types/seller";

type KosherType = "meat" | "dairy" | "pareve";

const KOSHER_OPTIONS: { value: KosherType; label: string; selectedClass: string }[] = [
  { value: "meat", label: "Meat", selectedClass: "bg-red-500/15 border-red-500 text-red-400" },
  { value: "dairy", label: "Dairy", selectedClass: "bg-blue-500/15 border-blue-500 text-blue-400" },
  {
    value: "pareve",
    label: "Pareve",
    selectedClass: "bg-green-500/15 border-green-500 text-green-400",
  },
];

/**
 * "12", "12.5", "12.50", "$12.50" -> cents; null when unparseable.
 * Two decimal places max — money is integer cents everywhere.
 */
function parseDollarsToCents(text: string): number | null {
  const cleaned = text.trim().replace(/^\$/, "").replace(/,/g, "");
  if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) return null;
  return Math.round(parseFloat(cleaned) * 100);
}

function centsToDollarsText(cents: number): string {
  return (cents / 100).toFixed(2);
}

export function MenuItemForm({
  categories,
  item,
  defaultCategoryId,
  onSaved,
  onClose,
  onModifierGroupsChange,
}: {
  categories: SellerMenuCategory[];
  /** null = creating a new item; non-null = editing that item. */
  item: SellerMenuItem | null;
  /** Preselected category for new items (the section's "Add item" button). */
  defaultCategoryId?: string;
  onSaved: (saved: SellerMenuItem, wasCreate: boolean) => void;
  onClose: () => void;
  /**
   * Fired after every server-acked modifier-group mutation (they persist
   * immediately, independent of the item Save/Cancel), so the parent can keep
   * its copy of the item's modifier_groups current even on Cancel.
   */
  onModifierGroupsChange?: (itemId: string, groups: SellerModifierGroup[]) => void;
}) {
  const [name, setName] = useState(item?.name ?? "");
  const [description, setDescription] = useState(item?.description ?? "");
  const [priceText, setPriceText] = useState(item ? centsToDollarsText(item.price) : "");
  const [categoryId, setCategoryId] = useState(
    item?.category_id ?? defaultCategoryId ?? categories[0]?.id ?? "",
  );
  const [kosher, setKosher] = useState<KosherType>(
    item ? (item.is_meat ? "meat" : item.is_dairy ? "dairy" : "pareve") : "pareve",
  );
  const [imageUrl, setImageUrl] = useState(item?.image_url ?? "");
  const [modifierGroups, setModifierGroups] = useState<SellerModifierGroup[]>(
    item?.modifier_groups ?? [],
  );

  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  // Escape closes the modal (unless a save is mid-flight).
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape" && !saving) onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [saving, onClose]);

  const cents = parseDollarsToCents(priceText);
  const priceInvalid = priceText.trim() !== "" && cents === null;
  const canSave =
    name.trim().length > 0 && categoryId !== "" && cents !== null && !saving && !uploading;

  async function onFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    // Reset so picking the same file again re-fires onChange.
    e.target.value = "";
    if (!file) return;

    setUploadError(null);
    setUploading(true);
    try {
      setImageUrl(await uploadImage(file, "menu_item"));
    } catch (err) {
      setUploadError((err as Error).message || "Photo upload failed — please try again.");
    } finally {
      setUploading(false);
    }
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSave || cents === null) return;

    const body: MenuItemRequest = {
      category_id: categoryId,
      name: name.trim(),
      description: description.trim(),
      image_url: imageUrl || undefined,
      price: cents,
      is_meat: kosher === "meat",
      is_dairy: kosher === "dairy",
      is_pareve: kosher === "pareve",
    };

    setSaving(true);
    setSaveError(null);
    try {
      const saved = item
        ? await sellerApi.menu.updateItem(item.id, body)
        : await sellerApi.menu.createItem(body);
      // Item write responses never include modifier_groups — reattach the
      // authoritative set the embedded editor has been maintaining.
      onSaved(item ? { ...saved, modifier_groups: modifierGroups } : saved, !item);
    } catch (err) {
      setSaveError((err as Error).message || "Couldn't save the item — please try again.");
      setSaving(false);
    }
    // On success the parent unmounts us — no state updates after onSaved.
  }

  function handleModifierGroupsChange(groups: SellerModifierGroup[]) {
    setModifierGroups(groups);
    if (item) onModifierGroupsChange?.(item.id, groups);
  }

  return (
    <div
      className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-label={item ? "Edit menu item" : "New menu item"}
    >
      <div className="card w-full max-w-lg max-h-[90vh] flex flex-col">
        <div className="flex items-center justify-between gap-3 px-6 py-4 border-b border-dark-800 shrink-0">
          <h2 className="text-lg font-bold">{item ? "Edit item" : "New item"}</h2>
          <button
            type="button"
            onClick={onClose}
            disabled={saving}
            aria-label="Close"
            className="p-1.5 rounded-lg text-dark-400 hover:bg-dark-800 hover:text-white transition-colors disabled:opacity-50"
          >
            <X className="w-5 h-5" aria-hidden="true" />
          </button>
        </div>

        <form onSubmit={onSubmit} className="flex-1 overflow-y-auto px-6 py-5 space-y-5">
          {/* Photo */}
          <div>
            <span className="block text-sm text-dark-300 mb-1.5">Photo</span>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/jpeg,image/png,image/webp,image/heic"
              onChange={onFileSelected}
              className="sr-only"
              aria-label="Upload item photo"
            />
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={uploading}
              className={`relative w-full aspect-video rounded-xl border overflow-hidden transition-colors ${
                imageUrl
                  ? "border-dark-700"
                  : "border-dashed border-dark-600 bg-dark-800/60 hover:border-brand-500"
              } disabled:cursor-wait`}
            >
              {imageUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={imageUrl}
                  alt="Item photo"
                  className="absolute inset-0 w-full h-full object-cover"
                />
              ) : (
                <span className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-dark-400">
                  <ImagePlus className="w-6 h-6 text-brand-500" aria-hidden="true" />
                  <span className="text-xs">Tap to add photo</span>
                </span>
              )}
              {uploading && (
                <span className="absolute inset-0 flex items-center justify-center bg-black/50">
                  <Loader2 className="w-6 h-6 animate-spin text-white" aria-hidden="true" />
                </span>
              )}
            </button>
            {imageUrl && !uploading && (
              <button
                type="button"
                onClick={() => {
                  setImageUrl("");
                  setUploadError(null);
                }}
                className="flex items-center gap-1.5 text-xs font-medium text-red-400 hover:text-red-300 transition-colors mt-2"
              >
                <X className="w-3.5 h-3.5" aria-hidden="true" />
                Remove photo
              </button>
            )}
            {uploadError && <p className="text-xs text-red-400 mt-2">{uploadError}</p>}
          </div>

          {/* Name */}
          <div>
            <label htmlFor="menu-item-name" className="block text-sm text-dark-300 mb-1.5">
              Name
            </label>
            <input
              id="menu-item-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Pastrami Sandwich"
              autoFocus
              maxLength={120}
              className="input w-full"
            />
          </div>

          {/* Description */}
          <div>
            <label htmlFor="menu-item-description" className="block text-sm text-dark-300 mb-1.5">
              Description <span className="text-dark-500">(optional)</span>
            </label>
            <textarea
              id="menu-item-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="What's in it, how it's made…"
              rows={3}
              maxLength={500}
              className="input w-full resize-y"
            />
          </div>

          {/* Price + category */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label htmlFor="menu-item-price" className="block text-sm text-dark-300 mb-1.5">
                Price
              </label>
              <div className="relative">
                <span
                  className="absolute left-4 top-1/2 -translate-y-1/2 text-dark-400"
                  aria-hidden="true"
                >
                  $
                </span>
                <input
                  id="menu-item-price"
                  type="text"
                  inputMode="decimal"
                  value={priceText}
                  onChange={(e) => setPriceText(e.target.value)}
                  placeholder="0.00"
                  className="input w-full pl-8"
                  aria-invalid={priceInvalid}
                />
              </div>
              {priceInvalid && (
                <p className="text-xs text-red-400 mt-1.5">Enter a price like 12.50</p>
              )}
            </div>
            <div>
              <label htmlFor="menu-item-category" className="block text-sm text-dark-300 mb-1.5">
                Category
              </label>
              <select
                id="menu-item-category"
                value={categoryId}
                onChange={(e) => setCategoryId(e.target.value)}
                className="input w-full cursor-pointer"
              >
                {categories.map((cat) => (
                  <option key={cat.id} value={cat.id}>
                    {cat.name}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Kosher type — exactly one, mirrors the iOS mutually-exclusive toggles */}
          <div>
            <span className="block text-sm text-dark-300 mb-1.5">Kosher type</span>
            <div className="grid grid-cols-3 gap-2" role="radiogroup" aria-label="Kosher type">
              {KOSHER_OPTIONS.map((opt) => {
                const selected = kosher === opt.value;
                return (
                  <button
                    key={opt.value}
                    type="button"
                    role="radio"
                    aria-checked={selected}
                    onClick={() => setKosher(opt.value)}
                    className={`py-2.5 px-3 rounded-xl border text-sm font-semibold transition-colors ${
                      selected
                        ? opt.selectedClass
                        : "border-dark-700 bg-dark-800 text-dark-300 hover:bg-dark-700"
                    }`}
                  >
                    {opt.label}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Option groups (S18) — needs a saved item id to attach groups to */}
          <div>
            <span className="block text-sm text-dark-300 mb-1.5">
              Option groups <span className="text-dark-500">(optional)</span>
            </span>
            {item ? (
              <ModifierGroupsEditor
                itemId={item.id}
                groups={modifierGroups}
                onChange={handleModifierGroupsChange}
              />
            ) : (
              <p className="text-xs text-dark-500">
                Save the item first, then edit it to add options like Size, Sauce, or Extras.
              </p>
            )}
          </div>

          {saveError && (
            <div className="px-4 py-2.5 rounded-xl border border-red-500/30 bg-red-500/10 text-red-300 text-sm">
              {saveError}
            </div>
          )}

          <div className="flex gap-3 pt-1">
            <button
              type="button"
              onClick={onClose}
              disabled={saving}
              className="btn-secondary flex-1 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={!canSave}
              className="btn-primary flex-1 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {saving ? "Saving…" : item ? "Save changes" : "Add item"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
