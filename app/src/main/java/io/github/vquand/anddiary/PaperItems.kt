package io.github.vquand.anddiary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class PaperItemKind(val wire: String) {
    STICKER("sticker"),
    PHOTO("photo"),
    VIDEO("video"),
    AUDIO("audio"),
    HANZI("hanzi"),
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
    private val mediaBitmaps = mutableMapOf<String, Bitmap?>()
    private val pendingImports = mutableSetOf<String>()
    val catalog: StickerCatalog by lazy { loadCatalog(context) }
    val hanziCatalog: HanziCatalog by lazy { loadHanziCatalog(context) }

    fun read(): List<PaperItem> {
        val bytes = try {
            itemsFile.readFully()
        } catch (error: FileNotFoundException) {
            if (itemsFile.baseFile.exists()) throw error
            return emptyList()
        }
        val array = JSONArray(String(bytes, Charsets.UTF_8))
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

    fun write(items: List<PaperItem>) {
        if (items.isEmpty()) {
            itemsFile.delete()
            if (itemsFile.baseFile.exists()) throw IOException("Could not remove empty items file")
            return
        }
        itemsFile.baseFile.parentFile?.mkdirs()
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
        val stream = itemsFile.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            itemsFile.finishWrite(stream)
        } catch (error: Exception) {
            itemsFile.failWrite(stream)
            throw error
        }
    }

    fun clearAll() {
        itemsFile.delete()
        mediaDir.deleteRecursively()
        mediaBitmaps.clear()
    }

    fun clearPendingImport(filename: String) {
        synchronized(pendingImports) {
            pendingImports.remove(filename)
        }
    }

    fun pruneUnusedMedia(activeItems: List<PaperItem>) {
        val protectedRefs = synchronized(pendingImports) { pendingImports.toSet() }
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
        synchronized(pendingImports) { pendingImports.add(filename) }
        try {
            mediaDir.mkdirs()
            val destination = File(mediaDir, filename)

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

            PaperItemKind.STICKER, PaperItemKind.HANZI -> {
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
            clearPendingImport(filename)
            throw error
        }
    }

    fun cachedBitmap(item: PaperItem): Bitmap? {
        if (item.kind == PaperItemKind.AUDIO || item.kind == PaperItemKind.HANZI) return null
        if (item.kind == PaperItemKind.STICKER) return loadStickerBitmap(item.ref)
        val key = "${item.kind.wire}:${item.ref}"
        synchronized(mediaBitmaps) {
            return mediaBitmaps[key]
        }
    }

    fun hasBitmapEntry(item: PaperItem): Boolean {
        if (item.kind == PaperItemKind.AUDIO || item.kind == PaperItemKind.HANZI || item.kind == PaperItemKind.STICKER) {
            return true
        }
        val key = "${item.kind.wire}:${item.ref}"
        synchronized(mediaBitmaps) {
            return mediaBitmaps.containsKey(key)
        }
    }

    fun loadBitmap(item: PaperItem): Bitmap? {
        if (item.kind == PaperItemKind.AUDIO || item.kind == PaperItemKind.HANZI) return null
        if (item.kind == PaperItemKind.STICKER) return loadStickerBitmap(item.ref)
        val key = "${item.kind.wire}:${item.ref}"
        synchronized(mediaBitmaps) {
            if (mediaBitmaps.containsKey(key)) return mediaBitmaps[key]
        }

        val decoded = when (item.kind) {
            PaperItemKind.PHOTO -> decodeSampledFile(mediaFile(item), 1024)
            PaperItemKind.VIDEO -> decodeVideoFrame(mediaFile(item), 640)
            else -> null
        }
        synchronized(mediaBitmaps) {
            mediaBitmaps[key] = decoded
        }
        return decoded
    }

    fun loadStickerBitmap(stickerId: String): Bitmap? {
        synchronized(stickerBitmaps) {
            if (stickerBitmaps.containsKey(stickerId)) return stickerBitmaps[stickerId]
        }
        val spec = catalog.specFor(stickerId)
        val decoded = if (spec?.sheet != null) {
            val sheetBmp = synchronized(sheetBitmaps) {
                sheetBitmaps.getOrPut(spec.sheet) {
                    runCatching {
                        context.assets.open("stickers/${spec.sheet}").use(BitmapFactory::decodeStream)
                    }.getOrNull()
                }
            }
            if (sheetBmp != null && spec.cols > 0 && spec.rows > 0) {
                val cellW = sheetBmp.width / spec.cols
                val cellH = sheetBmp.height / spec.rows
                val x = (spec.col * cellW).coerceIn(0, (sheetBmp.width - cellW).coerceAtLeast(0))
                val y = (spec.row * cellH).coerceIn(0, (sheetBmp.height - cellH).coerceAtLeast(0))
                runCatching { Bitmap.createBitmap(sheetBmp, x, y, cellW, cellH) }.getOrNull()
            } else {
                null
            }
        } else {
            val file = catalog.fileFor(stickerId)
            runCatching {
                context.assets.open("stickers/$file").use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
        synchronized(stickerBitmaps) {
            stickerBitmaps[stickerId] = decoded
        }
        return decoded
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: throw IOException("Could not open selected image")
        val largest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largest / sample > 2048) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Could not decode selected image")
        try {
            val format = if (Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            destination.outputStream().use { output ->
                if (!bitmap.compress(format, 88, output)) {
                    throw IOException("Could not encode image as WebP")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun extensionFor(displayName: String?, mime: String?, kind: PaperItemKind): String {
        val fromMime = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        if (!fromMime.isNullOrBlank()) return fromMime
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase(Locale.US)
        if (!fromName.isNullOrBlank() && fromName.length <= 5) return fromName
        return when (kind) {
            PaperItemKind.PHOTO -> "webp"
            PaperItemKind.VIDEO -> "mp4"
            PaperItemKind.AUDIO -> "m4a"
            PaperItemKind.STICKER, PaperItemKind.HANZI -> "webp"
        }
    }

    companion object {
        private var cachedCatalog: StickerCatalog? = null
        private var cachedHanzi: HanziCatalog? = null
        private val sheetBitmaps = mutableMapOf<String, Bitmap?>()
        private val stickerBitmaps = mutableMapOf<String, Bitmap?>()

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
                .filter { it.kind == PaperItemKind.PHOTO || it.kind == PaperItemKind.VIDEO || it.kind == PaperItemKind.AUDIO }
                .mapTo(mutableSetOf()) { it.ref }
            keep.addAll(protectedRefs)
            mediaDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in keep) {
                    file.delete()
                }
            }
            if (mediaDir.list().isNullOrEmpty()) {
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
