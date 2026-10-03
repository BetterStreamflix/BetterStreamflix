package com.dskja.betterstreamflix.models

/**
 * Pure trailer ranking / thumbnail helpers (unit-testable, no Android deps).
 */
object TrailerCatalog {
    const val THUMB_QUALITY_DEFAULT = "hq"
    const val THUMB_QUALITY_HIGH = "max"
    const val THUMB_QUALITY_STANDARD = "sd"

    const val KEY_TRAILER_THUMB_QUALITY = "trailer_thumb_quality"
    const val KEY_TRAILER_START_MUTED = "trailer_start_muted"

    /** Max trailers shown in detail lists (mobile tab + TV row). */
    const val MAX_LIST_ITEMS = 8

    fun isOfficialTitle(title: String): Boolean {
        if (title.isBlank()) return false
        val t = title.lowercase()
        return t.contains("official") ||
            t.contains("offiziell") ||
            t.contains("officiel") ||
            t.contains("ufficiale") ||
            t.contains("oficial")
    }

    /**
     * Type rank: Trailer → Teaser → Clip → other.
     * Accepts TMDb type strings (case-insensitive).
     */
    fun typeRank(type: String?): Int = when (type?.trim()?.lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        "clip" -> 2
        else -> 3
    }

    /**
     * Prefer YouTube pool; fall back to Vimeo only when no YouTube exists.
     * Within a pool: official first, then type rank, then newer publishedAt.
     */
    fun <T> rankVideos(
        videos: List<T>,
        siteOf: (T) -> String?,
        keyOf: (T) -> String?,
        officialOf: (T) -> Boolean,
        typeOf: (T) -> String?,
        publishedAtOf: (T) -> String?,
    ): List<T> {
        val withKey = videos.filter { !keyOf(it).isNullOrBlank() && !siteOf(it).isNullOrBlank() }
        val youtube = withKey.filter { siteOf(it).equals("YouTube", ignoreCase = true) }
        val pool = if (youtube.isNotEmpty()) {
            youtube
        } else {
            withKey.filter { siteOf(it).equals("Vimeo", ignoreCase = true) }
        }
        return pool.sortedWith(
            compareBy<T> { if (officialOf(it)) 0 else 1 }
                .thenBy { typeRank(typeOf(it)) }
                .thenByDescending { publishedAtOf(it).orEmpty() },
        )
    }

    fun mergeTrailers(
        seed: List<TrailerEntry>,
        remote: List<TrailerEntry>,
        limit: Int = MAX_LIST_ITEMS,
    ): List<TrailerEntry> {
        // Prefer remote official/type metadata when the same URL appears in both.
        val byUrl = LinkedHashMap<String, TrailerEntry>()
        for (entry in seed + remote) {
            val key = entry.url.trim()
            if (key.isEmpty()) continue
            val existing = byUrl[key]
            if (existing == null) {
                byUrl[key] = entry
            } else {
                byUrl[key] = existing.copy(
                    official = existing.official || entry.official,
                    type = if (typeRank(entry.type) < typeRank(existing.type)) entry.type else existing.type,
                    title = when {
                        entry.official && !existing.official -> entry.title
                        existing.title.isNotBlank() -> existing.title
                        else -> entry.title
                    },
                    site = entry.site.takeIf { it.isNotBlank() } ?: existing.site,
                )
            }
        }
        return byUrl.values
            .sortedWith(
                compareBy<TrailerEntry> { if (it.official) 0 else 1 }
                    .thenBy { typeRank(it.type) }
                    .thenBy { if (it.isYoutube) 0 else 1 },
            )
            .take(limit)
    }

    fun youtubeThumbUrl(videoId: String, quality: String? = THUMB_QUALITY_DEFAULT): String {
        val file = when (quality) {
            THUMB_QUALITY_HIGH -> "maxresdefault.jpg"
            THUMB_QUALITY_STANDARD -> "sddefault.jpg"
            else -> "hqdefault.jpg"
        }
        return "https://img.youtube.com/vi/$videoId/$file"
    }

    fun preferredPlayableUrl(entries: List<TrailerEntry>): String? =
        entries.firstOrNull { it.isYoutube }?.url
            ?: entries.firstOrNull()?.url
}
