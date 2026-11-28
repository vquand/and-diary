package io.github.vquand.anddiary

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class DayRoutingTests {
    @Before
    fun setUp() {
        DayState.resetForTests()
    }

    @After
    fun tearDown() {
        DayState.resetForTests()
    }

    // --- routeCommit truth table ---

    @Test
    fun routeCommit_sheetShowsDate_returnsApply() {
        assertEquals(
            CommitRoute.APPLY,
            DayRouting.routeCommit(
                genValid = true,
                tokenValid = true,
                sheetShowsDate = true,
                sheetLoading = false,
                sheetReadable = true,
            ),
        )
    }

    @Test
    fun routeCommit_loading_returnsQueueUntilLoaded() {
        assertEquals(
            CommitRoute.QUEUE_UNTIL_LOADED,
            DayRouting.routeCommit(
                genValid = true,
                tokenValid = true,
                sheetShowsDate = true,
                sheetLoading = true,
                sheetReadable = false,
            ),
        )
    }

    @Test
    fun routeCommit_unreadable_returnsErrorUnreadable() {
        assertEquals(
            CommitRoute.ERROR_UNREADABLE,
            DayRouting.routeCommit(
                genValid = true,
                tokenValid = true,
                sheetShowsDate = true,
                sheetLoading = false,
                sheetReadable = false,
            ),
        )
    }

    @Test
    fun routeCommit_noSheet_returnsDisk() {
        assertEquals(
            CommitRoute.DISK,
            DayRouting.routeCommit(
                genValid = true,
                tokenValid = true,
                sheetShowsDate = false,
                sheetLoading = false,
                sheetReadable = false,
            ),
        )
    }

    @Test
    fun routeCommit_staleGen_returnsDiscard() {
        assertEquals(
            CommitRoute.DISCARD,
            DayRouting.routeCommit(
                genValid = false,
                tokenValid = true,
                sheetShowsDate = true,
                sheetLoading = false,
                sheetReadable = true,
            ),
        )
    }

    @Test
    fun routeCommit_supersededToken_returnsDiscard() {
        assertEquals(
            CommitRoute.DISCARD,
            DayRouting.routeCommit(
                genValid = true,
                tokenValid = false,
                sheetShowsDate = true,
                sheetLoading = false,
                sheetReadable = true,
            ),
        )
    }

    @Test
    fun routeCommit_discardWinsOverEverything() {
        // Stale gen on a live, readable, non-loading sheet → still DISCARD
        assertEquals(
            CommitRoute.DISCARD,
            DayRouting.routeCommit(
                genValid = false,
                tokenValid = false,
                sheetShowsDate = true,
                sheetLoading = false,
                sheetReadable = true,
            ),
        )
        // Stale gen with no sheet → still DISCARD (not DISK)
        assertEquals(
            CommitRoute.DISCARD,
            DayRouting.routeCommit(
                genValid = false,
                tokenValid = true,
                sheetShowsDate = false,
                sheetLoading = false,
                sheetReadable = false,
            ),
        )
    }

    // --- acceptLoadResult ---

    @Test
    fun acceptLoadResult_staleSeq_returnsIgnore() {
        assertEquals(
            LoadDecision.IGNORE,
            DayRouting.acceptLoadResult(
                resultSeq = 1L,
                currentSeq = 2L,
                sameDateAndStore = true,
                loadedRev = 0L,
                currentRev = 0L,
            ),
        )
    }

    @Test
    fun acceptLoadResult_otherDateOrStore_returnsIgnore() {
        assertEquals(
            LoadDecision.IGNORE,
            DayRouting.acceptLoadResult(
                resultSeq = 1L,
                currentSeq = 1L,
                sameDateAndStore = false,
                loadedRev = 0L,
                currentRev = 0L,
            ),
        )
    }

    @Test
    fun acceptLoadResult_changedRev_returnsReload() {
        assertEquals(
            LoadDecision.RELOAD,
            DayRouting.acceptLoadResult(
                resultSeq = 1L,
                currentSeq = 1L,
                sameDateAndStore = true,
                loadedRev = 0L,
                currentRev = 1L,
            ),
        )
    }

    @Test
    fun acceptLoadResult_match_returnsApply() {
        assertEquals(
            LoadDecision.APPLY,
            DayRouting.acceptLoadResult(
                resultSeq = 1L,
                currentSeq = 1L,
                sameDateAndStore = true,
                loadedRev = 0L,
                currentRev = 0L,
            ),
        )
    }

    // --- shouldBumpClearGen ---

    @Test
    fun shouldBumpClearGen_onSuccess() {
        assertTrue(DayRouting.shouldBumpClearGen(true))
    }

    @Test
    fun shouldBumpClearGen_onFailure() {
        assertFalse(DayRouting.shouldBumpClearGen(false))
    }

    // --- DayState-level simulation ---

    @Test
    fun diskMergeUnderLock_bumpsDiskRev_loadGetsReload() {
        val date = LocalDate.of(2026, 11, 28)
        val state = DayState.of(date)
        val items = mutableListOf<PaperItem>()
        val newItem = PaperItem(
            id = "i1", kind = PaperItemKind.PHOTO, ref = "photo.webp",
            label = "Photo", durationMs = 0, x = 0f, y = 0f, width = 100f, height = 100f,
        )

        // A load reads diskRev before the merge
        val revBeforeMerge = state.diskRev.get()

        // Merge bumps diskRev
        val outcome = DayRouting.mergeItemToDiskUnderLock(
            state = state,
            startGen = state.clearGen.get(),
            isTokenStillActive = { true },
            existingItemId = null,
            newItem = newItem,
            readItems = { items.toList() },
            writeItems = { items.clear(); items.addAll(it) },
        )
        assertEquals(DiskMergeOutcome.MERGED, outcome)
        assertEquals(1, items.size)
        assertEquals(1L, state.diskRev.get())

        // Load that read the old rev gets RELOAD
        val decision = DayRouting.acceptLoadResult(
            resultSeq = 1L,
            currentSeq = 1L,
            sameDateAndStore = true,
            loadedRev = revBeforeMerge,
            currentRev = state.diskRev.get(),
        )
        assertEquals(LoadDecision.RELOAD, decision)
    }

    @Test
    fun clearGenBump_causesDiscardForOldMerge() {
        val date = LocalDate.of(2026, 11, 28)
        val state = DayState.of(date)
        val items = mutableListOf<PaperItem>()
        val newItem = PaperItem(
            id = "i1", kind = PaperItemKind.PHOTO, ref = "photo.webp",
            label = "Photo", durationMs = 0, x = 0f, y = 0f, width = 100f, height = 100f,
        )

        val startGen = state.clearGen.get()
        // Simulate a successful clear: bump clearGen
        state.clearGen.incrementAndGet()

        // Merge task whose startGen != clearGen writes nothing
        val outcome = DayRouting.mergeItemToDiskUnderLock(
            state = state,
            startGen = startGen,
            isTokenStillActive = { true },
            existingItemId = null,
            newItem = newItem,
            readItems = { items.toList() },
            writeItems = { items.clear(); items.addAll(it) },
        )
        assertEquals(DiskMergeOutcome.DISCARDED, outcome)
        assertTrue(items.isEmpty())
    }

    @Test
    fun clearGenBumpOnlyOnSuccess() {
        val state = DayState.of(LocalDate.of(2026, 11, 28))
        val original = state.clearGen.get()

        // Success
        if (DayRouting.shouldBumpClearGen(true)) {
            state.clearGen.incrementAndGet()
        }
        assertEquals(original + 1, state.clearGen.get())

        // Failure does not bump
        val afterBump = state.clearGen.get()
        if (DayRouting.shouldBumpClearGen(false)) {
            state.clearGen.incrementAndGet()
        }
        assertEquals(afterBump, state.clearGen.get())
    }

    @Test
    fun mergeWithInactiveToken_discards() {
        val date = LocalDate.of(2026, 11, 28)
        val state = DayState.of(date)
        val items = mutableListOf<PaperItem>()
        val newItem = PaperItem(
            id = "i1", kind = PaperItemKind.PHOTO, ref = "photo.webp",
            label = "Photo", durationMs = 0, x = 0f, y = 0f, width = 100f, height = 100f,
        )

        val outcome = DayRouting.mergeItemToDiskUnderLock(
            state = state,
            startGen = state.clearGen.get(),
            isTokenStillActive = { false },
            existingItemId = null,
            newItem = newItem,
            readItems = { items.toList() },
            writeItems = { items.clear(); items.addAll(it) },
        )
        assertEquals(DiskMergeOutcome.DISCARDED, outcome)
        assertTrue(items.isEmpty())
    }

    @Test
    fun mergeUpdatesExistingItem() {
        val date = LocalDate.of(2026, 11, 28)
        val state = DayState.of(date)
        val existing = PaperItem(
            id = "i1", kind = PaperItemKind.CONTOUR, ref = "old.contour.json",
            label = "smooth", durationMs = 3, x = 10f, y = 10f, width = 200f, height = 150f,
        )
        val items = mutableListOf(existing)
        val updated = existing.copy(ref = "new.contour.json", label = "block", durationMs = 5)

        val outcome = DayRouting.mergeItemToDiskUnderLock(
            state = state,
            startGen = state.clearGen.get(),
            isTokenStillActive = { true },
            existingItemId = "i1",
            newItem = updated,
            readItems = { items.toList() },
            writeItems = { items.clear(); items.addAll(it) },
        )
        assertEquals(DiskMergeOutcome.MERGED, outcome)
        assertEquals(1, items.size)
        assertEquals("new.contour.json", items[0].ref)
        assertEquals("block", items[0].label)
    }

    @Test
    fun mergeDiscardsWhenExistingItemIdNotFound() {
        val date = LocalDate.of(2026, 11, 28)
        val state = DayState.of(date)
        val items = mutableListOf<PaperItem>()
        val newItem = PaperItem(
            id = "i1", kind = PaperItemKind.PHOTO, ref = "photo.webp",
            label = "Photo", durationMs = 0, x = 0f, y = 0f, width = 100f, height = 100f,
        )

        val outcome = DayRouting.mergeItemToDiskUnderLock(
            state = state,
            startGen = state.clearGen.get(),
            isTokenStillActive = { true },
            existingItemId = "nonexistent",
            newItem = newItem,
            readItems = { items.toList() },
            writeItems = { items.clear(); items.addAll(it) },
        )
        assertEquals(DiskMergeOutcome.DISCARDED, outcome)
        assertTrue(items.isEmpty())
    }

    // --- shouldDrainPendingCommits ---

    @Test
    fun drainPendingCommits_deferredWhileLoadingOrUnreadableOrActiveGesture() {
        assertFalse(DayRouting.shouldDrainPendingCommits(hasActiveGesture = false, loading = true, readable = false, queueSize = 1))
        assertFalse(DayRouting.shouldDrainPendingCommits(hasActiveGesture = false, loading = true, readable = true, queueSize = 1))
        assertFalse(DayRouting.shouldDrainPendingCommits(hasActiveGesture = false, loading = false, readable = false, queueSize = 1))
        assertFalse(DayRouting.shouldDrainPendingCommits(hasActiveGesture = true, loading = false, readable = true, queueSize = 1))
        assertFalse(DayRouting.shouldDrainPendingCommits(hasActiveGesture = false, loading = false, readable = true, queueSize = 0))
        assertTrue(DayRouting.shouldDrainPendingCommits(hasActiveGesture = false, loading = false, readable = true, queueSize = 1))
    }

    // --- onCommitSaveFailed ---

    @Test
    fun commitSaveFailed_neverDeletesFileUnlessExplicitlyObsolete() {
        assertEquals(
            FailedSaveDisposition.KEEP_FILE_FLUSH_TO_DISK,
            DayRouting.onCommitSaveFailed(genValid = true, tokenValid = true, missingExistingItem = false),
        )
        assertEquals(
            FailedSaveDisposition.KEEP_FILE_NOTIFY_FAILURE,
            DayRouting.onCommitSaveFailed(genValid = true, tokenValid = true, missingExistingItem = true),
        )
        assertEquals(
            FailedSaveDisposition.DELETE_OBSOLETE,
            DayRouting.onCommitSaveFailed(genValid = false, tokenValid = true, missingExistingItem = false),
        )
        assertEquals(
            FailedSaveDisposition.DELETE_OBSOLETE,
            DayRouting.onCommitSaveFailed(genValid = true, tokenValid = false, missingExistingItem = false),
        )
    }

    // --- shouldReloadOnDiskSync ---

    @Test
    fun diskSync_reloadsWhenRevBumpsAfterSyncOrWhenLoading() {
        assertFalse(DayRouting.shouldReloadOnDiskSync(loading = false, readable = true, loadedRev = 3L, currentRev = 3L))
        assertTrue(DayRouting.shouldReloadOnDiskSync(loading = false, readable = true, loadedRev = 3L, currentRev = 4L))
        assertTrue(DayRouting.shouldReloadOnDiskSync(loading = true, readable = false, loadedRev = 3L, currentRev = 3L))
        assertTrue(DayRouting.shouldReloadOnDiskSync(loading = true, readable = false, loadedRev = 3L, currentRev = 4L))
        assertFalse(DayRouting.shouldReloadOnDiskSync(loading = false, readable = false, loadedRev = 3L, currentRev = 4L))
    }

    // --- clearDay during in-flight load ---

    @Test
    fun clearDayDuringInFlightLoad_dropsStaleLoadResultAndReloadsOtherSheet() {
        val state = DayState.of(LocalDate.of(2026, 11, 28))
        val inFlightSeq = 1L
        val revBeforeClear = state.diskRev.get()
        val startGenBeforeClear = state.clearGen.get()

        // clearDay succeeds: bumps clearGen and diskRev under lock, and bumps sheet loadSeq
        if (DayRouting.shouldBumpClearGen(true)) {
            state.clearGen.incrementAndGet()
            state.diskRev.incrementAndGet()
        }
        val currentSeqAfterClear = inFlightSeq + 1L

        // In-flight load on the clearing sheet is dropped (IGNORE)
        assertEquals(
            LoadDecision.IGNORE,
            DayRouting.acceptLoadResult(
                resultSeq = inFlightSeq,
                currentSeq = currentSeqAfterClear,
                sameDateAndStore = true,
                loadedRev = revBeforeClear,
                currentRev = state.diskRev.get(),
            ),
        )

        // Another attached sheet showing the same date sees the bumped diskRev and reloads
        assertTrue(
            DayRouting.shouldReloadOnDiskSync(
                loading = false,
                readable = true,
                loadedRev = revBeforeClear,
                currentRev = state.diskRev.get(),
            ),
        )

        // Any queued commit from before clearDay is discarded on startGen mismatch
        assertEquals(
            CommitRoute.DISCARD,
            DayRouting.routeCommit(
                genValid = state.clearGen.get() == startGenBeforeClear,
                tokenValid = true,
                sheetShowsDate = true,
                sheetLoading = false,
                sheetReadable = true,
            ),
        )
    }

    // --- historyActionWhileFinishing ---

    @Test
    fun finishingStroke_queuesUndoAndDropsRedo() {
        assertEquals(
            FinishingHistoryAction.EXECUTE_NOW,
            DayRouting.historyActionWhileFinishing(isRedo = false, hasFinishingOnCurrentDate = false),
        )
        assertEquals(
            FinishingHistoryAction.EXECUTE_NOW,
            DayRouting.historyActionWhileFinishing(isRedo = true, hasFinishingOnCurrentDate = false),
        )
        assertEquals(
            FinishingHistoryAction.QUEUE_FOR_FINISH,
            DayRouting.historyActionWhileFinishing(isRedo = false, hasFinishingOnCurrentDate = true),
        )
        assertEquals(
            FinishingHistoryAction.DROP,
            DayRouting.historyActionWhileFinishing(isRedo = true, hasFinishingOnCurrentDate = true),
        )
    }
}
