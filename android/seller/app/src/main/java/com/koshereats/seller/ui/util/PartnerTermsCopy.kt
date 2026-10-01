package com.koshereats.seller.ui.util

/**
 * Fee / payout copy shared by the Payouts screen, the Restaurant Partner
 * Agreement gate, the Dashboard delivery tile and Settings, so the numbers can
 * never drift between surfaces. Must match the iOS seller app and web.
 */
object PartnerTermsCopy {
    const val FEE_EXPLAINER =
        "KosherEats keeps 10% of the food subtotal on orders delivered by our courier partners, " +
            "and 5% plus card processing on pickup and self-delivered orders. You receive 100% of the " +
            "sales tax collected on your orders — you're responsible for reporting and remitting it."

    /** Self-delivery economics, shown wherever the delivery mode is chosen. */
    const val SELF_DELIVERY_KEEP =
        "You keep the full delivery fee and tip; KosherEats keeps 5% + card processing."

    const val PAYOUTS_PROMPT = "Set up payouts to get paid automatically for orders"
}
