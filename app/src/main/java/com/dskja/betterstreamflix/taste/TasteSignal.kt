package com.dskja.betterstreamflix.taste

/**
 * Local taste feedback kinds for the Search & Taste Graph.
 */
enum class TasteSignalKind {
    HIDDEN,
    NOT_INTERESTED,
    MORE_LIKE_SEED,
    ;

    fun key(): String = name.lowercase()

    companion object {
        fun fromKey(raw: String?): TasteSignalKind? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

data class TasteSignalEntry(
    val identityKey: String,
    val kind: TasteSignalKind,
    val atMs: Long,
    val title: String = "",
    val genreIds: List<String> = emptyList(),
    val tmdbId: String? = null,
    val isTv: Boolean = false,
)

/**
 * Profile-scoped taste snapshot used by [TasteRanker].
 *
 * [hardHidden] identity / TMDb keys are removed from results.
 * [softDemotes] keys (Not interested) stay visible but score down.
 */
data class TasteSnapshot(
    val genreAffinity: Map<String, Float> = emptyMap(),
    val titleBoosts: Map<String, Float> = emptyMap(),
    /** Hard-hidden identity / TMDb keys (Hide). Named [negatives] kept for test/API compat. */
    val negatives: Set<String> = emptySet(),
    val softDemotes: Map<String, Float> = emptyMap(),
    val recentQueryTokens: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
) {
    /** Alias for [negatives]. */
    val hardHidden: Set<String> get() = negatives

    fun isNegative(identityKey: String): Boolean = identityKey in negatives

    fun isHardHidden(identityKey: String?, tmdbKey: String? = null): Boolean {
        if (!identityKey.isNullOrBlank() && identityKey in hardHidden) return true
        if (!tmdbKey.isNullOrBlank() && tmdbKey in hardHidden) return true
        return false
    }

    fun softDemote(identityKey: String?, tmdbKey: String? = null): Float {
        var penalty = 0f
        if (!identityKey.isNullOrBlank()) {
            penalty += softDemotes[identityKey] ?: 0f
        }
        if (!tmdbKey.isNullOrBlank()) {
            penalty += softDemotes[tmdbKey] ?: 0f
        }
        return penalty
    }

    companion object {
        fun tmdbKey(tmdbId: String?, isTv: Boolean): String? {
            val id = tmdbId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (isTv) "tmdb:tv:$id" else "tmdb:movie:$id"
        }
    }
}
