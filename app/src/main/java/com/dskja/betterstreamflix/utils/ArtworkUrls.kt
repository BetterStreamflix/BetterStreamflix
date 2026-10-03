package com.dskja.betterstreamflix.utils

/**
 * Normalizes remote artwork URLs so hero / banner loads prefer a sharp size
 * instead of tiny thumbnails that look zoomed and soft when center-cropped.
 */
object ArtworkUrls {

    private val tmdbSize = Regex("""(image\.tmdb\.org/t/p/)(w\d+|original)(/)""")
    private val tmdbSizeAlt = Regex("""(themoviedb\.org/t/p/)(w\d+|original)(/)""")

    /**
     * Wide TMDb size for mid-weight hero washes (soft blur layers, cheap prefetch).
     * Full-bleed Featured / detail covers should use [preferFeatured] / [preferOriginal].
     */
    fun preferHero(url: String?): String? = replaceSize(url, "w1280")

    /** Full-bleed Featured / TV home background — never upsample a tiny bitmap. */
    fun preferFeatured(url: String?): String? = replaceSize(url, "original")

    /** Original / max quality when the surface can show it (detail banner). */
    fun preferOriginal(url: String?): String? = replaceSize(url, "original")

    /** Shelf / grid posters — sharp on xxhdpi without pulling full original. */
    fun preferPoster(url: String?): String? = replaceSize(url, "w780")

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
