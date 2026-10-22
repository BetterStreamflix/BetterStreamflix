package com.betterstreamflix.app.logo

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.betterstreamflix.app.R
import com.betterstreamflix.app.ui.LettermarkDrawable

/**
 * Renders a strip of network / studio / watch-provider logo chips into the
 * `ll_logo_chip_strip` container of `view_logo_chip_strip.xml`. Logo-less
 * entries degrade to a text pill; broken loads degrade to a lettermark.
 */
object LogoChipStrip {

    private const val CHIP_HEIGHT_DP = 34
    private const val CHIP_MAX_WIDTH_DP = 120
    private const val CHIP_PAD_H_DP = 10
    private const val CHIP_PAD_V_DP = 4
    private const val CHIP_MARGIN_DP = 8

    /** [stripRoot] is the included layout root (the vertical LinearLayout). */
    fun bind(
        stripRoot: View?,
        labelText: CharSequence?,
        chips: List<LogoChips.LogoChip>,
    ) {
        val root = stripRoot ?: return
        val label = root.findViewById<TextView>(R.id.tv_logo_chip_strip_label)
        val container = root.findViewById<LinearLayout>(R.id.ll_logo_chip_strip)
            ?: return
        container.removeAllViews()
        if (chips.isEmpty()) {
            root.isVisible = false
            return
        }
        root.isVisible = true
        label?.text = labelText.orEmpty()
        chips.forEach { chip -> container.addView(chipView(container, chip)) }
    }

    private fun dp(view: View, v: Int): Int =
        (v * view.resources.displayMetrics.density).toInt()

    private fun chipView(parent: ViewGroup, chip: LogoChips.LogoChip): View {
        val ctx = parent.context
        val url = chip.logoUrl?.takeIf { it.isNotBlank() }
        return if (url != null) {
            ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(this, CHIP_HEIGHT_DP),
                ).apply { marginEnd = dp(this@apply, CHIP_MARGIN_DP) }
                adjustViewBounds = true
                maxWidth = dp(this, CHIP_MAX_WIDTH_DP)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = chip.name
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                val isSvg = url.substringBefore('?')
                    .endsWith(".svg", ignoreCase = true)
                if (isSvg) {
                    setLayerType(
                        if (com.betterstreamflix.app.utils.DeviceCapabilities
                                .isLeanbackDevice(ctx)
                        ) View.LAYER_TYPE_NONE else View.LAYER_TYPE_SOFTWARE,
                        null,
                    )
                    Glide.with(this)
                        .`as`(android.graphics.drawable.PictureDrawable::class.java)
                        .load(url)
                        .listener(object : RequestListener<android.graphics.drawable.PictureDrawable> {
                            override fun onLoadFailed(
                                e: GlideException?, model: Any?,
                                target: Target<android.graphics.drawable.PictureDrawable>,
                                isFirstResource: Boolean,
                            ): Boolean {
                                setImageDrawable(LettermarkDrawable(chip.name))
                                return true
                            }

                            override fun onResourceReady(
                                resource: android.graphics.drawable.PictureDrawable,
                                model: Any, target: Target<android.graphics.drawable.PictureDrawable>?,
                                dataSource: DataSource, isFirstResource: Boolean,
                            ): Boolean = false
                        })
                        .into(this)
                } else {
                    Glide.with(this)
                        .load(url)
                        .fitCenter()
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?, model: Any?,
                                target: Target<Drawable>, isFirstResource: Boolean,
                            ): Boolean {
                                setImageDrawable(LettermarkDrawable(chip.name))
                                return true
                            }

                            override fun onResourceReady(
                                resource: Drawable, model: Any,
                                target: Target<Drawable>?, dataSource: DataSource,
                                isFirstResource: Boolean,
                            ): Boolean = false
                        })
                        .into(this)
                }
            }
        } else {
            TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(this@apply, CHIP_MARGIN_DP) }
                text = chip.name
                contentDescription = chip.name
                setBackgroundResource(R.drawable.bg_meta_pill)
                setPadding(
                    dp(this, CHIP_PAD_H_DP), dp(this, CHIP_PAD_V_DP),
                    dp(this, CHIP_PAD_H_DP), dp(this, CHIP_PAD_V_DP),
                )
                setTextColor(0xFFE8E8E8.toInt())
                textSize = 12f
            }
        }
    }
}
