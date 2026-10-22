package com.betterstreamflix.app.logo

import com.betterstreamflix.app.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Fanart.tv as the second title-logo source (clearlogo / clearart).
 *
 * Movies resolve by TMDb id; TV shows resolve by TVDb id, discovered via the
 * TMDb `external_ids` append. A personal Fanart.tv API key is required —
 * without one the seam stays a cheap no-op.
 */
object FanartLogoFetch {

    private const val BASE = "https://webservice.fanart.tv/v3"

    data class FanartLogo(
        val url: String,
        val lang: String?,
        val likes: Int,
    )

    @Volatile
    var apiKey: String? = null

    fun hasApiKey(): Boolean = !apiKey.isNullOrBlank()

    /** Injectable seams — production hits fanart.tv, tests inject fakes. */
    @Volatile
    var fetchMovieLogos: suspend (tmdbId: Int) -> List<FanartLogo>? =
        { tmdbId -> defaultFetchMovie(tmdbId) }

    @Volatile
    var fetchTvLogos: suspend (tmdbId: Int) -> List<FanartLogo>? =
        { tmdbId -> defaultFetchTv(tmdbId) }

    fun isFanartUrl(url: String?): Boolean =
        url?.contains("fanart.tv", ignoreCase = true) == true

    /**
     * Rank: wanted language → en → language-less → other, then likes desc.
     * HD logos preferred over SD within the same bucket.
     */
    fun pickUrl(logos: List<FanartLogo>?, language: String?): String? {
        val wanted = TmdbLogoPicker.primaryLanguage(language).takeIf { it.isNotBlank() }
        fun rank(logo: FanartLogo): Int {
            val iso = logo.lang?.lowercase()
            return when {
                wanted != null && iso == wanted -> 0
                iso == "en" -> 1
                iso.isNullOrBlank() -> 2
                else -> 3
            }
        }
        return logos
            ?.filter { it.url.isNotBlank() }
            ?.filterNot { it.url.substringBefore('?').endsWith(".svg", ignoreCase = true) }
            ?.sortedWith(
                compareBy<FanartLogo> { rank(it) }
                    .thenByDescending { it.likes }
                    .thenBy { it.url },
            )
            ?.firstOrNull()
            ?.url
    }

    fun resetToDefaults() {
        fetchMovieLogos = { tmdbId -> defaultFetchMovie(tmdbId) }
        fetchTvLogos = { tmdbId -> defaultFetchTv(tmdbId) }
    }

    private suspend fun defaultFetchMovie(tmdbId: Int): List<FanartLogo>? {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val json = httpGet("$BASE/movies/$tmdbId?api_key=$key") ?: return null
        return parseLogos(json, "hdmovielogo", "movielogo")
    }

    private suspend fun defaultFetchTv(tmdbId: Int): List<FanartLogo>? {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Fanart.tv TV rows key on TVDb — resolve through TMDb external_ids.
        val tvdbId = runCatching {
            com.betterstreamflix.app.utils.TMDb3.TvSeries.details(
                seriesId = tmdbId,
                appendToResponse = listOf(
                    com.betterstreamflix.app.utils.TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                ),
            ).externalIds?.tvdbId
        }.getOrNull() ?: return null
        val json = httpGet("$BASE/tv/$tvdbId?api_key=$key") ?: return null
        return parseLogos(json, "hdtvlogo", "tvlogo")
    }

    private fun parseLogos(json: JSONObject, vararg arrays: String): List<FanartLogo> {
        val out = mutableListOf<FanartLogo>()
        for (name in arrays) {
            val arr = json.optJSONArray(name) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                out += FanartLogo(
                    url = url,
                    lang = item.optString("lang").takeIf { it.isNotBlank() },
                    likes = item.optString("likes").toIntOrNull() ?: 0,
                )
            }
        }
        return out
    }

    private suspend fun httpGet(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .build()
            NetworkClient.systemDns.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()?.let { JSONObject(it) }
            }
        }.getOrNull()
    }
}
