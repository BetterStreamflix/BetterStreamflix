package com.dskja.betterstreamflix.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dskja.betterstreamflix.profiles.ProfileManager
import java.util.concurrent.TimeUnit

object CloudSyncScheduler {
    const val KEY_PROFILE_ID = "profile_id"
    const val KEY_USER_ID = "user_id"

    fun enqueue(
        context: Context,
        profileId: String = ProfileManager.activeProfileId,
        userId: String? = CloudSyncManager.currentUserId()
            ?: CloudAccountStore.activeUserId(context, profileId),
    ) {
        val resolvedUserId = userId ?: return
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setInputData(
                workDataOf(
                    KEY_PROFILE_ID to profileId,
                    KEY_USER_ID to resolvedUserId,
                ),
            )
            .addTag("cloud-profile-$profileId")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "cloud-user-state-$profileId-$resolvedUserId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelProfile(context: Context, profileId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelAllWorkByTag("cloud-profile-$profileId")
    }
}
