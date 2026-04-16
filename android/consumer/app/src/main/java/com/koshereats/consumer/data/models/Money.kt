package com.koshereats.consumer.data.models

fun Int.formatPrice(): String = "$%.2f".format(this / 100.0)

fun Int.formatPriceWhole(): String = "$%d".format(this / 100)
