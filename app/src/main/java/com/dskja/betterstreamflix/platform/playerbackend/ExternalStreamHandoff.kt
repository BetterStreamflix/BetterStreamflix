package com.dskja.betterstreamflix.platform.playerbackend

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.ExternalDownloadHandoff
import com.dskja.betterstreamflix.extractors.StreamMime
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Hands a resolved playable HTTP(S) (or local) stream URI to an external **video player**
 * via [Intent.ACTION_VIEW], with package-aware headers and optional remembered default.
 *
 * Download managers (ADM / 1DM) are excluded from Play with — use
 * [ExternalDownloadHandoff] / “Download with…” for those.
 */
object ExternalStreamHandoff {
    private const val TAG = "ExternalStreamHandoff"
    const val ACTION_PLAYER_CHOSEN = "com.dskja.betterstreamflix.ACTION_EXTERNAL_PLAYER_CHOSEN"

    /** Well-known video player packages preferred for Play with. */
    val KNOWN_PLAYER_PACKAGES = listOf(
        "org.videolan.vlc",
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "is.xyz.mpv",
        "com.brouken.player",
        "org.videolan.vlc.debug",
        "com.mxtech.videoplayer.beta",
    )

    /**
     * Packages that must never appear in Play with and must never be remembered
     * as the default external player.
     */
    val DOWNLOADER_PACKAGE_DENYLIST = (
        ExternalDownloadHandoff.CANDIDATE_PACKAGES + listOf(
            "com.vanda.admpro",
            "com.dv.get",
        )
    ).distinct()

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
            addCategory(Intent.CATEGORY_DEFAULT)
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

    /** Pure: whether [packageName] is a download manager (never a Play-with target). */
    fun isDownloaderPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (DOWNLOADER_PACKAGE_DENYLIST.any { it.equals(pkg, ignoreCase = true) }) return true
        if (ExternalDownloadHandoff.isAdmPackage(pkg)) return true
        if (ExternalDownloadHandoff.isOneDmPackage(pkg)) return true
        if (pkg.startsWith("com.dv.adm", ignoreCase = true)) return true
        if (pkg.startsWith("idm.internet.download.manager", ignoreCase = true)) return true
        return false
    }

    /** Pure: Play-with must use ACTION_VIEW + video categories, never download-manager packages. */
    fun isEligiblePlayerPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        return !isDownloaderPackage(pkg)
    }

    /**
     * Package ids that must be excluded from a Play-with chooser.
     * Pure helper for tests / EXTRA_EXCLUDE_COMPONENTS builders.
     */
    fun excludePackagesForPlayWith(candidatePackages: Collection<String>): List<String> {
        return candidatePackages.filter { isDownloaderPackage(it) }.distinct()
    }

    /**
     * Activity class names commonly registered by download managers for ACTION_VIEW.
     * Used when building [android.content.Intent.EXTRA_EXCLUDE_COMPONENTS].
     */
    fun downloaderExcludeClassNames(packageName: String): List<String> {
        if (!isDownloaderPackage(packageName)) return emptyList()
        return listOf(
            "$packageName.MainActivity",
            "$packageName.Downloader",
            "com.dv.adm.AEditor",
            "com.dv.get.AEditor",
            "com.dv.adm.pay.AEditor",
        ).distinct()
    }

    fun preferredPackage(context: Context): String? {
        val saved = UserPreferences.externalPlayerPackage.trim()
        if (saved.isNotEmpty()) {
            if (isDownloaderPackage(saved)) {
                // Corrupted default from an older polluted chooser — clear it.
                clearPreferredPackage()
            } else if (isPackageInstalled(context, saved)) {
                return saved
            }
        }
        return null
    }

    fun rememberChosenPackage(packageName: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        if (isDownloaderPackage(pkg)) {
            Log.w(TAG, "Refusing to remember downloader as external player: $pkg")
            return
        }
        UserPreferences.externalPlayerPackage = pkg
    }

    fun clearPreferredPackage() {
        UserPreferences.externalPlayerPackage = ""
    }

    /**
     * Discover installed **video players** for Play with.
     * Uses ACTION_VIEW + video/HLS mime probes and strips known download managers.
     */
    fun listInstalledPlayers(context: Context): List<ResolveInfo> {
        val pm = context.packageManager
        val seen = linkedMapOf<String, ResolveInfo>()
        val probeMimes = listOf(
            "video/*",
            "video/mp4",
            "application/vnd.apple.mpegurl",
            "application/x-mpegURL",
        )
        for (mime in probeMimes) {
            val probe = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.dskja.betterstreamflix.player_probe/video.mp4"), mime)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val matches = runCatching {
                pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrDefault(emptyList())
            for (ri in matches) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                if (!isEligiblePlayerPackage(pkg)) continue
                seen.putIfAbsent(pkg, ri)
            }
        }
        // HTTPS video probe catches players that ignore content:// probes.
        val httpsProbe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("https://example.com/video.mp4"), "video/*")
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val httpsMatches = runCatching {
            pm.queryIntentActivities(httpsProbe, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        for (ri in httpsMatches) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue
            if (!isEligiblePlayerPackage(pkg)) continue
            seen.putIfAbsent(pkg, ri)
        }
        // Ensure well-known players appear even when OEM query filters are odd.
        for (pkg in KNOWN_PLAYER_PACKAGES) {
            if (seen.containsKey(pkg)) continue
            if (!isPackageInstalled(context, pkg)) continue
            if (!isEligiblePlayerPackage(pkg)) continue
            val packaged = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("https://example.com/video.mp4"), "video/*")
                setPackage(pkg)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val ri = runCatching {
                pm.queryIntentActivities(packaged, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull()
            }.getOrNull()
            if (ri != null) seen[pkg] = ri
        }
        return seen.values.toList()
    }

    fun canResolve(context: Context): Boolean =
        preferredPackage(context) != null ||
            ExternalMpvBackend.preferredInstalledPackage(context) != null ||
            listInstalledPlayers(context).isNotEmpty()

    /**
     * @param forceChooser when true, always show the player picker (and remember the pick).
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
                    ?.takeIf { isEligiblePlayerPackage(it) }
            if (!preferred.isNullOrBlank() && isEligiblePlayerPackage(preferred)) {
                if (startForPackage(activity, resolved, request, preferred)) {
                    return true
                }
                if (preferred == UserPreferences.externalPlayerPackage) {
                    clearPreferredPackage()
                }
            }
        }
        return launchChooser(activity, resolved, request)
    }

    private fun startForPackage(
        activity: Activity,
        resolved: Resolved,
        request: Request,
        packageName: String,
    ): Boolean {
        if (!isEligiblePlayerPackage(packageName)) return false
        val direct = buildViewIntent(activity, resolved, request, packageName)
        grantUriIfNeeded(activity, resolved, packageName)
        if (direct.resolveActivity(activity.packageManager) == null) return false
        return try {
            activity.startActivity(direct)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Preferred player missing: ${e.message}")
            false
        }
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
        val players = listInstalledPlayers(activity)
        if (players.isEmpty()) {
            ExpDialogChrome.notify(
                activity,
                R.string.external_player_none_found,
                R.string.player_external_player_title,
            )
            return false
        }
        // Custom picker — never use a blind ACTION_VIEW system chooser, which
        // surfaces ADM/1DM that also register for http(s) / video VIEW.
        val pm = activity.packageManager
        val labels = players.map { ri ->
            buildString {
                append(ri.loadLabel(pm))
                append("\n")
                append(ri.activityInfo.packageName)
            }
        }.toTypedArray()
        return try {
            val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.player_external_player_title)
                .setItems(labels) { _, which ->
                    val ri = players.getOrNull(which) ?: return@setItems
                    val pkg = ri.activityInfo?.packageName ?: return@setItems
                    if (!isEligiblePlayerPackage(pkg)) {
                        ExpDialogChrome.notify(
                            activity,
                            R.string.external_player_none_found,
                            R.string.player_external_player_title,
                        )
                        return@setItems
                    }
                    val intent = buildViewIntent(activity, resolved, request, pkg).apply {
                        component = ComponentName(pkg, ri.activityInfo.name)
                    }
                    grantUriIfNeeded(activity, resolved, pkg)
                    try {
                        activity.startActivity(intent)
                        rememberChosenPackage(pkg)
                    } catch (e: Exception) {
                        Log.e(TAG, "Start player failed: $pkg", e)
                        ExpDialogChrome.notify(
                            activity,
                            R.string.external_player_none_found,
                            R.string.player_external_player_title,
                        )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
            builder.show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Player picker failed", e)
            ExpDialogChrome.notify(
                activity,
                R.string.external_player_none_found,
                R.string.player_external_player_title,
            )
            false
        }
    }

    fun chosenComponentReceiver(): android.content.BroadcastReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val component = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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
