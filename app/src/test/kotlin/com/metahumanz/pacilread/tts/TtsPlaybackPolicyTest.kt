package com.metahumanz.pacilread.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackPolicyTest {
    @Test
    fun shortSentenceUsesMinimumWatchdogTimeout() {
        assertEquals(12_000L, TtsPlaybackPolicy.systemUtteranceTimeoutMillis(5, 1f))
    }

    @Test
    fun longerSentenceGetsMorePlaybackTime() {
        val shortTimeout = TtsPlaybackPolicy.systemUtteranceTimeoutMillis(20, 1f)
        val longTimeout = TtsPlaybackPolicy.systemUtteranceTimeoutMillis(100, 1f)
        assertTrue(longTimeout > shortTimeout)
    }

    @Test
    fun slowerSpeechGetsMorePlaybackTime() {
        val normalTimeout = TtsPlaybackPolicy.systemUtteranceTimeoutMillis(100, 1f)
        val slowTimeout = TtsPlaybackPolicy.systemUtteranceTimeoutMillis(100, 0.5f)
        assertTrue(slowTimeout > normalTimeout)
    }

    @Test
    fun timeoutIsCappedForMalformedLongSentence() {
        assertEquals(180_000L, TtsPlaybackPolicy.systemUtteranceTimeoutMillis(Int.MAX_VALUE, 0.5f))
    }
}
