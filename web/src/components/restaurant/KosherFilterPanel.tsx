"use client";

import type { KosherCertification, Restaurant } from "@/types";
import { Check, SlidersHorizontal, X } from "lucide-react";
import { useEffect, useState } from "react";

// Filter state for the kosher filter panel — web port of the iOS
// KosherFilters model (RestaurantStore.swift). Each field narrows the result
// set: an empty certifications list / false toggle means "no constraint",
// not "exclude everything". Certifications are OR'd together; the dietary
// toggles AND on top, so the user can build a precise query like
// "Glatt + Cholov Yisroel + OU or cRc".
export interface KosherFilters {
  certifications: KosherCertification[];
  glattOnly: boolean;
  cholovYisroelOnly: boolean;
  pasYisroelOnly: boolean;
}

export const EMPTY_KOSHER_FILTERS: KosherFilters = {
  certifications: [],
  glattOnly: false,
  cholovYisroelOnly: false,
  pasYisroelOnly: false,
};

export function isKosherFilterActive(f: KosherFilters): boolean {
  return f.certifications.length > 0 || f.glattOnly || f.cholovYisroelOnly || f.pasYisroelOnly;
}

// Count of active filters — drives the badge on the filter button
// (matches iOS KosherFilters.activeCount).
export function kosherFilterCount(f: KosherFilters): number {
  let n = f.certifications.length;
  if (f.glattOnly) n += 1;
  if (f.cholovYisroelOnly) n += 1;
  if (f.pasYisroelOnly) n += 1;
  return n;
}

export function matchesKosherFilters(r: Restaurant, f: KosherFilters): boolean {
  if (f.certifications.length > 0 && !f.certifications.includes(r.kosher_certification)) {
    return false;
  }
  if (f.glattOnly && !r.is_glatt_kosher) return false;
  if (f.cholovYisroelOnly && !r.is_cholov_yisroel) return false;
  if (f.pasYisroelOnly && !r.is_pas_yisroel) return false;
  return true;
}

// sessionStorage persistence so applied filters survive navigation (tap into
// a restaurant, come back — the list is still narrowed to YOUR standards).
// sessionStorage (not localStorage) intentionally: kashrus filters are a
// per-visit refinement, matching the iOS in-memory KosherFilters lifetime
// (reset on app relaunch) rather than a permanent account setting.
const FILTERS_STORAGE_KEY = "ke_kosher_filters";

// Every enum value (types/index.ts KosherCertification) — used to validate
// persisted certifications so a stale/corrupt payload can never smuggle an
// unknown string into filter state.
const ALL_CERTIFICATIONS: readonly KosherCertification[] = [
  "OU",
  "OK",
  "Kof-K",
  "Star-K",
  "cRc",
  "Badatz",
  "Chof-K",
  "other",
];

export function loadKosherFilters(): KosherFilters {
  if (typeof window === "undefined") return EMPTY_KOSHER_FILTERS;
  try {
    const raw = window.sessionStorage.getItem(FILTERS_STORAGE_KEY);
    if (!raw) return EMPTY_KOSHER_FILTERS;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null) return EMPTY_KOSHER_FILTERS;
    const p = parsed as Partial<Record<keyof KosherFilters, unknown>>;
    return {
      certifications: Array.isArray(p.certifications)
        ? p.certifications.filter((c): c is KosherCertification =>
            (ALL_CERTIFICATIONS as readonly string[]).includes(c as string)
          )
        : [],
      glattOnly: p.glattOnly === true,
      cholovYisroelOnly: p.cholovYisroelOnly === true,
      pasYisroelOnly: p.pasYisroelOnly === true,
    };
  } catch {
    // Corrupt JSON or storage access denied — fall back to no filters.
    return EMPTY_KOSHER_FILTERS;
  }
}

export function saveKosherFilters(f: KosherFilters): void {
  if (typeof window === "undefined") return;
  try {
    if (isKosherFilterActive(f)) {
      window.sessionStorage.setItem(FILTERS_STORAGE_KEY, JSON.stringify(f));
    } else {
      window.sessionStorage.removeItem(FILTERS_STORAGE_KEY);
    }
  } catch {
    // Storage full/blocked (private mode) — filters just won't persist.
  }
}

// The 7 certifying agencies, in iOS KosherCertification enum order
// (OU, OK, Kof-K, Star-K, cRc, Badatz, Chof-K). "other" is intentionally
// excluded from the chips — it isn't a standard the user filters FOR.
const AGENCIES: KosherCertification[] = ["OU", "OK", "Kof-K", "Star-K", "cRc", "Badatz", "Chof-K"];

// Dietary toggle copy mirrors the iOS KosherFilterSheet rows verbatim.
const DIETARY_TOGGLES: {
  key: "glattOnly" | "cholovYisroelOnly" | "pasYisroelOnly";
  title: string;
  subtitle: string;
}[] = [
  {
    key: "glattOnly",
    title: "Glatt Kosher",
    subtitle: "Only Glatt-certified meat establishments",
  },
  {
    key: "cholovYisroelOnly",
    title: "Cholov Yisroel",
    subtitle: "Dairy under full Yisroel supervision",
  },
  {
    key: "pasYisroelOnly",
    title: "Pas Yisroel",
    subtitle: "Baked goods under full Yisroel supervision",
  },
];

// KosherFilterPanel is the differentiator UI — it lets the user narrow
// restaurants by certification agency, Glatt Kosher, Cholov Yisroel, and
// Pas Yisroel: "find kosher food that actually matches YOUR kashrus
// standards." Web port of the iOS KosherFilterSheet: toggles edit a draft,
// "Show N results" live-updates on the Apply button, Apply commits the draft
// to the parent via onApply.
export function KosherFilterPanel({
  allRestaurants,
  filters,
  onApply,
}: {
  // The base result set the live count previews against (the current
  // query's results, before kosher filtering).
  allRestaurants: Restaurant[];
  filters: KosherFilters;
  onApply: (filters: KosherFilters) => void;
}) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<KosherFilters>(filters);

  // Below md: the panel is a full-height bottom sheet, so lock body scroll
  // while it's up (and only there — on md+ it's an inline card and the page
  // must keep scrolling). Escape closes at any width.
  useEffect(() => {
    if (!open) return;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("keydown", onKeyDown);

    const mq = window.matchMedia("(max-width: 767px)");
    const previousOverflow = document.body.style.overflow;
    const applyLock = () => {
      document.body.style.overflow = mq.matches ? "hidden" : previousOverflow;
    };
    applyLock();
    mq.addEventListener("change", applyLock);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      mq.removeEventListener("change", applyLock);
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  const appliedCount = kosherFilterCount(filters);
  const draftCount = kosherFilterCount(draft);

  // Live preview of how many restaurants match the *draft* filters.
  // Recomputes on every toggle so the user can gauge how narrow they're going.
  const previewCount = allRestaurants.filter((r) => matchesKosherFilters(r, draft)).length;

  function toggleOpen() {
    if (!open) setDraft(filters); // re-seed the draft from what's applied
    setOpen(!open);
  }

  function toggleCertification(cert: KosherCertification) {
    setDraft((d) => ({
      ...d,
      certifications: d.certifications.includes(cert)
        ? d.certifications.filter((c) => c !== cert)
        : [...d.certifications, cert],
    }));
  }

  function toggleDietary(key: "glattOnly" | "cholovYisroelOnly" | "pasYisroelOnly") {
    setDraft((d) => ({ ...d, [key]: !d[key] }));
  }

  function apply() {
    onApply(draft);
    setOpen(false);
  }

  function clear() {
    setDraft(EMPTY_KOSHER_FILTERS);
  }

  return (
    <>
      {/* Trigger */}
      <button
        onClick={toggleOpen}
        aria-expanded={open}
        className={`flex items-center gap-2 px-4 py-2 min-h-[44px] rounded-full text-sm font-medium transition-colors border ${
          appliedCount > 0
            ? "bg-brand-500/20 text-brand-400 border-brand-500"
            : "bg-dark-800 text-dark-300 border-dark-700 hover:bg-dark-700"
        }`}
      >
        <SlidersHorizontal className="w-4 h-4" aria-hidden="true" />
        Kosher Filters
        {appliedCount > 0 && (
          <span className="bg-brand-500 text-white text-xs font-bold rounded-full w-5 h-5 flex items-center justify-center">
            {appliedCount}
          </span>
        )}
      </button>

      {/* Panel. Below md: a full-height bottom sheet over a backdrop (body
          scroll is locked while open — see the effect above). At md+ the
          wrapper collapses to a static w-full node so the panel stays an
          inline card that wraps below the trigger row in the flex-wrap
          parent, exactly as before. */}
      {open && (
        <div
          className="fixed inset-0 z-[60] bg-black/70 flex flex-col justify-end md:static md:inset-auto md:z-auto md:bg-transparent md:block md:w-full"
          onClick={() => setOpen(false)}
        >
          <div
            className="card flex flex-col h-full min-h-0 rounded-none border-x-0 border-b-0 md:h-auto md:rounded-2xl md:border md:border-dark-800"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Sheet header — mobile only; md+ keeps the old headerless card. */}
            <div className="flex items-center justify-between gap-3 px-5 py-3 border-b border-dark-800 md:hidden">
              <h2 className="font-bold text-lg">Kosher Filters</h2>
              <button
                onClick={() => setOpen(false)}
                aria-label="Close filters"
                className="w-11 h-11 -mr-2 rounded-xl text-dark-400 hover:text-white hover:bg-dark-800 flex items-center justify-center transition-colors"
              >
                <X className="w-5 h-5" aria-hidden="true" />
              </button>
            </div>

            {/* Scrollable body (only actually scrolls in the mobile sheet). */}
            <div className="flex-1 min-h-0 overflow-y-auto p-5 md:flex-none md:overflow-visible">
            {/* Certification section */}
            <div className="mb-6">
              <h3 className="font-bold text-lg">Certification</h3>
              <p className="text-dark-400 text-sm mb-3">Select any that work for you</p>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
                {AGENCIES.map((cert) => {
                  const selected = draft.certifications.includes(cert);
                  return (
                    <button
                      key={cert}
                      onClick={() => toggleCertification(cert)}
                      aria-pressed={selected}
                      aria-label={`${cert} certification`}
                      className={`flex items-center gap-2 px-3 py-3 min-h-[44px] rounded-xl text-sm font-semibold border-2 transition-colors ${
                        selected
                          ? "bg-dark-800 border-brand-500 text-white"
                          : "bg-dark-800 border-transparent text-dark-300 hover:bg-dark-700"
                      }`}
                    >
                      <span
                        aria-hidden="true"
                        className={`w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 ${
                          selected ? "bg-brand-500" : "border border-dark-500"
                        }`}
                      >
                        {selected && <Check className="w-3.5 h-3.5 text-white" />}
                      </span>
                      {cert}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Dietary standards section */}
            <div className="mb-6">
              <h3 className="font-bold text-lg">Dietary Standards</h3>
              <p className="text-dark-400 text-sm mb-3">
                Stricter kashrus? Toggle what matters to you
              </p>
              <div className="space-y-2">
                {DIETARY_TOGGLES.map(({ key, title, subtitle }) => {
                  const on = draft[key];
                  return (
                    <button
                      key={key}
                      onClick={() => toggleDietary(key)}
                      role="switch"
                      aria-checked={on}
                      aria-label={title}
                      className="w-full flex items-center justify-between gap-4 bg-dark-800 hover:bg-dark-700 rounded-xl px-4 py-3 text-left transition-colors"
                    >
                      <span>
                        <span className="block font-semibold">{title}</span>
                        <span className="block text-dark-400 text-sm">{subtitle}</span>
                      </span>
                      {/* Switch track + knob */}
                      <span
                        aria-hidden="true"
                        className={`relative w-11 h-6 rounded-full flex-shrink-0 transition-colors ${
                          on ? "bg-brand-500" : "bg-dark-600"
                        }`}
                      >
                        <span
                          className={`absolute top-0.5 w-5 h-5 rounded-full bg-white transition-all ${
                            on ? "left-[22px]" : "left-0.5"
                          }`}
                        />
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>

            </div>

            {/* Apply bar — pinned below the scroll area so it acts as the
                sheet's sticky action bar on mobile; safe-area padding keeps
                it clear of the iOS home indicator. */}
            <div className="flex items-center gap-3 px-5 pt-4 pb-[calc(1rem+env(safe-area-inset-bottom))] border-t border-dark-800 md:pb-5">
              <button
                onClick={apply}
                disabled={previewCount === 0}
                className="btn-primary flex-1 flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {previewCount === 0
                  ? "No matches"
                  : `Show ${previewCount} result${previewCount === 1 ? "" : "s"}`}
                {draftCount > 0 && (
                  <span className="bg-white/25 text-xs font-bold px-2 py-0.5 rounded-md">
                    {draftCount} filter{draftCount === 1 ? "" : "s"}
                  </span>
                )}
              </button>
              {isKosherFilterActive(draft) && (
                <button
                  onClick={clear}
                  className="btn-secondary flex items-center gap-1.5 text-sm py-3 min-h-[44px]"
                >
                  <X className="w-4 h-4" aria-hidden="true" />
                  Clear
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
