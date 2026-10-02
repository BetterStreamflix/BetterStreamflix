package com.dskja.betterstreamflix.download

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Hands a resolved stream URL to an external download manager (ADM and similar)
 * via documented package intents plus generic VIEW/SEND fallbacks.
 */
object ExternalDownloadHandoff {
    private const val TAG = "ExternalDownloadHandoff"

    val CANDIDATE_PACKAGES = listOf(
        "com.dv.adm",
        "com.dv.adm.pay",
        "com.dv.adm.old",
        "idm.internet.download.manager",
        "idm.internet.download.manager.plus",
        "com.vanda.admpro",
        "com.aefyr.sai",
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
        preferredPackage(context) != null || canResolveGeneric(context)

    fun rememberChosenPackage(packageName: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        UserPreferences.externalDownloaderPackage = pkg
    }

    fun clearPreferredPackage() {
        UserPreferences.externalDownloaderPackage = ""
    }

    fun buildAdmIntent(request: Request, packageName: String): Intent {
        val uri = Uri.parse(request.url.trim())
        val headerLines = request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_TEXT, request.url)
            putExtra("com.android.extra.filename", sanitizeFileName(request.fileName))
            if (!request.fileName.isNullOrBlank()) {
                putExtra("filename", sanitizeFileName(request.fileName))
                putExtra("file_name", sanitizeFileName(request.fileName))
            }
            if (!request.title.isNullOrBlank()) {
                putExtra(Intent.EXTRA_TITLE, request.title)
                putExtra("title", request.title)
            }
            if (headerLines.isNotEmpty()) {
                putExtra("headers", headerLines)
                putExtra("http_header_list", headerLines)
                putExtra(
                    "extra_headers",
                    request.headers.map { "${it.key}: ${it.value}" }.toTypedArray(),
                )
                request.headers["Referer"]?.let {
                    putExtra("referer", it)
                    putExtra("Referer", it)
                }
                request.headers["User-Agent"]?.let {
                    putExtra("useragent", it)
                    putExtra("User-Agent", it)
                }
                request.headers["Cookie"]?.let { putExtra("cookies", it) }
            }
            request.mimeType?.takeIf { it.isNotBlank() }?.let { type ->
                setDataAndType(uri, type)
            }
        }
    }

    fun buildGenericViewIntent(request: Request): Intent {
        val uri = Uri.parse(request.url.trim())
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_TEXT, request.url)
            if (!request.fileName.isNullOrBlank()) {
                putExtra("com.android.extra.filename", sanitizeFileName(request.fileName))
            }
        }
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
     * Opens ADM (or similar) with the URL, falling back to a chooser.
     * Returns false when no handler is available.
     */
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
        if (!forceChooser) {
            val preferred = preferredPackage(activity)
            if (!preferred.isNullOrBlank()) {
                val intent = buildAdmIntent(request, preferred)
                if (intent.resolveActivity(activity.packageManager) != null) {
                    return try {
                        activity.startActivity(intent)
                        true
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "Preferred downloader missing: ${e.message}")
                        clearPreferredPackage()
                        launchChooser(activity, request)
                    }
                }
            }
            for (pkg in CANDIDATE_PACKAGES) {
                if (!isPackageInstalled(activity, pkg)) continue
                val intent = buildAdmIntent(request, pkg)
                if (intent.resolveActivity(activity.packageManager) == null) continue
                return try {
                    activity.startActivity(intent)
                    rememberChosenPackage(pkg)
                    true
                } catch (_: ActivityNotFoundException) {
                    continue
                }
            }
        }
        return launchChooser(activity, request)
    }

    private fun launchChooser(activity: Activity, request: Request): Boolean {
        val view = buildGenericViewIntent(request)
        val send = buildSendIntent(request)
        val title = activity.getString(R.string.external_download_with_title)
        return try {
            when {
                view.resolveActivity(activity.packageManager) != null -> {
                    activity.startActivity(Intent.createChooser(view, title))
                    true
                }
                send.resolveActivity(activity.packageManager) != null -> {
                    activity.startActivity(Intent.createChooser(send, title))
                    true
                }
                else -> {
                    ExpDialogChrome.notify(
                        activity,
                        R.string.external_download_none_found,
                        R.string.external_download_with_title,
                    )
                    false
                }
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

    private fun canResolveGeneric(context: Context): Boolean {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/file.mp4"))
        return probe.resolveActivity(context.packageManager) != null
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
}
