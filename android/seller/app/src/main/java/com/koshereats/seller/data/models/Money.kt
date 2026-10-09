package com.koshereats.seller.data.models

import java.text.NumberFormat

fun Int.formatPrice(): String = NumberFormat.getCurrencyInstance(java.util.Locale.US).format(this / 100.0)

