package io.github.vquand.anddiary

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class ContourSaveRegistryTests {
    private val day = LocalDate.of(2026, 11, 28)
    private val other = LocalDate.of(2026, 11, 29)

    @Before fun setUp() = DayState.resetForTests()
    @After fun tearDown() = DayState.resetForTests()

    @Test
    fun newerTokenSupersedesOlderAndKeepsLatestDocument() {
        val reg = ContourSaveRegistry<String>()
        val key = day to "item"
        reg.begin(key, 1L, "first")
        reg.begin(key, 2L, "second")
        assertFalse(reg.isActive(key, 1L))
        assertTrue(reg.isActive(key, 2L))
        assertEquals("second", reg.pendingDoc(key))
        // The superseded save finishing must not drop the newer document.
        assertFalse(reg.finish(key, 1L))
        assertEquals("second", reg.pendingDoc(key))
        assertTrue(reg.finish(key, 2L))
        assertNull(reg.pendingDoc(key))
    }

    @Test
    fun tokensSurviveOtherDatesAndClearDateOnlyTouchesThatDate() {
        val reg = ContourSaveRegistry<String>()
        reg.begin(day to "a", 1L, "doc-a")
        reg.begin(other to "b", 2L, "doc-b")
        reg.clearDate(day)
        assertFalse(reg.isActive(day to "a", 1L))
        assertNull(reg.pendingDoc(day to "a"))
        assertTrue(reg.isActive(other to "b", 2L))
        assertEquals("doc-b", reg.pendingDoc(other to "b"))
    }

    @Test
    fun cancelOnUndoDeleteInvalidatesTheInFlightSave() {
        val reg = ContourSaveRegistry<String>()
        val key = day to "item"
        reg.begin(key, 5L, "doc")
        reg.cancel(day, "item")
        assertFalse(reg.isActive(key, 5L))
        assertNull(reg.pendingDoc(key))
        assertFalse(reg.finish(key, 5L))
    }

    @Test
    fun saveWithoutTokenIsAlwaysActive() {
        val reg = ContourSaveRegistry<String>()
        assertTrue(reg.isActive(null, null))
    }

    /** clearDay clears and bumps the generation in one critical section, so an off-sheet merge can never land in between. */
    @Test
    fun clearAndGenerationBumpAreAtomicAgainstMerges() {
        repeat(100) {
            DayState.resetForTests()
            val state = DayState.of(day)
            val disk = mutableListOf<PaperItem>()
            val startGen = state.clearGen.get()
            val newItem = PaperItem(
                id = "x", kind = PaperItemKind.PHOTO, ref = "x.webp", label = "", durationMs = 0L,
                x = 0f, y = 0f, width = 1f, height = 1f,
            )
            val go = CountDownLatch(1)
            val merged = AtomicInteger()
            val merger = thread {
                go.await()
                val outcome = DayRouting.mergeItemToDiskUnderLock(
                    state = state, startGen = startGen, isTokenStillActive = { true },
                    existingItemId = null, newItem = newItem,
                    readItems = { synchronized(disk) { disk.toList() } },
                    writeItems = { synchronized(disk) { disk.clear(); disk.addAll(it) } },
                )
                if (outcome == DiskMergeOutcome.MERGED) merged.incrementAndGet()
            }
            go.countDown()
            state.lock.withLock {
                synchronized(disk) { disk.clear() } // the clear
                if (DayRouting.shouldBumpClearGen(true)) state.clearGen.incrementAndGet()
            }
            merger.join()
            // Either the merge ran entirely before the clear (then it was wiped) or it was discarded afterwards.
            assertTrue("a merged item must never survive a clear", synchronized(disk) { disk.isEmpty() })
        }
    }

    @Test
    fun loadStartedBeforeAnOffSheetMergeIsToldToReload() {
        val state = DayState.of(day)
        val rev = state.diskRev.get()
        DayRouting.mergeItemToDiskUnderLock(
            state = state, startGen = state.clearGen.get(), isTokenStillActive = { true },
            existingItemId = null,
            newItem = PaperItem("y", PaperItemKind.PHOTO, "y.webp", "", 0L, 0f, 0f, 1f, 1f),
            readItems = { emptyList() }, writeItems = { },
        )
        assertEquals(
            LoadDecision.RELOAD,
            DayRouting.acceptLoadResult(1L, 1L, true, rev, state.diskRev.get()),
        )
    }
}
