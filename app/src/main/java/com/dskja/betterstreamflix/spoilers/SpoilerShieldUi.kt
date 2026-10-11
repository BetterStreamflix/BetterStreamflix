package com.dskja.betterstreamflix.spoilers

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Episode
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.UserPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Unlock chooser for Spoiler Shield — adult confirm, Kids parental PIN.
 */
object SpoilerShieldUi {

    fun requestUnlock(
        fragment: Fragment,
        episode: Episode,
        onUnlocked: () -> Unit,
    ) {
        val context = fragment.requireContext()
        if (!SpoilerShield.isShielded(episode)) {
            onUnlocked()
            return
        }
        val afterGate = {
            showUnlockChoices(context, episode, onUnlocked)
        }
        if (ProfileManager.isKidsActive()) {
            promptKidsGate(context, afterGate)
        } else {
            afterGate()
        }
    }

    private fun showUnlockChoices(
        context: Context,
        episode: Episode,
        onUnlocked: () -> Unit,
    ) {
        val labels = mutableListOf(
            context.getString(R.string.spoiler_shield_unlock_episode),
        )
        val actions = mutableListOf<() -> Unit>({
            SpoilerShield.unlockEpisode(episode.id)
            onUnlocked()
        })
        val showId = episode.tvShow?.id?.trim().orEmpty()
        if (showId.isNotEmpty()) {
            labels += context.getString(R.string.spoiler_shield_unlock_show)
            actions += {
                SpoilerShield.unlockShow(showId)
                onUnlocked()
            }
        }
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(ExpDialogChrome.themedContext(context))
        } else {
            AlertDialog.Builder(ExpDialogChrome.themedContext(context))
        }
        builder
            .setTitle(R.string.spoiler_shield_unlock_title)
            .setItems(labels.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
                dialog.show()
            }
    }

    private fun promptKidsGate(context: Context, onVerified: () -> Unit) {
        val parental = UserPreferences.parentalControlPin
        val profile = ProfileManager.activeProfile()
        val profilePin = profile?.pinHash
        when {
            parental.isNotBlank() -> promptDigitPin(
                context = context,
                title = context.getString(R.string.spoiler_shield_kids_pin_title),
                message = context.getString(R.string.spoiler_shield_kids_pin_message),
                verify = { entered ->
                    if (UserPreferences.verifyParentalControlPin(entered)) {
                        UserPreferences.registerParentalPinSuccess()
                        true
                    } else {
                        UserPreferences.registerParentalPinFailure()
                        false
                    }
                },
                onVerified = onVerified,
            )
            profilePin != null && profile != null -> promptDigitPin(
                context = context,
                title = context.getString(R.string.spoiler_shield_kids_pin_title),
                message = context.getString(R.string.spoiler_shield_kids_pin_message),
                verify = { entered -> ProfileManager.verifyPin(profile.id, entered) },
                onVerified = onVerified,
            )
            else -> {
                // No PIN configured — confirm with a guardian-style dialog.
                val message = context.getString(R.string.spoiler_shield_kids_confirm_message)
                val glass = if (ExperimentalMobileDesign.enabled()) {
                    ExpDialogChrome.buildGlassMessage(context, message)
                } else {
                    null
                }
                val builder = if (ExperimentalMobileDesign.enabled()) {
                    MaterialAlertDialogBuilder(ExpDialogChrome.themedContext(context))
                } else {
                    AlertDialog.Builder(ExpDialogChrome.themedContext(context))
                }
                builder.setTitle(R.string.spoiler_shield_unlock_title)
                if (glass != null) builder.setView(glass.root) else builder.setMessage(message)
                builder
                    .setPositiveButton(R.string.spoiler_shield_unlock_episode) { _, _ ->
                        onVerified()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                    .also { dialog ->
                        dialog.setOnShowListener {
                            if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                            else ExpDialogChrome.polishButtons(dialog)
                        }
                        dialog.show()
                    }
            }
        }
    }

    private fun promptDigitPin(
        context: Context,
        title: String,
        message: String,
        verify: (String) -> Boolean,
        onVerified: () -> Unit,
    ) {
        val input = EditText(ExpDialogChrome.themedContext(context)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = context.getString(R.string.settings_parental_pin_hint)
        }
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(ExpDialogChrome.themedContext(context))
        } else {
            AlertDialog.Builder(ExpDialogChrome.themedContext(context))
        }
        // On TV, keep the dialog simple — EditText still works with on-screen keyboard /
        // Gboard; Leanback PIN chrome is owned by ProfilesSettingsController.
        builder
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString()?.trim().orEmpty()
                if (verify(entered)) {
                    ExpMotion.hapticTap(input)
                    onVerified()
                } else {
                    ExpDialogChrome.notify(
                        context,
                        R.string.settings_parental_invalid_pin,
                        R.string.spoiler_shield_unlock_title,
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
                dialog.show()
            }
    }
}
