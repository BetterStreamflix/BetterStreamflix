package com.dskja.betterstreamflix.profiles

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpMotion

/** Full-screen lounge profile switcher with optional PIN keypad. */
class ProfilePickerDialog : DialogFragment() {

    var onProfileSwitched: (() -> Unit)? = null
    var onManageProfiles: (() -> Unit)? = null
    var onCreateProfile: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(requireContext().getColor(R.color.profile_bg)))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        isCancelable = true
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_profile_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ExpMotion.enterScreen(view)
        val active = ProfileManager.activeProfile()
        view.findViewById<View>(R.id.profile_picker_root)
            ?.setBackgroundResource(ProfileAtmosphere.specFor(active?.atmosphereKey).backgroundRes)
        view.findViewById<TextView>(R.id.tv_profile_picker_greeting)?.text =
            ProfileGreeting.line(requireContext(), active)
        if (ExperimentalMobileDesign.enabled()) {
            ExperimentalMobileDesign.applyReducedGlass(view)
            view.findViewById<TextView>(R.id.btn_profile_picker_create)
                ?.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
            view.findViewById<TextView>(R.id.btn_profile_picker_manage)
                ?.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            val primary = com.google.android.material.color.MaterialColors.getColor(
                requireContext(),
                androidx.appcompat.R.attr.colorPrimary,
                requireContext().getColor(R.color.m3_primary),
            )
            view.findViewById<TextView>(R.id.tv_profile_picker_brand)?.setTextColor(primary)
            ExpMotion.popIn(view.findViewById(R.id.btn_profile_picker_create))
            ExpMotion.popIn(view.findViewById(R.id.btn_profile_picker_manage))
        }
        bindProfiles(view)
        view.findViewById<View>(R.id.btn_profile_picker_close)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            dismissAllowingStateLoss()
        }
        view.findViewById<TextView>(R.id.btn_profile_picker_create).setOnClickListener {
            ExpMotion.hapticTap(it)
            dismissAllowingStateLoss()
            onCreateProfile?.invoke()
        }
        view.findViewById<TextView>(R.id.btn_profile_picker_manage).setOnClickListener {
            ExpMotion.hapticTap(it)
            dismissAllowingStateLoss()
            onManageProfiles?.invoke()
        }
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
            view.findViewById<View>(R.id.btn_profile_picker_create)?.applyExpPress()
            view.findViewById<View>(R.id.btn_profile_picker_manage)?.applyExpPress()
        }
        ExpMotion.revealHeader(
            view.findViewById(R.id.tv_profile_picker_brand),
            view.findViewById(R.id.tv_profile_picker_greeting),
            view.findViewById(R.id.tv_profile_picker_title),
            view.findViewById(R.id.tv_profile_picker_subtitle),
            view.findViewById(R.id.v_profile_picker_rule),
        )
        ExpMotion.pulseAccentRule(view.findViewById(R.id.v_profile_picker_rule))
        ExpMotion.startAnimation(view.findViewById(R.id.sv_profile_picker), R.anim.support_fade_slide_up)
    }

    private fun bindProfiles(root: View) {
        val column = root.findViewById<LinearLayout>(R.id.ll_profile_picker_row)
        column.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val profiles = ProfileManager.profilesForPicker()
        val activeId = ProfileManager.activeProfileId
        val density = resources.displayMetrics.density

        if (profiles.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = getString(R.string.profile_create_subtitle)
                gravity = android.view.Gravity.CENTER
                setTextColor(context.getColor(R.color.profile_text_secondary))
                textSize = 14f
                setPadding(16, 24, 16, 24)
            }
            column.addView(empty)
            return
        }

        profiles.forEachIndexed { index, profile ->
            val item = inflater.inflate(R.layout.item_profile_picker, column, false)
            item.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also {
                if (index > 0) it.topMargin = (10 * density).toInt()
            }
            bindProfileItem(item, profile, activeId)
            item.alpha = 0f
            item.translationY = 16f * density
            item.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(36L * index)
                .setDuration(280L)
                .start()
            column.addView(item)
        }
    }

    private fun bindProfileItem(item: View, profile: UserProfile, activeId: String) {
        item.findViewById<ProfileAvatarView>(R.id.pav_profile_avatar)
            .bind(profile, textSizeSp = 22f)
        item.findViewById<TextView>(R.id.tv_profile_name).text = profile.displayName
        item.findViewById<View>(R.id.v_profile_active_ring).apply {
            val wasVisible = visibility == View.VISIBLE
            visibility = if (profile.id == activeId) View.VISIBLE else View.GONE
            if (ExperimentalMobileDesign.enabled() && visibility == View.VISIBLE) {
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    this,
                    androidx.appcompat.R.attr.colorPrimary,
                    context.getColor(R.color.m3_primary),
                )
                background?.mutate()?.setTint(primary)
            }
            if (visibility == View.VISIBLE && !wasVisible) ExpMotion.popIn(this)
        }
        item.findViewById<TextView>(R.id.tv_profile_kids_badge).apply {
            val wasVisible = visibility == View.VISIBLE
            visibility = if (profile.isKids) View.VISIBLE else View.GONE
            if (ExperimentalMobileDesign.enabled() && visibility == View.VISIBLE) {
                val primary = com.google.android.material.color.MaterialColors.getColor(
                    this,
                    androidx.appcompat.R.attr.colorPrimary,
                    context.getColor(R.color.m3_primary),
                )
                setTextColor(primary)
            }
            if (visibility == View.VISIBLE && !wasVisible) ExpMotion.popIn(this)
        }
        item.findViewById<TextView>(R.id.tv_profile_lock).apply {
            val wasVisible = visibility == View.VISIBLE
            visibility = if (profile.pinHash != null) View.VISIBLE else View.GONE
            if (visibility == View.VISIBLE && !wasVisible) ExpMotion.popIn(this)
        }

        val paletteTitle = getString(ProfileAvatarStyle.paletteFor(profile.avatarKey).titleRes)
        val stats = ProfileLibraryStats.forProfile(profile.id)
        val extras = buildList {
            if (profile.id == activeId) add(getString(R.string.profile_picker_active))
            add(paletteTitle)
        }
        item.findViewById<TextView>(R.id.tv_profile_meta).text =
            ProfileLibraryStats.formatMeta(requireContext(), stats, extras)

        item.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (profile.id == activeId) {
                dismissAllowingStateLoss()
                return@setOnClickListener
            }
            if (profile.pinHash != null) {
                showPinDialog(profile) { completeSwitch(profile) }
            } else {
                completeSwitch(profile)
            }
        }
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { item.applyExpPress() }
    }

    private fun showPinDialog(profile: UserProfile, onVerified: () -> Unit) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_profile_pin, null, false)
        view.findViewById<ProfileAvatarView>(R.id.pav_profile_pin_avatar)
            .bind(profile, textSizeSp = 22f)
        view.findViewById<TextView>(R.id.tv_profile_pin_title).text =
            getString(R.string.profile_pin_enter, profile.displayName)
        val lock = view.findViewById<TextView>(R.id.tv_profile_pin_lock)
        fun refreshLock() {
            if (ProfileManager.isPinLocked(profile.id)) {
                val seconds = ((ProfileManager.pinLockRemainingMillis(profile.id) + 999) / 1000).toInt()
                lock.visibility = View.VISIBLE
                lock.text = getString(R.string.profile_pin_locked, seconds)
            } else {
                lock.visibility = View.GONE
            }
        }
        refreshLock()

        val dialog = (
            if (ExperimentalMobileDesign.enabled()) MaterialAlertDialogBuilder(context)
            else AlertDialog.Builder(context)
        )
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        ProfilePinPad.bind(
            root = view,
            onSubmit = { pin ->
                if (ProfileManager.isPinLocked(profile.id)) {
                    refreshLock()
                    ExpDialogChrome.notify(context, R.string.profile_pin_locked_short, R.string.profile_pin_title)
                    false
                } else if (ProfileManager.verifyPin(profile.id, pin)) {
                    dialog.dismiss()
                    onVerified()
                    true
                } else {
                    refreshLock()
                    ExpDialogChrome.notify(context, R.string.profile_pin_invalid, R.string.profile_pin_title)
                    false
                }
            },
            onCancel = { dialog.dismiss() },
        )
        dialog.setOnShowListener {
            if (ExperimentalMobileDesign.enabled()) {
                ExperimentalMobileDesign.applyReducedGlass(view)
                view.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
                ExpMotion.enterScreen(view)
                ExpDialogChrome.polishShown(dialog)
                ExpMotion.revealHeader(
                    view.findViewById(R.id.pav_profile_pin_avatar),
                    view.findViewById(R.id.tv_profile_pin_title),
                    view.findViewById(R.id.v_profile_pin_rule),
                )
                ExpMotion.pulseAccentRule(view.findViewById(R.id.v_profile_pin_rule))
            }
        }
        dialog.show()
    }

    private fun completeSwitch(profile: UserProfile) {
        val context = requireContext()
        if (!ProfileManager.switchTo(context, profile.id)) return
        ExpDialogChrome.notify(
            context,
            getString(R.string.profile_switched_toast, profile.displayName),
            R.string.profile_switch_title,
        )
        dismissAllowingStateLoss()
        onProfileSwitched?.invoke()
    }

    companion object {
        const val TAG = "ProfilePickerDialog"

        fun show(
            fragment: Fragment,
            onSwitched: (() -> Unit)? = null,
            onManage: (() -> Unit)? = null,
            onCreate: (() -> Unit)? = null,
        ) {
            if (!fragment.isAdded || fragment.childFragmentManager.isStateSaved) return
            val existing = fragment.childFragmentManager.findFragmentByTag(TAG)
            if (existing is DialogFragment) {
                if (existing.dialog?.isShowing == true) return
                existing.dismissAllowingStateLoss()
            }
            ProfilePickerDialog().apply {
                onProfileSwitched = onSwitched
                onManageProfiles = onManage
                onCreateProfile = onCreate
            }.show(fragment.childFragmentManager, TAG)
        }

        fun showCreateDialog(
            fragment: Fragment,
            onCreated: ((UserProfile) -> Unit)? = null,
        ) {
            val context = fragment.requireContext()
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_profile_create, null, false)
            val nameInput = view.findViewById<EditText>(R.id.et_profile_create_name)
            val greetingInput = view.findViewById<EditText>(R.id.et_profile_create_greeting)
            val kidsCheck = view.findViewById<CheckBox>(R.id.cb_profile_create_kids)
            val avatarRow = view.findViewById<LinearLayout>(R.id.ll_profile_create_avatars)
            val avatarLabel = view.findViewById<TextView>(R.id.tv_profile_create_avatar_label)
            val atmosphereRow = view.findViewById<LinearLayout>(R.id.ll_profile_create_atmospheres)
            val atmosphereLabel = view.findViewById<TextView>(R.id.tv_profile_create_atmosphere_label)

            val used = ProfileManager.profiles().map { it.avatarKey }.toSet()
            var selected = ProfileManager.avatarKeys.firstOrNull { it !in used }
                ?: ProfileManager.avatarKeys.first()
            var atmosphere = ProfileAtmosphere.DEFAULT

            fun refreshAvatarRow() {
                avatarRow.removeAllViews()
                val density = context.resources.displayMetrics.density
                ProfileAvatarStyle.all().forEach { palette ->
                    val orb = ProfileAvatarView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (52 * density).toInt(),
                            (52 * density).toInt(),
                        ).also {
                            val m = (8 * density).toInt()
                            it.setMargins(0, 0, m, 0)
                        }
                        bind(palette.key, ProfileAvatarStyle.initialFor(palette.key), textSizeSp = 14f)
                        isSelected = palette.key == selected
                        foreground = context.getDrawable(R.drawable.bg_profile_avatar_select_ring)
                        if (ExperimentalMobileDesign.enabled()) {
                            with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
                        }
                        setOnClickListener {
                            com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(it)
                            selected = palette.key
                            refreshAvatarRow()
                        }
                    }
                    avatarRow.addView(orb)
                }
                avatarLabel.setText(ProfileAvatarStyle.paletteFor(selected).titleRes)
            }

            fun refreshAtmosphereRow() {
                atmosphereRow ?: return
                atmosphereRow.removeAllViews()
                val density = context.resources.displayMetrics.density
                ProfileAtmosphere.all().forEach { spec ->
                    val chip = TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            (36 * density).toInt(),
                        ).also {
                            it.marginEnd = (8 * density).toInt()
                        }
                        text = context.getString(spec.titleRes)
                        gravity = android.view.Gravity.CENTER
                        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                        setTextAppearance(R.style.TextAppearance_Nocturne_Caption)
                        setTextColor(
                            context.getColor(
                                if (spec.key == atmosphere) R.color.profile_on_accent
                                else R.color.profile_text_primary,
                            ),
                        )
                        setBackgroundResource(
                            if (spec.key == atmosphere) R.drawable.bg_profile_btn_primary
                            else R.drawable.bg_profile_btn_secondary,
                        )
                        setOnClickListener {
                            ExpMotion.hapticTap(it)
                            atmosphere = spec.key
                            refreshAtmosphereRow()
                        }
                    }
                    atmosphereRow.addView(chip)
                }
                atmosphereLabel?.setText(ProfileAtmosphere.specFor(atmosphere).titleRes)
            }

            refreshAvatarRow()
            refreshAtmosphereRow()

            val dialog = (
                if (ExperimentalMobileDesign.enabled()) MaterialAlertDialogBuilder(context)
                else AlertDialog.Builder(context)
            ).setView(view).create()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val cancel = view.findViewById<TextView>(R.id.btn_profile_create_cancel)
            val ok = view.findViewById<TextView>(R.id.btn_profile_create_ok)
            if (ExperimentalMobileDesign.enabled()) {
                with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
                    cancel.applyExpPress()
                    ok.applyExpPress()
                    kidsCheck.applyExpPress()
                    nameInput.applyExpPress()
                    greetingInput?.applyExpPress()
                }
                cancel.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                ok.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
            }
            cancel.setOnClickListener {
                com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(it)
                dialog.dismiss()
            }
            ok.setOnClickListener {
                com.dskja.betterstreamflix.utils.ExpMotion.hapticTap(it)
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    ExpDialogChrome.notify(context, R.string.profile_name_empty, R.string.profile_rename_title)
                    return@setOnClickListener
                }
                val created = ProfileManager.create(
                    name = name,
                    isKids = kidsCheck.isChecked,
                    avatarKey = selected,
                    atmosphereKey = atmosphere,
                    greetingName = greetingInput?.text?.toString(),
                )
                ExpDialogChrome.notify(
                    context,
                    context.getString(R.string.profile_created_toast, created.displayName),
                    R.string.profile_switch_title,
                )
                dialog.dismiss()
                onCreated?.invoke(created)
            }
            dialog.setOnShowListener {
                if (ExperimentalMobileDesign.enabled()) {
                    ExperimentalMobileDesign.applyReducedGlass(view)
                    view.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
                    ExpDialogChrome.polishShown(dialog)
                    nameInput.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                    greetingInput?.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                    com.dskja.betterstreamflix.utils.ExpMotion.revealHeader(
                        view.findViewById(R.id.tv_profile_create_title),
                        view.findViewById(R.id.v_profile_create_rule),
                        avatarLabel,
                    )
                    ExpMotion.pulseAccentRule(view.findViewById(R.id.v_profile_create_rule))
                }
            }
            dialog.show()
            nameInput.requestFocus()
        }
    }
}
