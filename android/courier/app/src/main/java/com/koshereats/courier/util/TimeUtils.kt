package com.koshereats.courier.util

import java.time.LocalDate
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

fun isoLocalDate(iso: String): LocalDate? = try {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
} catch (_: Throwable) { null }
