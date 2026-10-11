package com.dskja.betterstreamflix.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

@SuppressLint("RestrictedApi")
object WatchNextUtils {

    fun programs(context: Context): List<WatchNextProgram> {
        return queryAll(context)
            .filter { program ->
                val providerName = UserPreferences.currentProvider?.name
                providerName == null || program.internalProviderId == providerName
            }
    }

    fun programsForProvider(context: Context, providerName: String): List<WatchNextProgram> =
        queryAll(context).filter { it.internalProviderId == providerName }

    fun insert(context: Context, program: WatchNextProgram): Uri? {
        return runCatching {
            context.contentResolver.insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                program.toContentValues(),
            )
        }.getOrNull()
    }

    fun getProgram(context: Context, contentId: String): WatchNextProgram? {
        val providerName = UserPreferences.currentProvider?.name ?: return null
        return getProgram(context, contentId, providerName)
    }

    fun getProgram(
        context: Context,
        contentId: String,
        providerName: String,
    ): WatchNextProgram? =
        queryAll(context).find {
            it.contentId == contentId && it.internalProviderId == providerName
        }

    fun updateProgram(context: Context, id: Long, program: WatchNextProgram) {
        runCatching {
            context.contentResolver.update(
                TvContractCompat.buildWatchNextProgramUri(id),
                program.toContentValues(),
                null,
                null,
            )
        }
    }

    fun deleteProgramById(context: Context, id: Long) {
        runCatching {
            context.contentResolver.delete(
                TvContractCompat.buildWatchNextProgramUri(id),
                null,
                null,
            )
        }
    }

    fun deleteAllForProvider(context: Context, providerName: String) {
        queryAll(context)
            .filter { it.internalProviderId == providerName }
            .forEach { program ->
                if (program.id >= 0L) deleteProgramById(context, program.id)
            }
    }

    private fun queryAll(context: Context): List<WatchNextProgram> {
        return runCatching {
            context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                WatchNextProgram.PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.map { WatchNextProgram.fromCursor(it) }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
