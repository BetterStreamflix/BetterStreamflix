package com.dskja.betterstreamflix.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteSharedWatchlist(
    val id: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    @EncodeDefault val title: String = "Watchlist",
    @SerialName("share_token") val shareToken: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Partial update used when rotating token / TTL without touching id/owner. */
@Serializable
data class RemoteSharedWatchlistTokenUpdate(
    val title: String,
    @SerialName("share_token") val shareToken: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteSharedWatchlistItem(
    @SerialName("list_id") val listId: String? = null,
    val provider: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_id") val mediaId: String,
    @EncodeDefault val title: String = "",
    @EncodeDefault val poster: String? = null,
    @EncodeDefault val banner: String? = null,
    @EncodeDefault @SerialName("sort_index") val sortIndex: Int = 0,
)

@Serializable
data class SharedWatchlistPayload(
    val id: String? = null,
    val title: String = "Watchlist",
    @SerialName("share_token") val shareToken: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val items: List<RemoteSharedWatchlistItem> = emptyList(),
)

@Serializable
data class SharedWatchlistTokenParam(
    @SerialName("p_token") val token: String,
)
