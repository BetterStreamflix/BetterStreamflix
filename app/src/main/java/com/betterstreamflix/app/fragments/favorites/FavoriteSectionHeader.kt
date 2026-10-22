package com.betterstreamflix.app.fragments.favorites

import com.betterstreamflix.app.adapters.AppAdapter

data class FavoriteSectionHeader(
    val title: String,
    val section: FavoritesViewModel.Section,
) : AppAdapter.Item {
    override var itemType: AppAdapter.Type = AppAdapter.Type.FAVORITE_SECTION_HEADER
}
