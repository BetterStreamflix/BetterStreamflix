package com.betterstreamflix.app.fragments.search

/**
 * Parses special Search intents ("ähnlich wie…", "similar to…") from the raw query.
 */
object SearchQueryParser {

    sealed class Intent {
        data class Normal(val query: String) : Intent()
        data class SimilarTo(val seedTitle: String, val rawQuery: String) : Intent()
    }

    private val similarPatterns = listOf(
        Regex("""(?iu)^\s*ähnlich\s+wie\s+(.+?)\s*$"""),
        Regex("""(?iu)^\s*aehnlich\s+wie\s+(.+?)\s*$"""),
        Regex("""(?iu)^\s*ähnlich\s+zu\s+(.+?)\s*$"""),
        Regex("""(?iu)^\s*similar\s+to\s+(.+?)\s*$"""),
        Regex("""(?iu)^\s*more\s+like\s+(.+?)\s*$"""),
        // German shorthand — require at least 2 chars after "wie "
        Regex("""(?iu)^\s*wie\s+(.{2,}?)\s*$"""),
    )

    fun parse(raw: String): Intent {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Intent.Normal("")
        for (pattern in similarPatterns) {
            val match = pattern.matchEntire(trimmed) ?: continue
            val seed = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (seed.length >= 2) {
                return Intent.SimilarTo(seedTitle = seed, rawQuery = trimmed)
            }
        }
        return Intent.Normal(trimmed)
    }
}
