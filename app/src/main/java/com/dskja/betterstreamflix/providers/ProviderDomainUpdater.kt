package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.utils.DomainRedirectChecker
import com.dskja.betterstreamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared “Update domain” path for provider picker and player stream-failure CTAs. */
object ProviderDomainUpdater {

    sealed class Outcome {
        data class Updated(val newUrl: String) : Outcome()
        data object Unchanged : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    suspend fun update(provider: Provider): Outcome = withContext(Dispatchers.IO) {
        val config = provider as? ProviderConfigUrl
            ?: return@withContext Outcome.Failed("Provider has no configurable domain")
        runCatching {
            val start = UserPreferences.getProviderCache(
                provider,
                UserPreferences.PROVIDER_URL,
            ).ifBlank { config.defaultBaseUrl }
            val result = DomainRedirectChecker.check(start)
            if (result.changed) {
                val newUrl = result.finalUrl.trimEnd('/') + "/"
                UserPreferences.setProviderCache(
                    provider,
                    UserPreferences.PROVIDER_URL,
                    newUrl,
                )
                config.onChangeUrl(true)
                Outcome.Updated(newUrl)
            } else {
                Outcome.Unchanged
            }
        }.getOrElse { Outcome.Failed(it.message ?: "?") }
    }
}
