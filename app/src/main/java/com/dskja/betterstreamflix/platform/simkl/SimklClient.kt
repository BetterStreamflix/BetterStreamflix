package com.dskja.betterstreamflix.platform.simkl

import android.util.Log
import com.dskja.betterstreamflix.utils.NetworkClient
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simkl REST client — scrobble, watchlist, and history sync.
 * Auth: OAuth/PKCE token stored in UserPreferences.
 */
object SimklClient {
    private const val TAG = "SimklClient"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun warm() {
        if (SimklConfig.configured()) {
            Log.d(TAG, "Simkl configured")
        }
    }

    suspend fun scrobble(
        action: SimklScrobbler.Action,
        progress: Double,
        imdbId: String?,
        tmdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!SimklConfig.configured()) return@withContext false
        if (action == SimklScrobbler.Action.NONE) return@withContext false
        val path = when (action) {
            SimklScrobbler.Action.START -> "/scrobble/start"
            SimklScrobbler.Action.PAUSE -> "/scrobble/pause"
            SimklScrobbler.Action.STOP -> "/scrobble/stop"
            SimklScrobbler.Action.NONE -> return@withContext false
        }
        val body = JSONObject().put("progress", progress.coerceIn(0.0, 100.0))
        if (season != null && episode != null) {
            val show = JSONObject()
            if (!imdbId.isNullOrBlank()) show.put("ids", JSONObject().put("imdb", imdbId))
            else if (!tmdbId.isNullOrBlank()) show.put("ids", JSONObject().put("tmdb", tmdbId))
            else return@withContext false
            body.put("show", show)
            body.put("episode", JSONObject().put("season", season).put("number", episode))
        } else {
            val movie = JSONObject()
            val ids = JSONObject()
            if (!imdbId.isNullOrBlank()) ids.put("imdb", imdbId)
            if (!tmdbId.isNullOrBlank()) ids.put("tmdb", tmdbId)
            if (ids.length() == 0) return@withContext false
            movie.put("ids", ids)
            body.put("movie", movie)
        }
        post(path, body.toString())
    }

    /**
     * Adds or removes a title from the Simkl plantowatch / watching lists.
     * [isTv] picks the show payload; movies use the movie payload.
     */
    suspend fun syncWatchlist(
        add: Boolean,
        imdbId: String?,
        tmdbId: String? = null,
        isTv: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!SimklConfig.configured()) return@withContext false
        val ids = JSONObject()
        if (!imdbId.isNullOrBlank()) ids.put("imdb", imdbId)
        if (!tmdbId.isNullOrBlank()) ids.put("tmdb", tmdbId)
        if (ids.length() == 0) return@withContext false
        val item = JSONObject().put("ids", ids).put("to", if (add) "plantowatch" else "notinterested")
        val body = JSONObject()
        if (isTv) body.put("shows", JSONArray().put(item))
        else body.put("movies", JSONArray().put(item))
        post("/sync/add-to-list", body.toString())
    }

    /**
     * Marks a movie / episode as completed in Simkl history.
     */
    suspend fun markWatched(
        imdbId: String?,
        tmdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!SimklConfig.configured()) return@withContext false
        val ids = JSONObject()
        if (!imdbId.isNullOrBlank()) ids.put("imdb", imdbId)
        if (!tmdbId.isNullOrBlank()) ids.put("tmdb", tmdbId)
        if (ids.length() == 0) return@withContext false
        val body = JSONObject()
        if (season != null && episode != null) {
            body.put(
                "shows",
                JSONArray().put(
                    JSONObject()
                        .put("ids", ids)
                        .put(
                            "seasons",
                            JSONArray().put(
                                JSONObject()
                                    .put("number", season)
                                    .put(
                                        "episodes",
                                        JSONArray().put(JSONObject().put("number", episode)),
                                    ),
                            ),
                        ),
                ),
            )
        } else {
            body.put("movies", JSONArray().put(JSONObject().put("ids", ids)))
        }
        post("/sync/history", body.toString())
    }

    /**
     * Pulls recent watch history from Simkl (movies + episodes).
     * Returns a compact list of id/title pairs for UI / merge hooks.
     */
    suspend fun fetchRecentHistory(limit: Int = 40): List<SimklHistoryItem> = withContext(Dispatchers.IO) {
        if (!SimklConfig.configured()) return@withContext emptyList()
        runCatching {
            val url = "${SimklConfig.API}/sync/all-items/movies,episodes/?${SimklConfig.queryParams()}&extended=full"
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { SimklConfig.authHeaders().forEach { (k, v) -> header(k, v) } }
                .build()
            NetworkClient.default.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchRecentHistory HTTP ${response.code}")
                    return@use emptyList()
                }
                parseHistory(raw, limit)
            }
        }.getOrElse {
            Log.w(TAG, "fetchRecentHistory failed: ${it.message}")
            emptyList()
        }
    }

    private fun parseHistory(raw: String, limit: Int): List<SimklHistoryItem> {
        val root = JSONObject(raw)
        val out = ArrayList<SimklHistoryItem>(limit)
        fun takeMovies(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                if (out.size >= limit) return
                val row = array.optJSONObject(i) ?: continue
                val movie = row.optJSONObject("movie") ?: row
                val ids = movie.optJSONObject("ids") ?: JSONObject()
                out.add(
                    SimklHistoryItem(
                        title = movie.optString("title").ifBlank { movie.optString("name") },
                        imdbId = ids.optString("imdb").takeIf { it.isNotBlank() },
                        tmdbId = ids.opt("tmdb")?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                        isTv = false,
                    ),
                )
            }
        }
        fun takeShows(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                if (out.size >= limit) return
                val row = array.optJSONObject(i) ?: continue
                val show = row.optJSONObject("show") ?: row
                val ids = show.optJSONObject("ids") ?: JSONObject()
                out.add(
                    SimklHistoryItem(
                        title = show.optString("title").ifBlank { show.optString("name") },
                        imdbId = ids.optString("imdb").takeIf { it.isNotBlank() },
                        tmdbId = ids.opt("tmdb")?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                        isTv = true,
                    ),
                )
            }
        }
        takeMovies(root.optJSONArray("movies"))
        takeShows(root.optJSONArray("shows") ?: root.optJSONArray("episodes"))
        return out
    }

    private fun post(path: String, jsonBody: String): Boolean {
        return runCatching {
            val url = "${SimklConfig.API}$path?${SimklConfig.queryParams()}"
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(jsonMedia))
                .apply { SimklConfig.authHeaders().forEach { (k, v) -> header(k, v) } }
                .build()
            NetworkClient.default.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "POST $path HTTP ${response.code}")
                    false
                } else true
            }
        }.getOrElse {
            Log.w(TAG, "POST $path failed: ${it.message}")
            false
        }
    }

    fun clearTokens() {
        UserPreferences.simklAccessToken = ""
    }

    /**
     * GET /users/settings — validates client id + access token.
     * Returns account name / id when healthy.
     */
    suspend fun pingUser(): String? = withContext(Dispatchers.IO) {
        if (!SimklConfig.configured()) return@withContext null
        runCatching {
            val url = "${SimklConfig.API}/users/settings?${SimklConfig.queryParams()}"
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { SimklConfig.authHeaders().forEach { (k, v) -> header(k, v) } }
                .build()
            NetworkClient.default.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "pingUser HTTP ${response.code}")
                    return@use null
                }
                val user = JSONObject(raw).optJSONObject("user") ?: JSONObject(raw)
                user.optString("name")
                    .ifBlank { user.optString("username") }
                    .ifBlank { user.optString("id") }
                    .takeIf { it.isNotBlank() }
            }
        }.getOrElse {
            Log.w(TAG, "pingUser failed: ${it.message}")
            null
        }
    }
}

data class SimklHistoryItem(
    val title: String,
    val imdbId: String?,
    val tmdbId: String?,
    val isTv: Boolean,
)
