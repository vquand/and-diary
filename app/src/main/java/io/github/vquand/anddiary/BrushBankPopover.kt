package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // Constructed programmatically by InkSheet.
internal class BrushBankPopover(
    context: Context,
    private val brushes: DiaryBrushes,
    private val onSelect: (BrushSpec) -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val palette = DiarySettings.palette(context)
    private val rows = mutableListOf<BrushRowViews>()

    private data class BrushRowViews(
        val spec: BrushSpec,
        val container: LinearLayout,
        val icon: ToolIconView,
        val name: TextView,
        val star: ImageView,
    )

    init {
        orientation = VERTICAL
        isClickable = true
        elevation = 8f * density
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(palette.paper)
            setStroke(dp(1), palette.rule)
            cornerRadius = 10f * density
        }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                setText(R.string.brush_bank)
                setTextColor(palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                paint.isFakeBoldText = true
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                setText(R.string.close_symbol)
                setTextColor(palette.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(8), dp(2), dp(4), dp(2))
                setOnClickListener { onClose() }
            })
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        val listContainer = LinearLayout(context).apply { orientation = VERTICAL }
        for (spec in BrushBank.all) {
            val row = buildRow(spec)
            rows.add(row)
            listContainer.addView(row.container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }

        val scroll = ScrollView(context).apply {
            addView(listContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val maxScrollHeight = (resources.displayMetrics.heightPixels * 0.65f).roundToInt()
        val scrollHeight = minOf(dp(340), maxScrollHeight).coerceAtLeast(dp(140))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, scrollHeight))

        refreshSelection()
    }

    fun preferredDialogWidthPx(): Int {
        val maxDialogWidth = (resources.displayMetrics.widthPixels * 0.92f).roundToInt()
        return minOf(dp(356), maxDialogWidth).coerceAtLeast(dp(240))
    }

    private fun buildRow(spec: BrushSpec): BrushRowViews {
        val iconView = ToolIconView(context, spec.icon).apply {
            contentDescription = context.getString(spec.nameRes)
        }
        val nameView = TextView(context).apply {
            setText(spec.nameRes)
            setTextColor(palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val previewSizeDp = brushes.bankSizeDpFor(spec).coerceIn(1.5f, 18f)
        val previewView = BrushPreviewView(context, brushes.textureStore).apply {
            setStrokePreview(
                family = brushes.familyFor(spec),
                color = brushes.bankColorFor(spec),
                sizePx = previewSizeDp * density,
            )
        }
        val middle = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(nameView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)).apply {
                topMargin = dp(4)
            })
        }
        val starView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            contentDescription = context.getString(R.string.star_brush)
            isClickable = true
        }
        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(10), dp(8), dp(8), dp(8))
            addView(iconView, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
            addView(middle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(starView, LayoutParams(dp(30), dp(30)).apply { marginStart = dp(8) })
        }

        val pickAction = OnClickListener {
            brushes.selectBankBrush(spec.id)
            refreshSelection()
            onSelect(spec)
        }
        container.setOnClickListener(pickAction)
        starView.setOnClickListener(pickAction)

        return BrushRowViews(spec, container, iconView, nameView, starView)
    }

    private fun refreshSelection() {
        val activeId = brushes.bankBrush.id
        for (row in rows) {
            val selected = row.spec.id == activeId
            row.container.background = GradientDrawable().apply {
                setColor(if (selected) palette.wash else palette.paperSoft)
                setStroke(dp(1), if (selected) palette.accent else palette.rule)
                cornerRadius = 8f * density
            }
            row.icon.iconColor = if (selected) palette.accent else palette.text
            row.icon.inkColor = null
            row.name.setTextColor(if (selected) palette.accent else palette.text)
            row.name.paint.isFakeBoldText = selected
            row.star.setImageResource(if (selected) R.drawable.ic_star_on else R.drawable.ic_star_off)
            row.star.imageTintList = ColorStateList.valueOf(if (selected) palette.accent else palette.muted)
        }
    }

    private fun dp(value: Int) = (value * density).roundToInt()
}
