package com.koshereats.seller.data.util

import kotlin.math.roundToInt

/**
 * Locale-tolerant money parsing for user-entered dollar strings.
 *
 * The platform [String.toDouble]/[String.toDoubleOrNull] only accept a '.' decimal
 * separator, so a comma decimal ("12,50") parses to 0/null on those call sites and a
 * price silently becomes $0.00. [parseCents] normalizes ',' -> '.' first, making the
 * comma-decimal money bug structurally impossible at every parse site that uses it.
 */
object Money {

    /**
     * Parse a user-entered dollar string to integer cents.
     *
     * Normalizes a comma decimal (',' -> '.'), strips any char outside [0-9.], and
     * rejects input with more than one decimal point. Returns null on empty/invalid
     * input. Behavior-preserving for dot-decimal input.
     *
     * Examples: "12,50" -> 1250, "12.50" -> 1250, "5" -> 500, "" -> null, "1.2.3" -> null.
     */
    fun parseCents(dollars: String): Int? {
        val normalized = dollars.replace(',', '.')
        val cleaned = normalized.filter { it.isDigit() || it == '.' }
        if (cleaned.isEmpty()) return null
        if (cleaned.count { it == '.' } > 1) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        return (value * 100).roundToInt()
    }

    /** Cents from an already-parsed dollar Double (no string normalization needed). */
    fun dollarsToCents(dollars: Double): Int = (dollars * 100).roundToInt()
}
