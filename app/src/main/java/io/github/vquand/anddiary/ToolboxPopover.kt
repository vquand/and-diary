package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // Constructed programmatically by InkSheet.
internal class ToolboxPopover(
    context: Context,
    private val brushes: DiaryBrushes,
    private val onChanged: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = DiarySettings.palette(context)
    private var tool: DiaryTool = DiaryTool.PEN
    private var customExpanded = false
    private var updatingSliders = false
    private val hsv = FloatArray(3)
    private val dividers = mutableListOf<View>()
    private val mutedLabels = mutableListOf<TextView>()

    private val titleView = TextView(context).apply {
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        paint.isFakeBoldText = true
    }
    private val previewView = StrokePreviewView(context)
    private val colorSection = LinearLayout(context).apply { orientation = VERTICAL }
    private val hexLabel = TextView(context).apply {
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }
    private val swatchViews = mutableListOf<SwatchView>()
    private val customSwatch = SwatchView(context, isCustom = true)
    private val customPanel = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
    }
    private val hueBar = makeSeekBar(360)
    private val satBar = makeSeekBar(100)
    private val valBar = makeSeekBar(100)

    private val sizeTitle = TextView(context).apply {
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }
    private val sizeValue = TextView(context).apply {
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }
    private val presetRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val sizeBar = makeSeekBar(100)

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = false
        setPadding(dp(14), dp(12), dp(14), dp(14))
        elevation = 8f * density
        applyCardBackground()

        // Header: tool title + live stroke/eraser preview
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(previewView, LayoutParams(dp(110), dp(34)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Color section (Pen & Brush)
        colorSection.addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })
        colorSection.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                setText(R.string.ink_color)
                setTextColor(palette.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                mutedLabels.add(this)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(hexLabel)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        // Two rows of swatches: 5 on row 1, 3 + Custom on row 2
        val row1 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val row2 = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        DiaryBrushes.INK_SWATCHES.forEachIndexed { index, swatchColor ->
            val swatch = SwatchView(context, isCustom = false).apply {
                color = swatchColor
                setOnClickListener {
                    customExpanded = false
                    brushes.setColorFor(tool, swatchColor)
                    syncFromState()
                    onChanged()
                }
            }
            swatchViews.add(swatch)
            val targetRow = if (index < 5) row1 else row2
            targetRow.addView(swatch, LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(8)
            })
        }
        customSwatch.setOnClickListener {
            customExpanded = !customExpanded
            customPanel.visibility = if (customExpanded) VISIBLE else GONE
            updateSwatchSelection()
        }
        row2.addView(customSwatch, LayoutParams(dp(38), dp(38)))

        colorSection.addView(row1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        colorSection.addView(row2, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Inline custom HSV sliders
        customPanel.addView(sliderRow(R.string.hue, hueBar), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        customPanel.addView(sliderRow(R.string.saturation, satBar), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        customPanel.addView(sliderRow(R.string.brightness, valBar), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        colorSection.addView(customPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(colorSection, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Size section (all tools)
        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10)
            bottomMargin = dp(10)
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sizeTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(sizeValue)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        addView(presetRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        addView(sizeBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        wireSliders()
    }

    fun bind(activeTool: DiaryTool) {
        tool = activeTool
        val isCustom = tool != DiaryTool.ERASER &&
            brushes.colorFor(tool) !in DiaryBrushes.INK_SWATCHES
        customExpanded = isCustom
        syncFromState()
    }

    fun applyPalette(next: DiaryPalette) {
        palette = next
        applyCardBackground()
        titleView.setTextColor(palette.text)
        hexLabel.setTextColor(palette.muted)
        sizeTitle.setTextColor(palette.muted)
        sizeValue.setTextColor(palette.text)
        for (view in dividers) view.setBackgroundColor(palette.rule)
        for (label in mutedLabels) label.setTextColor(palette.muted)
        for (swatch in swatchViews) swatch.invalidate()
        customSwatch.invalidate()
        syncFromState()
    }

    private fun applyCardBackground() {
        background = GradientDrawable().apply {
            setColor(palette.paper)
            setStroke(dp(1), palette.rule)
            cornerRadius = 10f * density
        }
    }

    private fun syncFromState() {
        titleView.setText(
            when (tool) {
                DiaryTool.PEN -> R.string.pen
                DiaryTool.BRUSH -> R.string.brush
                DiaryTool.ERASER -> R.string.eraser
                DiaryTool.SELECT -> R.string.select
            },
        )
        colorSection.visibility = if (tool == DiaryTool.ERASER) GONE else VISIBLE
        customPanel.visibility = if (tool != DiaryTool.ERASER && customExpanded) VISIBLE else GONE
        sizeTitle.setText(if (tool == DiaryTool.ERASER) R.string.eraser_size else R.string.stroke_size)

        if (tool != DiaryTool.ERASER) {
            val color = brushes.colorFor(tool)
            hexLabel.text = String.format(Locale.US, "#%06X", color and 0xffffff)
            Color.colorToHSV(color, hsv)
            updatingSliders = true
            hueBar.progress = hsv[0].roundToInt().coerceIn(0, 360)
            satBar.progress = (hsv[1] * 100f).roundToInt().coerceIn(0, 100)
            valBar.progress = (hsv[2] * 100f).roundToInt().coerceIn(0, 100)
            updatingSliders = false
            updateHsvTracks()
            updateSwatchSelection()
        }

        val sizeDp = brushes.sizeDpFor(tool)
        val range = brushes.sizeRangeFor(tool)
        sizeValue.text = formatSize(sizeDp)
        updatingSliders = true
        val fraction = ((sizeDp - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        sizeBar.progress = (fraction * 100f).roundToInt()
        updatingSliders = false
        rebuildPresets(sizeDp)
        previewView.invalidate()
    }

    private fun updateSwatchSelection() {
        val current = brushes.colorFor(tool)
        var matchedPreset = false
        for (view in swatchViews) {
            val active = view.color == current
            if (active) matchedPreset = true
            view.active = active
        }
        customSwatch.color = current
        customSwatch.active = !matchedPreset || customExpanded
    }

    private fun updateHsvTracks() {
        val pureHue = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val brightSat = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
        val neutralVal = Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2]))

        hueBar.progressDrawable = trackDrawable(
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN,
                Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
            ),
        )
        satBar.progressDrawable = trackDrawable(intArrayOf(neutralVal, Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))))
        valBar.progressDrawable = trackDrawable(intArrayOf(Color.BLACK, brightSat))
        // Keep pureHue referenced cleanly if val is 0 so hue track stays vivid.
        if (hsv[2] < 0.05f) {
            satBar.progressDrawable = trackDrawable(intArrayOf(Color.DKGRAY, pureHue))
        }
    }

    private fun trackDrawable(colors: IntArray): LayerDrawable {
        val gradient = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = 4f * density
            setSize(0, dp(8))
        }
        return LayerDrawable(arrayOf(gradient)).apply {
            setLayerInset(0, 0, dp(10), 0, dp(10))
        }
    }

    private fun rebuildPresets(currentSizeDp: Float) {
        presetRow.removeAllViews()
        val presets = brushes.sizePresetsFor(tool)
        presets.forEachIndexed { index, preset ->
            val isActive = kotlin.math.abs(currentSizeDp - preset) < 0.35f
            val chip = TextView(context).apply {
                text = formatSize(preset)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (isActive) palette.accent else palette.text)
                paint.isFakeBoldText = isActive
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor(if (isActive) palette.wash else palette.paper)
                    setStroke(dp(1), if (isActive) palette.accent else palette.rule)
                    cornerRadius = 6f * density
                }
                setOnClickListener {
                    brushes.setSizeDpFor(tool, preset)
                    syncFromState()
                    onChanged()
                }
            }
            presetRow.addView(chip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index < presets.lastIndex) marginEnd = dp(6)
            })
        }
    }

    private fun wireSliders() {
        val hsvListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updatingSliders || !fromUser) return
                hsv[0] = hueBar.progress.toFloat()
                hsv[1] = satBar.progress / 100f
                hsv[2] = valBar.progress / 100f
                val color = Color.HSVToColor(hsv)
                brushes.setColorFor(tool, color)
                hexLabel.text = String.format(Locale.US, "#%06X", color and 0xffffff)
                updateHsvTracks()
                updateSwatchSelection()
                previewView.invalidate()
                onChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        }
        hueBar.setOnSeekBarChangeListener(hsvListener)
        satBar.setOnSeekBarChangeListener(hsvListener)
        valBar.setOnSeekBarChangeListener(hsvListener)

        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (updatingSliders || !fromUser) return
                val range = brushes.sizeRangeFor(tool)
                val raw = range.start + (range.endInclusive - range.start) * (progress / 100f)
                val snapped = (raw * 2f).roundToInt() / 2f
                brushes.setSizeDpFor(tool, snapped)
                sizeValue.text = formatSize(brushes.sizeDpFor(tool))
                rebuildPresets(brushes.sizeDpFor(tool))
                previewView.invalidate()
                onChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun sliderRow(labelRes: Int, bar: SeekBar) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            setText(labelRes)
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            mutedLabels.add(this)
        }, LayoutParams(dp(34), LayoutParams.WRAP_CONTENT))
        addView(bar, LayoutParams(0, dp(28), 1f))
    }

    private fun makeSeekBar(maxValue: Int) = SeekBar(context).apply {
        max = maxValue
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(palette.rule)
        dividers.add(this)
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    private inner class StrokePreviewView(context: Context) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cy = height / 2f
            // Draw preview on a light paper pill so dark inks stay clearly visible in dark theme.
            fillPaint.color = DiaryPalette.LIGHT.paper
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f * density, 6f * density, fillPaint)
            if (tool == DiaryTool.ERASER) {
                val radius = (brushes.eraserRadiusPx * 0.55f).coerceIn(4f * density, cy - 3f * density)
                val cx = width - radius - 8f * density
                fillPaint.color = DiaryPalette.LIGHT.eraserFill
                canvas.drawCircle(cx, cy, radius, fillPaint)
                strokePaint.color = DiaryPalette.LIGHT.muted
                strokePaint.strokeWidth = 1.5f * density
                canvas.drawCircle(cx, cy, radius, strokePaint)
                return
            }

            val startX = 12f * density
            val endX = width - 12f * density
            val amp = 6f * density
            val strokePx = (brushes.sizeDpFor(tool) * density).coerceIn(1.5f * density, 18f * density)
            strokePaint.color = brushes.colorFor(tool)
            strokePaint.strokeWidth = strokePx
            path.reset()
            path.moveTo(startX, cy + amp * 0.4f)
            path.cubicTo(
                startX + (endX - startX) * 0.33f, cy - amp,
                startX + (endX - startX) * 0.66f, cy + amp,
                endX, cy - amp * 0.4f,
            )
            canvas.drawPath(path, strokePaint)
        }
    }

    private inner class SwatchView(
        context: Context,
        private val isCustom: Boolean,
    ) : FrameLayout(context) {
        var color: Int = palette.text
            set(value) {
                field = value
                invalidate()
            }
        var active: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 15f * density
            isFakeBoldText = true
        }

        init {
            setWillNotDraw(false)
            isClickable = true
            contentDescription = if (isCustom) {
                context.getString(R.string.custom_color)
            } else {
                context.getString(R.string.ink_color)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val outerRadius = (minOf(width, height) / 2f) - 2f * density

            if (active) {
                ringPaint.color = palette.accent
                ringPaint.strokeWidth = 2f * density
                canvas.drawCircle(cx, cy, outerRadius, ringPaint)
            }

            val innerRadius = outerRadius - 3.5f * density
            if (isCustom) {
                fillPaint.color = if (active) color else palette.wash
                canvas.drawCircle(cx, cy, innerRadius, fillPaint)
                ringPaint.color = palette.muted
                ringPaint.strokeWidth = 1.2f * density
                canvas.drawCircle(cx, cy, innerRadius, ringPaint)
                val luminance = Color.luminance(fillPaint.color)
                textPaint.color = if (luminance < 0.45f) 0xfff8f3e8.toInt() else 0xff302e29.toInt()
                val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText("+", cx, baseline, textPaint)
            } else {
                fillPaint.color = color
                canvas.drawCircle(cx, cy, innerRadius, fillPaint)
                // Keep dark swatches clearly separated on dark popover surfaces.
                if (palette.isDark) {
                    ringPaint.color = palette.rule
                    ringPaint.strokeWidth = 1f * density
                    canvas.drawCircle(cx, cy, innerRadius, ringPaint)
                }
            }
        }
    }

    companion object {
        fun formatSize(sizeDp: Float): String {
            val rounded = sizeDp.roundToInt()
            return if (kotlin.math.abs(sizeDp - rounded) < 0.05f) {
                "${rounded}dp"
            } else {
                String.format(Locale.US, "%.1fdp", sizeDp)
            }
        }
    }
}
