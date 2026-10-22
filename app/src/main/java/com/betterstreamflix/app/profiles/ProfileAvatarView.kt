package com.betterstreamflix.app.profiles

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView

/**
 * Circular avatar tile rendering the curated illustrated vector set.
 * Clips to an oval; the vector supplies its own full-bleed background.
 */
class ProfileAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val image = AppCompatImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    fun bind(profile: UserProfile, textSizeSp: Float = 28f) {
        bind(profile.avatarKey, profile.displayName, textSizeSp)
    }

    fun bind(avatarKey: String, displayName: String, textSizeSp: Float = 28f) {
        image.setImageResource(ProfileAvatarStyle.drawableFor(avatarKey))
        image.contentDescription = displayName
    }

    companion object {
        /** Solid accent-colored oval for chips / compact rows. */
        fun gradientDrawable(avatarKey: String): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    ProfileAvatarStyle.colorFor(avatarKey),
                    ProfileAvatarStyle.colorFor(avatarKey),
                ),
            ).apply {
                shape = GradientDrawable.OVAL
            }
    }
}
