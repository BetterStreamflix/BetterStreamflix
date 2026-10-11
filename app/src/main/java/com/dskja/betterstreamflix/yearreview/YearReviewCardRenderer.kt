package com.dskja.betterstreamflix.yearreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.dskja.betterstreamflix.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a shareable PNG year-review card into `cache/share/`.
 */
object YearReviewCardRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1620

    fun renderToShareFile(
        context: Context,
        stats: YearReviewStats,
    ): File? {
        val bitmap = render(context, stats) ?: return null
        return runCatching {
            val shareDir = File(context.cacheDir, "share").also { it.mkdirs() }
            val out = File(
                shareDir,
                "year_review_${stats.year}_${stats.profileName.hashCode()}.png",
            )
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            bitmap.recycle()
            out.takeIf { it.exists() && it.canRead() }
        }.getOrNull()
    }

    fun render(context: Context, stats: YearReviewStats): Bitmap? {
        return runCatching {
            val bitmap = createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val display = typefaceOrDefault(context, R.font.syne, Typeface.DEFAULT_BOLD)
            val body = typefaceOrDefault(context, R.font.manrope, Typeface.SANS_SERIF)

            // Deep charcoal + warm red wash (brand accent, not purple/cream).
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                    intArrayOf(0xFF141018.toInt(), 0xFF1C1218.toInt(), 0xFF0E0C10.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)

            val accentWash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, WIDTH.toFloat(), HEIGHT * 0.45f,
                    intArrayOf(0x66E50914.toInt(), 0x00E50914.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT * 0.45f, accentWash)

            val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE8E2DC.toInt()
                textSize = 42f
                typeface = body
                letterSpacing = 0.08f
            }
            canvas.drawText(
                context.getString(R.string.app_name).uppercase(Locale.getDefault()),
                72f,
                120f,
                brandPaint,
            )

            val yearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 96f
                typeface = display
            }
            canvas.drawText(
                context.getString(R.string.year_review_card_headline, stats.year),
                72f,
                240f,
                yearPaint,
            )

            val profilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB3E8E2DC.toInt()
                textSize = 36f
                typeface = body
            }
            canvas.drawText(stats.profileName, 72f, 300f, profilePaint)

            // Hours hero
            val hoursLabel = context.getString(R.string.year_review_hours_label)
            val hoursValue = formatHours(stats.totalHours)
            val hoursPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE50914.toInt()
                textSize = 160f
                typeface = display
            }
            canvas.drawText(hoursValue, 72f, 520f, hoursPaint)
            val hoursUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE8E2DC.toInt()
                textSize = 40f
                typeface = body
            }
            canvas.drawText(hoursLabel, 72f, 580f, hoursUnit)

            val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x99E8E2DC.toInt()
                textSize = 32f
                typeface = body
            }
            canvas.drawText(
                context.getString(
                    R.string.year_review_counts_line,
                    stats.movieCount,
                    stats.episodeCount,
                ),
                72f,
                640f,
                metaPaint,
            )

            var y = 760f
            val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFE50914.toInt()
                textSize = 28f
                typeface = body
                letterSpacing = 0.12f
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 44f
                typeface = display
            }
            val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB3E8E2DC.toInt()
                textSize = 30f
                typeface = body
            }

            canvas.drawText(
                context.getString(R.string.year_review_top_series).uppercase(Locale.getDefault()),
                72f,
                y,
                sectionPaint,
            )
            y += 56f
            if (stats.topSeries.isEmpty()) {
                canvas.drawText(
                    context.getString(R.string.year_review_empty_series),
                    72f,
                    y,
                    detailPaint,
                )
                y += 64f
            } else {
                stats.topSeries.forEachIndexed { index, series ->
                    canvas.drawText("${index + 1}. ${series.title}", 72f, y, titlePaint)
                    y += 48f
                    canvas.drawText(
                        context.getString(
                            R.string.year_review_series_detail,
                            series.episodeCount,
                            series.estimatedMinutes,
                        ),
                        72f,
                        y,
                        detailPaint,
                    )
                    y += 72f
                }
            }

            y += 24f
            canvas.drawText(
                context.getString(R.string.year_review_longest_binge)
                    .uppercase(Locale.getDefault()),
                72f,
                y,
                sectionPaint,
            )
            y += 56f
            val binge = stats.longestBinge
            if (binge == null) {
                canvas.drawText(
                    context.getString(R.string.year_review_empty_binge),
                    72f,
                    y,
                    detailPaint,
                )
            } else {
                canvas.drawText(binge.title, 72f, y, titlePaint)
                y += 48f
                canvas.drawText(
                    context.getString(
                        R.string.year_review_binge_detail,
                        binge.episodeCount,
                        formatHours(binge.estimatedMinutes / 60.0),
                    ),
                    72f,
                    y,
                    detailPaint,
                )
                y += 44f
                val df = SimpleDateFormat("MMM d", Locale.getDefault())
                canvas.drawText(
                    context.getString(
                        R.string.year_review_binge_range,
                        df.format(Date(binge.startMs)),
                        df.format(Date(binge.endMs)),
                    ),
                    72f,
                    y,
                    detailPaint,
                )
            }

            // Footer bar
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE50914.toInt() }
            canvas.drawRoundRect(
                RectF(72f, HEIGHT - 120f, WIDTH - 72f, HEIGHT - 100f),
                8f,
                8f,
                bar,
            )
            val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x80E8E2DC.toInt()
                textSize = 28f
                typeface = body
            }
            canvas.drawText(
                context.getString(R.string.year_review_card_footer),
                72f,
                HEIGHT - 56f,
                footer,
            )

            bitmap
        }.getOrNull()
    }

    fun formatHours(hours: Double): String {
        return if (hours == hours.toLong().toDouble()) {
            hours.toLong().toString()
        } else {
            String.format(Locale.US, "%.1f", hours)
        }
    }

    private fun typefaceOrDefault(context: Context, fontRes: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context, fontRes) }.getOrNull() ?: fallback
}
