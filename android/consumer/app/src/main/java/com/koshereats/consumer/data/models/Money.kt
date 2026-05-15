package com.koshereats.consumer.data.models

import java.text.NumberFormat
import java.util.Locale

fun Int.formatPrice(): String = NumberFormat.getCurrencyInstance(Locale.US).format(this / 100.0)

fun Int.formatPriceWhole(): String {
    val nf = NumberFormat.getCurrencyInstance(Locale.US)
    nf.maximumFractionDigits = 0
    nf.minimumFractionDigits = 0
    return nf.format(this / 100.0)
}
