package com.dskja.betterstreamflix.providers

import android.content.Context
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.models.Video

/**
 * “Warum dieser Server” — explains why playback picked a hoster.
 * Combines preferred name, manual pins, A/B learning, and failover hops.
 */
object ServerWhy {

    enum class Reason {
        PINNED,
        PREFERRED,
        LEARNED,
        FAILOVER,
        STUCK_SWITCH,
        DEFAULT,
    }

    data class Explanation(
        val reason: Reason,
        val serverName: String,
        val detail: String? = null,
    )

    @Volatile
    private var last: Explanation? = null

    @Volatile
    private var pendingFailover = false

    @Volatile
    private var pendingStuckSwitch = false

    fun markFailoverHop() {
        pendingFailover = true
    }

    fun markStuckSwitch() {
        pendingStuckSwitch = true
        pendingFailover = true
    }

    fun clearSessionFlags() {
        pendingFailover = false
        pendingStuckSwitch = false
        last = null
    }

    fun lastExplanation(): Explanation? = last

    fun explainSelection(
        providerName: String,
        server: Video.Server,
        preferredServerName: String?,
        orderedServers: List<Video.Server>,
        nowMs: Long = System.currentTimeMillis(),
    ): Explanation {
        val name = server.name.trim().ifBlank { server.id }
        val stuck = pendingStuckSwitch
        val failover = pendingFailover
        pendingStuckSwitch = false
        pendingFailover = false

        val explanation = when {
            stuck -> Explanation(Reason.STUCK_SWITCH, name)
            failover -> Explanation(Reason.FAILOVER, name)
            ProviderServerPriority.isPinned(providerName, server) ->
                Explanation(Reason.PINNED, name)
            !preferredServerName.isNullOrBlank() &&
                server.name.equals(preferredServerName, ignoreCase = true) ->
                Explanation(Reason.PREFERRED, name)
            isLearnedLead(providerName, server, orderedServers, nowMs) ->
                Explanation(
                    Reason.LEARNED,
                    name,
                    detail = learnedScoreLabel(providerName, server, nowMs),
                )
            else -> Explanation(Reason.DEFAULT, name)
        }
        last = explanation
        return explanation
    }

    fun formatLine(context: Context, explanation: Explanation): String {
        val server = explanation.serverName.ifBlank {
            context.getString(R.string.provider_trust_server_unknown)
        }
        return when (explanation.reason) {
            Reason.PINNED ->
                context.getString(R.string.server_why_pinned, server)
            Reason.PREFERRED ->
                context.getString(R.string.server_why_preferred, server)
            Reason.LEARNED ->
                context.getString(R.string.server_why_learned, server)
            Reason.FAILOVER ->
                context.getString(R.string.server_why_failover, server)
            Reason.STUCK_SWITCH ->
                context.getString(R.string.server_why_stuck, server)
            Reason.DEFAULT ->
                context.getString(R.string.server_why_default, server)
        }
    }

    fun formatShortLabel(context: Context, reason: Reason): String = when (reason) {
        Reason.PINNED -> context.getString(R.string.server_why_badge_pinned)
        Reason.PREFERRED -> context.getString(R.string.server_why_badge_preferred)
        Reason.LEARNED -> context.getString(R.string.server_why_badge_learned)
        Reason.FAILOVER -> context.getString(R.string.server_why_badge_failover)
        Reason.STUCK_SWITCH -> context.getString(R.string.server_why_badge_stuck)
        Reason.DEFAULT -> ""
    }

    fun reasonForServer(
        providerName: String,
        server: Video.Server,
        preferredServerName: String?,
        orderedServers: List<Video.Server>,
        nowMs: Long = System.currentTimeMillis(),
    ): Reason {
        return when {
            ProviderServerPriority.isPinned(providerName, server) -> Reason.PINNED
            !preferredServerName.isNullOrBlank() &&
                server.name.equals(preferredServerName, ignoreCase = true) -> Reason.PREFERRED
            isLearnedLead(providerName, server, orderedServers, nowMs) -> Reason.LEARNED
            else -> Reason.DEFAULT
        }
    }

    private fun isLearnedLead(
        providerName: String,
        server: Video.Server,
        orderedServers: List<Video.Server>,
        nowMs: Long,
    ): Boolean {
        val top = ProviderServerAb.topServers(providerName, limit = 1, nowMs = nowMs)
            .firstOrNull() ?: return false
        val key = ProviderServerAb.serverKey(server)
        if (key != top.first) return false
        // Only badge when it actually leads the ordered list (or is second after preferred/pin).
        val lead = orderedServers.firstOrNull() ?: return false
        if (ProviderServerAb.serverKey(lead) == key) return true
        val second = orderedServers.getOrNull(1) ?: return false
        return ProviderServerAb.serverKey(second) == key
    }

    private fun learnedScoreLabel(
        providerName: String,
        server: Video.Server,
        nowMs: Long,
    ): String? {
        val stats = ProviderServerAb.topServers(providerName, limit = 8, nowMs = nowMs)
            .firstOrNull { it.first == ProviderServerAb.serverKey(server) }
            ?.second ?: return null
        if (stats.samples <= 0) return null
        val pct = ((stats.successes.toDouble() / stats.samples) * 100.0).toInt().coerceIn(0, 100)
        return "$pct%"
    }
}
