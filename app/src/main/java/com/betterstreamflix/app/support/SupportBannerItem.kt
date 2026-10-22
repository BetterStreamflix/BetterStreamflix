package com.betterstreamflix.app.support

import com.betterstreamflix.app.adapters.AppAdapter

/**
 * Compact home-row support / tip card (Trinkgeld flow).
 * Visibility is gated by [SupportPromptPolicy] (episode threshold + cool-down).
 */
data class SupportBannerItem(
    val id: String = "support_banner",
) : AppAdapter.Item {
    override lateinit var itemType: AppAdapter.Type
}
