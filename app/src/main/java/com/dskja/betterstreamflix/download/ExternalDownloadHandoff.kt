package com.dskja.betterstreamflix.download

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Hands a resolved stream URL to ADM / 1DM / similar download managers using
 * the activity contracts those apps actually honor.
 */
object ExternalDownloadHandoff {
    private const val TAG = "ExternalDownloadHandoff"
    const val ACTION_DOWNLOADER_CHOSEN =
        "com.dskja.betterstreamflix.ACTION_EXTERNAL_DOWNLOADER_CHOSEN"

    val CANDIDATE_PACKAGES = listOf(
        "com.dv.adm",
        "com.dv.adm.pay",
        "com.dv.adm.old",
        "idm.internet.download.manager",
        "idm.internet.download.manager.plus",
    )

    /** Activity class names tried per ADM package (free/pro/legacy). */
    private val ADM_EDITOR_CLASSES = listOf(
        "com.dv.adm.AEditor",
        "com.dv.get.AEditor",
        "com.dv.adm.pay.AEditor",
    )

    data class Request(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val fileName: String? = null,
        val mimeType: String? = null,
        val title: String? = null,
    )

    fun preferredPackage(context: Context): String? {
        val saved = UserPreferences.externalDownloaderPackage.trim()
        if (saved.isNotEmpty() && isPackageInstalled(context, saved)) return saved
        return CANDIDATE_PACKAGES.firstOrNull { isPackageInstalled(context, it) }
    }

    fun isAvailable(context: Context): Boolean =
        preferredPackage(context) != null ||
            installedCandidates(context).isNotEmpty()

    fun rememberChosenPackage(packageName: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        UserPreferences.externalDownloaderPackage = pkg
    }

    fun clearPreferredPackage() {
        UserPreferences.externalDownloaderPackage = ""
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    fun installedCandidates(context: Context): List<String> =
        CANDIDATE_PACKAGES.filter { isPackageInstalled(context, it) }

    fun sanitizeFileName(name: String?): String {
        val base = name?.trim().orEmpty().ifBlank { "stream" }
        return base.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
    }

    fun isAdmPackage(packageName: String): Boolean =
        packageName == "com.dv.adm" ||
            packageName == "com.dv.adm.pay" ||
            packageName == "com.dv.adm.old"

    fun isOneDmPackage(packageName: String): Boolean =
        packageName.startsWith("idm.internet.download.manager")

    /**
     * Build the best-known enqueue intent for [packageName].
     * Pure for unit tests — does not resolve against PackageManager.
     */
    fun buildIntentForPackage(request: Request, packageName: String): Intent {
        val spec = describeForPackage(request, packageName)
        return Intent(spec.action).apply {
            if (spec.dataUri != null) {
                data = Uri.parse(spec.dataUri)
            }
            if (spec.className != null) {
                component = ComponentName(spec.packageName, spec.className)
            } else {
                setPackage(spec.packageName)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            for ((k, v) in spec.extras) {
                putExtra(k, v)
            }
        }
    }

    /** Pure description of the handoff intent (unit-testable without Android mocks). */
    data class IntentSpec(
        val action: String,
        val packageName: String,
        val className: String?,
        val dataUri: String?,
        val extras: Map<String, String>,
    )

    fun describeForPackage(request: Request, packageName: String): IntentSpec {
        val url = request.url.trim()
        val fileName = sanitizeFileName(request.fileName)
        val headerExtras = mutableMapOf<String, String>()
        if (request.headers.isNotEmpty()) {
            headerExtras["headers"] =
                request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            request.headers["Cookie"]?.let {
                headerExtras["Cookies"] = it
                headerExtras["cookies"] = it
            }
            request.headers["User-Agent"]?.let {
                headerExtras["useragent"] = it
                headerExtras["User-Agent"] = it
            }
            request.headers["Referer"]?.let {
                headerExtras["referer"] = it
                headerExtras["Referer"] = it
            }
        }
        return when {
            isAdmPackage(packageName) -> IntentSpec(
                action = Intent.ACTION_MAIN,
                packageName = packageName,
                className = admEditorClassCandidates(packageName).first(),
                dataUri = null,
                extras = buildMap {
                    put(Intent.EXTRA_TEXT, url)
                    put("com.android.extra.filename", fileName)
                    put("filename", fileName)
                    if (!request.title.isNullOrBlank()) put(Intent.EXTRA_TITLE, request.title)
                    putAll(headerExtras)
                },
            )
            isOneDmPackage(packageName) -> IntentSpec(
                action = Intent.ACTION_VIEW,
                packageName = packageName,
                className = "$packageName.Downloader",
                dataUri = url,
                extras = buildMap {
                    put("extra_filename", fileName)
                    put("com.android.extra.filename", fileName)
                    put(Intent.EXTRA_TEXT, url)
                    putAll(headerExtras)
                },
            )
            else -> IntentSpec(
                action = Intent.ACTION_VIEW,
                packageName = packageName,
                className = null,
                dataUri = url,
                extras = buildMap {
                    put(Intent.EXTRA_TEXT, url)
                    put("com.android.extra.filename", fileName)
                    putAll(headerExtras)
                },
            )
        }
    }

    /** Candidate component class names for ADM AEditor (for tests / discovery). */
    fun admEditorClassCandidates(packageName: String): List<String> = when (packageName) {
        "com.dv.adm.pay" -> listOf(
            "com.dv.adm.AEditor",
            "com.dv.adm.pay.AEditor",
            "com.dv.get.AEditor",
        )
        else -> ADM_EDITOR_CLASSES
    }

    private fun buildGenericPackageViewIntent(
        url: String,
        fileName: String,
        packageName: String,
        request: Request,
    ): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra("com.android.extra.filename", fileName)
            putCookieAndUaExtras(request)
        }
    }

    private fun Intent.putCookieAndUaExtras(request: Request) {
        if (request.headers.isEmpty()) return
        val headerLines = request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        putExtra("headers", headerLines)
        putExtra("Cookies", request.headers["Cookie"] ?: request.headers["cookie"])
        putExtra("cookies", request.headers["Cookie"] ?: request.headers["cookie"])
        putExtra("useragent", request.headers["User-Agent"] ?: request.headers["user-agent"])
        putExtra("User-Agent", request.headers["User-Agent"] ?: request.headers["user-agent"])
        putExtra("referer", request.headers["Referer"] ?: request.headers["referer"])
        putExtra("Referer", request.headers["Referer"] ?: request.headers["referer"])
    }

    fun buildSendIntent(request: Request): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, request.url.trim())
            if (!request.title.isNullOrBlank()) {
                putExtra(Intent.EXTRA_TITLE, request.title)
                putExtra(Intent.EXTRA_SUBJECT, request.title)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Resolves and starts the best intent for [packageName], trying ADM class fallbacks.
     */
    fun startForPackage(context: Context, request: Request, packageName: String): Boolean {
        if (isAdmPackage(packageName)) {
            for (cls in admEditorClassCandidates(packageName)) {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(packageName, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Intent.EXTRA_TEXT, request.url.trim())
                    putExtra("com.android.extra.filename", sanitizeFileName(request.fileName))
                    putExtra("filename", sanitizeFileName(request.fileName))
                    putCookieAndUaExtras(request)
                }
                if (intent.resolveActivity(context.packageManager) == null) continue
                return try {
                    context.startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    continue
                }
            }
            // Last resort: package-scoped VIEW
            val fallback = buildGenericPackageViewIntent(
                request.url.trim(),
                sanitizeFileName(request.fileName),
                packageName,
                request,
            )
            if (fallback.resolveActivity(context.packageManager) != null) {
                return try {
                    context.startActivity(fallback)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
            return false
        }
        val intent = buildIntentForPackage(request, packageName)
        if (intent.resolveActivity(context.packageManager) == null) {
            // 1DM Downloader class may differ — try package-only VIEW
            val loose = buildGenericPackageViewIntent(
                request.url.trim(),
                sanitizeFileName(request.fileName),
                packageName,
                request,
            )
            if (loose.resolveActivity(context.packageManager) == null) return false
            return try {
                context.startActivity(loose)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun launch(
        activity: Activity,
        request: Request,
        forceChooser: Boolean = false,
    ): Boolean {
        val url = request.url.trim()
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            ExpDialogChrome.notify(
                activity,
                R.string.external_download_invalid_url,
                R.string.external_download_with_title,
            )
            return false
        }
        val looksLikeHls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
            request.mimeType?.contains("mpegurl", ignoreCase = true) == true ||
            request.mimeType?.contains("m3u8", ignoreCase = true) == true
        if (looksLikeHls) {
            ExpDialogChrome.notify(
                activity,
                R.string.external_download_hls_unsupported,
                R.string.external_download_with_title,
            )
            return false
        }
        if (!forceChooser) {
            val preferred = preferredPackage(activity)
            if (!preferred.isNullOrBlank() && startForPackage(activity, request, preferred)) {
                return true
            }
            for (pkg in installedCandidates(activity)) {
                if (startForPackage(activity, request, pkg)) {
                    rememberChosenPackage(pkg)
                    return true
                }
            }
            if (installedCandidates(activity).isEmpty()) {
                ExpDialogChrome.notify(
                    activity,
                    R.string.external_download_none_found,
                    R.string.external_download_with_title,
                )
                return false
            }
        }
        return launchChooser(activity, request)
    }

    private fun launchChooser(activity: Activity, request: Request): Boolean {
        // Prefer an explicit list of known managers over a blind VIEW chooser
        // (VIEW often lists browsers instead of download managers).
        val installed = installedCandidates(activity)
        if (installed.isNotEmpty()) {
            val labels = installed.toTypedArray()
            return try {
                val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.external_download_with_title)
                    .setItems(labels) { _, which ->
                        val pkg = installed.getOrNull(which) ?: return@setItems
                        if (startForPackage(activity, request, pkg)) {
                            rememberChosenPackage(pkg)
                            ExpDialogChrome.notify(activity, R.string.external_download_started)
                        } else {
                            ExpDialogChrome.notify(
                                activity,
                                R.string.external_download_none_found,
                                R.string.external_download_with_title,
                            )
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                builder.show()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Package picker failed", e)
                false
            }
        }
        // No known managers — try SEND of the URL so the user can pick any app.
        val send = buildSendIntent(request)
        val title = activity.getString(R.string.external_download_with_title)
        return try {
            if (send.resolveActivity(activity.packageManager) != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    val receiver = Intent(ACTION_DOWNLOADER_CHOSEN).apply {
                        setPackage(activity.packageName)
                    }
                    val pending = PendingIntent.getBroadcast(
                        activity,
                        request.url.hashCode(),
                        receiver,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    activity.startActivity(
                        Intent.createChooser(send, title, pending.intentSender),
                    )
                } else {
                    activity.startActivity(Intent.createChooser(send, title))
                }
                true
            } else {
                ExpDialogChrome.notify(
                    activity,
                    R.string.external_download_none_found,
                    R.string.external_download_with_title,
                )
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download chooser failed", e)
            ExpDialogChrome.notify(
                activity,
                R.string.external_download_none_found,
                R.string.external_download_with_title,
            )
            false
        }
    }

    fun chosenComponentReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    Intent.EXTRA_CHOSEN_COMPONENT,
                    ComponentName::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
            }
            rememberChosenPackage(component?.packageName)
            Log.i(TAG, "Remembered external downloader: ${component?.packageName}")
        }
    }
}
