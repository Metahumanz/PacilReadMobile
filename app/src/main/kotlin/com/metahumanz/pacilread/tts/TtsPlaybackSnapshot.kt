package com.metahumanz.pacilread.tts

class TtsPlaybackSnapshot(
    @JvmField val bookId: Long,
    bookTitle: String?,
    chapterTitle: String?,
    @JvmField val chapterIndex: Int,
    @JvmField val sentenceStart: Int,
    @JvmField val sentenceEnd: Int,
    state: String?,
    @JvmField val sleepDeadlineElapsed: Long,
) {
    @JvmField val bookTitle: String = bookTitle ?: ""
    @JvmField val chapterTitle: String = chapterTitle ?: ""
    @JvmField val state: String = state ?: STATE_STOPPED

    fun isActive(): Boolean = state == STATE_PLAYING || state == STATE_PAUSED || state == STATE_LOADING

    fun isPaused(): Boolean = state == STATE_PAUSED

    companion object {
        const val STATE_STOPPED = "stopped"
        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_LOADING = "loading"
    }
}
