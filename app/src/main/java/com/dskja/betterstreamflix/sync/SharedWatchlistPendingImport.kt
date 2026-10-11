package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.profiles.ProfileManager

/** Offline queue for share-link imports until network + Supabase are available (per profile). */
object SharedWatchlistPendingImport {
    private const val PREFS_BASE = "shared_watchlist_pending"
    private const val KEY_TOKENS = "tokens"

    @Synchronized
    fun enqueue(context: Context, token: String) {
        if (!SharedWatchlistLinks.isValidToken(token)) return
        val normalized = token.trim()
        val current = read(context).toMutableList()
        if (normalized in current) return
        current.add(normalized)
        write(context, current)
    }

    @Synchronized
    fun peek(context: Context): List<String> = read(context)

    @Synchronized
    fun acknowledge(context: Context, token: String) {
        write(context, read(context).filterNot { it == token.trim() })
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TOKENS).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileManager.scopedPrefKey(PREFS_BASE),
            Context.MODE_PRIVATE,
        )

    private fun read(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_TOKENS, null) ?: return emptyList()
        return raw.split('\n').map { it.trim() }.filter { SharedWatchlistLinks.isValidToken(it) }
    }

    private fun write(context: Context, tokens: List<String>) {
        prefs(context).edit()
            .putString(KEY_TOKENS, tokens.joinToString("\n"))
            .apply()
    }
}
