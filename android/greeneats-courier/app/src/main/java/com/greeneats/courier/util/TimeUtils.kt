package com.greeneats.courier.util

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun shortTime(iso: String): String = try {
    val local = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("h:mm a").format(local)
} catch (_: Throwable) { iso }

fun shortDateTime(iso: String): String = try {
    val local = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("MMM d · h:mm a").format(local)
} catch (_: Throwable) { iso }
