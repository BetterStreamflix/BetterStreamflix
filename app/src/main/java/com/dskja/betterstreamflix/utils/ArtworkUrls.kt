package com.dskja.betterstreamflix.utils

/**
 * Normalizes remote artwork URLs so hero / banner loads prefer a sharp size
 * instead of tiny thumbnails that look zoomed and soft when center-cropped.
 */
object ArtworkUrls {

    private val tmdbSize = Regex("""(image\.tmdb\.org/t/p/)(w\d+|original)(/)""")
    private val tmdbSizeAlt = Regex("""(themoviedb\.org/t/p/)(w\d+|original)(/)""")

    /** Prefer a wide TMDb size suitable for full-bleed heroes. */
    fun preferHero(url: String?): String? = replaceSize(url, "w1280")

    /** Original / max quality when the surface can show it (detail banner). */
    fun preferOriginal(url: String?): String? = replaceSize(url, "original")

    fun preferW500(url: String?): String? = replaceSize(url, "w500")

    fun preferW300(url: String?): String? = replaceSize(url, "w300")

    /** Title logos: original first, w1280 only as a Glide retry fallback. */
    fun preferLogo(url: String?): String? = preferOriginal(url) ?: preferHero(url)

    /**
     * Ordered size ladder for logo Glide retries.
     * Respects [UserPreferences.tmdbLogoQuality] when available.
     */
    fun logoSizeLadder(url: String?): List<String> {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        val originalFirst = try {
            UserPreferences.tmdbLogoQuality != "w1280_first"
        } catch (_: Throwable) {
            true
        }
        val ordered = if (originalFirst) {
            listOfNotNull(preferOriginal(value), preferHero(value), preferW500(value), preferW300(value))
        } else {
            listOfNotNull(preferHero(value), preferOriginal(value), preferW500(value), preferW300(value))
        }.distinct()
        return ordered.ifEmpty { listOf(value) }
    }

    fun bannerOrPoster(banner: String?, poster: String?, hero: Boolean = true): String? {
        val primary = if (hero) preferHero(banner) else preferOriginal(banner)
        if (!primary.isNullOrBlank()) return primary
        return if (hero) preferHero(poster) else preferOriginal(poster)
    }

    private fun replaceSize(url: String?, size: String): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        return tmdbSizeAlt.replace(
            tmdbSize.replace(value) { match ->
                "${match.groupValues[1]}$size${match.groupValues[3]}"
            },
        ) { match ->
            "${match.groupValues[1]}$size${match.groupValues[3]}"
        }
    }

    /**
     * Stable identity across TMDb size tiers (original/w1280/…) so a failed
     * original blacklist also covers the Glide ladder siblings.
     */
    fun logoFileIdentity(url: String?): String {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return ""
        val stripped = tmdbSizeAlt.replace(
            tmdbSize.replace(value) { match ->
                "${match.groupValues[1]}*${match.groupValues[3]}"
            },
        ) { match ->
            "${match.groupValues[1]}*${match.groupValues[3]}"
        }
        return stripped.lowercase()
    }
}
