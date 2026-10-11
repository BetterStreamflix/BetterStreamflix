package com.dskja.betterstreamflix.sync

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadConnectivityMonitor
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SharedWatchlistUi {

    fun shareCurrentList(fragment: Fragment) {
        val context = fragment.context ?: return
        if (!SupabaseProvider.isConfigured) {
            ExpDialogChrome.notify(context, R.string.shared_watchlist_share_needs_supabase)
            return
        }
        if (CloudSyncManager.currentUserId() == null) {
            ExpDialogChrome.notify(context, R.string.shared_watchlist_share_needs_sign_in)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(context)) {
            ExpDialogChrome.notify(context, R.string.shared_watchlist_share_offline)
            return
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SharedWatchlistRepository.publishCurrentFavorites(context) }
            }
            if (!fragment.isAdded) return@launch
            val ctx = fragment.context ?: return@launch
            result.onSuccess { link ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        ctx.getString(R.string.shared_watchlist_share_subject),
                    )
                }
                runCatching {
                    ctx.startActivity(
                        Intent.createChooser(
                            send,
                            ctx.getString(R.string.shared_watchlist_share_action),
                        ),
                    )
                }.onFailure {
                    Toast.makeText(ctx, link, Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    ctx,
                    ctx.getString(
                        R.string.shared_watchlist_share_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    fun handleIncomingToken(activity: FragmentActivity, token: String) {
        val appContext = activity.applicationContext
        if (!SharedWatchlistLinks.isValidToken(token)) {
            ExpDialogChrome.notify(activity, R.string.shared_watchlist_import_invalid_link)
            return
        }
        if (!SupabaseProvider.isConfigured) {
            SharedWatchlistPendingImport.enqueue(appContext, token)
            ExpDialogChrome.notify(activity, R.string.shared_watchlist_import_needs_supabase)
            return
        }
        if (!DownloadConnectivityMonitor.isOnline(appContext)) {
            SharedWatchlistPendingImport.enqueue(appContext, token)
            ExpDialogChrome.notify(activity, R.string.shared_watchlist_import_queued_offline)
            return
        }
        activity.lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching { SharedWatchlistRepository.fetchByToken(appContext, token) }
            }.getOrElse { error ->
                SharedWatchlistPendingImport.enqueue(appContext, token)
                ExpDialogChrome.notify(
                    activity,
                    activity.getString(
                        R.string.shared_watchlist_import_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                return@launch
            }
            val count = payload.items.size
            val message = activity.getString(
                R.string.shared_watchlist_import_confirm_message,
                payload.title.ifBlank { activity.getString(R.string.main_menu_favorites) },
                count,
            )
            val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(R.string.shared_watchlist_import_confirm_title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.shared_watchlist_import_confirm_action) { _, _ ->
                    activity.lifecycleScope.launch {
                        val importResult = withContext(Dispatchers.IO) {
                            runCatching {
                                SharedWatchlistImporter.importPayload(appContext, payload)
                            }
                        }
                        importResult.onSuccess { result ->
                            SharedWatchlistPendingImport.acknowledge(appContext, token)
                            ExpDialogChrome.notify(
                                activity,
                                activity.getString(
                                    R.string.shared_watchlist_import_success,
                                    result.imported,
                                    result.alreadyPresent,
                                    result.skippedUnknownProvider,
                                ),
                            )
                        }.onFailure { error ->
                            ExpDialogChrome.notify(
                                activity,
                                activity.getString(
                                    R.string.shared_watchlist_import_failed,
                                    error.message ?: error.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                }
            builder.show()
        }
    }

    fun tryFlushPending(context: Context) {
        if (!SupabaseProvider.isConfigured) return
        if (!DownloadConnectivityMonitor.isOnline(context)) return
        // Best-effort; WorkManager / sync path also flushes.
    }
}
