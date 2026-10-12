package com.dskja.betterstreamflix.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteDeviceLinkCode(
    val id: String? = null,
    @SerialName("short_code") val shortCode: String,
    @SerialName("host_user_id") val hostUserId: String? = null,
    @SerialName("host_email") val hostEmail: String,
    @SerialName("claimed_at") val claimedAt: String? = null,
    @SerialName("looked_up_at") val lookedUpAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class DeviceLinkCodeParam(
    @SerialName("p_code") val code: String,
)

@Serializable
data class DeviceLinkLookupPayload(
    @SerialName("short_code") val shortCode: String? = null,
    val email: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("claimed_at") val claimedAt: String? = null,
    @SerialName("looked_up_at") val lookedUpAt: String? = null,
)

@Serializable
data class RemoteDeviceLinkClaimUpdate(
    @SerialName("claimed_at") val claimedAt: String,
)
