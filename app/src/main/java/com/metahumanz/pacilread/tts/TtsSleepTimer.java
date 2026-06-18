package com.metahumanz.pacilread.tts;

public final class TtsSleepTimer {
    public static final long MAX_DURATION_MILLIS = 3L * 60L * 60L * 1000L;

    private TtsSleepTimer() {
    }

    public static long sliderProgressToMillis(int progress) {
        int safe = Math.max(0, Math.min(progress, 36));
        return safe * 5L * 60L * 1000L;
    }

    public static int millisToSliderProgress(long millis) {
        if (millis <= 0) return 0;
        return Math.max(1, Math.min(36, Math.round(millis / (5f * 60f * 1000f))));
    }

    public static long preciseToMillis(int hours, int minutes, int seconds) {
        long total = Math.max(0, hours) * 3600L + Math.max(0, minutes) * 60L + Math.max(0, seconds);
        return Math.min(total * 1000L, 24L * 60L * 60L * 1000L - 1000L);
    }

    public static int[] millisToPrecise(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return new int[]{
                (int) Math.min(23L, totalSeconds / 3600L),
                (int) ((totalSeconds / 60L) % 60L),
                (int) (totalSeconds % 60L)
        };
    }

    public static long deadlineFrom(long nowElapsed, long durationMillis) {
        if (durationMillis <= 0L) return 0L;
        long safeNow = Math.max(0L, nowElapsed);
        if (Long.MAX_VALUE - safeNow < durationMillis) return Long.MAX_VALUE;
        return safeNow + durationMillis;
    }

    public static long remaining(long nowElapsed, long deadlineElapsed) {
        if (deadlineElapsed <= 0L) return 0L;
        return Math.max(0L, deadlineElapsed - Math.max(0L, nowElapsed));
    }

    public static boolean isExpired(long nowElapsed, long deadlineElapsed) {
        return deadlineElapsed > 0L && remaining(nowElapsed, deadlineElapsed) == 0L;
    }
}
