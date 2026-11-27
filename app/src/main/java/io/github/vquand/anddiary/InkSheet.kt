package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // The GPUI host supplies the selected date programmatically.
class InkSheet(context: Context, date: LocalDate) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var currentDate: LocalDate = date
    val selectedDate: LocalDate get() = currentDate
    private var palette = DiarySettings.palette(context)
    private var toolbarMode = DiarySettings.toolbarMode(context)
    private val brushes = DiaryBrushes(context, density)
    private var entry = InkEntry(context, date, brushes)
    private var itemsStore = PaperItemsStore(context, date)
    private var isSwitchingDate = false
    private val paper = Paper()
    private val mediaPlayback = MediaPlayback(context) { paper.invalidate() }
    private var clearDialog: AlertDialog? = null
    private var stickerDialog: Dialog? = null
    private var hanziDialog: Dialog? = null
    private var brushBankDialog: Dialog? = null

    private val penButton = ToolChip(context, R.string.pen, DiaryTool.PEN, ToolIconKind.PEN)
    private val brushButton = ToolChip(context, R.string.brush, DiaryTool.BRUSH, ToolIconKind.BRUSH)
    private val bankButton = ToolChip(context, brushes.bankBrush.nameRes, DiaryTool.BANK, brushes.bankBrush.icon)
    private val eraserButton = ToolChip(context, R.string.eraser, DiaryTool.ERASER, ToolIconKind.ERASER)
    private val selectButton = ActionChip(context, R.string.select, ToolIconKind.SELECT)
    private val settingsChip = SettingsChip(context)
    private val stickerButton = ActionChip(context, R.string.sticker, ToolIconKind.STICKER)
    private val mediaButton = ActionChip(context, R.string.media, ToolIconKind.MEDIA)
    private val hanziButton = ActionChip(context, R.string.hanzi_button, ToolIconKind.HANZI).apply {
        contentDescription = context.getString(R.string.hanzi_description)
    }
    private val fingerButton = ActionChip(context, R.string.finger_ink, ToolIconKind.FINGER)
    private val undoButton = ActionChip(context, R.string.undo, ToolIconKind.UNDO)
    private val redoButton = ActionChip(context, R.string.redo, ToolIconKind.REDO)
    private val moreButton = ActionChip(
        context,
        R.string.more_symbol,
        ToolIconKind.MORE,
        showLabelInExtended = false,
    ).apply {
        contentDescription = context.getString(R.string.more_options)
    }

    private val popoverScrim = View(context).apply {
        visibility = GONE
        isClickable = true
        setOnClickListener { hidePopovers() }
    }
    private val popover = ToolboxPopover(
        context = context,
        brushes = brushes,
        onChanged = { updateToolbar() },
        onOpenBrushBank = { openBrushBankDialog() },
    ).apply {
        visibility = GONE
    }
    private val mediaPopover = MediaPickerPopover(context) { kind ->
        hidePopovers()
        pickMedia(kind)
    }.apply {
        visibility = GONE
    }
    private val deleteDiaryItem = TextView(context).apply {
        setText(R.string.delete_diary)
        setTextColor(palette.danger)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        setOnClickListener {
            hidePopovers()
            confirmClearDay()
        }
    }
    private val moreMenu = LinearLayout(context).apply {
        orientation = VERTICAL
        isClickable = true
        visibility = GONE
        elevation = 8f * density
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = GradientDrawable().apply {
            setColor(palette.paper)
            setStroke(dp(1), palette.rule)
            cornerRadius = 10f * density
        }
        addView(deleteDiaryItem, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private lateinit var toolbarScroll: HorizontalScrollView
    private lateinit var toolbarRule: View

    init {
        orientation = VERTICAL

        penButton.setOnClickListener { selectTool(DiaryTool.PEN) }
        brushButton.setOnClickListener { selectTool(DiaryTool.BRUSH) }
        bankButton.setOnClickListener { selectTool(DiaryTool.BANK) }
        eraserButton.setOnClickListener { selectTool(DiaryTool.ERASER) }
        selectButton.setOnClickListener { selectTool(DiaryTool.SELECT) }
        settingsChip.setOnClickListener { togglePopover(popover) }
        stickerButton.setOnClickListener { openStickerDialog() }
        mediaButton.setOnClickListener { togglePopover(mediaPopover) }
        hanziButton.setOnClickListener {
            hidePopovers()
            paper.addDailyHanzi()
        }

        fingerButton.setOnClickListener {
            paper.cancelGesture()
            paper.fingerInk = !paper.fingerInk
            updateToolbar()
        }
        undoButton.setOnClickListener {
            hidePopovers()
            paper.undo()
        }
        redoButton.setOnClickListener {
            hidePopovers()
            paper.redo()
        }
        moreButton.setOnClickListener { togglePopover(moreMenu) }

        val barContent = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minimumHeight = dp(52)

            addView(penButton, chipParams(marginEndDp = 6))
            addView(brushButton, chipParams(marginEndDp = 6))
            addView(bankButton, chipParams(marginEndDp = 6))
            addView(eraserButton, chipParams(marginEndDp = 6))
            addView(selectButton, chipParams(marginEndDp = 6))
            addView(settingsChip, chipParams(marginEndDp = 8))
            addView(stickerButton, chipParams(marginEndDp = 6))
            addView(mediaButton, chipParams(marginEndDp = 6))
            addView(hanziButton, chipParams(marginEndDp = 10))
            addView(View(context), LayoutParams(0, 1, 1f))
            addView(fingerButton, chipParams(marginEndDp = 8))
            addView(undoButton, chipParams(marginEndDp = 6))
            addView(redoButton, chipParams(marginEndDp = 6))
            addView(moreButton, chipParams(marginEndDp = 0))
        }

        toolbarScroll = HorizontalScrollView(context).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(palette.wash)
            addView(barContent, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        toolbarRule = View(context).apply { setBackgroundColor(palette.rule) }

        val contentArea = FrameLayout(context).apply {
            addView(paper, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(popoverScrim, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(
                popover,
                FrameLayout.LayoutParams(dp(280), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(10), dp(8), dp(10), dp(10))
                },
            )
            addView(
                mediaPopover,
                FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(10), dp(8), dp(10), dp(10))
                },
            )
            addView(
                moreMenu,
                FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    setMargins(dp(10), dp(8), dp(10), dp(10))
                },
            )
        }

        addView(toolbarScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(toolbarRule, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        addView(contentArea, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        updateToolbar()
        updateHistoryButtons()
    }

    fun cancelGesture() {
        paper.cancelGesture()
    }

    fun refreshAppearance() {
        palette = DiarySettings.palette(context)
        toolbarMode = DiarySettings.toolbarMode(context)
        toolbarScroll.setBackgroundColor(palette.wash)
        toolbarRule.setBackgroundColor(palette.rule)
        deleteDiaryItem.setTextColor(palette.danger)
        moreMenu.background = GradientDrawable().apply {
            setColor(palette.paper)
            setStroke(dp(1), palette.rule)
            cornerRadius = 10f * density
        }
        popover.applyPalette(palette)
        mediaPopover.applyPalette(palette)
        updateToolbar()
        updateHistoryButtons()
    }

    fun switchDate(targetDate: LocalDate, animate: Boolean = true, notifyHost: Boolean = true) {
        if (targetDate == currentDate) {
            paper.animate().translationX(0f).alpha(1f).setDuration(140L).start()
            return
        }
        hidePopovers()
        mediaPlayback.release()
        stickerDialog?.dismiss()
        hanziDialog?.dismiss()
        brushBankDialog?.dismiss()
        clearDialog?.dismiss()
        paper.cancelGesture()

        val previousStore = itemsStore
        val previousItems = paper.currentItems()
        val forward = targetDate.isAfter(currentDate)
        val applyNewDate = {
            pruneUnusedMediaAsync(previousStore, previousItems)
            currentDate = targetDate
            entry = InkEntry(context, targetDate, brushes)
            itemsStore = PaperItemsStore(context, targetDate)
            paper.reloadForCurrentDate()
            if (notifyHost) DiaryActivity.notifyDateSelected(targetDate)
        }

        val travel = width.toFloat().coerceAtLeast(320f * density)
        if (!animate || isSwitchingDate) {
            paper.animate().cancel()
            isSwitchingDate = false
            paper.translationX = 0f
            paper.alpha = 1f
            applyNewDate()
            return
        }

        isSwitchingDate = true
        val exitX = if (forward) -travel else travel
        val enterX = -exitX
        paper.animate().cancel()
        paper.animate()
            .translationX(exitX)
            .alpha(0.25f)
            .setDuration(145L)
            .setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction {
                applyNewDate()
                paper.translationX = enterX
                paper.alpha = 0.25f
                paper.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(210L)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .withEndAction { isSwitchingDate = false }
                    .start()
            }
            .start()
    }

    fun confirmClearDay() {
        hidePopovers()
        if (clearDialog != null) return
        if (!paper.hasContent) {
            Toast.makeText(context, R.string.ink_empty, Toast.LENGTH_SHORT).show()
            return
        }
        clearDialog = AlertDialog.Builder(context)
            .setTitle(R.string.delete_diary)
            .setMessage(R.string.clear_day_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_diary) { _, _ -> paper.clearDay() }
            .create().also { dialog ->
                dialog.setOnDismissListener { clearDialog = null }
                dialog.show()
            }
    }

    override fun onDetachedFromWindow() {
        pruneUnusedMediaAsync(itemsStore, paper.currentItems())
        mediaPlayback.release()
        stickerDialog?.dismiss()
        stickerDialog = null
        hanziDialog?.dismiss()
        hanziDialog = null
        brushBankDialog?.dismiss()
        brushBankDialog = null
        clearDialog?.dismiss()
        super.onDetachedFromWindow()
    }

    private fun pruneUnusedMediaAsync(store: PaperItemsStore, activeItems: List<PaperItem>) {
        ioExecutor.execute {
            runCatching { store.pruneUnusedMedia(activeItems) }
        }
    }

    private fun openHanziPractice(item: PaperItem) {
        val word = itemsStore.hanziCatalog.find(item.ref) ?: return
        hidePopovers()
        paper.cancelGesture()
        hanziDialog?.dismiss()
        hanziDialog = HanziPracticeDialog.show(
            context = context,
            word = word,
            onCompleted = { score -> paper.markHanziCompleted(item.id, score) },
            onDismiss = {
                hanziDialog = null
                updateToolbar()
            },
        )
        updateToolbar()
    }

    private fun openStickerDialog() {
        hidePopovers()
        paper.cancelGesture()
        if (stickerDialog?.isShowing == true) return
        val picker = StickerPickerPopover(context, itemsStore) { spec ->
            stickerDialog?.dismiss()
            paper.addSticker(spec)
        }
        stickerDialog = Dialog(context).apply {
            setContentView(picker, ViewGroup.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT))
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setCanceledOnTouchOutside(true)
            setOnDismissListener {
                picker.hideKeyboard()
                stickerDialog = null
                updateToolbar()
            }
            show()
        }
        updateToolbar()
    }

    private fun openBrushBankDialog() {
        paper.cancelGesture()
        if (brushBankDialog?.isShowing == true) return
        val picker = BrushBankPopover(
            context = context,
            brushes = brushes,
            onSelect = {
                updateToolbar()
                if (popover.visibility == VISIBLE) {
                    popover.refresh()
                }
            },
            onClose = { brushBankDialog?.dismiss() },
        )
        brushBankDialog = Dialog(context).apply {
            setContentView(picker, ViewGroup.LayoutParams(picker.preferredDialogWidthPx(), ViewGroup.LayoutParams.WRAP_CONTENT))
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setCanceledOnTouchOutside(true)
            setOnDismissListener {
                brushBankDialog = null
                updateToolbar()
            }
            show()
        }
    }

    private fun pickMedia(kind: PaperItemKind) {
        val activity = context as? DiaryActivity ?: return
        val mime = when (kind) {
            PaperItemKind.PHOTO -> "image/*"
            PaperItemKind.VIDEO -> "video/*"
            PaperItemKind.AUDIO -> "audio/*"
            PaperItemKind.STICKER, PaperItemKind.HANZI -> return
        }
        activity.launchMediaPicker(mime) { uri ->
            if (uri != null) importMediaAsync(uri, kind)
        }
    }

    private fun importMediaAsync(uri: Uri, kind: PaperItemKind) {
        val targetDate = currentDate
        val targetStore = itemsStore
        val centerX = paper.viewportCenterX()
        val centerY = paper.viewportCenterY()
        val maxWidth = paper.maxNewItemWidth()
        ioExecutor.execute {
            try {
                val item = targetStore.importMedia(uri, kind, centerX, centerY, maxWidth, density)
                targetStore.loadBitmap(item)
                post {
                    if (currentDate == targetDate) {
                        paper.addImportedItem(item)
                        targetStore.clearPendingImport(item.ref)
                    } else {
                        // User switched days while importing; persist to the original date safely.
                        ioExecutor.execute {
                            try {
                                runCatching {
                                    val existing = targetStore.read()
                                    targetStore.write(existing + item)
                                }
                            } finally {
                                targetStore.clearPendingImport(item.ref)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e("DiaryMedia", "Could not import media", error)
                post { Toast.makeText(context, R.string.media_import_failed, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun playVideo(item: PaperItem) =
        mediaPlayback.playVideo(item.label, itemsStore.mediaFile(item))

    private fun toggleAudio(item: PaperItem) =
        mediaPlayback.toggleAudio(item.id, itemsStore.mediaFile(item))

    private fun stopAudio() = mediaPlayback.stopAudio()

    private fun selectTool(tool: DiaryTool) {
        paper.cancelGesture()
        if (paper.activeTool == tool) {
            if (tool != DiaryTool.SELECT) togglePopover(popover) else hidePopovers()
            return
        }
        paper.activeTool = tool
        if (tool != DiaryTool.SELECT) paper.clearSelection()
        if (popover.visibility == VISIBLE) {
            if (tool == DiaryTool.SELECT) hidePopovers() else popover.bind(tool)
        } else {
            hidePopovers()
        }
        updateToolbar()
    }

    private fun togglePopover(target: View) {
        if (target.visibility == VISIBLE) {
            hidePopovers()
            return
        }
        paper.cancelGesture()
        popover.visibility = GONE
        mediaPopover.visibility = GONE
        moreMenu.visibility = GONE

        if (target === popover) {
            if (paper.activeTool == DiaryTool.SELECT) {
                paper.activeTool = DiaryTool.PEN
                paper.clearSelection()
            }
            popover.bind(paper.activeTool)
        }
        popoverScrim.visibility = VISIBLE
        target.visibility = VISIBLE
        updateToolbar()
    }

    private fun hidePopovers() {
        val anyOpen = popover.visibility == VISIBLE ||
            mediaPopover.visibility == VISIBLE ||
            moreMenu.visibility == VISIBLE
        if (!anyOpen) return
        popover.visibility = GONE
        mediaPopover.visibility = GONE
        moreMenu.visibility = GONE
        popoverScrim.visibility = GONE
        updateToolbar()
    }

    private fun updateToolbar() {
        val active = paper.activeTool
        penButton.update(selected = active == DiaryTool.PEN, indicatorColor = brushes.penColor, palette = palette, toolbarMode = toolbarMode)
        brushButton.update(selected = active == DiaryTool.BRUSH, indicatorColor = brushes.brushColor, palette = palette, toolbarMode = toolbarMode)
        bankButton.setBrushDisplay(brushes.bankBrush.nameRes, brushes.bankBrush.icon)
        bankButton.update(selected = active == DiaryTool.BANK, indicatorColor = brushes.bankColor, palette = palette, toolbarMode = toolbarMode)
        eraserButton.update(selected = active == DiaryTool.ERASER, indicatorColor = palette.muted, palette = palette, toolbarMode = toolbarMode)
        selectButton.update(active = active == DiaryTool.SELECT, enabled = true, palette = palette, toolbarMode = toolbarMode)
        settingsChip.visibility = if (active == DiaryTool.SELECT) GONE else VISIBLE
        if (active != DiaryTool.SELECT) {
            settingsChip.update(
                tool = active,
                color = if (active == DiaryTool.ERASER) palette.muted else brushes.colorFor(active),
                sizeText = ToolboxPopover.formatSize(brushes.sizeDpFor(active)),
                expanded = popover.visibility == VISIBLE,
                palette = palette,
                toolbarMode = toolbarMode,
                bankIcon = brushes.bankBrush.icon,
            )
        }
        stickerButton.update(active = stickerDialog?.isShowing == true, enabled = true, palette = palette, toolbarMode = toolbarMode)
        mediaButton.update(active = mediaPopover.visibility == VISIBLE, enabled = true, palette = palette, toolbarMode = toolbarMode)
        hanziButton.update(active = hanziDialog?.isShowing == true, enabled = true, palette = palette, toolbarMode = toolbarMode)
        fingerButton.update(active = paper.fingerInk, enabled = true, palette = palette, toolbarMode = toolbarMode)
        moreButton.update(active = moreMenu.visibility == VISIBLE, enabled = true, palette = palette, toolbarMode = toolbarMode)
    }

    private fun updateHistoryButtons() {
        undoButton.update(active = false, enabled = paper.canUndo, palette = palette, toolbarMode = toolbarMode)
        redoButton.update(active = false, enabled = paper.canRedo, palette = palette, toolbarMode = toolbarMode)
    }

    private fun chipParams(marginEndDp: Int) = LayoutParams(LayoutParams.WRAP_CONTENT, dp(38)).apply {
        marginEnd = dp(marginEndDp)
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    private data class HistorySnapshot(val strokes: List<Stroke>, val items: List<PaperItem>)
    private data class Drawing(val pointerId: Int, val strokeId: InProgressStrokeId)
    private data class Scrolling(val pointerId: Int, val downY: Float, val startOffset: Float)
    private data class Erasing(
        val pointerId: Int,
        var lastX: Float,
        var lastY: Float,
        val preEraseStrokes: List<Stroke>,
        var erasedAny: Boolean = false,
    )
    private enum class DragMode { MOVE, RESIZE }
    private data class ItemDrag(
        val pointerId: Int,
        val itemId: String,
        val mode: DragMode,
        val downX: Float,
        val downY: Float,
        val startItem: PaperItem,
        val preItems: List<PaperItem>,
        var moved: Boolean = false,
    )
    private data class MediaTap(val pointerId: Int, val item: PaperItem, val downX: Float, val downY: Float)

    private inner class Paper : FrameLayout(context) {
        var fingerInk = false
        var activeTool = DiaryTool.PEN
        private val ink = InProgressStrokesView(context).apply {
            textureBitmapStore = brushes.textureStore
        }
        private val renderer = CanvasStrokeRenderer.create(brushes.textureStore)
        private val identity = Matrix()
        private val viewToPaper = Matrix()
        private var strokes = emptyList<Stroke>()
        private var items = emptyList<PaperItem>()
        private var selectedItemId: String? = null
        private val undoStack = ArrayDeque<HistorySnapshot>()
        private val redoStack = ArrayDeque<HistorySnapshot>()
        private val finishing = mutableMapOf<InProgressStrokeId, InkEntry>()
        val hasContent: Boolean get() = strokes.isNotEmpty() || finishing.values.any { it === entry } || items.isNotEmpty()
        val canUndo: Boolean get() = undoStack.isNotEmpty()
        val canRedo: Boolean get() = redoStack.isNotEmpty()
        private var readable = false

        private val paperPalette = DiaryPalette.LIGHT
        private val guide = Paint().apply { color = paperPalette.rule }
        private val itemRenderer = PaperItemRenderer(density, paperPalette)
        private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paperPalette.eraserFill
            style = Paint.Style.FILL
        }
        private val eraserRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paperPalette.muted
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val loadingBitmaps = mutableSetOf<String>()

        private var drawing: Drawing? = null
        private var erasing: Erasing? = null
        private var scrolling: Scrolling? = null
        private var itemDrag: ItemDrag? = null
        private var mediaTap: MediaTap? = null
        private var offset = 0f
        private var sheetHeight = 0f

        init {
            setWillNotDraw(false)
            setBackgroundColor(paperPalette.paper)
            contentDescription = context.getString(R.string.writing_paper)
            addView(ink, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            try {
                awaitPendingWrite(entry.date)
                strokes = entry.read()
                items = itemsStore.read()
                readable = true
                preloadMediaBitmaps()
            } catch (error: Exception) {
                showEntryError(R.string.ink_load_failed, error)
            }
            ink.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    val currentCompleted = mutableListOf<Stroke>()
                    val byPreviousEntry = mutableMapOf<InkEntry, MutableList<Stroke>>()
                    for ((id, stroke) in strokes) {
                        val owner = finishing.remove(id) ?: continue
                        if (owner === entry) {
                            currentCompleted.add(stroke)
                        } else {
                            byPreviousEntry.getOrPut(owner) { mutableListOf() }.add(stroke)
                        }
                    }
                    if (currentCompleted.isNotEmpty()) {
                        val previous = HistorySnapshot(this@Paper.strokes, items)
                        commitChange(previous, this@Paper.strokes + currentCompleted, items)
                    }
                    for ((oldEntry, oldStrokes) in byPreviousEntry) {
                        val future = ioExecutor.submit {
                            runCatching {
                                oldEntry.write(oldEntry.read() + oldStrokes)
                            }
                        }
                        pendingEntryWrites[oldEntry.date] = future
                    }
                    ink.removeFinishedStrokes(strokes.keys)
                }
            })
        }

        fun currentItems(): List<PaperItem> = items

        fun reloadForCurrentDate() {
            cancelGesture()
            loadingBitmaps.clear()
            selectedItemId = null
            undoStack.clear()
            redoStack.clear()
            offset = 0f
            readable = false
            try {
                awaitPendingWrite(entry.date)
                strokes = entry.read()
                items = itemsStore.read()
                readable = true
                preloadMediaBitmaps()
            } catch (error: Exception) {
                strokes = emptyList()
                items = emptyList()
                showEntryError(R.string.ink_load_failed, error)
            }
            updateHistoryButtons()
            invalidate()
        }

        private fun preloadMediaBitmaps() {
            val store = itemsStore
            val pending = items.filter {
                (it.kind == PaperItemKind.PHOTO || it.kind == PaperItemKind.VIDEO) && !store.hasBitmapEntry(it)
            }
            if (pending.isEmpty()) return
            for (item in pending) {
                requestItemBitmap(item, store)
            }
        }

        private fun requestItemBitmap(item: PaperItem, store: PaperItemsStore) {
            val key = "${item.kind.wire}:${item.ref}"
            if (!loadingBitmaps.add(key)) return
            ioExecutor.execute {
                store.loadBitmap(item)
                post {
                    loadingBitmaps.remove(key)
                    if (itemsStore === store) invalidate()
                }
            }
        }

        fun viewportCenterX(): Float = (width / 2f).coerceAtLeast(120f * density)
        fun viewportCenterY(): Float = offset + (height / 2f).coerceAtLeast(160f * density)
        fun maxNewItemWidth(): Float = (width * 0.58f).coerceAtLeast(160f * density)

        fun clearSelection() {
            if (selectedItemId != null) {
                selectedItemId = null
                invalidate()
            }
        }

        fun addSticker(spec: StickerSpec) {
            val item = itemsStore.createSticker(spec, viewportCenterX(), viewportCenterY(), density)
            addImportedItem(item)
        }

        fun addDailyHanzi() {
            val words = itemsStore.hanziCatalog.words
            if (words.isEmpty()) return
            val used = items.filter { it.kind == PaperItemKind.HANZI }.mapTo(mutableSetOf()) { it.ref }
            val nextWord = words.firstOrNull { it.char !in used }
                ?: words[used.size % words.size]
            val item = itemsStore.createHanzi(nextWord, viewportCenterX(), viewportCenterY(), maxNewItemWidth(), density)
            addImportedItem(item)
        }

        fun markHanziCompleted(itemId: String, score: Int) {
            val updated = items.map { item ->
                if (item.id == itemId) item.copy(score = maxOf(item.score, score.coerceIn(1, 100))) else item
            }
            if (updated != items) {
                save(strokes, updated)
            }
        }

        fun addImportedItem(item: PaperItem) {
            val previous = HistorySnapshot(strokes, items)
            if (commitChange(previous, strokes, items + item)) {
                selectedItemId = item.id
                activeTool = DiaryTool.SELECT
                updateToolbar()
                invalidate()
            }
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
            for (item in items) {
                itemRenderer.drawItem(canvas, item, itemsStore, mediaPlayback.playingAudioId, ::requestItemBitmap)
            }
            for (stroke in strokes) renderer.draw(canvas, stroke, identity)
            items.firstOrNull { it.id == selectedItemId }?.let { itemRenderer.drawSelectionHandles(canvas, it) }
            canvas.restoreToCount(restore)

            erasing?.let { active ->
                val cursorY = active.lastY - offset
                val radius = brushes.eraserRadiusPx
                canvas.drawCircle(active.lastX, cursorY, radius, eraserFill)
                canvas.drawCircle(active.lastX, cursorY, radius, eraserRing)
            }
        }

        override fun onInterceptTouchEvent(event: MotionEvent) = true

        @SuppressLint("ClickableViewAccessibility") // Ink uses pointer positions and has no click action.
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!readable) return false
            val index = event.actionIndex
            val action = when (event.actionMasked) {
                SAMSUNG_ACTION_PEN_DOWN -> MotionEvent.ACTION_DOWN
                SAMSUNG_ACTION_PEN_MOVE -> MotionEvent.ACTION_MOVE
                SAMSUNG_ACTION_PEN_UP -> MotionEvent.ACTION_UP
                SAMSUNG_ACTION_PEN_CANCEL -> MotionEvent.ACTION_CANCEL
                else -> event.actionMasked
            }
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val pointer = event.getPointerId(index)
                    val tool = event.getToolType(index)
                    val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS
                    val stylusEraser = event.actionMasked == SAMSUNG_ACTION_PEN_DOWN || isStylusEraserButton(event, tool)
                    val first = action == MotionEvent.ACTION_DOWN
                    val paperX = event.getX(index)
                    val paperY = event.getY(index) + offset
                    if (first) cancelGesture()

                    if (activeTool == DiaryTool.SELECT && first && !stylusEraser) {
                        if (handleSelectDown(pointer, paperX, paperY)) return true
                        if (tool == MotionEvent.TOOL_TYPE_FINGER) {
                            scrolling = Scrolling(pointer, event.getY(index), offset)
                        }
                        return true
                    }

                    val fingerAllowed = first && fingerInk && tool == MotionEvent.TOOL_TYPE_FINGER
                    val shouldErase = stylusEraser || (activeTool == DiaryTool.ERASER && (stylus || fingerAllowed))
                    val shouldDraw = !shouldErase && activeTool != DiaryTool.SELECT && (stylus || fingerAllowed)

                    if (drawing == null && erasing == null && shouldErase) {
                        cancelGesture()
                        erasing = Erasing(pointer, paperX, paperY, strokes)
                        eraseSegment(paperX, paperY, paperX, paperY)
                        invalidate()
                    } else if (drawing == null && erasing == null && shouldDraw) {
                        cancelGesture()
                        requestUnbufferedDispatch(event)
                        viewToPaper.setTranslate(0f, offset)
                        val brush = brushes.brushFor(activeTool)
                        drawing = Drawing(pointer, ink.startStroke(event, pointer, brush, viewToPaper))
                    } else if (first && tool == MotionEvent.TOOL_TYPE_FINGER) {
                        val playable = findPlayableHit(paperX, paperY)
                        if (playable != null) {
                            mediaTap = MediaTap(pointer, playable, event.getX(index), event.getY(index))
                        }
                        scrolling = Scrolling(pointer, event.getY(index), offset)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    itemDrag?.let { drag ->
                        val pointerIndex = event.findPointerIndex(drag.pointerId)
                        if (pointerIndex != -1) {
                            val cx = event.getX(pointerIndex)
                            val cy = event.getY(pointerIndex) + offset
                            val dx = cx - drag.downX
                            val dy = cy - drag.downY
                            if (!drag.moved && hypot(dx.toDouble(), dy.toDouble()) > 4f * density) {
                                drag.moved = true
                            }
                            if (drag.moved) applyItemDrag(drag, dx, dy)
                        }
                    }
                    drawing?.let { stroke ->
                        val pointerIndex = event.findPointerIndex(stroke.pointerId)
                        if (pointerIndex != -1) {
                            if (isStylusEraserButton(event, event.getToolType(pointerIndex))) {
                                ink.cancelStroke(stroke.strokeId, event)
                                drawing = null
                                val cx = event.getX(pointerIndex)
                                val cy = event.getY(pointerIndex) + offset
                                erasing = Erasing(stroke.pointerId, cx, cy, strokes)
                                eraseSegment(cx, cy, cx, cy)
                                invalidate()
                            } else {
                                ink.addToStroke(event, stroke.pointerId, stroke.strokeId, null)
                            }
                        }
                    }
                    erasing?.let { active ->
                        val pointerIndex = event.findPointerIndex(active.pointerId)
                        if (pointerIndex != -1) {
                            for (h in 0 until event.historySize) {
                                val hx = event.getHistoricalX(pointerIndex, h)
                                val hy = event.getHistoricalY(pointerIndex, h) + offset
                                eraseSegment(active.lastX, active.lastY, hx, hy)
                                active.lastX = hx
                                active.lastY = hy
                            }
                            val cx = event.getX(pointerIndex)
                            val cy = event.getY(pointerIndex) + offset
                            eraseSegment(active.lastX, active.lastY, cx, cy)
                            active.lastX = cx
                            active.lastY = cy
                            invalidate()
                        }
                    }
                    scrolling?.let { scroll ->
                        val pointerIndex = event.findPointerIndex(scroll.pointerId)
                        if (pointerIndex != -1) {
                            val moveY = event.getY(pointerIndex)
                            if (abs(scroll.downY - moveY) > 6f * density) {
                                mediaTap = null
                            }
                            offset = (scroll.startOffset + scroll.downY - moveY)
                                .coerceIn(0f, maxOffset())
                            invalidate()
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val pointer = event.getPointerId(index)
                    val canceled = Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0

                    itemDrag?.takeIf { it.pointerId == pointer }?.let { drag ->
                        itemDrag = null
                        if (canceled) {
                            items = drag.preItems
                        } else if (drag.moved) {
                            val previous = HistorySnapshot(strokes, drag.preItems)
                            if (!commitChange(previous, strokes, items)) {
                                items = drag.preItems
                            }
                        } else if (drag.mode == DragMode.MOVE) {
                            when (drag.startItem.kind) {
                                PaperItemKind.VIDEO -> playVideo(drag.startItem)
                                PaperItemKind.AUDIO -> toggleAudio(drag.startItem)
                                PaperItemKind.HANZI -> openHanziPractice(drag.startItem)
                                else -> Unit
                            }
                        }
                        invalidate()
                    }

                    drawing?.takeIf { it.pointerId == pointer }?.let { stroke ->
                        drawing = null
                        if (canceled) {
                            ink.cancelStroke(stroke.strokeId, event)
                        } else {
                            finishing[stroke.strokeId] = entry
                            ink.finishStroke(event, pointer, stroke.strokeId)
                        }
                    }
                    erasing?.takeIf { it.pointerId == pointer }?.let { active ->
                        erasing = null
                        if (canceled) {
                            strokes = active.preEraseStrokes
                        } else if (active.erasedAny) {
                            val previous = HistorySnapshot(active.preEraseStrokes, items)
                            if (!commitChange(previous, strokes, items)) {
                                strokes = active.preEraseStrokes
                            }
                        }
                        invalidate()
                    }
                    mediaTap?.takeIf { it.pointerId == pointer }?.let { tap ->
                        mediaTap = null
                        if (!canceled) {
                            val dx = event.getX(index) - tap.downX
                            val dy = event.getY(index) - tap.downY
                            if (hypot(dx.toDouble(), dy.toDouble()) <= 8f * density) {
                                when (tap.item.kind) {
                                    PaperItemKind.VIDEO -> playVideo(tap.item)
                                    PaperItemKind.AUDIO -> toggleAudio(tap.item)
                                    PaperItemKind.HANZI -> openHanziPractice(tap.item)
                                    else -> Unit
                                }
                            }
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

        private fun isStylusEraserButton(event: MotionEvent, tool: Int): Boolean =
            tool == MotionEvent.TOOL_TYPE_ERASER ||
                (tool == MotionEvent.TOOL_TYPE_STYLUS &&
                    (event.buttonState and (
                        MotionEvent.BUTTON_STYLUS_PRIMARY or
                            MotionEvent.BUTTON_STYLUS_SECONDARY or
                            MotionEvent.BUTTON_SECONDARY
                        )) != 0)

        private fun handleSelectDown(pointer: Int, paperX: Float, paperY: Float): Boolean {
            val handleHitR = 22f * density
            val pad = 4f * density
            val selected = items.firstOrNull { it.id == selectedItemId }
            if (selected != null) {
                val right = selected.x + selected.width + pad
                val top = selected.y - pad
                val bottom = selected.y + selected.height + pad

                // Delete handle
                if (hypot((paperX - right).toDouble(), (paperY - top).toDouble()) <= handleHitR) {
                    if (mediaPlayback.playingAudioId == selected.id) stopAudio()
                    val previous = HistorySnapshot(strokes, items)
                    val remaining = items.filterNot { it.id == selected.id }
                    if (commitChange(previous, strokes, remaining)) {
                        selectedItemId = null
                    }
                    invalidate()
                    return true
                }

                // Resize handle
                if (selected.kind != PaperItemKind.AUDIO &&
                    hypot((paperX - right).toDouble(), (paperY - bottom).toDouble()) <= handleHitR
                ) {
                    itemDrag = ItemDrag(pointer, selected.id, DragMode.RESIZE, paperX, paperY, selected, items)
                    return true
                }
            }

            // Hit-test items top-most first
            val hit = items.lastOrNull { item ->
                paperX >= item.x && paperX <= item.x + item.width &&
                    paperY >= item.y && paperY <= item.y + item.height
            }
            if (hit != null) {
                selectedItemId = hit.id
                itemDrag = ItemDrag(pointer, hit.id, DragMode.MOVE, paperX, paperY, hit, items)
                invalidate()
                return true
            }

            clearSelection()
            return false
        }

        private fun findPlayableHit(paperX: Float, paperY: Float): PaperItem? =
            items.lastOrNull { item ->
                when (item.kind) {
                    PaperItemKind.AUDIO -> {
                        val cx = item.x + 26f * density
                        val cy = item.y + item.height / 2f
                        hypot((paperX - cx).toDouble(), (paperY - cy).toDouble()) <= 24f * density
                    }

                    PaperItemKind.VIDEO -> {
                        val cx = item.x + item.width / 2f
                        val cy = item.y + item.height / 2f
                        hypot((paperX - cx).toDouble(), (paperY - cy).toDouble()) <= 28f * density
                    }

                    PaperItemKind.HANZI -> {
                        paperX >= item.x && paperX <= item.x + item.width &&
                            paperY >= item.y && paperY <= item.y + item.height
                    }

                    else -> false
                }
            }

        private fun applyItemDrag(drag: ItemDrag, dx: Float, dy: Float) {
            val base = drag.startItem
            val updated = when (drag.mode) {
                DragMode.MOVE -> {
                    val maxX = (width - base.width).coerceAtLeast(0f)
                    val maxY = (sheetHeight - base.height).coerceAtLeast(0f)
                    base.copy(
                        x = (base.x + dx).coerceIn(0f, maxX),
                        y = (base.y + dy).coerceIn(0f, maxY),
                    )
                }

                DragMode.RESIZE -> {
                    val aspect = if (base.width > 0f) base.height / base.width else 1f
                    val minW = 44f * density
                    val maxW = (width - base.x - 8f * density).coerceAtLeast(minW)
                    val newW = (base.width + dx).coerceIn(minW, maxW)
                    val newH = (newW * aspect).coerceAtLeast(minW * aspect)
                    base.copy(width = newW, height = newH)
                }
            }
            items = drag.preItems.map { if (it.id == drag.itemId) updated else it }
            invalidate()
        }

        fun cancelGesture() {
            drawing?.let { ink.cancelStroke(it.strokeId) }
            drawing = null
            erasing?.let {
                strokes = it.preEraseStrokes
                invalidate()
            }
            erasing = null
            itemDrag?.let {
                items = it.preItems
                invalidate()
            }
            itemDrag = null
            mediaTap = null
            scrolling?.let {
                offset = it.startOffset.coerceIn(0f, maxOffset())
                invalidate()
            }
            scrolling = null
        }

        fun undo() {
            val previous = undoStack.removeLastOrNull() ?: return
            val current = HistorySnapshot(strokes, items)
            if (save(previous.strokes, previous.items)) {
                pushHistory(redoStack, current)
                if (items.none { it.id == selectedItemId }) selectedItemId = null
                updateHistoryButtons()
            } else {
                undoStack.addLast(previous)
            }
        }

        fun redo() {
            val next = redoStack.removeLastOrNull() ?: return
            val current = HistorySnapshot(strokes, items)
            if (save(next.strokes, next.items)) {
                pushHistory(undoStack, current)
                if (items.none { it.id == selectedItemId }) selectedItemId = null
                updateHistoryButtons()
            } else {
                redoStack.addLast(next)
            }
        }

        fun clearDay() {
            stopAudio()
            if (save(emptyList(), emptyList(), allowUnreadable = true)) {
                readable = true
                itemsStore.clearAll()
                cancelGesture()
                finishing.entries.removeAll { it.value === entry }
                selectedItemId = null
                undoStack.clear()
                redoStack.clear()
                updateHistoryButtons()
            }
        }

        private fun eraseSegment(x0: Float, y0: Float, x1: Float, y1: Float) {
            val active = erasing ?: return
            if (strokes.isEmpty()) return
            val radius = brushes.eraserRadiusPx
            val eraserArea = if (x0 == x1 && y0 == y1) {
                ImmutableParallelogram.fromCenterAndDimensions(ImmutableVec(x0, y0), radius * 2f, radius * 2f)
            } else {
                val segment = ImmutableSegment(ImmutableVec(x0, y0), ImmutableVec(x1, y1))
                ImmutableParallelogram.fromSegmentAndPadding(segment, radius)
            }
            val remaining = strokes.filterNot { stroke ->
                eraserArea.intersects(stroke.shape, AffineTransform.IDENTITY)
            }
            if (remaining.size != strokes.size) {
                strokes = remaining
                active.erasedAny = true
            }
        }

        private fun commitChange(previous: HistorySnapshot, nextStrokes: List<Stroke>, nextItems: List<PaperItem>): Boolean {
            if (!save(nextStrokes, nextItems)) return false
            pushHistory(undoStack, previous)
            redoStack.clear()
            updateHistoryButtons()
            return true
        }

        private fun pushHistory(stack: ArrayDeque<HistorySnapshot>, snapshot: HistorySnapshot) {
            if (stack.size >= MAX_HISTORY) stack.removeFirst()
            stack.addLast(snapshot)
        }

        private fun save(
            nextStrokes: List<Stroke>,
            nextItems: List<PaperItem>,
            allowUnreadable: Boolean = false,
        ): Boolean {
            if (!readable && !allowUnreadable) return false
            try {
                entry.write(nextStrokes)
                itemsStore.write(nextItems)
                strokes = nextStrokes
                items = nextItems
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

    private companion object {
        const val MAX_HISTORY = 50
        val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DiaryMediaIO").apply { isDaemon = true }
        }
        val pendingEntryWrites = ConcurrentHashMap<LocalDate, Future<*>>()

        fun awaitPendingWrite(date: LocalDate) {
            val future = pendingEntryWrites.remove(date) ?: return
            runCatching { future.get() }
        }

        // Samsung One UI sends custom MotionEvent actions when the S Pen side button is held.
        const val SAMSUNG_ACTION_PEN_DOWN = 211
        const val SAMSUNG_ACTION_PEN_UP = 212
        const val SAMSUNG_ACTION_PEN_MOVE = 213
        const val SAMSUNG_ACTION_PEN_CANCEL = 214
    }
}
