package io.github.vquand.anddiary

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

internal object HanziPracticeDialog {
    fun show(
        context: Context,
        word: HanziWord,
        onCompleted: (Int) -> Unit,
        onDismiss: () -> Unit,
    ): Dialog {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).roundToInt() }

        var dialogRef: Dialog? = null
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(COLOR_PAPER)
                setStroke(dp(1), COLOR_RULE)
                cornerRadius = 12f * density
            }
        }

        val statusView = TextView(context).apply {
            setTextColor(COLOR_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            text = context.getString(R.string.hanzi_initial_status, word.strokes.size)
        }

        val canvasView = HanziPracticeCanvas(
            context = context,
            word = word,
            onStatus = { message, accent ->
                statusView.text = message
                statusView.setTextColor(if (accent) COLOR_ACCENT else COLOR_MUTED)
            },
            onCompleted = onCompleted,
        )

        // Header: character + pinyin / Hán-Việt / meaning + close
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = word.char
                setTextColor(COLOR_TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
                setPadding(0, 0, dp(12), 0)
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = context.getString(R.string.hanzi_pinyin_hanviet, word.pinyin, word.hanViet)
                    setTextColor(COLOR_ACCENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    paint.isFakeBoldText = true
                })
                addView(TextView(context).apply {
                    text = context.getString(R.string.hanzi_meaning_strokes, word.meaning, word.strokes.size)
                    setTextColor(COLOR_TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(context).apply {
                setText(R.string.close_symbol)
                setTextColor(COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener { dialogRef?.dismiss() }
            })
        }

        val guideChip = makeButton(context, R.string.hanzi_guide, density, active = true)
        val animateChip = makeButton(context, R.string.hanzi_animate, density, active = false)
        val hintChip = makeButton(context, R.string.hanzi_hint, density, active = false)
        val resetChip = makeButton(context, R.string.hanzi_reset, density, active = false)

        animateChip.setOnClickListener { canvasView.playAnimation() }
        hintChip.setOnClickListener { canvasView.playHint() }
        guideChip.setOnClickListener {
            val enabled = canvasView.toggleGuide()
            styleButton(guideChip, density, active = enabled)
        }
        resetChip.setOnClickListener { canvasView.resetPractice() }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(animateChip, LinearLayout.LayoutParams(0, dp(38), 1.25f).apply { marginEnd = dp(6) })
            addView(hintChip, LinearLayout.LayoutParams(0, dp(38), 0.9f).apply { marginEnd = dp(6) })
            addView(guideChip, LinearLayout.LayoutParams(0, dp(38), 0.95f).apply { marginEnd = dp(6) })
            addView(resetChip, LinearLayout.LayoutParams(0, dp(38), 0.9f))
        }

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        root.addView(
            canvasView,
            LinearLayout.LayoutParams(dp(276), dp(276)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            },
        )
        root.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return Dialog(context).apply {
            dialogRef = this
            setContentView(root, ViewGroup.LayoutParams(dp(348), ViewGroup.LayoutParams.WRAP_CONTENT))
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setCanceledOnTouchOutside(true)
            setOnDismissListener {
                canvasView.stopAnimations()
                onDismiss()
            }
            show()
        }
    }

    private fun makeButton(context: Context, labelRes: Int, density: Float, active: Boolean) =
        TextView(context).apply {
            setText(labelRes)
            gravity = Gravity.CENTER
            isClickable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            styleButton(this, density, active)
        }

    private fun styleButton(button: TextView, density: Float, active: Boolean) {
        button.setTextColor(if (active) COLOR_ACCENT else COLOR_TEXT)
        button.paint.isFakeBoldText = active
        button.background = GradientDrawable().apply {
            setColor(if (active) COLOR_WASH else COLOR_PAPER_LIGHT)
            setStroke((1f * density).roundToInt(), if (active) COLOR_ACCENT else COLOR_RULE)
            cornerRadius = 8f * density
        }
    }

    private const val COLOR_PAPER = 0xfff8f3e8.toInt()
    private const val COLOR_PAPER_LIGHT = 0xfffffdf7.toInt()
    private const val COLOR_WASH = 0xffeee5d5.toInt()
    private const val COLOR_RULE = 0xffe0d7c6.toInt()
    private const val COLOR_TEXT = 0xff302e29.toInt()
    private const val COLOR_MUTED = 0xff736c60.toInt()
    private const val COLOR_ACCENT = 0xff984631.toInt()
}

@SuppressLint("ViewConstructor")
private class HanziPracticeCanvas(
    context: Context,
    private val word: HanziWord,
    private val onStatus: (String, Boolean) -> Unit,
    private val onCompleted: (Int) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private var showGuide = true

    private var animator: ValueAnimator? = null
    private var animProgress = -1f
    private var hintStrokeIndex = -1
    private var hintProgress = 0f

    private var matchedCount = 0
    private val strokeScores = mutableListOf<Int>()
    private val userStrokePaths = mutableListOf<List<Pair<Float, Float>>>()
    private val activePoints = mutableListOf<Pair<Float, Float>>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xfffffdf7.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffd2c5b0.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffe5dac7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val guideStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffe2d9c8.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 11f * density
    }
    private val inkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff272320.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 10f * density
    }
    private val activeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff283948.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 9f * density
    }
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff984631.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 10.5f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff984631.toInt()
        style = Paint.Style.FILL
    }
    private val sealBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff984631.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val sealTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff984631.toInt()
        textSize = 18f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val drawPath = Path()
    private val rect = RectF()

    fun toggleGuide(): Boolean {
        showGuide = !showGuide
        invalidate()
        return showGuide
    }

    fun resetPractice() {
        stopAnimations()
        matchedCount = 0
        strokeScores.clear()
        userStrokePaths.clear()
        activePoints.clear()
        onStatus("Write stroke 1 of ${word.strokes.size}", false)
        invalidate()
    }

    fun stopAnimations() {
        animator?.cancel()
        animator = null
        animProgress = -1f
        hintStrokeIndex = -1
        hintProgress = 0f
    }

    fun playAnimation() {
        stopAnimations()
        val total = word.strokes.size.coerceAtLeast(1)
        animator = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
            duration = (total * 720L).coerceAtLeast(900L)
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                animProgress = va.animatedValue as Float
                val current = min(total, animProgress.toInt() + 1)
                onStatus("Animating stroke $current / $total", false)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) {
                        animProgress = -1f
                        animator = null
                        updatePracticePrompt()
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    fun playHint() {
        if (matchedCount >= word.strokes.size) {
            playAnimation()
            return
        }
        stopAnimations()
        hintStrokeIndex = matchedCount
        onStatus("Hint: trace stroke ${matchedCount + 1} from the red dot", true)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750L
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                hintProgress = va.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) {
                        animator = null
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(1f * density, 1f * density, w - 1f * density, h - 1f * density)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, bgPaint)

        // 田字格 center crosshairs + subtle diagonals
        canvas.drawLine(w / 2f, 0f, w / 2f, h, gridPaint)
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        canvas.drawLine(0f, 0f, w, h, gridPaint)
        canvas.drawLine(w, 0f, 0f, h, gridPaint)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, borderPaint)

        // Silhouette guide
        if (showGuide) {
            for (stroke in word.strokes) {
                drawNormalizedStroke(canvas, stroke, 1f, guideStrokePaint, drawTip = false)
            }
        }

        if (animProgress >= 0f) {
            // Full stroke-order animation
            val fullCount = animProgress.toInt().coerceAtMost(word.strokes.size)
            for (i in 0 until fullCount) {
                drawNormalizedStroke(canvas, word.strokes[i], 1f, inkStrokePaint, drawTip = false)
            }
            if (fullCount < word.strokes.size) {
                val frac = (animProgress - fullCount).coerceIn(0f, 1f)
                drawNormalizedStroke(canvas, word.strokes[fullCount], frac, accentStrokePaint, drawTip = true)
            }
            return
        }

        // Practice mode: draw matched strokes
        for (i in 0 until matchedCount) {
            drawNormalizedStroke(canvas, word.strokes[i], 1f, inkStrokePaint, drawTip = false)
        }

        // Optional hint stroke
        if (hintStrokeIndex in word.strokes.indices) {
            val stroke = word.strokes[hintStrokeIndex]
            drawNormalizedStroke(canvas, stroke, hintProgress.coerceIn(0.05f, 1f), accentStrokePaint, drawTip = true)
            val start = stroke.first()
            canvas.drawCircle(start.first / 100f * w, start.second / 100f * h, 6f * density, dotPaint)
        }

        // Live user stroke
        if (activePoints.size >= 2) {
            drawPath.reset()
            drawPath.moveTo(activePoints[0].first / 100f * w, activePoints[0].second / 100f * h)
            for (i in 1 until activePoints.size) {
                drawPath.lineTo(activePoints[i].first / 100f * w, activePoints[i].second / 100f * h)
            }
            canvas.drawPath(drawPath, activeStrokePaint)
        }

        // Completion vermilion seal stamp
        if (matchedCount >= word.strokes.size && word.strokes.isNotEmpty()) {
            val sealSize = 34f * density
            val sx = w - sealSize - 10f * density
            val sy = h - sealSize - 10f * density
            rect.set(sx, sy, sx + sealSize, sy + sealSize)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, sealBorderPaint)
            val avg = if (strokeScores.isNotEmpty()) strokeScores.average().roundToInt() else 100
            val glyph = if (avg >= 85) "優" else "好"
            val baseline = rect.centerY() - (sealTextPaint.descent() + sealTextPaint.ascent()) / 2f
            canvas.drawText(glyph, rect.centerX(), baseline, sealTextPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        val nx = (event.x / width.toFloat() * 100f).coerceIn(0f, 100f)
        val ny = (event.y / height.toFloat() * 100f).coerceIn(0f, 100f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (animProgress >= 0f) stopAnimations()
                if (matchedCount >= word.strokes.size) {
                    resetPractice()
                }
                activePoints.clear()
                activePoints.add(nx to ny)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val last = activePoints.lastOrNull()
                if (last == null || hypot((nx - last.first).toDouble(), (ny - last.second).toDouble()) >= 1.2) {
                    activePoints.add(nx to ny)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                activePoints.add(nx to ny)
                evaluateStroke(activePoints.toList())
                activePoints.clear()
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                activePoints.clear()
                invalidate()
            }
        }
        return true
    }

    private fun evaluateStroke(userRaw: List<Pair<Float, Float>>) {
        if (matchedCount >= word.strokes.size) return
        val expected = word.strokes[matchedCount]
        val score = HanziStrokeMatcher.matchScore(userRaw, expected)
        if (score != null) {
            hintStrokeIndex = -1
            userStrokePaths.add(userRaw)
            strokeScores.add(score)
            matchedCount++
            if (matchedCount >= word.strokes.size && word.strokes.isNotEmpty()) {
                val avg = strokeScores.average().roundToInt()
                onCompleted(avg)
            }
            updatePracticePrompt()
        } else {
            onStatus("Stroke ${matchedCount + 1} didn't match — follow the red hint", true)
            playHint()
        }
    }

    private fun updatePracticePrompt() {
        val total = word.strokes.size
        if (matchedCount >= total) {
            val avg = if (strokeScores.isNotEmpty()) strokeScores.average().roundToInt() else 100
            onStatus("Matched ${word.char}! $avg% accuracy — write again to replay", true)
        } else {
            onStatus("Write stroke ${matchedCount + 1} of $total", false)
        }
    }

    private fun drawNormalizedStroke(
        canvas: Canvas,
        points: List<Pair<Float, Float>>,
        fraction: Float,
        paint: Paint,
        drawTip: Boolean,
    ) {
        if (points.size < 2 || fraction <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val totalLen = HanziStrokeMatcher.polylineLength(points)
        if (totalLen <= 0.001f) return
        val targetLen = totalLen * fraction.coerceIn(0f, 1f)

        drawPath.reset()
        drawPath.moveTo(points[0].first / 100f * w, points[0].second / 100f * h)
        var acc = 0f
        var tipX = points[0].first / 100f * w
        var tipY = points[0].second / 100f * h

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val seg = HanziStrokeMatcher.dist(prev, curr)
            if (acc + seg >= targetLen) {
                val t = if (seg > 0.001f) (targetLen - acc) / seg else 1f
                val px = prev.first + (curr.first - prev.first) * t
                val py = prev.second + (curr.second - prev.second) * t
                tipX = px / 100f * w
                tipY = py / 100f * h
                drawPath.lineTo(tipX, tipY)
                break
            } else {
                tipX = curr.first / 100f * w
                tipY = curr.second / 100f * h
                drawPath.lineTo(tipX, tipY)
                acc += seg
            }
        }
        canvas.drawPath(drawPath, paint)
        if (drawTip) {
            canvas.drawCircle(tipX, tipY, 5.5f * density, dotPaint)
        }
    }
}
