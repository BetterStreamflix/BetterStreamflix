package com.dskja.betterstreamflix.taste

import android.content.Context
import com.dskja.betterstreamflix.profiles.ProfileManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profile-scoped SharedPreferences persistence for taste signals + genre affinity.
 */
object TasteGraphStore {

    private const val PREFS = "taste_graph_v1"
    private const val KEY_SIGNALS = "signals"
    private const val KEY_AFFINITY = "genre_affinity"
    private const val KEY_QUERY_TOKENS = "query_tokens"
    private const val MAX_SIGNALS = 200
    private const val MAX_TOKENS = 24

    fun loadSignals(context: Context): List<TasteSignalEntry> {
        val raw = prefs(context).getString(storageKey(KEY_SIGNALS), "[]").orEmpty()
        return parseSignals(raw)
    }

    fun saveSignals(context: Context, entries: List<TasteSignalEntry>) {
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
        prefs(context).edit().putString(storageKey(KEY_SIGNALS), arr.toString()).apply()
    }

    fun loadGenreAffinity(context: Context): Map<String, Float> {
        val raw = prefs(context).getString(storageKey(KEY_AFFINITY), "{}").orEmpty()
        return parseAffinity(raw)
    }

    fun saveGenreAffinity(context: Context, affinity: Map<String, Float>) {
        val obj = JSONObject()
        affinity.entries
            .sortedByDescending { it.value }
            .take(80)
            .forEach { (id, score) -> obj.put(id, score.toDouble()) }
        prefs(context).edit().putString(storageKey(KEY_AFFINITY), obj.toString()).apply()
    }

    fun loadQueryTokens(context: Context): List<String> {
        val raw = prefs(context).getString(storageKey(KEY_QUERY_TOKENS), "[]").orEmpty()
        return parseTokens(raw)
    }

    fun saveQueryTokens(context: Context, tokens: List<String>) {
        val arr = JSONArray()
        tokens.take(MAX_TOKENS).forEach { arr.put(it) }
        prefs(context).edit().putString(storageKey(KEY_QUERY_TOKENS), arr.toString()).apply()
    }

    fun clearAll(context: Context) {
        prefs(context).edit()
            .remove(storageKey(KEY_SIGNALS))
            .remove(storageKey(KEY_AFFINITY))
            .remove(storageKey(KEY_QUERY_TOKENS))
            .apply()
    }

    fun clearNegatives(context: Context) {
        val kept = loadSignals(context).filterNot {
            it.kind == TasteSignalKind.HIDDEN || it.kind == TasteSignalKind.NOT_INTERESTED
        }
        saveSignals(context, kept)
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

    private fun storageKey(base: String): String =
        ProfileManager.scopedPrefKeyFor(base, ProfileManager.activeProfileId)
}
