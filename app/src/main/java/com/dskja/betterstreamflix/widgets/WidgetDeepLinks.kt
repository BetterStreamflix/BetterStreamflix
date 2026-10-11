package com.dskja.betterstreamflix.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dskja.betterstreamflix.activities.main.MainMobileActivity
import com.dskja.betterstreamflix.activities.main.MainTvActivity
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Live home-screen widget deep links.
 *
 * Continue Watching reuses [com.dskja.betterstreamflix.tvlauncher.TvWatchDeepLink]
 * (`betterstreamflix://watch?…`). Live tiles use:
 * - `betterstreamflix://live` → Live Hub tab
 * - `betterstreamflix://live?id=…&provider=…` → live channel player
 */
object WidgetDeepLinks {
    const val SCHEME = "betterstreamflix"
    const val HOST_LIVE = "live"

    private val LIVE_PATTERN = Regex(
        "^betterstreamflix://live(?:\\?(.+))?$",
        RegexOption.IGNORE_CASE,
    )

    sealed class Target {
        data object LiveHub : Target()

        data class LiveChannel(
            val id: String,
            val provider: String,
            val title: String,
            val poster: String?,
        ) : Target()
    }

    fun buildLiveHubUri(): String = "$SCHEME://$HOST_LIVE"

    fun buildLiveChannelUri(
        id: String,
        provider: String,
        title: String,
        poster: String? = null,
    ): String {
        val query = listOf(
            "id" to id,
            "provider" to provider,
            "title" to title,
            "poster" to poster.orEmpty(),
        ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "$SCHEME://$HOST_LIVE?$query"
    }

    fun parse(uri: Uri?): Target? = parse(uri?.toString())

    /** JVM-safe parser for unit tests and intent handlers. */
    fun parse(raw: String?): Target? {
        if (raw.isNullOrBlank()) return null
        val match = LIVE_PATTERN.find(raw.trim()) ?: return null
        val q = queryMap(match.groupValues.getOrNull(1).orEmpty())
        val id = q["id"].orEmpty().trim()
        if (id.isEmpty()) return Target.LiveHub
        val provider = q["provider"].orEmpty().trim()
        if (provider.isEmpty()) return Target.LiveHub
        return Target.LiveChannel(
            id = id,
            provider = provider,
            title = q["title"].orEmpty().ifBlank { id },
            poster = q["poster"]?.takeIf { it.isNotBlank() },
        )
    }

    fun openIntent(context: Context, deepLink: String): Intent {
        val isTv = context.packageManager.hasSystemFeature("android.software.leanback")
        return Intent(
            context,
            if (isTv) MainTvActivity::class.java else MainMobileActivity::class.java,
        ).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(deepLink)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            `package` = context.packageName
        }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun queryMap(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = dec(pair.substring(0, idx))
            val value = dec(pair.substring(idx + 1))
            key to value
        }.toMap()
    }

    private fun dec(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }
}
