package com.dskja.betterstreamflix.profiletransfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.profiles.ProfileUnlockGate
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.dskja.betterstreamflix.utils.QrUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export / import UX for portable profiles (QR + file, no offline media).
 */
object ProfileTransferUi {
    @Volatile
    private var confirmInFlight = false

    fun exportFromSettings(
        fragment: Fragment,
        writeFile: (fileName: String, json: String) -> Unit,
    ) {
        val context = fragment.context ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ProfileTransferExporter.exportActive(context) }
            }
            if (!fragment.isAdded) return@launch
            val ctx = fragment.context ?: return@launch
            result.onSuccess { payload ->
                presentExport(fragment, payload, writeFile)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    ctx,
                    ctx.getString(
                        R.string.profile_transfer_export_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    private fun presentExport(
        fragment: Fragment,
        payload: ProfileTransferPayload,
        writeFile: (fileName: String, json: String) -> Unit,
    ) {
        val context = fragment.context ?: return
        val qrUri = ProfileTransferLinks.tryBuildUri(payload)
        val fileJson = runCatching { ProfileTransferCodec.encodeFileJson(payload) }.getOrNull()
        if (fileJson == null) {
            ExpDialogChrome.notify(context, R.string.profile_transfer_export_failed_generic)
            return
        }
        val fileName = "betterstreamflix-profile-${payload.sourceProfileId.ifBlank { "export" }}.json"
        showExportDialog(fragment, payload, qrUri, fileJson, fileName, writeFile)
    }

    private fun showExportDialog(
        fragment: Fragment,
        payload: ProfileTransferPayload,
        qrUri: String?,
        fileJson: String,
        fileName: String,
        writeFile: (fileName: String, json: String) -> Unit,
    ) {
        val context = fragment.context ?: return
        val density = context.resources.displayMetrics.density
        val display = context.resources.displayMetrics
        val dialogWidth = (display.widthPixels * 0.88f).toInt()
        val qrSize = minOf(
            (dialogWidth - (density * 72).toInt()).coerceAtLeast((density * 200).toInt()),
            (display.heightPixels * 0.36f).toInt().coerceAtLeast((density * 200).toInt()),
        )
        val bitmap = qrUri?.let { runCatching { QrUtils.generate(it, qrSize) }.getOrNull() }

        val headline = TextView(context).apply {
            text = context.getString(R.string.profile_transfer_export_headline)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ContextCompat.getColor(context, R.color.cinema_text))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val subtitle = TextView(context).apply {
            text = context.getString(
                R.string.profile_transfer_export_subtitle,
                payload.watchlist.size,
                payload.notes.size,
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.cinema_text_muted))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (density * 6).toInt(), 0, (density * 12).toInt())
        }
        val hint = TextView(context).apply {
            text = if (bitmap != null) {
                context.getString(R.string.profile_transfer_export_qr_hint)
            } else {
                context.getString(R.string.profile_transfer_export_file_only_hint)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.cinema_text_muted))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (density * 8).toInt(), 0, 0)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (density * 20).toInt(),
                (density * 12).toInt(),
                (density * 20).toInt(),
                (density * 8).toInt(),
            )
            addView(headline)
            addView(subtitle)
            if (bitmap != null) {
                val image = ImageView(context).apply {
                    setImageDrawable(BitmapDrawable(resources, bitmap))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.cinema_cta_fill))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    contentDescription = context.getString(R.string.profile_transfer_export_qr_cd)
                }
                addView(image, LinearLayout.LayoutParams(qrSize, qrSize))
            }
            addView(hint)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(container)
        }

        val useGlass = ExperimentalMobileDesign.enabled()
        val builder = if (useGlass) {
            MaterialAlertDialogBuilder(ExpDialogChrome.themedContext(context))
        } else {
            androidx.appcompat.app.AlertDialog.Builder(context)
        }
        val dialog = builder
            .setTitle(R.string.profile_transfer_export_title)
            .setView(scroll)
            .setPositiveButton(R.string.profile_transfer_save_file) { _, _ ->
                writeFile(fileName, fileJson)
            }
            .setNeutralButton(R.string.profile_transfer_share) { _, _ ->
                shareExport(context, qrUri, fileJson, fileName, payload)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener { ExpDialogChrome.polishButtons(dialog) }
        dialog.show()
    }

    private fun shareExport(
        context: Context,
        qrUri: String?,
        fileJson: String,
        fileName: String,
        payload: ProfileTransferPayload,
    ) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, fileJson)
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(
                    R.string.profile_transfer_share_subject,
                    payload.persona?.displayName?.takeIf { it.isNotBlank() }
                        ?: payload.sourceProfileId,
                ),
            )
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    send,
                    context.getString(R.string.profile_transfer_share),
                ),
            )
        }.onFailure {
            copyText(context, fileJson)
        }
    }

    fun importMenu(
        fragment: Fragment,
        openFile: () -> Unit,
        scanQr: () -> Unit,
    ) {
        val context = fragment.context ?: return
        val options = arrayOf(
            context.getString(R.string.profile_transfer_import_scan_qr),
            context.getString(R.string.profile_transfer_import_open_file),
            context.getString(R.string.profile_transfer_import_paste),
        )
        val useGlass = ExperimentalMobileDesign.enabled()
        val builder = if (useGlass) {
            MaterialAlertDialogBuilder(ExpDialogChrome.themedContext(context))
        } else {
            androidx.appcompat.app.AlertDialog.Builder(context)
        }
        builder
            .setTitle(R.string.profile_transfer_import_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> scanQr()
                    1 -> openFile()
                    2 -> showPasteDialog(fragment)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { ExpDialogChrome.polishButtons(it) }
    }

    private fun showPasteDialog(fragment: Fragment) {
        val context = fragment.context ?: return
        val input = android.widget.EditText(context).apply {
            hint = context.getString(R.string.profile_transfer_paste_hint)
            minLines = 3
            setTextIsSelectable(true)
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad / 2, pad, pad / 2)
        val useGlass = ExperimentalMobileDesign.enabled()
        val builder = if (useGlass) {
            MaterialAlertDialogBuilder(ExpDialogChrome.themedContext(context))
        } else {
            androidx.appcompat.app.AlertDialog.Builder(context)
        }
        builder
            .setTitle(R.string.profile_transfer_import_paste)
            .setView(input)
            .setPositiveButton(R.string.profile_transfer_import_confirm_action) { _, _ ->
                handleIncomingRaw(fragment.requireActivity(), input.text?.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { ExpDialogChrome.polishButtons(it) }
    }

    fun importFromFileUri(activity: FragmentActivity, uri: Uri) {
        activity.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                ExpDialogChrome.notify(activity, R.string.profile_transfer_import_invalid)
                return@launch
            }
            val payload = ProfileTransferCodec.decodeFileJson(text)
            if (payload == null) {
                ExpDialogChrome.notify(activity, R.string.profile_transfer_import_invalid)
                return@launch
            }
            handleIncoming(activity, payload)
        }
    }

    fun handleIncomingRaw(activity: FragmentActivity, raw: String?) {
        val payload = ProfileTransferLinks.parse(raw)
            ?: ProfileTransferCodec.decodeFileJson(raw)
        if (payload == null) {
            ExpDialogChrome.notify(activity, R.string.profile_transfer_import_invalid)
            return
        }
        handleIncoming(activity, payload)
    }

    fun handleIncoming(activity: FragmentActivity, payload: ProfileTransferPayload) {
        if (ProfileUnlockGate.requiresGateForActive()) {
            ProfileTransferPendingImport.enqueue(activity.applicationContext, payload)
            return
        }
        if (confirmInFlight) {
            ProfileTransferPendingImport.enqueue(activity.applicationContext, payload)
            return
        }
        confirmInFlight = true
        val message = activity.getString(
            R.string.profile_transfer_import_confirm_message,
            payload.watchlist.size,
            payload.notes.size,
            payload.persona?.displayName?.takeIf { it.isNotBlank() } ?: "—",
        )
        val useGlass = ExperimentalMobileDesign.enabled()
        val builder = if (useGlass) {
            MaterialAlertDialogBuilder(ExpDialogChrome.themedContext(activity))
        } else {
            androidx.appcompat.app.AlertDialog.Builder(activity)
        }
        val glass = if (useGlass) {
            ExpDialogChrome.buildGlassMessage(activity, message)
        } else {
            null
        }
        if (glass != null) builder.setView(glass.root) else builder.setMessage(message)
        var importing = false
        val applyKids = payload.persona?.isKids == true
        val dialog = builder
            .setTitle(R.string.profile_transfer_import_confirm_title)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.profile_transfer_import_confirm_action) { _, _ ->
                importing = true
                val options = ProfileTransferImporter.Options(
                    applyPersona = true,
                    applyKidsFlag = applyKids,
                )
                if (applyKids) {
                    promptParentalPinBeforeKidsImport(activity) {
                        runImport(activity, payload, options)
                    }
                } else {
                    runImport(activity, payload, options)
                }
            }
            .create()
        dialog.setOnDismissListener {
            if (!importing) confirmInFlight = false
        }
        dialog.setOnShowListener {
            if (glass != null) {
                ExpDialogChrome.polishGlassMessageShown(dialog, glass)
            } else {
                ExpDialogChrome.polishButtons(dialog)
            }
        }
        dialog.show()
    }

    fun tryFlushPending(context: Context) {
        val activity = context as? FragmentActivity ?: return
        if (activity.isFinishing) return
        if (ProfileUnlockGate.requiresGateForActive()) return
        if (confirmInFlight) return
        val payload = ProfileTransferPendingImport.take(activity.applicationContext) ?: return
        handleIncoming(activity, payload)
    }

    private fun runImport(
        activity: FragmentActivity,
        payload: ProfileTransferPayload,
        options: ProfileTransferImporter.Options,
    ) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ProfileTransferImporter.importPayload(
                        activity.applicationContext,
                        payload,
                        options,
                    )
                }
            }
            confirmInFlight = false
            result.onSuccess { r ->
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.profile_transfer_import_done,
                        r.watchlistImported,
                        r.notesWritten,
                    ),
                )
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.profile_transfer_import_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun writeJsonToUri(context: Context, uri: Uri, json: String): Boolean =
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                out.write(json)
            } != null
        }.getOrDefault(false)

    private fun promptParentalPinBeforeKidsImport(
        activity: FragmentActivity,
        onVerified: () -> Unit,
    ) {
        if (UserPreferences.parentalControlPin.isBlank()) {
            ExpDialogChrome.notify(
                activity,
                R.string.profile_transfer_kids_import_requires_pin,
                R.string.profile_transfer_import_confirm_title,
            )
            confirmInFlight = false
            return
        }
        val input = EditText(ExpDialogChrome.themedContext(activity)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = activity.getString(R.string.settings_parental_pin_hint)
        }
        val builder = if (ExperimentalMobileDesign.enabled()) {
            MaterialAlertDialogBuilder(ExpDialogChrome.themedContext(activity))
        } else {
            androidx.appcompat.app.AlertDialog.Builder(activity)
        }
        builder
            .setTitle(R.string.profile_transfer_kids_import_pin_title)
            .setMessage(R.string.profile_transfer_kids_import_pin_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString()?.trim().orEmpty()
                if (UserPreferences.verifyParentalControlPin(entered)) {
                    UserPreferences.registerParentalPinSuccess()
                    onVerified()
                } else {
                    UserPreferences.registerParentalPinFailure()
                    confirmInFlight = false
                    ExpDialogChrome.notify(
                        activity,
                        R.string.settings_parental_invalid_pin,
                        R.string.profile_transfer_import_confirm_title,
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                confirmInFlight = false
            }
            .show()
            .also { ExpDialogChrome.polishButtons(it) }
    }

    private fun copyText(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    context.getString(R.string.profile_transfer_export_title),
                    text,
                ),
            )
            ExpDialogChrome.notify(context, R.string.profile_transfer_copied)
        } else {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
