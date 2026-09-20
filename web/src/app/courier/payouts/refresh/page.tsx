import { Header } from "@/components/layout/Header";
import { RefreshCw } from "lucide-react";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Payout link expired - KosherEats",
  description: "Restart Stripe payout onboarding from the Driver app",
  robots: { index: false },
};

// Stripe sends couriers here when an onboarding link has expired or was
// already used. A fresh link needs the courier's session (GET
// /courier/payouts/link is authenticated), which only the Driver app holds —
// so the fix is always "start again from the app".
export default function CourierPayoutRefreshPage() {
  return (
    <>
      <Header />
      <main className="max-w-xl mx-auto px-6 py-16 text-dark-200">
        <div className="card p-8 text-center">
          <RefreshCw className="mx-auto mb-4 h-12 w-12 text-brand-400" aria-hidden />
          <h1 className="text-2xl font-bold text-white mb-2">That link has expired</h1>
          <p className="text-dark-300 text-sm leading-relaxed">
            Close this window, open the KosherEats Driver app, and tap
            <span className="text-white"> Set up payouts </span>
            again to get a fresh link. Nothing you entered was lost.
          </p>
        </div>
      </main>
    </>
  );
}
