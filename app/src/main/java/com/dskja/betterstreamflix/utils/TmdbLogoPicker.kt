package com.dskja.betterstreamflix.utils

/**
 * Compatibility facade — logo policy lives in [com.dskja.betterstreamflix.logo.TmdbLogoPicker].
 * Call sites and unit tests may keep importing this type.
 */
object TmdbLogoPicker {

    const val LOGO_MISS = com.dskja.betterstreamflix.logo.TmdbLogoPicker.LOGO_MISS
    const val MIN_SEARCH_VOTE_COUNT =
        com.dskja.betterstreamflix.logo.LogoConstants.MIN_SEARCH_VOTE_COUNT
    const val MIN_SEARCH_POPULARITY =
        com.dskja.betterstreamflix.logo.LogoConstants.MIN_SEARCH_POPULARITY
    const val MIN_TITLE_MATCH_LENGTH =
        com.dskja.betterstreamflix.logo.LogoConstants.MIN_TITLE_MATCH_LENGTH
    const val MISS_TTL_MS =
        com.dskja.betterstreamflix.logo.LogoConstants.DEFAULT_MISS_TTL_MS
    const val HIT_TTL_MS =
        com.dskja.betterstreamflix.logo.LogoConstants.DEFAULT_HIT_TTL_MS
    const val MIN_PREFERRED_WIDTH =
        com.dskja.betterstreamflix.logo.LogoConstants.MIN_PREFERRED_WIDTH

    data class LogoCandidate(
        val filePath: String,
        val iso639: String? = null,
        val voteCount: Int? = null,
        val voteAverage: Float? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) {
        fun toLogo(): com.dskja.betterstreamflix.logo.TmdbLogoPicker.LogoCandidate =
            com.dskja.betterstreamflix.logo.TmdbLogoPicker.LogoCandidate(
                filePath = filePath,
                iso639 = iso639,
                voteCount = voteCount,
                voteAverage = voteAverage,
                width = width,
                height = height,
            )
    }

    data class CacheEntry(
        val value: String,
        val cachedAtMs: Long,
    ) {
        fun toLogo(): com.dskja.betterstreamflix.logo.TmdbLogoPicker.CacheEntry =
            com.dskja.betterstreamflix.logo.TmdbLogoPicker.CacheEntry(value, cachedAtMs)

        companion object {
            fun from(entry: com.dskja.betterstreamflix.logo.TmdbLogoPicker.CacheEntry) =
                CacheEntry(entry.value, entry.cachedAtMs)
        }
    }

    enum class CacheStatus { ABSENT, MISS, STALE, HIT }

    data class CacheRead(
        val status: CacheStatus,
        val url: String? = null,
    )

    fun languageKey(language: String?): String =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.languageKey(language)

    fun primaryLanguage(language: String?): String =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.primaryLanguage(language)

    fun cacheKey(tmdbId: Int, language: String?): String =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.cacheKey(tmdbId, language)

    fun inflightKey(isTv: Boolean, tmdbId: Int, language: String?): String =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.inflightKey(isTv, tmdbId, language)

    fun isTrustedTmdbLogo(url: String?): Boolean =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.isTrustedTmdbLogo(url)

    fun shouldUpgradeLogo(current: String?): Boolean =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.shouldUpgradeLogo(current)

    fun shouldUpgradeLogo(
        current: String?,
        storedLang: String?,
        wantedLang: String?,
    ): Boolean =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.shouldUpgradeLogo(
            current,
            storedLang,
            wantedLang,
        )

    fun preferResolvedLogo(current: String?, tmdb: String?): String? =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.preferResolvedLogo(current, tmdb)

    fun preferResolvedLogo(
        current: String?,
        tmdb: String?,
        currentLang: String?,
        wantedLang: String?,
    ): String? =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.preferResolvedLogo(
            current,
            tmdb,
            currentLang,
            wantedLang,
        )

    fun readCache(
        entry: CacheEntry?,
        nowMs: Long = System.currentTimeMillis(),
        missTtlMs: Long = MISS_TTL_MS,
        hitTtlMs: Long = HIT_TTL_MS,
    ): CacheRead {
        val read = com.dskja.betterstreamflix.logo.TmdbLogoPicker.readCache(
            entry?.toLogo(),
            nowMs,
            missTtlMs,
            hitTtlMs,
        )
        return CacheRead(
            status = when (read.status) {
                com.dskja.betterstreamflix.logo.TmdbLogoPicker.CacheStatus.ABSENT -> CacheStatus.ABSENT
                com.dskja.betterstreamflix.logo.TmdbLogoPicker.CacheStatus.MISS -> CacheStatus.MISS
                com.dskja.betterstreamflix.logo.TmdbLogoPicker.CacheStatus.STALE -> CacheStatus.STALE
                com.dskja.betterstreamflix.logo.TmdbLogoPicker.CacheStatus.HIT -> CacheStatus.HIT
            },
            url = read.url,
        )
    }

    fun storeValue(resolvedUrl: String?): String =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.storeValue(resolvedUrl)

    fun storeEntry(resolvedUrl: String?, nowMs: Long = System.currentTimeMillis()): CacheEntry =
        CacheEntry.from(
            com.dskja.betterstreamflix.logo.TmdbLogoPicker.storeEntry(resolvedUrl, nowMs),
        )

    fun valueFromCache(cached: String?): String? =
        cached?.takeIf { it.isNotBlank() && it != LOGO_MISS }

    fun pickBestFilePath(logos: List<LogoCandidate>?, language: String?): String? =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.pickBestFilePath(
            logos?.map { it.toLogo() },
            language,
        )

    fun isAcceptableSearchHit(
        voteCount: Int,
        popularity: Float,
        queryTitle: String,
        candidateTitles: List<String?>,
        normalize: (String) -> String,
        releaseYear: Int? = null,
        candidateYear: Int? = null,
    ): Boolean =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.isAcceptableSearchHit(
            voteCount = voteCount,
            popularity = popularity,
            queryTitle = queryTitle,
            candidateTitles = candidateTitles,
            normalize = normalize,
            releaseYear = releaseYear,
            candidateYear = candidateYear,
        )

    fun titlesSimilarEnough(
        queryTitle: String,
        candidateTitles: List<String?>,
        normalize: (String) -> String,
    ): Boolean =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.titlesSimilarEnough(
            queryTitle,
            candidateTitles,
            normalize,
        )

    fun titlesMatch(left: String, right: String): Boolean =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.titlesMatch(left, right)

    fun fuzzyCloseEnough(a: String, b: String, maxDistance: Int = 2): Boolean =
        com.dskja.betterstreamflix.logo.TmdbLogoPicker.fuzzyCloseEnough(a, b, maxDistance)
}
