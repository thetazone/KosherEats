import { Header } from "@/components/layout/Header";
import { RestaurantCard } from "@/components/restaurant/RestaurantCard";
import { SearchBar } from "@/components/ui/SearchBar";

const FEATURED_RESTAURANTS = [
  {
    id: "1",
    name: "Jerusalem Grill",
    image_url: "/placeholder-restaurant.jpg",
    kosher_certification: "OU" as const,
    cuisine_type: ["Israeli", "Middle Eastern"],
    rating: 4.8,
    review_count: 324,
    delivery_fee: 399,
    est_delivery_min: 25,
    est_delivery_max: 40,
    is_glatt_kosher: true,
    is_open: true,
  },
  {
    id: "2",
    name: "Shalom Sushi",
    image_url: "/placeholder-restaurant.jpg",
    kosher_certification: "OK" as const,
    cuisine_type: ["Japanese", "Sushi"],
    rating: 4.6,
    review_count: 189,
    delivery_fee: 499,
    est_delivery_min: 30,
    est_delivery_max: 45,
    is_glatt_kosher: false,
    is_open: true,
  },
  {
    id: "3",
    name: "Kosher Burger Co.",
    image_url: "/placeholder-restaurant.jpg",
    kosher_certification: "Star-K" as const,
    cuisine_type: ["American", "Burgers"],
    rating: 4.5,
    review_count: 412,
    delivery_fee: 299,
    est_delivery_min: 20,
    est_delivery_max: 35,
    is_glatt_kosher: true,
    is_open: true,
  },
  {
    id: "4",
    name: "Mama's Kitchen",
    image_url: "/placeholder-restaurant.jpg",
    kosher_certification: "cRc" as const,
    cuisine_type: ["Jewish", "Deli"],
    rating: 4.9,
    review_count: 567,
    delivery_fee: 349,
    est_delivery_min: 25,
    est_delivery_max: 40,
    is_glatt_kosher: true,
    is_open: false,
  },
];

const CUISINE_FILTERS = [
  "All",
  "Israeli",
  "Pizza",
  "Sushi",
  "Burgers",
  "Deli",
  "Chinese",
  "Mexican",
  "Italian",
  "Bakery",
  "Falafel",
];

export default function Home() {
  return (
    <>
      <Header />
      <main className="flex-1">
        {/* Hero */}
        <section className="bg-gradient-to-b from-dark-900 to-dark-950 py-16">
          <div className="max-w-7xl mx-auto px-4 text-center">
            <h1 className="text-5xl font-extrabold mb-4">
              Kosher food,{" "}
              <span className="text-brand-500">delivered.</span>
            </h1>
            <p className="text-dark-400 text-lg mb-8 max-w-2xl mx-auto">
              Order from the best kosher-certified restaurants near you.
              Every restaurant verified. Every meal trusted.
            </p>
            <SearchBar />
          </div>
        </section>

        {/* Cuisine Filters */}
        <section className="max-w-7xl mx-auto px-4 py-8">
          <div className="flex gap-3 overflow-x-auto pb-4 scrollbar-hide">
            {CUISINE_FILTERS.map((cuisine) => (
              <button
                key={cuisine}
                className={`px-5 py-2.5 rounded-full text-sm font-medium whitespace-nowrap transition-colors ${
                  cuisine === "All"
                    ? "bg-brand-500 text-white"
                    : "bg-dark-800 text-dark-300 hover:bg-dark-700 hover:text-white"
                }`}
              >
                {cuisine}
              </button>
            ))}
          </div>
        </section>

        {/* Restaurant Grid */}
        <section className="max-w-7xl mx-auto px-4 pb-16">
          <h2 className="text-2xl font-bold mb-6">Popular near you</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
            {FEATURED_RESTAURANTS.map((restaurant) => (
              <RestaurantCard key={restaurant.id} restaurant={restaurant} />
            ))}
          </div>
        </section>

        {/* Kosher Certifications Info */}
        <section className="max-w-7xl mx-auto px-4 pb-16">
          <h2 className="text-2xl font-bold mb-6">Filter by certification</h2>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            {["OU", "OK", "Star-K", "Kof-K", "cRc", "Badatz", "Chof-K"].map(
              (cert) => (
                <button
                  key={cert}
                  className="card p-4 text-center hover:border-brand-500 transition-colors cursor-pointer"
                >
                  <span className="text-lg font-bold text-brand-400">
                    {cert}
                  </span>
                  <p className="text-dark-400 text-sm mt-1">Certified</p>
                </button>
              )
            )}
          </div>
        </section>
      </main>

      {/* Footer */}
      <footer className="bg-dark-900 border-t border-dark-800 py-12">
        <div className="max-w-7xl mx-auto px-4">
          <div className="grid grid-cols-1 md:grid-cols-4 gap-8">
            <div>
              <h3 className="text-brand-500 font-bold text-xl mb-4">
                KosherEats
              </h3>
              <p className="text-dark-400 text-sm">
                The trusted kosher food delivery platform.
              </p>
            </div>
            <div>
              <h4 className="font-semibold mb-3">For You</h4>
              <ul className="space-y-2 text-dark-400 text-sm">
                <li>Browse Restaurants</li>
                <li>My Orders</li>
                <li>Favorites</li>
              </ul>
            </div>
            <div>
              <h4 className="font-semibold mb-3">For Restaurants</h4>
              <ul className="space-y-2 text-dark-400 text-sm">
                <li>Partner with us</li>
                <li>Seller Dashboard</li>
              </ul>
            </div>
            <div>
              <h4 className="font-semibold mb-3">Support</h4>
              <ul className="space-y-2 text-dark-400 text-sm">
                <li>Help Center</li>
                <li>Contact Us</li>
                <li>Kashrut Policy</li>
              </ul>
            </div>
          </div>
          <div className="border-t border-dark-800 mt-8 pt-8 text-center text-dark-500 text-sm">
            &copy; 2026 KosherEats. All rights reserved.
          </div>
        </div>
      </footer>
    </>
  );
}
