package com.dskja.betterstreamflix.platform.playerbackend

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.extractors.StreamMime
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Hands a resolved playable HTTP(S) (or local) stream URI to an external player
 * via [Intent.ACTION_VIEW], with package-aware headers and optional remembered default.
 */
object ExternalStreamHandoff {
    private const val TAG = "ExternalStreamHandoff"
    const val ACTION_PLAYER_CHOSEN = "com.dskja.betterstreamflix.ACTION_EXTERNAL_PLAYER_CHOSEN"

    data class Request(
        val sourceUrl: String,
        val headers: Map<String, String> = emptyMap(),
        val title: String? = null,
        val positionMs: Long = 0L,
        val subtitleUri: String? = null,
        val mimeType: String? = null,
    )

    data class Resolved(
        val uri: Uri,
        val mimeType: String,
        val grantRead: Boolean,
        /** True when we wrote a local m3u8 that likely has relative segments — warn UX. */
        val fragilePlaylist: Boolean = false,
    )

    fun resolveSource(context: Context, request: Request): Resolved? {
        val raw = request.sourceUrl.trim()
        if (raw.isBlank()) return null
        val mime = StreamMime.coalesce(request.mimeType, raw) ?: "video/*"
        if (raw.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
            val playlist = decodeBase64Uri(raw) ?: return null
            val extracted = extractUrlFromPlaylist(playlist)
            if (!extracted.isNullOrBlank()) {
                return Resolved(Uri.parse(extracted), mime, grantRead = false)
            }
            // Relative-only playlists break in most external players over content://.
            return null
        }
        return Resolved(
            Uri.parse(raw),
            mime,
            grantRead = raw.startsWith("content://") || raw.startsWith("file://"),
        )
    }

    fun buildViewIntent(
        context: Context?,
        resolved: Resolved,
        request: Request,
        packageName: String?,
    ): Intent {
        val headerArray = request.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
        val headerLinesLf = request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val referer = request.headers.entries.firstOrNull {
            it.key.equals("Referer", ignoreCase = true)
        }?.value
        val userAgent = request.headers.entries.firstOrNull {
            it.key.equals("User-Agent", ignoreCase = true)
        }?.value

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(resolved.uri, resolved.mimeType)
            if (!packageName.isNullOrBlank()) setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (resolved.grantRead) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context != null) {
                    clipData = ClipData.newUri(context.contentResolver, "stream", resolved.uri)
                }
            }
            putExtra("return_result", true)
            if (request.positionMs > 0L) {
                putExtra("position", request.positionMs.toInt())
                putExtra("seek_position", request.positionMs)
            }
            if (!request.title.isNullOrBlank()) {
                putExtra("title", request.title)
                putExtra(Intent.EXTRA_TITLE, request.title)
            }
            if (!request.subtitleUri.isNullOrBlank()) {
                putExtra("subs", Uri.parse(request.subtitleUri))
                putExtra("subtitle", request.subtitleUri)
                putExtra("subtitles_location", request.subtitleUri)
            }
            applyPlayerHeaders(
                packageName = packageName,
                headerArray = headerArray,
                headerLinesLf = headerLinesLf,
                referer = referer,
                userAgent = userAgent,
                headers = request.headers,
            )
        }
    }

    /** Package-aware header extras (MX / mpv / Brouken / generic). */
    fun Intent.applyPlayerHeaders(
        packageName: String?,
        headerArray: Array<String>,
        headerLinesLf: String,
        referer: String?,
        userAgent: String?,
        headers: Map<String, String>,
    ) {
        if (headers.isEmpty()) return
        val pkg = packageName.orEmpty()
        when {
            pkg.startsWith("com.mxtech.videoplayer") -> {
                putExtra("headers", headerArray)
                referer?.let { putExtra("referer", it) }
                userAgent?.let { putExtra("user-agent", it) }
            }
            pkg == "is.xyz.mpv" || pkg == "com.brouken.player" -> {
                // Historical mpv-android / Just (Brouken) keys.
                putExtra("http-header-fields", headerLinesLf)
                putExtra("headers", headerArray)
                referer?.let {
                    putExtra("referrer", it)
                    putExtra("referer", it)
                }
                userAgent?.let { putExtra("user-agent", it) }
            }
            pkg == "org.videolan.vlc" -> {
                // VLC Android has no documented HTTP header intent extras.
                // Still attach generic keys some forks honor.
                putExtra("http-header-fields", headerLinesLf)
                referer?.let { putExtra("referrer", it) }
                userAgent?.let { putExtra("user-agent", it) }
            }
            else -> {
                putExtra("headers", headerArray)
                putExtra("extra_headers", headers.map { "${it.key}: ${it.value}" }.toTypedArray())
                putExtra("http-header-fields", headerLinesLf)
                putExtra("http_header_list", headerLinesLf)
                referer?.let {
                    putExtra("referer", it)
                    putExtra("referrer", it)
                }
                userAgent?.let { putExtra("user-agent", it) }
            }
        }
    }

    fun preferredPackage(context: Context): String? {
        val saved = UserPreferences.externalPlayerPackage.trim()
        if (saved.isNotEmpty() && isPackageInstalled(context, saved)) return saved
        return null
    }

    fun rememberChosenPackage(packageName: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        UserPreferences.externalPlayerPackage = pkg
    }

    fun clearPreferredPackage() {
        UserPreferences.externalPlayerPackage = ""
    }

    fun listInstalledPlayers(context: Context): List<ResolveInfo> {
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("https://example.com/video.mp4"), "video/*")
        }
        return context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo?.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
    }

    fun canResolve(context: Context): Boolean =
        preferredPackage(context) != null ||
            ExternalMpvBackend.preferredInstalledPackage(context) != null ||
            listInstalledPlayers(context).isNotEmpty()

    /**
     * @param forceChooser when true, always show the system chooser (and remember the pick).
     *                     when false, use the remembered default / installed candidate first.
     */
    fun launch(
        activity: Activity,
        request: Request,
        forceChooser: Boolean = false,
    ): Boolean {
        val resolved = resolveSource(activity, request)
        if (resolved == null) {
            ExpDialogChrome.notify(
                activity,
                R.string.player_external_player_error_video,
                R.string.player_external_player_title,
            )
            return false
        }
        if (!forceChooser) {
            val preferred = preferredPackage(activity)
                ?: ExternalMpvBackend.preferredInstalledPackage(activity)
            if (!preferred.isNullOrBlank()) {
                val direct = buildViewIntent(activity, resolved, request, preferred)
                grantUriIfNeeded(activity, resolved, preferred)
                if (direct.resolveActivity(activity.packageManager) != null) {
                    return try {
                        activity.startActivity(direct)
                        true
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "Preferred player missing: ${e.message}")
                        if (preferred == UserPreferences.externalPlayerPackage) {
                            clearPreferredPackage()
                        }
                        launchChooser(activity, resolved, request)
                    }
                }
            }
        }
        return launchChooser(activity, resolved, request)
    }

    private fun grantUriIfNeeded(activity: Activity, resolved: Resolved, packageName: String) {
        if (!resolved.grantRead) return
        runCatching {
            activity.grantUriPermission(
                packageName,
                resolved.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun launchChooser(
        activity: Activity,
        resolved: Resolved,
        request: Request,
    ): Boolean {
        val intent = buildViewIntent(activity, resolved, request, packageName = null)
        val players = listInstalledPlayers(activity)
        if (intent.resolveActivity(activity.packageManager) == null && players.isEmpty()) {
            ExpDialogChrome.notify(
                activity,
                R.string.external_player_none_found,
                R.string.player_external_player_title,
            )
            return false
        }
        return try {
            val chooserTitle = activity.getString(R.string.player_external_player_title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val receiver = Intent(ACTION_PLAYER_CHOSEN).apply {
                    setPackage(activity.packageName)
                }
                val pending = PendingIntent.getBroadcast(
                    activity,
                    request.sourceUrl.hashCode(),
                    receiver,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                activity.startActivity(
                    Intent.createChooser(intent, chooserTitle, pending.intentSender),
                )
            } else {
                activity.startActivity(Intent.createChooser(intent, chooserTitle))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Chooser failed", e)
            ExpDialogChrome.notify(
                activity,
                R.string.external_player_none_found,
                R.string.player_external_player_title,
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
            Log.i(TAG, "Remembered external player: ${component?.packageName}")
        }
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    fun decodeBase64Uri(dataUri: String): String? {
        val comma = dataUri.indexOf(',')
        if (comma < 0) return null
        return runCatching {
            String(Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT))
        }.getOrNull()
    }

    fun extractUrlFromPlaylist(playlist: String): String? {
        return playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotEmpty() &&
                    !line.startsWith("#") &&
                    (line.startsWith("http://") || line.startsWith("https://"))
            }
    }
}
