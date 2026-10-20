package com.dskja.betterstreamflix.fragments.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.UserPreferences
import com.dskja.betterstreamflix.utils.dp

/**
 * Live preview card for the Cinema Room tab — mirrors the TV Ambient idle
 * scene (brand chrome, rotating posters, title) and reflects the current
 * master/ambient/idle settings.
 */
class CinemaRoomPreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_cinema_room_preview
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        paint(holder)
    }

    fun refresh() {
        notifyChanged()
    }

    companion object {
        fun paint(holder: PreferenceViewHolder) {
            val ctx = holder.itemView.context
            val enabled = UserPreferences.cinemaRoomEnabled
            val ambient = UserPreferences.tvAmbientModeEnabled && enabled
            val idleMs = UserPreferences.cinemaAmbientIdleMs

            val dot = holder.findViewById(R.id.v_cinema_room_dot)
            val brand = holder.findViewById(R.id.tv_cinema_room_brand) as? TextView
            val sceneTitle = holder.findViewById(R.id.tv_cinema_room_scene_title) as? TextView
            val sceneSub = holder.findViewById(R.id.tv_cinema_room_scene_sub) as? TextView
            val status = holder.findViewById(R.id.tv_cinema_room_status) as? TextView
            val posterA = holder.findViewById(R.id.v_cinema_room_poster_a)
            val posterB = holder.findViewById(R.id.v_cinema_room_poster_b)

            val accent = ContextCompat.getColor(ctx, R.color.cinema_accent)
            val muted = ContextCompat.getColor(ctx, R.color.cinema_text_faint)
            tintDot(dot, if (enabled) accent else muted)

            val alpha = if (enabled) 1f else 0.45f
            listOfNotNull(brand, sceneTitle, sceneSub, posterA, posterB).forEach {
                it.alpha = alpha
            }

            posterA?.let { paintPoster(it, accentLift = enabled) }
            posterB?.let { paintPoster(it, accentLift = false, dim = !enabled) }

            if (enabled && ambient) {
                status?.text = ctx.getString(
                    R.string.cinema_room_preview_status_ambient,
                    (idleMs / 1000L).toInt(),
                )
            } else if (enabled) {
                status?.text = ctx.getString(R.string.cinema_room_preview_status_no_ambient)
            } else {
                status?.text = ctx.getString(R.string.cinema_room_preview_status_off)
            }
        }

        private fun tintDot(dot: View?, color: Int) {
            dot ?: return
            val d = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            dot.background = d
        }

        private fun paintPoster(view: View, accentLift: Boolean, dim: Boolean = false) {
            val ctx = view.context
            val base = ContextCompat.getColor(
                ctx,
                if (dim) R.color.cinema_surface else R.color.cinema_elevated,
            )
            val glow = ContextCompat.getColor(ctx, R.color.cinema_glass)
            val gd = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    if (accentLift) blend(base, glow, 0.5f) else base,
                    base,
                ),
            ).apply {
                cornerRadius = 10.dp(ctx).toFloat()
            }
            view.background = gd
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            val ar = (a shr 16) and 0xFF
            val ag = (a shr 8) and 0xFF
            val ab = a and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val r = (ar + (br - ar) * t).toInt()
            val g = (ag + (bg - ag) * t).toInt()
            val bl = (ab + (bb - ab) * t).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }
}
