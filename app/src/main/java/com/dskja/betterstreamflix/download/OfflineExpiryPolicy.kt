package com.dskja.betterstreamflix.download

import com.dskja.betterstreamflix.profiles.ProfileManager
import com.dskja.betterstreamflix.utils.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Offline Expiry Nudge — completed downloads untouched for [UNTOUCHED_MS]
 * get a single keep/delete prompt, then a cool-down ([COOLDOWN_MS]).
 *
 * “Unberührt” uses [untouchedAt] = max(completedAt, updatedAt, createdAt).
 * Keep / offline play bumps `updatedAt` to reset the clock.
 */
object OfflineExpiryPolicy {

    /** Age before a completed download is considered untouched. */
    val UNTOUCHED_MS: Long = TimeUnit.DAYS.toMillis(30)

    /** Gap between prompts after show / keep / delete / later. */
    val COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(14)

    /** Max items acted on in one nudge. */
    const val MAX_BATCH = 5

    /** Titles listed in the dialog body before “+N more”. */
    const val MAX_TITLES_IN_MESSAGE = 3

    fun untouchedAt(item: DownloadItemEntity): Long =
        maxOf(item.completedAt, item.updatedAt, item.createdAt)

    fun isStale(
        item: DownloadItemEntity,
        nowMs: Long = System.currentTimeMillis(),
        untouchedMs: Long = UNTOUCHED_MS,
    ): Boolean {
        if (item.state != DownloadItemState.COMPLETED.name) return false
        val touch = untouchedAt(item)
        if (touch <= 0L) return false
        return nowMs - touch >= untouchedMs
    }

    fun selectStale(
        items: List<DownloadItemEntity>,
        nowMs: Long = System.currentTimeMillis(),
        max: Int = MAX_BATCH,
    ): List<DownloadItemEntity> {
        if (max <= 0) return emptyList()
        return items
            .filter { isStale(it, nowMs) }
            .sortedBy { untouchedAt(it) }
            .take(max)
    }

    fun shouldPrompt(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ProfileManager.isKidsActive()) return false
        val last = maxOf(
            UserPreferences.offlineExpiryNudgeLastShownAtMs,
            UserPreferences.offlineExpiryNudgeLastDismissedAtMs,
        )
        if (last <= 0L) return true
        return nowMs - last >= COOLDOWN_MS
    }

    fun recordShown(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.offlineExpiryNudgeLastShownAtMs = nowMs
    }

    fun recordDismissed(nowMs: Long = System.currentTimeMillis()) {
        UserPreferences.offlineExpiryNudgeLastDismissedAtMs = nowMs
        if (UserPreferences.offlineExpiryNudgeLastShownAtMs <= 0L) {
            UserPreferences.offlineExpiryNudgeLastShownAtMs = nowMs
        }
    }

    fun formatTitleLines(
        items: List<DownloadItemEntity>,
        maxTitles: Int = MAX_TITLES_IN_MESSAGE,
    ): Pair<List<String>, Int> {
        val titles = items.map { it.title.trim().ifBlank { it.subtitle.trim() } }
            .filter { it.isNotEmpty() }
        val shown = titles.take(maxTitles)
        val extra = (titles.size - shown.size).coerceAtLeast(0)
        return shown to extra
    }
}
