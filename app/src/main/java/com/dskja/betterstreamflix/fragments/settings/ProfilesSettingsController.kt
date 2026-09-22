package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExpPressEffects
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.profiles.ProfileAvatarStyle
import com.dskja.betterstreamflix.profiles.ProfileAvatarView
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.profiles.ProfilePickerDialog
import com.dskja.betterstreamflix.profiles.UserProfile

/** Shared Profiles binder for Mobile + TV settings. */
object ProfilesSettingsController {
    private fun alertBuilder(context: Context) =
        if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }

    private fun notifyUser(
        context: Context,
        message: CharSequence,
        titleRes: Int = R.string.profile_switch_title,
    ) {
        if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.showInfo(context, titleRes, message, ::alertBuilder)
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun styleExpInput(input: EditText, context: Context) {
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        if (ExperimentalMobileDesign.enabled()) {
            input.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
            input.setPadding(pad, pad, pad, pad)
            input.setTextAppearance(R.style.TextAppearance_Lumina_Body)
        } else {
            input.setPadding(pad, pad / 2, pad, pad / 2)
        }
    }

    private val integrationPrefKeys = mapOf(
        "PROFILE_INT_TRAKT" to UserProfile.Integration.TRAKT,
        "PROFILE_INT_JELLYFIN" to UserProfile.Integration.JELLYFIN,
        "PROFILE_INT_PLEX" to UserProfile.Integration.PLEX,
        "PROFILE_INT_DEBRID" to UserProfile.Integration.DEBRID,
        "PROFILE_INT_SIMKL" to UserProfile.Integration.SIMKL,
        "PROFILE_INT_OPENSUBTITLES" to UserProfile.Integration.OPENSUBTITLES,
        "PROFILE_INT_TMDB" to UserProfile.Integration.TMDB,
    )

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
        onProfileSwitched: (() -> Unit)? = null,
    ) {
        val context = fragment.requireContext()

        findPreference("PROFILE_SWITCH")?.setOnPreferenceClickListener {
            showSwitchDialog(fragment, findPreference, onProfileSwitched)
            true
        }

        findPreference("PROFILE_CREATE")?.setOnPreferenceClickListener {
            showCreateDialog(fragment, findPreference, onProfileSwitched)
            true
        }

        findPreference("PROFILE_RENAME")?.setOnPreferenceClickListener {
            showRenameDialog(fragment, findPreference)
            true
        }

        findPreference("PROFILE_DELETE")?.setOnPreferenceClickListener {
            showDeleteDialog(fragment, findPreference, onProfileSwitched)
            true
        }

        findPreference("PROFILE_AVATAR")?.setOnPreferenceClickListener {
            showAvatarPicker(fragment, findPreference)
            true
        }

        findPreference("PROFILE_KIDS")?.let { pref ->
            pref.isVisible = false
            pref.isEnabled = false
        }

        findPreference("PROFILE_PIN")?.setOnPreferenceClickListener {
            showPinEditor(fragment, findPreference)
            true
        }

        findPreference("PROFILE_PIN_CLEAR")?.setOnPreferenceClickListener {
            ProfileManager.clearPin(ProfileManager.activeProfileId)
            notifyUser(context, context.getString(R.string.profile_pin_cleared), R.string.profile_pin_title)
            refresh(findPreference, context, fragment)
            true
        }

        integrationPrefKeys.forEach { (prefKey, integration) ->
            findPreference(prefKey)?.let { pref ->
                val switch = pref as? SwitchPreferenceCompat ?: return@let
                switch.setOnPreferenceChangeListener { _, newValue ->
                    ProfileManager.setIntegrationEnabled(
                        ProfileManager.activeProfileId,
                        integration,
                        newValue as Boolean,
                    )
                    refresh(findPreference, context, fragment)
                    true
                }
            }
        }

        refresh(findPreference, context, fragment)
    }

    fun refresh(
        findPreference: (String) -> Preference?,
        context: Context,
        fragment: Fragment? = null,
    ) {
        val profile = ProfileManager.activeProfile()
        val profileCount = ProfileManager.profiles().size

        findPreference("PROFILE_ACTIVE")?.summary = profile?.let { activeSummary(context, it) }
            ?: context.getString(R.string.profile_default)

        findPreference("PROFILE_DELETE")?.isEnabled = profileCount > 1

        findPreference("PROFILE_AVATAR")?.apply {
            val key = profile?.avatarKey ?: ProfileManager.avatarKeys.first()
            summary = context.getString(ProfileAvatarStyle.paletteFor(key).titleRes)
            val preview = context.getDrawable(R.drawable.bg_profile_avatar_preview)?.mutate()
            if (preview != null) {
                DrawableCompat.setTint(preview, ProfileAvatarStyle.colorFor(key))
                icon = preview
            }
        }

        (findPreference("PROFILE_KIDS") as? SwitchPreferenceCompat)?.isChecked = profile?.isKids == true

        findPreference("PROFILE_PIN")?.summary = if (profile?.pinHash != null) {
            context.getString(R.string.profile_pin_set)
        } else {
            context.getString(R.string.profile_pin_not_set)
        }

        findPreference("PROFILE_PIN_CLEAR")?.isVisible = profile?.pinHash != null

        val active = profile
        integrationPrefKeys.forEach { (prefKey, integration) ->
            (findPreference(prefKey) as? SwitchPreferenceCompat)?.isChecked =
                active?.let { ProfileManager.isIntegrationEnabled(it, integration) } ?: true
        }

        findPreference(ProfileHeroPreference.KEY)?.let { pref ->
            (pref as? ProfileHeroPreference)?.refresh()
                ?: run { pref.summary = pref.summary }
        }
    }

    private fun activeSummary(context: Context, profile: UserProfile): String {
        val kids = if (profile.isKids) " · ${context.getString(R.string.profile_kids_badge_short)}" else ""
        val pin = if (profile.pinHash != null) " · ${context.getString(R.string.profile_picker_locked)}" else ""
        val avatar = context.getString(ProfileAvatarStyle.paletteFor(profile.avatarKey).titleRes)
        return "${profile.displayName}$kids$pin · $avatar"
    }

    fun showSwitchDialog(
        fragment: Fragment,
        startInManageMode: Boolean = false,
        onProfileSwitched: (() -> Unit)? = null,
    ) {
        ProfilePickerDialog.show(
            fragment = fragment,
            onSwitched = onProfileSwitched,
            onManage = null,
            onCreate = {
                ProfilePickerDialog.showCreateDialog(fragment) { created ->
                    promptSwitchAfterCreate(fragment, { null }, created, onProfileSwitched)
                }
            },
            startInManageMode = startInManageMode,
        )
    }

    private fun showSwitchDialog(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        onProfileSwitched: (() -> Unit)?,
    ) {
        ProfilePickerDialog.show(
            fragment = fragment,
            onSwitched = {
                refresh(findPreference, fragment.requireContext(), fragment)
                onProfileSwitched?.invoke()
            },
            onManage = {
                refresh(findPreference, fragment.requireContext(), fragment)
            },
            onCreate = {
                ProfilePickerDialog.showCreateDialog(fragment) { created ->
                    promptSwitchAfterCreate(fragment, findPreference, created, onProfileSwitched)
                }
            },
        )
    }

    private fun promptSwitchAfterCreate(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        created: UserProfile,
        onProfileSwitched: (() -> Unit)?,
    ) {
        val ctx = fragment.requireContext()
        val glass = if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.buildGlassMessage(
                ctx,
                fragment.getString(R.string.profile_switch_prompt, created.displayName),
            )
        } else {
            null
        }
        val builder = alertBuilder(ctx)
        if (glass != null) builder.setView(glass.root)
        else builder.setMessage(fragment.getString(R.string.profile_switch_prompt, created.displayName))
        builder
            .setPositiveButton(android.R.string.ok) { _, _ ->
                performSwitch(fragment, findPreference, created, onProfileSwitched)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                refresh(findPreference, fragment.requireContext(), fragment)
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    if (glass != null) ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                    else ExpDialogChrome.polishButtons(dialog)
                }
                dialog.show()
            }
    }

    private fun showCreateDialog(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        onProfileSwitched: (() -> Unit)?,
    ) {
        ProfilePickerDialog.showCreateDialog(fragment) { created ->
            promptSwitchAfterCreate(fragment, findPreference, created, onProfileSwitched)
        }
    }

    private fun showGlassInputDialog(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        input: EditText,
        onPositive: () -> Unit,
    ) {
        val glass = if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.buildGlassMessage(context, message).also { g ->
                val density = context.resources.displayMetrics.density
                g.root.addView(
                    input,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also { it.topMargin = (12 * density).toInt() },
                )
            }
        } else {
            null
        }
        val builder = alertBuilder(context).setTitle(title)
        if (glass != null) builder.setView(glass.root)
        else builder.setMessage(message).setView(input)
        builder
            .setPositiveButton(android.R.string.ok) { _, _ -> onPositive() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    if (glass != null) {
                        ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                        with(ExpPressEffects) { input.applyExpPress() }
                        ExpMotion.popIn(input)
                    } else {
                        ExpDialogChrome.polishButtons(dialog)
                    }
                }
                dialog.show()
            }
    }

    private fun showGlassInputDialog(
        context: Context,
        titleRes: Int,
        message: CharSequence,
        input: EditText,
        onPositive: () -> Unit,
    ) = showGlassInputDialog(context, context.getString(titleRes), message, input, onPositive)

    private fun showRenameDialog(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ) {
        val context = fragment.requireContext()
        val profile = ProfileManager.activeProfile() ?: return
        val input = EditText(context).apply {
            setText(profile.displayName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelection(text?.length ?: 0)
            styleExpInput(this, context)
        }

        showGlassInputDialog(
            context = context,
            titleRes = R.string.profile_rename_title,
            message = context.getString(R.string.profile_rename_title),
            input = input,
        ) {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                notifyUser(context, context.getString(R.string.profile_name_empty), R.string.profile_rename_title)
                return@showGlassInputDialog
            }
            if (ProfileManager.rename(profile.id, name)) {
                notifyUser(context, context.getString(R.string.profile_renamed_toast), R.string.profile_rename_title)
                refresh(findPreference, context, fragment)
            }
        }
    }

    private fun showDeleteDialog(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        onProfileSwitched: (() -> Unit)?,
    ) {
        val context = fragment.requireContext()
        val profile = ProfileManager.activeProfile() ?: return
        if (ProfileManager.profiles().size <= 1) {
            if (ExperimentalMobileDesign.enabled()) {
                ExpDialogChrome.showInfo(
                    context,
                    R.string.profile_delete_title,
                    context.getString(R.string.profile_cannot_delete_last),
                    ::alertBuilder,
                )
            } else {
                Toast.makeText(context, R.string.profile_cannot_delete_last, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val deleteMessage = context.getString(R.string.profile_delete_confirm, profile.displayName)
        val glass = if (ExperimentalMobileDesign.enabled()) {
            ExpDialogChrome.buildGlassMessage(context, deleteMessage)
        } else {
            null
        }
        val builder = alertBuilder(context)
        if (glass != null) builder.setView(glass.root)
        else builder.setMessage(deleteMessage)
        builder
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val wasActive = profile.id == ProfileManager.activeProfileId
                if (ProfileManager.delete(profile.id)) {
                    notifyUser(context, context.getString(R.string.profile_deleted_toast), R.string.profile_delete_title)
                    refresh(findPreference, context, fragment)
                    if (wasActive) onProfileSwitched?.invoke()
                }
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

    private fun showAvatarPicker(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ) {
        val context = fragment.requireContext()
        val profile = ProfileManager.activeProfile() ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_profile_avatar_picker, null, false)
        val preview = view.findViewById<ProfileAvatarView>(R.id.pav_profile_avatar_preview)
        val label = view.findViewById<TextView>(R.id.tv_profile_avatar_label)
        val row = view.findViewById<LinearLayout>(R.id.ll_profile_avatar_choices)
        var selected = profile.avatarKey

        fun refreshChoices() {
            row.removeAllViews()
            val density = context.resources.displayMetrics.density
            preview.bind(selected, profile.displayName, textSizeSp = 22f)
            label.setText(ProfileAvatarStyle.paletteFor(selected).titleRes)
            ProfileAvatarStyle.all().forEach { palette ->
                val orb = ProfileAvatarView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (56 * density).toInt(),
                        (56 * density).toInt(),
                    ).also {
                        val m = (10 * density).toInt()
                        it.setMargins(0, 0, m, 0)
                    }
                    bind(palette.key, ProfileAvatarStyle.initialFor(palette.key), textSizeSp = 16f)
                    isSelected = palette.key == selected
                    foreground = context.getDrawable(R.drawable.bg_profile_avatar_select_ring)
                    if (ExperimentalMobileDesign.enabled()) {
                        with(ExpPressEffects) { applyExpPress() }
                    }
                    setOnClickListener {
                        ExpMotion.hapticTap(it)
                        selected = palette.key
                        refreshChoices()
                    }
                }
                row.addView(orb)
            }
        }
        refreshChoices()

        val dialog = (
            if (ExperimentalMobileDesign.enabled()) MaterialAlertDialogBuilder(context)
            else AlertDialog.Builder(context)
        ).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val cancel = view.findViewById<TextView>(R.id.btn_profile_avatar_cancel)
        val ok = view.findViewById<TextView>(R.id.btn_profile_avatar_ok)
        if (ExperimentalMobileDesign.enabled()) {
            cancel.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            ok.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
            with(ExpPressEffects) {
                cancel.applyExpPress()
                ok.applyExpPress()
            }
        }
        cancel.setOnClickListener {
            ExpMotion.hapticTap(it)
            dialog.dismiss()
        }
        ok.setOnClickListener {
            ExpMotion.hapticTap(it)
            ProfileManager.updateAvatar(profile.id, selected)
            refresh(findPreference, context, fragment)
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            if (ExperimentalMobileDesign.enabled()) {
                ExperimentalMobileDesign.applyReducedGlass(view)
                view.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
                ExpDialogChrome.polishShown(dialog)
            }
        }
        dialog.show()
    }

    private fun showPinEditor(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ) {
        val context = fragment.requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = context.getString(R.string.profile_pin_title)
            styleExpInput(this, context)
        }

        showGlassInputDialog(
            context = context,
            titleRes = R.string.profile_pin_title,
            message = context.getString(R.string.profile_pin_title),
            input = input,
        ) {
            val pin = input.text?.toString()?.trim().orEmpty()
            if (pin.isEmpty()) return@showGlassInputDialog
            if (ProfileManager.setPin(ProfileManager.activeProfileId, pin)) {
                notifyUser(context, context.getString(R.string.profile_pin_saved), R.string.profile_pin_title)
                refresh(findPreference, context, fragment)
            }
        }
    }

    private fun promptPin(context: Context, profile: UserProfile, onVerified: () -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            styleExpInput(this, context)
        }

        showGlassInputDialog(
            context = context,
            title = context.getString(R.string.profile_pin_enter, profile.displayName),
            message = context.getString(R.string.profile_pin_enter, profile.displayName),
            input = input,
        ) {
            val pin = input.text?.toString()?.trim().orEmpty()
            if (ProfileManager.verifyPin(profile.id, pin)) {
                onVerified()
            } else {
                notifyUser(context, context.getString(R.string.profile_pin_invalid), R.string.profile_pin_title)
            }
        }
    }

    private fun performSwitch(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        target: UserProfile,
        onProfileSwitched: (() -> Unit)?,
    ) {
        val context = fragment.requireContext()
        if (!ProfileManager.switchTo(context, target.id)) return
        notifyUser(
            context,
            context.getString(R.string.profile_switched_toast, target.displayName),
        )
        refresh(findPreference, context, fragment)
        onProfileSwitched?.invoke()
    }
}
