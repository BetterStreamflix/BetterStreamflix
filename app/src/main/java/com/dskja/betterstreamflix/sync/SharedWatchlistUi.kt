package com.dskja.betterstreamflix.sync

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.download.DownloadConnectivityMonitor
import com.dskja.betterstreamflix.profiles.ProfileUnlockGate
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SharedWatchlistUi {
    private val promptedPendingTokens = mutableSetOf<String>()

    @Volatile
    private var confirmInFlight = false

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
        val options = arrayOf(
            context.getString(R.string.shared_watchlist_share_action),
            context.getString(R.string.shared_watchlist_revoke_action),
        )
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.shared_watchlist_share_menu_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> publishAndShare(fragment)
                    1 -> confirmRevoke(fragment)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun publishAndShare(fragment: Fragment) {
        val context = fragment.context ?: return
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

    private fun confirmRevoke(fragment: Fragment) {
        val context = fragment.context ?: return
        if (!DownloadConnectivityMonitor.isOnline(context)) {
            ExpDialogChrome.notify(context, R.string.shared_watchlist_share_offline)
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.shared_watchlist_revoke_confirm_title)
            .setMessage(R.string.shared_watchlist_revoke_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.shared_watchlist_revoke_action) { _, _ ->
                revokeSharedList(fragment)
            }
            .show()
    }

    fun revokeSharedList(fragment: Fragment) {
        val context = fragment.context ?: return
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SharedWatchlistRepository.revokeCurrentSharedList(context) }
            }
            if (!fragment.isAdded) return@launch
            val ctx = fragment.context ?: return@launch
            result.onSuccess {
                ExpDialogChrome.notify(ctx, R.string.shared_watchlist_revoke_success)
            }.onFailure { error ->
                ExpDialogChrome.notify(
                    ctx,
                    ctx.getString(
                        R.string.shared_watchlist_revoke_failed,
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
        // Defer confirm until Kids/PIN unlock completes so imports cannot race the gate.
        if (ProfileUnlockGate.requiresGateForActive()) {
            SharedWatchlistPendingImport.enqueue(appContext, token)
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
        if (confirmInFlight) {
            SharedWatchlistPendingImport.enqueue(appContext, token)
            return
        }
        confirmInFlight = true
        activity.lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching { SharedWatchlistRepository.fetchByToken(appContext, token) }
            }.getOrElse { error ->
                confirmInFlight = false
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
            var importing = false
            val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
            builder.setMessage(message)
            val dialog = builder
                .setTitle(R.string.shared_watchlist_import_confirm_title)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.shared_watchlist_import_confirm_action) { _, _ ->
                    importing = true
                    activity.lifecycleScope.launch {
                        val importResult = withContext(Dispatchers.IO) {
                            runCatching {
                                SharedWatchlistImporter.importPayload(appContext, payload)
                            }
                        }
                        confirmInFlight = false
                        importResult.onSuccess { result ->
                            SharedWatchlistPendingImport.acknowledge(appContext, token)
                            promptedPendingTokens.remove(token.trim())
                            ExpDialogChrome.notify(
                                activity,
                                activity.getString(
                                    R.string.shared_watchlist_import_success,
                                    result.imported,
                                    result.alreadyPresent,
                                    result.skippedUnknownProvider,
                                    result.skippedAgeRestricted,
                                ),
                            )
                            // Continue with any remaining queued tokens.
                            tryFlushPending(activity)
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
                .create()
            dialog.setOnDismissListener {
                if (!importing) confirmInFlight = false
            }
            dialog.setOnShowListener {
                ExpDialogChrome.polishButtons(dialog)
            }
            dialog.show()
        }
    }

    /**
     * Peeks the per-profile pending queue and, when an [FragmentActivity] is available,
     * shows the same confirm dialog as [handleIncomingToken]. Never silent-imports.
     * No-ops while the Kids/PIN unlock gate is still required.
     */
    fun tryFlushPending(context: Context) {
        if (!SupabaseProvider.isConfigured) return
        if (!DownloadConnectivityMonitor.isOnline(context)) return
        val activity = context as? FragmentActivity ?: return
        if (activity.isFinishing) return
        if (ProfileUnlockGate.requiresGateForActive()) return
        if (confirmInFlight) return
        val appContext = activity.applicationContext
        val token = SharedWatchlistPendingImport.peek(appContext)
            .firstOrNull { it !in promptedPendingTokens }
            ?: return
        promptedPendingTokens.add(token)
        handleIncomingToken(activity, token)
    }
}
