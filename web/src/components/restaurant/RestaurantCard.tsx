import { KosherBadge } from "@/components/restaurant/KosherBadge";
import { formatUSD } from "@/lib/format";
import type { Restaurant } from "@/types";
import { Heart, Star } from "lucide-react";
import Image from "next/image";
import Link from "next/link";

// isFavorite/onToggleFavorite are optional (mirrors the iOS
// RestaurantCardView): pass onToggleFavorite only for signed-in users —
// when omitted, no heart is rendered.
export function RestaurantCard({
  restaurant,
  isFavorite = false,
  onToggleFavorite,
}: {
  restaurant: Restaurant;
  isFavorite?: boolean;
  onToggleFavorite?: () => void;
}) {
  return (
    <Link href={`/restaurant/${restaurant.id}`}>
      <div
        className={`card group cursor-pointer hover:border-dark-600 transition-all duration-200 ${
          !restaurant.is_open ? "opacity-60" : ""
        }`}
      >
        {/* Image */}
        <div className="relative h-48 bg-dark-800 overflow-hidden">
          {restaurant.image_url ? (
            <Image
              src={restaurant.image_url}
              alt={restaurant.name}
              fill
              sizes="(max-width: 768px) 100vw, 33vw"
              className="object-cover"
            />
          ) : (
            <div className="absolute inset-0 bg-gradient-to-br from-brand-900/40 to-dark-800" />
          )}
          <div className="absolute inset-0 bg-gradient-to-t from-dark-900/80 to-transparent z-10" />
          {!restaurant.is_open && (
            <div className="absolute inset-0 flex items-center justify-center z-20">
              <span className="bg-dark-900/90 text-dark-300 px-4 py-2 rounded-full text-sm font-medium">
                Closed
              </span>
            </div>
          )}

          {/* Favorite heart — stops the Link navigation so a heart tap never
              opens the restaurant page. */}
          {onToggleFavorite && (
            <button
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                onToggleFavorite();
              }}
              aria-label={
                isFavorite
                  ? `Remove ${restaurant.name} from favorites`
                  : `Add ${restaurant.name} to favorites`
              }
              className="absolute top-3 right-3 z-30 bg-dark-900/70 hover:bg-dark-900/90 rounded-full p-2 transition-colors"
            >
              <Heart
                className={`w-5 h-5 transition-colors ${
                  isFavorite ? "text-red-500 fill-red-500" : "text-white"
                }`}
                aria-hidden="true"
              />
            </button>
          )}

          {/* Certification badge */}
          <div className="absolute top-3 left-3 z-20">
            <KosherBadge restaurant={restaurant} size="compact" />
          </div>
        </div>

        {/* Info */}
        <div className="p-4">
          <h3 className="font-bold text-lg group-hover:text-brand-400 transition-colors">
            {restaurant.name}
          </h3>

          <div className="flex items-center gap-2 mt-1">
            <div className="flex items-center gap-1">
              <Star className="w-4 h-4 text-brand-400 fill-brand-400" aria-hidden="true" />
              <span className="text-sm font-medium">{restaurant.rating}</span>
              <span className="text-dark-500 text-sm">
                ({restaurant.review_count})
              </span>
            </div>
            <span className="text-dark-600">·</span>
            <span className="text-dark-400 text-sm">
              {restaurant.cuisine_type.join(", ")}
            </span>
          </div>

          <div className="flex items-center gap-3 mt-3 text-sm text-dark-400">
            <span>
              {restaurant.est_delivery_min}-{restaurant.est_delivery_max} min
            </span>
            <span className="text-dark-600">·</span>
            <span>
              {formatUSD(restaurant.delivery_fee)} delivery
            </span>
          </div>
        </div>
      </div>
    </Link>
  );
}
