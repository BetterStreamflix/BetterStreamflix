package com.dskja.betterstreamflix.sync

import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadConnectivityMonitor
import com.dskja.betterstreamflix.fragments.settings.CloudAccountSettingsController
import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.ui.LeanbackDensityPolicy
import com.dskja.betterstreamflix.utils.DeviceCapabilities
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Account Link-Code UI: phone shows a 6-char code; TV/other enters code → email-prefilled password sign-in.
 */
object DeviceLinkUi {

    private const val CODE_LENGTH = 6
    private const val PAD_COLUMNS = 6

    fun showOfferCode(fragment: Fragment) {
        val context = fragment.context ?: return
        if (!guardReady(context, requireSignedIn = true)) return

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DeviceLinkRepository.createOffer(context) }
            }
            if (!fragment.isAdded) return@launch
            val ctx = fragment.context ?: return@launch
            result.onSuccess { offer ->
                showOfferDialog(ctx, offer.shortCode)
            }.onFailure { error ->
                notifyError(ctx, error)
            }
        }
    }

    fun promptEnterCode(activity: FragmentActivity, onSignedIn: (() -> Unit)? = null) {
        if (!guardReady(activity, requireSignedIn = false)) return
        if (CloudSyncManager.currentUserId() != null) {
            ExpDialogChrome.notify(activity, R.string.device_link_already_signed_in)
            return
        }

        if (isTelevisionUi(activity)) {
            showLeanbackEnterPad(activity, onSignedIn)
        } else {
            showPhoneEnterDialog(activity, onSignedIn)
        }
    }

    /** TV / Leanback / Fire TV — prefer DPAD pad over soft keyboard. */
    fun isTelevisionUi(context: Context): Boolean {
        if (DeviceCapabilities.isLeanbackDevice(context)) return true
        if (DeviceCapabilities.isAmazonFireTv(context)) return true
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun showPhoneEnterDialog(
        activity: FragmentActivity,
        onSignedIn: (() -> Unit)?,
    ) {
        val padDp = LeanbackDensityPolicy.overlayChromePadDp()
        val density = activity.resources.displayMetrics.density
        val padPx = (padDp * density).toInt()
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(7), InputFilter.AllCaps())
            hint = activity.getString(R.string.device_link_code_hint)
            setPadding(padPx * 2, padPx, padPx * 2, padPx)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, 0)
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.device_link_enter_message)
                    setPadding(padPx, 0, padPx, padPx)
                },
            )
            addView(input)
        }
        ExpDialogChrome.showAlert(activity) { builder ->
            builder
                .setTitle(R.string.device_link_enter_title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.device_link_enter_action) { _, _ ->
                    lookupAndSignIn(activity, input.text?.toString().orEmpty(), onSignedIn)
                }
        }
    }

    private fun showLeanbackEnterPad(
        activity: FragmentActivity,
        onSignedIn: (() -> Unit)?,
    ) {
        val density = activity.resources.displayMetrics.density
        val padDp = LeanbackDensityPolicy.overlayChromePadDp()
        val padPx = (padDp * density).toInt()
        val gapPx = (LeanbackDensityPolicy.itemSpacingDp() * density).toInt()
        val typed = StringBuilder()
        val slots = Array(CODE_LENGTH) {
            TextView(activity).apply {
                text = "·"
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(gapPx, gapPx / 2, gapPx, gapPx / 2)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        }

        fun refreshSlots() {
            for (i in 0 until CODE_LENGTH) {
                slots[i].text = if (i < typed.length) typed[i].toString() else "·"
            }
        }

        val slotRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, padPx)
            slots.forEach { slot ->
                addView(
                    slot,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = gapPx / 2
                        marginEnd = gapPx / 2
                    },
                )
            }
        }

        // Hold dialog/positive as vars (not an anonymous object) so local funs
        // can capture them under Kotlin FIR ("References to variables aren't supported yet").
        var padDialog: AlertDialog? = null
        var padPositive: Button? = null

        fun updatePositiveEnabled() {
            padPositive?.isEnabled = typed.length >= CODE_LENGTH
        }

        fun appendChar(ch: Char) {
            if (typed.length >= CODE_LENGTH) return
            typed.append(ch)
            refreshSlots()
            updatePositiveEnabled()
            if (typed.length >= CODE_LENGTH) {
                padDialog?.dismiss()
                lookupAndSignIn(activity, typed.toString(), onSignedIn)
            }
        }

        fun backspace() {
            if (typed.isEmpty()) return
            typed.deleteCharAt(typed.length - 1)
            refreshSlots()
            updatePositiveEnabled()
        }

        fun clearAll() {
            typed.clear()
            refreshSlots()
            updatePositiveEnabled()
        }

        val alphabet = CompanionShortCodes.ALPHABET
        val grid = GridLayout(activity).apply {
            columnCount = PAD_COLUMNS
            rowCount = (alphabet.length + PAD_COLUMNS - 1) / PAD_COLUMNS
            useDefaultMargins = false
        }
        val keyMinH = (LeanbackDensityPolicy.chromeIconSizeDp() * density).toInt()
        var firstKey: Button? = null
        for (index in alphabet.indices) {
            val ch = alphabet[index]
            val key = Button(activity).apply {
                text = ch.toString()
                isFocusable = true
                isFocusableInTouchMode = true
                isAllCaps = true
                minimumHeight = keyMinH
                setPadding(gapPx, gapPx / 2, gapPx, gapPx / 2)
                setOnClickListener { appendChar(ch) }
            }
            if (firstKey == null) firstKey = key
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(index % PAD_COLUMNS, 1f)
                rowSpec = GridLayout.spec(index / PAD_COLUMNS)
                setMargins(gapPx / 2, gapPx / 2, gapPx / 2, gapPx / 2)
            }
            grid.addView(key, params)
        }

        val backspaceBtn = Button(activity).apply {
            text = activity.getString(R.string.device_link_pad_backspace)
            isFocusable = true
            isFocusableInTouchMode = true
            minimumHeight = keyMinH
            setOnClickListener { backspace() }
        }
        val clearBtn = Button(activity).apply {
            text = activity.getString(R.string.device_link_pad_clear)
            isFocusable = true
            isFocusableInTouchMode = true
            minimumHeight = keyMinH
            setOnClickListener { clearAll() }
        }
        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, padPx, 0, 0)
            addView(
                backspaceBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = gapPx
                },
            )
            addView(
                clearBtn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, 0)
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.device_link_enter_message)
                    setPadding(0, 0, 0, padPx)
                },
            )
            addView(slotRow)
            addView(
                grid,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(actionRow)
        }

        val dialog = ExpDialogChrome.showAlert(activity) { builder ->
            builder
                .setTitle(R.string.device_link_enter_title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.device_link_enter_action) { _, _ ->
                    lookupAndSignIn(activity, typed.toString(), onSignedIn)
                }
        }
        padDialog = dialog
        // showAlert already showed the dialog; configure pad focus after polish.
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        padPositive = positive
        positive?.isEnabled = false
        hideSoftKeyboard(activity, container)
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )
        (firstKey ?: backspaceBtn).requestFocus()
    }

    private fun hideSoftKeyboard(context: Context, anchor: android.view.View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        imm.hideSoftInputFromWindow(anchor.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
        anchor.rootView?.windowToken?.let { token ->
            imm.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun lookupAndSignIn(
        activity: FragmentActivity,
        rawCode: String,
        onSignedIn: (() -> Unit)?,
    ) {
        val cleaned = DeviceLinkCodes.normalize(rawCode)
        if (cleaned == null) {
            ExpDialogChrome.notify(activity, R.string.device_link_invalid_code)
            return
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DeviceLinkRepository.lookupByCode(activity, cleaned) }
            }
            result.onSuccess { lookup ->
                CloudAccountSettingsController.promptSignInWithEmail(
                    activity = activity,
                    prefilledEmail = lookup.email,
                    onSuccess = {
                        activity.lifecycleScope.launch(Dispatchers.IO) {
                            DeviceLinkRepository.markClaimed(activity, cleaned)
                        }
                        onSignedIn?.invoke()
                    },
                )
            }.onFailure { error ->
                notifyError(activity, error)
            }
        }
    }

    private fun showOfferDialog(context: Context, shortCode: String) {
        val display = DeviceLinkCodes.formatDisplay(shortCode)
        val density = context.resources.displayMetrics.density
        val padDp = LeanbackDensityPolicy.overlayChromePadDp()
        val padPx = (padDp * density).toInt()
        val codeView = TextView(context).apply {
            text = display
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setPadding(padPx, padPx, padPx, padPx / 2)
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.12f
        }
        val message = TextView(context).apply {
            text = context.getString(R.string.device_link_offer_message, display)
            setPadding(padPx, 0, padPx, padPx / 2)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, 0)
            addView(
                codeView,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(
                message,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        ExpDialogChrome.showAlert(context) { builder ->
            builder
                .setTitle(R.string.device_link_offer_title)
                .setView(container)
                .setPositiveButton(R.string.device_link_offer_done, null)
                .setNeutralButton(R.string.device_link_copy_code) { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("link-code", display))
                    ExpDialogChrome.notify(context, R.string.device_link_copied)
                }
        }
    }

    private fun guardReady(context: Context, requireSignedIn: Boolean): Boolean {
        if (ProfileManager.isKidsActive()) {
            ExpDialogChrome.notify(context, R.string.cloud_sync_kids_sign_in_blocked)
            return false
        }
        if (!SupabaseProvider.isConfigured) {
            ExpDialogChrome.notify(context, R.string.device_link_needs_supabase)
            return false
        }
        if (!DownloadConnectivityMonitor.isOnline(context)) {
            ExpDialogChrome.notify(context, R.string.device_link_offline)
            return false
        }
        if (requireSignedIn && CloudSyncManager.currentUserId() == null) {
            ExpDialogChrome.notify(context, R.string.device_link_needs_sign_in)
            return false
        }
        return true
    }

    private fun notifyError(context: Context, error: Throwable) {
        val message = when (error) {
            is KidsCloudAuthException ->
                context.getString(R.string.cloud_sync_kids_sign_in_blocked)
            else -> context.getString(
                R.string.device_link_error,
                error.message ?: error.javaClass.simpleName,
            )
        }
        ExpDialogChrome.notify(context, message)
    }
}
