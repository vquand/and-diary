package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.Toast
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import java.time.LocalDate
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // The GPUI host supplies the selected date programmatically.
class InkSheet(context: Context, date: LocalDate) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val brushes = DiaryBrushes(density)
    private val entry = InkEntry(context, date, brushes)
    private val undo = Button(context).apply {
        setText(R.string.undo)
        isAllCaps = false
        isEnabled = false
        minimumWidth = 0
        minWidth = 0
    }
    private val paper = Paper()
    private var clearDialog: AlertDialog? = null

    init {
        orientation = VERTICAL
        val pen = tool(R.string.pen)
        val brush = tool(R.string.brush)
        val tools = RadioGroup(context).apply {
            orientation = HORIZONTAL
            addView(pen)
            addView(brush)
            check(pen.id)
            setOnCheckedChangeListener { _, id -> paper.useBrush = id == brush.id }
        }
        val fingerInk = Switch(context).apply {
            setText(R.string.finger_ink)
            switchPadding = dp(6)
            setOnCheckedChangeListener { _, checked ->
                paper.cancelGesture()
                paper.fingerInk = checked
            }
        }
        undo.setOnClickListener { paper.undo() }

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            setBackgroundColor(0xffeee5d5.toInt())
            minimumHeight = dp(52)
            addView(tools)
            addView(fingerInk, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            })
            addView(View(context), LayoutParams(0, 1, 1f))
            addView(undo)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(paper, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun cancelGesture() = paper.cancelGesture()

    fun confirmClearDay() {
        if (clearDialog != null) return
        if (!paper.hasInk) {
            Toast.makeText(context, R.string.ink_empty, Toast.LENGTH_SHORT).show()
            return
        }
        clearDialog = AlertDialog.Builder(context)
            .setTitle(R.string.clear_day)
            .setMessage(R.string.clear_day_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_day) { _, _ -> paper.clearDay() }
            .create().also { dialog ->
                dialog.setOnDismissListener { clearDialog = null }
                dialog.show()
            }
    }

    override fun onDetachedFromWindow() {
        clearDialog?.dismiss()
        super.onDetachedFromWindow()
    }

    private fun tool(label: Int) = RadioButton(context).apply {
        id = View.generateViewId()
        setText(label)
        minHeight = dp(48)
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    private data class Drawing(val pointerId: Int, val strokeId: InProgressStrokeId)
    private data class Scrolling(val pointerId: Int, val downY: Float, val startOffset: Float)

    private inner class Paper : FrameLayout(context) {
        var fingerInk = false
        var useBrush = false
        private val ink = InProgressStrokesView(context)
        private val renderer = CanvasStrokeRenderer.create()
        private val identity = Matrix()
        private val viewToPaper = Matrix()
        private var strokes = emptyList<Stroke>()
        private val finishing = mutableSetOf<InProgressStrokeId>()
        val hasInk: Boolean get() = strokes.isNotEmpty() || finishing.isNotEmpty()
        private var readable = false
        private val guide = Paint().apply { color = 0xffe0d7c6.toInt() }
        private var drawing: Drawing? = null
        private var scrolling: Scrolling? = null
        private var offset = 0f
        private var sheetHeight = 0f

        init {
            setWillNotDraw(false)
            setBackgroundColor(0xfff8f3e8.toInt())
            contentDescription = context.getString(R.string.writing_paper)
            addView(ink, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            try {
                strokes = entry.read()
                readable = true
                undo.isEnabled = strokes.isNotEmpty()
            } catch (error: Exception) {
                showEntryError(R.string.ink_load_failed, error)
            }
            ink.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    val completed = strokes.filterKeys(finishing::remove)
                    if (completed.isNotEmpty()) save(this@Paper.strokes + completed.values)
                    ink.removeFinishedStrokes(strokes.keys)
                }
            })
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            sheetHeight = maxOf(sheetHeight, height * 2f)
            offset = offset.coerceIn(0f, maxOffset())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val spacing = 32f * density
            var y = spacing - offset % spacing
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, guide)
                y += spacing
            }
            val restore = canvas.save()
            canvas.translate(0f, -offset)
            for (stroke in strokes) renderer.draw(canvas, stroke, identity)
            canvas.restoreToCount(restore)
        }

        override fun onInterceptTouchEvent(event: MotionEvent) = true

        @SuppressLint("ClickableViewAccessibility") // Ink uses pointer positions and has no click action.
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!readable) return false
            val index = event.actionIndex
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val pointer = event.getPointerId(index)
                    val tool = event.getToolType(index)
                    val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS
                    val first = event.actionMasked == MotionEvent.ACTION_DOWN
                    if (first) cancelGesture()
                    if (drawing == null && (stylus || (first && fingerInk && tool == MotionEvent.TOOL_TYPE_FINGER))) {
                        cancelGesture()
                        requestUnbufferedDispatch(event)
                        viewToPaper.setTranslate(0f, offset)
                        val brush = if (useBrush) brushes.softBrush else brushes.pen
                        drawing = Drawing(pointer, ink.startStroke(event, pointer, brush, viewToPaper))
                    } else if (first && tool == MotionEvent.TOOL_TYPE_FINGER) {
                        scrolling = Scrolling(pointer, event.getY(index), offset)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    drawing?.let { stroke ->
                        if (event.findPointerIndex(stroke.pointerId) != -1) {
                            ink.addToStroke(event, stroke.pointerId, stroke.strokeId, null)
                        }
                    }
                    scrolling?.let { scroll ->
                        val pointerIndex = event.findPointerIndex(scroll.pointerId)
                        if (pointerIndex != -1) {
                            offset = (scroll.startOffset + scroll.downY - event.getY(pointerIndex))
                                .coerceIn(0f, maxOffset())
                            invalidate()
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val pointer = event.getPointerId(index)
                    val canceled = Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0
                    drawing?.takeIf { it.pointerId == pointer }?.let { stroke ->
                        drawing = null
                        if (canceled) {
                            ink.cancelStroke(stroke.strokeId, event)
                        } else {
                            finishing.add(stroke.strokeId)
                            ink.finishStroke(event, pointer, stroke.strokeId)
                        }
                    }
                    if (scrolling?.pointerId == pointer) {
                        if (canceled) cancelGesture() else scrolling = null
                    }
                }

                MotionEvent.ACTION_CANCEL -> cancelGesture()
            }
            return true
        }

        fun cancelGesture() {
            drawing?.let { ink.cancelStroke(it.strokeId) }
            drawing = null
            scrolling?.let {
                offset = it.startOffset.coerceIn(0f, maxOffset())
                invalidate()
            }
            scrolling = null
        }

        fun undo() {
            if (strokes.isNotEmpty()) save(strokes.dropLast(1))
        }

        fun clearDay() {
            if (save(emptyList())) {
                cancelGesture()
                finishing.clear()
            }
        }

        private fun save(next: List<Stroke>): Boolean {
            try {
                entry.write(next)
                strokes = next
                undo.isEnabled = strokes.isNotEmpty()
                invalidate()
                return true
            } catch (error: Exception) {
                showEntryError(R.string.ink_save_failed, error)
                return false
            }
        }

        private fun showEntryError(message: Int, error: Exception) {
            Log.e("DiaryInk", context.getString(message), error)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }

        private fun maxOffset() = (sheetHeight - height).coerceAtLeast(0f)
    }
}
