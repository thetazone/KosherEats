package com.greeneats.consumer.data.models

import java.text.NumberFormat

fun Int.formatPrice(): String = NumberFormat.getCurrencyInstance().format(this / 100.0)

fun Int.formatPriceWhole(): String {
    val nf = NumberFormat.getCurrencyInstance()
    nf.maximumFractionDigits = 0
    nf.minimumFractionDigits = 0
    return nf.format(this / 100)
}
