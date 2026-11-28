package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // Constructed programmatically by InkSheet.
internal class DayScrubberPill(
    context: Context,
    private val entriesDir: File,
) : View(context) {
    private val density = resources.displayMetrics.density
    private var palette = DiarySettings.palette(context)
    private var baseDate: LocalDate = LocalDate.now()
    private var offsetDays: Float = 0f
    private var savedDays: Set<String> = emptySet()
    private var scanSeq = 0L

    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val activeSlotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val weekdayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }
    private val dayNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val slotRect = RectF()

    init {
        visibility = GONE
        elevation = 10f * density
    }

    fun applyPalette(next: DiaryPalette) {
        palette = next
        invalidate()
    }

    fun showScrubber(currentDate: LocalDate) {
        baseDate = currentDate
        offsetDays = 0f
        val seq = ++scanSeq
        val dir = entriesDir
        scanExecutor.execute {
            val scanned = scanSavedDayNames(dir.list().orEmpty())
            mainHandler.post {
                if (scanSeq == seq && visibility == VISIBLE) {
                    savedDays = scanned
                    invalidate()
                }
            }
        }
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        translationY = 0f
        invalidate()
        val startEnter = {
            pivotX = width / 2f
            pivotY = height / 2f
            scaleX = 0.34f
            scaleY = 0.72f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(OvershootInterpolator(1.25f))
                .start()
        }
        if (width > 0 && height > 0) {
            startEnter()
        } else {
            post {
                if (visibility == VISIBLE) startEnter()
            }
        }
    }

    fun updateOffset(daysFloat: Float): LocalDate {
        offsetDays = daysFloat
        invalidate()
        return baseDate.plusDays(daysFloat.roundToInt().toLong())
    }

    fun dismissScrubber() {
        if (visibility != VISIBLE) return
        animate().cancel()
        pivotX = width / 2f
        pivotY = height / 2f
        animate()
            .scaleX(0.36f)
            .scaleY(0.72f)
            .alpha(0f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { visibility = GONE }
            .start()
    }

    private fun hasEntry(date: LocalDate): Boolean = date.toString() in savedDays

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val corner = h / 2f
        rect.set(1f * density, 1f * density, w - 1f * density, h - 1f * density)

        // Dynamic-island dark ink capsule on light paper; elevated warm slate on dark paper
        val bgColor = if (palette.isDark) 0xff2b2723.toInt() else 0xff272320.toInt()
        val fgColor = 0xfff8f3e8.toInt()
        val mutedColor = 0xffbfb5a6.toInt()

        pillFill.color = bgColor
        canvas.drawRoundRect(rect, corner, corner, pillFill)
        pillStroke.color = if (palette.isDark) palette.rule else 0x33f8f3e8
        canvas.drawRoundRect(rect, corner, corner, pillStroke)

        val centerIndex = offsetDays.roundToInt()
        val centerDate = baseDate.plusDays(centerIndex.toLong())

        // Month + year label at top center
        monthPaint.color = mutedColor
        canvas.drawText(centerDate.format(monthFormatter), w / 2f, 17f * density, monthPaint)

        // Center vermilion selector highlight capsule
        val slotW = (w - 28f * density) / 7f
        val centerX = w / 2f
        val stripTop = 23f * density
        val stripBottom = h - 7f * density
        slotRect.set(
            centerX - slotW * 0.48f,
            stripTop,
            centerX + slotW * 0.48f,
            stripBottom,
        )
        activeSlotFill.color = palette.accent
        canvas.drawRoundRect(slotRect, 12f * density, 12f * density, activeSlotFill)

        // Continuous gliding day slots (-4..+4 around center)
        val frac = offsetDays - centerIndex
        canvas.save()
        canvas.clipRect(14f * density, stripTop - 2f * density, w - 14f * density, h)
        for (rel in -4..4) {
            val date = centerDate.plusDays(rel.toLong())
            val pos = rel - frac
            val cx = centerX + pos * slotW
            val distFromCenter = abs(pos)
            val edgeFade = (1f - (distFromCenter / 3.6f)).coerceIn(0f, 1f)
            val alphaInt = (edgeFade * 255f).roundToInt()
            if (alphaInt <= 4) continue

            val isSelected = rel == 0 && abs(frac) < 0.5f
            val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                .take(3)

            weekdayPaint.color = if (isSelected) fgColor else mutedColor
            weekdayPaint.alpha = alphaInt
            canvas.drawText(weekday, cx, stripTop + 12.5f * density, weekdayPaint)

            dayNumPaint.color = fgColor
            dayNumPaint.alpha = alphaInt
            dayNumPaint.textSize = (if (isSelected) 16.5f else 14.5f) * density
            canvas.drawText(date.dayOfMonth.toString(), cx, stripTop + 30.5f * density, dayNumPaint)

            if (hasEntry(date)) {
                dotPaint.color = if (isSelected) fgColor else palette.accent
                dotPaint.alpha = alphaInt
                canvas.drawCircle(cx, stripBottom - 4.5f * density, 2.2f * density, dotPaint)
            }
        }
        canvas.restore()
    }

    internal companion object {
        private val scanExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DiaryScrubberIO").apply { isDaemon = true }
        }
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        fun scanSavedDayNames(names: Array<out String>): Set<String> {
            val days = HashSet<String>()
            for (name in names) {
                val stem = when {
                    name.endsWith(".ink") -> name.removeSuffix(".ink")
                    name.endsWith(".items") -> name.removeSuffix(".items")
                    else -> continue
                }
                val parsed = runCatching { LocalDate.parse(stem) }.getOrNull() ?: continue
                if (parsed.toString() == stem) days.add(stem)
            }
            return days
        }
    }
}
