package com.dskja.betterstreamflix.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dskja.betterstreamflix.profiles.ProfileManager
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
                // Safest initial policy: only sync while this profile is active.
                if (profileId != ProfileManager.activeProfileId) {
                    Result.success()
                } else {
                    val client = SupabaseProvider.initializeForProfile(applicationContext, profileId)
                        ?: return Result.success()
                    client.auth.awaitInitialization()
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
            // Persistent failures (e.g. expired session) must not retry forever.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
