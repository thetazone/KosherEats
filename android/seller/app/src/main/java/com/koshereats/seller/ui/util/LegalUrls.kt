package com.koshereats.seller.ui.util

/** Public KosherEats endpoints the seller app links to. koshereats.shop is the only domain we own. */
object LegalUrls {
    const val PARTNERS_EMAIL = "partners@koshereats.shop"
    const val PRIVACY = "https://koshereats.shop/privacy"
    const val TERMS = "https://koshereats.shop/terms"
    /** Fallback when GET /seller/agreement carries no terms_url. */
    const val PARTNER_TERMS = "https://koshereats.shop/restaurant-terms"
}
