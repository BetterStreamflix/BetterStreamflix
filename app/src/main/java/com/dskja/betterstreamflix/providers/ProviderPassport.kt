package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.player.SerienStreamBypassHelper
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Per-provider Login / Captcha passport — Settings + picker surface for
 * “Sitzung frisch” / “Captcha fällig” (and login-due) on Mobile + TV.
 *
 * Distinct from [ProviderHealthDashboard] (dead/slow probes): this answers
 * whether the user’s session / CF clearance is ready for play.
 */
object ProviderPassport {

    enum class Status {
        /** Clearance (or token session) is ready — “Sitzung frisch”. */
        SESSION_FRESH,

        /** Account/session present but stream gate still needs captcha — “Captcha fällig”. */
        CAPTCHA_DUE,

        /** No usable login — sign-in required. */
        LOGIN_DUE,
    }

    enum class Kind {
        /** SerienStream / AniWorld style CF clearance + account jar. */
        CLEARANCE_GATE,

        /** Token/password session (GuardaFlix) — no CF captcha layer. */
        ACCOUNT_TOKEN,
    }

    enum class ActionTarget {
        SERIENSTREAM_AUTH,
        ANIWORLD_AUTH,
        GUARDAFLIX_AUTH,
        NONE,
    }

    data class Signals(
        val kind: Kind,
        val hasClearance: Boolean = false,
        val isLoggedIn: Boolean = false,
        val hasWebSession: Boolean = false,
        val displayName: String? = null,
        val domain: String? = null,
        val lastValidatedOk: Boolean? = null,
        val lastValidatedAtMs: Long? = null,
    )

    data class Row(
        val name: String,
        val status: Status,
        val kind: Kind,
        val actionTarget: ActionTarget,
        val displayName: String? = null,
        val domain: String? = null,
        val detail: String? = null,
        val lastValidatedAtMs: Long? = null,
        val lastValidatedOk: Boolean? = null,
    )

    data class Snapshot(
        val rows: List<Row>,
        val freshCount: Int,
        val captchaDueCount: Int,
        val loginDueCount: Int,
        val probedAtMs: Long,
    ) {
        val attentionCount: Int get() = captchaDueCount + loginDueCount
    }

    data class Entry(
        val name: String,
        val actionTarget: ActionTarget,
        val signals: Signals,
    )

    /** Pure status resolution — unit-tested without Android prefs. */
    fun resolveStatus(signals: Signals): Status = when (signals.kind) {
        Kind.CLEARANCE_GATE -> when {
            signals.hasClearance -> Status.SESSION_FRESH
            signals.isLoggedIn || signals.hasWebSession -> Status.CAPTCHA_DUE
            else -> Status.LOGIN_DUE
        }
        Kind.ACCOUNT_TOKEN -> when {
            signals.isLoggedIn -> Status.SESSION_FRESH
            else -> Status.LOGIN_DUE
        }
    }

    fun snapshotFromEntries(
        entries: List<Entry>,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot {
        val rows = entries.map { entry ->
            val status = resolveStatus(entry.signals)
            Row(
                name = entry.name,
                status = status,
                kind = entry.signals.kind,
                actionTarget = entry.actionTarget,
                displayName = entry.signals.displayName,
                domain = entry.signals.domain,
                detail = detailFor(entry.signals, status),
                lastValidatedAtMs = entry.signals.lastValidatedAtMs,
                lastValidatedOk = entry.signals.lastValidatedOk,
            )
        }.sortedWith(
            compareBy<Row> { statusRank(it.status) }
                .thenBy { it.name.lowercase() },
        )
        return Snapshot(
            rows = rows,
            freshCount = rows.count { it.status == Status.SESSION_FRESH },
            captchaDueCount = rows.count { it.status == Status.CAPTCHA_DUE },
            loginDueCount = rows.count { it.status == Status.LOGIN_DUE },
            probedAtMs = nowMs,
        )
    }

    fun snapshotFromRuntime(nowMs: Long = System.currentTimeMillis()): Snapshot {
        return snapshotFromEntries(runtimeEntries(), nowMs)
    }

    /**
     * Re-read jars and optionally live-validate SerienStream / AniWorld account
     * probes so Settings can refresh “Sitzung frisch” after login/captcha.
     */
    suspend fun refresh(validateSessions: Boolean = true): Snapshot =
        withContext(Dispatchers.IO) {
            if (validateSessions) {
                coroutineScope {
                    val ss = async {
                        runCatching { SerienStreamAuthManager.validateSession() }
                    }
                    val aw = async {
                        runCatching { AniWorldAuthManager.validateSession() }
                    }
                    ss.await()
                    aw.await()
                }
            }
            snapshotFromRuntime()
        }

    /** Status for picker badges — null when the provider has no passport. */
    fun statusForProvider(provider: Provider): Status? {
        val entry = runtimeEntries().firstOrNull { it.name == provider.name } ?: return null
        return resolveStatus(entry.signals)
    }

    fun statusForProviderName(name: String): Status? {
        val entry = runtimeEntries().firstOrNull {
            it.name.equals(name, ignoreCase = true)
        } ?: return null
        return resolveStatus(entry.signals)
    }

    fun supportsPassport(provider: Provider): Boolean =
        provider is SerienStreamProvider ||
            provider is AniWorldProvider ||
            provider is GuardaFlixProvider

    private fun runtimeEntries(): List<Entry> = listOf(
        serienStreamEntry(),
        aniWorldEntry(),
        guardaFlixEntry(),
    )

    private fun serienStreamEntry(): Entry {
        val cookies = SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.serienStreamSessionCookies,
        )
        val snap = SerienStreamAuthManager.snapshot()
        return Entry(
            name = SerienStreamProvider.name,
            actionTarget = ActionTarget.SERIENSTREAM_AUTH,
            signals = Signals(
                kind = Kind.CLEARANCE_GATE,
                hasClearance = SerienStreamBypassHelper.looksLikeClearanceSolved(cookies),
                isLoggedIn = snap.isLoggedIn,
                hasWebSession = SerienStreamBypassHelper.hasWebSessionCookie(cookies),
                displayName = snap.displayName,
                domain = snap.domain,
                lastValidatedOk = snap.lastValidatedOk,
                lastValidatedAtMs = snap.lastValidatedAtMs,
            ),
        )
    }

    private fun aniWorldEntry(): Entry {
        val cookies = SerienStreamBypassHelper.sanitizeSessionCookies(
            UserPreferences.aniWorldSessionCookies,
        )
        val snap = AniWorldAuthManager.snapshot()
        return Entry(
            name = AniWorldProvider.name,
            actionTarget = ActionTarget.ANIWORLD_AUTH,
            signals = Signals(
                kind = Kind.CLEARANCE_GATE,
                hasClearance = SerienStreamBypassHelper.looksLikeClearanceSolved(cookies),
                isLoggedIn = snap.isLoggedIn,
                hasWebSession = SerienStreamBypassHelper.hasWebSessionCookie(cookies),
                displayName = snap.displayName,
                domain = snap.domain,
                lastValidatedOk = snap.lastValidatedOk,
                lastValidatedAtMs = snap.lastValidatedAtMs,
            ),
        )
    }

    private fun guardaFlixEntry(): Entry {
        val loggedIn = GuardaFlixProvider.isLoggedIn()
        val username = GuardaFlixProvider.authUsername().takeIf { it.isNotBlank() }
        return Entry(
            name = GuardaFlixProvider.name,
            actionTarget = ActionTarget.GUARDAFLIX_AUTH,
            signals = Signals(
                kind = Kind.ACCOUNT_TOKEN,
                isLoggedIn = loggedIn,
                displayName = username,
                domain = runCatching {
                    android.net.Uri.parse(GuardaFlixProvider.baseUrl).host
                }.getOrNull(),
            ),
        )
    }

    private fun detailFor(signals: Signals, status: Status): String? {
        return when {
            !signals.displayName.isNullOrBlank() -> signals.displayName
            status == Status.SESSION_FRESH && signals.lastValidatedOk == true -> "validated"
            status == Status.CAPTCHA_DUE && signals.isLoggedIn -> "logged_in_no_clearance"
            status == Status.LOGIN_DUE -> "signed_out"
            else -> signals.domain
        }
    }

    private fun statusRank(status: Status): Int = when (status) {
        Status.CAPTCHA_DUE -> 0
        Status.LOGIN_DUE -> 1
        Status.SESSION_FRESH -> 2
    }
}
