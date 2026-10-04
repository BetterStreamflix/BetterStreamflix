package com.dskja.betterstreamflix.sync

/**
 * The same Supabase Auth account is already linked to another local profile
 * on this installation. Profiles must keep separate cloud identities.
 */
class CloudAccountAlreadyLinkedException(
    val ownerProfileId: String,
) : IllegalStateException(
    "This cloud account is already linked to another local profile ($ownerProfileId).",
)
