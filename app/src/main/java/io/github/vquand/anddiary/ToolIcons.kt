package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

internal enum class ToolIconKind {
    PEN,
    BRUSH,
    ERASER,
    SELECT,
    STICKER,
    MEDIA,
    HANZI,
    FINGER,
    UNDO,
    REDO,
    MORE,
}

@SuppressLint("ViewConstructor") // Constructed programmatically by InkSheet.
internal class ToolIconView(
    context: Context,
    kind: ToolIconKind,
) : View(context) {
    private val density = resources.displayMetrics.density

    var kind: ToolIconKind = kind
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var iconColor: Int = DiaryPalette.LIGHT.text
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var inkColor: Int? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val size = minOf(w, h)
        val ox = (w - size) / 2f
        val oy = (h - size) / 2f
        val strokeW = (size * 0.085f).coerceAtLeast(1.4f * density)

        strokePaint.color = iconColor
        strokePaint.strokeWidth = strokeW
        fillPaint.color = iconColor

        fun sx(u: Float) = ox + u * size
        fun sy(v: Float) = oy + v * size

        when (kind) {
            ToolIconKind.PEN -> {
                val chosen = inkColor ?: iconColor
                // Diagonal pen body from top-right to bottom-left
                path.reset()
                path.moveTo(sx(0.24f), sy(0.76f))
                path.lineTo(sx(0.30f), sy(0.54f))
                path.lineTo(sx(0.72f), sy(0.12f))
                path.lineTo(sx(0.88f), sy(0.28f))
                path.lineTo(sx(0.46f), sy(0.70f))
                path.close()

                // Fill barrel & tip with chosen ink color
                fillPaint.color = chosen
                canvas.drawPath(path, fillPaint)

                // Crisp outline in iconColor so dark/light inks stay legible on any theme
                canvas.drawPath(path, strokePaint)

                // Collar line near nib
                canvas.drawLine(sx(0.32f), sy(0.52f), sx(0.48f), sy(0.68f), strokePaint)

                // Bold color indicator bar underneath the pen tip
                strokePaint.color = chosen
                strokePaint.strokeWidth = (strokeW * 1.45f)
                canvas.drawLine(sx(0.16f), sy(0.90f), sx(0.84f), sy(0.90f), strokePaint)
                strokePaint.color = iconColor
                strokePaint.strokeWidth = strokeW
            }

            ToolIconKind.BRUSH -> {
                val chosen = inkColor ?: iconColor
                // Handle (top-right to middle)
                path.reset()
                path.moveTo(sx(0.44f), sy(0.48f))
                path.lineTo(sx(0.76f), sy(0.12f))
                path.lineTo(sx(0.88f), sy(0.24f))
                path.lineTo(sx(0.52f), sy(0.56f))
                path.close()
                canvas.drawPath(path, strokePaint)

                // Soft teardrop bristle tip filled with chosen ink color
                path.reset()
                path.moveTo(sx(0.44f), sy(0.48f))
                path.cubicTo(sx(0.28f), sy(0.48f), sx(0.24f), sy(0.66f), sx(0.14f), sy(0.78f))
                path.cubicTo(sx(0.30f), sy(0.80f), sx(0.52f), sy(0.74f), sx(0.52f), sy(0.56f))
                path.close()

                fillPaint.color = chosen
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)

                // Color indicator bar underneath
                strokePaint.color = chosen
                strokePaint.strokeWidth = (strokeW * 1.45f)
                canvas.drawLine(sx(0.16f), sy(0.90f), sx(0.84f), sy(0.90f), strokePaint)
                strokePaint.color = iconColor
                strokePaint.strokeWidth = strokeW
            }

            ToolIconKind.ERASER -> {
                // Angled block eraser
                path.reset()
                path.moveTo(sx(0.20f), sy(0.60f))
                path.lineTo(sx(0.58f), sy(0.20f))
                path.lineTo(sx(0.86f), sy(0.48f))
                path.lineTo(sx(0.54f), sy(0.82f))
                path.lineTo(sx(0.32f), sy(0.82f))
                path.close()
                canvas.drawPath(path, strokePaint)

                // Sleeve divider across the middle of the eraser
                canvas.drawLine(sx(0.39f), sy(0.40f), sx(0.67f), sy(0.68f), strokePaint)

                // Erase baseline
                canvas.drawLine(sx(0.22f), sy(0.82f), sx(0.86f), sy(0.82f), strokePaint)
            }

            ToolIconKind.SELECT -> {
                // Samsung Notes-style lasso loop with knot & tail
                rect.set(sx(0.14f), sy(0.16f), sx(0.84f), sy(0.68f))
                val prevEffect = strokePaint.pathEffect
                strokePaint.pathEffect = android.graphics.DashPathEffect(
                    floatArrayOf(size * 0.13f, size * 0.09f),
                    0f,
                )
                canvas.drawOval(rect, strokePaint)
                strokePaint.pathEffect = prevEffect

                // Lasso knot and trailing rope at bottom-right
                canvas.drawCircle(sx(0.62f), sy(0.67f), strokeW * 0.85f, fillPaint)
                path.reset()
                path.moveTo(sx(0.62f), sy(0.67f))
                path.quadTo(sx(0.70f), sy(0.82f), sx(0.84f), sy(0.86f))
                canvas.drawPath(path, strokePaint)
            }

            ToolIconKind.STICKER -> {
                // Rounded square sticker with peeled bottom-right corner
                val r = size * 0.14f
                path.reset()
                path.moveTo(sx(0.16f), sy(0.16f) + r)
                path.quadTo(sx(0.16f), sy(0.16f), sx(0.16f) + r, sy(0.16f))
                path.lineTo(sx(0.84f) - r, sy(0.16f))
                path.quadTo(sx(0.84f), sy(0.16f), sx(0.84f), sy(0.16f) + r)
                path.lineTo(sx(0.84f), sy(0.60f))
                path.lineTo(sx(0.60f), sy(0.84f))
                path.lineTo(sx(0.16f) + r, sy(0.84f))
                path.quadTo(sx(0.16f), sy(0.84f), sx(0.16f), sy(0.84f) - r)
                path.close()
                canvas.drawPath(path, strokePaint)

                // Corner fold
                path.reset()
                path.moveTo(sx(0.60f), sy(0.84f))
                path.lineTo(sx(0.60f), sy(0.60f))
                path.lineTo(sx(0.84f), sy(0.60f))
                canvas.drawPath(path, strokePaint)

                // Tiny smile inside
                canvas.drawCircle(sx(0.38f), sy(0.42f), strokeW * 0.6f, fillPaint)
                canvas.drawCircle(sx(0.60f), sy(0.42f), strokeW * 0.6f, fillPaint)
                rect.set(sx(0.36f), sy(0.46f), sx(0.62f), sy(0.62f))
                canvas.drawArc(rect, 20f, 140f, false, strokePaint)
            }

            ToolIconKind.MEDIA -> {
                // Rounded picture frame
                rect.set(sx(0.14f), sy(0.18f), sx(0.86f), sy(0.82f))
                canvas.drawRoundRect(rect, size * 0.10f, size * 0.10f, strokePaint)

                // Sun dot
                canvas.drawCircle(sx(0.34f), sy(0.36f), size * 0.065f, fillPaint)

                // Mountain silhouette
                path.reset()
                path.moveTo(sx(0.18f), sy(0.76f))
                path.lineTo(sx(0.42f), sy(0.50f))
                path.lineTo(sx(0.56f), sy(0.64f))
                path.lineTo(sx(0.68f), sy(0.48f))
                path.lineTo(sx(0.84f), sy(0.76f))
                canvas.drawPath(path, strokePaint)
            }

            ToolIconKind.HANZI -> {
                // Miniature 田字格 frame with a crisp 文 character inside
                rect.set(sx(0.14f), sy(0.14f), sx(0.86f), sy(0.86f))
                canvas.drawRoundRect(rect, size * 0.10f, size * 0.10f, strokePaint)
                // Top dot + horizontal bar + crossed strokes of 文
                canvas.drawLine(sx(0.50f), sy(0.26f), sx(0.50f), sy(0.34f), strokePaint)
                canvas.drawLine(sx(0.28f), sy(0.38f), sx(0.72f), sy(0.38f), strokePaint)
                path.reset()
                path.moveTo(sx(0.62f), sy(0.39f))
                path.quadTo(sx(0.54f), sy(0.62f), sx(0.28f), sy(0.76f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.38f), sy(0.44f))
                path.quadTo(sx(0.52f), sy(0.62f), sx(0.72f), sy(0.76f))
                canvas.drawPath(path, strokePaint)
            }

            ToolIconKind.FINGER -> {
                // Touch ripple arc at top
                rect.set(sx(0.32f), sy(0.10f), sx(0.68f), sy(0.46f))
                canvas.drawArc(rect, 205f, 130f, false, strokePaint)

                // Pointing index finger + palm
                path.reset()
                path.moveTo(sx(0.42f), sy(0.56f))
                path.lineTo(sx(0.42f), sy(0.28f))
                path.cubicTo(sx(0.42f), sy(0.20f), sx(0.56f), sy(0.20f), sx(0.56f), sy(0.28f))
                path.lineTo(sx(0.56f), sy(0.50f))
                path.lineTo(sx(0.72f), sy(0.54f))
                path.cubicTo(sx(0.79f), sy(0.56f), sx(0.80f), sy(0.62f), sx(0.78f), sy(0.70f))
                path.lineTo(sx(0.74f), sy(0.86f))
                path.lineTo(sx(0.38f), sy(0.86f))
                path.lineTo(sx(0.26f), sy(0.68f))
                path.cubicTo(sx(0.23f), sy(0.62f), sx(0.30f), sy(0.54f), sx(0.37f), sy(0.59f))
                path.lineTo(sx(0.42f), sy(0.63f))
                canvas.drawPath(path, strokePaint)
            }

            ToolIconKind.UNDO -> {
                // Curved counter-clockwise arrow
                path.reset()
                path.moveTo(sx(0.36f), sy(0.24f))
                path.lineTo(sx(0.18f), sy(0.42f))
                path.lineTo(sx(0.36f), sy(0.60f))
                canvas.drawPath(path, strokePaint)

                path.reset()
                path.moveTo(sx(0.20f), sy(0.42f))
                path.lineTo(sx(0.60f), sy(0.42f))
                path.cubicTo(sx(0.82f), sy(0.42f), sx(0.84f), sy(0.76f), sx(0.58f), sy(0.76f))
                path.lineTo(sx(0.40f), sy(0.76f))
                canvas.drawPath(path, strokePaint)
            }

            ToolIconKind.REDO -> {
                // Curved clockwise arrow
                path.reset()
                path.moveTo(sx(0.64f), sy(0.24f))
                path.lineTo(sx(0.82f), sy(0.42f))
                path.lineTo(sx(0.64f), sy(0.60f))
                canvas.drawPath(path, strokePaint)

                path.reset()
                path.moveTo(sx(0.80f), sy(0.42f))
                path.lineTo(sx(0.40f), sy(0.42f))
                path.cubicTo(sx(0.18f), sy(0.42f), sx(0.16f), sy(0.76f), sx(0.42f), sy(0.76f))
                path.lineTo(sx(0.60f), sy(0.76f))
                canvas.drawPath(path, strokePaint)
            }

            ToolIconKind.MORE -> {
                val r = size * 0.085f
                canvas.drawCircle(sx(0.24f), sy(0.50f), r, fillPaint)
                canvas.drawCircle(sx(0.50f), sy(0.50f), r, fillPaint)
                canvas.drawCircle(sx(0.76f), sy(0.50f), r, fillPaint)
            }
        }
    }
}
