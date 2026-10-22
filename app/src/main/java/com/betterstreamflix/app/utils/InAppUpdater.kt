package com.betterstreamflix.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.betterstreamflix.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

object InAppUpdater {

    private const val GITHUB_OWNER = "BetterStreamflix"
    private const val GITHUB_REPO = "BetterStreamflix"

    private data class Version(val name: String) : Comparable<Version> {
        override operator fun compareTo(other: Version): Int {
            val thisParts = this.name.split(".").toTypedArray()
            val thatParts = other.name.split(".").toTypedArray()
            for (i in 0 until max(thisParts.size, thatParts.size)) {
                val thisPart = thisParts.getOrNull(i)?.toIntOrNull() ?: 0
                val thatPart = thatParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (thisPart < thatPart) return -1
                if (thisPart > thatPart) return 1
            }
            return 0
        }
    }

    suspend fun getReleaseUpdate(): GitHub.Release? {
        val latestRelease = GitHub.Releases.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        if (Version(latestRelease.tagName.substringAfter("v")) > Version(currentVersion)) {
            return latestRelease
        }
        return null
    }

    suspend fun getNewReleases(): List<GitHub.Release> {
        val releases = GitHub.Releases.getReleases(GITHUB_OWNER, GITHUB_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        val newReleases = releases
            .filter { Version(it.tagName.substringAfter("v")) > Version(currentVersion) }

        return newReleases
    }

    suspend fun downloadApk(context: Context, asset: GitHub.Release.Asset): File {
        // Must live under a FileProvider root (see provider_paths.xml -> cache/share).
        val shareDir = File(context.cacheDir, "share")
        withContext(Dispatchers.IO) {
            shareDir.mkdirs()
            shareDir.listFiles()
                ?.filter { it.extension == "apk" }
                ?.forEach { it.delete() }
        }

        val apk = withContext(Dispatchers.IO) {
            File.createTempFile(
                "update-${File(asset.name).nameWithoutExtension}-",
                ".${File(asset.name).extension.ifEmpty { "apk" }}",
                shareDir
            )
        }

        try {
            withContext(Dispatchers.IO) {
                val connection = URL(asset.browserDownloadUrl).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.inputStream.use { input ->
                        FileOutputStream(apk).use { output -> input.copyTo(output) }
                    }
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { apk.delete() }
            throw e
        }

        return apk
    }

    fun installApk(context: Context, uri: Uri) {
        val file = uri.path?.let(::File) ?: return
        val intent = Intent(Intent.ACTION_VIEW).also { intent ->
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            intent.setDataAndType(
                FileProvider.getUriForFile(
                    context,
                    BuildConfig.APPLICATION_ID + ".provider",
                    file
                ),
                "application/vnd.android.package-archive"
            )
        }
        context.startActivity(intent)
    }
}