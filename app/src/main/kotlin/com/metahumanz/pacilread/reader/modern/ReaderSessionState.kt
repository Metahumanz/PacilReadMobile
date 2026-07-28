package com.metahumanz.pacilread.reader.modern

import android.animation.ValueAnimator
import android.graphics.Bitmap
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.ChapterRecord
import com.metahumanz.pacilread.model.ReplacementRuleRecord

class ReaderSessionState {
    @JvmField val chapters: MutableList<ChapterRecord> = ArrayList()
    @JvmField val replacementRules: MutableList<ReplacementRuleRecord> = ArrayList()
    @JvmField var bookId = -1L
    @JvmField var book: BookRecord? = null
    @JvmField var currentChapterIndex = 0
    @JvmField var currentPageIndex = 0
    @JvmField var restoredChapterIndex = -1
    @JvmField var restoredPageIndex = -1
    @JvmField var restoredProgressOffset = -1
    @JvmField var requestedChapterOrderIndex = -1
    @JvmField var requestedChapterOffset = -1
    @JvmField var systemInsetTop = 0
    @JvmField var systemInsetBottom = 0
    @JvmField var systemInsetLeft = 0
    @JvmField var systemInsetRight = 0
    @JvmField var readerContentInsetTop = 0
    @JvmField var readerContentInsetBottom = 0
    @JvmField var readerContentInsetLeft = 0
    @JvmField var readerContentInsetRight = 0
    @JvmField var currentBatteryLevel = -1
    @JvmField var controlsVisible = false
    @JvmField var controlsTransitionActive = false
    @JvmField var autoPageActive = false
    @JvmField var ttsActive = false
    @JvmField var ttsPaused = false
    @JvmField var ttsChapterIndex = -1
    @JvmField var currentTtsUnitIndex = -1
    @JvmField var ttsHighlightPageIndex = -1
    @JvmField var ttsHighlightStart = -1
    @JvmField var ttsHighlightEnd = -1
    @JvmField var ttsSessionId = 0
    @JvmField var isAnimating = false
    @JvmField var animationToken = 0L
    @JvmField var pagingTouchSlop = 0
    @JvmField var pagingGestureCandidate = false
    @JvmField var interactivePaging = false
    @JvmField var pagingSnapshotsVisible = false
    @JvmField var simulationFinishCoverVisible = false
    @JvmField var simulationStableCoverVisible = false
    @JvmField var pagingDownX = 0f
    @JvmField var pagingDownY = 0f
    @JvmField var pagingLastX = 0f
    @JvmField var pagingLastMoveDeltaX = 0f
    @JvmField var pagingVelocityX = 0f
    @JvmField var interactiveProgress = 0f
    @JvmField var interactiveStartX = 0f
    @JvmField var interactiveStartY = 0f
    @JvmField var interactiveTouchX = 0f
    @JvmField var interactiveTouchY = 0f
    @JvmField var pagingLastEventTime = 0L
    @JvmField var interactiveDirection = 0
    @JvmField var interactiveCancel = false
    @JvmField var interactiveTargetChapterIndex = -1
    @JvmField var interactiveTargetPageIndex = -1
    @JvmField var animationTargetChapterIndex = -1
    @JvmField var animationTargetPageIndex = -1
    @JvmField var interactiveAnimator: ValueAnimator? = null
    @JvmField var totalProcessedBookLength = -1
    @JvmField var currentReaderPageColor = 0xFFF7F0E1.toInt()
    @JvmField var currentReaderTextColor = 0xFF5C4B37.toInt()
    @JvmField var pendingTapPagingDelta = 0
    @JvmField var lastTapY = -1f
    @JvmField var currentPageSnapshotBitmap: Bitmap? = null
    @JvmField var incomingPageSnapshotBitmap: Bitmap? = null
    @JvmField var nextPageSnapshotBitmap: Bitmap? = null
    @JvmField var previousPageSnapshotBitmap: Bitmap? = null
    @JvmField var preparedCurrentSnapshotChapterIndex = -1
    @JvmField var preparedCurrentSnapshotPageIndex = -1
    @JvmField var preparedIncomingSnapshotChapterIndex = -1
    @JvmField var preparedIncomingSnapshotPageIndex = -1
    @JvmField var preparedNextSnapshotChapterIndex = -1
    @JvmField var preparedNextSnapshotPageIndex = -1
    @JvmField var preparedPreviousSnapshotChapterIndex = -1
    @JvmField var preparedPreviousSnapshotPageIndex = -1
    @JvmField var suppressInsetReflowUntilUptimeMs = 0L
    @JvmField var sessionStartTime = 0L
    @JvmField var sessionStartOffset = 0
    @JvmField var lastKnownChapterIndex = -1
    @JvmField var lastKnownPageIndex = -1
    @JvmField var lastKnownChapterOffset = 0
}
