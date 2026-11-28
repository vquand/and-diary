package io.github.vquand.anddiary

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class DayStateTests {
    @Before
    fun setUp() {
        DayState.resetForTests()
    }

    @After
    fun tearDown() {
        DayState.resetForTests()
    }

    @Test
    fun registryReturnsSingleStatePerDateAndResetsCleanly() {
        val d1 = LocalDate.of(2026, 11, 28)
        val d2 = LocalDate.of(2026, 11, 29)
        val s1 = DayState.of(d1)
        assertSame(s1, DayState.of(d1))
        assertNotSame(s1, DayState.of(d2))
        assertEquals(0, s1.clearGen.get())
        assertEquals(0L, s1.diskRev.get())

        DayState.resetForTests()
        assertNotSame(s1, DayState.of(d1))
    }

    @Test
    fun protectAndReleaseAreRefCountedAndNeverDropBelowZero() {
        val state = DayState.of(LocalDate.of(2026, 11, 28))

        state.release("missing.webp")
        assertTrue(state.protectedSnapshot().isEmpty())

        state.protect("a.webp")
        state.protect("a.webp")
        state.protect("b.mp4")
        assertEquals(setOf("a.webp", "b.mp4"), state.protectedSnapshot())

        state.release("a.webp")
        assertEquals(setOf("a.webp", "b.mp4"), state.protectedSnapshot())

        state.release("a.webp")
        assertEquals(setOf("b.mp4"), state.protectedSnapshot())

        // Extra releases must not make the count negative.
        state.release("a.webp")
        state.release("a.webp")
        assertEquals(setOf("b.mp4"), state.protectedSnapshot())

        // Re-protecting after extra releases starts back at count 1.
        state.protect("a.webp")
        assertEquals(setOf("a.webp", "b.mp4"), state.protectedSnapshot())
        state.release("a.webp")
        assertEquals(setOf("b.mp4"), state.protectedSnapshot())
    }

    @Test
    fun pruneNeverDeletesProtectedFileCreatedConcurrently() {
        val date = LocalDate.of(2026, 11, 28)
        val dir = Files.createTempDirectory("daystate-concurrent-prune").toFile()
        try {
            for (iteration in 0 until 200) {
                val state = DayState.of(date)
                val ref = "inflight-$iteration.webp"
                val file = File(dir, ref)
                val startLatch = CountDownLatch(1)
                val doneLatch = CountDownLatch(2)
                var threadError: Throwable? = null

                val importer = thread {
                    try {
                        startLatch.await()
                        state.lock.withLock {
                            state.protect(ref)
                            dir.mkdirs()
                            file.writeText("payload-$iteration")
                        }
                    } catch (t: Throwable) {
                        threadError = t
                    } finally {
                        doneLatch.countDown()
                    }
                }
                val pruner = thread {
                    try {
                        startLatch.await()
                        MediaPrune.prune(state, dir, { emptyList() }, emptySet())
                    } catch (t: Throwable) {
                        threadError = t
                    } finally {
                        doneLatch.countDown()
                    }
                }

                startLatch.countDown()
                assertTrue("Timed out on iteration $iteration", doneLatch.await(5, TimeUnit.SECONDS))
                importer.join(1000)
                pruner.join(1000)
                if (threadError != null) throw AssertionError("Worker failed", threadError)

                assertTrue("Protected file deleted on iteration $iteration", file.exists())
                assertTrue("Media dir deleted while protected on iteration $iteration", dir.exists())

                state.release(ref)
                file.delete()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneKeepsRetainedRefsAndAtomicSiblingsOfKeptRefs() {
        val state = DayState.of(LocalDate.of(2026, 11, 28))
        val dir = Files.createTempDirectory("daystate-siblings").toFile()
        try {
            val active = File(dir, "active.webp").apply { writeText("active") }
            val activeNew = File(dir, "active.webp.new").apply { writeText("new") }
            val activeBak = File(dir, "active.webp.bak").apply { writeText("bak") }
            val retained = File(dir, "retained.mp4").apply { writeText("ret") }
            val retainedNew = File(dir, "retained.mp4.new").apply { writeText("ret-new") }
            val protectedFile = File(dir, "inflight.contour.json").apply { writeText("{}") }
            val protectedBak = File(dir, "inflight.contour.json.bak").apply { writeText("{}") }
            val orphan = File(dir, "orphan.webp").apply { writeText("old") }
            val orphanNew = File(dir, "orphan.webp.new").apply { writeText("old-new") }
            val orphanBak = File(dir, "orphan.webp.bak").apply { writeText("old-bak") }

            val activeItem = PaperItem(
                id = "1",
                kind = PaperItemKind.PHOTO,
                ref = "active.webp",
                label = "Photo",
                durationMs = 0L,
                x = 0f,
                y = 0f,
                width = 100f,
                height = 100f,
            )
            state.protect("inflight.contour.json")

            MediaPrune.prune(state, dir, { listOf(activeItem) }, setOf("retained.mp4"))

            assertTrue(active.exists())
            assertTrue(activeNew.exists())
            assertTrue(activeBak.exists())
            assertTrue(retained.exists())
            assertTrue(retainedNew.exists())
            assertTrue(protectedFile.exists())
            assertTrue(protectedBak.exists())
            assertFalse(orphan.exists())
            assertFalse(orphanNew.exists())
            assertFalse(orphanBak.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneSkipsEntirelyWhenReadItemsReturnsNullOrThrows() {
        val state = DayState.of(LocalDate.of(2026, 11, 28))
        val dir = Files.createTempDirectory("daystate-null-read").toFile()
        try {
            val orphan = File(dir, "orphan.webp").apply { writeText("keep-on-error") }

            MediaPrune.prune(state, dir, { null }, emptySet())
            assertTrue(orphan.exists())
            assertTrue(dir.exists())

            MediaPrune.prune(state, dir, { throw IOException("corrupt items") }, emptySet())
            assertTrue(orphan.exists())
            assertTrue(dir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneWithEmptyKeepSetDeletesOrphansExceptProtectedInFlightFile() {
        val state = DayState.of(LocalDate.of(2026, 11, 28))
        val dir = Files.createTempDirectory("daystate-empty-keep").toFile()
        try {
            val orphanA = File(dir, "orphan-a.webp").apply { writeText("a") }
            val orphanB = File(dir, "orphan-b.m4a").apply { writeText("b") }
            val inflight = File(dir, "inflight.webp").apply { writeText("inflight") }
            state.protect("inflight.webp")

            MediaPrune.prune(state, dir, { emptyList() }, emptySet())

            assertFalse(orphanA.exists())
            assertFalse(orphanB.exists())
            assertTrue(inflight.exists())
            assertTrue(dir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneDoesNotDeleteEmptyDirectoryWhileARefIsProtected() {
        val state = DayState.of(LocalDate.of(2026, 11, 28))
        val dir = Files.createTempDirectory("daystate-empty-dir-protected").toFile()
        try {
            state.protect("pending.webp")
            MediaPrune.prune(state, dir, { emptyList() }, emptySet())
            assertTrue(dir.exists())

            state.release("pending.webp")
            MediaPrune.prune(state, dir, { emptyList() }, emptySet())
            assertFalse(dir.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
