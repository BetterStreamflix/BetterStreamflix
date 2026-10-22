package com.betterstreamflix.app.spoilers

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.Episode
import com.betterstreamflix.app.profiles.ProfileManager
import com.betterstreamflix.app.utils.ExpDialogChrome
import com.betterstreamflix.app.utils.ExpMotion
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Unlock chooser for Spoiler Shield — adult confirm, Kids parental PIN.
 *
 * Adults with the opt-in toggle need only a confirm dialog (no profile PIN).
 * Intentional for household adults; Kids always require parental/profile PIN (SS-013).
 * Not a substitute for Kids Fortress on a shared adult profile.
 */
object SpoilerShieldUi {

    /** Runs [onAllowed] immediately when the episode is not shielded; otherwise unlock first. */
    fun gateEpisodePlay(
        fragment: Fragment?,
        episode: Episode,
        onAllowed: () -> Unit,
    ) {
        if (!SpoilerShield.isShielded(episode)) {
            onAllowed()
            return
        }
        if (fragment == null) return
        requestUnlock(fragment, episode, onAllowed)
    }

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
        AlertDialog.Builder(ExpDialogChrome.themedContext(context))
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
                ExpDialogChrome.notify(
                    context,
                    R.string.spoiler_shield_kids_pin_required,
                    R.string.spoiler_shield_unlock_title,
                )
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
        // On TV, keep the dialog simple — EditText still works with on-screen keyboard /
        // Gboard; Leanback PIN chrome is owned by ProfilesSettingsController.
        AlertDialog.Builder(ExpDialogChrome.themedContext(context))
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
