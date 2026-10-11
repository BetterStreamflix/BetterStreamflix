package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.profiles.ProfileManager

/** Offline / Kids-PIN queue for hand-off short codes (per profile). */
object CastHandoffPendingClaim {
    private const val PREFS_BASE = "cast_handoff_pending"
    private const val KEY_CODES = "codes"

    @Synchronized
    fun enqueue(context: Context, code: String) {
        val normalized = CastHandoffLinks.normalizeShortCode(code) ?: return
        val current = read(context).toMutableList()
        if (normalized in current) return
        current.add(normalized)
        write(context, current)
    }

    @Synchronized
    fun peek(context: Context): List<String> = read(context)

    @Synchronized
    fun acknowledge(context: Context, code: String) {
        val normalized = CastHandoffLinks.normalizeShortCode(code) ?: code.trim().uppercase()
        write(context, read(context).filterNot { it == normalized })
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CODES).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            ProfileManager.scopedPrefKey(PREFS_BASE),
            Context.MODE_PRIVATE,
        )

    private fun read(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CODES, null) ?: return emptyList()
        return raw.split('\n').mapNotNull { CastHandoffLinks.normalizeShortCode(it) }
    }

    private fun write(context: Context, codes: List<String>) {
        prefs(context).edit()
            .putString(KEY_CODES, codes.joinToString("\n"))
            .apply()
    }
}
