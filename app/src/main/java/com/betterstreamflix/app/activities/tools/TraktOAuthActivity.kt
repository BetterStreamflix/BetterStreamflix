package com.betterstreamflix.app.activities.tools

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.betterstreamflix.app.R
import com.betterstreamflix.app.platform.trakt.TraktClient
import com.betterstreamflix.app.platform.trakt.TraktOAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles [betterstreamflix://trakt/oauth] redirect after Trakt browser login.
 */
class TraktOAuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        com.betterstreamflix.app.utils.AccessibilityPreferences.applyHighContrastOverlayAuto(this)
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
                notifyAndFinish(getString(R.string.platform_trakt_oauth_failed, result.reason))
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
                    )
                }
            }
        }
    }

    private fun notifyAndFinish(message: CharSequence) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
