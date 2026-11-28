package io.github.vquand.anddiary

import kotlin.concurrent.withLock

internal enum class CommitRoute {
    DISCARD,
    APPLY,
    QUEUE_UNTIL_LOADED,
    DISK,
    ERROR_UNREADABLE,
}

internal enum class LoadDecision {
    APPLY,
    IGNORE,
    RELOAD,
}

internal enum class DiskMergeOutcome {
    MERGED,
    DISCARDED,
}

internal enum class FailedSaveDisposition {
    DELETE_OBSOLETE,
    KEEP_FILE_FLUSH_TO_DISK,
    KEEP_FILE_NOTIFY_FAILURE,
}

internal enum class FinishingHistoryAction {
    EXECUTE_NOW,
    QUEUE_FOR_FINISH,
    DROP,
}

internal object DayRouting {
    fun routeCommit(
        genValid: Boolean,
        tokenValid: Boolean,
        sheetShowsDate: Boolean,
        sheetLoading: Boolean,
        sheetReadable: Boolean,
    ): CommitRoute {
        if (!genValid || !tokenValid) return CommitRoute.DISCARD
        if (!sheetShowsDate) return CommitRoute.DISK
        if (sheetLoading) return CommitRoute.QUEUE_UNTIL_LOADED
        if (!sheetReadable) return CommitRoute.ERROR_UNREADABLE
        return CommitRoute.APPLY
    }

    fun acceptLoadResult(
        resultSeq: Long,
        currentSeq: Long,
        sameDateAndStore: Boolean,
        loadedRev: Long,
        currentRev: Long,
    ): LoadDecision {
        if (resultSeq != currentSeq || !sameDateAndStore) return LoadDecision.IGNORE
        if (loadedRev != currentRev) return LoadDecision.RELOAD
        return LoadDecision.APPLY
    }

    fun shouldBumpClearGen(clearSucceeded: Boolean): Boolean = clearSucceeded

    fun shouldDrainPendingCommits(
        hasActiveGesture: Boolean,
        loading: Boolean,
        readable: Boolean,
        queueSize: Int,
    ): Boolean = !hasActiveGesture && !loading && readable && queueSize > 0

    fun shouldReloadOnDiskSync(
        loading: Boolean,
        readable: Boolean,
        loadedRev: Long,
        currentRev: Long,
    ): Boolean = loading || (readable && currentRev != loadedRev)

    fun onCommitSaveFailed(
        genValid: Boolean,
        tokenValid: Boolean,
        missingExistingItem: Boolean = false,
    ): FailedSaveDisposition {
        if (!genValid || !tokenValid) return FailedSaveDisposition.DELETE_OBSOLETE
        if (missingExistingItem) return FailedSaveDisposition.KEEP_FILE_NOTIFY_FAILURE
        return FailedSaveDisposition.KEEP_FILE_FLUSH_TO_DISK
    }

    fun historyActionWhileFinishing(
        isRedo: Boolean,
        hasFinishingOnCurrentDate: Boolean,
    ): FinishingHistoryAction = when {
        !hasFinishingOnCurrentDate -> FinishingHistoryAction.EXECUTE_NOW
        isRedo -> FinishingHistoryAction.DROP
        else -> FinishingHistoryAction.QUEUE_FOR_FINISH
    }

    fun mergeItemToDiskUnderLock(
        state: DayState,
        startGen: Int,
        isTokenStillActive: () -> Boolean,
        existingItemId: String?,
        newItem: PaperItem,
        readItems: () -> List<PaperItem>,
        writeItems: (List<PaperItem>) -> Unit,
    ): DiskMergeOutcome = state.lock.withLock {
        if (state.clearGen.get() != startGen || !isTokenStillActive()) {
            return@withLock DiskMergeOutcome.DISCARDED
        }
        val existing = readItems()
        val next = BackgroundItemSaveLogic.mergeItemIntoList(existing, existingItemId, newItem)
            ?: return@withLock DiskMergeOutcome.DISCARDED
        writeItems(next)
        state.diskRev.incrementAndGet()
        DiskMergeOutcome.MERGED
    }
}

/**
 * Tracks the newest in-flight contour save per (date, item): a token (a newer save supersedes an older one)
 * and the document that save is about to write, so a second orbit or edit starts from it instead of the
 * stale file. Tokens are never cleared on date change, only by their own terminal step, by undo/redo/delete
 * of that item, or by clearing that day.
 */
internal class ContourSaveRegistry<D> {
    private val tokens = java.util.concurrent.ConcurrentHashMap<Pair<java.time.LocalDate, String>, Long>()
    private val docs = java.util.concurrent.ConcurrentHashMap<Pair<java.time.LocalDate, String>, Pair<Long, D>>()

    fun begin(key: Pair<java.time.LocalDate, String>, token: Long, doc: D?) {
        tokens[key] = token
        if (doc != null) docs[key] = token to doc else docs.remove(key)
    }

    fun isActive(key: Pair<java.time.LocalDate, String>?, token: Long?): Boolean =
        key == null || token == null || tokens[key] == token

    /** Terminal step of a token. True if it was still the active one. */
    fun finish(key: Pair<java.time.LocalDate, String>?, token: Long?): Boolean {
        if (key == null || token == null) return false
        docs.computeIfPresent(key) { _, entry -> if (entry.first == token) null else entry }
        return tokens.remove(key, token)
    }

    fun cancel(date: java.time.LocalDate, itemId: String) {
        val key = date to itemId
        tokens.remove(key)
        docs.remove(key)
    }

    fun clearDate(date: java.time.LocalDate) {
        tokens.keys.removeAll { it.first == date }
        docs.keys.removeAll { it.first == date }
    }

    fun pendingDoc(key: Pair<java.time.LocalDate, String>): D? = docs[key]?.second
}
