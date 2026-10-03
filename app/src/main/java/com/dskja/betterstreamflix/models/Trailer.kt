package com.dskja.betterstreamflix.models

import com.dskja.betterstreamflix.adapters.AppAdapter

class Trailer(
    val title: String,
    val url: String,
    val type: String = "Trailer",
    val official: Boolean = false,
) : AppAdapter.Item {

    override lateinit var itemType: AppAdapter.Type

    fun copy(
        title: String = this.title,
        url: String = this.url,
        type: String = this.type,
        official: Boolean = this.official,
    ) = Trailer(title, url, type, official).also {
        if (::itemType.isInitialized) {
            it.itemType = itemType
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Trailer
        if (title != other.title) return false
        if (url != other.url) return false
        if (type != other.type) return false
        if (official != other.official) return false
        if (!::itemType.isInitialized || !other::itemType.isInitialized) return false
        return itemType == other.itemType
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + official.hashCode()
        result = 31 * result + (if (::itemType.isInitialized) itemType.hashCode() else 0)
        return result
    }
}
