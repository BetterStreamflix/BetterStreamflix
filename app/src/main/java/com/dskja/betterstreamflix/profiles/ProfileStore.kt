package com.dskja.betterstreamflix.profiles

import android.content.Context
import androidx.core.content.edit
import com.dskja.betterstreamflix.R
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local profile persistence. Avoid Gson [com.google.gson.reflect.TypeToken] for
 * open generics — under R8 that crashed cold start (BETTERSTREAMFLIX-1B).
 */
object ProfileStore {

    const val DEFAULT_PROFILE_ID = "default"
    private const val DEFAULT_AVATAR_KEY = "crimson"

    private const val PREFS = "beta_profiles"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

    private val gson = Gson()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(context: Context): List<UserProfile> {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        // Prefer Array<> class literal (R8-safe). Fall back to hand-rolled JSON
        // if Gson ever fails on a corrupted or legacy payload.
        val fromGson = runCatching {
            gson.fromJson(raw, Array<UserProfile>::class.java)?.toList().orEmpty()
        }.getOrNull()
        if (fromGson != null) return fromGson
        return runCatching { parseProfilesJson(raw) }.getOrDefault(emptyList())
    }

    fun saveAll(context: Context, profiles: List<UserProfile>) {
        prefs(context).edit {
            putString(KEY_PROFILES, gson.toJson(profiles.toTypedArray()))
        }
    }

    fun getActiveId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_PROFILE_ID, null)

    fun setActiveId(context: Context, id: String) {
        prefs(context).edit {
            putString(KEY_ACTIVE_PROFILE_ID, id)
        }
    }

    fun ensureDefaultExists(context: Context): UserProfile {
        val appContext = context.applicationContext
        val profiles = loadAll(appContext).toMutableList()
        val existing = profiles.find { it.id == DEFAULT_PROFILE_ID }
        if (existing != null) {
            if (getActiveId(appContext) == null) {
                setActiveId(appContext, DEFAULT_PROFILE_ID)
            }
            return existing
        }

        val now = System.currentTimeMillis()
        val displayName = runCatching {
            appContext.getString(R.string.profile_default)
        }.getOrDefault("Default")

        val defaultProfile = UserProfile(
            id = DEFAULT_PROFILE_ID,
            displayName = displayName,
            avatarKey = DEFAULT_AVATAR_KEY,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        profiles.add(0, defaultProfile)
        saveAll(appContext, profiles)
        if (getActiveId(appContext) == null) {
            setActiveId(appContext, DEFAULT_PROFILE_ID)
        }
        return defaultProfile
    }

    /** Minimal JSONArray parser — no TypeToken, no reflection generics. */
    internal fun parseProfilesJson(raw: String): List<UserProfile> {
        val array = when {
            raw.trimStart().startsWith("[") -> JSONArray(raw)
            else -> JSONArray().put(JSONObject(raw))
        }
        val out = ArrayList<UserProfile>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val displayName = obj.optString("displayName").ifBlank { id }
            val avatarKey = obj.optString("avatarKey").ifBlank { DEFAULT_AVATAR_KEY }
            val created = obj.optLong("createdAtMillis", System.currentTimeMillis())
            val updated = obj.optLong("updatedAtMillis", created)
            val integrations = linkedSetOf<String>()
            val integ = obj.optJSONArray("enabledIntegrations")
            if (integ != null) {
                for (j in 0 until integ.length()) {
                    val value = integ.optString(j)
                    if (value.isNotBlank()) integrations.add(value)
                }
            }
            out.add(
                UserProfile(
                    id = id,
                    displayName = displayName,
                    avatarKey = avatarKey,
                    accentColorArgb = if (obj.has("accentColorArgb") && !obj.isNull("accentColorArgb")) {
                        obj.optInt("accentColorArgb")
                    } else {
                        null
                    },
                    isKids = obj.optBoolean("isKids", false),
                    maxAgeRating = if (obj.has("maxAgeRating") && !obj.isNull("maxAgeRating")) {
                        obj.optInt("maxAgeRating")
                    } else {
                        null
                    },
                    pinHash = obj.optString("pinHash").takeIf { it.isNotBlank() },
                    createdAtMillis = created,
                    updatedAtMillis = updated,
                    enabledIntegrations = integrations,
                    notes = obj.optString("notes").takeIf { it.isNotBlank() },
                ),
            )
        }
        return out
    }
}
