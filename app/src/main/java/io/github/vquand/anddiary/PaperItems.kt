package io.github.vquand.anddiary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.LruCache
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class PaperItemKind(val wire: String) {
    STICKER("sticker"),
    PHOTO("photo"),
    VIDEO("video"),
    AUDIO("audio"),
    HANZI("hanzi"),
    CONTOUR("contour"),
    ;

    companion object {
        fun fromWire(value: String): PaperItemKind? = entries.firstOrNull { it.wire == value }
    }
}

internal data class HanziWord(
    val char: String,
    val pinyin: String,
    val hanViet: String,
    val meaning: String,
    val strokes: List<List<Pair<Float, Float>>>,
)

internal data class HanziCatalog(val words: List<HanziWord>) {
    fun find(char: String): HanziWord? = words.firstOrNull { it.char == char }
}

internal data class PaperItem(
    val id: String,
    val kind: PaperItemKind,
    val ref: String,
    val label: String,
    val durationMs: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val score: Int = 0,
    val rotation: Float = 0f,
)

internal data class StickerPack(
    val id: String,
    val name: String,
    val description: String,
)

internal data class StickerSpec(
    val id: String,
    val packId: String,
    val file: String,
    val label: String,
    val description: String,
    val tags: List<String>,
    val sheet: String? = null,
    val cols: Int = 1,
    val rows: Int = 1,
    val col: Int = 0,
    val row: Int = 0,
) {
    private val searchBlob: String by lazy {
        buildString {
            append(label.lowercase(Locale.US))
            append(' ')
            append(id.lowercase(Locale.US))
            append(' ')
            append(packId.lowercase(Locale.US))
            append(' ')
            append(description.lowercase(Locale.US))
            for (tag in tags) {
                append(' ')
                append(tag.lowercase(Locale.US))
            }
        }
    }

    fun matches(queryTokens: List<String>): Boolean =
        queryTokens.all { token -> searchBlob.contains(token) }
}

internal data class StickerCatalog(
    val packs: List<StickerPack>,
    val stickers: List<StickerSpec>,
) {
    fun filter(query: String, packId: String?): List<StickerSpec> {
        val tokens = query.trim().lowercase(Locale.US)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        return stickers.filter { sticker ->
            (packId == null || sticker.packId == packId) &&
                (tokens.isEmpty() || sticker.matches(tokens))
        }
    }

    fun specFor(stickerId: String): StickerSpec? =
        stickers.firstOrNull { it.id == stickerId }

    fun fileFor(stickerId: String): String =
        specFor(stickerId)?.file ?: "$stickerId.webp"
}

internal class PaperItemsStore(private val context: Context, val date: LocalDate) {
    private val itemsFile = AtomicFile(File(context.filesDir, "entries/$date.items"))
    private val mediaDir = File(context.filesDir, "media/$date")
    private val mediaBitmaps = object : LruCache<String, Bitmap>(mediaCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failedMedia = mutableSetOf<String>()
    private val mediaDirKey = mediaDir.absolutePath
    val catalog: StickerCatalog by lazy { loadCatalog(context) }
    val hanziCatalog: HanziCatalog by lazy { loadHanziCatalog(context) }

    fun read(): List<PaperItem> {
        val bytes = try {
            itemsFile.readFully()
        } catch (error: FileNotFoundException) {
            if (itemsFile.baseFile.exists()) throw error
            return emptyList()
        }
        return decodeItemsJson(String(bytes, Charsets.UTF_8))
    }

    fun write(items: List<PaperItem>) {
        if (items.isEmpty()) {
            itemsFile.delete()
            if (itemsFile.baseFile.exists()) throw IOException("Could not remove empty items file")
            return
        }
        itemsFile.baseFile.parentFile?.mkdirs()
        val payload = encodeItemsJson(items).toByteArray(Charsets.UTF_8)
        val stream = itemsFile.startWrite()
        try {
            stream.write(payload)
            stream.flush()
            itemsFile.finishWrite(stream)
        } catch (error: Exception) {
            itemsFile.failWrite(stream)
            throw error
        }
    }

    fun clearMetadataAndCache() {
        itemsFile.delete()
        mediaBitmaps.evictAll()
        synchronized(failedMedia) { failedMedia.clear() }
    }

    fun deleteMediaDir() {
        mediaDir.deleteRecursively()
    }

    fun clearPendingImport(filename: String) {
        clearPendingImportForDir(mediaDirKey, filename)
    }

    fun pruneUnusedMediaFromDisk() {
        // Snapshot pending imports before reading .items so a commit landing in between is visible in one of the two.
        val protectedRefs = protectedRefsForDir(mediaDirKey)
        val activeItems = runCatching { read() }.getOrNull() ?: return
        pruneUnusedMediaInDir(mediaDir, activeItems, protectedRefs)
    }

    fun mediaFile(item: PaperItem): File = File(mediaDir, item.ref)

    fun createSticker(spec: StickerSpec, centerX: Float, centerY: Float, density: Float): PaperItem {
        val size = 76f * density
        return PaperItem(
            id = UUID.randomUUID().toString(),
            kind = PaperItemKind.STICKER,
            ref = spec.id,
            label = spec.label,
            durationMs = 0L,
            x = (centerX - size / 2f).coerceAtLeast(8f * density),
            y = (centerY - size / 2f).coerceAtLeast(8f * density),
            width = size,
            height = size,
        )
    }

    fun createHanzi(word: HanziWord, centerX: Float, centerY: Float, maxWidth: Float, density: Float): PaperItem {
        val width = minOf(maxWidth, 256f * density).coerceAtLeast(210f * density)
        val height = 114f * density
        return PaperItem(
            id = UUID.randomUUID().toString(),
            kind = PaperItemKind.HANZI,
            ref = word.char,
            label = word.pinyin,
            durationMs = word.strokes.size.toLong(),
            x = (centerX - width / 2f).coerceAtLeast(8f * density),
            y = (centerY - height / 2f).coerceAtLeast(8f * density),
            width = width,
            height = height,
        )
    }

    fun createContour(
        document: ContourDocument,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        density: Float,
    ): PaperItem {
        val filename = writeContourFile(document)
        val width = minOf(maxWidth, 260f * density).coerceAtLeast(180f * density)
        val height = width * 0.75f
        return PaperItem(
            id = UUID.randomUUID().toString(),
            kind = PaperItemKind.CONTOUR,
            ref = filename,
            label = document.style.wire,
            durationMs = document.strokes.size.toLong(),
            x = (centerX - width / 2f).coerceAtLeast(8f * density),
            y = (centerY - height / 2f).coerceAtLeast(8f * density),
            width = width,
            height = height,
        )
    }

    fun updateContour(existing: PaperItem, document: ContourDocument): PaperItem {
        val filename = writeContourFile(document)
        return existing.copy(
            ref = filename,
            label = document.style.wire,
            durationMs = document.strokes.size.toLong(),
        )
    }

    fun readContour(item: PaperItem): ContourDocument? {
        val file = mediaFile(item)
        if (!file.exists()) return null
        return runCatching {
            ContourDocument.fromJson(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun writeContourFile(document: ContourDocument): String {
        val filename = "${UUID.randomUUID()}.contour.json"
        val destination = File(mediaDir, filename)
        addPendingImportForDir(mediaDirKey, filename)
        mediaDir.mkdirs()
        val atomic = AtomicFile(destination)
        val payload = document.toJson().toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try {
            stream.write(payload)
            stream.flush()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            clearPendingImport(filename)
            throw error
        }
        return filename
    }

    fun importMedia(
        uri: Uri,
        kind: PaperItemKind,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        density: Float,
    ): PaperItem {
        val displayName = queryDisplayName(uri)
        val mime = context.contentResolver.getType(uri)
        val extension = if (kind == PaperItemKind.PHOTO) "webp" else extensionFor(displayName, mime, kind)
        val id = UUID.randomUUID().toString()
        val filename = "$id.$extension"
        val destination = File(mediaDir, filename)
        addPendingImportForDir(mediaDirKey, filename)
        try {
            mediaDir.mkdirs()

            if (kind == PaperItemKind.PHOTO) {
                importPhotoAsWebp(uri, destination)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("Could not open selected media")
            }

        val label = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: when (kind) {
            PaperItemKind.PHOTO -> "Photo"
            PaperItemKind.VIDEO -> "Video"
            PaperItemKind.AUDIO -> "Audio"
            PaperItemKind.STICKER -> "Sticker"
            PaperItemKind.HANZI -> "Hanzi"
            PaperItemKind.CONTOUR -> "Contour"
        }

        var durationMs = 0L
        val targetWidth: Float
        val targetHeight: Float

        when (kind) {
            PaperItemKind.PHOTO -> {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(destination.absolutePath, opts)
                val aspect = if (opts.outWidth > 0 && opts.outHeight > 0) {
                    opts.outHeight.toFloat() / opts.outWidth.toFloat()
                } else {
                    0.75f
                }
                targetWidth = minOf(maxWidth, 220f * density).coerceAtLeast(96f * density)
                targetHeight = (targetWidth * aspect).coerceIn(64f * density, 340f * density)
            }

            PaperItemKind.VIDEO -> {
                var aspect = 9f / 16f
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(destination.absolutePath)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                    val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
                    val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                    val (w, h) = if (rot == 90 || rot == 270) rawH to rawW else rawW to rawH
                    if (w > 0 && h > 0) aspect = h.toFloat() / w.toFloat()
                } catch (_: Exception) {
                } finally {
                    retriever.release()
                }
                targetWidth = minOf(maxWidth, 230f * density).coerceAtLeast(120f * density)
                targetHeight = (targetWidth * aspect).coerceIn(96f * density, 320f * density)
            }

            PaperItemKind.AUDIO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(destination.absolutePath)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } catch (_: Exception) {
                } finally {
                    retriever.release()
                }
                targetWidth = minOf(maxWidth, 236f * density).coerceAtLeast(160f * density)
                targetHeight = 58f * density
            }

            PaperItemKind.STICKER, PaperItemKind.HANZI, PaperItemKind.CONTOUR -> {
                targetWidth = 76f * density
                targetHeight = 76f * density
            }
        }

            return PaperItem(
                id = id,
                kind = kind,
                ref = filename,
                label = label,
                durationMs = durationMs,
                x = (centerX - targetWidth / 2f).coerceAtLeast(8f * density),
                y = (centerY - targetHeight / 2f).coerceAtLeast(8f * density),
                width = targetWidth,
                height = targetHeight,
            )
        } catch (error: Exception) {
            destination.delete()
            clearPendingImport(filename)
            throw error
        }
    }

    fun cacheBitmap(item: PaperItem, bitmap: Bitmap) {
        val key = "${item.kind.wire}:${item.ref}"
        mediaBitmaps.put(key, bitmap)
        synchronized(failedMedia) { failedMedia.remove(key) }
    }

    fun cachedBitmap(item: PaperItem): Bitmap? {
        if (item.kind == PaperItemKind.AUDIO || item.kind == PaperItemKind.HANZI) return null
        if (item.kind == PaperItemKind.STICKER) return cachedStickerBitmap(item.ref)
        return mediaBitmaps.get("${item.kind.wire}:${item.ref}")
    }

    fun hasBitmapEntry(item: PaperItem): Boolean {
        if (item.kind == PaperItemKind.AUDIO || item.kind == PaperItemKind.HANZI) return true
        if (item.kind == PaperItemKind.STICKER) {
            return stickerBitmaps.get(item.ref) != null || synchronized(failedStickers) { item.ref in failedStickers }
        }
        val key = "${item.kind.wire}:${item.ref}"
        return mediaBitmaps.get(key) != null || synchronized(failedMedia) { key in failedMedia }
    }

    fun loadBitmap(item: PaperItem): Bitmap? {
        if (item.kind == PaperItemKind.AUDIO || item.kind == PaperItemKind.HANZI) return null
        if (item.kind == PaperItemKind.STICKER) return loadStickerBitmap(item.ref)
        val key = "${item.kind.wire}:${item.ref}"
        mediaBitmaps.get(key)?.let { return it }
        if (synchronized(failedMedia) { key in failedMedia }) return null

        val decoded = when (item.kind) {
            PaperItemKind.PHOTO -> decodeSampledFile(mediaFile(item), 1024)
            PaperItemKind.VIDEO -> decodeVideoFrame(mediaFile(item), 640)
            PaperItemKind.CONTOUR -> readContour(item)?.let { Contour3DRenderer.render(it, 800, 600) }
            else -> null
        }
        if (decoded != null) {
            mediaBitmaps.put(key, decoded)
        } else {
            synchronized(failedMedia) { failedMedia.add(key) }
        }
        return decoded
    }

    fun cachedStickerBitmap(stickerId: String): Bitmap? =
        stickerBitmaps.get(stickerId)

    fun loadStickerBitmapAsync(stickerId: String, onLoaded: (Bitmap?) -> Unit) {
        stickerExecutor.execute {
            val bitmap = loadStickerBitmap(stickerId)
            mainHandler.post { onLoaded(bitmap) }
        }
    }

    /** Blocking; call off the UI thread. A sheet is decoded once, sliced into every cell, then released. */
    fun loadStickerBitmap(stickerId: String): Bitmap? {
        stickerBitmaps.get(stickerId)?.let { return it }
        if (synchronized(failedStickers) { stickerId in failedStickers }) return null
        val sheet = catalog.specFor(stickerId)?.sheet
        if (sheet != null) {
            synchronized(sheetLock) {
                if (stickerBitmaps.get(stickerId) == null && synchronized(failedStickers) { stickerId !in failedStickers }) {
                    sliceStickerSheet(sheet)
                }
            }
            return cachedStickerBitmap(stickerId)
        }
        val decoded = runCatching {
            context.assets.open("stickers/${catalog.fileFor(stickerId)}").use(BitmapFactory::decodeStream)
        }.getOrNull()
        if (decoded != null) {
            stickerBitmaps.put(stickerId, decoded)
        } else {
            synchronized(failedStickers) { failedStickers.add(stickerId) }
        }
        return decoded
    }

    private fun sliceStickerSheet(sheet: String) {
        val sheetBmp = runCatching {
            context.assets.open("stickers/$sheet").use(BitmapFactory::decodeStream)
        }.getOrNull()
        val specs = catalog.stickers.filter { it.sheet == sheet }
        for (spec in specs) {
            val cell = sheetBmp?.let { cropStickerCell(it, spec) }
            if (cell != null) {
                stickerBitmaps.put(spec.id, cell)
            } else {
                synchronized(failedStickers) { failedStickers.add(spec.id) }
            }
        }
        if (sheetBmp != null && specs.none { stickerBitmaps.get(it.id) === sheetBmp }) sheetBmp.recycle()
    }

    private fun cropStickerCell(sheetBmp: Bitmap, spec: StickerSpec): Bitmap? {
        if (spec.cols <= 0 || spec.rows <= 0) return null
        val cellW = sheetBmp.width / spec.cols
        val cellH = sheetBmp.height / spec.rows
        val x = (spec.col * cellW).coerceIn(0, (sheetBmp.width - cellW).coerceAtLeast(0))
        val y = (spec.row * cellH).coerceIn(0, (sheetBmp.height - cellH).coerceAtLeast(0))
        return runCatching { Bitmap.createBitmap(sheetBmp, x, y, cellW, cellH) }.getOrNull()
    }

    private fun decodeSampledFile(file: File, maxDim: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val largest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largest / sample > maxDim) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun decodeVideoFrame(file: File, maxDim: Int): Bitmap? {
        if (!file.exists()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxDim, maxDim)
            } else {
                retriever.getFrameAtTime(0L)
            }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    private fun importPhotoAsWebp(uri: Uri, destination: File) {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: throw IOException("Could not open selected image")
        val largest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largest / sample > 2048) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Could not decode selected image")
        val oriented = applyExifOrientation(decoded, orientation)
        try {
            val format = webpCompressFormat()
            destination.outputStream().use { output ->
                if (!oriented.compress(format, 88, output)) {
                    throw IOException("Could not encode image as WebP")
                }
            }
        } finally {
            if (oriented !== decoded) oriented.recycle()
            decoded.recycle()
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val (degrees, flip) = exifTransform(orientation) ?: return bitmap
        val matrix = Matrix()
        matrix.setRotate(degrees)
        if (flip) matrix.postScale(-1f, 1f)
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun extensionFor(displayName: String?, mime: String?, kind: PaperItemKind): String {
        val fromMime = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (!fromMime.isNullOrBlank()) return fromMime
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase(Locale.US)
        if (!fromName.isNullOrBlank() && fromName.length <= 5) return fromName
        return if (kind == PaperItemKind.AUDIO) "m4a" else "mp4"
    }

    companion object {
        private var cachedCatalog: StickerCatalog? = null
        private var cachedHanzi: HanziCatalog? = null
        private val stickerBitmaps = object : LruCache<String, Bitmap>(stickerCacheBytes()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        // Tracks ids whose decode failed (null result). LruCache cannot store null values.
        private val failedStickers = mutableSetOf<String>()
        private val sheetLock = Any()
        private val pendingImportsByDir = HashMap<String, MutableSet<String>>()
        private val stickerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DiaryStickerIO").apply { isDaemon = true }
        }
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        internal fun addPendingImportForDir(mediaDirKey: String, filename: String) {
            synchronized(pendingImportsByDir) {
                pendingImportsByDir.getOrPut(mediaDirKey) { mutableSetOf() }.add(filename)
            }
        }

        internal fun clearPendingImportForDir(mediaDirKey: String, filename: String) {
            synchronized(pendingImportsByDir) {
                val refs = pendingImportsByDir[mediaDirKey] ?: return
                refs.remove(filename)
                if (refs.isEmpty()) pendingImportsByDir.remove(mediaDirKey)
            }
        }

        internal fun protectedRefsForDir(mediaDirKey: String): Set<String> =
            synchronized(pendingImportsByDir) { pendingImportsByDir[mediaDirKey]?.toSet().orEmpty() }

        private fun mediaCacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8).coerceIn(16L shl 20, 128L shl 20).toInt()

        private fun stickerCacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 12).coerceIn(8L shl 20, 64L shl 20).toInt()

        /** Rotation in degrees (clockwise) applied first, then an optional horizontal flip; null when upright. */
        fun exifTransform(orientation: Int): Pair<Float, Boolean>? = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0f to true
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f to false
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180f to true
            ExifInterface.ORIENTATION_TRANSPOSE -> 90f to true
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f to false
            ExifInterface.ORIENTATION_TRANSVERSE -> -90f to true
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f to false
            else -> null
        }

        fun webpCompressFormat(): Bitmap.CompressFormat =
            if (Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

        fun encodeItemsJson(items: List<PaperItem>): String {
            val array = JSONArray()
            for (item in items) {
                array.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("kind", item.kind.wire)
                        .put("ref", item.ref)
                        .put("label", item.label)
                        .put("durationMs", item.durationMs)
                        .put("x", item.x.toDouble())
                        .put("y", item.y.toDouble())
                        .put("width", item.width.toDouble())
                        .put("height", item.height.toDouble())
                        .put("score", item.score)
                        .put("rotation", item.rotation.toDouble()),
                )
            }
            return array.toString()
        }

        fun decodeItemsJson(json: String): List<PaperItem> {
            val array = JSONArray(json)
            return List(array.length()) { index ->
                val obj = array.getJSONObject(index)
                val kind = PaperItemKind.fromWire(obj.getString("kind"))
                    ?: throw IOException("Unknown paper item kind")
                PaperItem(
                    id = obj.getString("id"),
                    kind = kind,
                    ref = obj.getString("ref"),
                    label = obj.optString("label", ""),
                    durationMs = obj.optLong("durationMs", 0L),
                    x = obj.getDouble("x").toFloat(),
                    y = obj.getDouble("y").toFloat(),
                    width = obj.getDouble("width").toFloat(),
                    height = obj.getDouble("height").toFloat(),
                    score = obj.optInt("score", 0),
                    rotation = obj.optDouble("rotation", 0.0).toFloat(),
                )
            }
        }

        fun loadHanziCatalog(context: Context): HanziCatalog {
            cachedHanzi?.let { return it }
            val parsed = runCatching {
                val jsonText = context.assets.open("hanzi/words.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val root = JSONObject(jsonText)
                val wordsArr = root.getJSONArray("words")
                val words = List(wordsArr.length()) { i ->
                    val obj = wordsArr.getJSONObject(i)
                    val strokesArr = obj.getJSONArray("strokes")
                    val strokes = List(strokesArr.length()) { s ->
                        val ptsArr = strokesArr.getJSONArray(s)
                        List(ptsArr.length()) { p ->
                            val pt = ptsArr.getJSONArray(p)
                            pt.getDouble(0).toFloat() to pt.getDouble(1).toFloat()
                        }
                    }
                    HanziWord(
                        char = obj.getString("char"),
                        pinyin = obj.getString("pinyin"),
                        hanViet = obj.getString("hanViet"),
                        meaning = obj.getString("meaning"),
                        strokes = strokes,
                    )
                }
                HanziCatalog(words)
            }.getOrElse { HanziCatalog(emptyList()) }
            cachedHanzi = parsed
            return parsed
        }

        fun loadCatalog(context: Context): StickerCatalog {
            cachedCatalog?.let { return it }
            val parsed = runCatching {
                val jsonText = context.assets.open("stickers/catalog.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val root = JSONObject(jsonText)
                val packsArr = root.getJSONArray("packs")
                val packs = List(packsArr.length()) { index ->
                    val obj = packsArr.getJSONObject(index)
                    StickerPack(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                    )
                }
                val stickersArr = root.getJSONArray("stickers")
                val stickers = List(stickersArr.length()) { index ->
                    val obj = stickersArr.getJSONObject(index)
                    val tagsArr = obj.optJSONArray("tags")
                    val tags = if (tagsArr != null) {
                        List(tagsArr.length()) { t -> tagsArr.getString(t) }
                    } else {
                        emptyList()
                    }
                    val sheet = obj.optString("sheet", "").takeIf { it.isNotEmpty() }
                    StickerSpec(
                        id = obj.getString("id"),
                        packId = obj.getString("pack"),
                        file = obj.optString("file", ""),
                        label = obj.getString("label"),
                        description = obj.optString("description", ""),
                        tags = tags,
                        sheet = sheet,
                        cols = obj.optInt("cols", 1),
                        rows = obj.optInt("rows", 1),
                        col = obj.optInt("col", 0),
                        row = obj.optInt("row", 0),
                    )
                }
                StickerCatalog(packs, stickers)
            }.getOrElse {
                StickerCatalog(emptyList(), emptyList())
            }
            cachedCatalog = parsed
            return parsed
        }

        fun pruneUnusedMediaInDir(
            mediaDir: File,
            activeItems: List<PaperItem>,
            protectedRefs: Set<String> = emptySet(),
        ) {
            if (!mediaDir.isDirectory) return
            val keep = activeItems
                .filter {
                    it.kind == PaperItemKind.PHOTO ||
                        it.kind == PaperItemKind.VIDEO ||
                        it.kind == PaperItemKind.AUDIO ||
                        it.kind == PaperItemKind.CONTOUR
                }
                .mapTo(mutableSetOf()) { it.ref }
            keep.addAll(protectedRefs)
            mediaDir.listFiles()?.forEach { file ->
                val baseName = file.name.removeSuffix(".new").removeSuffix(".bak")
                if (file.isFile && file.name !in keep && baseName !in protectedRefs) {
                    file.delete()
                }
            }
            if (protectedRefs.isEmpty() && mediaDir.list().isNullOrEmpty()) {
                mediaDir.delete()
            }
        }

        fun formatDuration(ms: Long): String {
            if (ms <= 0L) return ""
            val totalSeconds = (ms / 1000.0).roundToInt()
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%d:%02d", minutes, seconds)
            }
        }
    }
}
