package com.dskja.betterstreamflix.telegram

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.dskja.betterstreamflix.databinding.DialogTelegramJoinGateMobileBinding

/**
 * Optional community invite: inflate as topmost child of [android.R.id.content].
 */
object TelegramJoinGateOverlay {

    private const val TAG = "TelegramJoinGate"
    const val VIEW_TAG = "telegram_join_gate_overlay"

    @Volatile
    private var binder: TelegramJoinGateUiBinder? = null

    fun isAttached(activity: FragmentActivity): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        return content.findViewWithTag<View>(VIEW_TAG) != null
    }

    fun show(activity: FragmentActivity): Boolean {
        if (!TelegramJoinGatePolicy.shouldShowGate()) return false
        if (activity.isFinishing || activity.isDestroyed) return false
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: run {
            Log.e(TAG, "overlay show failed: no android.R.id.content")
            return false
        }
        val existing = content.findViewWithTag<View>(VIEW_TAG)
        if (existing != null) {
            existing.bringToFront()
            existing.visibility = View.VISIBLE
            TelegramJoinGateController.onGateActivityStarted()
            Log.i(TAG, "overlay already attached — brought to front")
            return true
        }
        return runCatching {
            val binding = DialogTelegramJoinGateMobileBinding.inflate(
                activity.layoutInflater,
                content,
                false,
            )
            val root = binding.root
            root.tag = VIEW_TAG
            root.isClickable = true
            root.isFocusable = true
            root.elevation = 96f * activity.resources.displayMetrics.density
            content.addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            root.bringToFront()
            TelegramJoinGateController.onGateActivityStarted()
            binder?.destroy()
            binder = TelegramJoinGateUiBinder(
                binding = binding,
                host = object : TelegramJoinGateUiBinder.Host {
                    override fun context(): Context = activity
                    override fun lifecycleOwner(): LifecycleOwner = activity
                    override fun isHostAlive(): Boolean =
                        !activity.isFinishing &&
                            !activity.isDestroyed &&
                            content.findViewWithTag<View>(VIEW_TAG) != null

                    override fun onDismissed() {
                        dismiss(activity)
                        TelegramJoinGateController.onGateFinished(activity)
                    }
                },
            ).also { it.bind() }
            Log.i(TAG, "community invite overlay attached")
            true
        }.onFailure {
            Log.e(TAG, "overlay show failed", it)
        }.getOrDefault(false)
    }

    /** Close + remember dismiss (optional invite). */
    fun dismissOptional(activity: FragmentActivity): Boolean {
        if (!isAttached(activity) && binder == null) return false
        binder?.dismiss(animate = true) ?: run {
            TelegramJoinGatePolicy.markDismissed()
            dismiss(activity)
            TelegramJoinGateController.onGateFinished(activity)
        }
        return true
    }

    fun dismiss(activity: FragmentActivity?) {
        binder?.destroy()
        binder = null
        if (activity == null || activity.isDestroyed) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val gate = content.findViewWithTag<View>(VIEW_TAG) ?: return
        runCatching { (gate.parent as? ViewGroup)?.removeView(gate) }
        TelegramJoinGateController.onGateActivityDestroyed()
        Log.i(TAG, "overlay dismissed")
    }
}
