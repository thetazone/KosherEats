import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Support - KosherEats",
  description: "Get help with KosherEats",
};

export default function SupportPage() {
  return (
    <main className="max-w-3xl mx-auto px-6 py-16 text-dark-200">
      <h1 className="text-4xl font-bold text-white mb-2">Support</h1>
      <p className="text-dark-400 mb-10">We&apos;re here to help.</p>

      <div className="space-y-8 text-sm leading-relaxed">
        <section className="bg-dark-900 border border-dark-800 rounded-xl p-6">
          <h2 className="text-lg font-semibold text-white mb-3">Contact us</h2>
          <p className="text-dark-300 mb-4">
            For questions about orders, account issues, or anything else, reach
            out and we&apos;ll respond as quickly as we can.
          </p>
          <p>
            <strong className="text-white">Email:</strong>{" "}
            <a href="mailto:support@koshereats.shop" className="text-brand-400 underline">
              support@koshereats.shop
            </a>
          </p>
        </section>

        <section className="bg-dark-900 border border-dark-800 rounded-xl p-6">
          <h2 className="text-lg font-semibold text-white mb-3">Common questions</h2>
          <div className="space-y-4 text-dark-300">
            <div>
              <h3 className="font-medium text-white">How do I cancel an order?</h3>
              <p>Open the order in the Orders tab and tap &quot;Cancel Order&quot;. Orders can be cancelled before the restaurant starts preparing.</p>
            </div>
            <div>
              <h3 className="font-medium text-white">How do I contact my courier?</h3>
              <p>Once a courier is assigned, tap the Chat button on your order tracking screen to send them a message directly.</p>
            </div>
            <div>
              <h3 className="font-medium text-white">How do I become a courier?</h3>
              <p>Download the KosherEats Driver app and sign up. You&apos;ll go through a short onboarding (vehicle info, documents, background check) and can start delivering once approved.</p>
            </div>
            <div>
              <h3 className="font-medium text-white">How do I list my restaurant?</h3>
              <p>Email us at <a href="mailto:partners@koshereats.shop" className="text-brand-400 underline">partners@koshereats.shop</a> and we&apos;ll set up your account and get your menu online.</p>
            </div>
          </div>
        </section>

        <section className="bg-dark-900 border border-dark-800 rounded-xl p-6">
          <h2 className="text-lg font-semibold text-white mb-3">App version</h2>
          <p className="text-dark-300">
            Make sure you&apos;re running the latest version of the app from the
            App Store or Google Play for the best experience and latest fixes.
          </p>
        </section>
      </div>
    </main>
  );
}
