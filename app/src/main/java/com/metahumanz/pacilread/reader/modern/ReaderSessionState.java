package com.metahumanz.pacilread.reader.modern;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;

import java.util.ArrayList;
import java.util.List;

public final class ReaderSessionState {
    public final List<ChapterRecord> chapters = new ArrayList<>();
    public final List<ReplacementRuleRecord> replacementRules = new ArrayList<>();

    public long bookId = -1L;
    public BookRecord book;
    public int currentChapterIndex = 0;
    public int currentPageIndex = 0;
    public int restoredChapterIndex = -1;
    public int restoredPageIndex = -1;
    public int restoredProgressOffset = -1;
    public int requestedChapterOrderIndex = -1;
    public int requestedChapterOffset = -1;
    public int systemInsetTop = 0;
    public int systemInsetBottom = 0;
    public int systemInsetLeft = 0;
    public int systemInsetRight = 0;
    public int currentBatteryLevel = -1;
    public boolean controlsVisible = false;
    public boolean autoPageActive = false;
    public boolean ttsActive = false;
    public int ttsChapterIndex = -1;
    public int currentTtsUnitIndex = -1;
    public int ttsHighlightPageIndex = -1;
    public int ttsHighlightStart = -1;
    public int ttsHighlightEnd = -1;
    public int ttsSessionId = 0;
    public boolean isAnimating = false;
    public long animationToken = 0L;
    public int pagingTouchSlop = 0;
    public boolean pagingGestureCandidate = false;
    public boolean interactivePaging = false;
    public boolean pagingSnapshotsVisible = false;
    public float pagingDownX = 0f;
    public float pagingDownY = 0f;
    public float pagingLastX = 0f;
    public float pagingLastMoveDeltaX = 0f;
    public float pagingVelocityX = 0f;
    public float interactiveProgress = 0f;
    public float interactiveStartX = 0f;
    public float interactiveStartY = 0f;
    public float interactiveTouchX = 0f;
    public float interactiveTouchY = 0f;
    public long pagingLastEventTime = 0L;
    public int interactiveDirection = 0;
    public boolean interactiveCancel = false;
    public int interactiveTargetChapterIndex = -1;
    public int interactiveTargetPageIndex = -1;
    public int animationTargetChapterIndex = -1;
    public int animationTargetPageIndex = -1;
    public ValueAnimator interactiveAnimator;
    public int totalProcessedBookLength = -1;
    public int currentReaderPageColor = 0xFFF7F0E1;
    public int currentReaderTextColor = 0xFF5C4B37;
    public int pendingTapPagingDelta = 0;
    public float lastTapY = -1f;
    public Bitmap currentPageSnapshotBitmap;
    public Bitmap incomingPageSnapshotBitmap;
    public int preparedCurrentSnapshotChapterIndex = -1;
    public int preparedCurrentSnapshotPageIndex = -1;
    public int preparedIncomingSnapshotChapterIndex = -1;
    public int preparedIncomingSnapshotPageIndex = -1;
    public long suppressInsetReflowUntilUptimeMs = 0L;
    public long sessionStartTime = 0L;
    public int sessionStartOffset = 0;
}
