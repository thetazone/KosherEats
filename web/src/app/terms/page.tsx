import { Header } from "@/components/layout/Header";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Terms of Service - KosherEats",
  description: "KosherEats terms of service",
};

export default function TermsPage() {
  return (
    <>
      <Header />
      <main className="max-w-3xl mx-auto px-6 py-16 text-dark-200">
      <h1 className="text-4xl font-bold text-white mb-2">Terms of Service</h1>
      <p className="text-dark-400 mb-10">Last updated: April 5, 2026</p>

      <div className="space-y-8 text-sm leading-relaxed text-dark-300">
        <section>
          <h2 className="text-xl font-semibold text-white mb-3">1. Acceptance</h2>
          <p>
            By using the KosherEats mobile applications or website
            (&quot;Service&quot;), you agree to these Terms of Service. If you do
            not agree, do not use the Service.
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-3">2. The Service</h2>
          <p>
            KosherEats is a food delivery platform that connects consumers with
            kosher restaurants and independent couriers. We facilitate orders,
            payments, and delivery logistics but do not prepare food ourselves.
            Restaurants are independent businesses responsible for food quality,
            kosher certification accuracy, and preparation.
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-3">3. Accounts</h2>
          <p>
            You must provide accurate information when creating an account. You
            are responsible for maintaining the security of your login
            credentials. Notify us immediately if you suspect unauthorized access.
            We reserve the right to suspend accounts that violate these terms.
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-3">4. Orders and Payments</h2>
          <p>
            Prices shown include the food price set by the restaurant. Delivery
            fees, service fees, and applicable taxes are displayed at checkout
            before you place the order. Tips go directly to your courier. All
            payments are processed by Stripe. Refunds for order issues are
            handled on a case-by-case basis — contact support.
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-3">5. Courier Terms</h2>
          <p>
            Couriers are independent contractors, not employees of KosherEats.
            Couriers must pass a background check, maintain valid documentation,
            and comply with all traffic and food safety regulations. Courier
            payouts are processed through Stripe Connect. KosherEats may
            deactivate courier accounts for violations of these terms or
            applicable law.
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-3">6. Restaurant Terms</h2>
          <p>
            Restaurants listed on KosherEats are responsible for the accuracy of
            their menu, pricing, kosher certification status, and food safety.
            KosherEats does not independently verify kosher certifications —
            consumers should verify certification details with the certifying
            agency if important to them.
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-3">7. Acceptable Use</h2>
          <p>
            You agree not to misuse the Service, including but not limited to:
            creating fake accounts, placing fraudulent orders, harassing
            couriers or restaurant staff, reverse-engineering the app, or
            attempting to access other users&apos; data.
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-3">8. Limitation of Liability</h2>
          <p>
            KosherEats is provided &quot;as is.&quot; We are not liable for food
            quality, delivery delays, or losses arising from use of the Service
            beyond the amount you paid for the specific order in question. We do
            not guarantee uninterrupted availability of the Service.
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-3">9. Changes</h2>
          <p>
            We may update these terms at any time. Continued use after changes
            constitutes acceptance. Material changes will be communicated via
            the app or email.
          </p>
        </section>

        <section>
          <h2 className="text-xl font-semibold text-white mb-3">10. Contact</h2>
          <p>
            Questions about these terms? Email{" "}
            <a href="mailto:support@koshereats.shop" className="text-brand-400 underline">
              support@koshereats.shop
            </a>.
          </p>
        </section>
      </div>
      </main>
    </>
  );
}
