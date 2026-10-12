package com.dskja.betterstreamflix.kids

import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.fragments.settings.ProfilesSettingsController
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.UserPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Settings pickers + non-dismissible bedtime PIN lock for Mobile and TV.
 */
object KidsBedtimeUi {

    @Volatile
    private var lockDialogShowing: Boolean = false

    /** True while the non-dismissible bedtime PIN lock dialog is on screen. */
    fun isLockDialogShowing(): Boolean = lockDialogShowing

    fun enabledSummary(context: Context): String {
        KidsBedtime.refreshLockState()
        if (!UserPreferences.kidsBedtimeEnabled) {
            return context.getString(R.string.kids_bedtime_summary_off)
        }
        val time = KidsBedtime.formatMinuteOfDay(UserPreferences.kidsBedtimeMinuteOfDay)
        return if (KidsBedtime.isLocked()) {
            context.getString(R.string.kids_bedtime_summary_locked, time)
        } else {
            context.getString(R.string.kids_bedtime_summary_on, time)
        }
    }

    fun timeSummary(context: Context): String =
        context.getString(
            R.string.kids_bedtime_time_summary,
            KidsBedtime.formatMinuteOfDay(UserPreferences.kidsBedtimeMinuteOfDay),
        )

    fun showTimePicker(context: Context, onChanged: () -> Unit) {
        val current = KidsBedtime.clampMinuteOfDay(UserPreferences.kidsBedtimeMinuteOfDay)
        val leanback = DeviceCapabilities.isLeanbackDevice(context) ||
            DeviceCapabilities.isAmazonFireTv(context)
        if (leanback) {
            showLeanbackTimeList(context, current, onChanged)
            return
        }
        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                UserPreferences.kidsBedtimeMinuteOfDay = hourOfDay * 60 + minute
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.kids_bedtime_time_saved,
                        KidsBedtime.formatMinuteOfDay(UserPreferences.kidsBedtimeMinuteOfDay),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                onChanged()
            },
            current / 60,
            current % 60,
            true,
        ).show()
    }

    private fun showLeanbackTimeList(
        context: Context,
        current: Int,
        onChanged: () -> Unit,
    ) {
        val options = KidsBedtime.SETTINGS_MINUTE_OPTIONS
        val labels = options.map { KidsBedtime.formatMinuteOfDay(it) }.toTypedArray()
        val selected = options.indexOfFirst { it == current }.let { idx ->
            if (idx >= 0) idx else options.indexOfFirst { it >= current }.coerceAtLeast(0)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.kids_bedtime_time_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                UserPreferences.kidsBedtimeMinuteOfDay = options[which]
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.kids_bedtime_time_saved,
                        labels[which],
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                onChanged()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.option_cancel, null)
            .show()
    }

    /**
     * Challenge PIN to clear the bedtime lock (and set overnight bypass).
     * Cancel leaves the lock engaged.
     */
    fun challengeUnlock(
        context: Context,
        onUnlocked: () -> Unit,
        onFailedOrCancelled: (() -> Unit)? = null,
    ) {
        if (!KidsBedtime.applies()) {
            onFailedOrCancelled?.invoke()
            return
        }
        val parental = UserPreferences.parentalControlPin
        val profile = ProfileManager.activeProfile()
        if (parental.isBlank() && profile?.pinHash == null) {
            Toast.makeText(
                context,
                context.getString(R.string.kids_bedtime_needs_pin),
                Toast.LENGTH_LONG,
            ).show()
            onFailedOrCancelled?.invoke()
            return
        }
        ProfilesSettingsController.challengeKidsUnlock(context) {
            KidsBedtime.unlockUntilWake()
            Toast.makeText(
                context,
                context.getString(R.string.kids_bedtime_unlocked),
                Toast.LENGTH_SHORT,
            ).show()
            onUnlocked()
        }
    }

    /**
     * Show a Cinema-Ink bedtime lock sheet when [KidsBedtime.isLocked].
     * Safe to call from Activity.onStart; ignores duplicates.
     */
    fun maybeShowLock(
        activity: FragmentActivity,
        onUnlocked: (() -> Unit)? = null,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        KidsBedtime.refreshLockState()
        if (!KidsBedtime.isLocked()) {
            lockDialogShowing = false
            return
        }
        if (lockDialogShowing) return
        if (activity.supportFragmentManager.isStateSaved) return

        lockDialogShowing = true
        val time = KidsBedtime.formatMinuteOfDay(UserPreferences.kidsBedtimeMinuteOfDay)
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.kids_bedtime_lock_title)
            .setMessage(activity.getString(R.string.kids_bedtime_lock_message, time))
            .setCancelable(false)
            .setPositiveButton(R.string.kids_bedtime_lock_enter_pin, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                challengeUnlock(
                    context = activity,
                    onUnlocked = {
                        lockDialogShowing = false
                        dialog.dismiss()
                        onUnlocked?.invoke()
                    },
                    onFailedOrCancelled = {
                        // Stay locked; allow later retry.
                    },
                )
            }
        }
        dialog.setOnDismissListener {
            // If still locked (cancel / wrong PIN path), allow a later onStart retry.
            if (KidsBedtime.isLocked()) {
                lockDialogShowing = false
            }
        }
        dialog.show()
    }

    /**
     * Guard navigating into the player after bedtime. Returns true when the
     * caller should abort the navigation for now. On successful unlock, invokes
     * [onAllowed] so the caller can retry (no "blocked" toast after success).
     */
    fun blockPlayerEntry(
        activity: FragmentActivity,
        onAllowed: (() -> Unit)? = null,
    ): Boolean {
        KidsBedtime.refreshLockState()
        if (!KidsBedtime.shouldBlockEnteringPlayer()) {
            onAllowed?.invoke()
            return false
        }
        if (KidsBedtime.isLocked()) {
            maybeShowLock(activity, onUnlocked = onAllowed)
            return true
        }
        // In window but not yet locked — require PIN before a new title.
        challengeUnlock(
            context = activity,
            onUnlocked = {
                // Bypass set — allow retry without a false "blocked" toast (KB-001/004).
                onAllowed?.invoke()
            },
            onFailedOrCancelled = {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.kids_bedtime_new_playback_blocked),
                    Toast.LENGTH_LONG,
                ).show()
            },
        )
        return true
    }
}
