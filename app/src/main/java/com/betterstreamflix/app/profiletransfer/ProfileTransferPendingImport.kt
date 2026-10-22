package com.betterstreamflix.app.profiletransfer

import android.content.Context
import androidx.core.content.edit
import com.betterstreamflix.app.profiles.ProfileManager

/** Hold a profile import until the active profile unlock gate clears. */
object ProfileTransferPendingImport {
    private const val PREFS = "profile_transfer_pending"
    private const val KEY = "pending_json"

    fun enqueue(context: Context, payload: ProfileTransferPayload) {
        val json = runCatching { ProfileTransferCodec.encodeCompactJson(payload) }.getOrNull()
            ?: return
        prefs(context).edit {
            putString(scopedKey(), json)
        }
    }

    fun take(context: Context): ProfileTransferPayload? {
        val key = scopedKey()
        val raw = prefs(context).getString(key, null) ?: return null
        prefs(context).edit { remove(key) }
        return ProfileTransferCodec.decodeFileJson(raw)
    }

    private fun scopedKey(): String =
        ProfileManager.scopedPrefKey(KEY)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
