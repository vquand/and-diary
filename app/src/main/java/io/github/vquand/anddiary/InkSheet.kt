package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import androidx.ink.brush.Brush
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

internal object BackgroundItemSaveLogic {
    fun isSaveStillValid(
        startClearGen: Int,
        currentClearGen: Int,
        expectedToken: Long? = null,
        activeToken: Long? = null,
    ): Boolean =
        startClearGen == currentClearGen &&
            (expectedToken == null || activeToken == expectedToken)

    fun mergeItemIntoList(
        existing: List<PaperItem>,
        existingItemId: String?,
        newItem: PaperItem,
    ): List<PaperItem> = if (existingItemId != null) {
        existing.map { current ->
            if (current.id == existingItemId) {
                current.copy(ref = newItem.ref, label = newItem.label, durationMs = newItem.durationMs)
            } else {
                current
            }
        }
    } else {
        existing + newItem
    }
}

@SuppressLint("ViewConstructor") // The GPUI host supplies the selected date programmatically.
class InkSheet(context: Context, date: LocalDate) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var currentDate: LocalDate = date
    val selectedDate: LocalDate get() = currentDate
    private var palette = DiarySettings.palette(context)
    private var toolbarMode = DiarySettings.toolbarMode(context)
    private var pluginsMask = DiarySettings.pluginsMask(context)
    private val brushes = DiaryBrushes(context, density)
    private var entry = InkEntry(context, date, brushes)
    private var itemsStore = PaperItemsStore(context, date)
    private var isSwitchingDate = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val paper = Paper()
    private val mediaPlayback = MediaPlayback(context) { paper.invalidate() }
    private var clearDialog: AlertDialog? = null
    private var stickerDialog: Dialog? = null
    private var hanziDialog: Dialog? = null
    private var contourDialog: Dialog? = null
    private var brushBankDialog: Dialog? = null
    private var exportDialog: Dialog? = null

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
    private val contourButton = ActionChip(context, R.string.contour_button, ToolIconKind.CONTOUR).apply {
        contentDescription = context.getString(R.string.contour_description)
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
    private val saveSelectionItem = TextView(context).apply {
        setText(R.string.save_selection)
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        visibility = GONE
        setOnClickListener {
            hidePopovers()
            openExportSelectionDialog()
        }
    }
    private val moreMenuDivider = View(context).apply {
        setBackgroundColor(palette.rule)
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
        addView(saveSelectionItem, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(moreMenuDivider, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(dp(10), dp(2), dp(10), dp(2))
        })
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
        contourButton.setOnClickListener {
            openContourEditor(existingItem = null)
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
            addView(hanziButton, chipParams(marginEndDp = 6))
            addView(contourButton, chipParams(marginEndDp = 10))
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
        preloadBrushTexturesAsync()
    }

    fun cancelGesture() {
        paper.cancelGesture()
    }

    fun refreshAppearance() {
        palette = DiarySettings.palette(context)
        toolbarMode = DiarySettings.toolbarMode(context)
        pluginsMask = DiarySettings.pluginsMask(context)
        toolbarScroll.setBackgroundColor(palette.wash)
        toolbarRule.setBackgroundColor(palette.rule)
        saveSelectionItem.setTextColor(palette.text)
        moreMenuDivider.setBackgroundColor(palette.rule)
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
            paper.animate().cancel()
            isSwitchingDate = false
            paper.animate().translationX(0f).alpha(1f).setDuration(140L).start()
            return
        }
        hidePopovers()
        mediaPlayback.release()
        stickerDialog?.dismiss()
        hanziDialog?.dismiss()
        contourDialog?.dismiss()
        brushBankDialog?.dismiss()
        exportDialog?.dismiss()
        clearDialog?.dismiss()
        paper.cancelGesture()

        val forward = targetDate.isAfter(currentDate)
        val applyNewDate = {
            paper.cancelGesture()
            pruneUnusedMediaAsync(itemsStore, paper.currentItems())
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedSheets.add(this)
        paper.syncDiskItemUpdatesIfNeeded()
    }

    override fun onDetachedFromWindow() {
        attachedSheets.remove(this)
        paper.cancelGesture()
        paper.animate().cancel()
        paper.translationX = 0f
        paper.alpha = 1f
        isSwitchingDate = false
        pruneUnusedMediaAsync(itemsStore, paper.currentItems())
        mediaPlayback.release()
        stickerDialog?.dismiss()
        stickerDialog = null
        hanziDialog?.dismiss()
        hanziDialog = null
        contourDialog?.dismiss()
        contourDialog = null
        brushBankDialog?.dismiss()
        brushBankDialog = null
        exportDialog?.dismiss()
        exportDialog = null
        clearDialog?.dismiss()
        super.onDetachedFromWindow()
    }

    private fun pruneUnusedMediaAsync(store: PaperItemsStore, activeItems: List<PaperItem>) {
        ioExecutor.execute {
            runCatching { store.pruneUnusedMedia(activeItems) }
        }
    }

    private fun preloadBrushTexturesAsync() {
        val store = brushes.textureStore
        ioExecutor.execute {
            for (spec in BrushBank.all) {
                spec.textureId?.let { id -> runCatching { store[id] } }
            }
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

    private fun openContourEditor(existingItem: PaperItem?) {
        hidePopovers()
        paper.cancelGesture()
        contourDialog?.dismiss()
        val initialDoc = existingItem?.let { itemsStore.readContour(it) } ?: ContourDocument()
        contourDialog = ContourEditorDialog.show(
            context = context,
            palette = palette,
            initialDocument = initialDoc,
            onConfirm = { doc -> saveContourAsync(existingItem, doc) },
            onDismiss = {
                contourDialog = null
                updateToolbar()
            },
        )
        updateToolbar()
    }

    private fun saveContourAsync(
        existingItem: PaperItem?,
        document: ContourDocument,
        precomputedHeightmap: ContourHeightmap? = null,
        precomputedVoxels: VoxelHeightmap? = null,
    ) {
        val targetDate = currentDate
        val targetStore = itemsStore
        val startGen = clearGenerationOf(targetDate)
        val centerX = paper.viewportCenterX()
        val centerY = paper.viewportCenterY()
        val maxWidth = paper.maxNewItemWidth()
        val existingId = existingItem?.id
        enqueueBackgroundItemSave(
            targetDate = targetDate,
            targetStore = targetStore,
            startGen = startGen,
            existingItemId = existingId,
            failureLogTag = "DiaryContour",
            failureLogMessage = "Could not save contour terrain",
            failureToastRes = R.string.contour_save_failed,
        ) { registerCreated ->
            val item = if (existingItem != null) {
                targetStore.updateContour(existingItem, document)
            } else {
                targetStore.createContour(document, centerX, centerY, maxWidth, density)
            }
            registerCreated(item)
            val hm = precomputedHeightmap ?: ContourTerrainEngine.buildHeightmap(document.strokes)
            val vox = precomputedVoxels ?: if (document.style == ContourRenderStyle.BLOCK) {
                ContourTerrainEngine.quantizeForBlocks(hm)
            } else {
                null
            }
            val rendered = Contour3DRenderer.render(
                document = document,
                widthPx = 800,
                heightPx = 600,
                heightmap = hm,
                voxels = vox,
            )
            targetStore.cacheBitmap(item, rendered)
            item to PreloadedContour(document, hm, vox)
        }
    }

    private fun enqueueBackgroundItemSave(
        targetDate: LocalDate,
        targetStore: PaperItemsStore,
        startGen: Int,
        existingItemId: String?,
        failureLogTag: String,
        failureLogMessage: String,
        failureToastRes: Int,
        work: (registerCreated: (PaperItem) -> Unit) -> Pair<PaperItem, PreloadedContour?>,
    ) {
        val tokenKey = existingItemId?.let { targetDate to it }
        val saveToken = tokenKey?.let { key ->
            val token = nextContourToken.getAndIncrement()
            contourSaveTokens[key] = token
            token
        }
        ioExecutor.execute {
            var createdItem: PaperItem? = null
            try {
                val (item, preloaded) = work { createdItem = it }
                createdItem = item
                if (!BackgroundItemSaveLogic.isSaveStillValid(
                        startClearGen = startGen,
                        currentClearGen = clearGenerationOf(targetDate),
                        expectedToken = saveToken,
                        activeToken = tokenKey?.let { contourSaveTokens[it] },
                    )
                ) {
                    if (tokenKey != null && saveToken != null) {
                        contourSaveTokens.remove(tokenKey, saveToken)
                    }
                    runCatching { targetStore.mediaFile(item).delete() }
                    targetStore.clearPendingImport(item.ref)
                    return@execute
                }
                mainHandler.post {
                    if (tokenKey != null && saveToken != null) {
                        if (!contourSaveTokens.remove(tokenKey, saveToken)) {
                            ioExecutor.execute {
                                runCatching { targetStore.mediaFile(item).delete() }
                                targetStore.clearPendingImport(item.ref)
                            }
                            return@post
                        }
                    }
                    val liveSheet = attachedSheets.firstOrNull { it.isAttachedToWindow && it.currentDate == targetDate }
                    if (existingItemId != null) {
                        paper.clearFinalizingContourPreview(existingItemId)
                        liveSheet?.paper?.clearFinalizingContourPreview(existingItemId)
                    }
                    if (!BackgroundItemSaveLogic.isSaveStillValid(startGen, clearGenerationOf(targetDate))) {
                        ioExecutor.execute {
                            runCatching { targetStore.mediaFile(item).delete() }
                            targetStore.clearPendingImport(item.ref)
                        }
                    } else if (liveSheet != null) {
                        liveSheet.paper.enqueueOrApplyBackgroundCommit(
                            targetStore = targetStore,
                            startGen = startGen,
                            existingItemId = existingItemId,
                            item = item,
                            preloaded = preloaded,
                            failureLogTag = failureLogTag,
                            failureLogMessage = failureLogMessage,
                            failureToastRes = failureToastRes,
                        )
                    } else {
                        enqueueDiskItemWrite(
                            targetDate = targetDate,
                            targetStore = targetStore,
                            startGen = startGen,
                            existingItemId = existingItemId,
                            item = item,
                            failureLogTag = failureLogTag,
                            failureLogMessage = failureLogMessage,
                            failureToastRes = failureToastRes,
                        )
                    }
                }
            } catch (error: Exception) {
                createdItem?.let {
                    runCatching { targetStore.mediaFile(it).delete() }
                    targetStore.clearPendingImport(it.ref)
                }
                Log.e(failureLogTag, failureLogMessage, error)
                mainHandler.post {
                    if (tokenKey != null && saveToken != null && contourSaveTokens.remove(tokenKey, saveToken)) {
                        existingItemId?.let { paper.clearFinalizingContourPreview(it) }
                    }
                    Toast.makeText(context, failureToastRes, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun enqueueDiskItemWrite(
        targetDate: LocalDate,
        targetStore: PaperItemsStore,
        startGen: Int,
        existingItemId: String?,
        item: PaperItem,
        failureLogTag: String,
        failureLogMessage: String,
        failureToastRes: Int,
    ) {
        // Tracked on a dedicated executor so switching back waits only for fast entry I/O.
        val previousWrite = pendingEntryWrites[targetDate]
        pendingEntryWrites[targetDate] = entryWriteExecutor.submit {
            runCatching { previousWrite?.get() }
            writeItemUpdateToDisk(
                targetDate = targetDate,
                targetStore = targetStore,
                startGen = startGen,
                existingItemId = existingItemId,
                item = item,
                failureLogTag = failureLogTag,
                failureLogMessage = failureLogMessage,
                failureToastRes = failureToastRes,
            )
        }
    }

    private fun writeItemUpdateToDisk(
        targetDate: LocalDate,
        targetStore: PaperItemsStore,
        startGen: Int,
        existingItemId: String?,
        item: PaperItem,
        failureLogTag: String,
        failureLogMessage: String,
        failureToastRes: Int,
    ) {
        try {
            if (!BackgroundItemSaveLogic.isSaveStillValid(startGen, clearGenerationOf(targetDate))) {
                runCatching { targetStore.mediaFile(item).delete() }
                return
            }
            val existing = targetStore.read()
            val next = BackgroundItemSaveLogic.mergeItemIntoList(existing, existingItemId, item)
            targetStore.write(next)
            bumpDiskItemRevision(targetDate)
        } catch (error: Exception) {
            runCatching { targetStore.mediaFile(item).delete() }
            Log.e(failureLogTag, failureLogMessage, error)
            mainHandler.post {
                Toast.makeText(context, failureToastRes, Toast.LENGTH_LONG).show()
            }
        } finally {
            targetStore.clearPendingImport(item.ref)
        }
    }

    private fun openExportSelectionDialog() {
        hidePopovers()
        paper.cancelGesture()
        if (exportDialog?.isShowing == true) return
        val snapshot = paper.selectionExportSnapshot() ?: return
        exportDialog = SelectionExporter.showDialog(
            context = context,
            date = currentDate,
            palette = palette,
            onConfirm = { request -> exportSelectionAsync(snapshot, request) },
            onDismiss = { exportDialog = null },
        )
    }

    private fun exportSelectionAsync(snapshot: SelectionExportSnapshot, request: ExportRequest) {
        val targetStore = itemsStore
        val runExport = {
            ioExecutor.execute {
                try {
                    val savedName = SelectionExporter.exportToGallery(
                        context = context,
                        snapshot = snapshot,
                        request = request,
                        itemsStore = targetStore,
                        density = density,
                        paperPalette = DiaryPalette.LIGHT,
                        textureStore = brushes.textureStore,
                    )
                    post {
                        Toast.makeText(
                            context,
                            context.getString(R.string.export_saved, savedName),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } catch (error: Exception) {
                    Log.e("DiaryExport", "Could not export selection", error)
                    post {
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT < 29) {
            val activity = context as? DiaryActivity
            if (activity != null) {
                activity.requestLegacyStoragePermission { granted ->
                    if (granted) {
                        runExport()
                    } else {
                        Toast.makeText(context, R.string.export_permission_denied, Toast.LENGTH_LONG).show()
                    }
                }
                return
            }
        }
        runExport()
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
            PaperItemKind.STICKER, PaperItemKind.HANZI, PaperItemKind.CONTOUR -> return
        }
        activity.launchMediaPicker(mime) { uri ->
            if (uri != null) importMediaAsync(uri, kind)
        }
    }

    private fun importMediaAsync(uri: Uri, kind: PaperItemKind) {
        val targetDate = currentDate
        val targetStore = itemsStore
        val startGen = clearGenerationOf(targetDate)
        val centerX = paper.viewportCenterX()
        val centerY = paper.viewportCenterY()
        val maxWidth = paper.maxNewItemWidth()
        enqueueBackgroundItemSave(
            targetDate = targetDate,
            targetStore = targetStore,
            startGen = startGen,
            existingItemId = null,
            failureLogTag = "DiaryMedia",
            failureLogMessage = "Could not import media",
            failureToastRes = R.string.media_import_failed,
        ) { registerCreated ->
            val item = targetStore.importMedia(uri, kind, centerX, centerY, maxWidth, density)
            registerCreated(item)
            targetStore.loadBitmap(item)
            item to null
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
        } else if (target === moreMenu) {
            val showExport = paper.hasSelection
            saveSelectionItem.visibility = if (showExport) VISIBLE else GONE
            moreMenuDivider.visibility = if (showExport) VISIBLE else GONE
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
        val hanziEnabled = DiarySettings.isPluginEnabled(pluginsMask, DiaryPlugin.HANZI)
        hanziButton.visibility = if (hanziEnabled) VISIBLE else GONE
        if (hanziEnabled) {
            hanziButton.update(active = hanziDialog?.isShowing == true, enabled = true, palette = palette, toolbarMode = toolbarMode)
        }
        val contourEnabled = DiarySettings.isPluginEnabled(pluginsMask, DiaryPlugin.CONTOUR_3D)
        contourButton.visibility = if (contourEnabled) VISIBLE else GONE
        if (contourEnabled) {
            contourButton.update(active = contourDialog?.isShowing == true, enabled = true, palette = palette, toolbarMode = toolbarMode)
        }
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
    private data class PreloadedContour(
        val document: ContourDocument,
        val heightmap: ContourHeightmap,
        val voxels: VoxelHeightmap?,
    )
    private data class PendingBackgroundCommit(
        val targetStore: PaperItemsStore,
        val startGen: Int,
        val existingItemId: String?,
        val item: PaperItem,
        val preloaded: PreloadedContour?,
        val failureLogTag: String,
        val failureLogMessage: String,
        val failureToastRes: Int,
    )
    private data class ContourOrbit(
        val pointerId: Int,
        val itemId: String,
        val startItem: PaperItem,
        val downRawX: Float,
        val downRawY: Float,
        val startYaw: Float,
        val startPitch: Float,
        var currentYaw: Float,
        var currentPitch: Float,
        val document: ContourDocument,
        val heightmap: ContourHeightmap,
        val voxels: VoxelHeightmap?,
        val previewBitmap: Bitmap,
        val previewCanvas: Canvas,
        var dirty: Boolean = false,
        var framePending: Boolean = false,
    )
    private data class MediaTap(val pointerId: Int, val item: PaperItem, val downX: Float, val downY: Float)
    private data class Lassoing(val pointerId: Int, val points: MutableList<Pair<Float, Float>>)
    private data class AreaDrag(
        val pointerId: Int,
        val mode: DragMode,
        val downX: Float,
        val downY: Float,
        val startBounds: RectF,
        val preStrokes: List<Stroke>,
        val preItems: List<PaperItem>,
        var moved: Boolean = false,
    )
    private data class TwoFingerTransform(
        val pointer1Id: Int,
        val pointer2Id: Int,
        val startMidX: Float,
        val startMidY: Float,
        val startAngleRad: Double,
        val startDist: Float,
        val pivotX: Float,
        val pivotY: Float,
        val allowScale: Boolean,
        val allowRotate: Boolean,
        val preStrokes: List<Stroke>,
        val preItems: List<PaperItem>,
        var moved: Boolean = false,
    )
    private data class SelectionTransform(
        val pivotX: Float,
        val pivotY: Float,
        val translateX: Float,
        val translateY: Float,
        val scale: Float,
        val rotationDeg: Float,
    ) {
        fun fillMatrix(out: Matrix) {
            out.reset()
            out.postTranslate(-pivotX, -pivotY)
            out.postScale(scale, scale)
            out.postRotate(rotationDeg)
            out.postTranslate(pivotX + translateX, pivotY + translateY)
        }
    }

    private inner class Paper : FrameLayout(context) {
        var fingerInk = false
        var activeTool = DiaryTool.PEN
        private val ink = InProgressStrokesView(context).apply {
            textureBitmapStore = brushes.textureStore
        }
        private val renderer = CanvasStrokeRenderer.create(brushes.textureStore)
        private val identity = Matrix()
        private val liveMatrix = Matrix()
        private val viewToPaper = Matrix()
        private var strokes = emptyList<Stroke>()
        private var items = emptyList<PaperItem>()
        private var persistedStrokes = emptyList<Stroke>()
        private var persistedItems = emptyList<PaperItem>()
        private var loadedDiskItemRevision = 0L
        private var selectedItemIds: Set<String> = emptySet()
        private var selectedStrokeIndices: Set<Int> = emptySet()
        private var selectionBounds: RectF? = null
        private var liveTransform: SelectionTransform? = null
        private val undoStack = ArrayDeque<HistorySnapshot>()
        private val redoStack = ArrayDeque<HistorySnapshot>()
        private val finishing = mutableMapOf<InProgressStrokeId, InkEntry>()
        val hasContent: Boolean get() = strokes.isNotEmpty() || finishing.values.any { it === entry } || items.isNotEmpty()
        val hasSelection: Boolean get() = selectedItemIds.isNotEmpty() || selectedStrokeIndices.isNotEmpty()
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
        private var contourOrbit: ContourOrbit? = null
        private var pendingOrbitRunnable: Runnable? = null
        private val preloadedContours = mutableMapOf<String, PreloadedContour>()
        private val finalizingContourPreviews = mutableMapOf<String, Bitmap>()
        private val pendingBackgroundCommits = mutableListOf<PendingBackgroundCommit>()
        private val hasActiveItemGesture: Boolean
            get() = contourOrbit != null || itemDrag != null || areaDrag != null || twoFinger != null
        private var areaDrag: AreaDrag? = null
        private var lassoing: Lassoing? = null
        private var twoFinger: TwoFingerTransform? = null
        private var mediaTap: MediaTap? = null
        private var pendingDeselectPointerId: Int? = null
        private var offset = 0f
        private var sheetHeight = 0f

        init {
            setWillNotDraw(false)
            setBackgroundColor(paperPalette.paper)
            contentDescription = context.getString(R.string.writing_paper)
            addView(ink, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            try {
                awaitPendingWrite(entry.date)
                val itemRev = diskItemRevisionOf(entry.date)
                strokes = entry.read()
                items = itemsStore.read()
                persistedStrokes = strokes
                persistedItems = items
                loadedDiskItemRevision = itemRev
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
                        val future = entryWriteExecutor.submit {
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
            preloadedContours.clear()
            finalizingContourPreviews.clear()
            if (pendingBackgroundCommits.isNotEmpty()) {
                val leftovers = pendingBackgroundCommits.toList()
                pendingBackgroundCommits.clear()
                for (commit in leftovers) {
                    enqueueDiskItemWrite(
                        targetDate = commit.targetStore.date,
                        targetStore = commit.targetStore,
                        startGen = commit.startGen,
                        existingItemId = commit.existingItemId,
                        item = commit.item,
                        failureLogTag = commit.failureLogTag,
                        failureLogMessage = commit.failureLogMessage,
                        failureToastRes = commit.failureToastRes,
                    )
                }
            }
            selectedItemIds = emptySet()
            selectedStrokeIndices = emptySet()
            selectionBounds = null
            liveTransform = null
            undoStack.clear()
            redoStack.clear()
            offset = 0f
            readable = false
            try {
                awaitPendingWrite(entry.date)
                val itemRev = diskItemRevisionOf(entry.date)
                strokes = entry.read()
                items = itemsStore.read()
                persistedStrokes = strokes
                persistedItems = items
                loadedDiskItemRevision = itemRev
                readable = true
                preloadMediaBitmaps()
            } catch (error: Exception) {
                strokes = emptyList()
                items = emptyList()
                persistedStrokes = strokes
                persistedItems = items
                loadedDiskItemRevision = diskItemRevisionOf(entry.date)
                showEntryError(R.string.ink_load_failed, error)
            }
            updateHistoryButtons()
            invalidate()
        }

        fun syncDiskItemUpdatesIfNeeded() {
            if (!readable) return
            if (!pendingEntryWrites.containsKey(entry.date) &&
                diskItemRevisionOf(entry.date) == loadedDiskItemRevision
            ) {
                return
            }
            awaitPendingWrite(entry.date)
            val currentRev = diskItemRevisionOf(entry.date)
            if (currentRev == loadedDiskItemRevision) return
            val diskItems = runCatching { itemsStore.read() }.getOrNull() ?: return
            loadedDiskItemRevision = currentRev
            if (diskItems != items) {
                val prevRefs = items.mapTo(mutableSetOf()) { it.ref }
                val nextRefs = diskItems.mapTo(mutableSetOf()) { it.ref }
                (prevRefs - nextRefs).forEach { preloadedContours.remove(it) }
                items = diskItems
                persistedItems = diskItems
                val liveIds = diskItems.mapTo(mutableSetOf()) { it.id }
                selectedItemIds = selectedItemIds.filterTo(mutableSetOf()) { it in liveIds }
                recomputeSelectionBounds()
                undoStack.clear()
                redoStack.clear()
                preloadMediaBitmaps()
                updateHistoryButtons()
                invalidate()
            }
        }

        private fun preloadMediaBitmaps() {
            val store = itemsStore
            val pending = items.filter {
                (it.kind == PaperItemKind.PHOTO ||
                    it.kind == PaperItemKind.VIDEO ||
                    it.kind == PaperItemKind.STICKER ||
                    it.kind == PaperItemKind.CONTOUR) &&
                    !store.hasBitmapEntry(it)
            }
            for (item in pending) {
                requestItemBitmap(item, store)
            }
        }

        // Decoded bitmaps are capped, so only load what is near the viewport; scrolling requests the rest from onDraw.
        private fun isNearViewport(item: PaperItem): Boolean {
            if (height <= 0) return true
            val margin = height.toFloat()
            return item.y + item.height >= offset - margin && item.y <= offset + height + margin
        }

        private fun requestItemBitmap(item: PaperItem, store: PaperItemsStore) {
            if (!isNearViewport(item)) return
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

        fun clearFinalizingContourPreview(itemId: String) {
            if (finalizingContourPreviews.remove(itemId) != null) {
                invalidate()
            }
        }

        private fun orbitBitmapFor(itemId: String): Bitmap? =
            contourOrbit?.takeIf { it.itemId == itemId }?.previewBitmap
                ?: finalizingContourPreviews[itemId]

        fun clearSelection() {
            if (hasSelection || liveTransform != null) {
                selectedItemIds = emptySet()
                selectedStrokeIndices = emptySet()
                selectionBounds = null
                liveTransform = null
                invalidate()
            }
        }

        fun selectionExportSnapshot(): SelectionExportSnapshot? {
            if (!hasSelection) return null
            recomputeSelectionBounds()
            val bounds = selectionBounds ?: return null
            val selectedItems = items.filter { it.id in selectedItemIds }
            val selectedStrokes = strokes.filterIndexed { idx, _ -> idx in selectedStrokeIndices }
            if (selectedItems.isEmpty() && selectedStrokes.isEmpty()) return null
            return SelectionExportSnapshot(
                date = currentDate,
                bounds = RectF(bounds),
                strokes = selectedStrokes,
                items = selectedItems,
            )
        }

        private fun singleSelectedItem(): PaperItem? =
            if (selectedStrokeIndices.isEmpty() && selectedItemIds.size == 1) {
                items.firstOrNull { it.id == selectedItemIds.first() }
            } else {
                null
            }

        private fun selectionAllowsResize(): Boolean =
            selectedStrokeIndices.isNotEmpty() ||
                items.any { it.id in selectedItemIds && it.kind != PaperItemKind.AUDIO }

        private fun selectionAllowsRotate(): Boolean =
            items.none { it.id in selectedItemIds && it.kind == PaperItemKind.CONTOUR }

        private fun recomputeSelectionBounds() {
            if (!hasSelection) {
                selectionBounds = null
                return
            }
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (item in items) {
                if (item.id !in selectedItemIds) continue
                val b = LassoGeometry.rotatedRectBounds(item.x, item.y, item.width, item.height, item.rotation)
                if (b[0] < minX) minX = b[0]
                if (b[1] < minY) minY = b[1]
                if (b[2] > maxX) maxX = b[2]
                if (b[3] > maxY) maxY = b[3]
            }
            for (idx in selectedStrokeIndices) {
                val stroke = strokes.getOrNull(idx) ?: continue
                val box = stroke.shape.computeBoundingBox() ?: continue
                if (box.xMin < minX) minX = box.xMin
                if (box.yMin < minY) minY = box.yMin
                if (box.xMax > maxX) maxX = box.xMax
                if (box.yMax > maxY) maxY = box.yMax
            }
            selectionBounds = if (minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
                RectF(minX, minY, maxX, maxY)
            } else {
                selectedItemIds = emptySet()
                selectedStrokeIndices = emptySet()
                null
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

        fun addImportedItem(item: PaperItem, preloaded: PreloadedContour? = null) {
            val previous = HistorySnapshot(strokes, items)
            if (commitChange(previous, strokes, items + item)) {
                selectedItemIds = setOf(item.id)
                selectedStrokeIndices = emptySet()
                recomputeSelectionBounds()
                activeTool = DiaryTool.SELECT
                updateToolbar()
                if (item.kind == PaperItemKind.CONTOUR) {
                    if (preloaded != null) {
                        preloadedContours[item.ref] = preloaded
                    } else {
                        preloadContourAsync(item)
                    }
                }
                invalidate()
            }
        }

        fun replaceContourContent(
            itemId: String,
            ref: String,
            label: String,
            durationMs: Long,
            preloaded: PreloadedContour? = null,
        ) {
            val current = items.firstOrNull { it.id == itemId } ?: return
            val updatedItem = current.copy(ref = ref, label = label, durationMs = durationMs)
            val previous = HistorySnapshot(strokes, items)
            val updated = items.map { if (it.id == itemId) updatedItem else it }
            if (commitChange(previous, strokes, updated)) {
                preloadedContours.remove(current.ref)
                if (preloaded != null) {
                    preloadedContours[ref] = preloaded
                }
                recomputeSelectionBounds()
                invalidate()
            }
        }

        fun enqueueOrApplyBackgroundCommit(
            targetStore: PaperItemsStore,
            startGen: Int,
            existingItemId: String?,
            item: PaperItem,
            preloaded: PreloadedContour?,
            failureLogTag: String,
            failureLogMessage: String,
            failureToastRes: Int,
        ) {
            val pending = PendingBackgroundCommit(
                targetStore = targetStore,
                startGen = startGen,
                existingItemId = existingItemId,
                item = item,
                preloaded = preloaded,
                failureLogTag = failureLogTag,
                failureLogMessage = failureLogMessage,
                failureToastRes = failureToastRes,
            )
            if (hasActiveItemGesture) {
                pendingBackgroundCommits.add(pending)
            } else {
                applyBackgroundCommit(pending)
            }
        }

        private fun drainPendingBackgroundCommits() {
            if (hasActiveItemGesture || pendingBackgroundCommits.isEmpty()) return
            val queued = pendingBackgroundCommits.toList()
            pendingBackgroundCommits.clear()
            for (commit in queued) {
                applyBackgroundCommit(commit)
            }
        }

        private fun applyBackgroundCommit(commit: PendingBackgroundCommit) {
            try {
                if (commit.existingItemId != null) {
                    replaceContourContent(
                        commit.existingItemId,
                        commit.item.ref,
                        commit.item.label,
                        commit.item.durationMs,
                        commit.preloaded,
                    )
                } else {
                    addImportedItem(commit.item, commit.preloaded)
                }
            } finally {
                commit.targetStore.clearPendingImport(commit.item.ref)
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
            val transform = liveTransform
            if (transform != null && hasSelection) {
                for (item in items) {
                    if (item.id !in selectedItemIds) {
                        itemRenderer.drawItem(canvas, item, itemsStore, mediaPlayback.playingAudioId, orbitBitmapFor(item.id), ::requestItemBitmap)
                    }
                }
                for (i in strokes.indices) {
                    if (i !in selectedStrokeIndices) {
                        renderer.draw(canvas, strokes[i], identity)
                    }
                }
                transform.fillMatrix(liveMatrix)
                val selRestore = canvas.save()
                canvas.concat(liveMatrix)
                for (item in items) {
                    if (item.id in selectedItemIds && (item.kind != PaperItemKind.AUDIO || transform.scale == 1f)) {
                        itemRenderer.drawItem(canvas, item, itemsStore, mediaPlayback.playingAudioId, orbitBitmapFor(item.id), ::requestItemBitmap)
                    }
                }
                for (i in strokes.indices) {
                    if (i in selectedStrokeIndices) {
                        renderer.draw(canvas, strokes[i], identity)
                    }
                }
                singleSelectedItem()?.let { itemRenderer.drawSelectionHandles(canvas, it, showCornerHandles = contourOrbit?.itemId != it.id) }
                    ?: selectionBounds?.let { itemRenderer.drawAreaSelectionHandles(canvas, it, selectionAllowsResize()) }
                canvas.restoreToCount(selRestore)
                // Draw selected AUDIO items without scaling so live preview matches committed size.
                if (transform.scale != 1f) {
                    val pt = FloatArray(2)
                    for (item in items) {
                        if (item.id in selectedItemIds && item.kind == PaperItemKind.AUDIO) {
                            pt[0] = item.x + item.width / 2f
                            pt[1] = item.y + item.height / 2f
                            liveMatrix.mapPoints(pt)
                            val previewItem = item.copy(
                                x = pt[0] - item.width / 2f,
                                y = pt[1] - item.height / 2f,
                                rotation = LassoGeometry.normalizeAngle(item.rotation + transform.rotationDeg),
                            )
                            itemRenderer.drawItem(canvas, previewItem, itemsStore, mediaPlayback.playingAudioId, null, ::requestItemBitmap)
                        }
                    }
                }
            } else {
                for (item in items) {
                    itemRenderer.drawItem(canvas, item, itemsStore, mediaPlayback.playingAudioId, orbitBitmapFor(item.id), ::requestItemBitmap)
                }
                for (stroke in strokes) renderer.draw(canvas, stroke, identity)
                singleSelectedItem()?.let { itemRenderer.drawSelectionHandles(canvas, it, showCornerHandles = contourOrbit?.itemId != it.id) }
                    ?: selectionBounds?.let { itemRenderer.drawAreaSelectionHandles(canvas, it, selectionAllowsResize()) }
            }
            lassoing?.let { active ->
                itemRenderer.drawLassoPath(canvas, active.points)
            }
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
            if (!readable || isSwitchingDate) return false
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

                    // Two-finger rotate & scale on any active selection (single sticker/item or lasso area).
                    if (!first && event.pointerCount == 2) {
                        fun isNonFingerPointer(pointerId: Int): Boolean {
                            val idx = event.findPointerIndex(pointerId)
                            return idx == -1 || event.getToolType(idx) != MotionEvent.TOOL_TYPE_FINGER
                        }
                        val penGestureActive = drawing != null || erasing != null || contourOrbit != null ||
                            (itemDrag?.let { isNonFingerPointer(it.pointerId) } == true) ||
                            (areaDrag?.let { isNonFingerPointer(it.pointerId) } == true) ||
                            (lassoing?.let { it.points.size > 1 || isNonFingerPointer(it.pointerId) } == true)
                        val toolTypes = intArrayOf(event.getToolType(0), event.getToolType(1))
                        if (LassoGeometry.canStartTwoFingerTransform(toolTypes, hasSelection, stylusEraser, penGestureActive)) {
                            startTwoFingerTransform(event)
                            return true
                        }
                    }

                    if (activeTool == DiaryTool.SELECT && first && !stylusEraser) {
                        if (handleSelectDown(pointer, paperX, paperY)) return true
                        if (stylus || fingerInk) {
                            // Defer clearing selection until the lasso actually drags,
                            // so a 2-finger rotate starting outside a small sticker still works.
                            lassoing = Lassoing(pointer, mutableListOf(paperX to paperY))
                            invalidate()
                        } else if (tool == MotionEvent.TOOL_TYPE_FINGER) {
                            if (hasSelection) pendingDeselectPointerId = pointer
                            scrolling = Scrolling(pointer, event.getY(index), offset)
                        }
                        return true
                    }

                    val fingerAllowed = first && fingerInk && tool == MotionEvent.TOOL_TYPE_FINGER
                    val shouldErase = stylusEraser || (activeTool == DiaryTool.ERASER && (stylus || fingerAllowed))
                    val shouldDraw = !shouldErase && activeTool != DiaryTool.SELECT && (stylus || fingerAllowed)

                    if (drawing == null && erasing == null && shouldErase) {
                        cancelGesture()
                        clearSelection()
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
                    twoFinger?.let { tf ->
                        val i1 = event.findPointerIndex(tf.pointer1Id)
                        val i2 = event.findPointerIndex(tf.pointer2Id)
                        if (i1 != -1 && i2 != -1) {
                            val x1 = event.getX(i1)
                            val y1 = event.getY(i1) + offset
                            val x2 = event.getX(i2)
                            val y2 = event.getY(i2) + offset
                            val midX = (x1 + x2) / 2f
                            val midY = (y1 + y2) / 2f
                            val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                            val dist = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
                            val deltaDeg = if (tf.allowRotate) {
                                Math.toDegrees(angle - tf.startAngleRad).toFloat()
                            } else {
                                0f
                            }
                            val scale = if (tf.allowScale && tf.startDist > 20f * density) {
                                (dist / tf.startDist).coerceIn(0.25f, 4f)
                            } else {
                                1f
                            }
                            val tx = midX - tf.startMidX
                            val ty = midY - tf.startMidY
                            liveTransform = SelectionTransform(
                                pivotX = tf.pivotX,
                                pivotY = tf.pivotY,
                                translateX = tx,
                                translateY = ty,
                                scale = scale,
                                rotationDeg = deltaDeg,
                            )
                            if (!tf.moved) {
                                val translatePx = hypot(tx.toDouble(), ty.toDouble()).toFloat()
                                tf.moved = LassoGeometry.isTransformMeaningful(translatePx, scale, deltaDeg, thresholdPx = 4f * density)
                            }
                            invalidate()
                        }
                        return true
                    }
                    contourOrbit?.let { orbit ->
                        val pointerIndex = event.findPointerIndex(orbit.pointerId)
                        if (pointerIndex != -1) {
                            val rawX = event.getX(pointerIndex)
                            val rawY = event.getY(pointerIndex)
                            val dx = rawX - orbit.downRawX
                            val dy = rawY - orbit.downRawY
                            orbit.currentYaw = ContourDocument.normalizeYaw(
                                orbit.startYaw + dx / (140f * density) * PI.toFloat(),
                            )
                            orbit.currentPitch = ContourDocument.clampPitch(
                                orbit.startPitch + dy / (180f * density) * (PI.toFloat() * 0.5f),
                            )
                            orbit.dirty = true
                            if (!orbit.framePending) {
                                orbit.framePending = true
                                postOnAnimation {
                                    orbit.framePending = false
                                    if (contourOrbit === orbit && orbit.dirty) {
                                        orbit.dirty = false
                                        renderOrbitPreview(orbit, gridStep = 2)
                                        invalidate()
                                    }
                                }
                            }
                        }
                        return true
                    }
                    lassoing?.let { active ->
                        val pointerIndex = event.findPointerIndex(active.pointerId)
                        if (pointerIndex != -1) {
                            val cx = event.getX(pointerIndex)
                            val cy = event.getY(pointerIndex) + offset
                            val last = active.points.last()
                            if (hypot((cx - last.first).toDouble(), (cy - last.second).toDouble()) >= 4f * density) {
                                if (hasSelection) clearSelection()
                                active.points.add(cx to cy)
                                invalidate()
                            }
                        }
                    }
                    areaDrag?.let { drag ->
                        val pointerIndex = event.findPointerIndex(drag.pointerId)
                        if (pointerIndex != -1) {
                            val cx = event.getX(pointerIndex)
                            val cy = event.getY(pointerIndex) + offset
                            val dx = cx - drag.downX
                            val dy = cy - drag.downY
                            if (!drag.moved && hypot(dx.toDouble(), dy.toDouble()) > 4f * density) {
                                drag.moved = true
                            }
                            if (drag.moved) {
                                liveTransform = when (drag.mode) {
                                    DragMode.MOVE -> SelectionTransform(
                                        pivotX = drag.startBounds.centerX(),
                                        pivotY = drag.startBounds.centerY(),
                                        translateX = dx,
                                        translateY = dy,
                                        scale = 1f,
                                        rotationDeg = 0f,
                                    )

                                    DragMode.RESIZE -> {
                                        val baseW = drag.startBounds.width().coerceAtLeast(24f * density)
                                        val baseH = drag.startBounds.height().coerceAtLeast(24f * density)
                                        val scale = LassoGeometry.areaDragScale(baseW, baseH, dx, dy)
                                        SelectionTransform(
                                            pivotX = drag.startBounds.left,
                                            pivotY = drag.startBounds.top,
                                            translateX = 0f,
                                            translateY = 0f,
                                            scale = scale,
                                            rotationDeg = 0f,
                                        )
                                    }
                                }
                                invalidate()
                            }
                        }
                    }
                    itemDrag?.let { drag ->
                        val pointerIndex = event.findPointerIndex(drag.pointerId)
                        if (pointerIndex != -1) {
                            val cx = event.getX(pointerIndex)
                            val cy = event.getY(pointerIndex) + offset
                            val dx = cx - drag.downX
                            val dy = cy - drag.downY
                            if (!drag.moved && hypot(dx.toDouble(), dy.toDouble()) > 4f * density) {
                                drag.moved = true
                                cancelPendingOrbit()
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
                                clearSelection()
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
                                if (pendingDeselectPointerId == scroll.pointerId) {
                                    pendingDeselectPointerId = null
                                }
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

                    twoFinger?.takeIf { pointer == it.pointer1Id || pointer == it.pointer2Id }?.let { tf ->
                        twoFinger = null
                        val activeTransform = liveTransform
                        liveTransform = null
                        if (!canceled && tf.moved && activeTransform != null &&
                            !LassoGeometry.isIdentityTransform(activeTransform.translateX, activeTransform.translateY, activeTransform.scale, activeTransform.rotationDeg)
                        ) {
                            commitSelectionTransform(activeTransform, tf.preStrokes, tf.preItems)
                        }
                        invalidate()
                    }

                    lassoing?.takeIf { it.pointerId == pointer }?.let { active ->
                        lassoing = null
                        if (!canceled) {
                            finishLassoSelection(active.points)
                        }
                        invalidate()
                    }

                    areaDrag?.takeIf { it.pointerId == pointer }?.let { drag ->
                        areaDrag = null
                        val activeTransform = liveTransform
                        liveTransform = null
                        if (!canceled && drag.moved && activeTransform != null &&
                            !LassoGeometry.isIdentityTransform(
                                activeTransform.translateX,
                                activeTransform.translateY,
                                activeTransform.scale,
                                activeTransform.rotationDeg,
                            )
                        ) {
                            commitSelectionTransform(activeTransform, drag.preStrokes, drag.preItems)
                        }
                        invalidate()
                    }

                    contourOrbit?.takeIf { it.pointerId == pointer }?.let { orbit ->
                        contourOrbit = null
                        if (!canceled) {
                            val changed = abs(orbit.currentYaw - orbit.startYaw) > 0.002f ||
                                abs(orbit.currentPitch - orbit.startPitch) > 0.002f
                            if (changed) {
                                if (orbit.dirty) {
                                    orbit.dirty = false
                                    renderOrbitPreview(orbit, gridStep = 2)
                                }
                                val finalDoc = orbit.document.copy(
                                    yawRad = orbit.currentYaw,
                                    pitchRad = orbit.currentPitch,
                                )
                                finalizingContourPreviews[orbit.itemId] = orbit.previewBitmap
                                saveContourAsync(orbit.startItem, finalDoc, orbit.heightmap, orbit.voxels)
                            }
                        }
                        invalidate()
                    }

                    itemDrag?.takeIf { it.pointerId == pointer }?.let { drag ->
                        cancelPendingOrbit()
                        itemDrag = null
                        if (canceled) {
                            items = drag.preItems
                            recomputeSelectionBounds()
                        } else if (drag.moved) {
                            val previous = HistorySnapshot(strokes, drag.preItems)
                            if (!commitChange(previous, strokes, items)) {
                                items = drag.preItems
                            }
                            recomputeSelectionBounds()
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
                                    PaperItemKind.CONTOUR -> {
                                        selectedItemIds = setOf(tap.item.id)
                                        selectedStrokeIndices = emptySet()
                                        recomputeSelectionBounds()
                                        activeTool = DiaryTool.SELECT
                                        updateToolbar()
                                        preloadContourAsync(tap.item)
                                        invalidate()
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                    if (scrolling?.pointerId == pointer) {
                        if (canceled) cancelGesture() else scrolling = null
                    }
                    if (pendingDeselectPointerId == pointer) {
                        pendingDeselectPointerId = null
                        if (!canceled) clearSelection()
                    }
                    drainPendingBackgroundCommits()
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

        private fun startTwoFingerTransform(event: MotionEvent) {
            cancelPendingOrbit()
            contourOrbit = null
            itemDrag?.let {
                items = it.preItems
                recomputeSelectionBounds()
            }
            itemDrag = null
            areaDrag = null
            lassoing = null
            scrolling = null
            mediaTap = null
            pendingDeselectPointerId = null
            liveTransform = null

            val x1 = event.getX(0)
            val y1 = event.getY(0) + offset
            val x2 = event.getX(1)
            val y2 = event.getY(1) + offset
            val midX = (x1 + x2) / 2f
            val midY = (y1 + y2) / 2f
            val bounds = selectionBounds
            twoFinger = TwoFingerTransform(
                pointer1Id = event.getPointerId(0),
                pointer2Id = event.getPointerId(1),
                startMidX = midX,
                startMidY = midY,
                startAngleRad = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()),
                startDist = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat(),
                pivotX = bounds?.centerX() ?: midX,
                pivotY = bounds?.centerY() ?: midY,
                allowScale = selectionAllowsResize(),
                allowRotate = selectionAllowsRotate(),
                preStrokes = strokes,
                preItems = items,
            )
        }

        private fun finishLassoSelection(points: List<Pair<Float, Float>>) {
            if (points.size < 3) {
                clearSelection()
                return
            }
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for ((px, py) in points) {
                if (px < minX) minX = px
                if (py < minY) minY = py
                if (px > maxX) maxX = px
                if (py > maxY) maxY = py
            }
            if (maxX - minX < 12f * density && maxY - minY < 12f * density) {
                clearSelection()
                return
            }

            val hitStrokes = mutableSetOf<Int>()
            val scratch = StrokeInput()
            for (i in strokes.indices) {
                val stroke = strokes[i]
                val box = stroke.shape.computeBoundingBox()
                if (box != null && (box.xMax < minX || box.xMin > maxX || box.yMax < minY || box.yMin > maxY)) {
                    continue
                }
                val inputs = stroke.inputs
                var matched = false
                for (p in 0 until inputs.size) {
                    inputs.populate(p, scratch)
                    if (LassoGeometry.pointInPolygon(scratch.x, scratch.y, points)) {
                        matched = true
                        break
                    }
                }
                if (!matched) {
                    // Fall back to segment-segment intersection for fast strokes
                    // whose samples straddle a narrow lasso without landing inside.
                    val samples = mutableListOf<Pair<Float, Float>>()
                    for (p in 0 until inputs.size) {
                        inputs.populate(p, scratch)
                        samples.add(scratch.x to scratch.y)
                    }
                    matched = LassoGeometry.polylineIntersectsPolygon(samples, points)
                }
                if (matched) hitStrokes.add(i)
            }

            val hitItems = mutableSetOf<String>()
            for (item in items) {
                if (LassoGeometry.itemIntersectsLasso(item.x, item.y, item.width, item.height, item.rotation, points)) {
                    hitItems.add(item.id)
                }
            }

            selectedStrokeIndices = hitStrokes
            selectedItemIds = hitItems
            recomputeSelectionBounds()
        }

        private fun commitSelectionTransform(
            transform: SelectionTransform,
            preStrokes: List<Stroke>,
            preItems: List<PaperItem>,
        ) {
            val pt = FloatArray(2)
            val minW = 44f * density
            val maxItemW = (width - 16f * density).coerceAtLeast(minW)

            // Clamp transform.scale once for the whole selection before building the matrix,
            // so item centers, item sizes, stroke points, and stroke widths share one scale.
            val resizableWidths = preItems
                .filter { it.id in selectedItemIds && it.kind != PaperItemKind.AUDIO }
                .map { it.width }
            val clampedScale = LassoGeometry.clampGroupScale(transform.scale, resizableWidths, minW, maxItemW)

            val matrix = Matrix()
            transform.copy(scale = clampedScale).fillMatrix(matrix)

            // First transform selected items (capping width to sheet) and measure overall group bounds.
            var groupMinX = Float.POSITIVE_INFINITY
            var groupMinY = Float.POSITIVE_INFINITY
            var groupMaxX = Float.NEGATIVE_INFINITY
            var groupMaxY = Float.NEGATIVE_INFINITY

            val prelimItems = preItems.map { item ->
                if (item.id !in selectedItemIds) return@map item
                val cx = item.x + item.width / 2f
                val cy = item.y + item.height / 2f
                pt[0] = cx
                pt[1] = cy
                matrix.mapPoints(pt)
                val itemScale = if (item.kind == PaperItemKind.AUDIO) 1f else clampedScale
                val newW = (item.width * itemScale).coerceIn(minW, maxItemW)
                val ratio = if (item.width > 0f) newW / item.width else 1f
                val newH = (item.height * ratio).coerceAtLeast(24f * density)
                val newRot = if (item.kind == PaperItemKind.CONTOUR) {
                    item.rotation
                } else {
                    LassoGeometry.normalizeAngle(item.rotation + transform.rotationDeg)
                }
                val updated = item.copy(
                    x = pt[0] - newW / 2f,
                    y = pt[1] - newH / 2f,
                    width = newW,
                    height = newH,
                    rotation = newRot,
                )
                val b = LassoGeometry.rotatedRectBounds(updated.x, updated.y, updated.width, updated.height, updated.rotation)
                if (b[0] < groupMinX) groupMinX = b[0]
                if (b[1] < groupMinY) groupMinY = b[1]
                if (b[2] > groupMaxX) groupMaxX = b[2]
                if (b[3] > groupMaxY) groupMaxY = b[3]
                updated
            }

            // Include transformed selected stroke bounds in the group bounding box.
            val scratch = StrokeInput()
            for (idx in selectedStrokeIndices) {
                val stroke = preStrokes.getOrNull(idx) ?: continue
                val inputs = stroke.inputs
                for (p in 0 until inputs.size) {
                    inputs.populate(p, scratch)
                    pt[0] = scratch.x
                    pt[1] = scratch.y
                    matrix.mapPoints(pt)
                    if (pt[0] < groupMinX) groupMinX = pt[0]
                    if (pt[1] < groupMinY) groupMinY = pt[1]
                    if (pt[0] > groupMaxX) groupMaxX = pt[0]
                    if (pt[1] > groupMaxY) groupMaxY = pt[1]
                }
            }

            val (shiftX, shiftY) = if (groupMinX.isFinite() && groupMinY.isFinite() && groupMaxX.isFinite() && groupMaxY.isFinite()) {
                LassoGeometry.computeGroupClampShift(
                    minX = groupMinX,
                    minY = groupMinY,
                    maxX = groupMaxX,
                    maxY = groupMaxY,
                    sheetWidth = width.toFloat(),
                    sheetHeight = sheetHeight,
                )
            } else {
                0f to 0f
            }

            if (shiftX != 0f || shiftY != 0f) {
                matrix.postTranslate(shiftX, shiftY)
            }

            val nextItems = if (shiftX != 0f || shiftY != 0f) {
                prelimItems.map { item ->
                    if (item.id !in selectedItemIds) item else item.copy(x = item.x + shiftX, y = item.y + shiftY)
                }
            } else {
                prelimItems
            }

            val nextStrokes = preStrokes.mapIndexed { idx, stroke ->
                if (idx !in selectedStrokeIndices) stroke else transformStroke(stroke, matrix, clampedScale, transform.rotationDeg)
            }

            val previous = HistorySnapshot(preStrokes, preItems)
            if (commitChange(previous, nextStrokes, nextItems)) {
                recomputeSelectionBounds()
            }
        }

        private fun transformStroke(
            stroke: Stroke,
            matrix: Matrix,
            scale: Float,
            rotationDeg: Float,
        ): Stroke {
            val inputs = stroke.inputs
            val batch = MutableStrokeInputBatch()
            batch.setNoiseSeed(inputs.getNoiseSeed())
            val scratch = StrokeInput()
            val pt = FloatArray(2)
            val rotRad = Math.toRadians(rotationDeg.toDouble()).toFloat()
            for (i in 0 until inputs.size) {
                inputs.populate(i, scratch)
                pt[0] = scratch.x
                pt[1] = scratch.y
                matrix.mapPoints(pt)
                val orientation = if (scratch.hasOrientation) {
                    LassoGeometry.wrapOrientation(scratch.orientationRadians + rotRad)
                } else {
                    StrokeInput.NO_ORIENTATION
                }
                val unitLen = if (inputs.hasStrokeUnitLength()) {
                    scratch.strokeUnitLengthCm * scale
                } else {
                    StrokeInput.NO_STROKE_UNIT_LENGTH
                }
                scratch.update(
                    pt[0],
                    pt[1],
                    scratch.elapsedTimeMillis,
                    scratch.toolType,
                    unitLen,
                    scratch.pressure,
                    scratch.tiltRadians,
                    orientation,
                )
                batch.add(scratch)
            }
            val oldBrush = stroke.brush
            val newSize = (oldBrush.size * scale).coerceIn(1f, 200f * density)
            val newBrush = Brush.createWithColorIntArgb(
                oldBrush.family,
                oldBrush.colorIntArgb,
                newSize,
                oldBrush.epsilon,
            )
            return Stroke(newBrush, batch.toImmutable())
        }

        private fun handleSelectDown(pointer: Int, paperX: Float, paperY: Float): Boolean {
            val handleHitR = 22f * density
            val single = singleSelectedItem()
            if (single != null) {
                val pad = 4f * density
                val cx = single.x + single.width / 2f
                val cy = single.y + single.height / 2f
                val (localX, localY) = LassoGeometry.rotatePoint(paperX, paperY, cx, cy, -single.rotation)
                val left = single.x - pad
                val right = single.x + single.width + pad
                val top = single.y - pad
                val bottom = single.y + single.height + pad

                val distDelete = hypot((localX - right).toDouble(), (localY - top).toDouble()).toFloat()
                val distResize = if (single.kind != PaperItemKind.AUDIO) {
                    hypot((localX - right).toDouble(), (localY - bottom).toDouble()).toFloat()
                } else {
                    Float.POSITIVE_INFINITY
                }
                val distEdit = if (single.kind == PaperItemKind.CONTOUR) {
                    hypot((localX - left).toDouble(), (localY - top).toDouble()).toFloat()
                } else {
                    Float.POSITIVE_INFINITY
                }

                // Pick the closer handle when handles overlap within hit radius
                if (distDelete <= handleHitR && distDelete <= distResize && distDelete <= distEdit) {
                    if (mediaPlayback.playingAudioId == single.id) stopAudio()
                    val previous = HistorySnapshot(strokes, items)
                    val remaining = items.filterNot { it.id == single.id }
                    if (commitChange(previous, strokes, remaining)) {
                        clearSelection()
                    }
                    invalidate()
                    return true
                }

                if (distEdit <= handleHitR && distEdit <= distResize) {
                    openContourEditor(single)
                    return true
                }

                if (distResize <= handleHitR) {
                    itemDrag = ItemDrag(pointer, single.id, DragMode.RESIZE, paperX, paperY, single, items)
                    return true
                }

                // Move inside selected item (or hold to orbit selected 3D contour)
                if (localX >= single.x && localX <= single.x + single.width &&
                    localY >= single.y && localY <= single.y + single.height
                ) {
                    itemDrag = ItemDrag(pointer, single.id, DragMode.MOVE, paperX, paperY, single, items)
                    if (single.kind == PaperItemKind.CONTOUR) {
                        scheduleContourOrbit(pointer, single, paperX, paperY - offset)
                    }
                    return true
                }
            } else if (hasSelection) {
                val bounds = selectionBounds
                if (bounds != null) {
                    val pad = 6f * density
                    val right = bounds.right + pad
                    val top = bounds.top - pad
                    val bottom = bounds.bottom + pad

                    val distDelete = hypot((paperX - right).toDouble(), (paperY - top).toDouble()).toFloat()
                    val distResize = if (selectionAllowsResize()) {
                        hypot((paperX - right).toDouble(), (paperY - bottom).toDouble()).toFloat()
                    } else {
                        Float.POSITIVE_INFINITY
                    }

                    // Pick the closer handle when both are within hit radius
                    if (distDelete <= handleHitR && distDelete <= distResize) {
                        if (mediaPlayback.playingAudioId in selectedItemIds) stopAudio()
                        val previous = HistorySnapshot(strokes, items)
                        val remainingStrokes = strokes.filterIndexed { idx, _ -> idx !in selectedStrokeIndices }
                        val remainingItems = items.filterNot { it.id in selectedItemIds }
                        if (commitChange(previous, remainingStrokes, remainingItems)) {
                            clearSelection()
                        }
                        invalidate()
                        return true
                    }

                    if (distResize <= handleHitR) {
                        areaDrag = AreaDrag(pointer, DragMode.RESIZE, paperX, paperY, RectF(bounds), strokes, items)
                        return true
                    }

                    // Move entire selected area
                    if (paperX >= bounds.left - pad && paperX <= bounds.right + pad &&
                        paperY >= bounds.top - pad && paperY <= bounds.bottom + pad
                    ) {
                        areaDrag = AreaDrag(pointer, DragMode.MOVE, paperX, paperY, RectF(bounds), strokes, items)
                        return true
                    }
                }
            }

            // Hit-test items top-most first (accounting for rotation)
            val hit = items.lastOrNull { item ->
                val cx = item.x + item.width / 2f
                val cy = item.y + item.height / 2f
                val (lx, ly) = LassoGeometry.rotatePoint(paperX, paperY, cx, cy, -item.rotation)
                lx >= item.x && lx <= item.x + item.width &&
                    ly >= item.y && ly <= item.y + item.height
            }
            if (hit != null) {
                selectedItemIds = setOf(hit.id)
                selectedStrokeIndices = emptySet()
                recomputeSelectionBounds()
                itemDrag = ItemDrag(pointer, hit.id, DragMode.MOVE, paperX, paperY, hit, items)
                if (hit.kind == PaperItemKind.CONTOUR) {
                    preloadContourAsync(hit)
                }
                invalidate()
                return true
            }

            return false
        }

        private fun findPlayableHit(paperX: Float, paperY: Float): PaperItem? =
            items.lastOrNull { item ->
                val cx = item.x + item.width / 2f
                val cy = item.y + item.height / 2f
                val (lx, ly) = LassoGeometry.rotatePoint(paperX, paperY, cx, cy, -item.rotation)
                when (item.kind) {
                    PaperItemKind.AUDIO -> {
                        val playCx = item.x + 26f * density
                        val playCy = item.y + item.height / 2f
                        hypot((lx - playCx).toDouble(), (ly - playCy).toDouble()) <= 24f * density
                    }

                    PaperItemKind.VIDEO -> {
                        hypot((lx - cx).toDouble(), (ly - cy).toDouble()) <= 28f * density
                    }

                    PaperItemKind.HANZI, PaperItemKind.CONTOUR -> {
                        lx >= item.x && lx <= item.x + item.width &&
                            ly >= item.y && ly <= item.y + item.height
                    }

                    else -> false
                }
            }

        private fun applyItemDrag(drag: ItemDrag, dx: Float, dy: Float) {
            val base = drag.startItem
            val updated = when (drag.mode) {
                DragMode.MOVE -> keepOnSheet(base.copy(x = base.x + dx, y = base.y + dy))

                DragMode.RESIZE -> {
                    val minW = 44f * density
                    val maxW = (width - 16f * density).coerceAtLeast(minW)
                    val resized = LassoGeometry.resizeRotatedRect(
                        x = base.x,
                        y = base.y,
                        width = base.width,
                        height = base.height,
                        rotationDeg = base.rotation,
                        dx = dx,
                        dy = dy,
                        minWidth = minW,
                        maxWidth = maxW,
                    )
                    keepOnSheet(base.copy(x = resized[0], y = resized[1], width = resized[2], height = resized[3]))
                }
            }
            items = drag.preItems.map { if (it.id == drag.itemId) updated else it }
            invalidate()
        }

        private fun keepOnSheet(item: PaperItem): PaperItem {
            val (x, y) = LassoGeometry.clampRotatedItemToSheet(
                item.x, item.y, item.width, item.height, item.rotation, width.toFloat(), sheetHeight,
            )
            return item.copy(x = x, y = y)
        }

        fun cancelGesture() {
            cancelPendingOrbit()
            if (contourOrbit != null) {
                contourOrbit = null
                invalidate()
            }
            drawing?.let { ink.cancelStroke(it.strokeId) }
            drawing = null
            erasing?.let {
                strokes = it.preEraseStrokes
                invalidate()
            }
            erasing = null
            itemDrag?.let {
                items = it.preItems
                recomputeSelectionBounds()
                invalidate()
            }
            itemDrag = null
            if (areaDrag != null || twoFinger != null || lassoing != null || liveTransform != null) {
                areaDrag = null
                twoFinger = null
                lassoing = null
                liveTransform = null
                invalidate()
            }
            mediaTap = null
            pendingDeselectPointerId = null
            scrolling?.let {
                offset = it.startOffset.coerceIn(0f, maxOffset())
                invalidate()
            }
            scrolling = null
            drainPendingBackgroundCommits()
        }

        private fun cancelPendingOrbit() {
            pendingOrbitRunnable?.let { removeCallbacks(it) }
            pendingOrbitRunnable = null
        }

        private fun preloadContourAsync(item: PaperItem, onLoaded: ((PreloadedContour) -> Unit)? = null) {
            preloadedContours[item.ref]?.let { cached ->
                onLoaded?.invoke(cached)
                return
            }
            val store = itemsStore
            ioExecutor.execute {
                val doc = store.readContour(item) ?: return@execute
                val hm = ContourTerrainEngine.buildHeightmap(doc.strokes)
                val vox = if (doc.style == ContourRenderStyle.BLOCK) {
                    ContourTerrainEngine.quantizeForBlocks(hm)
                } else {
                    null
                }
                val loaded = PreloadedContour(doc, hm, vox)
                post {
                    if (itemsStore !== store) return@post
                    preloadedContours[item.ref] = loaded
                    onLoaded?.invoke(loaded)
                }
            }
        }

        private fun scheduleContourOrbit(pointer: Int, item: PaperItem, rawX: Float, rawY: Float) {
            cancelPendingOrbit()
            preloadContourAsync(item)
            val runnable = Runnable {
                pendingOrbitRunnable = null
                val drag = itemDrag ?: return@Runnable
                if (!isAttachedToWindow || drag.pointerId != pointer || drag.itemId != item.id || drag.moved || twoFinger != null) {
                    return@Runnable
                }
                val cached = preloadedContours[item.ref]
                if (cached != null) {
                    startContourOrbit(pointer, item, rawX, rawY, cached)
                } else {
                    preloadContourAsync(item) { loaded ->
                        val activeDrag = itemDrag
                        if (isAttachedToWindow &&
                            activeDrag != null &&
                            activeDrag.pointerId == pointer &&
                            activeDrag.itemId == item.id &&
                            !activeDrag.moved &&
                            twoFinger == null &&
                            contourOrbit == null
                        ) {
                            startContourOrbit(pointer, item, rawX, rawY, loaded)
                        }
                    }
                }
            }
            pendingOrbitRunnable = runnable
            postDelayed(runnable, 220L)
        }

        private fun startContourOrbit(
            pointer: Int,
            item: PaperItem,
            rawX: Float,
            rawY: Float,
            loaded: PreloadedContour,
        ) {
            itemDrag = null
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val previewBmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            val previewCanvas = Canvas(previewBmp)
            val orbit = ContourOrbit(
                pointerId = pointer,
                itemId = item.id,
                startItem = item,
                downRawX = rawX,
                downRawY = rawY,
                startYaw = loaded.document.yawRad,
                startPitch = loaded.document.pitchRad,
                currentYaw = loaded.document.yawRad,
                currentPitch = loaded.document.pitchRad,
                document = loaded.document,
                heightmap = loaded.heightmap,
                voxels = loaded.voxels,
                previewBitmap = previewBmp,
                previewCanvas = previewCanvas,
            )
            renderOrbitPreview(orbit, gridStep = 2)
            contourOrbit = orbit
            invalidate()
        }

        private fun renderOrbitPreview(orbit: ContourOrbit, gridStep: Int = 1) {
            orbit.previewBitmap.eraseColor(Color.TRANSPARENT)
            val previewDoc = orbit.document.copy(
                yawRad = orbit.currentYaw,
                pitchRad = orbit.currentPitch,
            )
            Contour3DRenderer.draw(
                canvas = orbit.previewCanvas,
                document = previewDoc,
                width = orbit.previewBitmap.width.toFloat(),
                height = orbit.previewBitmap.height.toFloat(),
                heightmap = orbit.heightmap,
                voxels = orbit.voxels,
                gridStep = gridStep,
            )
        }

        fun undo() {
            val previous = undoStack.removeLastOrNull() ?: return
            val current = HistorySnapshot(strokes, items)
            if (save(previous.strokes, previous.items)) {
                pushHistory(redoStack, current)
                clearSelection()
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
                clearSelection()
                updateHistoryButtons()
            } else {
                redoStack.addLast(next)
            }
        }

        fun clearDay() {
            clearGenerations[currentDate] = clearGenerationOf(currentDate) + 1
            stopAudio()
            if (save(emptyList(), emptyList(), allowUnreadable = true)) {
                loadedDiskItemRevision = diskItemRevisionOf(currentDate)
                readable = true
                val storeToClear = itemsStore
                storeToClear.clearMetadataAndCache()
                preloadedContours.clear()
                finalizingContourPreviews.clear()
                contourSaveTokens.keys.removeAll { it.first == currentDate }
                pendingBackgroundCommits.forEach { it.targetStore.clearPendingImport(it.item.ref) }
                pendingBackgroundCommits.clear()
                ioExecutor.execute { runCatching { storeToClear.deleteMediaDir() } }
                cancelGesture()
                finishing.entries.removeAll { it.value === entry }
                clearSelection()
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
            val previousStrokes = persistedStrokes
            try {
                if (allowUnreadable || nextStrokes !== previousStrokes) {
                    entry.write(nextStrokes)
                    persistedStrokes = nextStrokes
                }
                if (allowUnreadable || nextItems !== persistedItems) {
                    try {
                        itemsStore.write(nextItems)
                    } catch (error: Exception) {
                        if (persistedStrokes !== previousStrokes) {
                            runCatching { entry.write(previousStrokes) }.onSuccess { persistedStrokes = previousStrokes }
                        }
                        throw error
                    }
                    persistedItems = nextItems
                }
                strokes = nextStrokes
                items = nextItems
                invalidate()
                return true
            } catch (error: Exception) {
                strokes = persistedStrokes
                items = persistedItems
                invalidate()
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
        val entryWriteExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DiaryEntryIO").apply { isDaemon = true }
        }
        val pendingEntryWrites = ConcurrentHashMap<LocalDate, Future<*>>()
        val clearGenerations = ConcurrentHashMap<LocalDate, Int>()
        val diskItemRevisions = ConcurrentHashMap<LocalDate, AtomicLong>()
        val contourSaveTokens = ConcurrentHashMap<Pair<LocalDate, String>, Long>()
        val nextContourToken = AtomicLong(1L)
        val attachedSheets = mutableSetOf<InkSheet>()

        fun clearGenerationOf(date: LocalDate): Int = clearGenerations[date] ?: 0
        fun diskItemRevisionOf(date: LocalDate): Long = diskItemRevisions[date]?.get() ?: 0L
        fun bumpDiskItemRevision(date: LocalDate): Long =
            diskItemRevisions.computeIfAbsent(date) { AtomicLong(0L) }.incrementAndGet()

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
