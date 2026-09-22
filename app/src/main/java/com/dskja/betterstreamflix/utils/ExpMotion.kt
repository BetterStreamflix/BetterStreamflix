package com.dskja.betterstreamflix.utils

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.LayoutAnimationController
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.dskja.betterstreamflix.R

/**
 * Shared motion helpers for the experimental mobile shell. Every animation is
 * a no-op (or instant) when the experiment is off or the user has disabled
 * animations via the system animator-duration scale.
 */
object ExpMotion {

    fun reduceMotion(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    private fun View.motionAllowed(): Boolean =
        ExperimentalMobileDesign.enabled() && !reduceMotion(context)

    /** Fade a view out and mark it GONE afterwards (loading screens). */
    fun fadeOutAndHide(view: View, duration: Long = 240) {
        if (!view.motionAllowed()) {
            view.visibility = View.GONE
            return
        }
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.GONE
            return
        }
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
            }
            .start()
    }

    /** Mark a view VISIBLE with a soft fade-in (loading screens, overlays). */
    fun fadeInAndShow(view: View, duration: Long = 280) {
        if (!view.motionAllowed()) {
            view.visibility = View.VISIBLE
            return
        }
        if (view.visibility == View.VISIBLE && view.alpha == 1f) return
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Small scale-pop used when badges/ribbons appear on cards. */
    fun popIn(view: View) {
        if (!view.motionAllowed()) return
        view.animate().cancel()
        view.scaleX = 0.55f
        view.scaleY = 0.55f
        view.alpha = 0f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(OvershootInterpolator(2.4f))
            .start()
    }

    /** Toggle feedback that stays inside the view bounds, unlike [popIn]'s overshoot. */
    fun softScale(view: View) {
        if (!view.motionAllowed()) return
        view.animate().cancel()
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .start()
    }

    /** Fragment-enter motion: soft fade + slight rise. */
    fun enterScreen(root: View, duration: Long = 260) {
        if (!root.motionAllowed()) return
        root.animate().cancel()
        root.alpha = 0f
        root.translationY = 16f * root.resources.displayMetrics.density
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * One-shot staggered fade-in when a RecyclerView receives its first items.
     * Registers on the current adapter; defers a frame if none is attached yet.
     */
    fun staggerFirstFill(rv: RecyclerView, attempts: Int = 0) {
        if (!rv.motionAllowed()) return
        val adapter = rv.adapter
        if (adapter == null) {
            if (attempts < 10) {
                rv.postDelayed({ staggerFirstFill(rv, attempts + 1) }, 48)
            }
            return
        }
        rv.layoutAnimation = LayoutAnimationController(
            AnimationUtils.loadAnimation(rv.context, R.anim.exp_item_fade_in)
        ).apply { delay = 0.12f }
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            private var fired = false

            private fun fire() {
                if (fired) return
                fired = true
                rv.scheduleLayoutAnimation()
                runCatching { adapter.unregisterAdapterDataObserver(this) }
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = fire()
            override fun onChanged() = fire()
        })
    }

    /** Load + start an XML animation, honouring reduced-motion. */
    fun startAnimation(view: View, animRes: Int) {
        if (!view.motionAllowed()) return
        view.startAnimation(AnimationUtils.loadAnimation(view.context, animRes))
    }

    /** Light haptic tick for primary actions. */
    fun hapticTap(view: View) {
        if (!ExperimentalMobileDesign.enabled()) return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * Cascading reveal for catalog headers (eyebrow → title → tagline → rule).
     * Matches the Settings hub / Support stagger language.
     */
    fun revealHeader(vararg views: View?) {
        val visible = views.filterNotNull()
        if (visible.isEmpty()) return
        val context = visible.first().context
        if (!ExperimentalMobileDesign.enabled() || reduceMotion(context)) {
            visible.forEach { it.alpha = 1f; it.translationY = 0f }
            return
        }
        visible.forEachIndexed { index, view ->
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = 14f * view.resources.displayMetrics.density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(48L * index)
                .setDuration(280L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** Soft horizontal shake for invalid PIN / error fields. */
    fun shake(view: View?) {
        view ?: return
        if (!view.motionAllowed()) return
        view.animate().cancel()
        view.animate()
            .translationX(10f)
            .setDuration(45L)
            .withEndAction {
                view.animate()
                    .translationX(-10f)
                    .setDuration(45L)
                    .withEndAction {
                        view.animate()
                            .translationX(0f)
                            .setDuration(45L)
                            .start()
                    }
                    .start()
            }
            .start()
    }

    /** Soft pulse on the accent rule under brand / catalog headers. */
    fun pulseAccentRule(view: View?) {
        view ?: return
        if (!view.motionAllowed()) return
        view.animate().cancel()
        view.scaleX = 0.35f
        view.alpha = 0.35f
        view.pivotX = 0f
        view.animate()
            .scaleX(1f)
            .alpha(1f)
            .setDuration(520L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Brand-first home entrance: logo → brand → tagline → rule. */
    fun brandReveal(logo: View?, brand: View?, tagline: View?, rule: View?) {
        revealHeader(logo, brand, tagline, rule)
        pulseAccentRule(rule)
    }

    /**
     * Slow cinematic Ken Burns on a full-bleed hero image.
     * Tags the animator on the view so it can be cancelled on detach.
     * Set [drift] false when another scroll parallax owns translation.
     */
    fun kenBurns(
        view: View?,
        scaleFrom: Float = 1f,
        scaleTo: Float = 1.08f,
        durationMs: Long = 18_000L,
        drift: Boolean = true,
    ) {
        view ?: return
        if (!view.motionAllowed()) return
        (view.getTag(R.id.exp_ken_burns_animator) as? AnimatorSet)?.cancel()
        view.scaleX = scaleFrom
        view.scaleY = scaleFrom
        if (drift) {
            view.translationX = 0f
            view.translationY = 0f
        }
        val dens = view.resources.displayMetrics.density
        val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, scaleFrom, scaleTo).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, scaleFrom, scaleTo).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val animators = mutableListOf<android.animation.Animator>(sx, sy)
        if (drift) {
            animators += ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, 10f * dens).apply {
                duration = durationMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
            }
            animators += ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -8f * dens).apply {
                duration = (durationMs * 1.12f).toLong()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
            }
        }
        val set = AnimatorSet().apply {
            interpolator = AccelerateDecelerateInterpolator()
            playTogether(animators)
            start()
        }
        view.setTag(R.id.exp_ken_burns_animator, set)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                (v.getTag(R.id.exp_ken_burns_animator) as? AnimatorSet)?.cancel()
                v.setTag(R.id.exp_ken_burns_animator, null)
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }
}
