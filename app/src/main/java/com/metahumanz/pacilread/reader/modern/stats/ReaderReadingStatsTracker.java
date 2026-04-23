package com.metahumanz.pacilread.reader.modern.stats;

import android.os.SystemClock;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public final class ReaderReadingStatsTracker {
    private final ReaderRuntime runtime;
    private final ReaderSessionState state;
    private final ZoneId zoneId = ZoneId.systemDefault();
    private final Runnable checkpointRunnable = this::runCheckpoint;
    private final Runnable uploadRunnable = this::runUpload;

    private boolean activeWindow;
    private long lastActivityElapsedMs;
    private long lastCheckpointElapsedMs;
    private long lastCheckpointWallMs;

    public ReaderReadingStatsTracker(ReaderRuntime runtime, ReaderSessionState state) {
        this.runtime = runtime;
        this.state = state;
    }

    public void bindBook(BookRecord book) {
        cancelCheckpoint();
        activeWindow = false;
        if (book == null || !runtime.settingsStore.isReadingTimeTrackingEnabled()) {
            return;
        }
        startWindow(SystemClock.elapsedRealtime(), System.currentTimeMillis());
        scheduleNextCheckpoint();
        if (runtime.readingStatsSyncManager.canAutoSync()) {
            runtime.executor.execute(() -> {
                try {
                    runtime.readingStatsSyncManager.downloadAndMergeReadingStats();
                } catch (Exception ignore) {
                }
            });
        }
    }

    public void resume() {
        if (state.book == null || !runtime.settingsStore.isReadingTimeTrackingEnabled()) {
            return;
        }
        if (!activeWindow) {
            startWindow(SystemClock.elapsedRealtime(), System.currentTimeMillis());
        }
        scheduleNextCheckpoint();
    }

    public void pause() {
        flushWindow(SystemClock.elapsedRealtime(), true);
        cancelCheckpoint();
        runtime.mainHandler.removeCallbacks(uploadRunnable);
        if (runtime.readingStatsSyncManager.canAutoSync()) {
            runtime.executor.execute(() -> {
                try {
                    runtime.readingStatsSyncManager.uploadLocalReadingStatsSnapshot();
                } catch (Exception ignore) {
                }
            });
        }
    }

    public void shutdown() {
        pause();
    }

    public void markActivity() {
        if (state.book == null || !runtime.settingsStore.isReadingTimeTrackingEnabled()) {
            return;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        long nowWall = System.currentTimeMillis();
        if (!activeWindow) {
            startWindow(nowElapsed, nowWall);
        } else if (nowElapsed >= lastActivityElapsedMs + ReadingStatsUtils.IDLE_TIMEOUT_MS) {
            flushWindow(nowElapsed, false);
            startWindow(nowElapsed, nowWall);
        }
        lastActivityElapsedMs = nowElapsed;
        scheduleNextCheckpoint();
    }

    private void runCheckpoint() {
        if (!activeWindow) {
            return;
        }
        flushWindow(SystemClock.elapsedRealtime(), false);
        if (activeWindow) {
            scheduleNextCheckpoint();
        }
    }

    private void runUpload() {
        runtime.executor.execute(() -> {
            try {
                runtime.readingStatsSyncManager.uploadLocalReadingStatsSnapshot();
            } catch (Exception ignore) {
            }
        });
    }

    private void flushWindow(long nowElapsed, boolean forceStop) {
        if (!activeWindow || state.book == null || !runtime.settingsStore.isReadingTimeTrackingEnabled()) {
            activeWindow = false;
            return;
        }
        long effectiveEndElapsed = Math.min(nowElapsed, lastActivityElapsedMs + ReadingStatsUtils.IDLE_TIMEOUT_MS);
        long pendingMillis = Math.max(0L, effectiveEndElapsed - lastCheckpointElapsedMs);
        long wholeSecondsMillis = (pendingMillis / 1000L) * 1000L;
        if (wholeSecondsMillis > 0L) {
            long rangeStartWall = lastCheckpointWallMs;
            long rangeEndWall = lastCheckpointWallMs + wholeSecondsMillis;
            persistRange(rangeStartWall, rangeEndWall);
            lastCheckpointElapsedMs += wholeSecondsMillis;
            lastCheckpointWallMs += wholeSecondsMillis;
            debounceUpload();
        }
        if (forceStop || nowElapsed >= lastActivityElapsedMs + ReadingStatsUtils.IDLE_TIMEOUT_MS) {
            activeWindow = false;
        }
    }

    private void persistRange(long startWallMs, long endWallMs) {
        if (endWallMs <= startWallMs || state.book == null) {
            return;
        }
        List<DayBucket> buckets = splitRangeByDay(startWallMs, endWallMs);
        if (buckets.isEmpty()) {
            return;
        }
        String sourceDeviceId = runtime.settingsStore.getReadingStatsDeviceId();
        String bookIdentity = state.book.readingStatsKey;
        String bookTitle = state.book.title;
        String bookAuthor = state.book.author;
        runtime.executor.execute(() -> {
            for (DayBucket bucket : buckets) {
                runtime.databaseHelper.recordReadingDuration(
                        sourceDeviceId,
                        bucket.date,
                        bookIdentity,
                        bookTitle,
                        bookAuthor,
                        bucket.durationSeconds,
                        0,
                        bucket.updatedAt
                );
            }
        });
    }

    private List<DayBucket> splitRangeByDay(long startWallMs, long endWallMs) {
        List<DayBucket> buckets = new ArrayList<>();
        long cursor = startWallMs;
        while (cursor < endWallMs) {
            long nextDayStart = ReadingStatsUtils.startOfNextDayMillis(cursor, zoneId);
            long segmentEnd = Math.min(endWallMs, nextDayStart);
            int seconds = (int) ((segmentEnd - cursor) / 1000L);
            if (seconds > 0) {
                DayBucket bucket = new DayBucket();
                bucket.date = ReadingStatsUtils.formatWallDate(cursor, zoneId);
                bucket.durationSeconds = seconds;
                bucket.updatedAt = segmentEnd;
                buckets.add(bucket);
            }
            cursor = segmentEnd;
        }
        return buckets;
    }

    private void debounceUpload() {
        if (!runtime.readingStatsSyncManager.canAutoSync()) {
            return;
        }
        runtime.mainHandler.removeCallbacks(uploadRunnable);
        runtime.mainHandler.postDelayed(uploadRunnable, 1_500L);
    }

    private void scheduleNextCheckpoint() {
        cancelCheckpoint();
        if (!activeWindow) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long idleRemaining = (lastActivityElapsedMs + ReadingStatsUtils.IDLE_TIMEOUT_MS) - now;
        if (idleRemaining <= 0L) {
            runtime.mainHandler.post(checkpointRunnable);
            return;
        }
        long nextDelay = Math.min(ReadingStatsUtils.CHECKPOINT_INTERVAL_MS, idleRemaining);
        runtime.mainHandler.postDelayed(checkpointRunnable, Math.max(1_000L, nextDelay));
    }

    private void cancelCheckpoint() {
        runtime.mainHandler.removeCallbacks(checkpointRunnable);
    }

    private void startWindow(long nowElapsed, long nowWall) {
        activeWindow = true;
        lastActivityElapsedMs = nowElapsed;
        lastCheckpointElapsedMs = nowElapsed;
        lastCheckpointWallMs = nowWall;
    }

    private static final class DayBucket {
        String date;
        int durationSeconds;
        long updatedAt;
    }
}
