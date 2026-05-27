package com.greeneats.consumer.data.models

import java.text.NumberFormat

/**
 * Formats a price stored in cents as a locale-aware currency string (e.g. "$12.99").
 * Negative values are coerced to 0 to prevent displaying negative prices in the UI.
 */
fun Int.formatPrice(): String =
    NumberFormat.getCurrencyInstance().format(this.coerceAtLeast(0) / 100.0)

/**
 * Formats a price stored in cents as a whole-dollar currency string (e.g. "$13").
 * Negative values are coerced to 0. Uses floating-point division to avoid
 * truncation (e.g. 150 cents -> "$2" not "$1").
 */
fun Int.formatPriceWhole(): String {
    val nf = NumberFormat.getCurrencyInstance()
    nf.maximumFractionDigits = 0
    nf.minimumFractionDigits = 0
    return nf.format(this.coerceAtLeast(0) / 100.0)
}
