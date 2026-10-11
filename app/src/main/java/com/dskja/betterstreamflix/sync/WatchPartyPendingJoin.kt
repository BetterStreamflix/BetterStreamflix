package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.profiles.ProfileManager

/** Offline / Kids-PIN queue for party deep links and join codes (per profile). */
object WatchPartyPendingJoin {
    private const val PREFS_BASE = "watch_party_pending"
    private const val KEY_TOKENS = "tokens"
    private const val KEY_CODES = "codes"

    @Synchronized
    fun enqueue(context: Context, token: String) {
        if (!WatchPartyLinks.isValidToken(token)) return
        val normalized = token.trim()
        val current = readTokens(context).toMutableList()
        if (normalized in current) return
        current.add(normalized)
        writeTokens(context, current)
    }

    @Synchronized
    fun enqueueCode(context: Context, code: String) {
        val normalized = WatchPartyLinks.normalizeShortCode(code) ?: return
        val current = readCodes(context).toMutableList()
        if (normalized in current) return
        current.add(normalized)
        writeCodes(context, current)
    }

    @Synchronized
    fun peek(context: Context): List<String> = readTokens(context)

    @Synchronized
    fun peekCodes(context: Context): List<String> = readCodes(context)

    @Synchronized
    fun acknowledge(context: Context, token: String) {
        writeTokens(context, readTokens(context).filterNot { it == token.trim() })
    }

    @Synchronized
    fun acknowledgeCode(context: Context, code: String) {
        val normalized = WatchPartyLinks.normalizeShortCode(code) ?: code.trim().uppercase()
        writeCodes(context, readCodes(context).filterNot { it == normalized })
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKENS)
            .remove(KEY_CODES)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileManager.scopedPrefKey(PREFS_BASE),
            Context.MODE_PRIVATE,
        )

    private fun readTokens(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_TOKENS, null) ?: return emptyList()
        return raw.split('\n').map { it.trim() }.filter { WatchPartyLinks.isValidToken(it) }
    }

    private fun writeTokens(context: Context, tokens: List<String>) {
        prefs(context).edit()
            .putString(KEY_TOKENS, tokens.joinToString("\n"))
            .apply()
    }

    private fun readCodes(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CODES, null) ?: return emptyList()
        return raw.split('\n').mapNotNull { WatchPartyLinks.normalizeShortCode(it) }
    }

    private fun writeCodes(context: Context, codes: List<String>) {
        prefs(context).edit()
            .putString(KEY_CODES, codes.joinToString("\n"))
            .apply()
    }
}
