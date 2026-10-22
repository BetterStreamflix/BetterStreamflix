package com.betterstreamflix.app.taste

import android.content.Context
import com.betterstreamflix.app.profiles.ProfileManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profile-scoped SharedPreferences persistence for taste signals + genre affinity.
 */
object TasteGraphStore {

    const val PREFS = "taste_graph_v1"
    private const val KEY_SIGNALS = "signals"
    private const val KEY_AFFINITY = "genre_affinity"
    private const val KEY_QUERY_TOKENS = "query_tokens"
    private const val KEY_WATCH_ENGAGEMENT = "watch_engagement"
    private const val MAX_SIGNALS = 200
    private const val MAX_TOKENS = 24

    fun loadSignals(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
    ): List<TasteSignalEntry> {
        val raw = prefs(context).getString(storageKey(KEY_SIGNALS, profileId), "[]").orEmpty()
        return parseSignals(raw)
    }

    fun saveSignals(
        context: Context,
        entries: List<TasteSignalEntry>,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        val capped = entries
            .sortedByDescending { it.atMs }
            .distinctBy { it.identityKey to it.kind }
            .take(MAX_SIGNALS)
        val arr = JSONArray()
        capped.forEach { e ->
            arr.put(
                JSONObject()
                    .put("k", e.identityKey)
                    .put("kind", e.kind.key())
                    .put("t", e.atMs)
                    .put("title", e.title)
                    .put("genres", JSONArray(e.genreIds))
                    .put("tmdb", e.tmdbId)
                    .put("tv", e.isTv),
            )
        }
        prefs(context).edit().putString(storageKey(KEY_SIGNALS, profileId), arr.toString()).apply()
    }

    fun loadGenreAffinity(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
    ): Map<String, Float> {
        val raw = prefs(context).getString(storageKey(KEY_AFFINITY, profileId), "{}").orEmpty()
        return parseAffinity(raw)
    }

    fun saveGenreAffinity(
        context: Context,
        affinity: Map<String, Float>,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        val obj = JSONObject()
        affinity.entries
            .sortedByDescending { it.value }
            .take(80)
            .forEach { (id, score) -> obj.put(id, score.toDouble()) }
        prefs(context).edit().putString(storageKey(KEY_AFFINITY, profileId), obj.toString()).apply()
    }

    fun loadQueryTokens(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
    ): List<String> {
        val raw = prefs(context).getString(storageKey(KEY_QUERY_TOKENS, profileId), "[]").orEmpty()
        return parseTokens(raw)
    }

    fun saveQueryTokens(
        context: Context,
        tokens: List<String>,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        val arr = JSONArray()
        tokens.take(MAX_TOKENS).forEach { arr.put(it) }
        prefs(context).edit().putString(storageKey(KEY_QUERY_TOKENS, profileId), arr.toString()).apply()
    }

    fun loadLastWatchEngagementMs(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
    ): Map<String, Long> {
        val raw = prefs(context).getString(storageKey(KEY_WATCH_ENGAGEMENT, profileId), "{}").orEmpty()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, obj.optLong(k, 0L))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun saveLastWatchEngagementMs(
        context: Context,
        identityKey: String,
        atMs: Long,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        val map = loadLastWatchEngagementMs(context, profileId).toMutableMap()
        map[identityKey] = atMs
        // Cap map size.
        val capped = map.entries.sortedByDescending { it.value }.take(80)
        val obj = JSONObject()
        capped.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit()
            .putString(storageKey(KEY_WATCH_ENGAGEMENT, profileId), obj.toString())
            .apply()
    }

    fun clearAll(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        prefs(context).edit()
            .remove(storageKey(KEY_SIGNALS, profileId))
            .remove(storageKey(KEY_AFFINITY, profileId))
            .remove(storageKey(KEY_QUERY_TOKENS, profileId))
            .remove(storageKey(KEY_WATCH_ENGAGEMENT, profileId))
            .apply()
    }

    fun clearNegatives(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        val kept = loadSignals(context, profileId).filterNot {
            it.kind == TasteSignalKind.HIDDEN || it.kind == TasteSignalKind.NOT_INTERESTED
        }
        saveSignals(context, kept, profileId)
    }

    fun wipeProfile(context: Context, profileId: String) {
        clearAll(context, profileId)
        val p = prefs(context)
        val suffix = "_p_$profileId"
        p.edit().also { editor ->
            p.all.keys.filter { it.endsWith(suffix) }.forEach { editor.remove(it) }
        }.apply()
    }

    /** Serialize signals for profile transfer (file export). */
    fun exportSignalsJson(context: Context): String {
        val arr = JSONArray()
        loadSignals(context).forEach { e ->
            arr.put(
                JSONObject()
                    .put("k", e.identityKey)
                    .put("kind", e.kind.key())
                    .put("t", e.atMs)
                    .put("title", e.title)
                    .put("genres", JSONArray(e.genreIds))
                    .put("tmdb", e.tmdbId)
                    .put("tv", e.isTv),
            )
        }
        return arr.toString()
    }

    fun exportAffinityJson(context: Context): String {
        val obj = JSONObject()
        loadGenreAffinity(context).forEach { (id, score) -> obj.put(id, score.toDouble()) }
        return obj.toString()
    }

    fun exportTokensJson(context: Context): String {
        val arr = JSONArray()
        loadQueryTokens(context).forEach { arr.put(it) }
        return arr.toString()
    }

    fun importBundle(
        context: Context,
        signalsJson: String?,
        affinityJson: String?,
        tokensJson: String?,
    ) {
        if (signalsJson != null) {
            saveSignals(context, parseSignals(signalsJson))
        }
        if (affinityJson != null) {
            saveGenreAffinity(context, parseAffinity(affinityJson))
        }
        if (tokensJson != null) {
            saveQueryTokens(context, parseTokens(tokensJson))
        }
    }

    internal fun parseSignals(raw: String): List<TasteSignalEntry> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val key = obj.optString("k").trim()
                val kind = TasteSignalKind.fromKey(obj.optString("kind")) ?: continue
                if (key.isBlank()) continue
                val genres = buildList {
                    val g = obj.optJSONArray("genres")
                    if (g != null) {
                        for (j in 0 until g.length()) {
                            val id = g.optString(j).trim()
                            if (id.isNotEmpty()) add(id)
                        }
                    }
                }
                add(
                    TasteSignalEntry(
                        identityKey = key,
                        kind = kind,
                        atMs = obj.optLong("t", 0L),
                        title = obj.optString("title"),
                        genreIds = genres,
                        tmdbId = obj.optString("tmdb").takeIf { it.isNotBlank() },
                        isTv = obj.optBoolean("tv", false),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    internal fun parseAffinity(raw: String): Map<String, Float> = runCatching {
        val obj = JSONObject(raw)
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, obj.optDouble(k, 0.0).toFloat())
            }
        }
    }.getOrDefault(emptyMap())

    internal fun parseTokens(raw: String): List<String> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val t = arr.optString(i).trim()
                if (t.isNotEmpty()) add(t)
            }
        }
    }.getOrDefault(emptyList())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun storageKey(base: String, profileId: String = ProfileManager.activeProfileId): String =
        ProfileManager.scopedPrefKeyFor(base, profileId)
}
