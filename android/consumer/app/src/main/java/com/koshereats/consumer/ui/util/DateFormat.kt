package com.koshereats.consumer.ui.util

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Renders a backend RFC 3339 timestamp (UTC) in the device's time zone and
 * locale, e.g. "Oct 8, 2026, 7:42 PM". Falls back to the raw date portion
 * when the input is not parseable so the UI never shows an empty slot.
 */
fun formatOrderDate(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .withLocale(Locale.getDefault()),
            )
    } catch (_: Exception) {
        iso.take(10)
    }
}
