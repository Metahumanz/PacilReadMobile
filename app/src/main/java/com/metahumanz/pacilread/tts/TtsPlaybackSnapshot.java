package com.metahumanz.pacilread.tts;

public final class TtsPlaybackSnapshot {
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_PLAYING = "playing";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_LOADING = "loading";

    public final long bookId;
    public final String bookTitle;
    public final String chapterTitle;
    public final int chapterIndex;
    public final int sentenceStart;
    public final int sentenceEnd;
    public final String state;
    public final long sleepDeadlineElapsed;

    public TtsPlaybackSnapshot(long bookId, String bookTitle, String chapterTitle,
                               int chapterIndex, int sentenceStart, int sentenceEnd,
                               String state, long sleepDeadlineElapsed) {
        this.bookId = bookId;
        this.bookTitle = bookTitle == null ? "" : bookTitle;
        this.chapterTitle = chapterTitle == null ? "" : chapterTitle;
        this.chapterIndex = chapterIndex;
        this.sentenceStart = sentenceStart;
        this.sentenceEnd = sentenceEnd;
        this.state = state == null ? STATE_STOPPED : state;
        this.sleepDeadlineElapsed = sleepDeadlineElapsed;
    }

    public boolean isActive() {
        return STATE_PLAYING.equals(state) || STATE_PAUSED.equals(state) || STATE_LOADING.equals(state);
    }

    public boolean isPaused() {
        return STATE_PAUSED.equals(state);
    }
}
