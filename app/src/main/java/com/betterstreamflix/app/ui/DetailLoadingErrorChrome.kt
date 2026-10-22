package com.betterstreamflix.app.ui

import android.content.Context
import android.view.View
import android.widget.Toast
import com.betterstreamflix.app.R
import com.betterstreamflix.app.utils.CacheUtils
import com.betterstreamflix.app.utils.ExpDialogChrome
import com.betterstreamflix.app.utils.ExpPressEffects
import com.betterstreamflix.app.utils.LoggingUtils

/**
 * Shared FailedLoading chrome for detail (and similar) loading overlays.
 * Expects the included [layout_is_loading_*] ids on [root].
 */
object DetailLoadingErrorChrome {

    fun bind(
        root: View,
        context: Context,
        error: Exception,
        showToast: Boolean = true,
        requestFocusOnRetry: Boolean = false,
        onRetry: () -> Unit,
    ) {
        if (showToast) {
            Toast.makeText(context, error.message ?: "", Toast.LENGTH_SHORT).show()
        }
        ExpPressEffects.showLoadingSkeleton(root, false)
        root.findViewById<View>(R.id.g_is_loading_retry)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.btn_is_loading_retry)?.setOnClickListener { onRetry() }
        root.findViewById<View>(R.id.btn_is_loading_clear_cache)?.setOnClickListener {
            CacheUtils.clearAppCache(context)
            ExpDialogChrome.notify(
                context,
                context.getString(R.string.clear_cache_done),
                R.string.loading_error_clear_cache,
            )
            onRetry()
        }
        root.findViewById<View>(R.id.btn_is_loading_error_details)?.setOnClickListener {
            LoggingUtils.showErrorDialog(context, error)
        }
        if (requestFocusOnRetry) {
            root.findViewById<View>(R.id.btn_is_loading_retry)?.requestFocus()
        }
    }
}
