package com.betterstreamflix.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.mediarouter.app.MediaRouteButton
import com.betterstreamflix.app.R
import com.betterstreamflix.app.cast.CastPlaybackHub
import com.betterstreamflix.app.cast.DlnaCastSession
import com.betterstreamflix.app.cast.DlnaDiscovery
import com.betterstreamflix.app.cast.DlnaRenderer
import com.betterstreamflix.app.cast.SmartTvBridge
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified cast sheet — one entry point for every way to move the picture
 * off the phone: Chromecast route picker, discovered DLNA/Tizen renderers,
 * paired Smart-TV remote bridges (Tizen WSS / webOS SSAP), and the system's
 * wireless-display mirroring settings. Mirrors the NG cast surface.
 */
object CastSheet {

    /** A resolved stream the caller can hand to a TV (URL + headers + resume point). */
    data class CurrentStream(
        val url: String,
        val title: String,
        val mimeType: String?,
        val headers: Map<String, String>,
        val positionMs: Long,
    )

    interface Callbacks {
        /** Current local stream, or null when nothing is playing. */
        fun currentStream(): CurrentStream?

        /** DLNA session just took over — pause local playback. */
        fun onDlnaStarted(renderer: DlnaRenderer) {}

        /** DLNA session ended — resume local playback at [positionMs] when > 0. */
        fun onDlnaStopped(positionMs: Long) {}

        /** Smart-TV "play here" push fired with a LAN-reachable URL. */
        fun onSmartTvOpenUrl(deviceName: String) {}
    }

    fun show(context: Context, callbacks: Callbacks) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_cast_sheet, null)
        dialog.setContentView(view)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        dialog.setOnDismissListener { scope.cancel() }

        wireGoogleCast(context, view)
        wireDlna(context, view, scope, callbacks)
        wireSmartTvs(context, view, scope, callbacks)
        wireMirror(context, view)

        dialog.show()
        // Bottom sheets start below their content; keep the sheet under ~85% height.
        dialog.window?.let { w ->
            val max = (context.resources.displayMetrics.heightPixels * 0.85f).toInt()
            view.post {
                if (view.height > max) {
                    view.layoutParams = view.layoutParams.apply { height = max }
                    view.requestLayout()
                }
            }
        }
    }

    // ── Google Cast ─────────────────────────────────────────────────────────

    private fun wireGoogleCast(context: Context, view: View) {
        val slot = view.findViewById<android.widget.FrameLayout>(R.id.fl_cast_route_button) ?: return
        runCatching {
            CastPlaybackHub.ensureCastContext(context)
            val themed = android.view.ContextThemeWrapper(
                context,
                androidx.mediarouter.R.style.Theme_MediaRouter,
            )
            val routeButton = MediaRouteButton(themed)
            CastButtonFactory.setUpMediaRouteButton(context, routeButton)
            slot.addView(routeButton)
        }.onFailure {
            // No Play Services / CastContext — hide the row entirely.
            view.findViewById<View>(R.id.ll_cast_google_row)?.visibility = View.GONE
        }
    }

    // ── DLNA / Tizen renderers ──────────────────────────────────────────────

    private fun wireDlna(
        context: Context,
        view: View,
        scope: CoroutineScope,
        callbacks: Callbacks,
    ) {
        val listContainer = view.findViewById<LinearLayout>(R.id.ll_cast_dlna_list) ?: return
        val scanningRow = view.findViewById<View>(R.id.ll_cast_dlna_scanning)
        val scanLabel = view.findViewById<TextView>(R.id.tv_cast_dlna_scan_label)
        val emptyHint = view.findViewById<TextView>(R.id.tv_cast_dlna_empty)
        val activeCard = view.findViewById<View>(R.id.ll_cast_dlna_active)
        val activeName = view.findViewById<TextView>(R.id.tv_cast_dlna_active_name)
        val stopBtn = view.findViewById<TextView>(R.id.btn_cast_dlna_stop)

        fun syncActiveCard() {
            val active = DlnaCastSession.activeRenderer
            activeCard?.visibility = if (active != null) View.VISIBLE else View.GONE
            active?.let { activeName?.text = it.name }
        }

        stopBtn?.setOnClickListener {
            scope.launch {
                val posMs = DlnaCastSession.stop()
                syncActiveCard()
                callbacks.onDlnaStopped(posMs ?: 0L)
            }
        }

        syncActiveCard()

        fun renderDevices(devices: List<DlnaRenderer>) {
            listContainer.removeAllViews()
            val density = context.resources.displayMetrics.density
            devices.forEach { renderer ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_detail_season_chip)
                    val p = (14 * density).toInt()
                    setPadding(p, (10 * density).toInt(), p, (10 * density).toInt())
                }
                val icon = android.widget.ImageView(context).apply {
                    setImageResource(R.drawable.ic_cast_tv)
                    setColorFilter(context.getColor(R.color.cinema_text_muted))
                    layoutParams = LinearLayout.LayoutParams(
                        (20 * density).toInt(), (20 * density).toInt(),
                    )
                }
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                    ).apply { marginStart = (12 * density).toInt() }
                }
                texts.addView(TextView(context).apply {
                    text = renderer.name
                    setTextColor(context.getColor(R.color.cinema_text))
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                texts.addView(TextView(context).apply {
                    text = context.getString(R.string.cast_sheet_dlna_sub, renderer.host)
                    setTextColor(context.getColor(R.color.cinema_text_faint))
                    textSize = 10f
                    isAllCaps = true
                    letterSpacing = 0.1f
                })
                row.addView(icon)
                row.addView(texts)
                row.setOnClickListener { startDlna(context, scope, renderer, callbacks) { syncActiveCard() } }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * density).toInt() }
                listContainer.addView(row, lp)
            }
        }

        scope.launch {
            scanningRow?.visibility = View.VISIBLE
            scanLabel?.text = context.getString(R.string.cast_sheet_scanning)
            val devices = runCatching { DlnaDiscovery(context).discover() }.getOrDefault(emptyList())
            scanningRow?.visibility = View.GONE
            scanLabel?.text = context.getString(R.string.cast_sheet_tvs_label)
            renderDevices(devices)
            emptyHint?.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun startDlna(
        context: Context,
        scope: CoroutineScope,
        renderer: DlnaRenderer,
        callbacks: Callbacks,
        onSync: () -> Unit,
    ) {
        val stream = callbacks.currentStream()
        if (stream == null) {
            Toast.makeText(context, R.string.cast_sheet_start_playback_first, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            // TVs can't send our scraper headers — route through the cast proxy.
            val lanUrl = CastPlaybackHub.wrapForCast(stream.url, stream.headers)
            val mime = stream.mimeType
                ?: if (lanUrl.substringBefore('?').endsWith(".m3u8")) {
                    "application/vnd.apple.mpegurl"
                } else {
                    "video/mp4"
                }
            val ok = DlnaCastSession.start(renderer, lanUrl, stream.title, mime)
            if (ok) {
                callbacks.onDlnaStarted(renderer)
                onSync()
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.cast_sheet_dlna_failed, renderer.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // ── Smart-TV remote bridge ──────────────────────────────────────────────

    private fun wireSmartTvs(
        context: Context,
        view: View,
        scope: CoroutineScope,
        callbacks: Callbacks,
    ) {
        val bridge = SmartTvBridge.get(context)
        val listContainer = view.findViewById<LinearLayout>(R.id.ll_cast_tv_list) ?: return
        val ipInput = view.findViewById<EditText>(R.id.et_cast_tv_ip)
        val tizenBtn = view.findViewById<TextView>(R.id.btn_cast_kind_tizen)
        val webosBtn = view.findViewById<TextView>(R.id.btn_cast_kind_webos)
        val pairBtn = view.findViewById<TextView>(R.id.btn_cast_pair)

        var kind = SmartTvBridge.TvDevice.Kind.TIZEN
        fun syncKindButtons() {
            val sel = kind == SmartTvBridge.TvDevice.Kind.TIZEN
            tizenBtn?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    context.getColor(if (sel) R.color.cinema_glass_strong else R.color.cinema_glass),
                )
            webosBtn?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    context.getColor(if (!sel) R.color.cinema_glass_strong else R.color.cinema_glass),
                )
            tizenBtn?.setTextColor(context.getColor(if (sel) R.color.cinema_text else R.color.cinema_text_muted))
            webosBtn?.setTextColor(context.getColor(if (!sel) R.color.cinema_text else R.color.cinema_text_muted))
        }
        tizenBtn?.setOnClickListener { kind = SmartTvBridge.TvDevice.Kind.TIZEN; syncKindButtons() }
        webosBtn?.setOnClickListener { kind = SmartTvBridge.TvDevice.Kind.WEBOS; syncKindButtons() }
        syncKindButtons()

        pairBtn?.setOnClickListener {
            val host = ipInput?.text?.toString()?.trim().orEmpty()
            if (host.count { it == '.' } != 3) {
                Toast.makeText(context, R.string.cast_sheet_pair_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pairBtn.isEnabled = false
            scope.launch {
                val device = bridge.addAndConnect(host, kind)
                pairBtn.isEnabled = true
                if (device != null) {
                    ipInput?.text?.clear()
                }
            }
        }

        // Render paired TVs + live statuses.
        scope.launch {
            combine(bridge.devices, bridge.status) { devices, status -> devices to status }
                .collect { (devices, status) ->
                    renderTvRows(context, listContainer, devices, status, bridge, scope, callbacks)
                }
        }
        // Reconnect remembered TVs in the background.
        scope.launch {
            bridge.devices.value.forEach { d -> if (!d.connected) bridge.connect(d) }
        }
    }

    private fun renderTvRows(
        context: Context,
        container: LinearLayout,
        devices: List<SmartTvBridge.TvDevice>,
        status: Map<String, String>,
        bridge: SmartTvBridge,
        scope: CoroutineScope,
        callbacks: Callbacks,
    ) {
        container.removeAllViews()
        val density = context.resources.displayMetrics.density
        devices.forEach { tv ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_detail_season_chip)
                val p = (12 * density).toInt()
                setPadding(p, p, p, p)
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val icon = android.widget.ImageView(context).apply {
                setImageResource(R.drawable.ic_cast_tv)
                setColorFilter(
                    context.getColor(
                        if (tv.connected) R.color.cinema_accent else R.color.cinema_text_faint,
                    ),
                )
                layoutParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt())
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { marginStart = (12 * density).toInt() }
            }
            texts.addView(TextView(context).apply {
                text = tv.name
                setTextColor(context.getColor(R.color.cinema_text))
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val proto = if (tv.kind == SmartTvBridge.TvDevice.Kind.TIZEN) "TIZEN WSS" else "WEBOS SSAP"
            texts.addView(TextView(context).apply {
                text = "$proto · ${tv.host} · ${status[tv.id] ?: context.getString(R.string.cast_sheet_offline)}"
                setTextColor(
                    context.getColor(
                        if (tv.connected) R.color.cinema_accent else R.color.cinema_text_faint,
                    ),
                )
                textSize = 10f
                letterSpacing = 0.1f
            })
            val forget = TextView(context).apply {
                text = context.getString(R.string.cast_sheet_forget)
                setTextColor(context.getColor(R.color.cinema_text_faint))
                textSize = 11f
                isAllCaps = true
                val p = (8 * density).toInt()
                setPadding(p, p, p, p)
            }
            forget.setOnClickListener {
                scope.launch { bridge.removeDevice(tv.id) }
            }
            top.addView(icon)
            top.addView(texts)
            top.addView(forget)
            card.addView(top)

            if (tv.connected) {
                val keys = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (8 * density).toInt() }
                }
                fun key(label: String, action: () -> Unit) {
                    val t = TextView(context).apply {
                        text = label
                        setTextColor(context.getColor(R.color.cinema_text))
                        textSize = 13f
                        gravity = android.view.Gravity.CENTER
                        setBackgroundResource(R.drawable.bg_detail_season_chip)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(
                            context.getColor(R.color.cinema_glass),
                        )
                    }
                    t.setOnClickListener { action() }
                    keys.addView(
                        t,
                        LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt())
                            .apply { marginEnd = (4 * density).toInt() },
                    )
                }
                key("⏮") { bridge.key(tv.id, "REWIND") }
                key("⏯") { bridge.key(tv.id, "PLAY"); bridge.key(tv.id, "PAUSE") }
                key("⏭") { bridge.key(tv.id, "FASTFORWARD") }
                key("🔉") { bridge.volume(tv.id, false) }
                key("🔊") { bridge.volume(tv.id, true) }

                keys.addView(
                    android.widget.Space(context),
                    LinearLayout.LayoutParams(0, 1, 1f),
                )
                val playHere = TextView(context).apply {
                    text = context.getString(R.string.cast_sheet_play_here)
                    setTextColor(context.getColor(R.color.cinema_accent))
                    textSize = 11f
                    isAllCaps = true
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_detail_season_chip)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        context.getColor(R.color.cinema_glass_strong),
                    )
                    val hp = (10 * density).toInt()
                    val vp = (7 * density).toInt()
                    setPadding(hp, vp, hp, vp)
                }
                playHere.setOnClickListener {
                    val stream = callbacks.currentStream()
                    if (stream == null) {
                        Toast.makeText(
                            context,
                            R.string.cast_sheet_start_playback_first,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setOnClickListener
                    }
                    scope.launch {
                        val lanUrl = CastPlaybackHub.wrapForCast(stream.url, stream.headers)
                        bridge.openUrl(tv.id, lanUrl)
                        callbacks.onSmartTvOpenUrl(tv.name)
                    }
                }
                keys.addView(playHere)
                card.addView(keys)
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * density).toInt() }
            container.addView(card, lp)
        }
    }

    // ── Screen mirroring ────────────────────────────────────────────────────

    private fun wireMirror(context: Context, view: View) {
        view.findViewById<View>(R.id.ll_cast_mirror_row)?.setOnClickListener {
            // Mirroring lives in OS/vendor settings — try Samsung's
            // Smart View panel first, then the platform cast settings.
            listOf(
                Intent().setClassName(
                    "com.samsung.android.app.smartview",
                    "com.samsung.android.app.smartview.MainActivity",
                ),
                Intent("android.settings.CAST_SETTINGS"),
                Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
                Intent(Settings.ACTION_SETTINGS),
            ).firstNotNullOfOrNull { intent ->
                runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    intent
                }.getOrNull()
            }
        }
    }
}
