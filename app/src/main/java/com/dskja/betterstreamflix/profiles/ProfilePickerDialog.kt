package com.dskja.betterstreamflix.profiles

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpMotion
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Centered profile switcher popup with avatar orbs and optional PIN gate. */
class ProfilePickerDialog : DialogFragment() {

    var onProfileSwitched: (() -> Unit)? = null
    var onManageProfiles: (() -> Unit)? = null
    var onCreateProfile: (() -> Unit)? = null

    private var manageMode = false
    private var startInManageMode = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), R.style.ProfilePickerDialogTheme)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setDimAmount(0.65f)
        }
        isCancelable = true
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
    }

    private fun capCardHeight(view: View) {
        val scroll = view.findViewById<View>(R.id.sv_profile_picker) ?: return
        val maxHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        scroll.post {
            if (scroll.height > maxHeight) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
                scroll.requestLayout()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_profile_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (startInManageMode) manageMode = true
        ExpMotion.enterScreen(view)
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
        applyManageChrome(view)
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
            enterManageMode(!manageMode)
        }
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
            view.findViewById<View>(R.id.btn_profile_picker_create)?.applyExpPress()
            view.findViewById<View>(R.id.btn_profile_picker_manage)?.applyExpPress()
        }
        ExpMotion.revealHeader(
            view.findViewById(R.id.tv_profile_picker_brand),
            view.findViewById(R.id.tv_profile_picker_title),
            view.findViewById(R.id.tv_profile_picker_subtitle),
            view.findViewById(R.id.v_profile_picker_rule),
        )
        ExpMotion.pulseAccentRule(view.findViewById(R.id.v_profile_picker_rule))
        ExpMotion.startAnimation(view.findViewById(R.id.sv_profile_picker), R.anim.support_fade_slide_up)
        capCardHeight(view)
    }

    fun enterManageMode(enabled: Boolean = true) {
        manageMode = enabled
        view?.let {
            applyManageChrome(it)
            bindProfiles(it)
        }
    }

    private fun applyManageChrome(root: View) {
        root.findViewById<TextView>(R.id.btn_profile_picker_manage)?.text =
            getString(if (manageMode) R.string.profile_done else R.string.profile_edit_profiles)
        root.findViewById<TextView>(R.id.tv_profile_picker_title)?.text =
            getString(
                if (manageMode) R.string.profile_manage_title else R.string.profile_picker_title,
            )
    }

    private fun bindProfiles(root: View) {
        val column = root.findViewById<LinearLayout>(R.id.ll_profile_picker_row)
        column.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val profiles = ProfileManager.profiles()
        val activeId = ProfileManager.activeProfileId
        val density = resources.displayMetrics.density

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        profiles.forEachIndexed { index, profile ->
            val item = inflater.inflate(R.layout.item_profile_picker, row, false)
            item.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            bindProfileItem(item, profile, activeId)
            item.alpha = 0f
            item.translationY = 18f * density
            item.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(40L * index)
                .setDuration(320L)
                .start()
            row.addView(item)
        }
        val add = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(
                (10 * density).toInt(),
                (8 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
            )
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (104 * density).toInt(),
                    (104 * density).toInt(),
                )
                gravity = android.view.Gravity.CENTER
                text = "+"
                textSize = 40f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_profile_add_orb)
            })
            addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = (12 * density).toInt() }
                text = getString(R.string.profile_add_profile)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            setOnClickListener {
                ExpMotion.hapticTap(it)
                if (manageMode) {
                    showCreateDialog(this@ProfilePickerDialog) {
                        view?.let(::bindProfiles)
                    }
                } else {
                    dismissAllowingStateLoss()
                    onCreateProfile?.invoke()
                }
            }
        }
        row.addView(add)
        column.addView(row)
    }

    private fun bindProfileItem(item: View, profile: UserProfile, activeId: String) {
        item.findViewById<ProfileAvatarView>(R.id.pav_profile_avatar)
            .bind(profile, textSizeSp = 30f)
        item.findViewById<TextView>(R.id.tv_profile_name).text = profile.displayName
        item.findViewById<View>(R.id.v_profile_active_ring).apply {
            val wasVisible = visibility == View.VISIBLE
            visibility = if (!manageMode && profile.id == activeId) View.VISIBLE else View.GONE
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
        item.findViewById<TextView>(R.id.tv_profile_kids_badge).visibility = View.GONE
        item.findViewById<TextView>(R.id.tv_profile_meta).visibility = View.GONE
        item.findViewById<TextView>(R.id.tv_profile_lock).apply {
            visibility = if (!manageMode && profile.pinHash != null) View.VISIBLE else View.GONE
        }
        item.findViewById<ImageView>(R.id.iv_profile_edit_badge).apply {
            visibility = if (manageMode) View.VISIBLE else View.GONE
            if (manageMode) ExpMotion.popIn(this)
        }

        item.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (manageMode) {
                showEditProfileDialog(profile)
                return@setOnClickListener
            }
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

    private fun showEditProfileDialog(profile: UserProfile) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_profile_create, null, false)
        view.findViewById<TextView>(R.id.tv_profile_create_title)
            ?.setText(R.string.profile_edit_title)
        val nameInput = view.findViewById<EditText>(R.id.et_profile_create_name).apply {
            setText(profile.displayName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelection(text?.length ?: 0)
        }
        view.findViewById<CheckBox>(R.id.cb_profile_create_kids)?.visibility = View.GONE
        val avatarRow = view.findViewById<LinearLayout>(R.id.ll_profile_create_avatars)
        val avatarLabel = view.findViewById<TextView>(R.id.tv_profile_create_avatar_label)
        var selected = profile.avatarKey

        fun refreshAvatarRow() {
            avatarRow.removeAllViews()
            val density = context.resources.displayMetrics.density
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
                        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
                    }
                    setOnClickListener {
                        ExpMotion.hapticTap(it)
                        selected = palette.key
                        refreshAvatarRow()
                    }
                }
                avatarRow.addView(orb)
            }
            avatarLabel.setText(ProfileAvatarStyle.paletteFor(selected).titleRes)
        }
        refreshAvatarRow()

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
                nameInput.applyExpPress()
            }
            cancel.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
            ok.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
        }
        cancel.setOnClickListener {
            ExpMotion.hapticTap(it)
            dialog.dismiss()
        }
        ok.setOnClickListener {
            ExpMotion.hapticTap(it)
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                ExpDialogChrome.notify(context, R.string.profile_name_empty, R.string.profile_rename_title)
                return@setOnClickListener
            }
            ProfileManager.rename(profile.id, name)
            ProfileManager.updateAvatar(profile.id, selected)
            dialog.dismiss()
            this.view?.let(::bindProfiles)
        }
        dialog.setOnShowListener {
            if (ExperimentalMobileDesign.enabled()) {
                ExperimentalMobileDesign.applyReducedGlass(view)
                view.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
                ExpDialogChrome.polishShown(dialog)
                nameInput.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                ExpMotion.revealHeader(
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

    private fun showPinDialog(profile: UserProfile, onVerified: () -> Unit) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_profile_pin, null, false)
        view.findViewById<ProfileAvatarView>(R.id.pav_profile_pin_avatar)
            .bind(profile, textSizeSp = 22f)
        view.findViewById<TextView>(R.id.tv_profile_pin_title).text =
            getString(R.string.profile_pin_enter, profile.displayName)
        val input = view.findViewById<EditText>(R.id.et_profile_pin)

        val dialog = (
            if (ExperimentalMobileDesign.enabled()) MaterialAlertDialogBuilder(context)
            else AlertDialog.Builder(context)
        ).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<TextView>(R.id.btn_profile_pin_cancel).setOnClickListener {
            ExpMotion.hapticTap(it)
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.btn_profile_pin_ok).setOnClickListener {
            ExpMotion.hapticTap(it)
            val pin = input.text?.toString()?.trim().orEmpty()
            if (ProfileManager.verifyPin(profile.id, pin)) {
                dialog.dismiss()
                onVerified()
            } else {
                ExpDialogChrome.notify(context, R.string.profile_pin_invalid, R.string.profile_pin_title)
                input.animate().translationX(12f).setDuration(50)
                    .withEndAction {
                        input.animate().translationX(-12f).setDuration(50)
                            .withEndAction { input.animate().translationX(0f).setDuration(50).start() }
                            .start()
                    }.start()
            }
        }
        dialog.setOnShowListener {
            if (ExperimentalMobileDesign.enabled()) {
                ExperimentalMobileDesign.applyReducedGlass(view)
                view.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
                ExpMotion.enterScreen(view)
                ExpDialogChrome.polishShown(dialog)
                input.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
                view.findViewById<TextView>(R.id.btn_profile_pin_cancel)
                    ?.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                view.findViewById<TextView>(R.id.btn_profile_pin_ok)
                    ?.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
                ExpMotion.revealHeader(
                    view.findViewById(R.id.pav_profile_pin_avatar),
                    view.findViewById(R.id.tv_profile_pin_title),
                    view.findViewById(R.id.v_profile_pin_rule),
                )
                ExpMotion.pulseAccentRule(view.findViewById(R.id.v_profile_pin_rule))
            }
        }
        dialog.show()
        input.requestFocus()
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
            startInManageMode: Boolean = false,
        ) {
            if (!fragment.isAdded || fragment.childFragmentManager.isStateSaved) return
            val existing = fragment.childFragmentManager.findFragmentByTag(TAG)
            if (existing is ProfilePickerDialog) {
                if (existing.dialog?.isShowing == true) {
                    if (startInManageMode) existing.enterManageMode(true)
                    return
                }
                existing.dismissAllowingStateLoss()
            } else if (existing is DialogFragment) {
                if (existing.dialog?.isShowing == true) return
                existing.dismissAllowingStateLoss()
            }
            ProfilePickerDialog().apply {
                onProfileSwitched = onSwitched
                onManageProfiles = onManage
                onCreateProfile = onCreate
                this.startInManageMode = startInManageMode
            }.show(fragment.childFragmentManager, TAG)
        }

        fun showCreateDialog(
            fragment: Fragment,
            onCreated: ((UserProfile) -> Unit)? = null,
        ) {
            val context = fragment.requireContext()
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_profile_create, null, false)
            val nameInput = view.findViewById<EditText>(R.id.et_profile_create_name)
            val kidsCheck = view.findViewById<CheckBox>(R.id.cb_profile_create_kids)
            val avatarRow = view.findViewById<LinearLayout>(R.id.ll_profile_create_avatars)
            val avatarLabel = view.findViewById<TextView>(R.id.tv_profile_create_avatar_label)

            val used = ProfileManager.profiles().map { it.avatarKey }.toSet()
            var selected = ProfileManager.avatarKeys.firstOrNull { it !in used }
                ?: ProfileManager.avatarKeys.first()

            fun refreshAvatarRow() {
                avatarRow.removeAllViews()
                val density = context.resources.displayMetrics.density
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
                            with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
                        }
                        setOnClickListener {
                            ExpMotion.hapticTap(it)
                            selected = palette.key
                            refreshAvatarRow()
                        }
                    }
                    avatarRow.addView(orb)
                }
                avatarLabel.setText(ProfileAvatarStyle.paletteFor(selected).titleRes)
            }
            refreshAvatarRow()

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
                }
                cancel.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
                ok.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
            }
            cancel.setOnClickListener {
                ExpMotion.hapticTap(it)
                dialog.dismiss()
            }
            ok.setOnClickListener {
                ExpMotion.hapticTap(it)
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    ExpDialogChrome.notify(context, R.string.profile_name_empty, R.string.profile_rename_title)
                    return@setOnClickListener
                }
                val created = ProfileManager.create(
                    name = name,
                    isKids = false,
                    avatarKey = selected,
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
                    ExpMotion.revealHeader(
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
