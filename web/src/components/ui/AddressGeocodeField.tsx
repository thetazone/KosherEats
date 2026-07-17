"use client";

// Address → coordinates lookup backed by /api/geocode (the server-side US
// Census geocoder proxy), with the existing manual lat/lng entry pattern as a
// collapsible fallback. Fully controlled: the parent owns the query string and
// the lat/lng strings (they feed its POST /user/addresses payload verbatim);
// this component owns only the lookup lifecycle and disclosure state.
//
// Two usage shapes:
//   - standalone: showQueryInput (default) renders a single-line address input
//     + "Find address" button; Enter in the input triggers the same lookup.
//   - composed (cart checkout): showQueryInput={false} — the parent composes
//     `query` from its own street/city/state/zip fields and only the action
//     button + confirmation + manual section render here.

import { ChevronDown, Loader2, MapPin } from "lucide-react";
import { useRef, useState } from "react";

export interface GeocodeResolution {
  lat: number;
  lng: number;
  /** The geocoder's normalized form of the address it matched. */
  matched: string;
  /** The exact query string the coordinates were resolved from. */
  query: string;
}

export interface AddressGeocodeFieldProps {
  /** Controlled one-line address query ("1367 Coney Island Ave, Brooklyn, NY"). */
  query: string;
  /** Required when the query input is shown; unused otherwise. */
  onQueryChange?: (q: string) => void;
  /** Render the single-line query input (default true). The cart form passes
   *  false and composes `query` from its street/city/state/zip fields. */
  showQueryInput?: boolean;
  /** Lookup button label (default "Find address"). */
  buttonLabel?: string;
  /** Controlled manual latitude/longitude strings — the same strings the
   *  parent form validates and parses at submit time. */
  lat: string;
  lng: string;
  onLatChange: (v: string) => void;
  onLngChange: (v: string) => void;
  /** Fired on a successful lookup. The parent decides what to fill (the cart
   *  form writes String(lat)/String(lng) back into its lat/lng strings). */
  onResolved: (r: GeocodeResolution) => void;
}

// Same rejection rules as the submit-time validation in
// CheckoutPanel.saveAddress / account/addresses (null island, out-of-range) —
// surfaced inline here so manual typos are caught before submit. Blank fields
// are not an error at this layer; required-ness stays with the parent form.
function manualCoordsProblem(latStr: string, lngStr: string): string | null {
  if (!latStr.trim() && !lngStr.trim()) return null;
  const lat = parseFloat(latStr);
  const lng = parseFloat(lngStr);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return "Enter the address's latitude and longitude.";
  }
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
    return "Latitude must be between -90 and 90, longitude between -180 and 180.";
  }
  if (lat === 0 && lng === 0) {
    return "Coordinates can't be (0, 0). Enter the address's real latitude and longitude.";
  }
  return null;
}

export function AddressGeocodeField({
  query,
  onQueryChange,
  showQueryInput = true,
  buttonLabel = "Find address",
  lat,
  lng,
  onLatChange,
  onLngChange,
  onResolved,
}: AddressGeocodeFieldProps) {
  const [lookingUp, setLookingUp] = useState(false);
  const [resolved, setResolved] = useState<GeocodeResolution | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [manualOpen, setManualOpen] = useState(false);
  // Ref (not state) guards the in-flight lookup: state updates are async, so a
  // rapid double Enter could slip past a `lookingUp` check before re-render.
  // This is what coalesces Enter-key triggered lookups to one in flight.
  const inFlightRef = useRef(false);

  async function lookup() {
    const q = query.trim();
    if (!q || inFlightRef.current) return;
    inFlightRef.current = true;
    setLookingUp(true);
    setLookupError(null);
    try {
      const res = await fetch(`/api/geocode?q=${encodeURIComponent(q)}`);
      if (res.status === 404) {
        setResolved(null);
        setLookupError("Address not found — check spelling or enter coordinates manually");
        setManualOpen(true);
        return;
      }
      if (!res.ok) throw new Error("geocoder_unavailable");
      const data = (await res.json()) as { lat?: unknown; lng?: unknown; matched?: unknown };
      if (typeof data.lat !== "number" || typeof data.lng !== "number") {
        throw new Error("bad_response");
      }
      const r: GeocodeResolution = {
        lat: data.lat,
        lng: data.lng,
        matched: typeof data.matched === "string" && data.matched ? data.matched : q,
        query: q,
      };
      setResolved(r);
      onResolved(r);
    } catch {
      setResolved(null);
      setLookupError(
        "Couldn't look up that address right now — try again or enter coordinates manually."
      );
      setManualOpen(true);
    } finally {
      inFlightRef.current = false;
      setLookingUp(false);
    }
  }

  // The confirmation only shows while the query still matches what was
  // resolved — editing the address after a lookup hides the stale match
  // (the parent's lat/lng strings keep their values until re-resolved).
  const confirmation =
    resolved && resolved.query === query.trim() && !lookupError ? resolved : null;

  const manualProblem = manualOpen ? manualCoordsProblem(lat, lng) : null;

  return (
    <div className="space-y-2">
      {showQueryInput && (
        <input
          className="input w-full text-base"
          type="text"
          placeholder="Address (e.g. 1367 Coney Island Ave, Brooklyn, NY)"
          aria-label="Address to find"
          autoComplete="street-address"
          value={query}
          onChange={(e) => onQueryChange?.(e.target.value)}
          onKeyDown={(e) => {
            // The component usually lives inside a larger form — Enter must
            // trigger the lookup, never submit the parent form.
            if (e.key === "Enter") {
              e.preventDefault();
              void lookup();
            }
          }}
        />
      )}

      <button
        type="button"
        onClick={() => void lookup()}
        disabled={lookingUp || !query.trim()}
        className="btn-secondary w-full text-sm py-2 min-h-[44px] inline-flex items-center justify-center gap-2"
      >
        {lookingUp && <Loader2 className="w-4 h-4 animate-spin" aria-hidden="true" />}
        {lookingUp ? "Looking up…" : buttonLabel}
      </button>

      <div aria-live="polite">
        {confirmation && (
          <div className="flex items-start gap-1.5 text-sm text-brand-400">
            <MapPin className="w-4 h-4 mt-0.5 shrink-0" aria-hidden="true" />
            <span className="min-w-0">
              {confirmation.matched}
              <span className="block text-xs text-dark-400">
                {confirmation.lat.toFixed(5)}, {confirmation.lng.toFixed(5)}
              </span>
            </span>
          </div>
        )}
        {lookupError && <p className="text-sm text-red-400">{lookupError}</p>}
      </div>

      <div>
        <button
          type="button"
          onClick={() => setManualOpen((o) => !o)}
          aria-expanded={manualOpen}
          className="inline-flex items-center gap-1 min-h-[44px] text-sm text-dark-400 hover:text-white transition-colors"
        >
          <ChevronDown
            className={`w-4 h-4 transition-transform ${manualOpen ? "rotate-180" : ""}`}
            aria-hidden="true"
          />
          Enter coordinates manually
        </button>
        {manualOpen && (
          <div className="space-y-1.5">
            {/* text + inputMode=decimal (not type=number): decimal keypad on
                mobile, no scroll-wheel value changes — the pre-existing
                pattern from /account/addresses. Submit-time validation stays
                with the parent form; manualCoordsProblem mirrors it inline. */}
            <div className="flex gap-2">
              <input
                className="input w-full text-base"
                type="text"
                inputMode="decimal"
                aria-label="Latitude"
                placeholder="Latitude (e.g. 40.7128)"
                value={lat}
                onChange={(e) => onLatChange(e.target.value)}
              />
              <input
                className="input w-full text-base"
                type="text"
                inputMode="decimal"
                aria-label="Longitude"
                placeholder="Longitude (e.g. -74.0060)"
                value={lng}
                onChange={(e) => onLngChange(e.target.value)}
              />
            </div>
            {manualProblem && <p className="text-xs text-red-400">{manualProblem}</p>}
          </div>
        )}
      </div>
    </div>
  );
}
