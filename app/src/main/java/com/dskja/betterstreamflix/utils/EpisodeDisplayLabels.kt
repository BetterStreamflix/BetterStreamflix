package com.dskja.betterstreamflix.utils

import java.util.Locale

/** Leanback season-grid episode labels (badge + readable title). */
object EpisodeDisplayLabels {
    /** Zero-padded episode badge, e.g. E01 / E12 / E100. */
    fun badge(number: Int): String = String.format(Locale.US, "E%02d", number)

    /** Prefer a non-blank episode title; otherwise a localized "Episode N" fallback. */
    fun readableTitle(raw: String?, number: Int, episodeNumberFallback: (Int) -> String): String =
        raw?.trim()?.takeIf { it.isNotEmpty() } ?: episodeNumberFallback(number)
}
