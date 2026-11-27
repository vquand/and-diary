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
    PENCIL,
    CHINESE_INK_BRUSH,
    CALLIGRAPHY_QUILL,
    AIRBRUSH,
    CHARCOAL,
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

                fillPaint.color = chosen
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(sx(0.32f), sy(0.52f), sx(0.48f), sy(0.68f), strokePaint)
                drawInkColorBar(canvas, ::sx, ::sy, strokeW)
            }

            ToolIconKind.BRUSH -> {
                val chosen = inkColor ?: iconColor
                path.reset()
                path.moveTo(sx(0.44f), sy(0.48f))
                path.lineTo(sx(0.76f), sy(0.12f))
                path.lineTo(sx(0.88f), sy(0.24f))
                path.lineTo(sx(0.52f), sy(0.56f))
                path.close()
                canvas.drawPath(path, strokePaint)

                path.reset()
                path.moveTo(sx(0.44f), sy(0.48f))
                path.cubicTo(sx(0.28f), sy(0.48f), sx(0.24f), sy(0.66f), sx(0.14f), sy(0.78f))
                path.cubicTo(sx(0.30f), sy(0.80f), sx(0.52f), sy(0.74f), sx(0.52f), sy(0.56f))
                path.close()

                fillPaint.color = chosen
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
                drawInkColorBar(canvas, ::sx, ::sy, strokeW)
            }

            ToolIconKind.PENCIL -> {
                fillPaint.color = inkColor ?: iconColor
                path.reset()
                path.moveTo(sx(0.779f), sy(0.059f))
                path.lineTo(sx(0.674f), sy(0.164f))
                path.lineTo(sx(0.836f), sy(0.326f))
                path.lineTo(sx(0.941f), sy(0.221f))
                path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.674f), sy(0.164f))
                path.lineTo(sx(0.317f), sy(0.521f))
                path.lineTo(sx(0.479f), sy(0.683f))
                path.lineTo(sx(0.836f), sy(0.326f))
                path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.618f), sy(0.220f))
                path.lineTo(sx(0.780f), sy(0.382f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.650f), sy(0.350f))
                path.lineTo(sx(0.398f), sy(0.602f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.317f), sy(0.521f))
                path.lineTo(sx(0.160f), sy(0.840f))
                path.lineTo(sx(0.479f), sy(0.683f))
                path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.214f), sy(0.726f))
                path.lineTo(sx(0.160f), sy(0.840f))
                path.lineTo(sx(0.274f), sy(0.786f))
                path.close()
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
                drawInkColorBar(canvas, ::sx, ::sy, strokeW)
            }

            ToolIconKind.CHINESE_INK_BRUSH -> {
                fillPaint.color = inkColor ?: iconColor
                path.reset()
                path.moveTo(sx(0.900f), sy(0.080f))
                path.lineTo(sx(0.471f), sy(0.509f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.425f), sy(0.463f))
                path.lineTo(sx(0.366f), sy(0.522f))
                path.lineTo(sx(0.458f), sy(0.614f))
                path.lineTo(sx(0.517f), sy(0.555f))
                path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.366f), sy(0.522f))
                path.cubicTo(sx(0.253f), sy(0.529f), sx(0.207f), sy(0.660f), sx(0.160f), sy(0.820f))
                path.cubicTo(sx(0.320f), sy(0.773f), sx(0.451f), sy(0.727f), sx(0.458f), sy(0.614f))
                path.close()
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
                drawInkColorBar(canvas, ::sx, ::sy, strokeW)
            }

            ToolIconKind.CALLIGRAPHY_QUILL -> {
                fillPaint.color = inkColor ?: iconColor
                path.reset()
                path.moveTo(sx(0.866f), sy(0.115f))
                path.lineTo(sx(0.787f), sy(0.102f))
                path.lineTo(sx(0.721f), sy(0.101f))
                path.lineTo(sx(0.662f), sy(0.108f))
                path.lineTo(sx(0.611f), sy(0.123f))
                path.lineTo(sx(0.568f), sy(0.144f))
                path.lineTo(sx(0.531f), sy(0.172f))
                path.lineTo(sx(0.501f), sy(0.207f))
                path.lineTo(sx(0.477f), sy(0.248f))
                path.lineTo(sx(0.459f), sy(0.294f))
                path.lineTo(sx(0.446f), sy(0.344f))
                path.lineTo(sx(0.436f), sy(0.399f))
                path.lineTo(sx(0.429f), sy(0.456f))
                path.lineTo(sx(0.424f), sy(0.514f))
                path.lineTo(sx(0.419f), sy(0.574f))
                path.lineTo(sx(0.467f), sy(0.557f))
                path.lineTo(sx(0.515f), sy(0.540f))
                path.lineTo(sx(0.562f), sy(0.521f))
                path.lineTo(sx(0.607f), sy(0.501f))
                path.lineTo(sx(0.650f), sy(0.479f))
                path.lineTo(sx(0.690f), sy(0.455f))
                path.lineTo(sx(0.727f), sy(0.427f))
                path.lineTo(sx(0.760f), sy(0.395f))
                path.lineTo(sx(0.789f), sy(0.359f))
                path.lineTo(sx(0.814f), sy(0.320f))
                path.lineTo(sx(0.834f), sy(0.276f))
                path.lineTo(sx(0.850f), sy(0.228f))
                path.lineTo(sx(0.862f), sy(0.175f))
                path.lineTo(sx(0.866f), sy(0.115f))
                path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.909f), sy(0.070f))
                path.lineTo(sx(0.275f), sy(0.722f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.707f), sy(0.278f))
                path.lineTo(sx(0.516f), sy(0.236f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.592f), sy(0.396f))
                path.lineTo(sx(0.450f), sy(0.402f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.279f), sy(0.668f))
                path.lineTo(sx(0.160f), sy(0.840f))
                path.lineTo(sx(0.329f), sy(0.716f))
                path.close()
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
                drawInkColorBar(canvas, ::sx, ::sy, strokeW)
            }

            ToolIconKind.AIRBRUSH -> {
                fillPaint.color = inkColor ?: iconColor
                path.reset()
                path.moveTo(sx(0.775f), sy(0.055f))
                path.lineTo(sx(0.445f), sy(0.385f))
                path.lineTo(sx(0.615f), sy(0.555f))
                path.lineTo(sx(0.945f), sy(0.225f))
                path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.709f), sy(0.121f))
                path.lineTo(sx(0.660f), sy(0.072f))
                path.lineTo(sx(0.541f), sy(0.190f))
                path.lineTo(sx(0.590f), sy(0.240f))
                path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.720f), sy(0.449f))
                path.lineTo(sx(0.703f), sy(0.537f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.445f), sy(0.385f))
                path.lineTo(sx(0.413f), sy(0.538f))
                path.lineTo(sx(0.462f), sy(0.587f))
                path.lineTo(sx(0.615f), sy(0.555f))
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(sx(0.358f), sy(0.642f), size * 0.022f, fillPaint)
                canvas.drawCircle(sx(0.287f), sy(0.649f), size * 0.022f, fillPaint)
                canvas.drawCircle(sx(0.351f), sy(0.713f), size * 0.022f, fillPaint)
                canvas.drawCircle(sx(0.273f), sy(0.727f), size * 0.028f, fillPaint)
                canvas.drawCircle(sx(0.189f), sy(0.684f), size * 0.020f, fillPaint)
                canvas.drawCircle(sx(0.316f), sy(0.811f), size * 0.020f, fillPaint)
                canvas.drawCircle(sx(0.175f), sy(0.762f), size * 0.020f, fillPaint)
                canvas.drawCircle(sx(0.238f), sy(0.810f), size * 0.020f, fillPaint)
                canvas.drawCircle(sx(0.095f), sy(0.721f), size * 0.016f, fillPaint)
                drawInkColorBar(canvas, ::sx, ::sy, strokeW)
            }

            ToolIconKind.CHARCOAL -> {
                fillPaint.color = inkColor ?: iconColor
                path.reset()
                path.moveTo(sx(0.755f), sy(0.075f))
                path.lineTo(sx(0.512f), sy(0.318f))
                path.lineTo(sx(0.682f), sy(0.488f))
                path.lineTo(sx(0.925f), sy(0.245f))
                path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.691f), sy(0.139f))
                path.lineTo(sx(0.861f), sy(0.309f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(sx(0.512f), sy(0.318f))
                path.lineTo(sx(0.396f), sy(0.420f))
                path.lineTo(sx(0.321f), sy(0.537f))
                path.lineTo(sx(0.230f), sy(0.600f))
                path.lineTo(sx(0.189f), sy(0.712f))
                path.lineTo(sx(0.200f), sy(0.800f))
                path.lineTo(sx(0.274f), sy(0.810f))
                path.lineTo(sx(0.340f), sy(0.773f))
                path.lineTo(sx(0.451f), sy(0.718f))
                path.lineTo(sx(0.547f), sy(0.609f))
                path.lineTo(sx(0.682f), sy(0.488f))
                path.close()
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(sx(0.102f), sy(0.728f), size * 0.022f, fillPaint)
                canvas.drawCircle(sx(0.151f), sy(0.623f), size * 0.016f, fillPaint)
                drawInkColorBar(canvas, ::sx, ::sy, strokeW)
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
                // Crisp pointer cursor
                path.reset()
                path.moveTo(sx(0.24f), sy(0.14f))
                path.lineTo(sx(0.24f), sy(0.82f))
                path.lineTo(sx(0.43f), sy(0.64f))
                path.lineTo(sx(0.58f), sy(0.88f))
                path.lineTo(sx(0.69f), sy(0.82f))
                path.lineTo(sx(0.54f), sy(0.58f))
                path.lineTo(sx(0.80f), sy(0.58f))
                path.close()
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

    private inline fun drawInkColorBar(
        canvas: Canvas,
        sx: (Float) -> Float,
        sy: (Float) -> Float,
        strokeW: Float,
    ) {
        val color = inkColor ?: return
        strokePaint.color = color
        strokePaint.strokeWidth = strokeW * 1.45f
        canvas.drawLine(sx(0.16f), sy(0.90f), sx(0.84f), sy(0.90f), strokePaint)
        strokePaint.color = iconColor
        strokePaint.strokeWidth = strokeW
    }
}
