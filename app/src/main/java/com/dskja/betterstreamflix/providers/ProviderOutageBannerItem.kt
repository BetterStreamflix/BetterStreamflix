package com.dskja.betterstreamflix.providers

import com.dskja.betterstreamflix.adapters.AppAdapter

/**
 * Home-row outage card from the Provider Trust Layer.
 */
data class ProviderOutageBannerItem(
    val providerNames: List<String>,
    val level: ProviderTrustLayer.OutageLevel,
    val id: String = "provider_outage_banner",
) : AppAdapter.Item {
    override lateinit var itemType: AppAdapter.Type

    val deadCount: Int get() = providerNames.size
}
