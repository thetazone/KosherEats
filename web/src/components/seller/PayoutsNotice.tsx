import Link from "next/link";
import { Landmark } from "lucide-react";

/**
 * Payouts pointer for the web seller dashboard. Stripe Connect onboarding and
 * payout statements live only in the KosherEats Restaurant app (the onboarding
 * link needs the app's session and opens in its in-app browser), so the web
 * just sends restaurants there and links the partner agreement for fee terms.
 */
export function PayoutsNotice({ className = "" }: { className?: string }) {
  return (
    <section className={`card p-5 flex items-start gap-4 ${className}`} aria-labelledby="payouts-notice-title">
      <div className="w-10 h-10 rounded-xl bg-brand-500/15 text-brand-500 flex items-center justify-center shrink-0">
        <Landmark className="w-5 h-5" aria-hidden="true" />
      </div>
      <div className="min-w-0">
        <h2 id="payouts-notice-title" className="font-semibold">
          Payouts
        </h2>
        <p className="text-sm text-dark-400 mt-0.5">
          Set up payouts in the KosherEats Restaurant app — you&apos;ll connect your bank account
          through Stripe there, and your payout statements live there too.
        </p>
        <div className="flex flex-wrap gap-x-5 mt-1 text-sm font-medium">
          <Link
            href="/#download"
            className="inline-flex items-center min-h-11 text-brand-500 hover:text-brand-400 transition-colors"
          >
            Get the Restaurant app
          </Link>
          <Link
            href="/restaurant-terms#fees"
            className="inline-flex items-center min-h-11 text-brand-500 hover:text-brand-400 transition-colors"
          >
            Fees &amp; partner agreement
          </Link>
        </div>
      </div>
    </section>
  );
}
