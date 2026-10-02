package com.dskja.betterstreamflix.platform.playerbackend

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.extractors.StreamMime
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.UserPreferences
import java.io.File
import java.io.FileOutputStream

/**
 * Hands a resolved playable HTTP(S) (or local) stream URI to an external player
 * via [Intent.ACTION_VIEW], with optional remembered default package.
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
            return try {
                val file = File(context.cacheDir, "stream_external.m3u8")
                FileOutputStream(file).use { it.write(playlist.toByteArray()) }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file,
                )
                Resolved(uri, "application/vnd.apple.mpegurl", grantRead = true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to materialize HLS playlist: ${e.message}")
                null
            }
        }
        return Resolved(Uri.parse(raw), mime, grantRead = raw.startsWith("content://"))
    }

    fun buildViewIntent(
        resolved: Resolved,
        request: Request,
        packageName: String?,
    ): Intent {
        val headerArray = request.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
        val headerLines = request.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(resolved.uri, resolved.mimeType)
            if (!packageName.isNullOrBlank()) setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (resolved.grantRead) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (headerArray.isNotEmpty()) {
                putExtra("headers", headerArray)
                putExtra("extra_headers", request.headers.map { "${it.key}: ${it.value}" }.toTypedArray())
                putExtra("http_header_list", headerLines)
                request.headers["Referer"]?.let { putExtra("referer", it) }
                request.headers["User-Agent"]?.let { putExtra("user-agent", it) }
            }
            if (request.positionMs > 0L) {
                putExtra("position", request.positionMs.toInt())
                putExtra("seek_position", request.positionMs)
            }
            putExtra("return_result", true)
            if (!request.title.isNullOrBlank()) {
                putExtra("title", request.title)
                putExtra(Intent.EXTRA_TITLE, request.title)
            }
            if (!request.subtitleUri.isNullOrBlank()) {
                putExtra("subs", Uri.parse(request.subtitleUri))
                putExtra("subtitle", request.subtitleUri)
                putExtra("subtitles_location", request.subtitleUri)
            }
        }
    }

    fun preferredPackage(context: Context): String? {
        val saved = UserPreferences.externalPlayerPackage.trim()
        if (saved.isNotEmpty() && isPackageInstalled(context, saved)) return saved
        return ExternalMpvBackend.preferredInstalledPackage(context)
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
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        return context.packageManager.queryIntentActivities(probe, flags)
            .filter { it.activityInfo?.packageName != context.packageName }
    }

    fun canResolve(context: Context): Boolean =
        preferredPackage(context) != null || listInstalledPlayers(context).isNotEmpty()

    /**
     * Launch an external player. Returns false when nothing can handle the intent.
     * When [forceChooser] is true, always shows the system chooser and remembers the pick.
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
        val preferred = if (forceChooser) null else preferredPackage(activity)
        if (!preferred.isNullOrBlank()) {
            val direct = buildViewIntent(resolved, request, preferred)
            if (direct.resolveActivity(activity.packageManager) != null) {
                return try {
                    activity.startActivity(direct)
                    true
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "Preferred player missing: ${e.message}")
                    clearPreferredPackage()
                    launchChooser(activity, resolved, request)
                }
            }
        }
        return launchChooser(activity, resolved, request)
    }

    private fun launchChooser(
        activity: Activity,
        resolved: Resolved,
        request: Request,
    ): Boolean {
        val intent = buildViewIntent(resolved, request, packageName = null)
        if (intent.resolveActivity(activity.packageManager) == null &&
            listInstalledPlayers(activity).isEmpty()
        ) {
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
                    android.content.ComponentName::class.java,
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
