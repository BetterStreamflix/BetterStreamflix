package com.dskja.betterstreamflix.utils

/**
 * Normalizes remote artwork URLs so hero / banner loads prefer a sharp size
 * instead of tiny thumbnails that look zoomed and soft when center-cropped.
 */
object ArtworkUrls {

    private val tmdbSize = Regex("""(image\.tmdb\.org/t/p/)(w\d+|original)(/)""")

    /** Prefer a wide TMDb size suitable for full-bleed heroes. */
    fun preferHero(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        return tmdbSize.replace(value) { match ->
            "${match.groupValues[1]}w1280${match.groupValues[3]}"
        }
    }

    /** Original / max quality when the surface can show it (detail banner). */
    fun preferOriginal(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        return tmdbSize.replace(value) { match ->
            "${match.groupValues[1]}original${match.groupValues[3]}"
        }
    }

    fun bannerOrPoster(banner: String?, poster: String?, hero: Boolean = true): String? {
        val primary = if (hero) preferHero(banner) else preferOriginal(banner)
        if (!primary.isNullOrBlank()) return primary
        return if (hero) preferHero(poster) else preferOriginal(poster)
    }
}
