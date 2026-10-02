package com.dskja.betterstreamflix.ui

import android.content.Context
import android.view.View
import android.widget.Toast
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.CacheUtils
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExpPressEffects
import com.dskja.betterstreamflix.utils.LoggingUtils

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
        ExpPressEffects.animateLoadingError(root)
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
