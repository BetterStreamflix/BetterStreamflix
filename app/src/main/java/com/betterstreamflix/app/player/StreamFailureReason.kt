package com.betterstreamflix.app.player

import android.content.Context
import com.betterstreamflix.app.R
import com.betterstreamflix.app.providers.AniWorldProvider
import com.betterstreamflix.app.providers.Provider
import com.betterstreamflix.app.providers.ProviderHealth
import com.betterstreamflix.app.providers.SerienStreamProvider
import com.betterstreamflix.app.providers.TmdbProvider

/**
 * User-facing classification for “why no stream?” — replaces opaque extractor /
 * Exo messages with captcha / domain / geo / timeout copy and optional one-tap CTAs.
 */
object StreamFailureReason {

    enum class Kind {
        CAPTCHA,
        DOMAIN_DEAD,
        GEO,
        TIMEOUT,
        ALL_SERVERS,
        GENERIC,
    }

    enum class Action {
        SOLVE_CAPTCHA,
        UPDATE_DOMAIN,
        OPEN_SETTINGS,
        NONE,
    }

    data class Presentation(
        val kind: Kind,
        val titleRes: Int,
        val messageRes: Int,
        val action: Action,
        val actionLabelRes: Int? = null,
    )

    fun classify(
        error: Throwable?,
        provider: Provider? = null,
        serversExhausted: Boolean = false,
    ): Presentation {
        val hay = errorChain(error)
        // SerienStream / AniWorld / TMDb-DE CF gate: prefer captcha CTA over generic “all servers failed”.
        if (isCaptcha(hay, provider, error) ||
            (serversExhausted && isGermanStreamGateProvider(provider))
        ) {
            val captchaAction = captchaAction(provider, hay)
            return Presentation(
                kind = Kind.CAPTCHA,
                titleRes = R.string.player_stream_fail_captcha_title,
                messageRes = R.string.player_stream_fail_captcha_message,
                action = captchaAction,
                actionLabelRes = captchaAction.labelRes(),
            )
        }
        if (isGeo(hay)) {
            val geoAction = domainAction(provider).takeIf { it != Action.NONE }
                ?: Action.OPEN_SETTINGS
            return Presentation(
                kind = Kind.GEO,
                titleRes = R.string.player_stream_fail_geo_title,
                messageRes = R.string.player_stream_fail_geo_message,
                action = geoAction,
                actionLabelRes = geoAction.labelRes(),
            )
        }
        if (isTimeout(hay)) {
            val domainAction = domainAction(provider)
            return Presentation(
                kind = Kind.TIMEOUT,
                titleRes = R.string.player_stream_fail_timeout_title,
                messageRes = R.string.player_stream_fail_timeout_message,
                action = domainAction,
                actionLabelRes = domainAction.labelRes(),
            )
        }
        if (isDomainDead(hay, provider)) {
            val domainAction = domainAction(provider)
            return Presentation(
                kind = Kind.DOMAIN_DEAD,
                titleRes = R.string.player_stream_fail_domain_title,
                messageRes = R.string.player_stream_fail_domain_message,
                action = domainAction,
                actionLabelRes = domainAction.labelRes(),
            )
        }
        if (serversExhausted) {
            val domainAction = domainAction(provider)
            return Presentation(
                kind = Kind.ALL_SERVERS,
                titleRes = R.string.player_stream_fail_all_servers_title,
                messageRes = R.string.player_all_servers_failed,
                action = domainAction,
                actionLabelRes = domainAction.labelRes(),
            )
        }
        return Presentation(
            kind = Kind.GENERIC,
            titleRes = R.string.exp_player_error_title,
            messageRes = R.string.player_stream_fail_generic_message,
            action = domainAction(provider).takeIf { it != Action.NONE } ?: Action.NONE,
            actionLabelRes = domainAction(provider).labelRes(),
        )
    }

    fun message(context: Context, presentation: Presentation, fallback: String? = null): String {
        val localized = context.getString(presentation.messageRes)
        return when {
            presentation.kind == Kind.GENERIC && !fallback.isNullOrBlank() -> fallback
            else -> localized
        }
    }

    /** SerienStream / AniWorld / TMDb(de) wrap — CF gate CTAs apply. */
    fun isGermanStreamGateProvider(provider: Provider?): Boolean =
        provider is SerienStreamProvider ||
            provider is AniWorldProvider ||
            (provider is TmdbProvider &&
                provider.language.lowercase(java.util.Locale.US).startsWith("de"))

    /**
     * Provider to run domain-update against — TMDb DE routes to SerienStream
     * (underlying native hoster), not the TMDb aggregator itself.
     */
    fun domainUpdateTarget(provider: Provider?): Provider? = when {
        provider == null -> null
        provider is TmdbProvider &&
            provider.language.lowercase(java.util.Locale.US).startsWith("de") -> SerienStreamProvider
        else -> provider
    }

    private fun domainAction(provider: Provider?): Action {
        val target = domainUpdateTarget(provider) ?: return Action.NONE
        return if (ProviderHealth.supportsDomainUpdate(target)) Action.UPDATE_DOMAIN else Action.NONE
    }

    /**
     * Solve-captcha WebView only works for SerienStream / AniWorld / TMDb DE gate hosts.
     * Also infer from the error haystack when the provider instance is unavailable
     * (e.g. "SerienStream stream gate…" / tmdbde:serienstream ids).
     */
    private fun captchaAction(provider: Provider?, hay: String): Action {
        return if (supportsCaptchaSolve(provider) || looksLikeGermanStreamGate(hay)) {
            Action.SOLVE_CAPTCHA
        } else {
            domainAction(provider)
        }
    }

    fun supportsCaptchaSolve(provider: Provider?): Boolean {
        val p = provider ?: return false
        return p is SerienStreamProvider ||
            p is com.betterstreamflix.app.providers.AniWorldProvider ||
            (p is com.betterstreamflix.app.providers.TmdbProvider &&
                p.language.lowercase().startsWith("de"))
    }

    private fun looksLikeGermanStreamGate(hay: String): Boolean {
        val h = hay.lowercase(java.util.Locale.US)
        return "stream gate" in h ||
            "gate is still active" in h ||
            "serienstream" in h ||
            "aniworld" in h ||
            "tmdbde:serienstream" in h ||
            "tmdbde:aniworld" in h
    }

    private fun Action.labelRes(): Int? = when (this) {
        Action.SOLVE_CAPTCHA -> R.string.player_stream_fail_action_solve_captcha
        Action.UPDATE_DOMAIN -> R.string.provider_update_domain
        Action.OPEN_SETTINGS -> R.string.player_stream_fail_action_open_vpn_settings
        Action.NONE -> null
    }

    private fun isCaptcha(hay: String, provider: Provider?, error: Throwable?): Boolean {
        val msg = error?.message.orEmpty().lowercase()
        // Gate throws often omit CF keywords: "SerienStream stream gate is still active…"
        if ("stream gate" in hay || "stream gate" in msg ||
            "gate is still active" in hay || "gate is still active" in msg ||
            "tmdbde:serienstream" in hay || "tmdbde:serienstream" in msg ||
            "tmdbde:aniworld" in hay || "tmdbde:aniworld" in msg
        ) {
            return true
        }
        if (isGermanStreamGateProvider(provider)) {
            if (msg.contains("bypass") ||
                msg.contains("captcha") ||
                msg.contains("cloudflare") ||
                msg.contains("turnstile") ||
                msg.contains("gate")
            ) {
                return true
            }
        }
        return "captcha" in hay ||
            "cloudflare" in hay ||
            "just a moment" in hay ||
            "cf-ray" in hay ||
            "turnstile" in hay ||
            ("challenge" in hay && ("cf-" in hay || "turnstile" in hay || "captcha" in hay))
    }

    private fun isGeo(hay: String): Boolean =
        "response code: 451" in hay ||
            "http 451" in hay ||
            "unavailable for legal reasons" in hay ||
            "not available in your country" in hay ||
            "not available in your region" in hay ||
            ("geo-block" in hay || "geoblock" in hay || "geo_block" in hay) ||
            ("geo restricted" in hay || "geo-restricted" in hay) ||
            ("blocked in your country" in hay || "blocked in your region" in hay) ||
            ("vpn" in hay && ("required" in hay || "blocked" in hay))

    private fun isTimeout(hay: String): Boolean =
        "timed out" in hay ||
            "timeout" in hay ||
            "timeoutexception" in hay ||
            "sockettimeout" in hay ||
            // Keep connect failures out of TIMEOUT unless they explicitly say timeout —
            // "failed to connect" is too broad and mis-labels domain/CTA paths.
            ("failed to connect" in hay && "timeout" in hay)

    private fun isDomainDead(hay: String, provider: Provider?): Boolean {
        if (provider != null && ProviderHealth.isQuarantined(provider)) return true
        return "unknownhost" in hay ||
            "unable to resolve host" in hay ||
            "no address associated" in hay ||
            "parked" in hay ||
            "domain is for sale" in hay ||
            "blocked host" in hay ||
            "err_name_not_resolved" in hay ||
            "cleartext" in hay && "not permitted" in hay
    }

    private fun errorChain(error: Throwable?): String =
        generateSequence(error) { it.cause }
            .mapNotNull { it.message ?: it.javaClass.simpleName }
            .joinToString(" ")
            .lowercase()
}
