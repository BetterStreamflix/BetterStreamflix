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
 */
data class TasteSnapshot(
    val genreAffinity: Map<String, Float> = emptyMap(),
    val titleBoosts: Map<String, Float> = emptyMap(),
    val negatives: Set<String> = emptySet(),
    val recentQueryTokens: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
) {
    fun isNegative(identityKey: String): Boolean = identityKey in negatives
}
