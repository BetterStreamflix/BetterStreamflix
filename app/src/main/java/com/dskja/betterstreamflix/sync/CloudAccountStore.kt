package com.dskja.betterstreamflix.sync

import android.content.Context
import androidx.core.content.edit
import com.dskja.betterstreamflix.profiles.ProfileManager

object CloudAccountStore {
    private const val PREFS = "cloud_account_state"
    private const val ACTIVE_USER = "active_user_id"
    private const val ACTIVE_EMAIL = "active_user_email"
    private const val LEGACY_OWNER = "legacy_owner_id"
    private const val MIGRATED = "profile_keys_migrated_v1"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(MIGRATED, false)) return
        p.edit {
            val defaultId = ProfileManager.DEFAULT_PROFILE_ID
            val oldUser = p.getString(ACTIVE_USER, null)
            val oldOwner = p.getString(LEGACY_OWNER, null)
            if (oldUser != null && p.getString(userKey(defaultId), null) == null) {
                putString(userKey(defaultId), oldUser)
            }
            if (oldOwner != null && p.getString(ownerKey(defaultId), null) == null) {
                putString(ownerKey(defaultId), oldOwner)
            }
            // Drop global keys after one-time migration into default.
            remove(ACTIVE_USER)
            remove(LEGACY_OWNER)
            putBoolean(MIGRATED, true)
        }
    }

    private fun userKey(profileId: String) = "${ACTIVE_USER}_$profileId"
    private fun emailKey(profileId: String) = "${ACTIVE_EMAIL}_$profileId"
    private fun ownerKey(profileId: String) = "${LEGACY_OWNER}_$profileId"

    fun activeUserId(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
    ): String? {
        migrateIfNeeded(context)
        return prefs(context).getString(userKey(profileId), null)
    }

    fun activeUserEmail(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
    ): String? {
        migrateIfNeeded(context)
        return prefs(context).getString(emailKey(profileId), null)
    }

    fun setActiveUserId(context: Context, userId: String?) {
        setActiveAccount(context, ProfileManager.activeProfileId, userId, null)
    }

    fun setActiveAccount(
        context: Context,
        profileId: String,
        userId: String?,
        email: String?,
    ) {
        migrateIfNeeded(context)
        prefs(context).edit {
            if (userId == null) {
                remove(userKey(profileId))
                remove(emailKey(profileId))
            } else {
                putString(userKey(profileId), userId)
                if (email != null) putString(emailKey(profileId), email)
            }
        }
    }

    fun legacyOwnerId(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
    ): String? {
        migrateIfNeeded(context)
        return prefs(context).getString(ownerKey(profileId), null)
    }

    fun claimLegacyData(
        context: Context,
        userId: String,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        migrateIfNeeded(context)
        prefs(context).edit { putString(ownerKey(profileId), userId) }
    }

    fun clearProfile(context: Context, profileId: String) {
        migrateIfNeeded(context)
        prefs(context).edit {
            remove(userKey(profileId))
            remove(emailKey(profileId))
            remove(ownerKey(profileId))
        }
    }

    /** Reverse lookup: which local profile already owns this Supabase user. */
    fun profileIdForUser(context: Context, userId: String): String? {
        migrateIfNeeded(context)
        val p = prefs(context)
        // Check known profiles first, then any leftover keyed entries.
        ProfileManager.profiles().forEach { profile ->
            if (p.getString(userKey(profile.id), null) == userId) return profile.id
        }
        p.all.forEach { (key, value) ->
            if (key.startsWith("${ACTIVE_USER}_") && value == userId) {
                return key.removePrefix("${ACTIVE_USER}_")
            }
        }
        return null
    }
}
