package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.profiles.ProfileManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Separate pending queue for episode notes + Looks so media [CloudMutationStore] stays untouched.
 */
object CloudNotesLooksMutationStore {
    private const val PREFS = "cloud_notes_looks_queue"
    private const val QUEUE = "pending_notes_looks"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(PendingNotesLooksMutation.serializer())

    @Serializable
    data class PendingNotesLooksMutation(
        val kind: String,
        val userId: String,
        val episodeId: String? = null,
        val text: String? = null,
        val look: String? = null,
        val providerName: String? = null,
        val showId: String? = null,
        val clientUpdatedAtMillis: Long,
    ) {
        val queueKey: String
            get() = when (kind) {
                KIND_NOTE -> {
                    val provider = providerName.orEmpty().trim()
                    val show = showId.orEmpty().trim()
                    if (provider.isEmpty() && show.isEmpty()) {
                        listOf(userId, "note", episodeId.orEmpty()).joinToString("\u001f")
                    } else {
                        listOf(userId, "note", provider, show, episodeId.orEmpty())
                            .joinToString("\u001f")
                    }
                }
                KIND_LOOK -> listOf(userId, "look").joinToString("\u001f")
                else -> listOf(userId, kind).joinToString("\u001f")
            }

        companion object {
            const val KIND_NOTE = "note"
            const val KIND_LOOK = "look"
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun queueKey(profileId: String) = "${QUEUE}_$profileId"

    @Synchronized
    fun enqueueNote(
        context: Context,
        userId: String,
        episodeId: String,
        text: String,
        clientUpdatedAtMillis: Long,
        providerName: String = "",
        showId: String = "",
        profileId: String = ProfileManager.activeProfileId,
    ) {
        enqueue(
            context,
            PendingNotesLooksMutation(
                kind = PendingNotesLooksMutation.KIND_NOTE,
                userId = userId,
                episodeId = episodeId,
                text = text,
                providerName = providerName.trim(),
                showId = showId.trim(),
                clientUpdatedAtMillis = clientUpdatedAtMillis,
            ),
            profileId,
        )
    }

    @Synchronized
    fun enqueueLook(
        context: Context,
        userId: String,
        look: String,
        clientUpdatedAtMillis: Long,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        enqueue(
            context,
            PendingNotesLooksMutation(
                kind = PendingNotesLooksMutation.KIND_LOOK,
                userId = userId,
                look = look,
                clientUpdatedAtMillis = clientUpdatedAtMillis,
            ),
            profileId,
        )
    }

    @Synchronized
    fun pendingForUser(
        context: Context,
        userId: String,
        profileId: String = ProfileManager.activeProfileId,
    ): List<PendingNotesLooksMutation> =
        read(context, profileId).filter { it.userId == userId }

    @Synchronized
    fun acknowledge(
        context: Context,
        uploaded: List<PendingNotesLooksMutation>,
        profileId: String = ProfileManager.activeProfileId,
    ) {
        if (uploaded.isEmpty()) return
        val uploadedVersions = uploaded.associate { it.queueKey to it.clientUpdatedAtMillis }
        val remaining = read(context, profileId).filter { state ->
            val uploadedVersion = uploadedVersions[state.queueKey]
            uploadedVersion == null || state.clientUpdatedAtMillis > uploadedVersion
        }
        write(context, profileId, remaining)
    }

    @Synchronized
    fun clearProfile(context: Context, profileId: String) {
        prefs(context).edit().remove(queueKey(profileId)).apply()
    }

    private fun enqueue(
        context: Context,
        mutation: PendingNotesLooksMutation,
        profileId: String,
    ) {
        val current = read(context, profileId).associateByTo(linkedMapOf()) { it.queueKey }
        current[mutation.queueKey] = mutation
        write(context, profileId, current.values.toList())
    }

    private fun read(context: Context, profileId: String): List<PendingNotesLooksMutation> {
        val raw = prefs(context).getString(queueKey(profileId), null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun write(
        context: Context,
        profileId: String,
        states: List<PendingNotesLooksMutation>,
    ) {
        prefs(context)
            .edit()
            .putString(queueKey(profileId), json.encodeToString(serializer, states))
            .apply()
    }
}
