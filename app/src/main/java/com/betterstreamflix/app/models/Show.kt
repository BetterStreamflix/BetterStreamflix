package com.betterstreamflix.app.models

import com.betterstreamflix.app.adapters.AppAdapter

sealed interface Show : AppAdapter.Item {
    var isFavorite: Boolean
}
