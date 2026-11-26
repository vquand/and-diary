package io.github.vquand.anddiary

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
) {
    PNG("PNG", "png", "image/png"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp"),
}

internal enum class ExportBackground(val labelRes: Int) {
    TRANSPARENT(R.string.export_bg_transparent),
    CANVAS(R.string.export_bg_canvas),
    CANVAS_WITH_LINES(R.string.export_bg_canvas_lines),
}

internal data class ExportRequest(
    val fileName: String,
    val format: ExportFormat,
    val background: ExportBackground,
)

internal data class SelectionExportSnapshot(
    val date: LocalDate,
    val bounds: RectF,
    val strokes: List<Stroke>,
    val items: List<PaperItem>,
)

internal object SelectionExporter {
    private const val MAX_EXPORT_DIM = 2048
    private const val DEFAULT_RENDER_SCALE = 2f

    fun suggestedBaseName(date: LocalDate): String =
        "diary_${date}_${UUID.randomUUID().toString().take(6)}"

    fun showDialog(
        context: Context,
        date: LocalDate,
        palette: DiaryPalette,
        onConfirm: (ExportRequest) -> Unit,
        onDismiss: () -> Unit = {},
    ): Dialog {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        var selectedFormat = ExportFormat.PNG
        var selectedBackground = ExportBackground.TRANSPARENT
        val defaultBase = suggestedBaseName(date)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(palette.paper)
                setStroke(dp(1), palette.rule)
                cornerRadius = 14f * density
            }
        }

        // Title
        root.addView(
            TextView(context).apply {
                setText(R.string.save_selection)
                setTextColor(palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                paint.isFakeBoldText = true
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )

        // File name label + EditText
        root.addView(sectionLabel(context, R.string.export_file_name, palette), labelParams(dp(4)))
        val nameInput = EditText(context).apply {
            setText(defaultBase)
            setSelection(defaultBase.length)
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(palette.wash)
                setStroke(dp(1), palette.rule)
                cornerRadius = 8f * density
            }
        }
        root.addView(
            nameInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )

        // Format selector
        root.addView(sectionLabel(context, R.string.export_format, palette), labelParams(dp(6)))
        val formatRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(
            formatRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) },
        )

        // Background selector
        root.addView(sectionLabel(context, R.string.export_background, palette), labelParams(dp(6)))
        val bgRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(
            bgRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) },
        )

        val jpegNote = TextView(context).apply {
            setText(R.string.export_jpeg_opaque_note)
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            visibility = View.GONE
        }
        root.addView(
            jpegNote,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) },
        )

        fun updateNote() {
            jpegNote.visibility = if (
                selectedFormat == ExportFormat.JPEG && selectedBackground == ExportBackground.TRANSPARENT
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        val formatChips = mutableListOf<Pair<ExportFormat, TextView>>()
        fun styleFormatChips() {
            for ((fmt, chip) in formatChips) {
                val active = fmt == selectedFormat
                styleChip(chip, active, palette, density)
            }
            updateNote()
        }
        for ((idx, fmt) in ExportFormat.entries.withIndex()) {
            val chip = TextView(context).apply {
                text = fmt.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                setOnClickListener {
                    selectedFormat = fmt
                    styleFormatChips()
                }
            }
            formatChips.add(fmt to chip)
            formatRow.addView(
                chip,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < ExportFormat.entries.lastIndex) marginEnd = dp(6)
                },
            )
        }

        val bgChips = mutableListOf<Pair<ExportBackground, TextView>>()
        fun styleBgChips() {
            for ((bg, chip) in bgChips) {
                val active = bg == selectedBackground
                styleChip(chip, active, palette, density)
            }
            updateNote()
        }
        for ((idx, bg) in ExportBackground.entries.withIndex()) {
            val chip = TextView(context).apply {
                setText(bg.labelRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                setOnClickListener {
                    selectedBackground = bg
                    styleBgChips()
                }
            }
            bgChips.add(bg to chip)
            bgRow.addView(
                chip,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < ExportBackground.entries.lastIndex) marginEnd = dp(6)
                },
            )
        }

        styleFormatChips()
        styleBgChips()

        lateinit var dialog: Dialog
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancelButton = TextView(context).apply {
            setText(android.R.string.cancel)
            setTextColor(palette.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        val saveButton = TextView(context).apply {
            setText(R.string.export_save)
            setTextColor(palette.paper)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            paint.isFakeBoldText = true
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            background = GradientDrawable().apply {
                setColor(palette.accent)
                cornerRadius = 8f * density
            }
            setOnClickListener {
                val finalName = LassoGeometry.sanitizeExportFileName(
                    rawName = nameInput.text?.toString().orEmpty(),
                    fallbackBase = defaultBase,
                    extension = selectedFormat.extension,
                )
                dialog.dismiss()
                onConfirm(ExportRequest(finalName, selectedFormat, selectedBackground))
            }
        }
        actionsRow.addView(cancelButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })
        actionsRow.addView(saveButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(
            actionsRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        dialog = Dialog(context).apply {
            setContentView(root, ViewGroup.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT))
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setCanceledOnTouchOutside(true)
            setOnDismissListener { onDismiss() }
            show()
        }
        return dialog
    }

    fun exportToGallery(
        context: Context,
        snapshot: SelectionExportSnapshot,
        request: ExportRequest,
        itemsStore: PaperItemsStore,
        density: Float,
        paperPalette: DiaryPalette,
    ): String {
        val pad = 12f * density
        val crop = RectF(
            snapshot.bounds.left - pad,
            snapshot.bounds.top - pad,
            snapshot.bounds.right + pad,
            snapshot.bounds.bottom + pad,
        )
        val rawW = crop.width().coerceAtLeast(16f * density)
        val rawH = crop.height().coerceAtLeast(16f * density)
        val maxRaw = max(rawW, rawH)
        val renderScale = minOf(DEFAULT_RENDER_SCALE, MAX_EXPORT_DIM / maxRaw).coerceAtLeast(0.5f)
        val outW = (rawW * renderScale).roundToInt().coerceIn(1, MAX_EXPORT_DIM)
        val outH = (rawH * renderScale).roundToInt().coerceIn(1, MAX_EXPORT_DIM)

        // Ensure all media/sticker bitmaps are decoded before drawing on this background thread.
        for (item in snapshot.items) {
            itemsStore.loadBitmap(item)
        }

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(renderScale, renderScale)
            canvas.translate(-crop.left, -crop.top)

            val drawSolidPaper = request.background != ExportBackground.TRANSPARENT ||
                request.format == ExportFormat.JPEG
            if (drawSolidPaper) {
                canvas.drawColor(paperPalette.paper)
            }
            if (request.background == ExportBackground.CANVAS_WITH_LINES) {
                val guide = Paint().apply {
                    color = paperPalette.rule
                    strokeWidth = 1f
                }
                val spacing = 32f * density
                val firstLine = ceil((crop.top / spacing).toDouble()).toInt()
                val lastLine = floor((crop.bottom / spacing).toDouble()).toInt()
                for (k in firstLine..lastLine) {
                    val y = k * spacing
                    canvas.drawLine(crop.left, y, crop.right, y, guide)
                }
            }

            val itemRenderer = PaperItemRenderer(density, paperPalette)
            for (item in snapshot.items) {
                itemRenderer.drawItem(canvas, item, itemsStore, playingAudioId = null) { _, _ -> }
            }

            if (snapshot.strokes.isNotEmpty()) {
                val strokeRenderer = CanvasStrokeRenderer.create()
                val identity = Matrix()
                for (stroke in snapshot.strokes) {
                    strokeRenderer.draw(canvas, stroke, identity)
                }
            }

            saveBitmapToGallery(context, bitmap, request)
            return request.fileName
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, request: ExportRequest) {
        val compressFormat = when (request.format) {
            ExportFormat.PNG -> Bitmap.CompressFormat.PNG
            ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ExportFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
        val quality = if (request.format == ExportFormat.PNG) 100 else 92

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, request.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, request.format.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Diary")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create MediaStore entry")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(compressFormat, quality, out)) {
                        throw IOException("Bitmap compression failed")
                    }
                } ?: throw IOException("Could not open MediaStore output stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            @Suppress("DEPRECATION")
            val picturesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Diary",
            )
            val targetDir = if (picturesDir.exists() || picturesDir.mkdirs()) {
                picturesDir
            } else {
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: throw IOException("No writable pictures directory")
            }
            val file = File(targetDir, request.fileName)
            file.outputStream().use { out ->
                if (!bitmap.compress(compressFormat, quality, out)) {
                    throw IOException("Bitmap compression failed")
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(request.format.mimeType),
                null,
            )
        }
    }

    private fun sectionLabel(context: Context, textRes: Int, palette: DiaryPalette) = TextView(context).apply {
        setText(textRes)
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        paint.isFakeBoldText = true
    }

    private fun labelParams(bottomPx: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = bottomPx }

    private fun styleChip(chip: TextView, active: Boolean, palette: DiaryPalette, density: Float) {
        chip.setTextColor(if (active) palette.accent else palette.text)
        chip.paint.isFakeBoldText = active
        chip.background = GradientDrawable().apply {
            setColor(if (active) palette.wash else palette.paper)
            setStroke((1f * density).roundToInt(), if (active) palette.accent else palette.rule)
            cornerRadius = 8f * density
        }
    }
}
