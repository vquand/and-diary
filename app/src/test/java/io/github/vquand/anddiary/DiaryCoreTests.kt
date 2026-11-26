package io.github.vquand.anddiary

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
    fun clampItemToSheetKeepsItemsReachable() {
        val (x1, y1) = LassoGeometry.clampItemToSheet(-50f, -20f, 80f, 80f, 600f, 1200f)
        assertEquals(0f, x1, 0.01f)
        assertEquals(0f, y1, 0.01f)

        val (x2, y2) = LassoGeometry.clampItemToSheet(700f, 1300f, 80f, 80f, 600f, 1200f)
        assertEquals(520f, x2, 0.01f)
        assertEquals(1120f, y2, 0.01f)
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
}
