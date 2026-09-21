package com.dskja.betterstreamflix.fragments.search

import android.content.Context
import org.json.JSONArray

/**
 * Tiny recent-search history (last [MAX] unique queries) shared by Mobile + TV Search.
 */
object SearchRecentStore {

    private const val PREFS = "search_recent_v1"
    private const val KEY = "queries"
    private const val MAX = 8

    fun list(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, "[]").orEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val q = arr.optString(i).trim()
                    if (q.isNotBlank()) add(q)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun remember(context: Context, query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val next = (listOf(q) + list(context).filterNot { it.equals(q, ignoreCase = true) })
            .take(MAX)
        val arr = JSONArray()
        next.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
