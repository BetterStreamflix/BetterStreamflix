package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.EpisodeNotesStore
import com.dskja.betterstreamflix.utils.ThemeManager
import com.dskja.betterstreamflix.utils.UserPreferences
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Notes + Looks cloud sync: collect / flush / merge / apply with local-primary LWW.
 * Tie → local (pending local wins ties).
 */
object CloudNotesLooksSync {
    private const val NOTES_TABLE = "user_episode_notes"
    private const val PREFS_TABLE = "user_profile_prefs"
    private const val FETCH_PAGE_SIZE = 500L

    /** Higher clock wins; equal clocks prefer local (local primary). */
    fun noteLocalWins(localClock: Long, remoteClock: Long): Boolean =
        localClock >= remoteClock

    fun lookLocalWins(localClock: Long, remoteClock: Long): Boolean =
        localClock >= remoteClock

    fun mergeNote(
        remote: RemoteEpisodeNote?,
        local: RemoteEpisodeNote,
    ): RemoteEpisodeNote {
        if (remote == null) return local
        return if (noteLocalWins(local.clientUpdatedAtMillis, remote.clientUpdatedAtMillis)) {
            local
        } else {
            remote
        }
    }

    fun mergeLook(
        remote: RemoteProfilePrefs?,
        local: RemoteProfilePrefs,
    ): RemoteProfilePrefs {
        if (remote == null) return local
        return if (lookLocalWins(local.clientUpdatedAtMillis, remote.clientUpdatedAtMillis)) {
            local
        } else {
            remote
        }
    }

    /**
     * Clamp kids/adult looks on apply so a kids row never paints an adult shell and
     * kids_bright does not stick on adult profiles unless already chosen.
     */
    fun clampLookForActiveProfile(look: String, kidsActive: Boolean): String {
        val resolved = ThemeManager.resolveLook(look)
        return when {
            kidsActive -> ThemeManager.KIDS_BRIGHT
            resolved == ThemeManager.KIDS_BRIGHT -> ThemeManager.CINEMA_INK
            else -> resolved
        }
    }

    fun collectLocalNotes(context: Context, userId: String): List<RemoteEpisodeNote> =
        EpisodeNotesStore.listRecordsForActiveProfile(context).map { (episodeId, record) ->
            RemoteEpisodeNote(
                userId = userId,
                episodeId = episodeId,
                text = record.text,
                clientUpdatedAtMillis = record.updatedAtMillis,
            )
        }

    fun collectLocalLook(userId: String): RemoteProfilePrefs {
        val look = ThemeManager.resolveLook(UserPreferences.selectedTheme)
        val clock = UserPreferences.selectedThemeUpdatedAtMillis
            .takeIf { it > 0L }
            ?: System.currentTimeMillis().also {
                UserPreferences.selectedThemeUpdatedAtMillis = it
            }
        return RemoteProfilePrefs(
            userId = userId,
            look = look,
            clientUpdatedAtMillis = clock,
        )
    }

    suspend fun flushPending(
        context: Context,
        client: SupabaseClient,
        userId: String,
        profileId: String = ProfileManager.activeProfileId,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ) {
        while (true) {
            val pending = CloudNotesLooksMutationStore.pendingForUser(context, userId, profileId)
            if (pending.isEmpty()) return

            val remoteNotes = fetchNotes(client).associateBy { it.queueKey }
            val remoteLook = fetchLook(client, userId)

            val notesToUpload = mutableListOf<RemoteEpisodeNote>()
            var lookToUpload: RemoteProfilePrefs? = null

            for (mutation in pending) {
                when (mutation.kind) {
                    CloudNotesLooksMutationStore.PendingNotesLooksMutation.KIND_NOTE -> {
                        val episodeId = mutation.episodeId ?: continue
                        val local = RemoteEpisodeNote(
                            userId = userId,
                            episodeId = episodeId,
                            text = mutation.text.orEmpty(),
                            clientUpdatedAtMillis = mutation.clientUpdatedAtMillis,
                        )
                        val remote = remoteNotes[local.queueKey]
                        if (remote == null ||
                            noteLocalWins(local.clientUpdatedAtMillis, remote.clientUpdatedAtMillis)
                        ) {
                            notesToUpload += local
                        }
                    }
                    CloudNotesLooksMutationStore.PendingNotesLooksMutation.KIND_LOOK -> {
                        val local = RemoteProfilePrefs(
                            userId = userId,
                            look = ThemeManager.resolveLook(mutation.look),
                            clientUpdatedAtMillis = mutation.clientUpdatedAtMillis,
                        )
                        if (remoteLook == null ||
                            lookLocalWins(local.clientUpdatedAtMillis, remoteLook.clientUpdatedAtMillis)
                        ) {
                            lookToUpload = local
                        }
                    }
                }
            }

            val total = notesToUpload.size + if (lookToUpload != null) 1 else 0
            var uploaded = 0
            if (total > 0) {
                onProgress(
                    CloudSyncProgress(
                        CloudSyncProgress.Stage.UPLOADING,
                        current = uploaded,
                        total = total,
                    ),
                )
            }
            if (notesToUpload.isNotEmpty()) {
                notesToUpload.chunked(250).forEach { chunk ->
                    client.from(NOTES_TABLE).upsert(chunk) {
                        onConflict = "user_id,episode_id"
                    }
                    uploaded += chunk.size
                    onProgress(
                        CloudSyncProgress(
                            CloudSyncProgress.Stage.UPLOADING,
                            current = uploaded,
                            total = total,
                        ),
                    )
                }
            }
            lookToUpload?.let { look ->
                client.from(PREFS_TABLE).upsert(look) {
                    onConflict = "user_id"
                }
                uploaded += 1
                onProgress(
                    CloudSyncProgress(
                        CloudSyncProgress.Stage.UPLOADING,
                        current = uploaded,
                        total = total,
                    ),
                )
            }
            CloudNotesLooksMutationStore.acknowledge(context, pending, profileId)
        }
    }

    suspend fun syncPullApply(
        context: Context,
        client: SupabaseClient,
        userId: String,
        mergeLocal: Boolean,
    ) {
        val remoteNotes = fetchNotes(client)
        val remoteLook = fetchLook(client, userId)
        withContext(Dispatchers.IO) {
            if (mergeLocal) {
                mergeAndUploadNotes(context, client, userId, remoteNotes)
                mergeAndUploadLook(client, userId, remoteLook)
                val finalNotes = fetchNotes(client)
                val finalLook = fetchLook(client, userId)
                applyRemoteNotes(context, finalNotes)
                applyRemoteLook(context, finalLook)
            } else {
                applyRemoteNotes(context, remoteNotes)
                applyRemoteLook(context, remoteLook)
            }
        }
    }

    private suspend fun mergeAndUploadNotes(
        context: Context,
        client: SupabaseClient,
        userId: String,
        remote: List<RemoteEpisodeNote>,
    ) {
        val remoteByKey = remote.associateByTo(linkedMapOf()) { it.queueKey }
        val local = collectLocalNotes(context, userId)
        val toUpload = mutableListOf<RemoteEpisodeNote>()
        for (localNote in local) {
            val merged = mergeNote(remoteByKey[localNote.queueKey], localNote)
            remoteByKey[localNote.queueKey] = merged
            if (merged === localNote ||
                merged.clientUpdatedAtMillis == localNote.clientUpdatedAtMillis
            ) {
                toUpload += merged
            }
        }
        if (toUpload.isNotEmpty()) {
            toUpload.chunked(250).forEach { chunk ->
                client.from(NOTES_TABLE).upsert(chunk) {
                    onConflict = "user_id,episode_id"
                }
            }
        }
    }

    private suspend fun mergeAndUploadLook(
        client: SupabaseClient,
        userId: String,
        remote: RemoteProfilePrefs?,
    ) {
        val local = collectLocalLook(userId)
        val merged = mergeLook(remote, local)
        if (merged.clientUpdatedAtMillis == local.clientUpdatedAtMillis || remote == null) {
            client.from(PREFS_TABLE).upsert(merged) {
                onConflict = "user_id"
            }
        }
    }

    fun applyRemoteNotes(context: Context, remote: List<RemoteEpisodeNote>) {
        val pending = CloudSyncManager.currentUserId()?.let {
            CloudNotesLooksMutationStore.pendingForUser(context, it)
        }.orEmpty()
            .filter {
                it.kind == CloudNotesLooksMutationStore.PendingNotesLooksMutation.KIND_NOTE
            }
            .associateBy { it.queueKey }

        for (note in remote) {
            val localPending = pending[note.queueKey]
            if (localPending != null &&
                noteLocalWins(localPending.clientUpdatedAtMillis, note.clientUpdatedAtMillis)
            ) {
                continue
            }
            val localRecord = EpisodeNotesStore.getRecord(context, note.episodeId)
            if (localRecord != null &&
                noteLocalWins(localRecord.updatedAtMillis, note.clientUpdatedAtMillis) &&
                localPending == null
            ) {
                // Equal clock → keep local; newer local → keep local.
                if (localRecord.updatedAtMillis >= note.clientUpdatedAtMillis) continue
            }
            EpisodeNotesStore.applyRemote(
                context,
                note.episodeId,
                note.text,
                note.clientUpdatedAtMillis,
            )
        }
    }

    fun applyRemoteLook(context: Context, remote: RemoteProfilePrefs?) {
        if (remote == null) return
        val userId = remote.userId
        val pendingLook = CloudNotesLooksMutationStore.pendingForUser(context, userId)
            .firstOrNull {
                it.kind == CloudNotesLooksMutationStore.PendingNotesLooksMutation.KIND_LOOK
            }
        if (pendingLook != null &&
            lookLocalWins(pendingLook.clientUpdatedAtMillis, remote.clientUpdatedAtMillis)
        ) {
            return
        }
        val localClock = UserPreferences.selectedThemeUpdatedAtMillis
        if (localClock > 0L &&
            lookLocalWins(localClock, remote.clientUpdatedAtMillis) &&
            pendingLook == null
        ) {
            return
        }
        val kids = ProfileManager.isKidsActive()
        val clamped = clampLookForActiveProfile(remote.look, kids)
        UserPreferences.applyRemoteSelectedTheme(clamped, remote.clientUpdatedAtMillis)
    }

    private suspend fun fetchNotes(client: SupabaseClient): List<RemoteEpisodeNote> =
        CloudSyncManager.collectPages(FETCH_PAGE_SIZE) { from, to ->
            client.from(NOTES_TABLE).select {
                order("episode_id", Order.ASCENDING)
                range(from, to)
            }.decodeList()
        }

    private suspend fun fetchLook(
        client: SupabaseClient,
        userId: String,
    ): RemoteProfilePrefs? = runCatching {
        client.from(PREFS_TABLE).select {
            filter { eq("user_id", userId) }
            limit(1)
        }.decodeList<RemoteProfilePrefs>().firstOrNull()
    }.getOrNull()
}
