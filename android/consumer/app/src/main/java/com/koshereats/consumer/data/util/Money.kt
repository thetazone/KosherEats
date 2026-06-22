package com.koshereats.consumer.data.util

import kotlin.math.roundToInt

/**
 * Parsing helpers that make the comma-decimal money bug structurally impossible.
 *
 * The canonical failure this guards against: a user types "12,50" (comma decimal,
 * common in many locales). A naive `toDoubleOrNull()` returns null for "12,50",
 * so the amount silently becomes 0 — or, with grouping-aware parsing, "12,50"
 * becomes 1250.0 and then 125000 cents. Both are wrong. Route all user-entered
 * dollar text through [parseCents] so "12,50" deterministically becomes 1250¢.
 */
object Money {

    /**
     * Parse user-entered dollar text into integer cents.
     *
     * Normalization:
     *  - ',' is treated as a decimal separator and rewritten to '.'.
     *  - Any character outside [0-9.] is stripped (currency symbols, spaces, etc.).
     *  - More than one '.' is rejected (ambiguous / malformed) -> null.
     *
     * @return cents as Int, or null when the input is empty or invalid.
     *
     * Examples:
     *  - "12,50" -> 1250
     *  - "12.50" -> 1250
     *  - "$3"    -> 300
     *  - ""      -> null
     *  - "1.2.3" -> null
     */
    fun parseCents(dollars: String): Int? {
        // Comma is a decimal separator here; rewrite then strip everything that
        // isn't a digit or a dot.
        val cleaned = dollars
            .replace(',', '.')
            .filter { it.isDigit() || it == '.' }

        if (cleaned.isEmpty()) return null
        if (cleaned.count { it == '.' } > 1) return null

        val value = cleaned.toDoubleOrNull() ?: return null
        return (value * 100).roundToInt()
    }

    /**
     * Convert an already-parsed dollar [Double] to integer cents.
     * Use this only when the value is genuinely a Double (computed, not
     * user-typed text); for user-entered text use [parseCents].
     */
    fun dollarsToCents(dollars: Double): Int = (dollars * 100).roundToInt()
}
