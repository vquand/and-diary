package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

internal object ContourEditorDialog {
    fun show(
        context: Context,
        palette: DiaryPalette,
        initialDocument: ContourDocument = ContourDocument(),
        onConfirm: (ContourDocument) -> Unit,
        onDismiss: () -> Unit = {},
    ): Dialog {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).roundToInt() }

        var selectedStyle = initialDocument.style
        var previewMode = false
        var selectedLevel: Int? = null // null = Auto nesting elevation
        val strokes = initialDocument.strokes.toMutableList()
        var committedStrokes = initialDocument.strokes
        var dialogRef: Dialog? = null

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(palette.paper)
                setStroke(dp(1), palette.rule)
                cornerRadius = 14f * density
            }
        }

        val hintView = TextView(context).apply {
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setText(R.string.contour_draw_hint)
        }

        lateinit var canvasView: ContourEditorCanvas
        lateinit var undoBtn: TextView
        lateinit var clearBtn: TextView

        fun updateActionButtons() {
            val hasStrokes = strokes.isNotEmpty()
            undoBtn.isEnabled = hasStrokes && !previewMode
            undoBtn.setTextColor(if (undoBtn.isEnabled) palette.text else palette.disabled)
            clearBtn.isEnabled = hasStrokes && !previewMode
            clearBtn.setTextColor(if (clearBtn.isEnabled) palette.danger else palette.disabled)
        }

        // Header row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    setText(R.string.contour_editor_title)
                    setTextColor(palette.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    paint.isFakeBoldText = true
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                TextView(context).apply {
                    setText(R.string.close_symbol)
                    setTextColor(palette.muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    gravity = Gravity.CENTER
                    isClickable = true
                    setPadding(dp(10), dp(2), dp(4), dp(2))
                    setOnClickListener { dialogRef?.dismiss() }
                },
            )
        }

        // Mode (Draw 2D / Preview 3D) + Style (Smooth / Block) bar
        val drawModeChip = makeChip(context, R.string.contour_mode_draw, density)
        val previewModeChip = makeChip(context, R.string.contour_mode_preview, density)
        val smoothChip = makeChip(context, R.string.contour_style_smooth, density)
        val blockChip = makeChip(context, R.string.contour_style_block, density)

        val levelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun currentDocument() = ContourDocument(
            style = selectedStyle,
            strokes = committedStrokes,
            yawRad = initialDocument.yawRad,
            pitchRad = initialDocument.pitchRad,
        )

        fun refreshTopChips() {
            styleChip(drawModeChip, !previewMode, palette, density)
            styleChip(previewModeChip, previewMode, palette, density)
            styleChip(smoothChip, selectedStyle == ContourRenderStyle.SMOOTH, palette, density)
            styleChip(blockChip, selectedStyle == ContourRenderStyle.BLOCK, palette, density)
            levelRow.visibility = if (previewMode) View.GONE else View.VISIBLE
            hintView.setText(
                if (previewMode) R.string.contour_preview_hint else R.string.contour_draw_hint,
            )
            updateActionButtons()
            canvasView.updateState(
                document = currentDocument(),
                preview3d = previewMode,
                drawLevel = selectedLevel,
            )
        }

        drawModeChip.setOnClickListener {
            previewMode = false
            refreshTopChips()
        }
        previewModeChip.setOnClickListener {
            previewMode = true
            refreshTopChips()
        }
        smoothChip.setOnClickListener {
            selectedStyle = ContourRenderStyle.SMOOTH
            refreshTopChips()
        }
        blockChip.setOnClickListener {
            selectedStyle = ContourRenderStyle.BLOCK
            refreshTopChips()
        }

        val topControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(drawModeChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) })
            addView(previewModeChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(10) })
            addView(smoothChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) })
            addView(blockChip, LinearLayout.LayoutParams(0, dp(34), 1f))
        }

        // Elevation level chips: Auto, 1..6
        val levelChoices: List<Pair<Int?, String>> = listOf(null to context.getString(R.string.contour_level_auto)) +
            (1..6).map { it to "L$it" }
        val levelChips = mutableListOf<Pair<Int?, TextView>>()
        fun styleLevelChips() {
            for ((lvl, chip) in levelChips) {
                styleChip(chip, lvl == selectedLevel, palette, density)
            }
        }
        levelRow.addView(
            TextView(context).apply {
                setText(R.string.contour_elevation_label)
                setTextColor(palette.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                paint.isFakeBoldText = true
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            },
        )
        for ((idx, pair) in levelChoices.withIndex()) {
            val (lvl, text) = pair
            val chip = TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                isClickable = true
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener {
                    selectedLevel = lvl
                    styleLevelChips()
                    canvasView.updateState(
                        document = currentDocument(),
                        preview3d = previewMode,
                        drawLevel = selectedLevel,
                    )
                }
            }
            levelChips.add(lvl to chip)
            levelRow.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(30), if (lvl == null) 1.35f else 1f).apply {
                    if (idx < levelChoices.lastIndex) marginEnd = dp(4)
                },
            )
        }
        styleLevelChips()

        // Canvas (4:3 rectangle area)
        val screenW = context.resources.displayMetrics.widthPixels
        val dialogW = min((screenW * 0.92f).roundToInt(), dp(520)).coerceAtLeast(dp(320))
        val canvasW = dialogW - dp(32)
        val canvasH = (canvasW * 0.72f).roundToInt().coerceIn(dp(220), dp(340))

        canvasView = ContourEditorCanvas(
            context = context,
            initialDocument = initialDocument,
            onStrokeAdded = { stroke ->
                strokes.add(stroke)
                committedStrokes = strokes.toList()
                updateActionButtons()
                canvasView.updateState(
                    document = currentDocument(),
                    preview3d = previewMode,
                    drawLevel = selectedLevel,
                )
            },
        )

        // Bottom controls: Undo / Clear on left, Cancel / Done on right
        undoBtn = TextView(context).apply {
            setText(R.string.undo)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener {
                if (strokes.isNotEmpty() && !previewMode) {
                    strokes.removeAt(strokes.lastIndex)
                    committedStrokes = strokes.toList()
                    refreshTopChips()
                }
            }
        }
        clearBtn = TextView(context).apply {
            setText(R.string.contour_clear)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            setOnClickListener {
                if (strokes.isNotEmpty() && !previewMode) {
                    strokes.clear()
                    committedStrokes = emptyList()
                    refreshTopChips()
                }
            }
        }
        val cancelBtn = TextView(context).apply {
            setText(android.R.string.cancel)
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            isClickable = true
            setOnClickListener { dialogRef?.dismiss() }
        }
        val doneBtn = TextView(context).apply {
            setText(R.string.contour_convert_done)
            setTextColor(palette.paper)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            paint.isFakeBoldText = true
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isClickable = true
            background = GradientDrawable().apply {
                setColor(palette.accent)
                cornerRadius = 8f * density
            }
            setOnClickListener {
                if (strokes.isEmpty()) {
                    Toast.makeText(context, R.string.contour_empty_warning, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val finalDoc = currentDocument()
                dialogRef?.dismiss()
                onConfirm(finalDoc)
            }
        }

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(undoBtn)
            addView(clearBtn)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
            addView(doneBtn)
        }

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        root.addView(topControls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        root.addView(levelRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        root.addView(hintView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        root.addView(canvasView, LinearLayout.LayoutParams(canvasW, canvasH).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        })
        root.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        refreshTopChips()

        return Dialog(context).apply {
            dialogRef = this
            setContentView(root, ViewGroup.LayoutParams(dialogW, ViewGroup.LayoutParams.WRAP_CONTENT))
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setCanceledOnTouchOutside(true)
            setOnDismissListener { onDismiss() }
            show()
        }
    }

    private fun makeChip(context: Context, labelRes: Int, density: Float) =
        TextView(context).apply {
            setText(labelRes)
            gravity = Gravity.CENTER
            isClickable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding((8f * density).roundToInt(), 0, (8f * density).roundToInt(), 0)
        }

    private fun styleChip(chip: TextView, active: Boolean, palette: DiaryPalette, density: Float) {
        chip.setTextColor(if (active) palette.accent else palette.text)
        chip.paint.isFakeBoldText = active
        chip.background = GradientDrawable().apply {
            setColor(if (active) palette.wash else palette.paperSoft)
            setStroke((1f * density).roundToInt(), if (active) palette.accent else palette.rule)
            cornerRadius = 8f * density
        }
    }
}

@SuppressLint("ViewConstructor")
private class ContourEditorCanvas(
    context: Context,
    initialDocument: ContourDocument,
    private val onStrokeAdded: (ContourStroke) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private var document: ContourDocument = initialDocument
    private var preview3d: Boolean = false
    private var drawLevel: Int? = null
    private var cachedStrokes: List<ContourStroke>? = null
    private var cachedHeightmap: ContourHeightmap? = null
    private var cachedVoxels: VoxelHeightmap? = null
    private var cachedVoxelsFor: ContourHeightmap? = null
    private var cachedResolvedStrokes: List<ContourStroke> = initialDocument.strokes
    private var cachedResolvedLevels: List<Int> = ContourTerrainEngine.resolveLevels(initialDocument.strokes)
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private val activePoints = mutableListOf<Pair<Float, Float>>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xfffffdf7.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff3b3732.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val gridTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55736c60
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff272320.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.1f * density
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff984631.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.4f * density
    }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xeefffdf7.toInt()
        style = Paint.Style.FILL
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff736c60.toInt()
        textSize = 10f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val drawPath = Path()
    private val frameRect = RectF()

    fun updateState(document: ContourDocument, preview3d: Boolean, drawLevel: Int?) {
        this.document = document
        this.preview3d = preview3d
        this.drawLevel = drawLevel
        if (preview3d) {
            activePoints.clear()
            activePointerId = MotionEvent.INVALID_POINTER_ID
        }
        if (cachedResolvedStrokes !== document.strokes) {
            cachedResolvedStrokes = document.strokes
            cachedResolvedLevels = ContourTerrainEngine.resolveLevels(document.strokes)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = 2f * density
        frameRect.set(pad, pad, w - pad, h - pad)
        canvas.drawRoundRect(frameRect, 8f * density, 8f * density, bgPaint)

        if (preview3d) {
            val cached = cachedHeightmap
            val hm = if (cachedStrokes === document.strokes && cached != null) {
                cached
            } else {
                ContourTerrainEngine.buildHeightmap(document.strokes).also {
                    cachedStrokes = document.strokes
                    cachedHeightmap = it
                }
            }
            val voxels = if (document.style == ContourRenderStyle.BLOCK) {
                if (cachedVoxelsFor !== hm) {
                    cachedVoxels = ContourTerrainEngine.quantizeForBlocks(hm)
                    cachedVoxelsFor = hm
                }
                cachedVoxels
            } else {
                null
            }
            Contour3DRenderer.draw(canvas, document, w, h, hm, voxels)
            canvas.drawRoundRect(frameRect, 8f * density, 8f * density, borderPaint)
            return
        }

        // Topographic map crosshairs / ticks inside the rectangle area
        for (i in 1..3) {
            val gx = w * i / 4f
            canvas.drawLine(gx, pad, gx, pad + 6f * density, gridTickPaint)
            canvas.drawLine(gx, h - pad - 6f * density, gx, h - pad, gridTickPaint)
        }
        for (i in 1..2) {
            val gy = h * i / 3f
            canvas.drawLine(pad, gy, pad + 6f * density, gy, gridTickPaint)
            canvas.drawLine(w - pad - 6f * density, gy, w - pad, gy, gridTickPaint)
        }
        val cross = 5f * density
        for (ix in 1..3) {
            for (iy in 1..2) {
                val cx = w * ix / 4f
                val cy = h * iy / 3f
                canvas.drawLine(cx - cross, cy, cx + cross, cy, gridTickPaint)
                canvas.drawLine(cx, cy - cross, cx, cy + cross, gridTickPaint)
            }
        }

        // Draw committed contour strokes with their resolved elevation labels
        val resolved = cachedResolvedLevels
        for (i in document.strokes.indices) {
            val pts = document.strokes[i].points
            if (pts.size < 2) continue
            val level = resolved.getOrElse(i) { 1 }
            strokePaint.strokeWidth = if (level % 3 == 0) 2.5f * density else 1.8f * density
            drawPath.reset()
            drawPath.moveTo(pts[0].first * w, pts[0].second * h)
            for (p in 1 until pts.size) {
                drawPath.lineTo(pts[p].first * w, pts[p].second * h)
            }
            canvas.drawPath(drawPath, strokePaint)

            // Draw small elevation level label near the midpoint of the stroke
            val mid = pts[pts.size / 2]
            val lx = (mid.first * w).coerceIn(14f * density, w - 14f * density)
            val ly = (mid.second * h).coerceIn(12f * density, h - 12f * density)
            canvas.drawCircle(lx, ly, 7.5f * density, badgeBg)
            val base = ly - (badgeText.descent() + badgeText.ascent()) / 2f
            canvas.drawText(level.toString(), lx, base, badgeText)
        }

        // Active live stroke
        if (activePoints.size >= 2) {
            drawPath.reset()
            drawPath.moveTo(activePoints[0].first * w, activePoints[0].second * h)
            for (p in 1 until activePoints.size) {
                drawPath.lineTo(activePoints[p].first * w, activePoints[p].second * h)
            }
            canvas.drawPath(drawPath, activePaint)
        }

        canvas.drawRoundRect(frameRect, 8f * density, 8f * density, borderPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (preview3d || width <= 0 || height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                val nx = (event.getX(0) / width.toFloat()).coerceIn(0f, 1f)
                val ny = (event.getY(0) / height.toFloat()).coerceIn(0f, 1f)
                activePoints.clear()
                activePoints.add(nx to ny)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx == -1) return true
                val nx = (event.getX(idx) / width.toFloat()).coerceIn(0f, 1f)
                val ny = (event.getY(idx) / height.toFloat()).coerceIn(0f, 1f)
                val last = activePoints.lastOrNull()
                if (last == null || hypot((nx - last.first).toDouble(), (ny - last.second).toDouble()) >= 0.012) {
                    activePoints.add(nx to ny)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val actionIdx = event.actionIndex
                if (event.getPointerId(actionIdx) != activePointerId) return true
                val canceled = Build.VERSION.SDK_INT >= 33 &&
                    (event.flags and MotionEvent.FLAG_CANCELED) != 0
                if (!canceled) {
                    val nx = (event.getX(actionIdx) / width.toFloat()).coerceIn(0f, 1f)
                    val ny = (event.getY(actionIdx) / height.toFloat()).coerceIn(0f, 1f)
                    activePoints.add(nx to ny)
                    val first = activePoints.firstOrNull()
                    val hasMovement = first != null && activePoints.any {
                        hypot((it.first - first.first).toDouble(), (it.second - first.second).toDouble()) >= 0.008
                    }
                    if (activePoints.size >= 2 && hasMovement) {
                        onStrokeAdded(ContourStroke(activePoints.toList(), drawLevel))
                    }
                }
                activePoints.clear()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                activePoints.clear()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }
        }
        return true
    }
}
