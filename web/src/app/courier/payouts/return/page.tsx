import { Header } from "@/components/layout/Header";
import { CheckCircle2 } from "lucide-react";
import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Payouts set up - KosherEats",
  description: "Stripe payout onboarding complete",
  robots: { index: false },
};

// Stripe Connect Express redirects couriers here after hosted onboarding
// (backend courier_payouts.go builds the URL from WEB_URL). The Driver app
// opens onboarding in an in-app browser and re-checks GET /courier/payouts/status
// when the sheet closes, so this page only needs to tell the courier to go back.
export default function CourierPayoutReturnPage() {
  return (
    <>
      <Header />
      <main className="max-w-xl mx-auto px-6 py-16 text-dark-200">
        <div className="card p-8 text-center">
          <CheckCircle2 className="mx-auto mb-4 h-12 w-12 text-success-400" aria-hidden />
          <h1 className="text-2xl font-bold text-white mb-2">Payout details saved</h1>
          <p className="text-dark-300 text-sm leading-relaxed">
            You can close this window and return to the KosherEats Driver app.
            Your payout status updates automatically; if Stripe still needs
            anything from you, the app will show it under Earnings.
          </p>
        </div>
      </main>
    </>
  );
}
