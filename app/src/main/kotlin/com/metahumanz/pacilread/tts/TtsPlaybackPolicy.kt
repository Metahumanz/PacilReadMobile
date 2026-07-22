package com.metahumanz.pacilread.tts

import kotlin.math.max

object TtsPlaybackPolicy {
    @JvmStatic
    fun systemUtteranceTimeoutMillis(textLength: Int, rate: Float): Long {
        val safeLength = max(textLength, 1)
        val safeRate = rate.coerceIn(0.5f, 3f)
        val estimatedMillis = 8_000L + (safeLength * 400L / safeRate).toLong()
        return estimatedMillis.coerceIn(MIN_SYSTEM_UTTERANCE_TIMEOUT_MS, MAX_SYSTEM_UTTERANCE_TIMEOUT_MS)
    }

    private const val MIN_SYSTEM_UTTERANCE_TIMEOUT_MS = 12_000L
    private const val MAX_SYSTEM_UTTERANCE_TIMEOUT_MS = 180_000L
}
