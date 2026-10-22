package com.dskja.betterstreamflix.ui

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import com.dskja.betterstreamflix.R
import java.util.Locale

class PlayerTvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : PlayerView(context, attrs, defStyle) {

    private var cachedController: PlayerControlView? = null

    /**
     * Resolve the layout PlayerControlView. Prefer [controllerOrNull] on hot paths —
     * never invent a detached ghost controller (chrome updates would be invisible).
     */
    val controller: PlayerControlView
        get() = controllerOrNull()
            ?: error("PlayerControlView missing from PlayerTvView layout")

    /** Non-throwing access for hot paths that can no-op when controller is absent. */
    fun controllerOrNull(): PlayerControlView? {
        cachedController?.let { return it }
        val resolved = runCatching {
            val field = PlayerView::class.java.getDeclaredField("controller")
            field.isAccessible = true
            field.get(this) as? PlayerControlView
        }.onFailure {
            Log.w(TAG, "controller reflection failed: ${it.message}")
        }.getOrNull()
            ?: findViewById<PlayerControlView?>(androidx.media3.ui.R.id.exo_controller)
        if (resolved == null) {
            Log.e(TAG, "PlayerControlView missing — skipping chrome binds")
        }
        cachedController = resolved
        return resolved
    }

    var isManualZoomEnabled: Boolean = false
        private set

    var onMediaPreviousClicked: (() -> Boolean)? = null
    var onMediaNextClicked: (() -> Boolean)? = null
    /** Captions / CC remote key — toggle subtitles without opening settings. */
    var onSubtitleToggleClicked: (() -> Boolean)? = null

    /** When true, hidden-chrome DPAD L/R zap channels instead of seeking. */
    var isLivePlayback: Boolean = false
    var onLiveZapPrevious: (() -> Boolean)? = null
    var onLiveZapNext: (() -> Boolean)? = null

    private var zoomToast: Toast? = null

    fun enterManualZoomMode() {
        player?.pause()
        isManualZoomEnabled = true
        showZoomToast(context.getString(R.string.player_manual_zoom_hint), Toast.LENGTH_LONG)
    }

    fun exitManualZoomMode() {
        isManualZoomEnabled = false
        videoSurfaceView?.apply {
            scaleX = 1f
            scaleY = 1f
        }
        zoomToast?.cancel()
        player?.play()
    }

    private fun showZoomToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        zoomToast?.cancel()
        zoomToast = Toast.makeText(context, message, duration)
        zoomToast?.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isManualZoomEnabled) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // RIPRISTINATA LOGICA ORIGINALE: Scaliamo solo il videoSurfaceView.
                // Grazie all'uso di texture_view nel layout, ora le modifiche sono visibili in tempo reale anche in pausa.
                val videoView = videoSurfaceView ?: return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        videoView.scaleY += 0.01f
                        showZoomToast("Zoom Y: ${String.format(Locale.US, "%.2f", videoView.scaleY)}")
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        videoView.scaleY -= 0.01f
                        showZoomToast("Zoom Y: ${String.format(Locale.US, "%.2f", videoView.scaleY)}")
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        videoView.scaleX -= 0.01f
                        showZoomToast("Zoom X: ${String.format(Locale.US, "%.2f", videoView.scaleX)}")
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        videoView.scaleX += 0.01f
                        showZoomToast("Zoom X: ${String.format(Locale.US, "%.2f", videoView.scaleX)}")
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        exitManualZoomMode()
                    }
                }
            }
            return true
        }

        val player = player ?: return super.dispatchKeyEvent(event)

        // Additive remote shortcuts — handled whether chrome is visible or not.
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                return if (event.action == KeyEvent.ACTION_DOWN) {
                    onMediaPreviousClicked?.invoke() ?: false
                } else {
                    true
                }
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                return if (event.action == KeyEvent.ACTION_DOWN) {
                    onMediaNextClicked?.invoke() ?: false
                } else {
                    true
                }
            }

            KeyEvent.KEYCODE_CAPTIONS -> {
                return if (event.action == KeyEvent.ACTION_DOWN) {
                    onSubtitleToggleClicked?.invoke() ?: false
                } else {
                    true
                }
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                return if (isLivePlayback) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        onLiveZapNext?.invoke() ?: false
                    } else {
                        true
                    }
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                return if (isLivePlayback) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        onLiveZapPrevious?.invoke() ?: false
                    } else {
                        true
                    }
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event.action == KeyEvent.ACTION_DOWN && !player.playWhenReady) {
                    player.play()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN && player.playWhenReady) {
                    player.pause()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (player.isPlaying) player.pause() else player.play()
                }
                return true
            }

            // ±30s: Fire TV / Android TV rewind & fast-forward (and skip) keys.
            // Dual skip scheme: progress-bar DPAD / key-time ±10s; MEDIA_REWIND/FF ±30s.
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                if (isLivePlayback) return false
                return if (event.action == KeyEvent.ACTION_DOWN) {
                    seekBy(player, -SKIP_30_MS)
                } else {
                    true
                }
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                if (isLivePlayback) return false
                return if (event.action == KeyEvent.ACTION_DOWN) {
                    seekBy(player, SKIP_30_MS)
                } else {
                    true
                }
            }
        }

        if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) && player.isPlayingAd) {
            return super.dispatchKeyEvent(event)
        }

        val controlsVisible = controllerOrNull()?.isVisible == true
        if (controlsVisible) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (isLivePlayback) {
                        onLiveZapPrevious?.invoke()
                    } else {
                        seekBy(player, -SKIP_10_MS)
                    }
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (isLivePlayback) {
                        onLiveZapNext?.invoke()
                    } else {
                        seekBy(player, SKIP_10_MS)
                    }
                }
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /** @return false when seek is unavailable (toast shown); true when seek applied or key ignored as UP. */
    private fun seekBy(player: Player, deltaMs: Long): Boolean {
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            Toast.makeText(context, R.string.player_cast_seek_unavailable, Toast.LENGTH_SHORT).show()
            return false
        }
        val duration = player.duration
        val max = if (duration > 0) duration else Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, max)
        player.seekTo(target)
        return true
    }

    private companion object {
        const val TAG = "PlayerTvView"
        /** Hidden-chrome DPAD L/R and progress-bar key increments (see setKeyTimeIncrement). */
        const val SKIP_10_MS = 10_000L
        /** MEDIA_REWIND / MEDIA_FAST_FORWARD / chrome ±30 buttons. */
        const val SKIP_30_MS = 30_000L
    }
}
