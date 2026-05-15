package com.koshereats.seller.data.models

import java.text.NumberFormat

fun Int.formatPrice(): String = NumberFormat.getCurrencyInstance(java.util.Locale.US).format(this / 100.0)

fun Int.formatPriceWhole(): String {
    val nf = NumberFormat.getCurrencyInstance(java.util.Locale.US)
    nf.maximumFractionDigits = 0
    nf.minimumFractionDigits = 0
    return nf.format(this / 100.0)
}
