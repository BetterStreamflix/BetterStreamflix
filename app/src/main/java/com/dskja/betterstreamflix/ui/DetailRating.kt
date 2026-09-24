package com.dskja.betterstreamflix.ui

import java.util.Locale

/**
 * Normalizes provider/TMDb rating values for detail UI.
 *
 * Providers sometimes scrape vote *counts* or popularity (e.g. "141 Bewertungen")
 * into [com.dskja.betterstreamflix.models.TvShow.rating]. Star scores belong on
 * a 0–10 scale (or 0–100 as a percentage).
 */
object DetailRating {

    /**
     * @return a display string like `"8.7"`, or null when [raw] is missing / not a score.
     */
    fun format(raw: Double?): String? {
        val score = normalize(raw) ?: return null
        return String.format(Locale.US, "%.1f", score)
    }

    /**
     * @return score on a 0–10 scale, or null when [raw] is not a usable rating.
     */
    fun normalize(raw: Double?): Double? {
        if (raw == null || raw.isNaN() || raw.isInfinite() || raw <= 0.0) return null
        return when {
            raw <= 10.0 -> raw
            // Percentage-style scores (e.g. 87 → 8.7).
            raw <= 100.0 -> raw / 10.0
            // Vote counts / popularity — not a rating.
            else -> null
        }
    }
}
