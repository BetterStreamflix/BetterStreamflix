package com.dskja.betterstreamflix.support

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Maintainable in-app supporter recognition.
 *
 * Source of truth: `assets/supporters.json`.
 * Add a new entry under `supporters` (name required; note/since/channel optional).
 * Names are shown as provided — no localization of personal names.
 */
object SupportersCatalog {
    private const val TAG = "SupportersCatalog"
    private const val ASSET_PATH = "supporters.json"

    data class Supporter(
        val name: String,
        val note: String = "",
        val since: String = "",
        val channel: String = "",
    )

    /** Bundled fallback if assets fail to load. */
    private val FALLBACK: List<Supporter> = listOf(
        Supporter(
            name = "Zem936",
            note = "First Buy Me a Coffee supporter",
            since = "2026-10-02",
            channel = "buy_me_a_coffee",
        ),
    )

    @Volatile
    private var cached: List<Supporter>? = null

    fun load(context: Context): List<Supporter> {
        cached?.let { return it }
        val loaded = runCatching {
            context.applicationContext.assets.open(ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }
                .let { parse(it) }
        }.getOrElse {
            Log.w(TAG, "Failed to load $ASSET_PATH: ${it.message}")
            emptyList()
        }
        val result = loaded.ifEmpty { FALLBACK }
        cached = result
        return result
    }

    /** Test / preview helper — does not touch assets. */
    fun parse(raw: String): List<Supporter> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("supporters") ?: return emptyList()
        val out = ArrayList<Supporter>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue
            out.add(
                Supporter(
                    name = name,
                    note = obj.optString("note").trim(),
                    since = obj.optString("since").trim(),
                    channel = obj.optString("channel").trim(),
                ),
            )
        }
        return out
    }

    fun displayNames(context: Context): String =
        load(context).joinToString(separator = ", ") { it.name }

    fun clearCacheForTests() {
        cached = null
    }
}
