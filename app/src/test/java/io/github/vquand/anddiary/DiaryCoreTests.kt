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
                StickerSpec("capybara/happy", "capybara", "", "Capybara Happy", "Happy smiling capybara", listOf("happy", "chubby"), "sheets/capybara.png", 4, 3, 0, 0),
                StickerSpec("rabbit/happy", "rabbit", "", "Rabbit Happy", "Happy lop-eared rabbit", listOf("happy", "lop"), "sheets/rabbit.png", 4, 3, 0, 0),
                StickerSpec("dog/samoyed_sleepy", "dog", "", "Samoyed Sleepy", "Sleepy fluffy samoyed", listOf("sleepy", "samoyed"), "sheets/dog.png", 4, 3, 1, 1),
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
}
