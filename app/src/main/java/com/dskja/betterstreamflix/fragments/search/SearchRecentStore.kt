package com.dskja.betterstreamflix.fragments.search

import android.content.Context

/**
 * Compatibility façade for Search Memories (“damals gesucht”).
 * Prefer [SearchMemories] for outcome-aware APIs.
 */
object SearchRecentStore {

    fun list(context: Context): List<String> =
        SearchMemories.list(context).map { it.query }

    fun remember(context: Context, query: String) {
        SearchMemories.rememberAttempt(context, query, global = false)
    }

    fun clear(context: Context) {
        SearchMemories.clear(context)
    }
}
