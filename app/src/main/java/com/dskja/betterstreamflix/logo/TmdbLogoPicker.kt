package com.dskja.betterstreamflix.logo

/**
 * Pure JVM helpers for TMDb title-logo ranking, cache keys, and search gating.
 */
object TmdbLogoPicker {

    const val LOGO_MISS = ""

    data class LogoCandidate(
        val filePath: String,
        val iso639: String? = null,
        val voteCount: Int? = null,
        val voteAverage: Float? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    data class CacheEntry(
        val value: String,
        val cachedAtMs: Long,
    )

    enum class CacheStatus { ABSENT, MISS, STALE, HIT }

    data class CacheRead(
        val status: CacheStatus,
        val url: String? = null,
    )

    /**
     * Language key for ranking / display preference (full BCP-47, lowercased).
     * Ranking still compares against TMDb's 2-letter [iso639] via [primaryLanguage].
     */
    fun languageKey(language: String?): String {
        val raw = language?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return ""
        return raw.replace('_', '-')
    }

    /** Primary ISO-639-1 subtag — matches TMDb image language tags and cache keys. */
    fun primaryLanguage(language: String?): String =
        languageKey(language).take(2).takeIf { it.length == 2 }.orEmpty()

    fun cacheKey(tmdbId: Int, language: String?): String =
        "$tmdbId:${primaryLanguage(language)}"

    fun inflightKey(isTv: Boolean, tmdbId: Int, language: String?): String =
        "${if (isTv) "tv" else "movie"}:${cacheKey(tmdbId, language)}"

    fun isTrustedTmdbLogo(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return false
        // Accept primary CDN and common mirrors / https variants.
        return value.contains("image.tmdb.org", ignoreCase = true) ||
            value.contains("themoviedb.org/t/p/", ignoreCase = true)
    }

    /**
     * Upgrade when blank, untrusted, or when the stored resolve language no longer
     * matches the wanted UI / provider language.
     *
     * Null langs (compat) only upgrade blank / untrusted URLs.
     */
    fun shouldUpgradeLogo(
        current: String?,
        storedLang: String? = null,
        wantedLang: String? = null,
    ): Boolean {
        if (current.isNullOrBlank() || !isTrustedTmdbLogo(current)) return true
        // Missing stored/wanted lang: keep a trusted logo (no infinite re-resolve).
        if (storedLang.isNullOrBlank() || wantedLang.isNullOrBlank()) return false
        return primaryLanguage(storedLang) != primaryLanguage(wantedLang)
    }

    fun inferSource(url: String?): LogoSource = when {
        url.isNullOrBlank() -> LogoSource.UNKNOWN
        isTrustedTmdbLogo(url) -> LogoSource.TMDB
        else -> LogoSource.PROVIDER
    }

    /**
     * Prefer a TMDb URL when the current asset should be upgraded (blank, untrusted,
     * or language mismatch). When [currentLang] / [wantedLang] differ, prefer [tmdb].
     */
    fun preferResolvedLogo(
        current: String?,
        tmdb: String?,
        currentLang: String? = null,
        wantedLang: String? = null,
    ): String? {
        val trustedTmdb = tmdb?.takeIf { isTrustedTmdbLogo(it) }
            ?: tmdb?.takeIf { it.isNotBlank() }
        val currentOk = current?.takeIf { it.isNotBlank() }
        return when {
            trustedTmdb != null &&
                shouldUpgradeLogo(currentOk, currentLang, wantedLang) -> trustedTmdb
            currentOk != null -> currentOk
            else -> trustedTmdb
        }
    }

    fun readCache(
        entry: CacheEntry?,
        nowMs: Long = System.currentTimeMillis(),
        missTtlMs: Long = LogoConstants.DEFAULT_MISS_TTL_MS,
        hitTtlMs: Long = LogoConstants.DEFAULT_HIT_TTL_MS,
    ): CacheRead {
        if (entry == null) return CacheRead(CacheStatus.ABSENT)
        val age = nowMs - entry.cachedAtMs
        val isMiss = entry.value == LOGO_MISS || entry.value.isBlank()
        if (isMiss) {
            return if (age > missTtlMs) CacheRead(CacheStatus.STALE) else CacheRead(CacheStatus.MISS)
        }
        if (age > hitTtlMs) return CacheRead(CacheStatus.STALE)
        return CacheRead(CacheStatus.HIT, entry.value)
    }

    fun storeValue(resolvedUrl: String?): String =
        resolvedUrl?.takeIf { it.isNotBlank() } ?: LOGO_MISS

    fun storeEntry(resolvedUrl: String?, nowMs: Long = System.currentTimeMillis()): CacheEntry =
        CacheEntry(storeValue(resolvedUrl), nowMs)

    /**
     * Language → English → language-less → other.
     * Prefers wordmark-like aspect ratios and wider files; skips SVG.
     * Stable secondary sort by filePath for determinism.
     */
    fun pickBestFilePath(
        logos: List<LogoCandidate>?,
        language: String?,
        excludedFilePaths: Set<String> = emptySet(),
        isExcluded: (String) -> Boolean = { false },
    ): String? {
        val wanted = primaryLanguage(language).takeIf { it.isNotBlank() }
        fun rank(image: LogoCandidate): Int {
            val iso = image.iso639?.lowercase()
            return when {
                wanted != null && iso == wanted -> 0
                iso == "en" -> 1
                iso.isNullOrBlank() -> 2
                else -> 3
            }
        }
        fun aspect(image: LogoCandidate): Float {
            val w = image.width ?: return 0f
            val h = (image.height ?: 0).takeIf { it > 0 } ?: return 0f
            return w.toFloat() / h.toFloat()
        }
        val usable = logos
            ?.filterNot { it.filePath.endsWith(".svg", ignoreCase = true) }
            ?.filter { it.filePath.isNotBlank() }
            ?.filter { it.filePath !in excludedFilePaths }
            ?.filterNot { isExcluded(it.filePath) }
            .orEmpty()
        if (usable.isEmpty()) return null
        // Language rank first: never drop a wanted-lang (rank-0) candidate because a
        // wider English / other-lang logo exists. Width filter applies only inside the
        // best available rank bucket.
        val bestRank = usable.minOf { rank(it) }
        val bucket = usable.filter { rank(it) == bestRank }
        val hasWide = bucket.any { (it.width ?: 0) >= LogoConstants.MIN_PREFERRED_WIDTH }
        val pool = if (hasWide) {
            bucket.filter { (it.width ?: 0) >= LogoConstants.MIN_PREFERRED_WIDTH }
        } else {
            bucket
        }
        return pool
            .sortedWith(
                compareByDescending<LogoCandidate> {
                    val a = aspect(it)
                    when {
                        a >= LogoConstants.MIN_ASPECT_FOR_WORDMARK -> 2
                        a > 0f -> 1
                        else -> 0
                    }
                }
                    .thenByDescending { it.voteCount ?: 0 }
                    .thenByDescending { it.voteAverage ?: 0f }
                    .thenByDescending { it.width ?: 0 }
                    .thenByDescending { it.height ?: 0 }
                    .thenBy { it.filePath },
            )
            .firstOrNull()
            ?.filePath
    }

    fun isAcceptableSearchHit(
        voteCount: Int,
        popularity: Float,
        queryTitle: String,
        candidateTitles: List<String?>,
        normalize: (String) -> String,
        releaseYear: Int? = null,
        candidateYear: Int? = null,
    ): Boolean {
        val popularEnough =
            voteCount >= LogoConstants.MIN_SEARCH_VOTE_COUNT ||
                popularity >= LogoConstants.MIN_SEARCH_POPULARITY
        if (!popularEnough) return false
        if (releaseYear != null && candidateYear != null &&
            kotlin.math.abs(releaseYear - candidateYear) > 1
        ) {
            return false
        }
        return titlesSimilarEnough(queryTitle, candidateTitles, normalize)
    }

    fun titlesSimilarEnough(
        queryTitle: String,
        candidateTitles: List<String?>,
        normalize: (String) -> String,
    ): Boolean {
        val query = normalize(queryTitle)
        if (query.length < LogoConstants.MIN_TITLE_MATCH_LENGTH) return false
        if (query.all { !it.isLetterOrDigit() }) return false
        return candidateTitles
            .asSequence()
            .filterNotNull()
            .map(normalize)
            .filter { it.length >= LogoConstants.MIN_TITLE_MATCH_LENGTH }
            .any { candidate ->
                titlesMatch(query, candidate) || fuzzyCloseEnough(query, candidate)
            }
    }

    fun titlesMatch(left: String, right: String): Boolean {
        if (left == right) return true
        val shorter: String
        val longer: String
        if (left.length <= right.length) {
            shorter = left
            longer = right
        } else {
            shorter = right
            longer = left
        }
        if (longer.startsWith(shorter) &&
            (longer.length == shorter.length || longer[shorter.length] == ' ')
        ) {
            if (shorter.length * LogoConstants.PREFIX_LENGTH_RATIO_NUM >=
                longer.length * LogoConstants.PREFIX_LENGTH_RATIO_DEN
            ) {
                return true
            }
            // Prefix too short relative to full title — keep evaluating token overlap
            // (e.g. "demon slayer" vs "demon slayer kimetsu no yaiba").
        }
        val shortTokens = shorter.split(' ').filter { it.isNotBlank() }
        val longTokens = longer.split(' ').filter { it.isNotBlank() }
        if (shortTokens.isEmpty() || longTokens.isEmpty()) return false
        if (shortTokens.first() != longTokens.first()) {
            // Alt / localized title path: multi-token query fully contained in the longer title
            // (e.g. "kimetsu no yaiba" ⊂ "demon slayer kimetsu no yaiba").
            return shortTokens.size >= 2 && shortTokens.all { it in longTokens.toSet() }
        }
        // Sequel / reboot guard: single shared lead token is not enough when lengths diverge.
        if (shortTokens.size == 1 && longTokens.size > 2) return false
        val longSet = longTokens.toSet()
        return shortTokens.all { it in longSet }
    }

    /** Tiny Levenshtein gate for near-miss titles (optional secondary path). */
    fun fuzzyCloseEnough(a: String, b: String, maxDistance: Int = 2): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return false
        if (a.length < LogoConstants.MIN_TITLE_MATCH_LENGTH ||
            b.length < LogoConstants.MIN_TITLE_MATCH_LENGTH
        ) {
            return false
        }
        return levenshtein(a, b) <= maxDistance
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = tmp
            }
        }
        return dp[n]
    }
}
