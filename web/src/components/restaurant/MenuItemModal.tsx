"use client";

import { formatUSD, formatUSDDelta } from "@/lib/format";
import type { MenuItem, Modifier, ModifierGroup, SelectedModifier } from "@/types";
import { AlertTriangle, Check, Loader2, Minus, Plus, X } from "lucide-react";
import { useEffect, useState } from "react";

// MenuItemSelection is what the modal hands back on Add: the raw ids the
// backend validates + prices server-side (POST /cart/items modifier_ids), the
// resolved snapshots for optimistic local display, and the computed unit
// price (base + deltas, integer cents) so the caller can render totals
// without re-deriving them.
export interface MenuItemSelection {
  modifier_ids: string[];
  selected_modifiers: SelectedModifier[];
  quantity: number;
  notes?: string;
  unit_price: number;
}

const MAX_QUANTITY = 99; // backend rejects quantity > 99
const MAX_NOTES_LENGTH = 500; // backend rejects notes > 500 chars

// A group must be satisfied if the seller flagged it required OR set a
// positive minimum (mirrors the iOS AddToCartSheet.canAdd gate).
function isMandatory(group: ModifierGroup): boolean {
  return group.is_required || group.min_selections > 0;
}

function requiredCount(group: ModifierGroup): number {
  return Math.max(group.min_selections, group.is_required ? 1 : 0);
}

// Pre-select each group's default options so required single-select groups
// start valid. Only seed AVAILABLE defaults, and never more than the group
// allows (mirrors the iOS sheet's onAppear seeding).
function seedDefaults(groups: ModifierGroup[]): Record<string, string[]> {
  const initial: Record<string, string[]> = {};
  for (const group of groups) {
    const defaults = group.modifiers
      .filter((m) => m.is_default && m.is_available)
      .sort((a, b) => a.sort_order - b.sort_order)
      .slice(0, Math.max(group.max_selections, 0))
      .map((m) => m.id);
    if (defaults.length > 0) initial[group.id] = defaults;
  }
  return initial;
}

function DietaryBadge({ label, color }: { label: string; color: string }) {
  return (
    <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${color}`}>
      {label}
    </span>
  );
}

// MenuItemModal is the web port of the iOS AddToCartSheet: modifier groups
// with the selection rules baked in (radio when max_selections == 1, checkbox
// otherwise; required groups gate the Add button), a quantity stepper, a
// notes field, and a live total that updates as the user picks.
//
// The parent owns the actual add: onSubmit performs the POST /cart/items and
// any local cart bookkeeping, then closes the modal on success. If onSubmit
// throws, the message is surfaced inline here and the modal stays open.
export function MenuItemModal({
  item,
  onClose,
  onSubmit,
}: {
  item: MenuItem;
  onClose: () => void;
  onSubmit: (selection: MenuItemSelection) => Promise<void>;
}) {
  const groups = [...(item.modifier_groups ?? [])].sort(
    (a, b) => a.sort_order - b.sort_order
  );

  // Selected modifier ids keyed by group id. Seeded once on mount — the
  // parent renders the modal only while open, so mount == open.
  const [selection, setSelection] = useState<Record<string, string[]>>(() =>
    seedDefaults(item.modifier_groups ?? [])
  );
  const [quantity, setQuantity] = useState(1);
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Escape closes; lock body scroll while the modal is up (same pattern as
  // KosherCertificateModal).
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [onClose]);

  function toggleModifier(group: ModifierGroup, mod: Modifier) {
    if (!mod.is_available) return;
    setSelection((prev) => {
      const current = prev[group.id] ?? [];
      const isSelected = current.includes(mod.id);
      if (group.max_selections === 1) {
        // Radio-style. Allow deselecting the chosen option when the group is
        // optional so a tapped add-on isn't permanently stuck.
        if (!isMandatory(group) && isSelected) {
          return { ...prev, [group.id]: [] };
        }
        return { ...prev, [group.id]: [mod.id] };
      }
      if (isSelected) {
        return { ...prev, [group.id]: current.filter((id) => id !== mod.id) };
      }
      if (current.length >= group.max_selections) return prev;
      return { ...prev, [group.id]: [...current, mod.id] };
    });
  }

  // Resolve the selection to snapshots in group order (stable display order).
  const selectedModifiers: SelectedModifier[] = groups.flatMap((group) =>
    (selection[group.id] ?? []).flatMap((id) => {
      const mod = group.modifiers.find((m) => m.id === id);
      if (!mod) return [];
      return [
        {
          id: mod.id,
          group_id: group.id,
          group_name: group.name,
          name: mod.name,
          price_delta: mod.price_delta,
        },
      ];
    })
  );

  const unitPrice =
    item.price + selectedModifiers.reduce((sum, m) => sum + m.price_delta, 0);

  const canAdd = groups.every(
    (group) => (selection[group.id] ?? []).length >= requiredCount(group)
  );

  async function handleSubmit() {
    if (!canAdd || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit({
        modifier_ids: selectedModifiers.map((m) => m.id),
        selected_modifiers: selectedModifiers,
        quantity,
        notes: notes.trim() || undefined,
        unit_price: unitPrice,
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to add item to cart");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Customize ${item.name}`}
      className="fixed inset-0 z-[60] bg-black/70 flex items-stretch md:items-center justify-center md:p-4"
      onClick={onClose}
    >
      {/* Below md: a full-height bottom sheet (h-full against the inset-0
          overlay); at md+ a centered dialog capped at 85vh. */}
      <div
        className="card w-full max-w-lg h-full md:h-auto md:max-h-[85vh] flex flex-col rounded-none md:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-start justify-between gap-3 px-5 py-4 border-b border-dark-800">
          <div className="min-w-0">
            <h2 className="text-lg font-bold truncate">{item.name}</h2>
            <div className="flex items-center gap-2 mt-1">
              <span className="text-brand-400 font-semibold text-sm">
                {formatUSD(item.price)}
              </span>
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
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            className="w-11 h-11 -mr-2 -mt-1 rounded-xl text-dark-400 hover:text-white hover:bg-dark-800 transition-colors flex-shrink-0 flex items-center justify-center"
          >
            <X className="w-5 h-5" aria-hidden="true" />
          </button>
        </div>

        {/* Scrollable body */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-6">
          {item.description && (
            <p className="text-dark-400 text-sm">{item.description}</p>
          )}

          {groups.map((group) => {
            const picked = selection[group.id] ?? [];
            const mandatory = isMandatory(group);
            const badge = mandatory
              ? group.min_selections > 1
                ? `Choose at least ${group.min_selections}`
                : "Required"
              : group.max_selections === 1
                ? "Choose 1"
                : `Up to ${group.max_selections}`;
            const sortedModifiers = [...group.modifiers].sort(
              (a, b) => a.sort_order - b.sort_order
            );
            const singleSelect = group.max_selections === 1;
            return (
              <div key={group.id} role="group" aria-label={group.name}>
                <div className="flex items-baseline justify-between gap-3 mb-2">
                  <div>
                    <span className="font-semibold">{group.name}</span>
                    {group.description && (
                      <span className="block text-xs text-dark-400 mt-0.5">
                        {group.description}
                      </span>
                    )}
                  </div>
                  <span
                    className={`text-xs font-bold px-2 py-0.5 rounded flex-shrink-0 ${
                      mandatory
                        ? "bg-brand-500/15 text-brand-400"
                        : "bg-dark-800 text-dark-400"
                    }`}
                  >
                    {badge}
                  </span>
                </div>
                <div className="bg-dark-800/50 border border-dark-800 rounded-xl divide-y divide-dark-800 overflow-hidden">
                  {sortedModifiers.map((mod) => {
                    const isSelected = picked.includes(mod.id);
                    const delta = formatUSDDelta(mod.price_delta);
                    return (
                      <button
                        key={mod.id}
                        type="button"
                        role={singleSelect ? "radio" : "checkbox"}
                        aria-checked={isSelected}
                        onClick={() => toggleModifier(group, mod)}
                        disabled={!mod.is_available}
                        className="w-full flex items-center gap-3 px-4 py-3 min-h-[44px] text-left hover:bg-dark-800 disabled:opacity-50 disabled:hover:bg-transparent transition-colors"
                      >
                        {singleSelect ? (
                          <span
                            aria-hidden="true"
                            className={`w-5 h-5 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${
                              isSelected ? "border-brand-500" : "border-dark-500"
                            }`}
                          >
                            {isSelected && (
                              <span className="w-2.5 h-2.5 rounded-full bg-brand-500" />
                            )}
                          </span>
                        ) : (
                          <span
                            aria-hidden="true"
                            className={`w-5 h-5 rounded border-2 flex items-center justify-center flex-shrink-0 ${
                              isSelected
                                ? "border-brand-500 bg-brand-500"
                                : "border-dark-500"
                            }`}
                          >
                            {isSelected && (
                              <Check className="w-3.5 h-3.5 text-white" strokeWidth={3} />
                            )}
                          </span>
                        )}
                        <span className="flex-1 text-sm">{mod.name}</span>
                        {!mod.is_available ? (
                          <span className="text-xs text-red-400">Unavailable</span>
                        ) : (
                          delta && (
                            <span className="text-sm text-dark-300">{delta}</span>
                          )
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>
            );
          })}

          {/* Special instructions */}
          <div>
            <label
              htmlFor="menu-item-notes"
              className="block text-sm font-semibold text-dark-300 mb-2"
            >
              Special Instructions
            </label>
            <input
              id="menu-item-notes"
              className="input w-full text-base"
              placeholder="e.g., no onions, extra sauce"
              maxLength={MAX_NOTES_LENGTH}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
          </div>
        </div>

        {/* Footer: quantity stepper + add — pinned below the scroll area so
            it acts as the sheet's sticky action bar on mobile; safe-area
            padding keeps it clear of the iOS home indicator. */}
        <div className="border-t border-dark-800 px-5 pt-4 pb-[calc(1rem+env(safe-area-inset-bottom))] md:pb-4 space-y-3">
          {error && (
            <div
              className="flex items-start gap-2 text-sm text-red-300 bg-red-900/20 border border-red-800 rounded-xl px-3 py-2"
              role="alert"
            >
              <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0" aria-hidden="true" />
              <span>{error}</span>
            </div>
          )}
          <div className="flex items-center gap-3">
            {/* 44px stepper buttons (w-11 h-11) — minimum touch target. */}
            <div className="flex items-center gap-2 bg-dark-800 rounded-xl px-1.5 py-1.5 flex-shrink-0">
              <button
                onClick={() => setQuantity((q) => Math.max(q - 1, 1))}
                disabled={quantity <= 1}
                aria-label="Decrease quantity"
                className="w-11 h-11 rounded-full bg-dark-700 hover:bg-dark-600 disabled:opacity-50 flex items-center justify-center text-white transition-colors"
              >
                <Minus className="w-4 h-4" aria-hidden="true" />
              </button>
              <span
                className="font-semibold w-6 text-center"
                aria-label={`Quantity: ${quantity}`}
              >
                {quantity}
              </span>
              <button
                onClick={() => setQuantity((q) => Math.min(q + 1, MAX_QUANTITY))}
                disabled={quantity >= MAX_QUANTITY}
                aria-label="Increase quantity"
                className="w-11 h-11 rounded-full bg-brand-500 hover:bg-brand-600 disabled:opacity-50 flex items-center justify-center text-white transition-colors"
              >
                <Plus className="w-4 h-4" aria-hidden="true" />
              </button>
            </div>
            <button
              onClick={handleSubmit}
              disabled={!canAdd || submitting}
              className="btn-primary flex-1 flex items-center justify-between gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <span className="flex items-center gap-2">
                {submitting && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
                {submitting
                  ? "Adding…"
                  : canAdd
                    ? "Add to Cart"
                    : "Select required options"}
              </span>
              <span>{formatUSD(unitPrice * quantity)}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
