package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class ToolChip(
    context: Context,
    labelRes: Int,
    private val tool: DiaryTool,
    iconKind: ToolIconKind,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val dot = IndicatorDot(context, isRing = tool == DiaryTool.ERASER)
    private val icon = ToolIconView(context, iconKind)
    private val label = TextView(context).apply {
        setText(labelRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        contentDescription = context.getString(labelRes)
        addView(dot, LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(6) })
        addView(icon, LayoutParams(dp(20), dp(20)))
        addView(label)
    }

    fun setBrushDisplay(labelRes: Int, iconKind: ToolIconKind) {
        label.setText(labelRes)
        contentDescription = context.getString(labelRes)
        icon.kind = iconKind
    }

    fun update(
        selected: Boolean,
        indicatorColor: Int,
        palette: DiaryPalette,
        toolbarMode: ToolbarMode,
    ) {
        val fg = if (selected) palette.accent else palette.text
        dot.dotColor = indicatorColor
        icon.iconColor = fg
        icon.inkColor = if (tool == DiaryTool.ERASER) null else indicatorColor

        when (toolbarMode) {
            ToolbarMode.ICON_ONLY -> {
                dot.visibility = GONE
                icon.visibility = VISIBLE
                label.visibility = GONE
                (icon.layoutParams as LayoutParams).marginEnd = 0
                setPadding(dp(9), 0, dp(9), 0)
            }

            ToolbarMode.ICON_AND_LABEL -> {
                dot.visibility = GONE
                icon.visibility = VISIBLE
                label.visibility = VISIBLE
                (icon.layoutParams as LayoutParams).marginEnd = dp(6)
                setPadding(dp(10), 0, dp(10), 0)
            }

            ToolbarMode.LABEL_ONLY -> {
                dot.visibility = VISIBLE
                icon.visibility = GONE
                label.visibility = VISIBLE
                setPadding(dp(10), 0, dp(10), 0)
            }
        }
        icon.requestLayout()

        label.setTextColor(fg)
        label.paint.isFakeBoldText = selected
        background = GradientDrawable().apply {
            setColor(if (selected) palette.paper else 0x00000000)
            setStroke(dp(1), if (selected) palette.accent else palette.rule)
            cornerRadius = 8f * density
        }
    }

    private fun dp(value: Int) = (value * density).roundToInt()
}

@SuppressLint("ViewConstructor")
internal class SettingsChip(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val dot = IndicatorDot(context, isRing = false)
    private val icon = ToolIconView(context, ToolIconKind.PEN)
    private val label = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        contentDescription = context.getString(R.string.tool_settings)
        setPadding(dp(9), 0, dp(9), 0)
        addView(dot, LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
        addView(icon, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) })
        addView(label)
    }

    fun update(
        tool: DiaryTool,
        color: Int,
        sizeText: String,
        expanded: Boolean,
        palette: DiaryPalette,
        toolbarMode: ToolbarMode,
        bankIcon: ToolIconKind = ToolIconKind.PENCIL,
    ) {
        val fg = if (expanded) palette.accent else palette.text
        dot.isRing = tool == DiaryTool.ERASER
        dot.dotColor = color
        icon.kind = when (tool) {
            DiaryTool.PEN -> ToolIconKind.PEN
            DiaryTool.BRUSH -> ToolIconKind.BRUSH
            DiaryTool.BANK -> bankIcon
            DiaryTool.ERASER -> ToolIconKind.ERASER
            DiaryTool.SELECT -> ToolIconKind.SELECT
        }
        icon.iconColor = fg
        icon.inkColor = if (tool == DiaryTool.ERASER) null else color

        val useIcon = toolbarMode != ToolbarMode.LABEL_ONLY
        dot.visibility = if (useIcon) GONE else VISIBLE
        icon.visibility = if (useIcon) VISIBLE else GONE

        label.text = if (expanded) "$sizeText ▴" else "$sizeText ▾"
        label.setTextColor(fg)
        background = GradientDrawable().apply {
            setColor(if (expanded) palette.paper else palette.paperSoft)
            setStroke(dp(1), if (expanded) palette.accent else palette.rule)
            cornerRadius = 8f * density
        }
    }

    private fun dp(value: Int) = (value * density).roundToInt()
}

@SuppressLint("ViewConstructor")
internal class ActionChip(
    context: Context,
    labelRes: Int,
    iconKind: ToolIconKind,
    private val showLabelInExtended: Boolean = true,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val icon = ToolIconView(context, iconKind)
    private val label = TextView(context).apply {
        setText(labelRes)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        contentDescription = context.getString(labelRes)
        addView(icon, LayoutParams(dp(20), dp(20)))
        addView(label)
    }

    fun update(
        active: Boolean,
        enabled: Boolean,
        palette: DiaryPalette,
        toolbarMode: ToolbarMode,
    ) {
        isEnabled = enabled
        val fg = when {
            !enabled -> palette.disabled
            active -> palette.accent
            else -> palette.text
        }
        icon.iconColor = fg
        label.setTextColor(fg)
        label.paint.isFakeBoldText = active

        when (toolbarMode) {
            ToolbarMode.ICON_ONLY -> {
                icon.visibility = VISIBLE
                label.visibility = GONE
                (icon.layoutParams as LayoutParams).marginEnd = 0
                setPadding(dp(9), 0, dp(9), 0)
            }

            ToolbarMode.ICON_AND_LABEL -> {
                icon.visibility = VISIBLE
                label.visibility = if (showLabelInExtended) VISIBLE else GONE
                (icon.layoutParams as LayoutParams).marginEnd = if (showLabelInExtended) dp(5) else 0
                setPadding(if (showLabelInExtended) dp(10) else dp(9), 0, if (showLabelInExtended) dp(10) else dp(9), 0)
            }

            ToolbarMode.LABEL_ONLY -> {
                icon.visibility = GONE
                label.visibility = VISIBLE
                setPadding(dp(10), 0, dp(10), 0)
            }
        }
        icon.requestLayout()

        background = GradientDrawable().apply {
            setColor(if (active) palette.paper else 0x00000000)
            setStroke(dp(1), if (active) palette.accent else palette.rule)
            cornerRadius = 8f * density
        }
    }

    private fun dp(value: Int) = (value * density).roundToInt()
}

@SuppressLint("ViewConstructor")
internal class IndicatorDot(context: Context, isRing: Boolean) : View(context) {
    private val density = resources.displayMetrics.density
    var isRing: Boolean = isRing
        set(value) {
            field = value
            invalidate()
        }
    var dotColor: Int = DiaryPalette.LIGHT.text
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = (minOf(width, height) / 2f) - 1.5f * density
        paint.color = dotColor
        if (isRing) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * density
        } else {
            paint.style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r, paint)
    }
}
