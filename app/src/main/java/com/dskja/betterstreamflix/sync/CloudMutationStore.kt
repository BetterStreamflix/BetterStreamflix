package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.profiles.ProfileManager
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object CloudMutationStore {
    private const val PREFS = "cloud_sync_queue"
    private const val QUEUE = "pending_media_states"
    private const val MIGRATED = "profile_queues_migrated_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(RemoteMediaState.serializer())

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun queueKey(profileId: String) = "${QUEUE}_$profileId"

    private fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(MIGRATED, false)) return
        val legacy = p.getString(QUEUE, null)
        if (!legacy.isNullOrBlank() && p.getString(queueKey(ProfileManager.DEFAULT_PROFILE_ID), null) == null) {
            p.edit()
                .putString(queueKey(ProfileManager.DEFAULT_PROFILE_ID), legacy)
                .remove(QUEUE)
                .putBoolean(MIGRATED, true)
                .apply()
        } else {
            p.edit().putBoolean(MIGRATED, true).apply()
        }
    }

    @Synchronized
    fun enqueue(
        context: Context,
        state: RemoteMediaState,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        migrateIfNeeded(context)
        val current = read(context, profileId).associateByTo(linkedMapOf()) { it.queueKey }
        current[state.queueKey] = state
        write(context, profileId, current.values.toList())
    }

    @Synchronized
    fun pendingForUser(
        context: Context,
        userId: String,
        profileId: String = ProfileManager.activeProfileId,
    ): List<RemoteMediaState> {
        migrateIfNeeded(context)
        return read(context, profileId).filter { it.userId == userId }
    }

    @Synchronized
    fun acknowledge(
        context: Context,
        uploaded: List<RemoteMediaState>,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        if (uploaded.isEmpty()) return
        migrateIfNeeded(context)
        val uploadedVersions = uploaded.associate { it.queueKey to it.clientUpdatedAtMillis }
        val remaining = read(context, profileId).filter { state ->
            val uploadedVersion = uploadedVersions[state.queueKey]
            uploadedVersion == null || state.clientUpdatedAtMillis > uploadedVersion
        }
        write(context, profileId, remaining)
    }

    @Synchronized
    fun clearProfile(context: Context, profileId: String) {
        migrateIfNeeded(context)
        prefs(context).edit().remove(queueKey(profileId)).apply()
    }

    private fun read(context: Context, profileId: String): List<RemoteMediaState> {
        val raw = prefs(context).getString(queueKey(profileId), null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun write(context: Context, profileId: String, states: List<RemoteMediaState>) {
        prefs(context)
            .edit()
            .putString(queueKey(profileId), json.encodeToString(serializer, states))
            .apply()
    }
}
