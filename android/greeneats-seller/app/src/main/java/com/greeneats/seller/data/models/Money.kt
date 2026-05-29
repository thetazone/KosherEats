package com.greeneats.seller.data.models

import java.text.NumberFormat

/**
 * Formats cents as a currency string (e.g. 1299 -> "$12.99").
 * Negative values are formatted normally (e.g. -500 -> "-$5.00")
 * which lets discounts display correctly.
 */
fun Int.formatPrice(): String = NumberFormat.getCurrencyInstance(java.util.Locale.US).format(this / 100.0)

/**
 * Formats cents as a whole-dollar currency string (e.g. 1299 -> "$13").
 * Uses floating-point division so 1999 rounds to "$20" instead of
 * truncating to "$19".
 */
fun Int.formatPriceWhole(): String {
    val nf = NumberFormat.getCurrencyInstance(java.util.Locale.US)
    nf.maximumFractionDigits = 0
    nf.minimumFractionDigits = 0
    return nf.format(this / 100.0)
}
