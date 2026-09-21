package com.dskja.betterstreamflix.telegram

import android.util.Log
import com.dskja.betterstreamflix.support.SupportUrls
import com.dskja.betterstreamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Live community metadata for the invite page.
 * Telegram: public t.me preview (icon, title, subscribers) — no bot token required.
 * Discord: invites API with_counts (icon, name, members, online).
 */
object CommunityInviteRepository {

    private const val TAG = "CommunityInvite"
    private const val TELEGRAM_HANDLE = TelegramJoinGatePolicy.CHANNEL_HANDLE
    private const val DISCORD_INVITE_CODE = "R4F72rMUZ8"

    data class ChannelInfo(
        val title: String,
        val handleOrTag: String,
        val description: String?,
        val memberCount: Int?,
        val onlineCount: Int?,
        val iconUrl: String?,
        val openUrl: String,
        val appUrl: String? = null,
    )

    data class Snapshot(
        val telegram: ChannelInfo?,
        val discord: ChannelInfo?,
    )

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) {
        val tg = async { runCatching { fetchTelegram() }.onFailure { Log.w(TAG, "telegram meta failed", it) }.getOrNull() }
        val dc = async { runCatching { fetchDiscord() }.onFailure { Log.w(TAG, "discord meta failed", it) }.getOrNull() }
        Snapshot(telegram = tg.await(), discord = dc.await())
    }

    private fun fetchTelegram(): ChannelInfo {
        val html = httpGet("https://t.me/$TELEGRAM_HANDLE")
            ?: error("empty telegram preview")
        val title = metaContent(html, "og:title")?.trim().orEmpty()
            .ifBlank { TELEGRAM_HANDLE }
        val description = metaContent(html, "og:description")?.trim()
        val iconUrl = metaContent(html, "og:image")?.trim()
            ?: attrMatch(html, Pattern.compile("""tgme_page_photo_image"[^>]*src="([^"]+)""""))
        val members = parseTelegramMembers(html)
        return ChannelInfo(
            title = title,
            handleOrTag = "@$TELEGRAM_HANDLE",
            description = description,
            memberCount = members,
            onlineCount = null,
            iconUrl = iconUrl,
            openUrl = SupportUrls.TELEGRAM_URL,
            appUrl = SupportUrls.TELEGRAM_APP_URL,
        )
    }

    private fun fetchDiscord(): ChannelInfo {
        val body = httpGet("https://discord.com/api/v9/invites/$DISCORD_INVITE_CODE?with_counts=true")
            ?: error("empty discord invite")
        val json = JSONObject(body)
        val guild = json.optJSONObject("guild")
        val profile = json.optJSONObject("profile")
        val guildId = guild?.optString("id").orEmpty()
            .ifBlank { profile?.optString("id").orEmpty() }
        val iconHash = guild?.optString("icon")?.takeIf { it.isNotBlank() }
            ?: profile?.optString("icon_hash")?.takeIf { it.isNotBlank() }
        val title = guild?.optString("name")?.takeIf { it.isNotBlank() }
            ?: profile?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "BetterStreamflix"
        val members = json.optInt("approximate_member_count", -1).takeIf { it >= 0 }
            ?: profile?.optInt("member_count", -1)?.takeIf { it >= 0 }
        val online = json.optInt("approximate_presence_count", -1).takeIf { it >= 0 }
            ?: profile?.optInt("online_count", -1)?.takeIf { it >= 0 }
        val iconUrl = if (!guildId.isNullOrBlank() && !iconHash.isNullOrBlank()) {
            "https://cdn.discordapp.com/icons/$guildId/$iconHash.png?size=128"
        } else {
            null
        }
        return ChannelInfo(
            title = title,
            handleOrTag = "discord.gg/$DISCORD_INVITE_CODE",
            description = guild?.optString("description")?.takeIf { it.isNotBlank() },
            memberCount = members,
            onlineCount = online,
            iconUrl = iconUrl,
            openUrl = SupportUrls.DISCORD_URL,
        )
    }

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) BetterStreamflix/1.0")
            .header("Accept", "text/html,application/json,*/*")
            .get()
            .build()
        NetworkClient.default.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for $url")
                return null
            }
            return response.body?.string()
        }
    }

    private fun metaContent(html: String, property: String): String? {
        val patterns = listOf(
            Pattern.compile(
                """<meta\s+property=["']$property["']\s+content=["']([^"']+)["']""",
                Pattern.CASE_INSENSITIVE,
            ),
            Pattern.compile(
                """<meta\s+content=["']([^"']+)["']\s+property=["']$property["']""",
                Pattern.CASE_INSENSITIVE,
            ),
        )
        for (p in patterns) {
            val m = p.matcher(html)
            if (m.find()) return decodeHtml(m.group(1) ?: return null)
        }
        return null
    }

    private fun attrMatch(html: String, pattern: Pattern): String? {
        val m = pattern.matcher(html)
        return if (m.find()) m.group(1) else null
    }

    private fun parseTelegramMembers(html: String): Int? {
        val extra = Pattern.compile(
            """tgme_page_extra["']?\s*>\s*([^<]+)""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        if (extra.find()) {
            val text = extra.group(1)?.replace('\u202F', ' ')?.trim().orEmpty()
            val digits = text.filter { it.isDigit() }
            if (digits.isNotEmpty()) return digits.toIntOrNull()
        }
        val counter = Pattern.compile(
            """tgme_channel_info_counter[^>]*>\s*<span[^>]*>\s*([\d\s\u202F.,]+)\s*</span>""",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        if (counter.find()) {
            val digits = counter.group(1)?.filter { it.isDigit() }.orEmpty()
            if (digits.isNotEmpty()) return digits.toIntOrNull()
        }
        return null
    }

    private fun decodeHtml(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("\t", " ")
}

