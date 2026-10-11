package com.dskja.betterstreamflix.notifications

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * POST_NOTIFICATIONS (API 33+) helper shared by downloads enqueue and Settings toggles.
 */
object NotificationPermission {
    const val REQUEST_CODE = 4209

    fun areSystemNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Best-effort runtime request; no-op when already granted or pre-Tiramisu. */
    fun maybeRequest(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (hasPostPermission(activity)) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE,
        )
    }

    fun canPost(context: Context): Boolean =
        areSystemNotificationsEnabled(context) && hasPostPermission(context)
}
