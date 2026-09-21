package com.dskja.betterstreamflix.telegram

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.LifecycleOwner
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.DialogTelegramJoinGateMobileBinding

/**
 * Fullscreen community invite Activity host (secondary to [TelegramJoinGateOverlay]).
 */
class TelegramJoinGateActivity : AppCompatActivity() {

    private var binder: TelegramJoinGateUiBinder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.TelegramJoinGateActivityTheme)
        super.onCreate(savedInstanceState)
        if (!TelegramJoinGatePolicy.shouldShowGate()) {
            setResult(RESULT_OK)
            finish()
            return
        }
        TelegramJoinGateController.onGateActivityStarted()

        val binding = DialogTelegramJoinGateMobileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes?.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                binder?.dismiss() ?: run {
                    TelegramJoinGatePolicy.markDismissed()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        })

        binder = TelegramJoinGateUiBinder(
            binding = binding,
            host = object : TelegramJoinGateUiBinder.Host {
                override fun context(): Context = this@TelegramJoinGateActivity
                override fun lifecycleOwner(): LifecycleOwner = this@TelegramJoinGateActivity
                override fun isHostAlive(): Boolean = !isFinishing && !isDestroyed
                override fun onDismissed() {
                    setResult(RESULT_OK)
                    finish()
                    overridePendingTransition(0, android.R.anim.fade_out)
                }
            },
        ).also { it.bind() }

        Log.i(TAG, "community invite activity shown")
    }

    override fun onStart() {
        super.onStart()
        TelegramJoinGateController.onGateActivityStarted()
    }

    override fun onStop() {
        TelegramJoinGateController.onGateActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        binder?.onResume()
    }

    override fun onDestroy() {
        binder?.destroy()
        binder = null
        TelegramJoinGateController.onGateActivityDestroyed()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TelegramJoinGate"
    }
}
