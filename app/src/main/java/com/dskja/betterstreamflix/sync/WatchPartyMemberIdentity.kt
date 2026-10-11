package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.profiles.ProfileManager
import java.util.UUID

/** Stable per-profile member key for lobby presence + chat (not a secret). */
object WatchPartyMemberIdentity {
    private const val PREFS_BASE = "watch_party_member"
    private const val KEY_MEMBER = "member_key"

    fun memberKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(
            ProfileManager.scopedPrefKey(PREFS_BASE),
            Context.MODE_PRIVATE,
        )
        val existing = prefs.getString(KEY_MEMBER, null)
        if (existing != null && existing.matches(Regex("^[A-Za-z0-9_-]{8,64}$"))) {
            return existing
        }
        val minted = UUID.randomUUID().toString().replace("-", "").take(32)
        prefs.edit().putString(KEY_MEMBER, minted).apply()
        return minted
    }

    fun displayName(): String =
        WatchPartyChat.sanitizeDisplayName(
            ProfileManager.activeProfile()?.displayName,
        )
}
