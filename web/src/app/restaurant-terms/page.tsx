import { Header } from "@/components/layout/Header";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Restaurant Partner Agreement - KosherEats",
  description:
    "The agreement between KosherEats and the restaurants it lists: services, fees, payouts, sales tax, and each side's responsibilities.",
};

// Version string the backend records when a restaurant taps Accept in the
// Restaurant app. Bump it (and the date) together with any substantive change.
const VERSION = "2026-10-01";
const LAST_UPDATED = "October 1, 2026";
const SUPPORT_EMAIL = "support@koshereats.shop";

export default function RestaurantTermsPage() {
  return (
    <>
      <Header />
      <main className="max-w-3xl mx-auto px-6 py-16 text-dark-200">
      <h1 className="text-4xl font-bold text-white mb-2">Restaurant Partner Agreement</h1>
      <p className="text-dark-400 mb-10">
        Last updated {LAST_UPDATED} · Version {VERSION}
      </p>

      <div className="space-y-8 text-sm leading-relaxed">
        <div className="space-y-3 text-dark-300">
          <p>
            This Restaurant Partner Agreement (the &quot;Agreement&quot;) is between{" "}
            <strong className="text-dark-200">KosherEats LLC</strong>, a New York limited
            liability company (NY DOS ID 8034762), 1547 E 5th St, Brooklyn, NY 11230
            (&quot;KosherEats,&quot; &quot;we,&quot; or &quot;us&quot;), and the restaurant or
            food business that accepts it (the &quot;Restaurant&quot; or &quot;you&quot;).
          </p>
          <p>
            It explains what KosherEats does for you, what we charge, how you get paid, and what
            each of us is responsible for. This version is effective {LAST_UPDATED}, and it
            applies to your restaurant from the moment you accept it.
          </p>
        </div>

        <Section id="services" title="1. Our services">
          <p>KosherEats runs an online marketplace for kosher food. Once your restaurant is listed, we:</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>list your menu in the KosherEats apps and on our website, koshereats.shop;</li>
            <li>take orders from customers and collect their payments; and</li>
            <li>
              arrange delivery through third-party courier services (for example, Uber Direct),
              or let customers pick up their orders from you.
            </li>
          </ul>
          <p>
            You may also choose to deliver orders yourself. KosherEats does not prepare food — you
            do.
          </p>
        </Section>

        <Section id="listing" title="2. Listing your restaurant">
          <p>
            We list a restaurant on KosherEats only after it has accepted this Agreement (and after
            the KosherEats team approves its application).
          </p>
          <p>
            You allow us to display your restaurant&apos;s name, logo, photos, menu, prices, hours,
            kosher certification details, and other information you provide, so customers can
            find you and order from you. You can update this information at any time in the
            KosherEats Restaurant app or the seller dashboard on our website.
          </p>
        </Section>

        <Section id="fees" title="3. Fees">
          <p>KosherEats charges you only the following fees, calculated per order:</p>

          <div className="card divide-y divide-dark-800">
            <div className="p-4">
              <p className="font-semibold text-white">
                Orders delivered by a courier KosherEats arranges
              </p>
              <p className="text-xs text-dark-400">For example, Uber Direct</p>
              <p className="mt-2">
                <strong className="text-dark-200">Delivery fee:</strong> 10% of the order&apos;s food
                subtotal.
              </p>
            </div>
            <div className="p-4">
              <p className="font-semibold text-white">
                Pickup orders, and orders you deliver yourself
              </p>
              <p className="mt-2">
                <strong className="text-dark-200">Basic service fee:</strong> 5% of the order&apos;s
                food subtotal, <strong className="text-dark-200">plus</strong> a{" "}
                <strong className="text-dark-200">transaction fee</strong> equal to the actual
                card-processing cost KosherEats incurs for that order.
              </p>
            </div>
          </div>

          <p>
            The <strong className="text-dark-200">food subtotal</strong> is the purchase price of
            the food and other menu items in the order, before sales tax, tips, and any delivery
            or service fees the customer pays. For example, on a $50.00 order delivered by a
            courier we arrange, the delivery fee is $5.00. On a $50.00 pickup order, the basic
            service fee is $2.50, plus the card-processing cost for that order.
          </p>
          <p>
            <strong className="text-dark-200">No other fees.</strong> KosherEats does not charge
            you any other fees — no sign-up, listing, or monthly fees — and never charges you for
            a telephone order or call that does not result in a transaction.
          </p>
          <p>
            <strong className="text-dark-200">Customer fees are separate.</strong> Any delivery
            fee or service fee a customer pays is separate from the fees above, and is shown to the
            customer at checkout before they place the order.
          </p>
          <p>
            <strong className="text-dark-200">Fee changes.</strong> We will give you at least 30
            days&apos; written notice, by email to the address on your account, before any change
            to our fees takes effect. If you don&apos;t agree with a change, you can end this
            Agreement before it takes effect (see <SectionLink id="termination">Section 12</SectionLink>).
          </p>
        </Section>

        <Section id="payouts" title="4. Payouts">
          <p>
            We pay you through Stripe Connect, a payments service provided by Stripe, Inc.
            (&quot;Stripe&quot;).
          </p>
          <ul className="list-disc pl-5 space-y-1">
            <li>
              <strong>Setup:</strong> to receive payouts, you complete Stripe&apos;s onboarding from
              the KosherEats Restaurant app. Stripe will ask for your bank account, identity, and
              tax information. Your Stripe account is also governed by the{" "}
              <a
                href="https://stripe.com/connect-account/legal"
                className="text-brand-400 underline"
                target="_blank"
                rel="noopener noreferrer"
              >
                Stripe Connected Account Agreement
              </a>
              .
            </li>
            <li>
              <strong>Per-order transfers:</strong> after each order is completed, we transfer that
              order&apos;s net amount to your Stripe account. The net amount is the order&apos;s
              food subtotal, plus the sales tax collected on it (see{" "}
              <SectionLink id="sales-tax">Section 5</SectionLink>) and any other amounts owed to
              you for that order, minus our fees for that order (see{" "}
              <SectionLink id="fees">Section 3</SectionLink>) and any refunds or adjustments (see{" "}
              <SectionLink id="refunds">Section 6</SectionLink>). Stripe then deposits the funds to
              your bank account on its payout schedule.
            </li>
            <li>
              <strong>Tax forms:</strong> Stripe issues tax forms, such as Form 1099-K, for
              amounts paid to you, as required by law.
            </li>
            <li>
              <strong>Statements:</strong> statements showing your orders, fees, sales tax,
              refunds, and payouts are available in the KosherEats Restaurant app.
            </li>
          </ul>
        </Section>

        <Section id="sales-tax" title="5. Sales tax">
          <p>
            You are the vendor of the food you sell through KosherEats, and you are responsible
            for reporting and remitting sales tax on those sales to the appropriate tax
            authorities.
          </p>
          <p>
            KosherEats collects sales tax from customers at checkout and passes 100% of it to you
            in your payout for each order. Your statement shows the sales tax collected on each
            order, so you have what you need to report and remit it.
          </p>
        </Section>

        <Section id="refunds" title="6. Refunds and adjustments">
          <ul className="list-disc pl-5 space-y-1">
            <li>
              <strong>Restaurant issues:</strong> if a customer is refunded because of a problem
              caused by your restaurant — for example, wrong or missing items, or food quality —
              the refunded amount is deducted from your payout for that order. If that order has
              already been paid out, the amount is reversed from your Stripe account or deducted
              from a later payout.
            </li>
            <li>
              <strong>Delivery issues:</strong> problems caused by delivery on orders delivered by
              a courier KosherEats arranges — for example, a late or lost delivery, or food the
              courier mishandled — are handled by KosherEats and the courier service, and are not
              deducted from your payout.
            </li>
          </ul>
          <p>Every refund or adjustment appears on your statement in the KosherEats Restaurant app.</p>
        </Section>

        <Section id="responsibilities" title="7. Your responsibilities">
          <p>You agree to:</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>keep your menu, prices, hours, and allergen information accurate and up to date;</li>
            <li>
              handle and prepare food safely, follow all applicable food-safety laws, and hold
              every license and permit required to operate your business; and
            </li>
            <li>prepare orders promptly and accurately.</li>
          </ul>
        </Section>

        <Section id="kosher" title="8. Kosher certification">
          <p>
            Customers choose KosherEats because they rely on the kosher information they see. You
            agree to:
          </p>
          <ul className="list-disc pl-5 space-y-1">
            <li>
              accurately describe your kosher certification, including identifying your
              certifying agency and any designations you list (such as glatt kosher, cholov
              Yisroel, or pas Yisroel); and
            </li>
            <li>
              promptly update KosherEats — in the KosherEats Restaurant app or by emailing{" "}
              <a href={`mailto:${SUPPORT_EMAIL}`} className="text-brand-400 underline">
                {SUPPORT_EMAIL}
              </a>{" "}
              — if your certification changes in any way, including if it lapses, is suspended,
              or is withdrawn.
            </li>
          </ul>
        </Section>

        <Section id="delivery-workers" title="9. Delivery workers">
          <p>
            As required by New York City law, you will allow delivery workers who are picking up
            orders from your restaurant to use your restroom, except where doing so would pose a
            health or safety risk.
          </p>
        </Section>

        <Section id="no-indemnification" title="10. No indemnification required">
          <p>
            KosherEats does not and will not require you to indemnify KosherEats, or any
            independent contractor acting on KosherEats&apos; behalf, for any damages or harm
            arising from the acts or omissions of KosherEats or that contractor. Nothing in this
            Agreement or in any KosherEats policy requires you to do so. This is consistent with
            New York City law governing third-party food delivery services.
          </p>
        </Section>

        <Section id="customer-data" title="11. Customer data">
          <p>
            For each order, KosherEats gives you the customer information you need to fill it: the
            customer&apos;s first name, the last four digits of their phone number, their delivery
            address and instructions, and the contents of the order. KosherEats does not otherwise
            share customers&apos; contact information with restaurants.
          </p>
          <p>
            You will use customer data only to fill the order it came with and in ways the law
            allows, and you will protect it with reasonable security measures against
            unauthorized access, use, or disclosure.
          </p>
        </Section>

        <Section id="termination" title="12. Term and termination">
          <p>
            This Agreement starts when you accept it and continues until either of us ends it.
            Either you or KosherEats may end it at any time by giving the other notice. You can
            give notice by emailing{" "}
            <a href={`mailto:${SUPPORT_EMAIL}`} className="text-brand-400 underline">
              {SUPPORT_EMAIL}
            </a>
            ; we will give you notice by email to the address on your account.
          </p>
          <p>
            When the Agreement ends, we remove your listing and stop taking new orders for your
            restaurant. You will still be paid for every order completed before then, less the
            fees and any refunds or adjustments that apply to those orders under this Agreement.
          </p>
        </Section>

        <Section id="acceptance" title="13. Electronic acceptance">
          <p>
            You accept this Agreement by tapping or clicking <strong>Accept</strong> in the
            KosherEats Restaurant app. Doing so is your electronic signature: it is legally binding
            and has the same effect as signing a paper contract, under the federal Electronic
            Signatures in Global and National Commerce Act (E-SIGN) and New York&apos;s Electronic
            Signatures and Records Act.
          </p>
          <p>
            The person who accepts confirms that they are authorized to accept this Agreement on
            behalf of the Restaurant. We keep a record of the version you accepted and when you
            accepted it.
          </p>
        </Section>

        <Section id="governing-law" title="14. Governing law">
          <p>
            This Agreement is governed by the laws of the State of New York, including applicable
            New York City law.
          </p>
        </Section>

        <Section id="changes" title="15. Entire agreement and changes">
          <p>
            This Agreement is the entire agreement between you and KosherEats about listing your
            restaurant and selling through KosherEats, and it replaces any earlier agreements or
            understandings on that subject.
          </p>
          <p>
            We may update this Agreement from time to time. We will give you notice of any change
            by email or in the KosherEats Restaurant app before it takes effect — and at least 30
            days&apos; written notice for any fee change (see{" "}
            <SectionLink id="fees">Section 3</SectionLink>). Each version is dated and numbered at
            the top of this page, and we may ask you to accept the updated version in the app. If
            you don&apos;t agree to a change, you can end this Agreement before it takes effect;
            if you keep using KosherEats after it takes effect, the updated Agreement applies.
          </p>
        </Section>

        <Section id="contact" title="16. Contact">
          <p>
            Questions about this Agreement? Email{" "}
            <a href={`mailto:${SUPPORT_EMAIL}`} className="text-brand-400 underline">
              {SUPPORT_EMAIL}
            </a>{" "}
            or write to KosherEats LLC, 1547 E 5th St, Brooklyn, NY 11230.
          </p>
        </Section>
      </div>
      </main>
    </>
  );
}

function Section({
  id,
  title,
  children,
}: {
  id: string;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section id={id} className="scroll-mt-24">
      <h2 className="text-xl font-semibold text-white mb-3">{title}</h2>
      <div className="space-y-3 text-dark-300">{children}</div>
    </section>
  );
}

function SectionLink({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    <a href={`#${id}`} className="text-brand-400 underline">
      {children}
    </a>
  );
}
