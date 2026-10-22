package com.betterstreamflix.app.logo

/**
 * Codec for the per-entity logo-chip columns: a compact `name|url` row per
 * line so Room can persist networks / companies / watch providers without
 * another TypeConverter. Pure JVM — unit-test friendly.
 */
object LogoChips {

    const val MAX_CHIPS = 6

    data class LogoChip(val name: String, val logoUrl: String?)

    fun encode(chips: List<LogoChip>): String? {
        val rows = chips
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.lowercase() }
            .take(MAX_CHIPS)
            .map { "${it.name.replace('|', '/').trim()}|${it.logoUrl?.trim().orEmpty()}" }
        return rows.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun parse(json: String?): List<LogoChip> {
        if (json.isNullOrBlank()) return emptyList()
        return json.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val sep = line.lastIndexOf('|')
                if (sep < 0) {
                    LogoChip(line, null)
                } else {
                    LogoChip(
                        name = line.substring(0, sep),
                        logoUrl = line.substring(sep + 1).takeIf { it.isNotBlank() },
                    )
                }
            }
            .filter { it.name.isNotBlank() }
            .toList()
    }

    /** TMDb logo_path → CDN URL, null/blank-safe. */
    fun tmdbLogoUrl(logoPath: String?): String? =
        logoPath?.takeIf { it.isNotBlank() }
            ?.let { "https://image.tmdb.org/t/p/w185$it" }
}
