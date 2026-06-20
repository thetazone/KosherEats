import Link from "next/link";

interface Restaurant {
  id: string;
  name: string;
  image_url: string;
  kosher_certification: string;
  cuisine_type: string[];
  rating: number;
  review_count: number;
  delivery_fee: number;
  est_delivery_min: number;
  est_delivery_max: number;
  is_glatt_kosher: boolean;
  is_open: boolean;
}

export function RestaurantCard({ restaurant }: { restaurant: Restaurant }) {
  return (
    <Link href={`/restaurant/${restaurant.id}`}>
      <div
        className={`card group cursor-pointer hover:border-dark-600 transition-all duration-200 ${
          !restaurant.is_open ? "opacity-60" : ""
        }`}
      >
        {/* Image */}
        <div className="relative h-48 bg-dark-800 overflow-hidden">
          {restaurant.image_url && (
            <img
              src={restaurant.image_url}
              alt={restaurant.name}
              className="absolute inset-0 w-full h-full object-cover"
            />
          )}
          <div className="absolute inset-0 bg-gradient-to-t from-dark-900/80 to-transparent z-10" />
          {!restaurant.is_open && (
            <div className="absolute inset-0 flex items-center justify-center z-20">
              <span className="bg-dark-900/90 text-dark-300 px-4 py-2 rounded-full text-sm font-medium">
                Closed
              </span>
            </div>
          )}
          {/* Placeholder gradient when no image */}
          {!restaurant.image_url && (
            <div className="absolute inset-0 bg-gradient-to-br from-brand-900/40 to-dark-800" />
          )}

          {/* Certification badge */}
          <div className="absolute top-3 left-3 z-20">
            <span className="bg-brand-500 text-white text-xs font-bold px-2 py-1 rounded-lg">
              {restaurant.kosher_certification}
            </span>
            {restaurant.is_glatt_kosher && (
              <span className="bg-dark-900/80 text-brand-400 text-xs font-bold px-2 py-1 rounded-lg ml-1">
                Glatt
              </span>
            )}
          </div>
        </div>

        {/* Info */}
        <div className="p-4">
          <h3 className="font-bold text-lg group-hover:text-brand-400 transition-colors">
            {restaurant.name}
          </h3>

          <div className="flex items-center gap-2 mt-1">
            <div className="flex items-center gap-1">
              <svg
                className="w-4 h-4 text-brand-400"
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
              </svg>
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
              ${(restaurant.delivery_fee / 100).toFixed(2)} delivery
            </span>
          </div>
        </div>
      </div>
    </Link>
  );
}
