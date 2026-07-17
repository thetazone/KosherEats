import type { Restaurant } from "@/types";

// KosherBadge is the certification chip cluster — the brand-colored
// certification mark plus an optional "Glatt" chip. Reused in two places so
// the certification always renders identically:
//   - compact: restaurant cards (browse/search grids)
//   - regular: the restaurant detail hero header
export function KosherBadge({
  restaurant,
  size = "regular",
}: {
  restaurant: Pick<Restaurant, "kosher_certification" | "is_glatt_kosher">;
  size?: "compact" | "regular";
}) {
  const compact = size === "compact";
  const certClass = compact
    ? "bg-brand-500 text-white text-xs font-bold px-2 py-1 rounded-lg"
    : "bg-brand-500 text-white text-sm font-bold px-3 py-1 rounded-lg";
  const glattClass = compact
    ? "bg-dark-900/80 text-brand-400 text-xs font-bold px-2 py-1 rounded-lg"
    : "bg-dark-800 text-brand-400 text-sm font-bold px-3 py-1 rounded-lg border border-dark-700";

  return (
    <div className={`flex items-center ${compact ? "gap-1" : "gap-3"}`}>
      <span className={certClass}>{restaurant.kosher_certification}</span>
      {restaurant.is_glatt_kosher && (
        <span className={glattClass}>{compact ? "Glatt" : "Glatt Kosher"}</span>
      )}
    </div>
  );
}
