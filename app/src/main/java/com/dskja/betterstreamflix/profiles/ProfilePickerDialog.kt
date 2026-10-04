package com.dskja.betterstreamflix.profiles

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.fragments.settings.ProfilesSettingsController
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
    /** When true, the picker cannot be dismissed until a profile is unlocked. */
    private var requireUnlock = false
    private var editorVisible = false
    private var backCallback: OnBackPressedCallback? = null

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
        isCancelable = !requireUnlock
        dialog.setCanceledOnTouchOutside(!requireUnlock)
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
            if (editorVisible) {
                restorePickerView()
            } else if (!requireUnlock) {
                dismissAllowingStateLoss()
            }
        }
        view.findViewById<View>(R.id.btn_profile_picker_close)?.visibility =
            if (requireUnlock) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.btn_profile_picker_create).setOnClickListener {
            ExpMotion.hapticTap(it)
            showCreateDialog()
        }
        view.findViewById<TextView>(R.id.btn_profile_picker_manage).setOnClickListener {
            ExpMotion.hapticTap(it)
            if (editorVisible) {
                restorePickerView()
                return@setOnClickListener
            }
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

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                restorePickerView()
            }
        }.also { callback ->
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        }
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

    /**
     * Inflate picker rows with [ProfilePickerDialogTheme], not the host Activity theme.
     * TV ([AppTheme.Tv] / Leanback) lacks AppCompat ripples / some Material attrs →
     * InflateException (Sentry BETTERSTREAMFLIX-1N).
     */
    private fun pickerInflater(): LayoutInflater {
        val base = dialog?.context ?: requireContext()
        val themed = androidx.appcompat.view.ContextThemeWrapper(
            base,
            R.style.ProfilePickerDialogTheme,
        )
        return LayoutInflater.from(themed)
    }

    private fun bindProfiles(root: View) {
        val column = root.findViewById<LinearLayout>(R.id.ll_profile_picker_row)
        column.removeAllViews()
        val inflater = pickerInflater()
        val profiles = ProfileManager.profiles()
        val activeId = ProfileManager.activeProfileId
        val density = resources.displayMetrics.density

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        var focusTarget: View? = null
        profiles.forEachIndexed { index, profile ->
            val item = runCatching {
                inflater.inflate(R.layout.item_profile_picker, row, false)
            }.getOrElse {
                // Last-resort: Activity inflater after theme attrs were backfilled on AppTheme.Tv.
                LayoutInflater.from(requireContext()).inflate(R.layout.item_profile_picker, row, false)
            }
            item.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            bindProfileItem(item, profile, activeId)
            if (profile.id == activeId) focusTarget = item
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
            isFocusable = true
            isClickable = true
            contentDescription = getString(R.string.profile_add_profile)
            setOnClickListener {
                ExpMotion.hapticTap(it)
                showCreateDialog()
            }
        }
        row.addView(add)
        val scroller = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(row)
        }
        scroller.post {
            val extra = scroller.width - row.width
            if (extra > 0) {
                row.setPadding(extra / 2, 0, extra / 2, 0)
            }
        }
        column.addView(scroller)
        (focusTarget ?: row.getChildAt(0))?.post {
            (focusTarget ?: row.getChildAt(0))?.requestFocus()
        }
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
        item.findViewById<TextView>(R.id.tv_profile_kids_badge).apply {
            visibility = if (profile.isKids) View.VISIBLE else View.GONE
            if (profile.isKids) {
                text = context.getString(R.string.profile_kids_badge_short)
            }
        }
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
            if (profile.id == activeId && !requireUnlock) {
                dismissAllowingStateLoss()
                return@setOnClickListener
            }
            fun proceedWithTargetPin() {
                if (profile.pinHash != null) {
                    showPinDialog(profile) { completeSwitch(profile) }
                } else {
                    completeSwitch(profile)
                }
            }
            if (requireUnlock && profile.id == activeId) {
                proceedWithTargetPin()
                return@setOnClickListener
            }
            ProfilesSettingsController.guardParentalExit(this, profile) {
                proceedWithTargetPin()
            }
        }
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { item.applyExpPress() }
    }

    /** In-screen create form inside Who's Watching (not a floating AlertDialog). */
    fun showCreateDialog() {
        val root = view ?: return
        val editor = root.findViewById<FrameLayout>(R.id.fl_profile_editor) ?: return
        val form = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_profile_create, editor, false)
        val nameInput = form.findViewById<EditText>(R.id.et_profile_create_name)
        val kidsCheck = form.findViewById<CheckBox>(R.id.cb_profile_create_kids)
        val pinInput = form.findViewById<EditText>(R.id.et_profile_create_pin)
        val avatarRow = form.findViewById<LinearLayout>(R.id.ll_profile_create_avatars)
        val avatarLabel = form.findViewById<TextView>(R.id.tv_profile_create_avatar_label)
        val avatarPreview = form.findViewById<ProfileAvatarView>(R.id.pav_profile_create_preview)

        wireAdvancedSection(form, profile = null)

        val used = ProfileManager.profiles().map { it.avatarKey }.toSet()
        var selected = ProfileAvatarStyle.featured().firstOrNull { it.key !in used }?.key
            ?: ProfileAvatarStyle.featured().first().key

        fun refreshAvatarRow() {
            bindAvatarRow(
                context = requireContext(),
                row = avatarRow,
                label = avatarLabel,
                preview = avatarPreview,
                previewName = nameInput.text?.toString().orEmpty(),
                selectedKey = selected,
                onSelect = { key ->
                    selected = key
                    refreshAvatarRow()
                },
            )
        }
        refreshAvatarRow()
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                avatarPreview?.bind(selected, s?.toString().orEmpty(), textSizeSp = 28f)
            }
        })

        val cancel = form.findViewById<TextView>(R.id.btn_profile_create_cancel)
        val ok = form.findViewById<TextView>(R.id.btn_profile_create_ok)
        polishEditorChrome(form, cancel, ok, nameInput, pinInput, kidsCheck)
        cancel.setOnClickListener {
            ExpMotion.hapticTap(it)
            restorePickerView()
        }
        ok.setOnClickListener {
            ExpMotion.hapticTap(it)
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                ExpDialogChrome.notify(requireContext(), R.string.profile_name_empty, R.string.profile_rename_title)
                return@setOnClickListener
            }
            val pin = pinInput?.text?.toString()?.trim().orEmpty()
            if (pin.isNotEmpty() && (pin.length !in 4..8 || pin.any { !it.isDigit() })) {
                ExpDialogChrome.notify(
                    requireContext(),
                    R.string.profile_pin_invalid_format,
                    R.string.profile_pin_title,
                )
                return@setOnClickListener
            }
            val created = ProfileManager.create(
                name = name,
                isKids = kidsCheck?.isChecked == true,
                avatarKey = selected,
            )
            if (pin.isNotEmpty()) {
                ProfileManager.setPin(created.id, pin)
            }
            ProfileManager.setEnabledIntegrations(created.id, readEnabledIntegrations(form))
            ExpDialogChrome.notify(
                requireContext(),
                getString(R.string.profile_created_toast, created.displayName),
                R.string.profile_switch_title,
            )
            restorePickerView()
            view?.let(::bindProfiles)
            onCreateProfile?.invoke()
        }
        showEditorPanel(editor, form)
        nameInput.requestFocus()
    }

    /** In-screen edit form inside Who's Watching (not a floating AlertDialog). */
    fun showEditProfileDialog(profile: UserProfile) {
        val root = view ?: return
        val editor = root.findViewById<FrameLayout>(R.id.fl_profile_editor) ?: return
        val form = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_profile_create, editor, false)
        form.findViewById<TextView>(R.id.tv_profile_create_title)
            ?.setText(R.string.profile_edit_title)
        val nameInput = form.findViewById<EditText>(R.id.et_profile_create_name).apply {
            setText(profile.displayName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSelection(text?.length ?: 0)
        }
        val kidsCheck = form.findViewById<CheckBox>(R.id.cb_profile_create_kids)?.apply {
            visibility = View.VISIBLE
            isChecked = profile.isKids
        }
        val pinInput = form.findViewById<EditText>(R.id.et_profile_create_pin)
        val clearPin = form.findViewById<CheckBox>(R.id.cb_profile_clear_pin)
        if (profile.pinHash != null) {
            pinInput?.hint = getString(R.string.profile_pin_change_hint)
            clearPin?.visibility = View.VISIBLE
        }
        wireAdvancedSection(form, profile)
        val avatarRow = form.findViewById<LinearLayout>(R.id.ll_profile_create_avatars)
        val avatarLabel = form.findViewById<TextView>(R.id.tv_profile_create_avatar_label)
        val avatarPreview = form.findViewById<ProfileAvatarView>(R.id.pav_profile_create_preview)
        var selected = profile.avatarKey

        fun refreshAvatarRow() {
            bindAvatarRow(
                context = requireContext(),
                row = avatarRow,
                label = avatarLabel,
                preview = avatarPreview,
                previewName = nameInput.text?.toString() ?: profile.displayName,
                selectedKey = selected,
                onSelect = { key ->
                    selected = key
                    refreshAvatarRow()
                },
            )
        }
        refreshAvatarRow()
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                avatarPreview?.bind(selected, s?.toString() ?: profile.displayName, textSizeSp = 28f)
            }
        })

        val cancel = form.findViewById<TextView>(R.id.btn_profile_create_cancel)
        val ok = form.findViewById<TextView>(R.id.btn_profile_create_ok)
        polishEditorChrome(form, cancel, ok, nameInput, pinInput, kidsCheck)
        cancel.setOnClickListener {
            ExpMotion.hapticTap(it)
            restorePickerView()
        }
        ok.setOnClickListener {
            ExpMotion.hapticTap(it)
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                ExpDialogChrome.notify(requireContext(), R.string.profile_name_empty, R.string.profile_rename_title)
                return@setOnClickListener
            }
            if (!applyPinFromForm(requireContext(), form, profile.id, hadExistingPin = profile.pinHash != null)) {
                return@setOnClickListener
            }
            ProfileManager.rename(profile.id, name)
            ProfileManager.updateAvatar(profile.id, selected)
            ProfileManager.updateKids(profile.id, kidsCheck?.isChecked == true)
            ProfileManager.setEnabledIntegrations(profile.id, readEnabledIntegrations(form))
            restorePickerView()
            view?.let(::bindProfiles)
        }
        val deleteBtn = form.findViewById<TextView>(R.id.btn_profile_create_delete)
        if (ProfileManager.profiles().size > 1) {
            deleteBtn?.visibility = View.VISIBLE
            deleteBtn?.setOnClickListener {
                ExpMotion.hapticTap(it)
                confirmDeleteProfile(profile)
            }
        }
        showEditorPanel(editor, form)
        nameInput.requestFocus()
    }

    private fun confirmDeleteProfile(profile: UserProfile) {
        val context = requireContext()
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(context)
        } else {
            AlertDialog.Builder(context)
        }
        builder
            .setMessage(getString(R.string.profile_delete_confirm, profile.displayName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val wasActive = profile.id == ProfileManager.activeProfileId
                if (!ProfileManager.delete(profile.id)) return@setPositiveButton
                if (wasActive && ProfileUnlockGate.requiresGateForActive()) {
                    restorePickerView()
                    return@setPositiveButton
                }
                if (wasActive) {
                    dismissAllowingStateLoss()
                    onProfileSwitched?.invoke()
                } else {
                    restorePickerView()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
                dialog.show()
            }
    }

    private fun polishEditorChrome(
        form: View,
        cancel: TextView,
        ok: TextView,
        nameInput: EditText,
        pinInput: EditText?,
        kidsCheck: CheckBox?,
    ) {
        if (!ExperimentalMobileDesign.enabled()) return
        with(com.dskja.betterstreamflix.utils.ExpPressEffects) {
            cancel.applyExpPress()
            ok.applyExpPress()
            nameInput.applyExpPress()
            pinInput?.applyExpPress()
            kidsCheck?.applyExpPress()
        }
        cancel.setBackgroundResource(ExperimentalMobileDesign.chipBackground())
        ok.setBackgroundResource(ExperimentalMobileDesign.primaryButtonBackground())
        ExperimentalMobileDesign.applyReducedGlass(form)
        form.setBackgroundResource(ExperimentalMobileDesign.dialogBackground())
        nameInput.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
        pinInput?.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
        ExpMotion.revealHeader(
            form.findViewById(R.id.tv_profile_create_title),
            form.findViewById(R.id.v_profile_create_rule),
            form.findViewById(R.id.tv_profile_create_avatar_label),
        )
        ExpMotion.pulseAccentRule(form.findViewById(R.id.v_profile_create_rule))
    }

    private fun showEditorPanel(editor: FrameLayout, form: View) {
        val root = view ?: return
        editor.removeAllViews()
        editor.addView(
            form,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.findViewById<View>(R.id.sv_profile_picker)?.visibility = View.GONE
        editor.visibility = View.VISIBLE
        editorVisible = true
        backCallback?.isEnabled = true
        isCancelable = false
        dialog?.setCanceledOnTouchOutside(false)
        capEditorScroll(form)
        ExpMotion.enterScreen(form)
    }

    private fun capEditorScroll(form: View) {
        val scroll = form.findViewById<View>(R.id.sv_profile_create_form) ?: return
        val max = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        scroll.post {
            val width = scroll.width.takeIf { it > 0 } ?: form.width
            if (width <= 0) return@post
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            if (scroll.measuredHeight > max) {
                scroll.layoutParams = scroll.layoutParams.apply { height = max }
                scroll.requestLayout()
            }
        }
    }

    private fun restorePickerView() {
        val root = view ?: return
        val editor = root.findViewById<FrameLayout>(R.id.fl_profile_editor)
        editor?.removeAllViews()
        editor?.visibility = View.GONE
        root.findViewById<View>(R.id.sv_profile_picker)?.visibility = View.VISIBLE
        editorVisible = false
        backCallback?.isEnabled = false
        isCancelable = !requireUnlock
        dialog?.setCanceledOnTouchOutside(!requireUnlock)
        root.findViewById<View>(R.id.btn_profile_picker_close)?.visibility =
            if (requireUnlock) View.GONE else View.VISIBLE
        bindProfiles(root)
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

        private val INTEGRATION_CHECKBOXES = listOf(
            R.id.cb_profile_int_trakt to UserProfile.Integration.TRAKT,
            R.id.cb_profile_int_jellyfin to UserProfile.Integration.JELLYFIN,
            R.id.cb_profile_int_plex to UserProfile.Integration.PLEX,
            R.id.cb_profile_int_debrid to UserProfile.Integration.DEBRID,
            R.id.cb_profile_int_simkl to UserProfile.Integration.SIMKL,
            R.id.cb_profile_int_opensubtitles to UserProfile.Integration.OPENSUBTITLES,
            R.id.cb_profile_int_tmdb to UserProfile.Integration.TMDB,
        )

        fun show(
            fragment: Fragment,
            onSwitched: (() -> Unit)? = null,
            onManage: (() -> Unit)? = null,
            onCreate: (() -> Unit)? = null,
            startInManageMode: Boolean = false,
            requireUnlock: Boolean = false,
        ) {
            if (!fragment.isAdded || fragment.childFragmentManager.isStateSaved) return
            val existing = fragment.childFragmentManager.findFragmentByTag(TAG)
            if (existing is ProfilePickerDialog) {
                if (existing.dialog?.isShowing == true) {
                    if (startInManageMode) existing.enterManageMode(true)
                    if (requireUnlock) existing.requireUnlock = true
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
                this.requireUnlock = requireUnlock
            }.show(fragment.childFragmentManager, TAG)
        }

        /**
         * Settings / external entry: still uses a dialog. Who's Watching uses the
         * in-screen [ProfilePickerDialog.showCreateDialog] instance method instead.
         */
        fun showCreateDialog(
            fragment: Fragment,
            onCreated: ((UserProfile) -> Unit)? = null,
        ) {
            if (fragment is ProfilePickerDialog) {
                fragment.showCreateDialog()
                return
            }
            val context = fragment.requireContext()
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_profile_create, null, false)
            val nameInput = view.findViewById<EditText>(R.id.et_profile_create_name)
            val kidsCheck = view.findViewById<CheckBox>(R.id.cb_profile_create_kids)
            val pinInput = view.findViewById<EditText>(R.id.et_profile_create_pin)
            val avatarRow = view.findViewById<LinearLayout>(R.id.ll_profile_create_avatars)
            val avatarLabel = view.findViewById<TextView>(R.id.tv_profile_create_avatar_label)
            val avatarPreview = view.findViewById<ProfileAvatarView>(R.id.pav_profile_create_preview)

            wireAdvancedSection(view, profile = null)

            val used = ProfileManager.profiles().map { it.avatarKey }.toSet()
            var selected = ProfileAvatarStyle.featured().firstOrNull { it.key !in used }?.key
                ?: ProfileAvatarStyle.featured().first().key

            fun refreshAvatarRow() {
                bindAvatarRow(
                    context = context,
                    row = avatarRow,
                    label = avatarLabel,
                    preview = avatarPreview,
                    previewName = nameInput.text?.toString().orEmpty(),
                    selectedKey = selected,
                    onSelect = { key ->
                        selected = key
                        refreshAvatarRow()
                    },
                )
            }
            refreshAvatarRow()
            nameInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    avatarPreview?.bind(selected, s?.toString().orEmpty(), textSizeSp = 28f)
                }
            })

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
                    pinInput?.applyExpPress()
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
                val pin = pinInput?.text?.toString()?.trim().orEmpty()
                if (pin.isNotEmpty() && (pin.length !in 4..8 || pin.any { !it.isDigit() })) {
                    ExpDialogChrome.notify(
                        context,
                        R.string.profile_pin_invalid_format,
                        R.string.profile_pin_title,
                    )
                    return@setOnClickListener
                }
                val created = ProfileManager.create(
                    name = name,
                    isKids = kidsCheck?.isChecked == true,
                    avatarKey = selected,
                )
                if (pin.isNotEmpty()) {
                    ProfileManager.setPin(created.id, pin)
                }
                ProfileManager.setEnabledIntegrations(created.id, readEnabledIntegrations(view))
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
                    pinInput?.setBackgroundResource(ExperimentalMobileDesign.searchFieldBackground())
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
            capEditorScroll(view)
        }

        private fun capEditorScroll(form: View) {
            val scroll = form.findViewById<View>(R.id.sv_profile_create_form) ?: return
            val max = (form.resources.displayMetrics.heightPixels * 0.62f).toInt()
            scroll.post {
                val width = scroll.width.takeIf { it > 0 } ?: form.width
                if (width <= 0) return@post
                scroll.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                if (scroll.measuredHeight > max) {
                    scroll.layoutParams = scroll.layoutParams.apply { height = max }
                    scroll.requestLayout()
                }
            }
        }

        private fun wireAdvancedSection(view: View, profile: UserProfile?) {
            val advancedBtn = view.findViewById<TextView>(R.id.btn_profile_create_advanced) ?: return
            val advancedPanel = view.findViewById<View>(R.id.ll_profile_create_advanced) ?: return
            var expanded = false
            fun syncAdvancedChrome() {
                advancedPanel.visibility = if (expanded) View.VISIBLE else View.GONE
                advancedBtn.setText(
                    if (expanded) R.string.profile_advanced_hide else R.string.profile_advanced,
                )
            }
            syncAdvancedChrome()
            advancedBtn.setOnClickListener {
                ExpMotion.hapticTap(it)
                expanded = !expanded
                syncAdvancedChrome()
            }
            INTEGRATION_CHECKBOXES.forEach { (viewId, integration) ->
                val check = view.findViewById<CheckBox>(viewId) ?: return@forEach
                check.isChecked = if (profile == null) {
                    true
                } else {
                    ProfileManager.isIntegrationEnabled(profile, integration)
                }
            }
        }

        private fun readEnabledIntegrations(view: View): Set<String> {
            val enabled = mutableSetOf<String>()
            INTEGRATION_CHECKBOXES.forEach { (viewId, integration) ->
                val check = view.findViewById<CheckBox>(viewId)
                if (check == null || check.isChecked) {
                    enabled += integration
                }
            }
            return enabled
        }

        private fun applyPinFromForm(
            context: Context,
            view: View,
            profileId: String,
            hadExistingPin: Boolean,
        ): Boolean {
            val pinInput = view.findViewById<EditText>(R.id.et_profile_create_pin)
            val clearPin = view.findViewById<CheckBox>(R.id.cb_profile_clear_pin)
            if (clearPin?.isChecked == true && hadExistingPin) {
                val pin = pinInput?.text?.toString()?.trim().orEmpty()
                if (!ProfileManager.verifyPin(profileId, pin)) {
                    ExpDialogChrome.notify(
                        context,
                        R.string.profile_pin_invalid,
                        R.string.profile_pin_title,
                    )
                    return false
                }
                ProfileManager.clearPin(profileId)
                return true
            }
            val pin = pinInput?.text?.toString()?.trim().orEmpty()
            if (pin.isEmpty()) return true
            if (!ProfileManager.setPin(profileId, pin)) {
                ExpDialogChrome.notify(
                    context,
                    R.string.profile_pin_invalid_format,
                    R.string.profile_pin_title,
                )
                return false
            }
            return true
        }

        private fun bindAvatarRow(
            context: Context,
            row: LinearLayout,
            label: TextView,
            preview: ProfileAvatarView?,
            previewName: String,
            selectedKey: String,
            onSelect: (String) -> Unit,
        ) {
            row.removeAllViews()
            val density = context.resources.displayMetrics.density
            val size = (60 * density).toInt()
            val gap = (10 * density).toInt()
            preview?.bind(selectedKey, previewName.ifBlank { selectedKey }, textSizeSp = 28f)
            ProfileAvatarStyle.all().forEach { palette ->
                val orb = ProfileAvatarView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).also {
                        it.setMargins(0, 0, gap, 0)
                    }
                    bind(palette.key, ProfileAvatarStyle.initialFor(palette.key), textSizeSp = 16f)
                    isSelected = palette.key == selectedKey
                    isFocusable = true
                    isClickable = true
                    contentDescription = context.getString(palette.titleRes)
                    foreground = context.getDrawable(R.drawable.bg_profile_avatar_select_ring)
                    if (ExperimentalMobileDesign.enabled()) {
                        with(com.dskja.betterstreamflix.utils.ExpPressEffects) { applyExpPress() }
                    }
                    setOnClickListener {
                        ExpMotion.hapticTap(it)
                        onSelect(palette.key)
                    }
                }
                row.addView(orb)
            }
            label.setText(ProfileAvatarStyle.paletteFor(selectedKey).titleRes)
        }
    }
}
