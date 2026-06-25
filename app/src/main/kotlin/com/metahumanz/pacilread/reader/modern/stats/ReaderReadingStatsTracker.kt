package com.metahumanz.pacilread.reader.modern.stats

import android.os.SystemClock
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import java.time.ZoneId

class ReaderReadingStatsTracker(
    private val runtime: ReaderRuntime,
    private val state: ReaderSessionState,
) {
    private val zoneId = ZoneId.systemDefault()
    private val checkpointRunnable = Runnable { runCheckpoint() }
    private val uploadRunnable = Runnable { runUpload() }
    private var activeWindow = false
    private var lastActivityElapsedMs = 0L
    private var lastCheckpointElapsedMs = 0L
    private var lastCheckpointWallMs = 0L
    private var lastCountedPageKey = ""
    private var pendingCharCount = 0

    fun bindBook(book: BookRecord?) {
        cancelCheckpoint()
        activeWindow = false
        lastCountedPageKey = ""
        pendingCharCount = 0
        if (book == null || !runtime.settingsStore.isReadingTimeTrackingEnabled) return
        startWindow(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        scheduleNextCheckpoint()
        if (runtime.readingStatsSyncManager.canAutoSync()) {
            runtime.safeExecute(Runnable {
                try { runtime.readingStatsSyncManager.downloadAndMergeReadingStats() } catch (_: Exception) {}
            }, "download reading stats")
        }
    }

    fun resume() {
        if (state.book == null || !runtime.settingsStore.isReadingTimeTrackingEnabled) return
        if (!activeWindow) startWindow(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        scheduleNextCheckpoint()
    }

    fun pause() {
        flushWindow(SystemClock.elapsedRealtime(), true)
        cancelCheckpoint()
        runtime.mainHandler.removeCallbacks(uploadRunnable)
        if (runtime.readingStatsSyncManager.canAutoSync()) {
            runtime.safeExecute(Runnable {
                try { runtime.readingStatsSyncManager.uploadLocalReadingStatsSnapshot() } catch (_: Exception) {}
            }, "upload reading stats on pause")
        }
    }

    fun shutdown() = pause()
    fun markActivity() = markActivity("", 0)

    fun markActivity(pageKey: String?, visibleCharCount: Int) {
        if (state.book == null || !runtime.settingsStore.isReadingTimeTrackingEnabled) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        if (!activeWindow) {
            startWindow(nowElapsed, nowWall)
        } else if (nowElapsed >= lastActivityElapsedMs + ReadingStatsUtils.IDLE_TIMEOUT_MS) {
            flushWindow(nowElapsed, false)
            startWindow(nowElapsed, nowWall)
        }
        if (!pageKey.isNullOrBlank() && pageKey != lastCountedPageKey && visibleCharCount > 0) {
            pendingCharCount += Math.max(visibleCharCount, 0)
            lastCountedPageKey = pageKey
        }
        lastActivityElapsedMs = nowElapsed
        scheduleNextCheckpoint()
    }

    private fun runCheckpoint() {
        if (!activeWindow) return
        flushWindow(SystemClock.elapsedRealtime(), false)
        if (activeWindow) scheduleNextCheckpoint()
    }

    private fun runUpload() {
        runtime.safeExecute(Runnable {
            try { runtime.readingStatsSyncManager.uploadLocalReadingStatsSnapshot() } catch (_: Exception) {}
        }, "upload reading stats")
    }

    private fun flushWindow(nowElapsed: Long, forceStop: Boolean) {
        if (!activeWindow || state.book == null || !runtime.settingsStore.isReadingTimeTrackingEnabled) {
            activeWindow = false
            return
        }
        val effectiveEndElapsed = Math.min(nowElapsed, lastActivityElapsedMs + ReadingStatsUtils.IDLE_TIMEOUT_MS)
        val pendingMillis = Math.max(0L, effectiveEndElapsed - lastCheckpointElapsedMs)
        val wholeSecondsMillis = pendingMillis / 1000L * 1000L
        if (wholeSecondsMillis > 0L) {
            val rangeStartWall = lastCheckpointWallMs
            val rangeEndWall = lastCheckpointWallMs + wholeSecondsMillis
            val rangeCharCount = pendingCharCount
            pendingCharCount = 0
            persistRange(rangeStartWall, rangeEndWall, rangeCharCount)
            lastCheckpointElapsedMs += wholeSecondsMillis
            lastCheckpointWallMs += wholeSecondsMillis
            debounceUpload()
        }
        if (forceStop || nowElapsed >= lastActivityElapsedMs + ReadingStatsUtils.IDLE_TIMEOUT_MS) activeWindow = false
    }

    private fun persistRange(startWallMs: Long, endWallMs: Long, charCount: Int) {
        val book = state.book
        if (endWallMs <= startWallMs || book == null) return
        val buckets = splitRangeByDay(startWallMs, endWallMs)
        if (buckets.isEmpty()) return
        distributeCharCount(buckets, Math.max(charCount, 0))
        val sourceDeviceId = runtime.settingsStore.readingStatsDeviceId
        val bookIdentity = book.readingStatsKey
        val bookTitle = book.title
        val bookAuthor = book.author
        runtime.safeExecute(Runnable {
            for (bucket in buckets) {
                runtime.databaseHelper.recordReadingDuration(
                    sourceDeviceId, bucket.date, bookIdentity, bookTitle, bookAuthor,
                    bucket.durationSeconds, bucket.charCount, bucket.updatedAt,
                )
            }
        }, "persist reading stats range")
    }

    private fun splitRangeByDay(startWallMs: Long, endWallMs: Long): MutableList<DayBucket> {
        val buckets = ArrayList<DayBucket>()
        var cursor = startWallMs
        while (cursor < endWallMs) {
            val segmentEnd = Math.min(endWallMs, ReadingStatsUtils.startOfNextDayMillis(cursor, zoneId))
            val seconds = ((segmentEnd - cursor) / 1000L).toInt()
            if (seconds > 0) {
                buckets.add(DayBucket(ReadingStatsUtils.formatWallDate(cursor, zoneId), seconds, 0, segmentEnd))
            }
            cursor = segmentEnd
        }
        return buckets
    }

    private fun distributeCharCount(buckets: MutableList<DayBucket>?, charCount: Int) {
        if (buckets.isNullOrEmpty() || charCount <= 0) return
        var totalSeconds = 0
        for (bucket in buckets) totalSeconds += Math.max(bucket.durationSeconds, 0)
        if (totalSeconds <= 0) {
            buckets[buckets.size - 1].charCount = charCount
            return
        }
        var remaining = charCount
        var remainingSeconds = totalSeconds
        for (i in buckets.indices) {
            val bucket = buckets[i]
            if (i == buckets.size - 1) {
                bucket.charCount = remaining
            } else {
                val value = Math.round(remaining.toFloat() * Math.max(bucket.durationSeconds, 0) / Math.max(remainingSeconds, 1))
                bucket.charCount = Math.max(value, 0)
                remaining -= bucket.charCount
                remainingSeconds -= Math.max(bucket.durationSeconds, 0)
            }
        }
    }

    private fun debounceUpload() {
        if (!runtime.readingStatsSyncManager.canAutoSync()) return
        runtime.mainHandler.removeCallbacks(uploadRunnable)
        runtime.mainHandler.postDelayed(uploadRunnable, 1_500L)
    }

    private fun scheduleNextCheckpoint() {
        cancelCheckpoint()
        if (!activeWindow) return
        val idleRemaining = lastActivityElapsedMs + ReadingStatsUtils.IDLE_TIMEOUT_MS - SystemClock.elapsedRealtime()
        if (idleRemaining <= 0L) {
            runtime.mainHandler.post(checkpointRunnable)
            return
        }
        val nextDelay = Math.min(ReadingStatsUtils.CHECKPOINT_INTERVAL_MS, idleRemaining)
        runtime.mainHandler.postDelayed(checkpointRunnable, Math.max(1_000L, nextDelay))
    }

    private fun cancelCheckpoint() = runtime.mainHandler.removeCallbacks(checkpointRunnable)

    private fun startWindow(nowElapsed: Long, nowWall: Long) {
        activeWindow = true
        lastActivityElapsedMs = nowElapsed
        lastCheckpointElapsedMs = nowElapsed
        lastCheckpointWallMs = nowWall
    }

    private class DayBucket(
        var date: String,
        var durationSeconds: Int,
        var charCount: Int,
        var updatedAt: Long,
    )
}
