package com.dskja.betterstreamflix.logo

/**
 * Pure visibility state for a fixed-height logo slot (title ↔ logo overlay).
 * Keeps layout height stable — no GONE↔VISIBLE shift of the slot itself.
 */
object TitleLogoSlot {

    enum class State {
        /** No logo URL / decode failed → show title text in the reserved slot. */
        SHOW_TITLE,
        /** Logo URL present, Glide still loading → reserve space, hide logo pixels. */
        LOADING_LOGO,
        /** Logo decoded → show logo, hide title. */
        SHOW_LOGO,
    }

    fun state(
        logoUrl: String?,
        hideUntilReady: Boolean,
        loadFailed: Boolean = false,
        loadReady: Boolean = false,
    ): State {
        if (logoUrl.isNullOrBlank() || loadFailed) return State.SHOW_TITLE
        if (loadReady) return State.SHOW_LOGO
        return if (hideUntilReady) State.LOADING_LOGO else State.SHOW_LOGO
    }
}
