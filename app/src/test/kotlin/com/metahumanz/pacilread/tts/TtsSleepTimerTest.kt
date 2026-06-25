package com.metahumanz.pacilread.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSleepTimerTest {
    @Test
    fun convertsSliderInFiveMinuteSteps() {
        assertEquals(0L, TtsSleepTimer.sliderProgressToMillis(0))
        assertEquals(30L * 60L * 1000L, TtsSleepTimer.sliderProgressToMillis(6))
        assertEquals(36, TtsSleepTimer.millisToSliderProgress(TtsSleepTimer.MAX_DURATION_MILLIS))
    }

    @Test
    fun convertsPreciseHoursMinutesAndSeconds() {
        val value = TtsSleepTimer.preciseToMillis(2, 3, 4)
        assertEquals(7_384_000L, value)
        assertArrayEquals(intArrayOf(2, 3, 4), TtsSleepTimer.millisToPrecise(value))
    }

    @Test
    fun deadlineKeepsCountingWhilePlaybackIsPaused() {
        val deadline = TtsSleepTimer.deadlineFrom(1_000L, 5_000L)
        assertEquals(2_000L, TtsSleepTimer.remaining(4_000L, deadline))
        assertFalse(TtsSleepTimer.isExpired(5_999L, deadline))
        assertTrue(TtsSleepTimer.isExpired(6_000L, deadline))
    }
}
