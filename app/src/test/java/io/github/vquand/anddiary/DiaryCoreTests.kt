package io.github.vquand.anddiary

import android.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DiaryCoreTests {
    @Test
    fun hanziStrokeMatcherAcceptsAccurateStrokeAndRejectsReversed() {
        val horizontal = listOf(18f to 38f, 50f to 37f, 82f to 36f)
        val userGood = listOf(20f to 40f, 52f to 39f, 80f to 37f)
        val userReversed = listOf(82f to 36f, 50f to 37f, 18f to 38f)
        val userFar = listOf(18f to 85f, 50f to 85f, 82f to 85f)

        val goodScore = HanziStrokeMatcher.matchScore(userGood, horizontal)
        assertNotNull(goodScore)
        assertTrue("Expected high score, got $goodScore", goodScore!! >= 85)
        assertNull(HanziStrokeMatcher.matchScore(userReversed, horizontal))
        assertNull(HanziStrokeMatcher.matchScore(userFar, horizontal))
    }

    @Test
    fun hanziStrokeMatcherAcceptsShortDotStroke() {
        val dot = listOf(46f to 12f, 51f to 17f, 55f to 22f)
        val userDot = listOf(48f to 14f, 53f to 20f)
        val tooLong = listOf(46f to 12f, 46f to 75f)

        assertNotNull(HanziStrokeMatcher.matchScore(userDot, dot))
        assertNull(HanziStrokeMatcher.matchScore(tooLong, dot))
    }

    @Test
    fun stickerCatalogFiltersAcrossPacksAndMetadataTokens() {
        val catalog = StickerCatalog(
            packs = listOf(
                StickerPack("capybara", "Capybara", "Chubby capybara"),
                StickerPack("rabbit", "Lop Rabbit", "Low-eared bunny"),
                StickerPack("dog", "Dogs", "Friendly dogs"),
            ),
            stickers = listOf(
                StickerSpec("capybara/happy", "capybara", "", "Capybara Happy", "Happy smiling capybara", listOf("happy", "chubby"), "sheets/capybara.webp", 4, 3, 0, 0),
                StickerSpec("rabbit/happy", "rabbit", "", "Rabbit Happy", "Happy lop-eared rabbit", listOf("happy", "lop"), "sheets/rabbit.webp", 4, 3, 0, 0),
                StickerSpec("dog/samoyed_sleepy", "dog", "", "Samoyed Sleepy", "Sleepy fluffy samoyed", listOf("sleepy", "samoyed"), "sheets/dog.webp", 4, 3, 1, 1),
            ),
        )

        val happy = catalog.filter("happy", null)
        assertEquals(listOf("capybara/happy", "rabbit/happy"), happy.map { it.id })

        val capyHappy = catalog.filter("  HAPPY   capybara ", null)
        assertEquals(listOf("capybara/happy"), capyHappy.map { it.id })

        val rabbitOnly = catalog.filter("happy", "rabbit")
        assertEquals(listOf("rabbit/happy"), rabbitOnly.map { it.id })

        val byBreed = catalog.filter("samoyed", null)
        assertEquals(listOf("dog/samoyed_sleepy"), byBreed.map { it.id })
    }

    @Test
    fun formatsMediaDurationsCleanly() {
        assertEquals("", PaperItemsStore.formatDuration(0L))
        assertEquals("0:14", PaperItemsStore.formatDuration(14_000L))
        assertEquals("1:05", PaperItemsStore.formatDuration(65_000L))
        assertEquals("1:01:02", PaperItemsStore.formatDuration(3_662_000L))
    }

    @Test
    fun derivesAndroidPaletteFromRustCoreColors() {
        val light = DiaryPalette.fromCoreColors(
            isDark = false,
            paper = 0xfff8f3e8.toInt(),
            wash = 0xffeee5d5.toInt(),
            rule = 0xffe0d7c6.toInt(),
            text = 0xff302e29.toInt(),
            muted = 0xff736c60.toInt(),
            accent = 0xff984631.toInt(),
            danger = 0xff9b3a32.toInt(),
        )
        assertEquals(0xfff8f3e8.toInt(), light.paper)
        assertEquals(0xff984631.toInt(), light.accent)
        assertEquals(0x26736c60, light.eraserFill)
    }

    @Test
    fun prunesUnusedMediaWhileKeepingActiveAndInFlightImports() {
        val dir = Files.createTempDirectory("diary-media-test").toFile()
        try {
            val keepActive = File(dir, "active.jpg").apply { writeText("photo") }
            val keepPending = File(dir, "inflight.mp4").apply { writeText("video") }
            val orphan = File(dir, "deleted.m4a").apply { writeText("audio") }

            val activeItems = listOf(
                PaperItem(
                    id = "1",
                    kind = PaperItemKind.PHOTO,
                    ref = "active.jpg",
                    label = "Photo",
                    durationMs = 0L,
                    x = 0f,
                    y = 0f,
                    width = 100f,
                    height = 100f,
                ),
            )

            PaperItemsStore.pruneUnusedMediaInDir(dir, activeItems, protectedRefs = setOf("inflight.mp4"))
            assertTrue(keepActive.exists())
            assertTrue(keepPending.exists())
            assertFalse(orphan.exists())

            // Once no items or pending refs remain, the directory itself is removed.
            PaperItemsStore.pruneUnusedMediaInDir(dir, emptyList())
            assertFalse(dir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inkEntryFramingRoundTripsPayloadsLargerThan8KbAndRejectsCorruptData() {
        val largePayload = ByteArray(12_345) { (it * 31).toByte() }
        val records = listOf(
            InkEntry.RawStrokeRecord(InkEntry.PEN, 0xff302e29.toInt(), 4.5f, 0.1f, largePayload),
            InkEntry.RawStrokeRecord(InkEntry.SOFT_BRUSH, 0xff984631.toInt(), 12f, 0.15f, byteArrayOf(1, 2, 3, 4)),
        )
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { InkEntry.encodeRecords(it, records) }
        val encoded = out.toByteArray()
        assertTrue(encoded.size > 12_345)

        val decoded = InkEntry.decodeRecords(encoded)
        assertEquals(2, decoded.size)
        assertEquals(InkEntry.PEN, decoded[0].brushKind)
        assertArrayEquals(largePayload, decoded[0].payload)
        assertEquals(InkEntry.SOFT_BRUSH, decoded[1].brushKind)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded[1].payload)

        // Truncated payload must throw IOException rather than silently returning partial data.
        var threw = false
        try {
            InkEntry.decodeRecords(encoded.copyOf(encoded.size - 10))
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun brushBankRegistryIsOrderedUniqueAndRoundTrips() {
        val expectedIds = listOf(
            "pencil",
            "chinese-ink-brush",
            "calligraphy-quill",
            "airbrush",
            "charcoal",
        )
        val expectedCodes = listOf(16, 17, 18, 19, 20)

        assertEquals(expectedIds, BrushBank.all.map { it.id })
        assertEquals(expectedCodes, BrushBank.all.map { it.storageCode })
        assertEquals(BrushBank.all.size, BrushBank.all.map { it.id }.toSet().size)
        assertEquals(BrushBank.all.size, BrushBank.all.map { it.storageCode }.toSet().size)

        for (spec in BrushBank.all) {
            assertEquals(spec, BrushBank.byId(spec.id))
            assertEquals(spec, BrushBank.byStorageCode(spec.storageCode))
            assertTrue("Storage code out of 16..255 for ${spec.id}", spec.storageCode in 16..255)
            assertTrue("minSizeDp/defaultSizeDp invalid for ${spec.id}", spec.minSizeDp > 0f && spec.defaultSizeDp in spec.minSizeDp..spec.maxSizeDp)
            assertTrue("presetsDp empty for ${spec.id}", spec.presetsDp.isNotEmpty())
            for (preset in spec.presetsDp) {
                assertTrue("Preset $preset out of range for ${spec.id}", preset in spec.minSizeDp..spec.maxSizeDp)
            }
            assertTrue(
                "defaultColor for ${spec.id} must be in INK_SWATCHES",
                spec.defaultColor in BrushBank.INK_SWATCHES,
            )
        }
        assertNull(BrushBank.byId("nonexistent-brush"))
        assertNull(BrushBank.byStorageCode(99))
        assertEquals("pencil", BrushBank.defaultSpec.id)
    }

    private class FakeKeyValueStore : KeyValueStore {
        val strings = mutableMapOf<String, String>()
        val floats = mutableMapOf<String, Float>()
        val ints = mutableMapOf<String, Int>()
        val writes = mutableListOf<String>()

        override fun getString(key: String): String? = strings[key]
        override fun putString(key: String, value: String) {
            writes.add(key)
            strings[key] = value
        }

        override fun getFloat(key: String): Float? = floats[key]
        override fun putFloat(key: String, value: Float) {
            writes.add(key)
            floats[key] = value
        }

        override fun getInt(key: String): Int? = ints[key]
        override fun putInt(key: String, value: Int) {
            writes.add(key)
            ints[key] = value
        }
    }

    @Test
    fun brushBankSelectionPersistsAndFallsBackToPencil() {
        val store = FakeKeyValueStore()

        val first = BrushBankSelection(store)
        assertEquals("pencil", first.selectedId)
        assertEquals("pencil", first.selectedSpec.id)
        assertEquals(2.5f, first.sizeFor(first.selectedSpec), 0.001f)

        val quill = first.select("calligraphy-quill")
        first.setSize(quill, 18f)
        val airbrush = BrushBank.byId("airbrush")!!
        first.setSize(airbrush, 36f)

        val reloaded = BrushBankSelection(store)
        assertEquals("calligraphy-quill", reloaded.selectedId)
        assertEquals(18, reloaded.selectedSpec.storageCode)
        assertEquals(18f, reloaded.sizeFor(quill), 0.001f)
        assertEquals(36f, reloaded.sizeFor(airbrush), 0.001f)
        assertEquals(2.5f, reloaded.sizeFor(BrushBank.defaultSpec), 0.001f)

        // Unknown / removed brush id falls back to default "pencil".
        store.putString(BrushBankSelection.KEY_BANK_BRUSH_ID, "removed-legacy-brush")
        assertEquals("pencil", reloaded.selectedId)
        assertEquals(16, reloaded.selectedSpec.storageCode)
    }

    @Test
    fun brushBankSelectionClampsSizeOnWriteAndRead() {
        val store = FakeKeyValueStore()
        val selection = BrushBankSelection(store)
        val pencil = BrushBank.byId("pencil")!!

        assertEquals(pencil.minSizeDp, selection.setSize(pencil, 0.1f), 0.001f)
        assertEquals(pencil.minSizeDp, selection.sizeFor(pencil), 0.001f)

        assertEquals(pencil.maxSizeDp, selection.setSize(pencil, 99f), 0.001f)
        assertEquals(pencil.maxSizeDp, selection.sizeFor(pencil), 0.001f)

        store.floats[BrushBankSelection.sizeKey(pencil.id)] = -5f
        assertEquals(pencil.minSizeDp, selection.sizeFor(pencil), 0.001f)

        store.floats[BrushBankSelection.sizeKey(pencil.id)] = 250f
        assertEquals(pencil.maxSizeDp, selection.sizeFor(pencil), 0.001f)
    }

    @Test
    fun brushBankSelectionPersistsPerBrushColorWithOpaqueAlphaAndSelectWritesOnlyId() {
        val store = FakeKeyValueStore()
        val selection = BrushBankSelection(store)
        val pencil = BrushBank.byId("pencil")!!
        val quill = BrushBank.byId("calligraphy-quill")!!

        // Starring only writes bank_brush_id (never size or color keys).
        store.writes.clear()
        selection.select(quill.id)
        selection.select("charcoal")
        selection.select(pencil.id)
        assertEquals(
            listOf(
                BrushBankSelection.KEY_BANK_BRUSH_ID,
                BrushBankSelection.KEY_BANK_BRUSH_ID,
                BrushBankSelection.KEY_BANK_BRUSH_ID,
            ),
            store.writes,
        )

        // Per-brush color persistence + forced opaque alpha.
        val writtenColor = selection.setColor(pencil, 0x35984631)
        assertEquals(0xff984631.toInt(), writtenColor)

        val selectedB = selection.select(quill.id)
        assertEquals(quill.defaultColor, selection.colorFor(selectedB))

        val selectedA = selection.select(pencil.id)
        assertEquals(0xff984631.toInt(), selection.colorFor(selectedA))

        val reloaded = BrushBankSelection(store)
        assertEquals(0xff984631.toInt(), reloaded.colorFor(pencil))
        assertEquals(quill.defaultColor, reloaded.colorFor(quill))
    }

    @Test
    fun inkEntrySupportsBankBrushCodesAndLegacyKindsAndRejectsUnknownCode() {
        val records = listOf(
            InkEntry.RawStrokeRecord(InkEntry.PEN, 0xff283948.toInt(), 3f, 0.2f, byteArrayOf(10, 20)),
            InkEntry.RawStrokeRecord(InkEntry.SOFT_BRUSH, 0xff272320.toInt(), 12f, 0.2f, byteArrayOf(30, 40)),
            InkEntry.RawStrokeRecord(17, 0xff272320.toInt(), 10f, 0.2f, byteArrayOf(50, 60, 70)),
            InkEntry.RawStrokeRecord(20, 0xff984631.toInt(), 14f, 0.2f, byteArrayOf(80, 90)),
        )
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { InkEntry.encodeRecords(it, records) }
        val decoded = InkEntry.decodeRecords(out.toByteArray())
        assertEquals(listOf(0, 1, 17, 20), decoded.map { it.brushKind })
        assertArrayEquals(byteArrayOf(50, 60, 70), decoded[2].payload)

        // Unknown brush code must be rejected with IOException.
        val badOut = ByteArrayOutputStream()
        DataOutputStream(badOut).use {
            InkEntry.encodeRecords(
                it,
                listOf(InkEntry.RawStrokeRecord(99, 0xff000000.toInt(), 5f, 0.2f, byteArrayOf(1, 2))),
            )
        }
        var threw = false
        try {
            InkEntry.decodeRecords(badOut.toByteArray())
        } catch (_: IOException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun proceduralStampsAreDeterministicAndWithinByteBounds() {
        for (kind in ProceduralStampKind.entries) {
            val a = BrushStampGenerator.generateAlphaMask(kind, size = 32, seed = 16L)
            val b = BrushStampGenerator.generateAlphaMask(kind, size = 32, seed = 16L)
            assertEquals(32 * 32, a.size)
            assertArrayEquals(a, b)
            for (alpha in a) {
                assertTrue("Alpha out of bounds: $alpha", alpha in 0..255)
            }
        }

        // Grayscale opaque pixels convert luminance -> alpha while transparent pixels preserve alpha.
        val opaqueGray = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff808080.toInt())
        val convertedOpaque = BrushStampGenerator.grayscalePixelsToMaskArgb(opaqueGray)
        assertEquals(0x00ffffff, convertedOpaque[0])
        assertEquals(0xffffffff.toInt(), convertedOpaque[1])
        assertEquals(128, (convertedOpaque[2] ushr 24) and 0xff)

        val alphaMask = intArrayOf(0x40123456, 0x00000000)
        val preservedAlpha = BrushStampGenerator.grayscalePixelsToMaskArgb(alphaMask)
        assertEquals(0x40ffffff, preservedAlpha[0])
        assertEquals(0x00ffffff, preservedAlpha[1])
    }

    @Test
    fun lassoPointInPolygonAndItemIntersectionWorkAccurately() {
        val square = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
        assertTrue(LassoGeometry.pointInPolygon(50f, 50f, square))
        assertFalse(LassoGeometry.pointInPolygon(150f, 50f, square))
        assertFalse(LassoGeometry.pointInPolygon(-10f, 50f, square))

        // Item inside lasso
        assertTrue(LassoGeometry.itemIntersectsLasso(20f, 20f, 40f, 40f, 0f, square))
        // Item outside lasso
        assertFalse(LassoGeometry.itemIntersectsLasso(120f, 120f, 40f, 40f, 0f, square))
        // Rotated item overlapping lasso corner
        assertTrue(LassoGeometry.itemIntersectsLasso(80f, 80f, 50f, 50f, 45f, square))
    }

    @Test
    fun rotationMathAndExportFilenameSanitization() {
        val (rx, ry) = LassoGeometry.rotatePoint(10f, 0f, 0f, 0f, 90f)
        assertEquals(0f, rx, 0.01f)
        assertEquals(10f, ry, 0.01f)

        assertEquals(0f, LassoGeometry.normalizeAngle(360f), 0.01f)
        assertEquals(-90f, LassoGeometry.normalizeAngle(270f), 0.01f)
        assertEquals(180f, LassoGeometry.normalizeAngle(-180f), 0.01f)

        assertEquals(
            "diary_2026-11-26_abc123.png",
            LassoGeometry.sanitizeExportFileName("diary_2026-11-26_abc123.png", "fallback", "png"),
        )
        assertEquals(
            "my_note.webp",
            LassoGeometry.sanitizeExportFileName("  my/note.jpg  ", "fallback", "webp"),
        )
        assertEquals(
            "fallback.jpg",
            LassoGeometry.sanitizeExportFileName("   ", "fallback", "jpg"),
        )
    }

    @Test
    fun rotatedResizeGrowsOutwardAndAnchorsTopLeftAtAnyAngle() {
        // At 180 degrees, dragging bottom-right outward is negative screen dx.
        val beforeCorners = LassoGeometry.rotatedRectCorners(100f, 100f, 80f, 40f, 180f)
        val resized180 = LassoGeometry.resizeRotatedRect(
            x = 100f,
            y = 100f,
            width = 80f,
            height = 40f,
            rotationDeg = 180f,
            dx = -40f,
            dy = 0f,
            minWidth = 20f,
            maxWidth = 400f,
        )
        assertEquals(120f, resized180[2], 0.01f)
        assertEquals(60f, resized180[3], 0.01f)
        val afterCorners = LassoGeometry.rotatedRectCorners(resized180[0], resized180[1], resized180[2], resized180[3], 180f)
        // Rotated top-left corner (index 0) must remain anchored in paper space.
        assertEquals(beforeCorners[0].first, afterCorners[0].first, 0.05f)
        assertEquals(beforeCorners[0].second, afterCorners[0].second, 0.05f)

        // At 90 degrees clockwise, outward along local +X is screen +Y.
        val resized90 = LassoGeometry.resizeRotatedRect(
            x = 100f,
            y = 100f,
            width = 80f,
            height = 80f,
            rotationDeg = 90f,
            dx = 0f,
            dy = 30f,
            minWidth = 20f,
            maxWidth = 400f,
        )
        assertEquals(110f, resized90[2], 0.01f)
    }

    @Test
    fun exportDimensionsScaleLargeSelectionsWithoutClipping() {
        val normal = SelectionExporter.computeExportDimensions(400f, 300f, maxDim = 2048)
        assertEquals(2f, normal.renderScale, 0.001f)
        assertEquals(800, normal.width)
        assertEquals(600, normal.height)

        // Tall selection > 4096 paper px must scale below 0.5x so the entire height fits in 2048px.
        val tall = SelectionExporter.computeExportDimensions(1000f, 5000f, maxDim = 2048)
        assertEquals(2048f / 5000f, tall.renderScale, 0.001f)
        assertEquals(2048, tall.height)
        assertEquals(410, tall.width)
        assertTrue(tall.width <= 2048 && tall.height <= 2048)
    }

    @Test
    fun clampRotatedItemToSheetKeepsItemsReachable() {
        val (x1, y1) = LassoGeometry.clampRotatedItemToSheet(-50f, -20f, 80f, 80f, 0f, 600f, 1200f)
        assertEquals(0f, x1, 0.01f)
        assertEquals(0f, y1, 0.01f)

        val (x2, y2) = LassoGeometry.clampRotatedItemToSheet(700f, 1300f, 80f, 80f, 0f, 600f, 1200f)
        assertEquals(520f, x2, 0.01f)
        assertEquals(1120f, y2, 0.01f)
    }

    @Test
    fun clampRotatedItemToSheetUsesRotatedBounds() {
        // A 256x58 card rotated 90deg occupies 58 wide by 256 tall around its center.
        val (x, y) = LassoGeometry.clampRotatedItemToSheet(100f, 0f, 256f, 58f, 90f, 600f, 1200f)
        val b = LassoGeometry.rotatedRectBounds(x, y, 256f, 58f, 90f)
        assertEquals(0f, b[1], 0.01f)
        assertEquals(256f, b[3] - b[1], 0.01f)
        assertEquals(100f, x, 0.01f)
    }

    @Test
    fun paperItemDefaultsRotationToZeroAndComputesRotatedBounds() {
        val unrotated = PaperItem(
            id = "s1",
            kind = PaperItemKind.STICKER,
            ref = "star",
            label = "Star",
            durationMs = 0L,
            x = 10f,
            y = 20f,
            width = 100f,
            height = 40f,
        )
        assertEquals(0f, unrotated.rotation, 0.001f)

        val rotated = unrotated.copy(rotation = 90f)
        val bounds = LassoGeometry.rotatedRectBounds(
            rotated.x,
            rotated.y,
            rotated.width,
            rotated.height,
            rotated.rotation,
        )
        // Center is (60, 40); rotating 100x40 by 90 deg yields 40x100 centered at (60, 40).
        assertEquals(40f, bounds[0], 0.05f)
        assertEquals(-10f, bounds[1], 0.05f)
        assertEquals(80f, bounds[2], 0.05f)
        assertEquals(90f, bounds[3], 0.05f)
    }

    @Test
    fun groupClampShiftKeepsMixedSelectionInsideSheetWithoutSplitting() {
        val (sx1, sy1) = LassoGeometry.computeGroupClampShift(-30f, 50f, 170f, 250f, 600f, 1200f)
        assertEquals(30f, sx1, 0.01f)
        assertEquals(0f, sy1, 0.01f)

        val (sx2, sy2) = LassoGeometry.computeGroupClampShift(500f, 1100f, 650f, 1260f, 600f, 1200f)
        assertEquals(-50f, sx2, 0.01f)
        assertEquals(-60f, sy2, 0.01f)
    }

    @Test
    fun uniqueFileAvoidsOverwritingExistingExports() {
        val dir = Files.createTempDirectory("diary-export-test").toFile()
        try {
            val first = SelectionExporter.uniqueFile(dir, "diary_2026-11-27.png")
            assertEquals("diary_2026-11-27.png", first.name)
            first.writeText("1")

            val second = SelectionExporter.uniqueFile(dir, "diary_2026-11-27.png")
            assertEquals("diary_2026-11-27 (1).png", second.name)
            second.writeText("2")

            val third = SelectionExporter.uniqueFile(dir, "diary_2026-11-27.png")
            assertEquals("diary_2026-11-27 (2).png", third.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writeUniqueFileKeepsSuccessfulWritesAndRemovesFailedOnes() {
        val dir = Files.createTempDirectory("diary-export-write-test").toFile()
        try {
            File(dir, "shot.png").writeText("old")
            val saved = SelectionExporter.writeUniqueFile(dir, "shot.png") { out ->
                out.write(1)
                true
            }
            assertEquals("shot (1).png", saved.name)
            assertEquals(1L, saved.length())

            val compressFailure = runCatching {
                SelectionExporter.writeUniqueFile(dir, "shot.png") { false }
            }
            assertTrue(compressFailure.exceptionOrNull() is IOException)

            val writeFailure = runCatching {
                SelectionExporter.writeUniqueFile(dir, "shot.png") { out ->
                    out.write(1)
                    throw IOException("disk full")
                }
            }
            assertTrue(writeFailure.exceptionOrNull() is IOException)
            assertEquals(setOf("shot.png", "shot (1).png"), dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun itemsJsonRoundTripsRotationAndAcceptsFilesWithoutIt() {
        val item = PaperItem(
            id = "a",
            kind = PaperItemKind.PHOTO,
            ref = "a.webp",
            label = "Trip",
            durationMs = 0L,
            x = 12f,
            y = 34.5f,
            width = 200f,
            height = 120f,
            score = 0,
            rotation = -37.5f,
        )
        val decoded = PaperItemsStore.decodeItemsJson(PaperItemsStore.encodeItemsJson(listOf(item)))
        assertEquals(listOf(item), decoded)

        val legacy = """[{"id":"b","kind":"sticker","ref":"star","label":"Star","x":1,"y":2,"width":3,"height":4}]"""
        val legacyItem = PaperItemsStore.decodeItemsJson(legacy).single()
        assertEquals(0f, legacyItem.rotation, 0.001f)
        assertEquals(0, legacyItem.score)
        assertEquals(0L, legacyItem.durationMs)
    }

    @Test
    fun itemsJsonRejectsUnknownKinds() {
        val json = """[{"id":"c","kind":"hologram","ref":"x","x":0,"y":0,"width":1,"height":1}]"""
        assertTrue(runCatching { PaperItemsStore.decodeItemsJson(json) }.exceptionOrNull() is IOException)
    }

    @Test
    fun exifOrientationMapsToRotationAndFlip() {
        assertNull(PaperItemsStore.exifTransform(ExifInterface.ORIENTATION_NORMAL))
        assertNull(PaperItemsStore.exifTransform(ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(90f to false, PaperItemsStore.exifTransform(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180f to false, PaperItemsStore.exifTransform(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270f to false, PaperItemsStore.exifTransform(ExifInterface.ORIENTATION_ROTATE_270))
        assertEquals(0f to true, PaperItemsStore.exifTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(180f to true, PaperItemsStore.exifTransform(ExifInterface.ORIENTATION_FLIP_VERTICAL))
        assertEquals(90f to true, PaperItemsStore.exifTransform(ExifInterface.ORIENTATION_TRANSPOSE))
        assertEquals(-90f to true, PaperItemsStore.exifTransform(ExifInterface.ORIENTATION_TRANSVERSE))
    }

    private val finger = 1
    private val stylus = 2

    @Test
    fun twoFingerTransformRequiresTwoFingersAndNoPenGesture() {
        assertTrue(LassoGeometry.canStartTwoFingerTransform(intArrayOf(finger, finger), true, false, false))
        // Palm (finger) landing while the S Pen is down must not start the transform.
        assertFalse(LassoGeometry.canStartTwoFingerTransform(intArrayOf(stylus, finger), true, false, false))
        assertFalse(LassoGeometry.canStartTwoFingerTransform(intArrayOf(finger, stylus), true, false, false))
        assertFalse(LassoGeometry.canStartTwoFingerTransform(intArrayOf(finger, finger), true, false, true))
        assertFalse(LassoGeometry.canStartTwoFingerTransform(intArrayOf(finger, finger), true, true, false))
        assertFalse(LassoGeometry.canStartTwoFingerTransform(intArrayOf(finger, finger), false, false, false))
        assertFalse(LassoGeometry.canStartTwoFingerTransform(intArrayOf(finger), true, false, false))
    }

    @Test
    fun restingFingersAreNotAMeaningfulTransform() {
        assertFalse(LassoGeometry.isTransformMeaningful(1f, 1.005f, 0.2f))
        assertTrue(LassoGeometry.isTransformMeaningful(12f, 1f, 0f))
        assertTrue(LassoGeometry.isTransformMeaningful(0f, 1.1f, 0f))
        assertTrue(LassoGeometry.isTransformMeaningful(0f, 1f, 5f))
        assertTrue(LassoGeometry.isIdentityTransform(0.1f, -0.1f, 1.001f, 0.1f))
        assertFalse(LassoGeometry.isIdentityTransform(3f, 0f, 1f, 0f))
        assertFalse(LassoGeometry.isIdentityTransform(0f, 0f, 1.05f, 0f))
        assertFalse(LassoGeometry.isIdentityTransform(0f, 0f, 1f, 2f))
    }

    @Test
    fun areaDragScaleUsesDominantAxis() {
        // Wide selection follows horizontal drag.
        assertEquals(1.5f, LassoGeometry.areaDragScale(200f, 100f, 100f, 0f), 0.001f)
        // Tall narrow selection follows vertical drag; horizontal does nothing.
        assertEquals(1.5f, LassoGeometry.areaDragScale(50f, 200f, 0f, 100f), 0.001f)
        assertEquals(1f, LassoGeometry.areaDragScale(50f, 200f, 100f, 0f), 0.001f)
        // Clamped.
        assertEquals(0.25f, LassoGeometry.areaDragScale(100f, 100f, -1000f, 0f), 0.001f)
        assertEquals(4f, LassoGeometry.areaDragScale(100f, 100f, 1000f, 0f), 0.001f)
    }

    @Test
    fun fastStrokeCrossingNarrowLassoIsDetectedBySegments() {
        val narrow = listOf(40f to 0f, 60f to 0f, 60f to 100f, 40f to 100f)
        // Samples sit on both sides of the lasso, none inside.
        val samples = listOf(10f to 50f, 90f to 50f)
        assertFalse(samples.any { LassoGeometry.pointInPolygon(it.first, it.second, narrow) })
        assertTrue(LassoGeometry.polylineIntersectsPolygon(samples, narrow))
        // A polyline entirely outside does not hit.
        assertFalse(LassoGeometry.polylineIntersectsPolygon(listOf(0f to 150f, 100f to 150f), narrow))
        assertFalse(LassoGeometry.polylineIntersectsPolygon(listOf(10f to 50f), narrow))
    }

    @Test
    fun orientationWrapStaysInHalfOpenRange() {
        val twoPi = (2.0 * Math.PI).toFloat()
        for (v in listOf(0f, -1e-9f, -1e-12f, -0.5f, 7.5f, twoPi, -twoPi, 3f * twoPi + 0.1f)) {
            val o = LassoGeometry.wrapOrientation(v)
            assertTrue("wrap($v)=$o", o >= 0f && o < twoPi)
        }
        assertEquals(0.5f, LassoGeometry.wrapOrientation(twoPi + 0.5f), 0.001f)
    }

    @Test
    fun groupScaleClampCapsMixedSelectionAndSkipsZeroWidths() {
        // 300px wide item cannot grow past 600px (2x); 100px item could grow 6x, so group caps at 2x.
        assertEquals(2f, LassoGeometry.clampGroupScale(4f, listOf(100f, 300f), minWidth = 44f, maxWidth = 600f), 0.001f)
        // 88px item cannot shrink below 44px (0.5x).
        assertEquals(0.5f, LassoGeometry.clampGroupScale(0.25f, listOf(88f, 300f), minWidth = 44f, maxWidth = 600f), 0.001f)
        // Zero/negative widths are skipped safely (no division by zero / Infinity).
        assertEquals(2f, LassoGeometry.clampGroupScale(3f, listOf(0f, -10f, 300f), minWidth = 44f, maxWidth = 600f), 0.001f)
        // Strokes-only selection (no resizable items) preserves the requested scale.
        assertEquals(3.5f, LassoGeometry.clampGroupScale(3.5f, emptyList(), minWidth = 44f, maxWidth = 600f), 0.001f)
    }

    @Test
    fun diaryPluginBitmaskMatchesRustFlags() {
        assertFalse(DiarySettings.isPluginEnabled(0, DiaryPlugin.HANZI))
        assertFalse(DiarySettings.isPluginEnabled(0, DiaryPlugin.CONTOUR_3D))
        assertTrue(DiarySettings.isPluginEnabled(1, DiaryPlugin.HANZI))
        assertFalse(DiarySettings.isPluginEnabled(1, DiaryPlugin.CONTOUR_3D))
        assertFalse(DiarySettings.isPluginEnabled(2, DiaryPlugin.HANZI))
        assertTrue(DiarySettings.isPluginEnabled(2, DiaryPlugin.CONTOUR_3D))
        assertTrue(DiarySettings.isPluginEnabled(3, DiaryPlugin.HANZI))
        assertTrue(DiarySettings.isPluginEnabled(3, DiaryPlugin.CONTOUR_3D))
    }

    @Test
    fun contourDocumentRoundTripsJsonAndStyle() {
        val doc = ContourDocument(
            style = ContourRenderStyle.BLOCK,
            strokes = listOf(
                ContourStroke(listOf(0.2f to 0.2f, 0.8f to 0.2f, 0.8f to 0.8f, 0.2f to 0.8f, 0.2f to 0.2f), null),
                ContourStroke(listOf(0.4f to 0.4f, 0.6f to 0.4f, 0.6f to 0.6f, 0.4f to 0.6f, 0.4f to 0.4f), 3),
            ),
            yawRad = -1.75f,
            pitchRad = 1.05f,
        )
        val decoded = ContourDocument.fromJson(doc.toJson())
        assertEquals(ContourRenderStyle.BLOCK, decoded.style)
        assertEquals(2, decoded.strokes.size)
        assertNull(decoded.strokes[0].explicitLevel)
        assertEquals(3, decoded.strokes[1].explicitLevel)
        assertEquals(5, decoded.strokes[0].points.size)
        assertEquals(-1.75f, decoded.yawRad, 0.001f)
        assertEquals(1.05f, decoded.pitchRad, 0.001f)

        // Legacy JSON without yaw/pitch falls back to defaults, and pitch never drops below surface.
        val legacy = ContourDocument.fromJson("""{"version":1,"style":"smooth","strokes":[],"pitch":-0.8}""")
        assertEquals(ContourDocument.DEFAULT_YAW_RAD, legacy.yawRad, 0.001f)
        assertEquals(ContourDocument.MIN_PITCH_RAD, legacy.pitchRad, 0.001f)
        assertEquals(ContourDocument.MIN_PITCH_RAD, ContourDocument.clampPitch(-1.5f), 0.001f)
        assertEquals(ContourDocument.MAX_PITCH_RAD, ContourDocument.clampPitch(3.0f), 0.001f)
    }

    @Test
    fun contourTerrainEngineInfersNestedLevelsAndBuildsRisingHeightmap() {
        val outerHill = ContourStroke(
            listOf(0.15f to 0.20f, 0.55f to 0.20f, 0.55f to 0.80f, 0.15f to 0.80f, 0.15f to 0.20f),
        )
        val innerPeak = ContourStroke(
            listOf(0.25f to 0.35f, 0.45f to 0.35f, 0.45f to 0.65f, 0.25f to 0.65f, 0.25f to 0.35f),
        )
        val sideHill = ContourStroke(
            listOf(0.68f to 0.30f, 0.88f to 0.30f, 0.88f to 0.70f, 0.68f to 0.70f, 0.68f to 0.30f),
        )

        val levels = ContourTerrainEngine.resolveLevels(listOf(outerHill, innerPeak, sideHill))
        assertEquals(listOf(1, 2, 1), levels)

        val hm = ContourTerrainEngine.buildHeightmap(listOf(outerHill, innerPeak, sideHill), cols = 40, rows = 30)
        assertEquals(2, hm.maxLevel)
        // Outer border is anchored to base plane 0.
        assertEquals(0f, hm.heightAt(0, 0), 0.001f)
        // Center of innerPeak (nx=0.35, ny=0.50 -> gx=14, gy=15) rises above level 2 due to summit dome.
        assertTrue("Expected summit > 2.0, got ${hm.heightAt(14, 15)}", hm.heightAt(14, 15) > 2.0f)
        // Center of sideHill (nx=0.78, ny=0.50 -> gx=31, gy=15) rises above level 1.
        assertTrue("Expected side hill > 1.0, got ${hm.heightAt(31, 15)}", hm.heightAt(31, 15) > 1.0f)

        val voxels = ContourTerrainEngine.quantizeForBlocks(hm, blockCols = 16, blockRows = 12)
        assertEquals(0, voxels.heightAt(0, 0))
        assertTrue("Expected positive voxel peak", voxels.maxBlocks >= 2)
    }

    @Test
    fun contourItemRoundTripsItemsJsonAndIsKeptByMediaPruning() {
        val dir = Files.createTempDirectory("diary-contour-prune").toFile()
        try {
            val activeFile = File(dir, "hill.contour.json").apply { writeText("""{"version":1,"style":"smooth","strokes":[]}""") }
            val oldRevision = File(dir, "old.contour.json").apply { writeText("""{"version":1,"style":"block","strokes":[]}""") }
            val item = PaperItem(
                id = "c1",
                kind = PaperItemKind.CONTOUR,
                ref = "hill.contour.json",
                label = "smooth",
                durationMs = 3L,
                x = 20f,
                y = 40f,
                width = 240f,
                height = 180f,
            )
            val decoded = PaperItemsStore.decodeItemsJson(PaperItemsStore.encodeItemsJson(listOf(item)))
            assertEquals(listOf(item), decoded)

            PaperItemsStore.pruneUnusedMediaInDir(dir, listOf(item))
            assertTrue(activeFile.exists())
            assertFalse(oldRevision.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun contourTerrainEngineHandlesDegenerateAndZeroLevelStrokesSafely() {
        val empty = ContourStroke(emptyList())
        val singlePoint = ContourStroke(listOf(0.5f to 0.5f))
        val zeroLoop = ContourStroke(
            listOf(0.2f to 0.2f, 0.8f to 0.2f, 0.8f to 0.8f, 0.2f to 0.8f, 0.2f to 0.2f),
            explicitLevel = 0,
        )

        val hm = ContourTerrainEngine.buildHeightmap(listOf(empty, singlePoint, zeroLoop), cols = 16, rows = 12)
        assertEquals(0f, hm.maxHeight, 0.001f)

        val voxels = ContourTerrainEngine.quantizeForBlocks(hm, blockCols = 8, blockRows = 6)
        assertEquals(0, voxels.maxBlocks)
    }

    @Test
    fun contour3DRendererPainterOrderSortsFarthestToClosestFromEye() {
        assertEquals(listOf(0, 1, 2, 3), Contour3DRenderer.painterOrder(4, 2.0f).toList())
        assertEquals(listOf(3, 2, 1, 0), Contour3DRenderer.painterOrder(4, -1.0f).toList())
        // Overhead near center: cells on both sides converge toward 0.5f.
        val overhead = Contour3DRenderer.painterOrder(4, 0.5f).toList()
        assertEquals(listOf(0, 1, 3, 2), overhead)
    }
}
