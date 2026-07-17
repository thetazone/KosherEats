import { Header } from "@/components/layout/Header";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy Policy - KosherEats",
  description: "KosherEats privacy policy",
};

export default function PrivacyPolicyPage() {
  return (
    <>
      <Header />
      <main className="max-w-3xl mx-auto px-6 py-16 text-dark-200">
      <h1 className="text-4xl font-bold text-white mb-2">Privacy Policy</h1>
      <p className="text-dark-400 mb-10">Last updated: April 5, 2026</p>

      <div className="space-y-8 text-sm leading-relaxed">
        <Section title="1. Who We Are">
          <p>
            KosherEats (&quot;we,&quot; &quot;us,&quot; or &quot;our&quot;) operates the KosherEats
            mobile applications (Consumer, Seller, and Courier) and the website
            at koshereats.shop (collectively, the &quot;Service&quot;). This Privacy
            Policy explains how we collect, use, share, and protect your
            personal information when you use our Service.
          </p>
        </Section>

        <Section title="2. Information We Collect">
          <H3>Information you provide</H3>
          <ul className="list-disc pl-5 space-y-1">
            <li><strong>Account information:</strong> name, email address, phone number, and password when you create an account.</li>
            <li><strong>Delivery addresses:</strong> street address, city, state, ZIP code, and geographic coordinates for order delivery.</li>
            <li><strong>Payment information:</strong> payment card details are collected and processed directly by our payment processor, Stripe. We do not store full card numbers on our servers.</li>
            <li><strong>Order history:</strong> items ordered, restaurants, delivery details, tips, and order timestamps.</li>
            <li><strong>Chat messages:</strong> messages you send within the order chat feature to communicate with your courier or restaurant.</li>
            <li><strong>Courier onboarding documents:</strong> driver&apos;s license, insurance, vehicle registration, and a selfie photo, collected during the courier application process.</li>
          </ul>

          <H3>Information collected automatically</H3>
          <ul className="list-disc pl-5 space-y-1">
            <li><strong>Device information:</strong> device type, operating system version, and push notification tokens for delivering order updates.</li>
            <li><strong>Location data:</strong> precise GPS location when you grant permission, used to show nearby restaurants (consumer app) and track delivery progress (courier app). The courier app collects location in the background during active deliveries only.</li>
            <li><strong>Usage data:</strong> pages viewed, features used, and interaction timestamps for improving the Service.</li>
          </ul>
        </Section>

        <Section title="3. How We Use Your Information">
          <ul className="list-disc pl-5 space-y-1">
            <li>Process and deliver your food orders.</li>
            <li>Facilitate communication between consumers, restaurants, and couriers via order chat.</li>
            <li>Send push notifications about order status updates (e.g., &quot;Your order is on the way&quot;).</li>
            <li>Process payments and courier payouts through Stripe and Stripe Connect.</li>
            <li>Conduct background checks on courier applicants through our third-party provider (Checkr).</li>
            <li>Verify courier identity and vehicle information during onboarding.</li>
            <li>Improve, personalize, and optimize the Service.</li>
            <li>Respond to customer support requests.</li>
            <li>Comply with legal obligations.</li>
          </ul>
        </Section>

        <Section title="4. How We Share Your Information">
          <p>We do not sell your personal information. We share data only in these limited circumstances:</p>
          <ul className="list-disc pl-5 space-y-1">
            <li><strong>Restaurants:</strong> receive your name, delivery address, and order details to prepare your food.</li>
            <li><strong>Couriers:</strong> receive the restaurant address, your delivery address, and your first name to complete the delivery.</li>
            <li><strong>Payment processors:</strong> Stripe processes payments and courier payouts. Their use of your data is governed by <a href="https://stripe.com/privacy" className="text-brand-400 underline" target="_blank" rel="noopener noreferrer">Stripe&apos;s Privacy Policy</a>.</li>
            <li><strong>Background check provider:</strong> Checkr processes courier background checks. Their use is governed by <a href="https://checkr.com/privacy-policy" className="text-brand-400 underline" target="_blank" rel="noopener noreferrer">Checkr&apos;s Privacy Policy</a>.</li>
            <li><strong>Cloud infrastructure:</strong> we use Amazon Web Services (S3) for document and image storage, and Fly.io for application hosting.</li>
            <li><strong>Legal requirements:</strong> we may disclose information if required by law, regulation, or legal process.</li>
          </ul>
        </Section>

        <Section title="5. Data Retention">
          <p>
            We retain your account information for as long as your account is active.
            Order history is retained for record-keeping and dispute resolution.
            Courier onboarding documents are retained for the duration of the courier&apos;s
            active status plus any legally required retention period. You may request
            deletion of your account and associated data by contacting us.
          </p>
        </Section>

        <Section title="6. Data Security">
          <p>
            We implement industry-standard security measures including encrypted
            data transmission (TLS/HTTPS), secure password hashing (bcrypt),
            token-based authentication (JWT), and rate limiting on authentication
            endpoints. Payment card data is handled entirely by Stripe and never
            touches our servers.
          </p>
        </Section>

        <Section title="7. Your Rights and Choices">
          <ul className="list-disc pl-5 space-y-1">
            <li><strong>Access and correction:</strong> you can view and update your profile information in the app at any time.</li>
            <li><strong>Deletion:</strong> you may request deletion of your account by contacting us at privacy@koshereats.shop.</li>
            <li><strong>Location:</strong> you can revoke location permissions in your device settings. This may limit the ability to show nearby restaurants or track deliveries.</li>
            <li><strong>Push notifications:</strong> you can disable push notifications in your device settings.</li>
            <li><strong>Marketing:</strong> we do not send marketing emails. All communications are transactional (order updates, account security).</li>
          </ul>
        </Section>

        <Section title="8. Children's Privacy">
          <p>
            The Service is not directed to children under 13. We do not knowingly
            collect personal information from children under 13. If we learn that
            we have collected information from a child under 13, we will delete it
            promptly.
          </p>
        </Section>

        <Section title="9. Third-Party Links">
          <p>
            The Service may contain links to third-party websites or services
            (e.g., restaurant websites, Stripe payment pages). We are not
            responsible for the privacy practices of these third parties.
          </p>
        </Section>

        <Section title="10. Changes to This Policy">
          <p>
            We may update this Privacy Policy from time to time. We will notify
            you of material changes by posting the updated policy on this page
            with a revised &quot;Last updated&quot; date. Your continued use of the
            Service after changes constitutes acceptance.
          </p>
        </Section>

        <Section title="11. Contact Us">
          <p>
            If you have questions about this Privacy Policy or wish to exercise
            your rights, contact us at:
          </p>
          <p className="mt-2">
            <strong>Email:</strong>{" "}
            <a href="mailto:privacy@koshereats.shop" className="text-brand-400 underline">
              privacy@koshereats.shop
            </a>
          </p>
        </Section>
      </div>
      </main>
    </>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="text-xl font-semibold text-white mb-3">{title}</h2>
      <div className="space-y-3 text-dark-300">{children}</div>
    </section>
  );
}

function H3({ children }: { children: React.ReactNode }) {
  return <h3 className="font-semibold text-dark-200 mt-4 mb-1">{children}</h3>;
}
