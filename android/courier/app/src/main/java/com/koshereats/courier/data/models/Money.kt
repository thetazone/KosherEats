package com.koshereats.courier.data.models

import java.text.NumberFormat

fun Int.formatPrice(): String = NumberFormat.getCurrencyInstance().format(this / 100.0)
