package com.dskja.betterstreamflix.sync

import android.content.Context
import com.dskja.betterstreamflix.database.AppDatabase
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.models.Movie
import com.dskja.betterstreamflix.models.TvShow
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.providers.Provider

object CloudSyncHooks {
    fun episodeNote(
        context: Context,
        episodeId: String,
        text: String,
        updatedAtMillis: Long,
    ) {
        if (CloudSyncManager.isApplyingRemote) return
        if (ProfileManager.isKidsActive()) return
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val userId = CloudSyncManager.currentUserId()
            ?: CloudAccountStore.activeUserId(appContext, profileId)
            ?: return
        val id = episodeId.trim()
        if (id.isEmpty()) return
        CloudNotesLooksMutationStore.enqueueNote(
            appContext,
            userId = userId,
            episodeId = id,
            text = text,
            clientUpdatedAtMillis = updatedAtMillis,
            profileId = profileId,
        )
        CloudSyncScheduler.enqueue(appContext, profileId, userId)
    }

    fun look(
        context: Context,
        look: String,
        updatedAtMillis: Long,
    ) {
        if (CloudSyncManager.isApplyingRemote) return
        if (ProfileManager.isKidsActive()) return
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val userId = CloudSyncManager.currentUserId()
            ?: CloudAccountStore.activeUserId(appContext, profileId)
            ?: return
        CloudNotesLooksMutationStore.enqueueLook(
            appContext,
            userId = userId,
            look = look,
            clientUpdatedAtMillis = updatedAtMillis,
            profileId = profileId,
        )
        CloudSyncScheduler.enqueue(appContext, profileId, userId)
    }

    fun movie(context: Context, provider: Provider, movie: Movie) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromMovie(userId, provider.name, movie, now)
        }
    }

    fun movie(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val movie = AppDatabase.getInstanceOrNull(context)?.movieDao()?.getById(id)
            ?: Movie(id = id)
        movie(context, provider, movie)
    }

    fun tvShow(context: Context, provider: Provider, show: TvShow) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromTvShow(userId, provider.name, show, now)
        }
    }

    fun tvShow(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val show = AppDatabase.getInstanceOrNull(context)?.tvShowDao()?.getById(id)
            ?: TvShow(id = id)
        tvShow(context, provider, show)
    }

    fun episode(context: Context, provider: Provider, episode: Episode) {
        enqueue(context) { userId, now ->
            RemoteMediaState.fromEpisode(userId, provider.name, episode, now)
        }
    }

    fun episode(context: Context, provider: Provider, id: String) {
        if (CloudSyncManager.isApplyingRemote) return
        val episode = AppDatabase.getInstanceOrNull(context)?.episodeDao()?.getById(id)
            ?: Episode(id = id)
        episode(context, provider, episode)
    }

    private inline fun enqueue(
        context: Context,
        state: (userId: String, now: Long) -> RemoteMediaState,
    ) {
        if (CloudSyncManager.isApplyingRemote) return
        val appContext = context.applicationContext
        val profileId = ProfileManager.activeProfileId
        val userId = CloudSyncManager.currentUserId()
            ?: CloudAccountStore.activeUserId(appContext, profileId)
            ?: return
        CloudMutationStore.enqueue(appContext, state(userId, System.currentTimeMillis()), profileId)
        CloudSyncScheduler.enqueue(appContext, profileId, userId)
    }
}
