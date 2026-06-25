package com.metahumanz.pacilread.tts

object TtsSleepTimer {
    const val MAX_DURATION_MILLIS = 3L * 60L * 60L * 1000L

    @JvmStatic
    fun sliderProgressToMillis(progress: Int): Long = progress.coerceIn(0, 36) * 5L * 60L * 1000L

    @JvmStatic
    fun millisToSliderProgress(millis: Long): Int {
        if (millis <= 0) return 0
        return Math.round(millis / (5f * 60f * 1000f)).coerceIn(1, 36)
    }

    @JvmStatic
    fun preciseToMillis(hours: Int, minutes: Int, seconds: Int): Long {
        val total = hours.coerceAtLeast(0) * 3600L + minutes.coerceAtLeast(0) * 60L + seconds.coerceAtLeast(0)
        return (total * 1000L).coerceAtMost(24L * 60L * 60L * 1000L - 1000L)
    }

    @JvmStatic
    fun millisToPrecise(millis: Long): IntArray {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        return intArrayOf(
            (totalSeconds / 3600L).coerceAtMost(23L).toInt(),
            ((totalSeconds / 60L) % 60L).toInt(),
            (totalSeconds % 60L).toInt(),
        )
    }

    @JvmStatic
    fun deadlineFrom(nowElapsed: Long, durationMillis: Long): Long {
        if (durationMillis <= 0L) return 0L
        val safeNow = nowElapsed.coerceAtLeast(0L)
        if (Long.MAX_VALUE - safeNow < durationMillis) return Long.MAX_VALUE
        return safeNow + durationMillis
    }

    @JvmStatic
    fun remaining(nowElapsed: Long, deadlineElapsed: Long): Long {
        if (deadlineElapsed <= 0L) return 0L
        return (deadlineElapsed - nowElapsed.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    @JvmStatic
    fun isExpired(nowElapsed: Long, deadlineElapsed: Long): Boolean =
        deadlineElapsed > 0L && remaining(nowElapsed, deadlineElapsed) == 0L
}
