package com.betterstreamflix.app.sync

/**
 * Kids profiles must not sign into Supabase Auth. Adult cloud libraries would
 * otherwise merge into the kids Room DB (UI age filter alone is not isolation).
 */
class KidsCloudAuthException : IllegalStateException(
    "Cloud sign-in is disabled on Kids profiles. Switch to an adult profile first.",
)
