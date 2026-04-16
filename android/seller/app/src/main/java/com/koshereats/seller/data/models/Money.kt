package com.koshereats.seller.data.models

import java.util.Locale

fun Int.formatPrice(): String = String.format(Locale.US, "$%.2f", this / 100.0)

fun Int.formatPriceWhole(): String = String.format(Locale.US, "$%d", this / 100)
