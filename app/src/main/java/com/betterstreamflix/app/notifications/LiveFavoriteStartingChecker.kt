package com.betterstreamflix.app.notifications

import android.content.Context
import android.util.Log
import com.betterstreamflix.app.R
import com.betterstreamflix.app.models.TvShow
import com.betterstreamflix.app.utils.LiveHubCatalog
import com.betterstreamflix.app.utils.UserPreferences

/**
 * Live Hub favorite reminders (favorites only):
 * - Prefer “5 minutes before kickoff” when a clock time is parseable.
 * - Otherwise one ping when a favorite newly appears live (with program evidence).
 * - Snooze + hourly/run caps prevent floods.
 *
 * First successful snapshot after opt-in is a silent baseline.
 * Empty→non-empty after that re-baselines silently (no notify flood).
 */
object LiveFavoriteStartingChecker {
    private const val TAG = "LiveFavNotify"

    suspend fun check(context: Context, budget: Int): Int {
        if (budget <= 0) return 0
        if (!UserPreferences.notifyLiveFavoriteStarting) return 0
        if (LiveReminderArm.deliveryBlocked()) return 0

        val hub = LiveHubCatalog.load(
            sportLabel = context.getString(R.string.live_hub_section_sport),
            newsLabel = context.getString(R.string.live_hub_section_news),
            channelsLabel = context.getString(R.string.live_hub_section_channels),
            favoritesLabel = context.getString(R.string.live_hub_section_favorites),
            context = context,
        )
        val hubShows = hub.categories
            .flatMap { it.list }
            .filterIsInstance<TvShow>()
        val liveFavorites = LiveHubCatalog.resolveFavoriteChannels(context, hubShows)
        // Tie schedules to the Live Hub favorites shelf even when empty.
        LiveReminderScheduler.syncFromFavorites(context, liveFavorites)

        if (liveFavorites.isEmpty()) {
            if (!UserPreferences.notifyLiveBaselineReady) {
                UserPreferences.notifyLiveActiveKeys = emptySet()
                UserPreferences.notifyLiveBaselineReady = true
            } else {
                UserPreferences.notifyLiveActiveKeys = emptySet()
            }
            return 0
        }

        val current = LinkedHashMap<String, TvShow>()
        for (show in liveFavorites) {
            current[LiveHubCatalog.identityKey(show)] = show
        }
        val currentKeys = current.keys

        if (!UserPreferences.notifyLiveBaselineReady) {
            UserPreferences.notifyLiveActiveKeys = currentKeys
            UserPreferences.notifyLiveBaselineReady = true
            Log.d(TAG, "baseline ${currentKeys.size} live favorites (silent)")
            return 0
        }

        val previous = UserPreferences.notifyLiveActiveKeys
        // Empty→non-empty: re-baseline silently once more (no newly-live flood).
        val fromEmptyBaseline = previous.isEmpty()
        val newlyLiveKeys = if (fromEmptyBaseline) {
            emptySet()
        } else {
            currentKeys - previous
        }
        UserPreferences.notifyLiveActiveKeys = currentKeys
        if (fromEmptyBaseline) {
            Log.d(TAG, "re-baseline ${currentKeys.size} after empty (silent)")
        }

        var sent = 0
        val now = System.currentTimeMillis()
        val liveBudget = minOf(budget, LiveReminderPolicy.MAX_LIVE_PER_RUN)
        var recentFires = LiveReminderStore.recentFires().toMutableList()

        for (key in currentKeys) {
            if (sent >= liveBudget) break
            val show = current[key] ?: continue
            val kickoff = LiveReminderPolicy.parseKickoffEpochMs(
                title = show.title,
                overview = show.overview,
                nowMs = now,
            )
            val program = show.programNow?.trim()?.takeIf { it.isNotBlank() }
                ?: LiveHubCatalog.programNowOf(show)
            val programEvidence = !program.isNullOrBlank()
            // Newly-live requires parseable kickoff or program evidence (LR-006).
            val newlyLive = key in newlyLiveKeys && (kickoff != null || programEvidence)

            val kickoffFp = kickoff?.toString()
            val alreadyThisKickoff = kickoffFp != null &&
                LiveReminderStore.kickoffFingerprint(key) == kickoffFp

            val decision = LiveReminderPolicy.decide(
                nowMs = now,
                kickoffEpochMs = kickoff,
                newlyLive = newlyLive,
                gate = LiveReminderPolicy.Gate(
                    snoozedUntilMs = LiveReminderStore.snoozedUntil(key),
                    lastNotifiedMs = LiveReminderStore.lastNotifiedMs(key),
                    recentFireTimesMs = recentFires,
                    alreadyNotifiedThisKickoff = alreadyThisKickoff,
                ),
            )

            when (decision) {
                is LiveReminderPolicy.Decision.ScheduleAt -> {
                    LiveReminderScheduler.schedule(
                        context = context,
                        identityKey = key,
                        title = show.title,
                        fireAtMs = decision.fireAtMs,
                        nowMs = now,
                        kickoffEpochMs = kickoff
                            ?: (decision.fireAtMs + LiveReminderPolicy.LEAD_BEFORE_KICKOFF_MS),
                    )
                }
                is LiveReminderPolicy.Decision.NotifyNow -> {
                    // Empty→nonempty without kickoff: baseline already updated; skip flood.
                    if (fromEmptyBaseline &&
                        decision.reason == LiveReminderPolicy.Reason.NEWLY_LIVE
                    ) {
                        continue
                    }
                    ProductNotifier.notifyLiveStarting(
                        context = context,
                        showTitle = show.title,
                        programHint = program,
                        identityKey = key,
                        preKickoff = decision.reason == LiveReminderPolicy.Reason.PRE_KICKOFF_WINDOW,
                        kickoffEpochMs = kickoff,
                    )
                    LiveReminderStore.markNotified(key, now)
                    if (kickoffFp != null) {
                        LiveReminderStore.setKickoffFingerprint(key, kickoffFp)
                    }
                    LiveReminderScheduler.cancel(context, key)
                    recentFires += now
                    recentFires = LiveReminderPolicy.pruneRecentFires(recentFires, now).toMutableList()
                    sent++
                    Log.i(TAG, "live reminder (${decision.reason}): ${show.title}")
                }
                LiveReminderPolicy.Decision.Skip -> Unit
            }
        }
        return sent
    }

    fun resetBaseline() {
        UserPreferences.notifyLiveBaselineReady = false
        UserPreferences.notifyLiveActiveKeys = emptySet()
    }
}
