package com.dskja.betterstreamflix.player

import android.content.Context
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.providers.Provider
import com.dskja.betterstreamflix.providers.ProviderHealth
import com.dskja.betterstreamflix.providers.SerienStreamProvider

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
        // SerienStream / CF gate: prefer captcha CTA over generic “all servers failed”.
        if (isCaptcha(hay, provider, error) ||
            (serversExhausted && provider is SerienStreamProvider)
        ) {
            return Presentation(
                kind = Kind.CAPTCHA,
                titleRes = R.string.player_stream_fail_captcha_title,
                messageRes = R.string.player_stream_fail_captcha_message,
                action = Action.SOLVE_CAPTCHA,
                actionLabelRes = R.string.player_stream_fail_action_solve_captcha,
            )
        }
        if (isGeo(hay)) {
            return Presentation(
                kind = Kind.GEO,
                titleRes = R.string.player_stream_fail_geo_title,
                messageRes = R.string.player_stream_fail_geo_message,
                action = Action.NONE,
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

    private fun domainAction(provider: Provider?): Action {
        val p = provider ?: return Action.NONE
        return if (ProviderHealth.supportsDomainUpdate(p)) Action.UPDATE_DOMAIN else Action.NONE
    }

    private fun Action.labelRes(): Int? = when (this) {
        Action.SOLVE_CAPTCHA -> R.string.player_stream_fail_action_solve_captcha
        Action.UPDATE_DOMAIN -> R.string.provider_update_domain
        Action.NONE -> null
    }

    private fun isCaptcha(hay: String, provider: Provider?, error: Throwable?): Boolean {
        if (provider is SerienStreamProvider) {
            val msg = error?.message.orEmpty()
            if (msg.contains("bypass", ignoreCase = true) ||
                msg.contains("captcha", ignoreCase = true) ||
                msg.contains("cloudflare", ignoreCase = true)
            ) {
                return true
            }
        }
        return "captcha" in hay ||
            "cloudflare" in hay ||
            "just a moment" in hay ||
            "cf-ray" in hay ||
            "challenge" in hay && "cloudflare" in hay ||
            "turnstile" in hay
    }

    private fun isGeo(hay: String): Boolean =
        "response code: 451" in hay ||
            "http 451" in hay ||
            "unavailable for legal reasons" in hay ||
            "geo" in hay && ("block" in hay || "restrict" in hay || "denied" in hay) ||
            "not available in your country" in hay ||
            "not available in your region" in hay ||
            "vpn" in hay && ("required" in hay || "blocked" in hay)

    private fun isTimeout(hay: String): Boolean =
        "timed out" in hay ||
            "timeout" in hay ||
            "timeoutexception" in hay ||
            "sockettimeout" in hay ||
            "failed to connect" in hay

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
