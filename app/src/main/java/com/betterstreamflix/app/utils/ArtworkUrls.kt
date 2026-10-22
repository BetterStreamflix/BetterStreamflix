package com.betterstreamflix.app.utils

/**
 * Normalizes remote artwork URLs so hero / banner loads prefer a sharp size
 * instead of tiny thumbnails that look zoomed and soft when center-cropped.
 *
 * When [UserPreferences.dataSaverMode] is on, TMDb size tokens step down so
 * Glide pulls smaller bitmaps (no Coil path in this app).
 */
object ArtworkUrls {

    // Size token may be w300, original, h632, or a compound crop such as
    // w185_and_h278_bestv2. Anything left in the path is upscaled and soft.
    private val tmdbSizeToken = Regex(
        """(image\.tmdb\.org/t/p/|themoviedb\.org/t/p/)""" +
            """((?:w\d+|h\d+|original)[^/]*)""" +
            """(/)""",
    )

    private fun dataSaver(): Boolean = UserPreferences.dataSaverMode

    /**
     * Wide TMDb size for mid-weight hero washes (soft blur layers, cheap prefetch).
     * Full-bleed Featured / detail covers should use [preferFeatured] / [preferOriginal].
     */
    fun preferHero(url: String?): String? =
        replaceSize(url, if (dataSaver()) "w780" else "w1280")

    /** Full-bleed Featured / TV home background — never upsample a tiny bitmap. */
    fun preferFeatured(url: String?): String? =
        replaceSize(url, if (dataSaver()) "w780" else "original")

    /** Original / max quality when the surface can show it (detail banner). */
    fun preferOriginal(url: String?): String? =
        replaceSize(url, if (dataSaver()) "w1280" else "original")

    /** Shelf / grid posters — sharp on xxhdpi without pulling full original. */
    fun preferPoster(url: String?): String? =
        replaceSize(url, if (dataSaver()) "w342" else "w780")

    fun preferW500(url: String?): String? =
        replaceSize(url, if (dataSaver()) "w185" else "w500")

    fun preferW300(url: String?): String? =
        replaceSize(url, if (dataSaver()) "w185" else "w300")

    /** Title logos: original first, w1280 only as a Glide retry fallback. */
    fun preferLogo(url: String?): String? = preferOriginal(url) ?: preferHero(url)

    /**
     * Ordered size ladder for logo Glide retries.
     * Always original first. The old `w1280_first` preference has no Settings
     * control anymore and was leaving wordmarks on the softer tier.
     */
    fun logoSizeLadder(url: String?): List<String> {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        val ordered = listOfNotNull(
            preferOriginal(value),
            preferHero(value),
            preferW500(value),
            preferW300(value),
        ).distinct()
        return ordered.ifEmpty { listOf(value) }
    }

    /**
     * @param hero when true, uses w1280 (soft / secondary washes).
     *   Featured and detail covers should pass `hero = false` for original.
     */
    fun bannerOrPoster(banner: String?, poster: String?, hero: Boolean = true): String? {
        val primary = if (hero) preferHero(banner) else preferOriginal(banner)
        if (!primary.isNullOrBlank()) return primary
        return if (hero) preferHero(poster) else preferOriginal(poster)
    }

    /** Featured swiper / TV home backdrop — always original when TMDb. */
    fun featuredBannerOrPoster(banner: String?, poster: String?): String? {
        val primary = preferFeatured(banner)
        if (!primary.isNullOrBlank()) return primary
        return preferFeatured(poster)
    }

    private fun replaceSize(url: String?, size: String): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        return tmdbSizeToken.replace(value) { match ->
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
        val stripped = tmdbSizeToken.replace(value) { match ->
            "${match.groupValues[1]}*${match.groupValues[3]}"
        }
        return stripped.lowercase()
    }
}
