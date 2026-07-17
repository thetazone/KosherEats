"use client";

// Modifier-groups editor (S18). Rendered inside MenuItemForm when editing an
// existing item. Mirrors the iOS seller app's ModifierGroupsEditorView: a
// list of the item's option groups (name, Required badge, "N options · pick
// X" summary) with an inline per-group form — name, required toggle, min/max
// selection steppers, and price-delta option rows. Group saves hit the API
// immediately (POST /seller/menu/items/{itemId}/modifier-groups, PUT/DELETE
// /seller/menu/modifier-groups/{groupId}) and the authoritative response is
// pushed up via onChange so parent state round-trips into the consumer
// MenuItemModal shapes (types/index.ts ModifierGroup/Modifier) unchanged.

import { useEffect, useState } from "react";
import { Loader2, Minus, Pencil, Plus, Star, Trash2, X } from "lucide-react";
import { centsToDollars, sellerApi } from "@/lib/sellerApi";
import type {
  ModifierGroupRequest,
  SellerModifier,
  SellerModifierGroup,
} from "@/types/seller";

/** "12", "12.5", "12.50", "$12.50" -> cents; null when unparseable. */
function parseDollarsToCents(text: string): number | null {
  const cleaned = text.trim().replace(/^\$/, "").replace(/,/g, "");
  if (cleaned === "") return 0; // blank price delta = free option
  if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) return null;
  return Math.round(parseFloat(cleaned) * 100);
}

/** "pick 1", "pick 0-3" — matches the iOS selection summary. */
function selectionSummary(g: SellerModifierGroup): string {
  return g.min_selections === g.max_selections
    ? `${g.min_selections}`
    : `${g.min_selections}-${g.max_selections}`;
}

/** Draft option row. `id` is undefined for not-yet-saved options (backend
 *  treats absent ids as inserts); `key` is a stable client-only identity. */
interface OptionDraft {
  key: string;
  id?: string;
  name: string;
  priceText: string;
  is_default: boolean;
  is_available: boolean;
}

let nextDraftKey = 0;

function draftFromModifier(m: SellerModifier): OptionDraft {
  return {
    key: `existing-${m.id}`,
    id: m.id,
    name: m.name,
    priceText: m.price_delta === 0 ? "" : centsToDollars(m.price_delta),
    is_default: m.is_default,
    is_available: m.is_available,
  };
}

function newOptionDraft(): OptionDraft {
  return {
    key: `new-${nextDraftKey++}`,
    name: "",
    priceText: "",
    is_default: false,
    is_available: true,
  };
}

export function ModifierGroupsEditor({
  itemId,
  groups,
  onChange,
}: {
  itemId: string;
  groups: SellerModifierGroup[];
  /** Called with the full authoritative list after every server-acked mutation. */
  onChange: (groups: SellerModifierGroup[]) => void;
}) {
  /** null = list view; { group: null } = creating; { group } = editing it. */
  const [editing, setEditing] = useState<{ group: SellerModifierGroup | null } | null>(null);

  // Two-step inline delete confirm, plus its busy/error state.
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  async function deleteGroup(groupId: string) {
    if (deletingId) return;
    setDeletingId(groupId);
    setDeleteError(null);
    try {
      await sellerApi.menu.deleteModifierGroup(groupId);
      onChange(groups.filter((g) => g.id !== groupId));
      setConfirmDeleteId(null);
    } catch (err) {
      setDeleteError((err as Error).message || "Couldn't delete the group — please try again.");
    } finally {
      setDeletingId(null);
    }
  }

  function onGroupSaved(saved: SellerModifierGroup, wasCreate: boolean) {
    const next = wasCreate
      ? [...groups, saved]
      : groups.map((g) => (g.id === saved.id ? saved : g));
    next.sort((a, b) => a.sort_order - b.sort_order);
    onChange(next);
    setEditing(null);
  }

  if (editing) {
    return (
      <GroupForm
        itemId={itemId}
        existing={editing.group}
        nextSortOrder={groups.length}
        onSaved={onGroupSaved}
        onCancel={() => setEditing(null)}
      />
    );
  }

  return (
    <div className="space-y-2">
      {groups.length === 0 ? (
        <p className="text-xs text-dark-500">
          Add options like Size, Sauce, or Extras that customers pick when ordering.
        </p>
      ) : (
        <ul className="space-y-2">
          {groups.map((group) => (
            <li
              key={group.id}
              className="rounded-xl border border-dark-700 bg-dark-800 px-3.5 py-2.5"
            >
              <div className="flex items-center gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-semibold truncate">{group.name}</span>
                    {group.is_required && (
                      <span className="text-[11px] font-semibold px-1.5 py-0.5 rounded bg-brand-500/15 text-brand-400">
                        Required
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-dark-400 mt-0.5">
                    {group.modifiers.length}{" "}
                    {group.modifiers.length === 1 ? "option" : "options"} &middot; pick{" "}
                    {selectionSummary(group)}
                  </p>
                </div>
                <div className="flex items-center gap-0.5 shrink-0">
                  {confirmDeleteId === group.id ? (
                    <>
                      <button
                        type="button"
                        onClick={() => deleteGroup(group.id)}
                        disabled={deletingId === group.id}
                        className="inline-flex items-center gap-1.5 text-xs font-semibold text-red-400 hover:text-red-300 px-2 py-1.5 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-wait"
                      >
                        {deletingId === group.id && (
                          <Loader2 className="w-3.5 h-3.5 animate-spin" aria-hidden="true" />
                        )}
                        {deletingId === group.id ? "Deleting…" : "Confirm delete"}
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          setConfirmDeleteId(null);
                          setDeleteError(null);
                        }}
                        disabled={deletingId === group.id}
                        aria-label="Cancel delete"
                        className="p-1.5 rounded-lg text-dark-400 hover:bg-dark-700 hover:text-white transition-colors disabled:opacity-50"
                      >
                        <X className="w-4 h-4" aria-hidden="true" />
                      </button>
                    </>
                  ) : (
                    <>
                      <button
                        type="button"
                        onClick={() => setEditing({ group })}
                        aria-label={`Edit option group ${group.name}`}
                        className="p-1.5 rounded-lg text-dark-400 hover:bg-dark-700 hover:text-white transition-colors"
                      >
                        <Pencil className="w-4 h-4" aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        onClick={() => setConfirmDeleteId(group.id)}
                        aria-label={`Delete option group ${group.name}`}
                        className="p-1.5 rounded-lg text-dark-400 hover:bg-red-500/10 hover:text-red-400 transition-colors"
                      >
                        <Trash2 className="w-4 h-4" aria-hidden="true" />
                      </button>
                    </>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}

      {deleteError && <p className="text-xs text-red-400">{deleteError}</p>}

      <button
        type="button"
        onClick={() => {
          setConfirmDeleteId(null);
          setDeleteError(null);
          setEditing({ group: null });
        }}
        className="flex items-center gap-1.5 text-sm font-semibold text-brand-500 hover:text-brand-400 transition-colors"
      >
        <Plus className="w-4 h-4" aria-hidden="true" />
        Add option group
      </button>
    </div>
  );
}

// ── Per-group form ───────────────────────────────────────────

function GroupForm({
  itemId,
  existing,
  nextSortOrder,
  onSaved,
  onCancel,
}: {
  itemId: string;
  existing: SellerModifierGroup | null;
  /** sort_order for a newly created group (appended after existing ones). */
  nextSortOrder: number;
  onSaved: (saved: SellerModifierGroup, wasCreate: boolean) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(existing?.name ?? "");
  const [isRequired, setIsRequired] = useState(existing?.is_required ?? false);
  const [minSelections, setMinSelections] = useState(existing?.min_selections ?? 0);
  const [maxSelections, setMaxSelections] = useState(existing?.max_selections ?? 1);
  const [options, setOptions] = useState<OptionDraft[]>(() =>
    existing && existing.modifiers.length > 0
      ? existing.modifiers.map(draftFromModifier)
      : [newOptionDraft()],
  );

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // While this sub-form is open, Escape backs out to the group list instead of
  // closing the whole MenuItemForm modal. Capture-phase on document runs (and
  // stops propagation) before the modal's bubble-phase Escape listener.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== "Escape") return;
      e.stopPropagation();
      if (!saving) onCancel();
    }
    document.addEventListener("keydown", onKeyDown, { capture: true });
    return () => document.removeEventListener("keydown", onKeyDown, { capture: true });
  }, [saving, onCancel]);

  // Mirrors the iOS clamps: required needs min >= 1; max can't drop below min.
  function toggleRequired() {
    const next = !isRequired;
    setIsRequired(next);
    if (next && minSelections < 1) {
      setMinSelections(1);
      if (maxSelections < 1) setMaxSelections(1);
    }
  }

  function stepMin(delta: number) {
    const next = Math.min(10, Math.max(0, minSelections + delta));
    setMinSelections(next);
    if (maxSelections < next) setMaxSelections(next);
    if (isRequired && next < 1) setIsRequired(false);
  }

  function stepMax(delta: number) {
    setMaxSelections(Math.min(20, Math.max(Math.max(1, minSelections), maxSelections + delta)));
  }

  function updateOption(key: string, patch: Partial<OptionDraft>) {
    setOptions((prev) => prev.map((o) => (o.key === key ? { ...o, ...patch } : o)));
  }

  /** Star toggle. Single-select groups (max 1) behave like radios: making one
   *  option the default clears the others. Multi-select allows several. */
  function toggleDefault(key: string) {
    setOptions((prev) =>
      prev.map((o) => {
        if (o.key === key) return { ...o, is_default: !o.is_default };
        return maxSelections === 1 ? { ...o, is_default: false } : o;
      }),
    );
  }

  function removeOption(key: string) {
    setOptions((prev) => prev.filter((o) => o.key !== key));
  }

  const optionsValid =
    options.length > 0 &&
    options.every((o) => o.name.trim().length > 0 && parseDollarsToCents(o.priceText) !== null);
  const canSave =
    name.trim().length > 0 &&
    maxSelections >= minSelections &&
    (!isRequired || minSelections >= 1) &&
    optionsValid &&
    !saving;

  async function save() {
    if (!canSave) return;

    const body: ModifierGroupRequest = {
      name: name.trim(),
      // Not editable here (matches iOS); carried through so an update doesn't
      // wipe it — the backend writes description verbatim on every PUT.
      description: existing?.description,
      is_required: isRequired,
      min_selections: minSelections,
      max_selections: maxSelections,
      sort_order: existing?.sort_order ?? nextSortOrder,
      modifiers: options.map((o, idx) => ({
        id: o.id,
        name: o.name.trim(),
        price_delta: parseDollarsToCents(o.priceText) ?? 0,
        is_default: o.is_default,
        is_available: o.is_available,
        sort_order: idx,
      })),
    };

    setSaving(true);
    setError(null);
    try {
      const saved = existing
        ? await sellerApi.menu.updateModifierGroup(existing.id, body)
        : await sellerApi.menu.createModifierGroup(itemId, body);
      // Go builds the response's Modifiers by appending to a nil slice, so
      // normalize null -> [] before it reaches state the consumer modal reads.
      onSaved({ ...saved, modifiers: saved.modifiers ?? [] }, !existing);
    } catch (err) {
      setError((err as Error).message || "Couldn't save the group — please try again.");
      setSaving(false);
    }
    // On success the parent swaps back to the list view — no state updates after.
  }

  return (
    <div className="rounded-xl border border-dark-700 bg-dark-800/60 p-4 space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-sm font-bold">{existing ? "Edit group" : "New group"}</h3>
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          aria-label="Back to option groups"
          className="p-1.5 rounded-lg text-dark-400 hover:bg-dark-700 hover:text-white transition-colors disabled:opacity-50"
        >
          <X className="w-4 h-4" aria-hidden="true" />
        </button>
      </div>

      {/* Name */}
      <div>
        <label htmlFor="modifier-group-name" className="block text-sm text-dark-300 mb-1.5">
          Group name
        </label>
        <input
          id="modifier-group-name"
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="e.g. Size"
          autoFocus
          maxLength={80}
          className="input w-full py-2.5"
        />
      </div>

      {/* Selection rules */}
      <div className="space-y-2.5">
        <div className="flex items-center justify-between gap-3">
          <span className="text-sm text-dark-300">Required</span>
          <button
            type="button"
            role="switch"
            aria-checked={isRequired}
            aria-label="Customers must pick from this group"
            onClick={toggleRequired}
            className={`relative w-10 h-6 rounded-full transition-colors shrink-0 ${
              isRequired ? "bg-brand-500" : "bg-dark-700"
            }`}
          >
            <span
              className={`absolute top-1 left-1 w-4 h-4 rounded-full bg-white shadow transition-transform ${
                isRequired ? "translate-x-4" : ""
              }`}
            />
          </button>
        </div>
        <StepperRow
          label="Minimum picks"
          value={minSelections}
          onStep={stepMin}
          decDisabled={minSelections <= 0}
          incDisabled={minSelections >= 10}
        />
        <StepperRow
          label="Maximum picks"
          value={maxSelections}
          onStep={stepMax}
          decDisabled={maxSelections <= Math.max(1, minSelections)}
          incDisabled={maxSelections >= 20}
        />
      </div>

      {/* Options */}
      <div>
        <span className="block text-sm text-dark-300 mb-1.5">Options</span>
        <div className="space-y-2">
          {options.map((opt) => {
            const priceInvalid =
              opt.priceText.trim() !== "" && parseDollarsToCents(opt.priceText) === null;
            return (
              <div
                key={opt.key}
                className="flex items-center gap-2 rounded-xl border border-dark-700 bg-dark-800 px-2.5 py-2"
              >
                <input
                  type="text"
                  value={opt.name}
                  onChange={(e) => updateOption(opt.key, { name: e.target.value })}
                  placeholder="Option name"
                  maxLength={80}
                  aria-label="Option name"
                  className="input flex-1 min-w-0 px-3 py-2 text-sm"
                />
                <div className="relative w-24 shrink-0">
                  <span
                    className="absolute left-2.5 top-1/2 -translate-y-1/2 text-xs text-dark-400"
                    aria-hidden="true"
                  >
                    +$
                  </span>
                  <input
                    type="text"
                    inputMode="decimal"
                    value={opt.priceText}
                    onChange={(e) => updateOption(opt.key, { priceText: e.target.value })}
                    placeholder="0.00"
                    aria-label="Price adjustment in dollars"
                    aria-invalid={priceInvalid}
                    className={`input w-full pl-7 pr-2 py-2 text-sm text-right ${
                      priceInvalid ? "border-red-500" : ""
                    }`}
                  />
                </div>
                <button
                  type="button"
                  onClick={() => toggleDefault(opt.key)}
                  aria-label={
                    opt.is_default
                      ? "Default option — tap to unset"
                      : "Set as default option"
                  }
                  aria-pressed={opt.is_default}
                  className={`p-1.5 rounded-lg transition-colors shrink-0 ${
                    opt.is_default
                      ? "text-amber-400 hover:text-amber-300"
                      : "text-dark-500 hover:text-dark-300"
                  }`}
                >
                  <Star
                    className="w-4 h-4"
                    fill={opt.is_default ? "currentColor" : "none"}
                    aria-hidden="true"
                  />
                </button>
                <button
                  type="button"
                  onClick={() => removeOption(opt.key)}
                  aria-label={`Remove ${opt.name.trim() || "option"}`}
                  className="p-1.5 rounded-lg text-dark-400 hover:bg-red-500/10 hover:text-red-400 transition-colors shrink-0"
                >
                  <Minus className="w-4 h-4" aria-hidden="true" />
                </button>
              </div>
            );
          })}
        </div>
        <button
          type="button"
          onClick={() => setOptions((prev) => [...prev, newOptionDraft()])}
          className="flex items-center gap-1.5 text-sm font-semibold text-brand-500 hover:text-brand-400 transition-colors mt-2"
        >
          <Plus className="w-4 h-4" aria-hidden="true" />
          Add option
        </button>
      </div>

      {error && (
        <div className="px-3.5 py-2.5 rounded-xl border border-red-500/30 bg-red-500/10 text-red-300 text-sm">
          {error}
        </div>
      )}

      <div className="flex gap-3">
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          className="btn-secondary flex-1 py-2.5 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={save}
          disabled={!canSave}
          className="btn-primary flex-1 py-2.5 flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {saving && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
          {saving ? "Saving…" : existing ? "Save group" : "Create group"}
        </button>
      </div>
    </div>
  );
}

// ── Stepper ──────────────────────────────────────────────────

function StepperRow({
  label,
  value,
  onStep,
  decDisabled,
  incDisabled,
}: {
  label: string;
  value: number;
  onStep: (delta: number) => void;
  decDisabled: boolean;
  incDisabled: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-sm text-dark-300">{label}</span>
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => onStep(-1)}
          disabled={decDisabled}
          aria-label={`Decrease ${label.toLowerCase()}`}
          className="p-1.5 rounded-lg border border-dark-700 bg-dark-800 text-dark-300 hover:bg-dark-700 hover:text-white transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <Minus className="w-3.5 h-3.5" aria-hidden="true" />
        </button>
        <span className="w-8 text-center text-sm font-semibold tabular-nums" aria-live="polite">
          {value}
        </span>
        <button
          type="button"
          onClick={() => onStep(1)}
          disabled={incDisabled}
          aria-label={`Increase ${label.toLowerCase()}`}
          className="p-1.5 rounded-lg border border-dark-700 bg-dark-800 text-dark-300 hover:bg-dark-700 hover:text-white transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <Plus className="w-3.5 h-3.5" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
