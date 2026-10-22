package com.betterstreamflix.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.betterstreamflix.app.profiles.ProfileManager
import io.github.jan.supabase.auth.auth

class CloudSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            if (!SupabaseProvider.isConfigured) {
                Result.success()
            } else {
                val profileId = inputData.getString(CloudSyncScheduler.KEY_PROFILE_ID)
                    ?: ProfileManager.activeProfileId
                val expectedUserId = inputData.getString(CloudSyncScheduler.KEY_USER_ID)
                // Intentional isolation: inactive-profile workers no-op so pending
                // mutations wait until that profile is active again (avoids writing
                // another profile's cloud state into the wrong Room DB).
                if (profileId != ProfileManager.activeProfileId) {
                    Result.success()
                } else {
                    val client = SupabaseProvider.initializeForProfile(applicationContext, profileId)
                        ?: return Result.success()
                    client.auth.awaitInitialization()
                    // Shared-list imports work with the publishable key alone.
                    runCatching {
                        SharedWatchlistRepository.flushPendingImports(applicationContext)
                    }
                    val sessionUserId = client.auth.currentSessionOrNull()?.user?.id
                    if (sessionUserId == null ||
                        (expectedUserId != null && sessionUserId != expectedUserId)
                    ) {
                        Result.success()
                    } else {
                        CloudSyncManager.syncNow(applicationContext)
                        Result.success()
                    }
                }
            }
        } catch (_: Throwable) {
            // SC-007: stop after MAX_ATTEMPTS; pending queues wait for the next enqueue.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private companion object {
        /** Cap retries so auth/network flakes do not spin forever (SC-007). */
        const val MAX_ATTEMPTS = 5
    }
}
