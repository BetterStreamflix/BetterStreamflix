package com.dskja.betterstreamflix.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
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
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Account Link-Code UI: phone shows a 6-char code; TV/other enters code → email-prefilled password sign-in.
 */
object DeviceLinkUi {

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

        val input = EditText(activity).apply {
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(7), InputFilter.AllCaps())
            hint = activity.getString(R.string.device_link_code_hint)
            setPadding(48, 32, 48, 32)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.device_link_enter_message)
                    setPadding(24, 0, 24, 16)
                },
            )
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.device_link_enter_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.device_link_enter_action) { _, _ ->
                lookupAndSignIn(activity, input.text?.toString().orEmpty(), onSignedIn)
            }
            .show()
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
        val codeView = TextView(context).apply {
            text = display
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setPadding(24, 24, 24, 8)
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.12f
        }
        val message = TextView(context).apply {
            text = context.getString(R.string.device_link_offer_message, display)
            setPadding(24, 0, 24, 8)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), 8, (16 * density).toInt(), 0)
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
        AlertDialog.Builder(context)
            .setTitle(R.string.device_link_offer_title)
            .setView(container)
            .setPositiveButton(R.string.device_link_offer_done, null)
            .setNeutralButton(R.string.device_link_copy_code) { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("link-code", display))
                ExpDialogChrome.notify(context, R.string.device_link_copied)
            }
            .show()
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
