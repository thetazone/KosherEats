import Link from "next/link";
import {
  Truck,
  ShieldCheck,
  Store,
  Smartphone,
  ChevronRight,
  Star,
  Clock,
  MapPin,
  Bike,
  DollarSign,
  Calendar,
  Users,
} from "lucide-react";

const CERTIFICATIONS = ["OU", "OK", "Star-K", "Kof-K", "cRc", "Badatz", "Chof-K"];

// Download / beta-tester links. iOS URLs need to be pasted in once the
// App Store listings are public; until then the buttons point to '#' and
// stay disabled-looking.
const APP_LINKS = {
  groupJoin: "https://groups.google.com/g/koshereatstesters",
  androidConsumerOptIn: "https://play.google.com/apps/testing/com.koshereats.consumer",
  androidSellerOptIn: "https://play.google.com/apps/testing/com.koshereats.seller",
  iosConsumer: "https://apps.apple.com/us/app/koshereats/id6761736422",
  iosSeller: "https://apps.apple.com/us/app/koshereatsseller/id6762100630",
};

const HOW_IT_WORKS = [
  {
    icon: MapPin,
    title: "Enter your address",
    description: "We'll show you kosher restaurants that deliver to your area.",
  },
  {
    icon: Store,
    title: "Pick a restaurant",
    description:
      "Browse menus from verified kosher-certified restaurants. Filter by certification, cuisine, or dietary needs.",
  },
  {
    icon: Truck,
    title: "Get it delivered",
    description:
      "Track your order in real-time from kitchen to your door.",
  },
];

const RESTAURANT_BENEFITS = [
  {
    icon: Users,
    title: "Reach more customers",
    description: "Tap into a growing community of kosher-observant diners looking for delivery options.",
  },
  {
    icon: Smartphone,
    title: "Easy seller dashboard",
    description: "Manage orders, update menus, and track earnings from our seller app or web dashboard.",
  },
  {
    icon: DollarSign,
    title: "Competitive rates",
    description: "Lower commission than the big platforms. We're built for the kosher community, not Wall Street.",
  },
  {
    icon: Calendar,
    title: "Shabbat-aware scheduling",
    description: "Automatic Shabbat/Yom Tov hours. No orders when you're closed — no missed earnings when you're open.",
  },
];

const COURIER_BENEFITS = [
  {
    icon: DollarSign,
    title: "Earn on your schedule",
    description: "Deliver when it works for you. Flexible hours, competitive pay, tips included.",
  },
  {
    icon: Bike,
    title: "Any vehicle works",
    description: "Car, scooter, bike, or on foot — deliver however suits your area.",
  },
  {
    icon: MapPin,
    title: "Local deliveries",
    description: "Short-distance deliveries within your neighborhood. No long highway drives.",
  },
  {
    icon: Clock,
    title: "Quick signup",
    description: "Background check, vehicle info, and you're ready to go. Start earning within days.",
  },
];

function AppCard({
  title,
  subtitle,
  iosHref,
  androidHref,
  androidLabel,
}: {
  title: string;
  subtitle: string;
  iosHref: string;
  androidHref: string;
  androidLabel: string;
}) {
  const iosReady = iosHref !== "";
  return (
    <div className="card p-6">
      <div className="mb-5">
        <h3 className="text-xl font-bold">{title}</h3>
        <p className="text-dark-400 text-sm">{subtitle}</p>
      </div>
      <div className="flex flex-col gap-3">
        <a
          href={iosReady ? iosHref : undefined}
          target={iosReady ? "_blank" : undefined}
          rel={iosReady ? "noopener noreferrer" : undefined}
          aria-disabled={!iosReady}
          className={`inline-flex items-center gap-3 rounded-xl px-5 py-3 transition-colors ${
            iosReady
              ? "bg-white text-black hover:bg-dark-100"
              : "bg-dark-800 text-dark-500 cursor-not-allowed"
          }`}
        >
          <svg className="w-7 h-7" viewBox="0 0 24 24" fill="currentColor">
            <path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.8-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z" />
          </svg>
          <div className="text-left">
            <div className="text-xs">{iosReady ? "Download on the" : "Coming soon to"}</div>
            <div className="text-lg font-semibold -mt-1">App Store</div>
          </div>
        </a>
        <a
          href={androidHref}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-3 bg-brand-500 hover:bg-brand-600 text-white rounded-xl px-5 py-3 transition-colors"
        >
          <svg className="w-7 h-7" viewBox="0 0 24 24" fill="currentColor">
            <path d="M3.18 23.67c-.37-.2-.63-.55-.63-1.02V1.35C2.55.53 3.32.1 4.04.5l9.56 5.52-2.6 2.52L3.18 23.67zM20.17 10.72l-2.76-1.6-3 2.88 3 2.88 2.76-1.6c.83-.48.83-1.68 0-2.16v-.4zM4.53.69l11.19 11.19-2.4 2.4L4.53.69zM4.53 23.31l8.79-13.59 2.4 2.4L4.53 23.31z" />
          </svg>
          <div className="text-left">
            <div className="text-xs">{androidLabel}</div>
            <div className="text-lg font-semibold -mt-1">Google Play</div>
          </div>
        </a>
      </div>
    </div>
  );
}

export default function Home() {
  return (
    <>
      {/* Nav */}
      <header className="bg-dark-950/80 backdrop-blur-md border-b border-dark-800 sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 h-16 flex items-center justify-between">
          <Link href="/" className="flex items-center gap-1">
            <span className="text-2xl font-extrabold text-brand-500">Kosher</span>
            <span className="text-2xl font-extrabold text-white">Eats</span>
          </Link>
          <nav className="hidden md:flex items-center gap-8">
            <a href="#how-it-works" className="text-dark-300 hover:text-white transition-colors text-sm font-medium">
              How it works
            </a>
            <a href="#restaurants" className="text-dark-300 hover:text-white transition-colors text-sm font-medium">
              For Restaurants
            </a>
            <a href="#couriers" className="text-dark-300 hover:text-white transition-colors text-sm font-medium">
              Deliver with us
            </a>
            <Link href="/support" className="text-dark-300 hover:text-white transition-colors text-sm font-medium">
              Support
            </Link>
          </nav>
          <div className="flex items-center gap-3">
            <Link href="/auth" className="hidden md:inline-block text-dark-300 hover:text-white transition-colors text-sm font-medium">
              Sign in
            </Link>
            <a href="#download" className="btn-primary text-sm py-2 px-5">
              Get the app
            </a>
          </div>
        </div>
      </header>

      <main className="flex-1">
        {/* Hero */}
        <section className="relative overflow-hidden">
          <div className="absolute inset-0 bg-gradient-to-br from-brand-950/50 via-dark-950 to-dark-950" />
          <div className="absolute top-20 right-0 w-[600px] h-[600px] bg-brand-500/5 rounded-full blur-3xl" />
          <div className="absolute bottom-0 left-0 w-[400px] h-[400px] bg-brand-500/5 rounded-full blur-3xl" />

          <div className="relative max-w-7xl mx-auto px-4 py-24 md:py-36">
            <div className="max-w-3xl">
              <div className="inline-flex items-center gap-2 bg-brand-500/10 border border-brand-500/20 rounded-full px-4 py-1.5 mb-6">
                <ShieldCheck className="w-4 h-4 text-brand-400" />
                <span className="text-brand-400 text-sm font-medium">Every restaurant kosher-certified</span>
              </div>

              <h1 className="text-5xl md:text-7xl font-extrabold leading-tight mb-6">
                Kosher food,{" "}
                <span className="text-transparent bg-clip-text bg-gradient-to-r from-brand-400 to-brand-600">
                  delivered.
                </span>
              </h1>

              <p className="text-dark-300 text-xl md:text-2xl mb-10 max-w-2xl leading-relaxed">
                Order from verified kosher restaurants near you. OU, OK, Star-K, Kof-K
                and more — filter by the certification you trust. Glatt, Cholov Yisroel,
                Pas Yisroel, meat, dairy, or pareve.
              </p>

              <div className="flex flex-col sm:flex-row gap-4">
                <a
                  href="#download"
                  className="btn-primary text-lg py-4 px-8 flex items-center justify-center gap-2"
                >
                  <Smartphone className="w-5 h-5" />
                  Download the app
                </a>
                <a
                  href="#how-it-works"
                  className="btn-secondary text-lg py-4 px-8 flex items-center justify-center gap-2"
                >
                  Learn more
                  <ChevronRight className="w-5 h-5" />
                </a>
              </div>
            </div>
          </div>
        </section>

        {/* Trust bar */}
        <section className="border-y border-dark-800 bg-dark-900/50">
          <div className="max-w-7xl mx-auto px-4 py-6">
            <div className="flex flex-wrap items-center justify-center gap-8 md:gap-16">
              {CERTIFICATIONS.map((cert) => (
                <span
                  key={cert}
                  className="text-dark-400 font-bold text-lg tracking-wide"
                >
                  {cert}
                </span>
              ))}
            </div>
          </div>
        </section>

        {/* How it works */}
        <section id="how-it-works" className="py-24 bg-dark-950">
          <div className="max-w-7xl mx-auto px-4">
            <div className="text-center mb-16">
              <h2 className="text-4xl font-extrabold mb-4">How it works</h2>
              <p className="text-dark-400 text-lg max-w-2xl mx-auto">
                Getting kosher food delivered is as easy as 1-2-3.
              </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
              {HOW_IT_WORKS.map((step, i) => (
                <div key={step.title} className="relative card p-8 text-center group hover:border-brand-500/30 transition-all">
                  <div className="absolute -top-4 left-1/2 -translate-x-1/2 w-8 h-8 bg-brand-500 rounded-full flex items-center justify-center text-sm font-bold">
                    {i + 1}
                  </div>
                  <step.icon className="w-10 h-10 text-brand-400 mx-auto mb-4 mt-2" />
                  <h3 className="text-xl font-bold mb-3">{step.title}</h3>
                  <p className="text-dark-400 leading-relaxed">{step.description}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* Kosher-specific features */}
        <section className="py-24 bg-dark-900/30">
          <div className="max-w-7xl mx-auto px-4">
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-16 items-center">
              <div>
                <h2 className="text-4xl font-extrabold mb-6">
                  Built for the{" "}
                  <span className="text-brand-500">kosher community</span>
                </h2>
                <p className="text-dark-300 text-lg mb-8 leading-relaxed">
                  We're not a general delivery app with a kosher filter bolted on.
                  Every restaurant on KosherEats is verified kosher. Every menu item
                  is labeled meat, dairy, or pareve.
                </p>

                <div className="space-y-6">
                  <div className="flex gap-4">
                    <div className="flex-shrink-0 w-10 h-10 bg-brand-500/10 rounded-xl flex items-center justify-center">
                      <ShieldCheck className="w-5 h-5 text-brand-400" />
                    </div>
                    <div>
                      <h4 className="font-bold mb-1">Certification filtering</h4>
                      <p className="text-dark-400 text-sm">
                        Search by OU, OK, Star-K, Kof-K, cRc, Badatz, Chof-K — see only restaurants
                        with the hashgacha you rely on.
                      </p>
                    </div>
                  </div>

                  <div className="flex gap-4">
                    <div className="flex-shrink-0 w-10 h-10 bg-brand-500/10 rounded-xl flex items-center justify-center">
                      <Star className="w-5 h-5 text-brand-400" />
                    </div>
                    <div>
                      <h4 className="font-bold mb-1">Glatt & Cholov Yisroel</h4>
                      <p className="text-dark-400 text-sm">
                        Filter for Glatt Kosher, Cholov Yisroel, and Pas Yisroel with a single tap.
                      </p>
                    </div>
                  </div>

                  <div className="flex gap-4">
                    <div className="flex-shrink-0 w-10 h-10 bg-brand-500/10 rounded-xl flex items-center justify-center">
                      <Clock className="w-5 h-5 text-brand-400" />
                    </div>
                    <div>
                      <h4 className="font-bold mb-1">Shabbat-aware</h4>
                      <p className="text-dark-400 text-sm">
                        Restaurants automatically show as closed during Shabbat and Yom Tov.
                        Schedule orders for Motzei Shabbat pickup.
                      </p>
                    </div>
                  </div>
                </div>
              </div>

              {/* Feature visual / phone mockup placeholder */}
              <div className="relative">
                <div className="bg-dark-900 border border-dark-800 rounded-3xl p-8 aspect-[3/4] flex flex-col items-center justify-center">
                  <div className="w-24 h-24 bg-brand-500/10 rounded-full flex items-center justify-center mb-6">
                    <span className="text-4xl font-extrabold text-brand-500">K</span>
                  </div>
                  <div className="space-y-3 w-full max-w-xs">
                    {["Jerusalem Grill", "Shalom Sushi", "Kosher Burger Co.", "Mama's Kitchen"].map(
                      (name, i) => (
                        <div
                          key={name}
                          className="bg-dark-800 rounded-xl p-3 flex items-center gap-3"
                        >
                          <div className="w-10 h-10 bg-brand-500/20 rounded-lg flex items-center justify-center">
                            <span className="text-brand-400 text-xs font-bold">
                              {CERTIFICATIONS[i]}
                            </span>
                          </div>
                          <div>
                            <div className="text-sm font-medium">{name}</div>
                            <div className="text-dark-500 text-xs">25-40 min</div>
                          </div>
                          <Star className="w-3 h-3 text-brand-400 ml-auto" />
                        </div>
                      )
                    )}
                  </div>
                </div>
                <div className="absolute -bottom-4 -right-4 w-32 h-32 bg-brand-500/10 rounded-full blur-2xl" />
              </div>
            </div>
          </div>
        </section>

        {/* For Restaurants */}
        <section id="restaurants" className="py-24 bg-dark-950">
          <div className="max-w-7xl mx-auto px-4">
            <div className="text-center mb-16">
              <span className="text-brand-400 font-semibold text-sm uppercase tracking-wider">For Restaurants</span>
              <h2 className="text-4xl font-extrabold mt-3 mb-4">
                Grow your kosher restaurant
              </h2>
              <p className="text-dark-400 text-lg max-w-2xl mx-auto">
                Partner with KosherEats and reach customers who are specifically looking for kosher delivery.
                No more competing with non-kosher restaurants for visibility.
              </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              {RESTAURANT_BENEFITS.map((benefit) => (
                <div key={benefit.title} className="card p-6 hover:border-brand-500/30 transition-all">
                  <benefit.icon className="w-8 h-8 text-brand-400 mb-4" />
                  <h3 className="text-lg font-bold mb-2">{benefit.title}</h3>
                  <p className="text-dark-400 leading-relaxed">{benefit.description}</p>
                </div>
              ))}
            </div>

            <div className="text-center mt-12">
              <a href="mailto:partners@koshereats.shop" className="btn-primary text-lg py-4 px-8 inline-flex items-center gap-2">
                <Store className="w-5 h-5" />
                Partner with us
              </a>
            </div>
          </div>
        </section>

        {/* For Couriers */}
        <section id="couriers" className="py-24 bg-dark-900/30">
          <div className="max-w-7xl mx-auto px-4">
            <div className="text-center mb-16">
              <span className="text-brand-400 font-semibold text-sm uppercase tracking-wider">For Couriers</span>
              <h2 className="text-4xl font-extrabold mt-3 mb-4">
                Deliver with KosherEats
              </h2>
              <p className="text-dark-400 text-lg max-w-2xl mx-auto">
                Earn money delivering kosher food in your community. Flexible hours, fair pay, local routes.
              </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
              {COURIER_BENEFITS.map((benefit) => (
                <div key={benefit.title} className="card p-6 text-center hover:border-brand-500/30 transition-all">
                  <benefit.icon className="w-8 h-8 text-brand-400 mx-auto mb-4" />
                  <h3 className="text-lg font-bold mb-2">{benefit.title}</h3>
                  <p className="text-dark-400 text-sm leading-relaxed">{benefit.description}</p>
                </div>
              ))}
            </div>

            <div className="text-center mt-12">
              <a href="mailto:deliver@koshereats.shop" className="btn-secondary text-lg py-4 px-8 inline-flex items-center gap-2">
                <Bike className="w-5 h-5" />
                Start delivering
              </a>
            </div>
          </div>
        </section>

        {/* Get the apps */}
        <section id="download" className="py-24 bg-dark-950 relative overflow-hidden">
          <div className="absolute inset-0 bg-gradient-to-b from-brand-500/5 to-transparent" />
          <div className="relative max-w-5xl mx-auto px-4">
            <div className="text-center mb-12">
              <span className="text-brand-400 font-semibold text-sm uppercase tracking-wider">Beta</span>
              <h2 className="text-4xl md:text-5xl font-extrabold mt-3 mb-4">
                Get the apps
              </h2>
              <p className="text-dark-300 text-lg max-w-2xl mx-auto">
                iOS is live on the App Store. Android is in closed beta — join our
                tester group below to install on Android.
              </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-12">
              <AppCard
                title="KosherEats"
                subtitle="For diners"
                iosHref={APP_LINKS.iosConsumer}
                androidHref={APP_LINKS.androidConsumerOptIn}
                androidLabel="Join Android beta"
              />
              <AppCard
                title="KosherEats Seller"
                subtitle="For restaurants"
                iosHref={APP_LINKS.iosSeller}
                androidHref={APP_LINKS.androidSellerOptIn}
                androidLabel="Join Android beta"
              />
            </div>

            <div className="card p-8 text-center">
              <Users className="w-8 h-8 text-brand-400 mx-auto mb-4" />
              <h3 className="text-2xl font-bold mb-2">Help us test on Android</h3>
              <p className="text-dark-400 mb-6 max-w-xl mx-auto">
                We need testers to opt in before Google will let us launch publicly.
                Join the tester group with your Google account, then tap the &ldquo;Join Android beta&rdquo;
                button above for the app you want to try.
              </p>
              <a
                href={APP_LINKS.groupJoin}
                target="_blank"
                rel="noopener noreferrer"
                className="btn-primary inline-flex items-center gap-2 text-lg py-4 px-8"
              >
                <Users className="w-5 h-5" />
                Join the tester group
              </a>
            </div>
          </div>
        </section>
      </main>

      {/* Footer */}
      <footer className="bg-dark-900 border-t border-dark-800 py-12">
        <div className="max-w-7xl mx-auto px-4">
          <div className="grid grid-cols-1 md:grid-cols-4 gap-8">
            <div>
              <Link href="/" className="inline-flex items-center gap-1 mb-4">
                <span className="text-brand-500 font-extrabold text-xl">Kosher</span>
                <span className="text-white font-extrabold text-xl">Eats</span>
              </Link>
              <p className="text-dark-400 text-sm">
                The trusted kosher food delivery platform. Every restaurant verified.
                Every meal trusted.
              </p>
            </div>
            <div>
              <h4 className="font-semibold mb-4">Order</h4>
              <ul className="space-y-2 text-dark-400 text-sm">
                <li>
                  <a href="#download" className="hover:text-white transition-colors">Get the app</a>
                </li>
                <li>
                  <a href="#how-it-works" className="hover:text-white transition-colors">How it works</a>
                </li>
              </ul>
            </div>
            <div>
              <h4 className="font-semibold mb-4">Partner</h4>
              <ul className="space-y-2 text-dark-400 text-sm">
                <li>
                  <a href="#restaurants" className="hover:text-white transition-colors">For Restaurants</a>
                </li>
                <li>
                  <a href="#couriers" className="hover:text-white transition-colors">Deliver with us</a>
                </li>
              </ul>
            </div>
            <div>
              <h4 className="font-semibold mb-4">Company</h4>
              <ul className="space-y-2 text-dark-400 text-sm">
                <li>
                  <Link href="/support" className="hover:text-white transition-colors">Support</Link>
                </li>
                <li>
                  <Link href="/privacy" className="hover:text-white transition-colors">Privacy Policy</Link>
                </li>
                <li>
                  <Link href="/terms" className="hover:text-white transition-colors">Terms of Service</Link>
                </li>
              </ul>
            </div>
          </div>
          <div className="border-t border-dark-800 mt-8 pt-8 text-center text-dark-500 text-sm">
            &copy; {new Date().getFullYear()} KosherEats. All rights reserved.
          </div>
        </div>
      </footer>
    </>
  );
}
