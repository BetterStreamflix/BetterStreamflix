package com.dskja.betterstreamflix.sync

/**
 * Pure queue helpers for Cast Room (unit-tested).
 */
object CastRoomQueue {
    fun nowPlaying(payload: CastRoomPayload): CastRoomItemPayload? {
        if (payload.items.isEmpty()) return null
        val index = payload.nowPlayingIndex.coerceIn(0, payload.items.lastIndex)
        return payload.items[index]
    }

    fun upNext(payload: CastRoomPayload): CastRoomItemPayload? {
        if (payload.items.isEmpty()) return null
        val next = payload.nowPlayingIndex + 1
        return payload.items.getOrNull(next)
    }

    fun playingKey(item: CastRoomItemPayload?, index: Int): String? {
        if (item == null) return null
        return "${item.mediaType}:${item.mediaId}:$index:${item.id.orEmpty()}"
    }

    fun moveItem(ids: List<String>, from: Int, to: Int): List<String>? {
        if (from !in ids.indices || to !in ids.indices || from == to) return null
        val mutable = ids.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        return mutable
    }

    fun displayLabel(item: CastRoomItemPayload): String {
        val title = item.title.ifBlank {
            item.showTitle?.takeIf { it.isNotBlank() } ?: item.mediaId
        }
        val subtitle = item.subtitle.ifBlank {
            when {
                item.mediaType == "episode" &&
                    item.seasonNumber != null &&
                    item.episodeNumber != null ->
                    "S${item.seasonNumber} E${item.episodeNumber}"
                else -> ""
            }
        }
        return if (subtitle.isBlank()) title else "$title · $subtitle"
    }

    /** Short codes: A–Z and 2–9 (no 0/1/O/I/L confusion on the wire). */
    fun newShortCode(random: () -> Int = { kotlin.random.Random.nextInt(0, CompanionShortCodes.ALPHABET.length) }): String =
        CompanionShortCodes.newShortCode(random)
}
