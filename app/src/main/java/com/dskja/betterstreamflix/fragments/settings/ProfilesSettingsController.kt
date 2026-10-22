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
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.kids.KidsBedtime
import com.dskja.betterstreamflix.kids.KidsFortressPolicy
import com.dskja.betterstreamflix.profiles.ProfileAvatarStyle
import com.dskja.betterstreamflix.profiles.ProfileAvatarView
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.profiles.ProfilePickerDialog
import com.dskja.betterstreamflix.profiles.UserProfile
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.UserPreferences

/** Shared Profiles binder for Mobile + TV settings. */
object ProfilesSettingsController {
    private fun alertBuilder(context: Context) =
        androidx.appcompat.app.AlertDialog.Builder(context)

    private fun notifyUser(
        context: Context,
        message: CharSequence,
        titleRes: Int = R.string.profile_switch_title,
    ) {
        ExpDialogChrome.showInfo(context, titleRes, message, ::alertBuilder)
    }

    private fun styleExpInput(input: EditText, context: Context) {
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        input.setBackgroundResource(R.drawable.bg_search_edittext)
        input.setPadding(pad, pad, pad, pad)
        input.setTextAppearance(R.style.TextAppearance_CinemaInk_Body)
    }

    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
        onProfileSwitched: (() -> Unit)? = null,
        onExportProfile: (() -> Unit)? = null,
        onImportProfile: (() -> Unit)? = null,
        onOpenGlobalIntegrations: (() -> Unit)? = null,
    ) {
        val context = fragment.requireContext()

        findPreference("PROFILE_EXPORT")?.setOnPreferenceClickListener {
            onExportProfile?.invoke()
            true
        }

        findPreference("PROFILE_IMPORT")?.setOnPreferenceClickListener {
            onImportProfile?.invoke()
            true
        }

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

        findPreference("PROFILE_INTEGRATIONS")?.setOnPreferenceClickListener {
            showIntegrationsDialog(fragment, findPreference)
            true
        }

        findPreference("PROFILE_INTEGRATIONS_GLOBAL")?.setOnPreferenceClickListener {
            onOpenGlobalIntegrations?.invoke()
            true
        }

        findPreference("PROFILE_KIDS")?.let { pref ->
            val switch = pref as? SwitchPreferenceCompat
            if (switch != null) {
                switch.isVisible = true
                switch.isEnabled = true
                switch.setOnPreferenceChangeListener { _, newValue ->
                    val enableKids = newValue as Boolean
                    val profile = ProfileManager.activeProfile()
                    if (enableKids &&
                        !com.dskja.betterstreamflix.kids.KidsFortressPolicy.canEnableKids(
                            isGuest = profile?.isGuest == true,
                        )
                    ) {
                        notifyUser(
                            context,
                            context.getString(R.string.parent_dashboard_guest_no_kids),
                            R.string.profile_kids_title,
                        )
                        return@setOnPreferenceChangeListener false
                    }
                    if (enableKids &&
                        profile?.pinHash == null &&
                        UserPreferences.parentalControlPin.isBlank()
                    ) {
                        notifyUser(
                            context,
                            context.getString(R.string.profile_kids_requires_exit_pin),
                            R.string.profile_kids_title,
                        )
                        return@setOnPreferenceChangeListener false
                    }
                    // Turning Kids OFF is an exit from the parental ceiling — require PIN.
                    if (!enableKids && profile != null && ProfileManager.hasParentalCeiling(profile)) {
                        val adultTarget = profile.copy(isKids = false, maxAgeRating = null)
                        guardParentalExit(fragment, adultTarget) {
                            ProfileManager.updateKids(ProfileManager.activeProfileId, false)
                            switch.isChecked = false
                            refresh(findPreference, context, fragment)
                        }
                        return@setOnPreferenceChangeListener false
                    }
                    val profileId = ProfileManager.activeProfileId
                    ProfileManager.updateKids(profileId, enableKids)
                    refresh(findPreference, context, fragment)
                    true
                }
            }
        }

        (findPreference("PROFILE_MAX_AGE") as? ListPreference)?.setOnPreferenceChangeListener { pref, newValue ->
            val age = (newValue as? String)?.toIntOrNull() ?: return@setOnPreferenceChangeListener false
            if (!ProfileManager.isKidsActive()) return@setOnPreferenceChangeListener false
            val current = ProfileManager.activeProfile()?.maxAgeRating
                ?: ProfileManager.KIDS_DEFAULT_MAX_AGE
            // Raising the Kids age ceiling requires the same exit PIN as leaving Kids.
            if (age > current) {
                val profile = ProfileManager.activeProfile() ?: return@setOnPreferenceChangeListener false
                val adultTarget = profile.copy(isKids = false, maxAgeRating = null)
                guardParentalExit(fragment, adultTarget) {
                    ProfileManager.updateMaxAgeRating(ProfileManager.activeProfileId, age)
                    (pref as? ListPreference)?.value = age.toString()
                    notifyUser(
                        context,
                        context.getString(R.string.settings_parental_max_age_saved),
                        R.string.profile_kids_max_age_title,
                    )
                    refresh(findPreference, context, fragment)
                }
                return@setOnPreferenceChangeListener false
            }
            ProfileManager.updateMaxAgeRating(ProfileManager.activeProfileId, age)
            notifyUser(
                context,
                context.getString(R.string.settings_parental_max_age_saved),
                R.string.profile_kids_max_age_title,
            )
            refresh(findPreference, context, fragment)
            true
        }

        findPreference("KIDS_BEDTIME_ENABLED")?.let { pref ->
            val switch = pref as? SwitchPreferenceCompat ?: return@let
            switch.setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as Boolean
                if (!ProfileManager.isKidsActive()) {
                    notifyUser(
                        context,
                        context.getString(R.string.profile_kids_title),
                        R.string.kids_bedtime_title,
                    )
                    return@setOnPreferenceChangeListener false
                }
                if (enable) {
                    val hasPin = UserPreferences.parentalControlPin.isNotBlank() ||
                        ProfileManager.activeProfile()?.pinHash != null
                    if (!hasPin) {
                        notifyUser(
                            context,
                            context.getString(R.string.kids_bedtime_needs_pin),
                            R.string.kids_bedtime_title,
                        )
                        return@setOnPreferenceChangeListener false
                    }
                }
                UserPreferences.kidsBedtimeEnabled = enable
                // KB-011: disabling clears lock + overnight bypass.
                val after = KidsBedtime.prefsAfterSetEnabled(
                    enabled = enable,
                    locked = UserPreferences.kidsBedtimeLocked,
                    bypassUntilMs = UserPreferences.kidsBedtimeBypassUntilMs,
                )
                UserPreferences.kidsBedtimeLocked = after.locked
                UserPreferences.kidsBedtimeBypassUntilMs = after.bypassUntilMs
                if (enable) {
                    notifyUser(
                        context,
                        context.getString(R.string.kids_bedtime_enabled_toast),
                        R.string.kids_bedtime_title,
                    )
                }
                refresh(findPreference, context, fragment)
                true
            }
        }

        findPreference("SPOILER_SHIELD_ENABLED")?.let { pref ->
            val switch = pref as? SwitchPreferenceCompat ?: return@let
            switch.setOnPreferenceChangeListener { _, newValue ->
                if (ProfileManager.isKidsActive()) {
                    // Kids always shields; the toggle is display-only there.
                    return@setOnPreferenceChangeListener false
                }
                UserPreferences.spoilerShieldEnabled = newValue as Boolean
                com.dskja.betterstreamflix.spoilers.SpoilerShield.clearSession()
                refresh(findPreference, context, fragment)
                true
            }
        }

        findPreference("KIDS_BEDTIME_TIME")?.setOnPreferenceClickListener {
            if (!ProfileManager.isKidsActive()) return@setOnPreferenceClickListener true
            com.dskja.betterstreamflix.kids.KidsBedtimeUi.showTimePicker(context) {
                refresh(findPreference, context, fragment)
            }
            true
        }

        findPreference("PROFILE_PIN")?.setOnPreferenceClickListener {
            showPinEditor(fragment, findPreference)
            true
        }

        findPreference("PROFILE_PIN_CLEAR")?.setOnPreferenceClickListener {
            val profile = ProfileManager.activeProfile()
            if (profile?.pinHash == null) return@setOnPreferenceClickListener true
            // Kids: clearing the profile PIN requires a parental PIN set + verified
            // in this flow so an exit PIN always remains.
            if (profile.isKids) {
                if (UserPreferences.parentalControlPin.isBlank()) {
                    notifyUser(
                        context,
                        context.getString(R.string.profile_kids_pin_clear_blocked),
                        R.string.profile_pin_title,
                    )
                    return@setOnPreferenceClickListener true
                }
                promptParentalPin(context) {
                    ProfileManager.clearPin(profile.id)
                    notifyUser(
                        context,
                        context.getString(R.string.profile_pin_cleared),
                        R.string.profile_pin_title,
                    )
                    refresh(findPreference, context, fragment)
                }
                return@setOnPreferenceClickListener true
            }
            // Adult: require the current profile PIN before clearing it.
            promptPin(context, profile) {
                ProfileManager.clearPin(profile.id)
                notifyUser(context, context.getString(R.string.profile_pin_cleared), R.string.profile_pin_title)
                refresh(findPreference, context, fragment)
            }
            true
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
            summary = context.getString(ProfileAvatarStyle.avatarFor(key).titleRes)
            val preview = context.getDrawable(R.drawable.bg_profile_avatar_preview)?.mutate()
            if (preview != null) {
                DrawableCompat.setTint(preview, ProfileAvatarStyle.colorFor(key))
                icon = preview
            }
        }

        (findPreference("PROFILE_KIDS") as? SwitchPreferenceCompat)?.isChecked = profile?.isKids == true

        (findPreference("PROFILE_MAX_AGE") as? ListPreference)?.apply {
            val kids = profile?.isKids == true
            isVisible = kids
            isEnabled = kids
            if (kids) {
                val age = profile?.maxAgeRating ?: ProfileManager.KIDS_DEFAULT_MAX_AGE
                value = age.toString()
                summary = context.getString(R.string.profile_kids_max_age_summary) + " ($age+)"
            }
        }

        val kidsProfile = profile?.isKids == true
        (findPreference("KIDS_BEDTIME_ENABLED") as? SwitchPreferenceCompat)?.apply {
            isVisible = kidsProfile
            isEnabled = kidsProfile
            isChecked = UserPreferences.kidsBedtimeEnabled
            summary = com.dskja.betterstreamflix.kids.KidsBedtimeUi.enabledSummary(context)
        }
        findPreference("KIDS_BEDTIME_TIME")?.apply {
            isVisible = kidsProfile && UserPreferences.kidsBedtimeEnabled
            isEnabled = kidsProfile && UserPreferences.kidsBedtimeEnabled
            summary = com.dskja.betterstreamflix.kids.KidsBedtimeUi.timeSummary(context)
        }
        (findPreference("SPOILER_SHIELD_ENABLED") as? SwitchPreferenceCompat)?.apply {
            if (kidsProfile) {
                isChecked = true
                isEnabled = false
                summary = context.getString(R.string.settings_spoiler_shield_summary_kids)
            } else {
                isEnabled = true
                isChecked = UserPreferences.spoilerShieldEnabled
                summary = context.getString(
                    if (UserPreferences.spoilerShieldEnabled) {
                        R.string.settings_spoiler_shield_summary_on
                    } else {
                        R.string.settings_spoiler_shield_summary_off
                    },
                )
            }
        }

        findPreference("PROFILE_PIN")?.summary = if (profile?.pinHash != null) {
            context.getString(R.string.profile_pin_set)
        } else {
            context.getString(R.string.profile_pin_not_set)
        }

        findPreference("PROFILE_PIN_CLEAR")?.isVisible = profile?.pinHash != null

        findPreference("PROFILE_INTEGRATIONS")?.summary = integrationsSummary(context, profile)

        // Year review is adult-only — hide entry on Kids / Guest (YR-001).
        findPreference("PROFILE_YEAR_REVIEW")?.isVisible =
            profile != null && !profile.isKids && !profile.isGuest

        // Parent Dashboard only exists when at least one Kids profile is present.
        findPreference("PARENT_DASHBOARD_OPEN")?.isVisible =
            ProfileManager.profiles().any { it.isKids }

        findPreference(ProfileHeroPreference.KEY)?.let { pref ->
            (pref as? ProfileHeroPreference)?.refresh()
                ?: run { pref.summary = pref.summary }
        }
    }

    private val PROFILE_INTEGRATION_ITEMS = listOf(
        UserProfile.Integration.TRAKT to R.string.profile_integration_trakt,
        UserProfile.Integration.JELLYFIN to R.string.profile_integration_jellyfin,
        UserProfile.Integration.PLEX to R.string.profile_integration_plex,
        UserProfile.Integration.DEBRID to R.string.profile_integration_debrid,
        UserProfile.Integration.SIMKL to R.string.profile_integration_simkl,
        UserProfile.Integration.OPENSUBTITLES to R.string.profile_integration_opensubtitles,
        UserProfile.Integration.TMDB to R.string.profile_integration_tmdb,
    )

    private fun integrationsSummary(context: Context, profile: UserProfile?): CharSequence {
        val enabled = profile?.enabledIntegrations.orEmpty()
            .ifEmpty { UserProfile.Integration.ALL }
        return if (enabled.containsAll(UserProfile.Integration.ALL)) {
            context.getString(R.string.profile_integrations_summary_all)
        } else if (enabled.isEmpty()) {
            context.getString(R.string.profile_integrations_summary_none)
        } else {
            val names = PROFILE_INTEGRATION_ITEMS
                .filter { it.first in enabled }
                .joinToString(" · ") { context.getString(it.second) }
            context.getString(
                R.string.profile_integrations_summary_some,
                enabled.count { it in UserProfile.Integration.ALL },
                names,
            )
        }
    }

    private fun showIntegrationsDialog(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
    ) {
        val context = fragment.requireContext()
        val profile = ProfileManager.activeProfile() ?: return
        val enabledNow = profile.enabledIntegrations.ifEmpty { UserProfile.Integration.ALL }
        val checked = BooleanArray(PROFILE_INTEGRATION_ITEMS.size) { index ->
            PROFILE_INTEGRATION_ITEMS[index].first in enabledNow
        }
        // Item label: "Trakt — Signed in" so users see the global account state
        // while deciding which integrations this profile may use.
        val labels = PROFILE_INTEGRATION_ITEMS.map { (key, nameRes) ->
            val status = com.dskja.betterstreamflix.platform.IntegrationStatus.globalOnly(key)
            context.getString(nameRes) + " · " +
                com.dskja.betterstreamflix.platform.IntegrationStatus.label(context, status)
        }.toTypedArray()
        alertBuilder(context)
            .setTitle(R.string.profile_integrations_dialog_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = PROFILE_INTEGRATION_ITEMS
                    .mapIndexedNotNull { index, (key, _) -> if (checked[index]) key else null }
                    .toSet()
                ProfileManager.setEnabledIntegrations(profile.id, selected)
                refresh(findPreference, context, fragment)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun activeSummary(context: Context, profile: UserProfile): String {
        val kids = if (profile.isKids) " · ${context.getString(R.string.profile_kids_badge_short)}" else ""
        val pin = if (profile.pinHash != null) " · ${context.getString(R.string.profile_picker_locked)}" else ""
        val avatar = context.getString(ProfileAvatarStyle.avatarFor(profile.avatarKey).titleRes)
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
        val glass = ExpDialogChrome.buildGlassMessage(
                ctx,
                fragment.getString(R.string.profile_switch_prompt, created.displayName),
            )
        val builder = alertBuilder(ctx)
        builder.setView(glass.root)
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
                    ExpDialogChrome.polishGlassMessageShown(dialog, glass)
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
        val glass = ExpDialogChrome.buildGlassMessage(context, message).also { g ->
                val density = context.resources.displayMetrics.density
                g.root.addView(
                    input,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).also { it.topMargin = (12 * density).toInt() },
                )
        }
        val builder = alertBuilder(context).setTitle(title)
        builder.setView(glass.root).setView(input)
        builder
            .setPositiveButton(android.R.string.ok) { _, _ -> onPositive() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    ExpDialogChrome.polishGlassMessageShown(dialog, glass)
                    with(ExpPressEffects) { input.applyExpPress() }
                    ExpMotion.popIn(input)

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
            ExpDialogChrome.showInfo(
                context,
                R.string.profile_delete_title,
                context.getString(R.string.profile_cannot_delete_last),
                ::alertBuilder,
            )
            return
        }

        val deleteMessage = context.getString(R.string.profile_delete_confirm, profile.displayName)
        val glass = ExpDialogChrome.buildGlassMessage(context, deleteMessage)
        val builder = alertBuilder(context)
        builder.setView(glass.root)
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
                    ExpDialogChrome.polishGlassMessageShown(dialog, glass)
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
            label.setText(ProfileAvatarStyle.avatarFor(selected).titleRes)
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
                    isFocusable = true
                    isClickable = true
                    contentDescription = context.getString(palette.titleRes)
                    foreground = context.getDrawable(R.drawable.bg_profile_avatar_select_ring)
                    with(ExpPressEffects) { applyExpPress() }
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
            androidx.appcompat.app.AlertDialog.Builder(context)
        ).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val cancel = view.findViewById<TextView>(R.id.btn_profile_avatar_cancel)
        val ok = view.findViewById<TextView>(R.id.btn_profile_avatar_ok)
        cancel.setBackgroundResource(R.drawable.bg_outlined_chip)
        ok.setBackgroundResource(R.drawable.bg_button_mobile)
        with(ExpPressEffects) {
            cancel.applyExpPress()
            ok.applyExpPress()
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
            view.setBackgroundResource(R.drawable.bg_profile_dialog)
            ExpDialogChrome.polishShown(dialog)
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
            } else {
                notifyUser(
                    context,
                    context.getString(R.string.profile_pin_invalid_format),
                    R.string.profile_pin_title,
                )
            }
        }
    }

    /**
     * When leaving a parental-locked / kids profile for one without a ceiling,
     * challenge the parental PIN (or active profile PIN) before [onAllowed].
     *
     * Also challenges kid→kid (and any leave) while the source kids profile is
     * bedtime-locked or in an active bedtime window (KF-004 / KB-005).
     *
     * Also used when disabling Kids / raising the Kids age ceiling on the active
     * profile (pass an adult stub as [target]).
     */
    fun guardParentalExit(
        fragment: Fragment,
        target: UserProfile,
        onAllowed: () -> Unit,
    ) {
        val active = ProfileManager.activeProfile()
        val leavingRestricted =
            ProfileManager.hasParentalCeiling(active) || UserPreferences.isParentalControlActive
        val targetAlsoRestricted = ProfileManager.hasParentalCeiling(target)
        val switchingAway = active != null && active.id != target.id
        KidsBedtime.refreshLockState()
        val sourceKidsBedtimeBlock = switchingAway &&
            active?.isKids == true &&
            (KidsBedtime.isLocked() || KidsBedtime.isActiveWindow())
        val needsChallenge =
            (leavingRestricted && !targetAlsoRestricted) || sourceKidsBedtimeBlock
        if (!needsChallenge) {
            onAllowed()
            return
        }
        val hasHousehold = UserPreferences.parentalControlPin.isNotBlank()
        val hasProfile = active?.pinHash != null
        if (!KidsFortressPolicy.exitChallengeRequired(
                leavingRestricted = true,
                hasHouseholdPin = hasHousehold,
                hasProfilePin = hasProfile,
            )
        ) {
            notifyUser(
                fragment.requireContext(),
                fragment.getString(R.string.profile_kids_exit_requires_pin),
                R.string.profile_kids_title,
            )
            return
        }
        challengeKidsUnlock(fragment.requireContext(), onAllowed)
    }

    /**
     * Challenge parental PIN (preferred) or active profile PIN before clearing Kids /
     * raising the age ceiling / otherwise unlocking adult catalog access.
     */
    fun challengeKidsUnlock(context: Context, onVerified: () -> Unit) {
        val active = ProfileManager.activeProfile()
        val hasHousehold = UserPreferences.parentalControlPin.isNotBlank()
        val hasProfile = active?.pinHash != null
        val restricted = active?.isKids == true || ProfileManager.hasParentalCeiling(active)
        if (restricted &&
            !KidsFortressPolicy.kidsUnlockAllowed(hasHousehold, hasProfile)
        ) {
            notifyUser(
                context,
                context.getString(R.string.profile_kids_exit_requires_pin),
                R.string.profile_kids_title,
            )
            return
        }
        if (hasHousehold) {
            promptParentalPin(context) { onVerified() }
            return
        }
        if (active?.pinHash != null) {
            promptPin(context, active) { onVerified() }
            return
        }
        onVerified()
    }

    private fun promptParentalPin(context: Context, onVerified: () -> Unit) {
        if (UserPreferences.parentalControlPin.isBlank()) {
            onVerified()
            return
        }
        // Leanback: reuse the profile PIN dialog (focusable OK/Cancel) instead of a bare EditText.
        if (DeviceCapabilities.isLeanbackDevice(context) || DeviceCapabilities.isAmazonFireTv(context)) {
            showLeanbackPinDialog(
                context = context,
                title = context.getString(R.string.settings_parental_enter_current_pin_title),
                avatarKey = ProfileManager.activeProfile()?.avatarKey,
                displayName = ProfileManager.activeProfile()?.displayName,
            ) { entered ->
                if (UserPreferences.verifyParentalControlPin(entered)) {
                    UserPreferences.registerParentalPinSuccess()
                    onVerified()
                    true
                } else {
                    UserPreferences.registerParentalPinFailure()
                    notifyUser(
                        context,
                        context.getString(R.string.settings_parental_invalid_pin),
                        R.string.settings_parental_pin_title,
                    )
                    false
                }
            }
            return
        }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = context.getString(R.string.settings_parental_pin_hint)
            styleExpInput(this, context)
        }
        showGlassInputDialog(
            context = context,
            titleRes = R.string.settings_parental_enter_current_pin_title,
            message = context.getString(R.string.settings_parental_enter_current_pin_message),
            input = input,
        ) {
            val entered = input.text?.toString()?.trim().orEmpty()
            if (UserPreferences.verifyParentalControlPin(entered)) {
                UserPreferences.registerParentalPinSuccess()
                onVerified()
            } else {
                UserPreferences.registerParentalPinFailure()
                notifyUser(
                    context,
                    context.getString(R.string.settings_parental_invalid_pin),
                    R.string.settings_parental_pin_title,
                )
            }
        }
    }

    private fun promptPin(context: Context, profile: UserProfile, onVerified: () -> Unit) {
        if (DeviceCapabilities.isLeanbackDevice(context) || DeviceCapabilities.isAmazonFireTv(context)) {
            showLeanbackPinDialog(
                context = context,
                title = context.getString(R.string.profile_pin_enter, profile.displayName),
                avatarKey = profile.avatarKey,
                displayName = profile.displayName,
            ) { pin ->
                if (ProfileManager.verifyPin(profile.id, pin)) {
                    onVerified()
                    true
                } else {
                    notifyUser(context, context.getString(R.string.profile_pin_invalid), R.string.profile_pin_title)
                    false
                }
            }
            return
        }
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

    /**
     * DPAD-friendly PIN challenge using [R.layout.dialog_profile_pin] (same chrome as Who's Watching).
     * [onSubmit] returns true to dismiss, false to keep the dialog open after a wrong PIN.
     */
    private fun showLeanbackPinDialog(
        context: Context,
        title: String,
        avatarKey: String?,
        displayName: String?,
        onSubmit: (String) -> Boolean,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_profile_pin, null, false)
        view.findViewById<ProfileAvatarView>(R.id.pav_profile_pin_avatar)?.bind(
            avatarKey = avatarKey ?: ProfileManager.avatarKeys.first(),
            displayName = displayName.orEmpty(),
            textSizeSp = 22f,
        )
        view.findViewById<TextView>(R.id.tv_profile_pin_title)?.text = title
        val input = view.findViewById<EditText>(R.id.et_profile_pin)
        val dialog = alertBuilder(context).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        view.findViewById<TextView>(R.id.btn_profile_pin_cancel)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.btn_profile_pin_ok)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            val pin = input?.text?.toString()?.trim().orEmpty()
            if (onSubmit(pin)) dialog.dismiss()
        }
        dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
        dialog.show()
        input?.requestFocus()
    }

    private fun performSwitch(
        fragment: Fragment,
        findPreference: (String) -> Preference?,
        target: UserProfile,
        onProfileSwitched: (() -> Unit)?,
    ) {
        guardParentalExit(fragment, target) {
            val context = fragment.requireContext()
            if (!ProfileManager.switchTo(context, target.id)) {
                if (target.isKids) {
                    notifyUser(
                        context,
                        context.getString(R.string.profile_kids_exit_requires_pin),
                        R.string.profile_kids_title,
                    )
                }
                return@guardParentalExit
            }
            notifyUser(
                context,
                context.getString(R.string.profile_switched_toast, target.displayName),
            )
            refresh(findPreference, context, fragment)
            onProfileSwitched?.invoke()
        }
    }
}
