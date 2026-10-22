package com.betterstreamflix.app.fragments.search

import android.content.Context
import com.betterstreamflix.app.profiles.ProfileManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Search Memories — “damals gesucht”: prior queries including failed / empty
 * ones, one-tap retry. Profile-scoped SharedPreferences JSON.
 */
object SearchMemories {

    private const val PREFS = "search_memories_v2"
    private const val KEY = "entries"
    const val MAX = 12

    enum class Outcome {
        SUCCESS,
        EMPTY,
        FAILED,
        ;

        companion object {
            fun fromKey(raw: String?): Outcome = when (raw?.lowercase()) {
                "empty" -> EMPTY
                "failed", "error" -> FAILED
                else -> SUCCESS
            }
        }

        fun key(): String = name.lowercase()
    }

    data class Entry(
        val query: String,
        val outcome: Outcome,
        val atMs: Long,
        val global: Boolean = false,
    )

    fun list(context: Context): List<Entry> {
        migrateLegacyIfNeeded(context)
        val raw = prefs(context).getString(storageKey(), "[]").orEmpty()
        return parse(raw)
    }

    /**
     * Move / insert query to the front. Keeps the prior outcome when known;
     * brand-new attempts stay off the strip until [recordOutcome] (so failed
     * searches still land as memories once the run finishes).
     */
    fun rememberAttempt(context: Context, query: String, global: Boolean = false) {
        val q = normalize(query) ?: return
        val now = System.currentTimeMillis()
        val prior = list(context)
        val previous = prior.firstOrNull { it.query.equals(q, ignoreCase = true) } ?: return
        val entry = previous.copy(atMs = now, global = global)
        persist(context, listOf(entry) + prior.filterNot { it.query.equals(q, ignoreCase = true) })
    }

    fun recordOutcome(
        context: Context,
        query: String,
        outcome: Outcome,
        global: Boolean = false,
        bumpTimestamp: Boolean = true,
    ) {
        val q = normalize(query) ?: return
        val prior = list(context)
        val existing = prior.firstOrNull { it.query.equals(q, ignoreCase = true) }
        val atMs = when {
            !bumpTimestamp && existing != null -> existing.atMs
            else -> System.currentTimeMillis()
        }
        val entry = Entry(query = q, outcome = outcome, atMs = atMs, global = global)
        persist(context, listOf(entry) + prior.filterNot { it.query.equals(q, ignoreCase = true) })
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(storageKey()).apply()
    }

    fun wipeProfile(context: Context, profileId: String) {
        val p = prefs(context)
        val key = ProfileManager.scopedPrefKeyFor(KEY, profileId)
        p.edit().remove(key).apply()
        val suffix = "_p_$profileId"
        p.edit().also { editor ->
            p.all.keys.filter { it.endsWith(suffix) }.forEach { editor.remove(it) }
        }.apply()
    }

    /** Export JSON array for profile transfer. */
    fun exportJson(context: Context): String {
        val arr = JSONArray()
        list(context).forEach { e ->
            arr.put(
                JSONObject()
                    .put("q", e.query)
                    .put("o", e.outcome.key())
                    .put("t", e.atMs)
                    .put("g", e.global),
            )
        }
        return arr.toString()
    }

    fun importJson(context: Context, raw: String?) {
        if (raw.isNullOrBlank()) return
        persist(context, parse(raw))
    }

    fun outcomeForLocalResults(results: List<*>): Outcome =
        if (results.isEmpty()) Outcome.EMPTY else Outcome.SUCCESS

    fun outcomeForGlobal(providerResults: List<ProviderResult>): Outcome? =
        outcomeForGlobalStates(providerResults.map { it.state })

    /** State-only helper (unit-testable without mocking [Provider]). */
    fun outcomeForGlobalStates(states: List<ProviderResult.State>): Outcome? {
        if (states.isEmpty()) return Outcome.EMPTY
        if (states.any { it is ProviderResult.State.Loading }) return null
        val anyHits = states.any { state ->
            val success = state as? ProviderResult.State.Success
            success != null && success.results.isNotEmpty()
        }
        if (anyHits) return Outcome.SUCCESS
        val allFailed = states.all { it is ProviderResult.State.Error }
        return if (allFailed) Outcome.FAILED else Outcome.EMPTY
    }

    private fun persist(context: Context, entries: List<Entry>) {
        val capped = entries.take(MAX)
        val arr = JSONArray()
        capped.forEach { e ->
            arr.put(
                JSONObject()
                    .put("q", e.query)
                    .put("o", e.outcome.key())
                    .put("t", e.atMs)
                    .put("g", e.global),
            )
        }
        prefs(context).edit().putString(storageKey(), arr.toString()).apply()
    }

    private fun parse(raw: String): List<Entry> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.opt(i) ?: continue
                when (item) {
                    is String -> {
                        val q = item.trim()
                        if (q.isNotBlank()) {
                            add(Entry(q, Outcome.SUCCESS, 0L, false))
                        }
                    }
                    is JSONObject -> {
                        val q = item.optString("q").trim()
                        if (q.isBlank()) continue
                        add(
                            Entry(
                                query = q,
                                outcome = Outcome.fromKey(item.optString("o")),
                                atMs = item.optLong("t", 0L),
                                global = item.optBoolean("g", false),
                            ),
                        )
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun normalize(query: String): String? {
        val q = query.trim()
        return q.takeIf { it.length >= 2 }
    }

    private fun storageKey(): String = ProfileManager.scopedPrefKey(KEY)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * One-time pull of legacy [SearchRecentStore] into the **default** profile only,
     * so first open on a Kids/secondary profile does not inherit adult recents.
     */
    private fun migrateLegacyIfNeeded(context: Context) {
        val p = prefs(context)
        val key = storageKey()
        if (p.contains(key)) return
        // Non-default profiles get an empty scoped list — never the device-wide legacy.
        if (ProfileManager.activeProfileId != ProfileManager.DEFAULT_PROFILE_ID) {
            p.edit().putString(key, "[]").apply()
            return
        }
        val legacy = runCatching {
            context.applicationContext
                .getSharedPreferences("search_recent_v1", Context.MODE_PRIVATE)
                .getString("queries", null)
        }.getOrNull()
        if (legacy.isNullOrBlank()) {
            p.edit().putString(key, "[]").apply()
            return
        }
        val migrated = parse(legacy).map {
            it.copy(outcome = Outcome.SUCCESS, atMs = it.atMs.takeIf { t -> t > 0 } ?: System.currentTimeMillis())
        }
        persist(context, migrated)
    }
}
