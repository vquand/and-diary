package io.github.vquand.anddiary

import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class DayState private constructor(val date: LocalDate) {
    val lock = ReentrantLock()
    val clearGen = AtomicInteger(0)
    val diskRev = AtomicLong(0)

    private val protectedCounts = HashMap<String, Int>()

    fun protect(ref: String) {
        synchronized(protectedCounts) {
            protectedCounts[ref] = (protectedCounts[ref] ?: 0) + 1
        }
    }

    fun release(ref: String) {
        synchronized(protectedCounts) {
            val current = protectedCounts[ref] ?: return
            if (current <= 1) {
                protectedCounts.remove(ref)
            } else {
                protectedCounts[ref] = current - 1
            }
        }
    }

    fun protectedSnapshot(): Set<String> = synchronized(protectedCounts) {
        protectedCounts.keys.toSet()
    }

    companion object {
        private val byDate = ConcurrentHashMap<LocalDate, DayState>()

        fun of(date: LocalDate): DayState = byDate.computeIfAbsent(date) { DayState(it) }

        internal fun resetForTests() {
            byDate.clear()
        }
    }
}

internal object MediaPrune {
    fun prune(
        state: DayState,
        mediaDir: File,
        readItems: () -> List<PaperItem>?,
        retained: Set<String> = emptySet(),
    ) {
        state.lock.withLock {
            if (!mediaDir.isDirectory) return
            val items = try {
                readItems()
            } catch (_: Exception) {
                null
            } ?: return

            val protected = state.protectedSnapshot()
            val keep = HashSet<String>(items.size + protected.size + retained.size)
            for (item in items) {
                if (item.kind == PaperItemKind.PHOTO ||
                    item.kind == PaperItemKind.VIDEO ||
                    item.kind == PaperItemKind.AUDIO ||
                    item.kind == PaperItemKind.CONTOUR
                ) {
                    keep.add(item.ref)
                }
            }
            keep.addAll(protected)
            keep.addAll(retained)

            mediaDir.listFiles()?.forEach { file ->
                val baseName = file.name.removeSuffix(".new").removeSuffix(".bak")
                if (file.isFile && file.name !in keep && baseName !in keep) {
                    file.delete()
                }
            }
            if (state.protectedSnapshot().isEmpty() && mediaDir.list().isNullOrEmpty()) {
                mediaDir.delete()
            }
        }
    }
}
