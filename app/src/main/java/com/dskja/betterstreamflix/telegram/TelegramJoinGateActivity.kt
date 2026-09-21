package com.dskja.betterstreamflix.telegram

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.dskja.betterstreamflix.BuildConfig
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.DialogTelegramJoinGateMobileBinding
import com.dskja.betterstreamflix.support.SupportLinkOpener
import com.dskja.betterstreamflix.utils.ExpPressEffects.applyExpPress
import com.dskja.betterstreamflix.utils.UserPreferences

/**
 * Fullscreen soft Telegram join gate (mobile). Prefer Activity over Dialog so
 * the gate always paints above Main and survives OEM dialog quirks.
 */
class TelegramJoinGateActivity : AppCompatActivity() {

    private lateinit var binding: DialogTelegramJoinGateMobileBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var confirmPoll: Runnable? = null
    private var iconPulseRunning = false
    private var orbDriftRunning = false
    private var ringPulseRunning = false
    private var finishingUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.TelegramJoinGateActivityTheme)
        super.onCreate(savedInstanceState)
        if (!TelegramJoinGatePolicy.shouldShowGate()) {
            setResult(RESULT_OK)
            finish()
            return
        }

        binding = DialogTelegramJoinGateMobileBinding.inflate(layoutInflater)
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

        ViewCompat.setOnApplyWindowInsetsListener(binding.tgGateContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val padH = resources.getDimensionPixelSize(R.dimen.tg_gate_pad_h)
            val padV = resources.getDimensionPixelSize(R.dimen.tg_gate_pad_v)
            view.updatePadding(
                left = bars.left + padH,
                top = bars.top + padV,
                right = bars.right + padH,
                bottom = bars.bottom + padV,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.tgGateContent)

        binding.tvTgGateChannel.text = getString(
            R.string.tg_gate_channel_chip,
            TelegramJoinGatePolicy.CHANNEL_HANDLE,
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        if (BuildConfig.DEBUG) {
            binding.btnTgGateDebugSkip.visibility = View.VISIBLE
            binding.btnTgGateDebugSkip.setOnClickListener {
                haptic(it)
                TelegramJoinGatePolicy.markOpened()
                TelegramJoinGatePolicy.markUnlocked()
                finishUnlocked()
            }
        }

        binding.tvTgGateChannel.setOnClickListener {
            haptic(it)
            openTelegram()
        }
        binding.flTgGateIconWrap.setOnClickListener {
            haptic(it)
            openTelegram()
        }
        binding.btnTgGateOpen.setOnClickListener {
            haptic(it)
            openTelegram()
        }
        binding.btnTgGateConfirm.setOnClickListener {
            haptic(it)
            if (!TelegramJoinGatePolicy.canConfirm()) {
                refreshPhase(animate = true)
                return@setOnClickListener
            }
            TelegramJoinGatePolicy.markUnlocked()
            finishUnlocked()
        }

        listOf(
            binding.btnTgGateOpen,
            binding.btnTgGateConfirm,
            binding.tvTgGateChannel,
            binding.flTgGateIconWrap,
        ).forEach { it.applyExpPress() }

        playEnterMotion()
        refreshPhase(animate = false)
        if (TelegramJoinGatePolicy.hasFreshOpen()) {
            scheduleConfirmEnable()
        }
        startIconPulse()
        startOrbDrift()
        startRingPulse()
        Log.i(TAG, "gate shown phase=${TelegramJoinGatePolicy.phase()}")
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (TelegramJoinGatePolicy.isUnlocked()) {
            finishUnlocked()
            return
        }
        refreshPhase(animate = true)
        if (TelegramJoinGatePolicy.hasFreshOpen() && !TelegramJoinGatePolicy.canConfirm()) {
            scheduleConfirmEnable()
        }
    }

    override fun onDestroy() {
        cancelConfirmPoll()
        stopIconPulse()
        stopOrbDrift()
        stopRingPulse()
        super.onDestroy()
    }

    private fun openTelegram() {
        SupportLinkOpener.openTelegram(this)
        TelegramJoinGatePolicy.markOpened()
        refreshPhase(animate = true)
        scheduleConfirmEnable()
    }

    private fun finishUnlocked() {
        if (finishingUnlocked) return
        finishingUnlocked = true
        cancelConfirmPoll()
        stopIconPulse()
        stopOrbDrift()
        stopRingPulse()
        if (reduceMotion() || !::binding.isInitialized) {
            setResult(RESULT_OK)
            finish()
            return
        }
        binding.root.animate()
            .alpha(0f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                setResult(RESULT_OK)
                finish()
                overridePendingTransition(0, android.R.anim.fade_out)
            }
            .start()
    }

    private fun scheduleConfirmEnable() {
        cancelConfirmPoll()
        val openedAt = UserPreferences.telegramJoinGateOpenedAtMs
        val remaining = (
            TelegramJoinGatePolicy.MIN_OPEN_DWELL_MS -
                (System.currentTimeMillis() - openedAt)
            ).coerceAtLeast(0L)
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                refreshPhase(animate = true)
                if (!TelegramJoinGatePolicy.canConfirm()) {
                    mainHandler.postDelayed(this, 350L)
                }
            }
        }
        confirmPoll = poll
        mainHandler.postDelayed(poll, remaining + 40L)
    }

    private fun cancelConfirmPoll() {
        confirmPoll?.let { mainHandler.removeCallbacks(it) }
        confirmPoll = null
    }

    private fun refreshPhase(animate: Boolean) {
        if (!::binding.isInitialized) return
        val phase = TelegramJoinGatePolicy.phase()
        val canConfirm = TelegramJoinGatePolicy.canConfirm()

        when (phase) {
            TelegramJoinGatePolicy.Phase.LOCKED -> {
                binding.vTgGateStep1.setBackgroundResource(R.drawable.bg_tg_gate_step_active)
                binding.vTgGateStep2.setBackgroundResource(R.drawable.bg_tg_gate_step_idle)
                binding.vTgGateStep3.setBackgroundResource(R.drawable.bg_tg_gate_step_idle)
                binding.tvTgGateStep1.setTextColor(getColor(R.color.tg_gate_text_primary))
                binding.tvTgGateStep2.setTextColor(getColor(R.color.tg_gate_text_secondary))
                binding.tvTgGateStep3.setTextColor(getColor(R.color.tg_gate_text_secondary))
                binding.tvTgGateHint.setText(R.string.tg_gate_hint_open_first)
                binding.btnTgGateOpen.setText(R.string.tg_gate_cta_open)
                setConfirmEnabled(false, animate)
            }
            TelegramJoinGatePolicy.Phase.OPENED -> {
                binding.vTgGateStep1.setBackgroundResource(R.drawable.bg_tg_gate_step_done)
                binding.vTgGateStep2.setBackgroundResource(R.drawable.bg_tg_gate_step_active)
                binding.vTgGateStep3.setBackgroundResource(
                    if (canConfirm) R.drawable.bg_tg_gate_step_done else R.drawable.bg_tg_gate_step_idle,
                )
                binding.tvTgGateStep1.setTextColor(getColor(R.color.tg_gate_text_secondary))
                binding.tvTgGateStep2.setTextColor(getColor(R.color.tg_gate_text_primary))
                binding.tvTgGateStep3.setTextColor(
                    getColor(
                        if (canConfirm) R.color.tg_gate_text_primary else R.color.tg_gate_text_secondary,
                    ),
                )
                binding.tvTgGateHint.setText(
                    if (canConfirm) R.string.tg_gate_hint_confirm else R.string.tg_gate_hint_join_then_return,
                )
                binding.btnTgGateOpen.setText(R.string.tg_gate_cta_open_again)
                setConfirmEnabled(canConfirm, animate)
            }
            TelegramJoinGatePolicy.Phase.UNLOCKED -> {
                setConfirmEnabled(true, animate)
            }
        }
    }

    private fun setConfirmEnabled(enabled: Boolean, animate: Boolean) {
        binding.btnTgGateConfirm.isEnabled = enabled
        binding.btnTgGateConfirm.setTextColor(
            getColor(if (enabled) R.color.tg_gate_success else R.color.tg_gate_text_tertiary),
        )
        val targetAlpha = if (enabled) 1f else 0.5f
        if (animate && kotlin.math.abs(binding.btnTgGateConfirm.alpha - targetAlpha) > 0.01f) {
            val btn = binding.btnTgGateConfirm
            btn.animate().cancel()
            if (enabled) {
                btn.animate()
                    .alpha(targetAlpha)
                    .scaleX(1.03f)
                    .scaleY(1.03f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        btn.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(160L)
                            .start()
                    }
                    .start()
            } else {
                btn.animate()
                    .alpha(targetAlpha)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        } else {
            binding.btnTgGateConfirm.alpha = targetAlpha
            binding.btnTgGateConfirm.scaleX = 1f
            binding.btnTgGateConfirm.scaleY = 1f
        }
    }

    private fun reduceMotion(): Boolean =
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    private fun playEnterMotion() {
        if (reduceMotion()) return
        val views = listOf(
            binding.tvTgGateBrand,
            binding.tvTgGateChannel,
            binding.vTgGateBrandRule,
            binding.flTgGateIconWrap,
            binding.tvTgGateHeadline,
            binding.tvTgGateBody,
            binding.llTgGateSteps,
            binding.btnTgGateOpen,
            binding.btnTgGateConfirm,
            binding.tvTgGateHint,
        )
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 22f + (index * 2f)
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(40L * index)
                .setDuration(460L)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
        binding.flTgGateIconWrap.scaleX = 0.72f
        binding.flTgGateIconWrap.scaleY = 0.72f
        binding.flTgGateIconWrap.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(120L)
            .setDuration(560L)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()

        binding.tvTgGateBrand.scaleX = 0.94f
        binding.tvTgGateBrand.scaleY = 0.94f
        binding.tvTgGateBrand.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(520L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun startIconPulse() {
        if (reduceMotion() || iconPulseRunning) return
        iconPulseRunning = true
        val icon = binding.ivTgGateIcon
        icon.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(1200L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (!iconPulseRunning || isFinishing) return@withEndAction
                icon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(1200L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        if (iconPulseRunning && !isFinishing) {
                            iconPulseRunning = false
                            startIconPulse()
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun stopIconPulse() {
        if (!::binding.isInitialized) return
        iconPulseRunning = false
        binding.ivTgGateIcon.animate().cancel()
        binding.ivTgGateIcon.scaleX = 1f
        binding.ivTgGateIcon.scaleY = 1f
    }

    private fun startRingPulse() {
        if (reduceMotion() || ringPulseRunning) return
        ringPulseRunning = true
        val outer = binding.vTgGateRingOuter
        val mid = binding.vTgGateRingMid
        outer.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .alpha(0.55f)
            .setDuration(1600L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (!ringPulseRunning || isFinishing) return@withEndAction
                outer.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(1600L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        if (ringPulseRunning && !isFinishing) {
                            ringPulseRunning = false
                            startRingPulse()
                        }
                    }
                    .start()
            }
            .start()
        mid.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(1400L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (!ringPulseRunning || isFinishing) return@withEndAction
                mid.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(1400L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun stopRingPulse() {
        if (!::binding.isInitialized) return
        ringPulseRunning = false
        binding.vTgGateRingOuter.animate().cancel()
        binding.vTgGateRingMid.animate().cancel()
        binding.vTgGateRingOuter.scaleX = 1f
        binding.vTgGateRingOuter.scaleY = 1f
        binding.vTgGateRingOuter.alpha = 1f
        binding.vTgGateRingMid.scaleX = 1f
        binding.vTgGateRingMid.scaleY = 1f
    }

    private fun startOrbDrift() {
        if (reduceMotion() || orbDriftRunning) return
        orbDriftRunning = true
        fun drift(view: View, dx: Float, dy: Float, duration: Long) {
            view.animate()
                .translationX(dx)
                .translationY(dy)
                .setDuration(duration)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    if (!orbDriftRunning || isFinishing) return@withEndAction
                    view.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(duration)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction {
                            if (orbDriftRunning && !isFinishing) {
                                drift(view, dx, dy, duration)
                            }
                        }
                        .start()
                }
                .start()
        }
        drift(binding.vTgGateOrbA, -18f, 22f, 5200L)
        drift(binding.vTgGateOrbB, 24f, -16f, 6400L)
    }

    private fun stopOrbDrift() {
        if (!::binding.isInitialized) return
        orbDriftRunning = false
        binding.vTgGateOrbA.animate().cancel()
        binding.vTgGateOrbB.animate().cancel()
        binding.vTgGateOrbA.translationX = 0f
        binding.vTgGateOrbA.translationY = 0f
        binding.vTgGateOrbB.translationX = 0f
        binding.vTgGateOrbB.translationY = 0f
    }

    private fun haptic(view: View) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    companion object {
        private const val TAG = "TelegramJoinGate"
    }
}
