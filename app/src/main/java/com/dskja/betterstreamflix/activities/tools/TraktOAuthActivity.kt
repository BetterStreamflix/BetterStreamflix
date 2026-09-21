package com.dskja.betterstreamflix.activities.tools

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.platform.trakt.TraktClient
import com.dskja.betterstreamflix.platform.trakt.TraktOAuth
import com.dskja.betterstreamflix.utils.ExpDialogChrome
import com.dskja.betterstreamflix.utils.ExperimentalMobileDesign
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles [betterstreamflix://trakt/oauth] redirect after Trakt browser login.
 */
class TraktOAuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (val result = TraktOAuth.consumeCallback(this, intent?.data)) {
            is TraktOAuth.Result.Ignored -> {
                finish()
            }
            is TraktOAuth.Result.Failed -> {
                notifyAndFinish(
                    getString(R.string.platform_trakt_oauth_failed, result.reason),
                    R.string.platform_trakt_category,
                )
            }
            is TraktOAuth.Result.Code -> {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        TraktClient.exchangeAuthorizationCode(result.code)
                    }
                    notifyAndFinish(
                        getString(
                            if (ok) R.string.platform_trakt_oauth_success
                            else R.string.platform_trakt_oauth_exchange_failed,
                        ),
                        R.string.platform_trakt_category,
                    )
                }
            }
        }
    }

    private fun notifyAndFinish(message: CharSequence, titleRes: Int) {
        if (!ExperimentalMobileDesign.enabled()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val glass = ExpDialogChrome.buildGlassMessage(this, message)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(glass.root)
            .setPositiveButton(android.R.string.ok, null)
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnShowListener { ExpDialogChrome.polishGlassMessageShown(dialog, glass) }
                dialog.setOnDismissListener { finish() }
                dialog.show()
            }
    }
}
