package com.dskja.betterstreamflix.models

/**
 * Ranked trailer candidate from TMDb (or a provider seed).
 * [site] is typically "YouTube" or "Vimeo".
 */
data class TrailerEntry(
    val title: String,
    val url: String,
    val type: String = "Trailer",
    val official: Boolean = false,
    val site: String = "YouTube",
) {
    val isYoutube: Boolean
        get() = site.equals("YouTube", ignoreCase = true) ||
            url.contains("youtu", ignoreCase = true)

    val isVimeo: Boolean
        get() = site.equals("Vimeo", ignoreCase = true) ||
            url.contains("vimeo.com", ignoreCase = true)

    fun toTriple(): Triple<String, String, String> = Triple(title, url, type)

    companion object {
        fun fromSeed(title: String, url: String, type: String = "Trailer"): TrailerEntry {
            val site = when {
                url.contains("vimeo.com", ignoreCase = true) -> "Vimeo"
                else -> "YouTube"
            }
            val official = TrailerCatalog.isOfficialTitle(title)
            return TrailerEntry(
                title = title,
                url = url,
                type = type,
                official = official,
                site = site,
            )
        }
    }
}
