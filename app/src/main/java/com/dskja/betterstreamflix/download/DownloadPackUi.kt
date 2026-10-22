package com.dskja.betterstreamflix.download

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
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
import androidx.navigation.fragment.NavHostFragment
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.profiles.ProfileUnlockGate
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.QrUtils
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share / import UX for metadata download packs (QR + link, no file transfer).
 */
object DownloadPackUi {
    @Volatile
    private var confirmInFlight = false
    private val promptedEncoded = mutableSetOf<String>()

    fun shareSeasonPack(fragment: Fragment, pack: DownloadSeasonPackEntity) {
        val context = fragment.context ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DownloadPackImporter.buildFromSeasonPack(context, pack) }
            }
            if (!fragment.isAdded) return@launch
            val ctx = fragment.context ?: return@launch
            result.onSuccess { built ->
                if (built.truncated) {
                    ExpDialogChrome.notify(
                        ctx,
                        ctx.getString(
                            R.string.download_pack_share_truncated,
                            DownloadPackPayload.MAX_EPISODES,
                        ),
                    )
                }
                presentShare(fragment, built.payload.withShareTtl())
            }.onFailure { error ->
                notifyShareFailure(ctx, error)
            }
        }
    }

    fun shareItems(fragment: Fragment, items: List<DownloadItemEntity>, titleFallback: String = "") {
        val context = fragment.context ?: return
        if (items.isEmpty()) {
            ExpDialogChrome.notify(context, R.string.download_pack_share_empty)
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { DownloadPackImporter.buildFromItems(context, items, titleFallback) }
            }
            if (!fragment.isAdded) return@launch
            val ctx = fragment.context ?: return@launch
            result.onSuccess { payload ->
                presentShare(fragment, payload.withShareTtl())
            }.onFailure { error ->
                notifyShareFailure(ctx, error)
            }
        }
    }

    private fun DownloadPackPayload.withShareTtl(): DownloadPackPayload =
        copy(
            version = DownloadPackPayload.VERSION,
            expiresAtEpochMs = if (expiresAtEpochMs > 0L) {
                expiresAtEpochMs
            } else {
                System.currentTimeMillis() + DownloadPackPayload.SHARE_TTL_MS
            },
        )

    private fun notifyShareFailure(context: Context, error: Throwable) {
        val message = error.message.orEmpty()
        if (message.contains("pack too large", ignoreCase = true)) {
            ExpDialogChrome.notify(context, R.string.download_pack_share_too_large)
            return
        }
        ExpDialogChrome.notify(
            context,
            context.getString(
                R.string.download_pack_share_failed,
                error.message ?: error.javaClass.simpleName,
            ),
        )
    }

    private fun presentShare(fragment: Fragment, payload: DownloadPackPayload) {
        val context = fragment.context ?: return
        val uri = runCatching { DownloadPackLinks.buildUri(payload) }.getOrElse { error ->
            notifyShareFailure(context, error)
            return
        }
        val qrUri = runCatching { DownloadPackLinks.buildCustomSchemeUri(payload) }.getOrDefault(uri)
        showShareDialog(fragment, payload, uri, qrUri)
    }

    private fun showShareDialog(
        fragment: Fragment,
        payload: DownloadPackPayload,
        httpsUri: String,
        qrUri: String,
    ) {
        val context = fragment.context ?: return
        val density = context.resources.displayMetrics.density
        val display = context.resources.displayMetrics
        val dialogWidth = (display.widthPixels * 0.88f).toInt()
        val qrSize = minOf(
            (dialogWidth - (density * 72).toInt()).coerceAtLeast((density * 200).toInt()),
            (display.heightPixels * 0.36f).toInt().coerceAtLeast((density * 200).toInt()),
        )
        val bitmap = runCatching { QrUtils.generate(qrUri, qrSize) }.getOrNull()

        val titleLine = context.getString(
            R.string.download_pack_share_headline,
            payload.tvShowTitle,
            payload.seasonNumber,
        )
        val subtitleLine = context.getString(
            R.string.download_pack_share_subtitle,
            payload.episodeCount(),
            payload.providerName,
        )

        val headline = TextView(context).apply {
            text = titleLine
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ContextCompat.getColor(context, R.color.cinema_text))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val subtitle = TextView(context).apply {
            text = subtitleLine
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.cinema_text_muted))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (density * 6).toInt(), 0, (density * 12).toInt())
        }
        val hint = TextView(context).apply {
            text = context.getString(R.string.download_pack_share_qr_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.cinema_text_muted))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (density * 8).toInt(), 0, 0)
        }
        val linkView = TextView(context).apply {
            text = httpsUri
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(context, R.color.cinema_text_muted))
            setPadding(0, (density * 10).toInt(), 0, 0)
            setTextIsSelectable(true)
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
                    contentDescription = context.getString(R.string.download_pack_share_qr_cd)
                }
                addView(image, LinearLayout.LayoutParams(qrSize, qrSize))
            }
            addView(hint)
            addView(linkView)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(container)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.download_pack_share_title)
            .setView(scroll)
            .setPositiveButton(R.string.download_pack_share_action) { _, _ ->
                sendLink(context, httpsUri, payload)
            }
            .setNeutralButton(R.string.download_pack_copy_link) { _, _ ->
                copyLink(context, httpsUri)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            ExpDialogChrome.polishButtons(dialog)
        }
        dialog.show()
    }

    private fun sendLink(context: Context, link: String, payload: DownloadPackPayload) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(
                    R.string.download_pack_share_subject,
                    payload.tvShowTitle,
                    payload.seasonNumber,
                ),
            )
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    send,
                    context.getString(R.string.download_pack_share_action),
                ),
            )
        }.onFailure {
            copyLink(context, link)
        }
    }

    private fun copyLink(context: Context, link: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.download_pack_share_title), link),
            )
            ExpDialogChrome.notify(context, R.string.download_pack_copied)
        } else {
            Toast.makeText(context, link, Toast.LENGTH_LONG).show()
        }
    }

    fun handleIncoming(activity: FragmentActivity, payload: DownloadPackPayload) {
        if (ProfileUnlockGate.requiresGateForActive()) {
            DownloadPackPendingImport.enqueue(activity.applicationContext, payload)
            return
        }
        if (confirmInFlight) {
            DownloadPackPendingImport.enqueue(activity.applicationContext, payload)
            return
        }
        confirmInFlight = true
        val message = activity.getString(
            R.string.download_pack_import_confirm_message,
            payload.tvShowTitle,
            payload.seasonNumber,
            payload.episodeCount(),
            payload.providerName,
        )
        val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
        builder.setMessage(message)
        var importing = false
        val encodedMark = runCatching { DownloadPackLinks.encode(payload) }.getOrNull()
        val dialog = builder
            .setTitle(R.string.download_pack_import_confirm_title)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_pack_import_confirm_action) { _, _ ->
                importing = true
                runImport(activity, payload)
            }
            .create()
        dialog.setOnDismissListener {
            if (!importing) {
                confirmInFlight = false
                encodedMark?.let { promptedEncoded.remove(it) }
            }
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

    fun handleIncomingRaw(activity: FragmentActivity, raw: String?) {
        val payload = DownloadPackLinks.parse(raw)
        if (payload == null) {
            ExpDialogChrome.notify(activity, R.string.download_pack_import_invalid)
            return
        }
        if (payload.isExpired()) {
            ExpDialogChrome.notify(activity, R.string.download_pack_import_expired)
            return
        }
        handleIncoming(activity, payload)
    }

    /** TV / paste-link entry: EditText dialog then [handleIncomingRaw]. */
    fun promptImportFromLink(activity: FragmentActivity) {
        val input = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.offline_studio_pack_import_link_hint)
            minLines = 2
            setTextIsSelectable(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.offline_studio_pack_import_tv)
            .setMessage(R.string.offline_studio_pack_import_paste_message)
            .setView(container)
            .setPositiveButton(R.string.download_pack_import_confirm_action) { _, _ ->
                handleIncomingRaw(activity, input.text?.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runImport(activity: FragmentActivity, payload: DownloadPackPayload) {
        activity.lifecycleScope.launch {
            val allowMetered = confirmCellularIfNeeded(activity)
            if (UserPreferences.downloadWifiOnly &&
                DownloadConnectivityMonitor.isMetered(activity) &&
                !allowMetered
            ) {
                confirmInFlight = false
                runCatching { DownloadPackLinks.encode(payload) }.getOrNull()
                    ?.let { promptedEncoded.remove(it) }
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DownloadPackImporter.importPayload(
                        activity.applicationContext,
                        payload,
                        allowMetered = allowMetered,
                    )
                }
            }
            confirmInFlight = false
            result.onSuccess { imported ->
                runCatching { DownloadPackLinks.encode(payload) }.getOrNull()
                    ?.let { promptedEncoded.remove(it) }
                DownloadPackPendingImport.clear(activity.applicationContext)
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.download_pack_import_success,
                        imported.queued,
                        imported.alreadyPresent,
                        imported.failed,
                    ),
                )
                navigateDownloads(activity)
                tryFlushPending(activity)
            }.onFailure { error ->
                runCatching { DownloadPackLinks.encode(payload) }.getOrNull()
                    ?.let { promptedEncoded.remove(it) }
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.download_pack_import_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    /**
     * @return true when metered downloads are allowed for this import
     * (user confirmed on cellular, or Wi‑Fi-only is off).
     */
    private suspend fun confirmCellularIfNeeded(activity: FragmentActivity): Boolean {
        if (!UserPreferences.downloadWifiOnly) return true
        if (!DownloadConnectivityMonitor.isMetered(activity)) return false
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            activity.runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.download_options_cellular_confirm_title)
                    .setMessage(R.string.download_options_cellular_confirm_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (cont.isActive) cont.resume(true)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        if (cont.isActive) cont.resume(false)
                    }
                    .setOnCancelListener {
                        if (cont.isActive) cont.resume(false)
                    }
                    .show()
            }
        }
    }

    private fun navigateDownloads(activity: FragmentActivity) {
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            ?: return
        val nav = navHost.navController
        if (nav.currentDestination?.id != R.id.downloads) {
            runCatching { nav.navigate(R.id.downloads) }
        }
    }

    fun tryFlushPending(context: Context) {
        val activity = context as? FragmentActivity ?: return
        if (activity.isFinishing) return
        if (ProfileUnlockGate.requiresGateForActive()) return
        if (confirmInFlight) return
        val encoded = DownloadPackPendingImport.peekEncoded(activity.applicationContext)
            ?: return
        if (encoded in promptedEncoded) return
        promptedEncoded.add(encoded)
        val payload = DownloadPackLinks.decode(encoded) ?: run {
            DownloadPackPendingImport.clear(activity.applicationContext)
            return
        }
        handleIncoming(activity, payload)
    }
}

/**
 * Small FIFO of pending pack deep links while PIN gate / prior confirm is busy.
 * Keeps the last [MAX_QUEUE] encoded packs; [peekEncoded] returns the oldest matching
 * the active profile; [clear] removes that head after consume (OS-055).
 */
object DownloadPackPendingImport {
    private const val PREFS = "download_pack_pending"
    private const val KEY_QUEUE = "queue_json"
    private const val KEY_LEGACY = "encoded"
    private const val KEY_LEGACY_PROFILE = "profile_id"
    private const val MAX_QUEUE = 3

    private data class Entry(val encoded: String, val profileId: String)

    private val memory = ArrayDeque<Entry>()

    fun enqueue(context: Context, payload: DownloadPackPayload) {
        val encoded = runCatching { DownloadPackLinks.encode(payload) }.getOrNull() ?: return
        val profileId = com.dskja.betterstreamflix.profiles.ProfileManager.activeProfileId
        synchronized(memory) {
            migrateLegacy(context)
            memory.removeAll { it.encoded == encoded && it.profileId == profileId }
            memory.addLast(Entry(encoded, profileId))
            while (memory.size > MAX_QUEUE) memory.removeFirst()
            persist(context)
        }
    }

    fun peekEncoded(context: Context): String? {
        val active = com.dskja.betterstreamflix.profiles.ProfileManager.activeProfileId
        synchronized(memory) {
            migrateLegacy(context)
            return memory.firstOrNull { it.profileId == active }?.encoded
        }
    }

    fun clear(context: Context) {
        val active = com.dskja.betterstreamflix.profiles.ProfileManager.activeProfileId
        synchronized(memory) {
            migrateLegacy(context)
            val idx = memory.indexOfFirst { it.profileId == active }
            if (idx >= 0) memory.removeAt(idx)
            persist(context)
        }
    }

    private fun migrateLegacy(context: Context) {
        if (memory.isNotEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queueJson = prefs.getString(KEY_QUEUE, null)
        if (!queueJson.isNullOrBlank()) {
            runCatching {
                val arr = org.json.JSONArray(queueJson)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val encoded = o.optString("encoded").trim()
                    val profileId = o.optString("profileId")
                    if (encoded.isNotEmpty()) memory.addLast(Entry(encoded, profileId))
                }
            }
            while (memory.size > MAX_QUEUE) memory.removeFirst()
            return
        }
        val legacy = prefs.getString(KEY_LEGACY, null)?.trim().orEmpty()
        if (legacy.isNotEmpty()) {
            val profileId = prefs.getString(KEY_LEGACY_PROFILE, null).orEmpty()
            memory.addLast(Entry(legacy, profileId))
            prefs.edit().remove(KEY_LEGACY).remove(KEY_LEGACY_PROFILE).apply()
            persist(context)
        }
    }

    private fun persist(context: Context) {
        val arr = org.json.JSONArray()
        memory.forEach { entry ->
            arr.put(
                org.json.JSONObject()
                    .put("encoded", entry.encoded)
                    .put("profileId", entry.profileId),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUEUE, arr.toString())
            .remove(KEY_LEGACY)
            .remove(KEY_LEGACY_PROFILE)
            .apply()
    }
}
