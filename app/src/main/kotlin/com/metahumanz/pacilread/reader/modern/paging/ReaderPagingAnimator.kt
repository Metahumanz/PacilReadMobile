package com.metahumanz.pacilread.reader.modern.paging

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.metahumanz.pacilread.reader.PageSlice
import com.metahumanz.pacilread.reader.SimulationPageTurnView
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController

class ReaderPagingAnimator(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val views: ReaderViewRefs,
    private val state: ReaderSessionState,
    private val ui: ReaderUiUtils,
) {
    private val pagingSnapshotCanvas = Canvas()
    private val pagingSnapshotWarmupRunnable = Runnable { warmPreparedPagingSnapshots() }
    private val simulationNextSnapshotWarmupRunnable = Runnable { warmDeferredSimulationDirectionalSnapshot(1) }
    private val simulationPreviousSnapshotWarmupRunnable = Runnable { warmDeferredSimulationDirectionalSnapshot(-1) }
    private val runtimeMaxMemoryBytes = Runtime.getRuntime().maxMemory()

    private lateinit var navigation: ReaderNavigationController
    private lateinit var content: ReaderContentController
    private lateinit var chrome: ReaderChromeController
    private var pagingSnapshotWarmupRequestId = 0
    private var lowRamDevice: Boolean? = null

    fun attachControllers(
        navigation: ReaderNavigationController,
        content: ReaderContentController,
        chrome: ReaderChromeController,
    ) {
        this.navigation = navigation
        this.content = content
        this.chrome = chrome
    }

    fun handleReaderPagingTouchEvent(event: MotionEvent): Boolean {
        if (state.controlsVisible || state.chapters.isEmpty() || views.pageStage.width == 0 || views.pageStage.height == 0) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state.interactivePaging) cancelInteractiveAnimator()
                state.pagingGestureCandidate = chrome.isInsideView(event, views.pageStage) && !state.isAnimating
                state.pagingDownX = localTouchX(event)
                state.pagingDownY = localTouchY(event)
                state.pagingLastX = state.pagingDownX
                state.pagingLastEventTime = event.eventTime
                state.pagingLastMoveDeltaX = 0f
                state.pagingVelocityX = 0f
                state.interactiveCancel = false
                captureInteractiveStartPoint(state.pagingDownX, state.pagingDownY)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!state.pagingGestureCandidate && !state.interactivePaging) return false
                updatePagingVelocity(event)
                val currentTouchX = localTouchX(event)
                val currentTouchY = localTouchY(event)
                if (!state.interactivePaging) {
                    val deltaX = currentTouchX - state.pagingDownX
                    val deltaY = currentTouchY - state.pagingDownY
                    val slop = effectivePagingTouchSlop(event)
                    val distanceSquare = deltaX * deltaX + deltaY * deltaY
                    if (distanceSquare <= slop * slop) return false
                    val simulationDiagonalStart = isSimulationFlipMode() && isSimulationDiagonalStartZone(state.pagingDownY)
                    val diagonalRatio = horizontalGestureRatio()
                    if (!simulationDiagonalStart && Math.abs(deltaY) > Math.abs(deltaX) * diagonalRatio) {
                        state.pagingGestureCandidate = false
                        return false
                    }
                    val minHorizontalDelta = if (simulationDiagonalStart) Math.max(1f, slop * 0.5f) else Math.abs(deltaY) / diagonalRatio
                    if (Math.abs(deltaX) <= minHorizontalDelta) return false
                    val direction = if (deltaX < 0f) 1 else -1
                    if (!prepareInteractivePaging(direction, state.pagingDownX, state.pagingDownY)) {
                        state.pagingGestureCandidate = false
                        return false
                    }
                }
                updateInteractiveTouchPoint(currentTouchX, currentTouchY)
                updateInteractiveCancelState()
                val width = interactiveProgressWidth()
                val deltaX = currentTouchX - state.pagingDownX
                val progress = if (state.interactiveDirection > 0) -deltaX / width else deltaX / width
                applyInteractivePagingProgress(progress, state.interactiveTouchY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!state.interactivePaging) {
                    state.pagingGestureCandidate = false
                    return false
                }
                updatePagingVelocity(event)
                if (event.actionMasked != MotionEvent.ACTION_CANCEL && isSimulationFlipMode()) {
                    updateInteractiveTouchPoint(localTouchX(event), localTouchY(event))
                    if (shouldCompleteSimulationDragAtEdge(state.interactiveTouchX)) {
                        finishInteractivePaging(true)
                        return true
                    }
                }
                val commit = event.actionMasked != MotionEvent.ACTION_CANCEL && shouldCommitInteractivePaging()
                finishInteractivePaging(commit)
                return true
            }
            else -> return false
        }
    }

    fun handleReaderVolumeKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || state.controlsVisible) return false
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> runtime.settingsStore.volumeKeyUpAction
            KeyEvent.KEYCODE_VOLUME_DOWN -> runtime.settingsStore.volumeKeyDownAction
            else -> null
        }
        if (action == null || action == "system") return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            state.lastTapY = -1f
            navigation.requestTapPageTurn(if (action == "page_up") -1 else 1)
            return true
        }
        return event.action == KeyEvent.ACTION_UP
    }

    fun animateTransition(targetChapterIndex: Int, targetPageIndex: Int, direction: Int) {
        val token = ++state.animationToken
        state.isAnimating = true
        rememberAnimationTarget(targetChapterIndex, targetPageIndex)
        val mode = runtime.settingsStore.flipMode
        if (mode != "simulation") clearStableSimulationCover(false)
        val width = Math.max(views.pageStage.width.toFloat(), ui.dp(240).toFloat())
        val height = Math.max(views.pageStage.height.toFloat(), ui.dp(320).toFloat())
        cancelInteractiveAnimator()
        resetAnimatedPage(views.pageCurrent)
        resetAnimatedPage(views.pageIncoming)
        views.simulationPageTurnView.clear()
        resetShadowView()
        views.pageIncoming.visibility = View.GONE
        if (mode == "none") {
            finishAnimation(targetChapterIndex, targetPageIndex, direction, token)
            return
        }
        if (mode == "simulation") initializeSimulationAutoStart(direction, height) else resetInteractiveTouchState()
        navigation.bindIncomingSpread(targetChapterIndex, targetPageIndex)
        preparePagingSnapshots(targetChapterIndex, targetPageIndex, direction)
        arrangePagingLayers(mode)
        applyPagingVisuals(mode, direction, 0f, if (mode == "simulation") state.interactiveTouchY else height * 0.5f)
        val animator = ValueAnimator.ofFloat(0f, 1f)
        state.interactiveAnimator = animator
        animator.duration = readerFlipDurationMs()
        animator.interpolator = if (mode == "simulation") PAGE_TURN_INTERPOLATOR else PAGE_SLIDE_INTERPOLATOR
        val startTouchX = state.interactiveTouchX
        val startTouchY = state.interactiveTouchY
        val targetTouchX = if (mode == "simulation") resolveSimulationTargetTouchX(direction, true) else 0f
        val targetTouchY = if (mode == "simulation") resolveSimulationTargetTouchY(direction) else height * 0.5f
        val finishCoverProgress = if (mode == "simulation") simulationFinishCoverProgressThreshold() else SIMULATION_FINISH_COVER_PROGRESS
        val cancelled = booleanArrayOf(false)
        val finishCoverShown = booleanArrayOf(false)
        animator.addUpdateListener { animation ->
            val animatedProgress = animation.animatedValue as Float
            if (mode == "simulation") {
                state.interactiveTouchX = lerp(startTouchX, targetTouchX, animatedProgress)
                state.interactiveTouchY = lerp(startTouchY, targetTouchY, animatedProgress)
                if (animatedProgress >= finishCoverProgress) {
                    finishCoverShown[0] = showSimulationTargetSnapshotAndStopCurl(targetChapterIndex, targetPageIndex, direction) || finishCoverShown[0]
                    if (finishCoverShown[0]) return@addUpdateListener
                }
            }
            applyPagingVisuals(mode, direction, animatedProgress, if (mode == "simulation") state.interactiveTouchY else height * 0.5f)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cancelled[0] = true
                state.interactiveAnimator = null
            }

            override fun onAnimationEnd(animation: Animator) {
                state.interactiveAnimator = null
                if (cancelled[0] || token != state.animationToken) return
                if (mode == "simulation" && !finishCoverShown[0]) {
                    showSimulationTargetSnapshotAndStopCurl(targetChapterIndex, targetPageIndex, direction)
                }
                finishAnimation(targetChapterIndex, targetPageIndex, direction, token)
            }
        })
        animator.start()
    }

    fun readerFlipDurationMs(): Long {
        val mode = runtime.settingsStore.flipMode
        var baseDuration = 180L
        if (mode == "none") return 0L
        if (mode == "cover") baseDuration = 220L else if (mode == "simulation") baseDuration = 360L else if (mode == "scroll") baseDuration = 190L
        val speed = runtime.settingsStore.flipSpeed
        if (speed == "fast") return (baseDuration * 0.6f).toLong()
        if (speed == "slow") return (baseDuration * 1.5f).toLong()
        return baseDuration
    }

    fun ensurePageAreaReady(action: Runnable): Boolean {
        if (views.pageBodyCurrent.width > 0 && views.pageBodyCurrent.height > 0) return true
        views.pageStage.post(action)
        return false
    }

    fun schedulePagingSnapshotWarmup() {
        val requestId = ++pagingSnapshotWarmupRequestId
        views.pageStage.removeCallbacks(pagingSnapshotWarmupRunnable)
        removeSimulationWarmupCallbacks()
        if (shouldSkipPagingSnapshotWarmup()) return
        views.pageStage.postOnAnimation { waitForPagingSnapshotWarmupPreDraw(requestId, snapshotWarmupPreDrawPasses()) }
    }

    private fun shouldSkipPagingSnapshotWarmup(): Boolean =
        state.chapters.isEmpty() || state.controlsVisible || activity.isReaderEnterTransitionActive || state.isAnimating || state.interactivePaging

    private fun waitForPagingSnapshotWarmupPreDraw(requestId: Int, remainingPasses: Int) {
        if (requestId != pagingSnapshotWarmupRequestId || shouldSkipPagingSnapshotWarmup()) return
        if (remainingPasses <= 0) {
            views.pageStage.post {
                if (requestId == pagingSnapshotWarmupRequestId && !shouldSkipPagingSnapshotWarmup()) pagingSnapshotWarmupRunnable.run()
            }
            return
        }
        val target = views.pageStage
        if (!target.isAttachedToWindow) return
        val completed = booleanArrayOf(false)
        val listenerRef = arrayOfNulls<ViewTreeObserver.OnPreDrawListener>(1)
        val complete = Runnable {
            if (completed[0]) return@Runnable
            completed[0] = true
            removePreDrawListener(target, listenerRef[0])
            waitForPagingSnapshotWarmupPreDraw(requestId, remainingPasses - 1)
        }
        listenerRef[0] = ViewTreeObserver.OnPreDrawListener {
            target.post(complete)
            true
        }
        val observer = target.viewTreeObserver
        if (!observer.isAlive) {
            target.post(complete)
            return
        }
        observer.addOnPreDrawListener(listenerRef[0])
        views.pageCurrent.requestLayout()
        views.pageCurrent.invalidate()
        target.invalidate()
        target.postDelayed(complete, SNAPSHOT_WARMUP_PREDRAW_FALLBACK_MS)
    }

    fun invalidatePreparedPagingSnapshots() {
        pagingSnapshotWarmupRequestId++
        clearStableSimulationCover(true)
        state.simulationFinishCoverVisible = false
        state.preparedCurrentSnapshotChapterIndex = -1
        state.preparedCurrentSnapshotPageIndex = -1
        state.preparedIncomingSnapshotChapterIndex = -1
        state.preparedIncomingSnapshotPageIndex = -1
        views.pageStage.removeCallbacks(pagingSnapshotWarmupRunnable)
        removeSimulationWarmupCallbacks()
        state.preparedNextSnapshotChapterIndex = -1
        state.preparedNextSnapshotPageIndex = -1
        state.preparedPreviousSnapshotChapterIndex = -1
        state.preparedPreviousSnapshotPageIndex = -1
    }

    fun restoreLivePageLayers(incomingVisible: Boolean) {
        state.pagingSnapshotsVisible = false
        state.simulationFinishCoverVisible = false
        state.simulationStableCoverVisible = false
        clearSimulationPagingLayer()
        resetAnimatedPage(views.pageSnapshotCurrent)
        views.pageSnapshotCurrent.visibility = View.GONE
        resetAnimatedPage(views.pageSnapshotIncoming)
        views.pageSnapshotIncoming.visibility = View.GONE
        views.pageCurrent.visibility = View.VISIBLE
        views.pageIncoming.visibility = if (incomingVisible) View.VISIBLE else View.GONE
    }

    fun clearStableSimulationCoverForLiveView() = clearStableSimulationCover(true)

    private fun clearStableSimulationCover(restoreHud: Boolean) {
        if (!state.simulationStableCoverVisible) return
        state.simulationStableCoverVisible = false
        state.pagingSnapshotsVisible = false
        resetAnimatedPage(views.pageSnapshotCurrent)
        views.pageSnapshotCurrent.visibility = View.GONE
        resetAnimatedPage(views.pageSnapshotIncoming)
        views.pageSnapshotIncoming.visibility = View.GONE
        if (restoreHud && !state.controlsVisible) showLiveHudAfterPaging()
        bringStableBookSpineOverlayToFront()
    }

    fun resetAnimatedPage(view: View?) {
        if (view == null) return
        view.animate().cancel()
        view.translationX = 0f
        view.translationY = 0f
        view.rotationX = 0f
        view.rotationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.pivotX = view.width * 0.5f
        view.pivotY = view.height * 0.5f
        view.alpha = 1f
        view.clipBounds = null
    }

    fun updateBodyTopMargin(bodyView: TextView, topMargin: Int) {
        val params = bodyView.layoutParams as android.view.ViewGroup.MarginLayoutParams
        params.topMargin = topMargin
        bodyView.layoutParams = params
    }

    fun cancelInteractiveAnimator() {
        state.interactiveAnimator?.cancel()
        state.interactiveAnimator = null
    }

    fun settleInterruptedPagingAnimation(): Boolean {
        if (!state.isAnimating && !state.interactivePaging && state.interactiveAnimator == null) return false
        val targetChapterIndex = if (state.animationTargetChapterIndex >= 0) state.animationTargetChapterIndex else state.interactiveTargetChapterIndex
        val targetPageIndex = if (state.animationTargetPageIndex >= 0) state.animationTargetPageIndex else state.interactiveTargetPageIndex
        state.animationToken++
        cancelInteractiveAnimator()
        if (targetChapterIndex >= 0 && targetPageIndex >= 0 && state.chapters.isNotEmpty()) {
            settleOnPage(targetChapterIndex, targetPageIndex)
            return true
        }
        if (state.chapters.isNotEmpty()) {
            settleOnPage(state.currentChapterIndex, state.currentPageIndex)
            return true
        }
        cancelInteractivePaging()
        clearAnimationTarget()
        return false
    }

    fun cancelInteractivePaging() {
        state.pagingGestureCandidate = false
        state.interactivePaging = false
        state.interactiveDirection = 0
        state.interactiveCancel = false
        state.interactiveProgress = 0f
        state.simulationFinishCoverVisible = false
        state.simulationStableCoverVisible = false
        state.interactiveTargetChapterIndex = -1
        state.interactiveTargetPageIndex = -1
        clearAnimationTarget()
        resetInteractiveTouchState()
        restoreLivePageLayers(false)
        showLiveHudAfterPaging()
        resetAnimatedPage(views.pageCurrent)
        resetAnimatedPage(views.pageIncoming)
        views.pageIncoming.visibility = View.GONE
        resetShadowView()
        state.isAnimating = false
    }

    fun recyclePagingSnapshots() {
        invalidatePreparedPagingSnapshots()
        if (state.currentPageSnapshotBitmap != null && !state.currentPageSnapshotBitmap!!.isRecycled) state.currentPageSnapshotBitmap!!.recycle()
        if (state.incomingPageSnapshotBitmap != null && !state.incomingPageSnapshotBitmap!!.isRecycled) state.incomingPageSnapshotBitmap!!.recycle()
        if (state.nextPageSnapshotBitmap != null && !state.nextPageSnapshotBitmap!!.isRecycled) state.nextPageSnapshotBitmap!!.recycle()
        if (state.previousPageSnapshotBitmap != null && !state.previousPageSnapshotBitmap!!.isRecycled) state.previousPageSnapshotBitmap!!.recycle()
        state.currentPageSnapshotBitmap = null
        state.incomingPageSnapshotBitmap = null
        state.nextPageSnapshotBitmap = null
        state.previousPageSnapshotBitmap = null
    }

    fun removeWarmupCallbacks() {
        pagingSnapshotWarmupRequestId++
        views.pageStage.removeCallbacks(pagingSnapshotWarmupRunnable)
        removeSimulationWarmupCallbacks()
    }

    private fun settleOnPage(targetChapterIndex: Int, targetPageIndex: Int) {
        val safeChapterIndex = ui.clamp(targetChapterIndex, 0, state.chapters.size - 1)
        val pages = content.getPagesForChapter(safeChapterIndex)
        if (pages.isEmpty()) {
            cancelInteractivePaging()
            return
        }
        val safePageIndex = ui.clamp(targetPageIndex, 0, pages.size - 1)
        state.currentChapterIndex = safeChapterIndex
        state.currentPageIndex = safePageIndex
        state.simulationFinishCoverVisible = false
        state.simulationStableCoverVisible = false
        navigation.bindCurrentSpread(safeChapterIndex, safePageIndex)
        content.rememberCurrentPageAnchor()
        restoreLivePageLayers(false)
        resetAnimatedPage(views.pageCurrent)
        resetAnimatedPage(views.pageIncoming)
        views.pageCurrent.visibility = View.VISIBLE
        views.pageCurrent.bringToFront()
        views.pageIncoming.visibility = View.GONE
        resetShadowView()
        resetInteractiveTouchState()
        state.pagingGestureCandidate = false
        state.interactivePaging = false
        state.interactiveDirection = 0
        state.interactiveCancel = false
        state.interactiveProgress = 0f
        state.interactiveTargetChapterIndex = -1
        state.interactiveTargetPageIndex = -1
        clearAnimationTarget()
        state.pendingTapPagingDelta = 0
        state.isAnimating = false
        activity.markReadingActivity()
        chrome.updateUiAfterPageChange()
        showLiveHudAfterPaging()
        content.scheduleProgressSave()
        chrome.scheduleAutoHide()
        schedulePagingSnapshotWarmup()
    }

    private fun updatePagingVelocity(event: MotionEvent) {
        val now = event.eventTime
        val elapsed = Math.max(1L, now - state.pagingLastEventTime)
        val currentX = localTouchX(event)
        state.pagingLastMoveDeltaX = currentX - state.pagingLastX
        state.pagingVelocityX = state.pagingLastMoveDeltaX / elapsed
        state.pagingLastX = currentX
        state.pagingLastEventTime = now
    }

    private fun updateInteractiveCancelState() {
        if (!state.interactivePaging || Math.abs(state.pagingLastMoveDeltaX) < 0.1f) return
        val directionalMove = if (state.interactiveDirection > 0) -state.pagingLastMoveDeltaX else state.pagingLastMoveDeltaX
        state.interactiveCancel = directionalMove < 0f
    }

    private fun prepareInteractivePaging(direction: Int, startX: Float, startY: Float): Boolean {
        val target = resolveInteractiveTarget(direction) ?: return false
        val mode = runtime.settingsStore.flipMode
        cancelInteractiveAnimator()
        if (mode != "simulation") clearStableSimulationCover(false)
        state.interactivePaging = true
        state.isAnimating = true
        state.interactiveDirection = direction
        state.interactiveTargetChapterIndex = target.chapterIndex
        state.interactiveTargetPageIndex = target.pageIndex
        rememberAnimationTarget(target.chapterIndex, target.pageIndex)
        state.interactiveProgress = 0f
        captureInteractiveStartPoint(startX, startY)
        navigation.bindIncomingSpread(target.chapterIndex, target.pageIndex)
        views.pageIncoming.visibility = View.VISIBLE
        resetAnimatedPage(views.pageCurrent)
        resetAnimatedPage(views.pageIncoming)
        resetShadowView()
        preparePagingSnapshots(target.chapterIndex, target.pageIndex, direction)
        arrangePagingLayers(runtime.settingsStore.flipMode)
        applyInteractivePagingProgress(0f, state.interactiveTouchY)
        return true
    }

    private fun resolveInteractiveTarget(direction: Int): PageTarget? {
        if (direction > 0) {
            val pages: List<PageSlice> = content.getPagesForChapter(state.currentChapterIndex)
            val nextPageIndex = state.currentPageIndex + navigation.pageStep()
            if (nextPageIndex < pages.size) return PageTarget(state.currentChapterIndex, nextPageIndex)
            if (state.currentChapterIndex < state.chapters.size - 1) return PageTarget(state.currentChapterIndex + 1, 0)
            return null
        }
        if (state.currentPageIndex > 0) return PageTarget(state.currentChapterIndex, Math.max(0, state.currentPageIndex - navigation.pageStep()))
        if (state.currentChapterIndex > 0) {
            val previousPages = content.getPagesForChapter(state.currentChapterIndex - 1)
            return PageTarget(state.currentChapterIndex - 1, navigation.lastSpreadStart(previousPages))
        }
        return null
    }

    private fun applyInteractivePagingProgress(progress: Float, touchY: Float) {
        if (!state.interactivePaging) return
        state.interactiveProgress = Math.max(0f, Math.min(1f, progress))
        applyPagingVisuals(runtime.settingsStore.flipMode, state.interactiveDirection, state.interactiveProgress, touchY)
    }

    private fun hideInteractiveShadow() = resetOverlayView(views.pageShadow)

    private fun updateInteractiveFoldHighlight(edgeX: Float, direction: Int, alpha: Float, scaleX: Float, rotation: Float) {
        updatePagingOverlay(views.pageFoldHighlight, edgeX, direction, alpha, 0.58f, scaleX, rotation, 0.34f)
    }

    private fun hideInteractiveFoldEffects() {
        resetOverlayView(views.pageFoldShadow)
        resetOverlayView(views.pageFoldHighlight)
    }

    private fun updatePagingOverlay(overlay: View?, edgeX: Float, direction: Int, alpha: Float, anchorRatio: Float, scaleX: Float, rotation: Float, maxAlpha: Float) {
        if (overlay == null) return
        overlay.animate().cancel()
        val safeAlpha = Math.max(0f, Math.min(maxAlpha, alpha))
        if (safeAlpha <= 0f) {
            resetOverlayView(overlay)
            return
        }
        val overlayWidth = Math.max(overlay.width.toFloat(), 1f)
        val safeAnchorRatio = Math.max(0f, Math.min(1f, anchorRatio))
        val anchorX = overlayWidth * safeAnchorRatio
        overlay.visibility = View.VISIBLE
        overlay.pivotX = anchorX
        overlay.pivotY = Math.max(overlay.height, 1) * 0.5f
        overlay.translationX = edgeX - anchorX
        overlay.scaleX = if (direction > 0) scaleX else -scaleX
        overlay.scaleY = 1f
        overlay.rotation = rotation
        overlay.alpha = safeAlpha
    }

    private fun shouldCommitInteractivePaging(): Boolean {
        val directionalVelocity = if (state.interactiveDirection > 0) -state.pagingVelocityX else state.pagingVelocityX
        val mode = runtime.settingsStore.flipMode
        val progressThreshold = progressCommitThreshold(mode)
        val velocityThreshold = velocityCommitThreshold(mode)
        if (state.interactiveProgress >= progressThreshold) return true
        if (directionalVelocity > velocityThreshold) return true
        if (directionalVelocity < -0.25f || (state.interactiveCancel && state.interactiveProgress < progressThreshold * 0.5f)) return false
        return false
    }

    private fun effectivePagingTouchSlop(event: MotionEvent?): Float {
        val slop = Math.max(state.pagingTouchSlop, 1).toFloat()
        if (event == null || !isPhoneReaderViewport()) return slop
        val toolType = if (event.pointerCount > 0) event.getToolType(0) else MotionEvent.TOOL_TYPE_UNKNOWN
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_MOUSE) return Math.max(1f, slop * 0.75f)
        if (isSimulationFlipMode()) return Math.max(2f, Math.min(slop, ui.dp(4).toFloat()))
        return Math.max(slop, ui.dp(9).toFloat())
    }

    private fun horizontalGestureRatio(): Float {
        if (!isSimulationFlipMode()) return DEFAULT_DIAGONAL_RATIO
        if (isPhoneReaderViewport()) return PHONE_SIMULATION_DIAGONAL_RATIO
        if (isTabletReaderViewport()) return TABLET_SIMULATION_DIAGONAL_RATIO
        return DEFAULT_DIAGONAL_RATIO
    }

    private fun progressCommitThreshold(mode: String): Float {
        if (mode == "cover") return if (isPhoneReaderViewport()) 0.16f else 0.18f
        if (mode == "simulation") {
            if (isSimulationOuterPageTurnActive()) return 0.38f
            if (isPhoneReaderViewport()) return 0.21f
            return if (isTabletReaderViewport()) 0.23f else 0.24f
        }
        if (mode == "scroll") {
            if (isPhoneReaderViewport()) return 0.26f
            return if (isTabletReaderViewport()) 0.27f else 0.28f
        }
        if (isPhoneReaderViewport()) return 0.20f
        return if (isTabletReaderViewport()) 0.21f else 0.22f
    }

    private fun velocityCommitThreshold(mode: String): Float {
        val baseThreshold = if (mode == "scroll") 0.7f else 0.85f
        return if (isPhoneReaderViewport()) baseThreshold * 0.9f else baseThreshold
    }

    private fun simulationFinishCoverProgressThreshold(): Float =
        if (isSimulationOuterPageTurnActive()) OUTER_PAGE_FINISH_COVER_PROGRESS else SIMULATION_FINISH_COVER_PROGRESS

    private fun finishInteractivePaging(commit: Boolean) {
        val start = state.interactiveProgress
        val end = if (commit) 1f else 0f
        val token = ++state.animationToken
        cancelInteractiveAnimator()
        if (commit) rememberAnimationTarget(state.interactiveTargetChapterIndex, state.interactiveTargetPageIndex)
        else rememberAnimationTarget(state.currentChapterIndex, state.currentPageIndex)
        val animator = ValueAnimator.ofFloat(start, end)
        state.interactiveAnimator = animator
        val mode = runtime.settingsStore.flipMode
        val remainingDistance = Math.max(0.2f, Math.abs(end - start))
        var duration = kotlin.math.max(110L, Math.round(readerFlipDurationMs() * remainingDistance).toLong())
        if (Math.abs(state.pagingVelocityX) > 0.7f) duration = kotlin.math.max(90L, Math.round(duration / Math.min(Math.abs(state.pagingVelocityX), 2.4f)).toLong())
        animator.duration = duration
        animator.interpolator = if (mode == "simulation") PAGE_TURN_INTERPOLATOR else PAGE_SLIDE_INTERPOLATOR
        val startTouchX = state.interactiveTouchX
        val startTouchY = state.interactiveTouchY
        val targetTouchX = if (mode == "simulation") resolveSimulationTargetTouchX(state.interactiveDirection, commit) else state.interactiveTouchX
        val targetTouchY = if (mode == "simulation") resolveSimulationTargetTouchY(state.interactiveDirection) else state.interactiveTouchY
        val finishCoverProgress = if (mode == "simulation") simulationFinishCoverProgressThreshold() else SIMULATION_FINISH_COVER_PROGRESS
        val cancelled = booleanArrayOf(false)
        val finishCoverShown = booleanArrayOf(false)
        animator.addUpdateListener { animation ->
            val animatedProgress = animation.animatedValue as Float
            if (mode == "simulation") {
                val touchFraction = normalizedAnimationValue(animatedProgress, start, end)
                state.interactiveTouchX = lerp(startTouchX, targetTouchX, touchFraction)
                state.interactiveTouchY = lerp(startTouchY, targetTouchY, touchFraction)
                if (commit && animatedProgress >= finishCoverProgress) {
                    finishCoverShown[0] = showSimulationTargetSnapshotAndStopCurl(
                        state.interactiveTargetChapterIndex,
                        state.interactiveTargetPageIndex,
                        state.interactiveDirection,
                    ) || finishCoverShown[0]
                    if (finishCoverShown[0]) return@addUpdateListener
                }
            }
            applyInteractivePagingProgress(animatedProgress, state.interactiveTouchY)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cancelled[0] = true
                state.interactiveAnimator = null
            }

            override fun onAnimationEnd(animation: Animator) {
                state.interactiveAnimator = null
                if (cancelled[0] || token != state.animationToken) return
                if (commit) {
                    if (mode == "simulation" && !finishCoverShown[0]) {
                        showSimulationTargetSnapshotAndStopCurl(
                            state.interactiveTargetChapterIndex,
                            state.interactiveTargetPageIndex,
                            state.interactiveDirection,
                        )
                    }
                    finishAnimation(
                        state.interactiveTargetChapterIndex,
                        state.interactiveTargetPageIndex,
                        state.interactiveDirection,
                        token,
                    )
                } else {
                    cancelInteractivePaging()
                }
            }
        })
        animator.start()
    }

    private fun shouldCompleteSimulationDragAtEdge(touchX: Float): Boolean {
        if (!isSimulationFlipMode() || !isSimulationOuterPageTurnActive() || !state.interactivePaging || state.interactiveDirection == 0 || state.interactiveTargetChapterIndex < 0 || state.interactiveTargetPageIndex < 0 || state.interactiveCancel || state.interactiveProgress < 0.92f) return false
        val width = Math.max(views.pageStage.width.toFloat(), ui.dp(240).toFloat())
        val edgeInset = Math.max(ui.dp(10).toFloat(), width * 0.018f)
        return if (state.interactiveDirection > 0) touchX <= edgeInset else touchX >= width - edgeInset
    }

    private fun rememberAnimationTarget(targetChapterIndex: Int, targetPageIndex: Int) {
        state.animationTargetChapterIndex = targetChapterIndex
        state.animationTargetPageIndex = targetPageIndex
    }

    private fun clearAnimationTarget() {
        state.animationTargetChapterIndex = -1
        state.animationTargetPageIndex = -1
    }

    private fun localTouchX(event: MotionEvent): Float {
        val stageLocation = IntArray(2)
        views.pageStage.getLocationOnScreen(stageLocation)
        return event.rawX - stageLocation[0]
    }

    private fun localTouchY(event: MotionEvent): Float {
        val stageLocation = IntArray(2)
        views.pageStage.getLocationOnScreen(stageLocation)
        return event.rawY - stageLocation[1]
    }

    private fun finishAnimation(targetChapterIndex: Int, targetPageIndex: Int, direction: Int, token: Long) {
        if (token != state.animationToken) return
        val previousChapterIndex = state.currentChapterIndex
        val previousPageIndex = state.currentPageIndex
        state.currentChapterIndex = targetChapterIndex
        state.currentPageIndex = targetPageIndex
        promoteIncomingSnapshotToCurrent(targetChapterIndex, targetPageIndex, previousChapterIndex, previousPageIndex, direction)
        navigation.bindCurrentSpread(targetChapterIndex, targetPageIndex)
        content.rememberCurrentPageAnchor()
        if (runtime.settingsStore.flipMode == "simulation") {
            resetAnimatedPage(views.pageCurrent)
            resetAnimatedPage(views.pageIncoming)
            resetShadowView()
            if (showPromotedCurrentSnapshotCover()) {
                prepareFinishedLivePageBehindSimulationCover()
                state.pagingGestureCandidate = false
                state.interactivePaging = false
                state.interactiveDirection = 0
                state.interactiveCancel = false
                state.interactiveProgress = 0f
                state.interactiveTargetChapterIndex = -1
                state.interactiveTargetPageIndex = -1
                clearAnimationTarget()
                completeFinishedAnimationSwapAfterLiveDraw(targetChapterIndex, targetPageIndex, token)
                return
            }
        }
        val keepIncomingCover = views.pageIncoming.visibility == View.VISIBLE
        restoreLivePageLayers(keepIncomingCover)
        resetAnimatedPage(views.pageCurrent)
        resetAnimatedPage(views.pageIncoming)
        resetShadowView()
        state.pagingGestureCandidate = false
        state.interactivePaging = false
        state.interactiveDirection = 0
        state.interactiveCancel = false
        state.interactiveProgress = 0f
        state.interactiveTargetChapterIndex = -1
        state.interactiveTargetPageIndex = -1
        clearAnimationTarget()
        if (keepIncomingCover) {
            views.pageIncoming.bringToFront()
            views.pageCurrent.visibility = View.INVISIBLE
            views.pageStage.post { completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token) }
            return
        }
        completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token)
    }

    private fun completeFinishedAnimationSwap(targetChapterIndex: Int, targetPageIndex: Int, token: Long) {
        if (token != state.animationToken) return
        val finishingSimulationCover = state.simulationFinishCoverVisible && views.pageSnapshotCurrent.visibility == View.VISIBLE
        if (finishingSimulationCover) {
            state.pagingSnapshotsVisible = true
            state.simulationStableCoverVisible = true
            resetAnimatedPage(views.pageCurrent)
            views.pageCurrent.visibility = View.VISIBLE
            resetAnimatedPage(views.pageSnapshotIncoming)
            views.pageSnapshotIncoming.visibility = View.GONE
            views.pageIncoming.visibility = View.GONE
            keepStableSimulationCoverOnTop()
            views.simulationPageTurnView.clear()
            keepStableSimulationCoverOnTop()
            views.pageStage.postOnAnimation { completeFinishedAnimationSwapNow(token, true) }
            return
        }
        views.simulationPageTurnView.clear()
        views.pageCurrent.visibility = View.VISIBLE
        views.pageCurrent.bringToFront()
        completeFinishedAnimationSwapNow(token, false)
    }

    private fun completeFinishedAnimationSwapNow(token: Long, keepSimulationCover: Boolean) {
        if (token != state.animationToken) return
        views.pageCurrent.visibility = View.VISIBLE
        if (!keepSimulationCover) views.pageCurrent.bringToFront()
        state.pagingSnapshotsVisible = keepSimulationCover
        state.simulationStableCoverVisible = keepSimulationCover
        resetAnimatedPage(views.pageSnapshotCurrent)
        views.pageSnapshotCurrent.visibility = if (keepSimulationCover) View.VISIBLE else View.GONE
        if (keepSimulationCover) views.pageSnapshotCurrent.bringToFront()
        resetAnimatedPage(views.pageSnapshotIncoming)
        views.pageSnapshotIncoming.visibility = View.GONE
        views.pageIncoming.visibility = View.GONE
        state.simulationFinishCoverVisible = false
        if (keepSimulationCover) keepStableSimulationCoverOnTop()
        bringStableBookSpineOverlayToFront()
        resetInteractiveTouchState()
        state.pendingTapPagingDelta = 0
        content.rememberCurrentPageAnchor()
        activity.markReadingActivity()
        chrome.updateUiAfterPageChange()
        state.isAnimating = false
        if (keepSimulationCover) hideLiveHudDuringPaging() else showLiveHudAfterPaging()
        content.scheduleProgressSave()
        chrome.scheduleAutoHide()
        schedulePagingSnapshotWarmup()
    }

    private fun showPromotedCurrentSnapshotCover(): Boolean {
        if (!ensureCurrentSnapshotCoverBitmap()) return false
        state.pagingSnapshotsVisible = true
        state.simulationFinishCoverVisible = true
        state.simulationStableCoverVisible = false
        views.pageSnapshotCurrent.setImageBitmap(state.currentPageSnapshotBitmap)
        resetAnimatedPage(views.pageSnapshotCurrent)
        views.pageSnapshotCurrent.visibility = View.VISIBLE
        views.pageSnapshotCurrent.bringToFront()
        bringStableBookSpineOverlayToFront()
        resetAnimatedPage(views.pageSnapshotIncoming)
        views.pageSnapshotIncoming.visibility = View.GONE
        views.simulationPageTurnView.clear()
        views.pageIncoming.visibility = View.GONE
        return true
    }

    private fun prepareFinishedLivePageBehindSimulationCover() {
        resetAnimatedPage(views.pageCurrent)
        views.pageCurrent.visibility = View.VISIBLE
        layoutPageLayerForSnapshot(views.pageCurrent)
        views.pageCurrent.invalidate()
        views.pageSnapshotCurrent.bringToFront()
        bringStableBookSpineOverlayToFront()
    }

    private fun showIncomingSnapshotCoverBeforeCommit(targetChapterIndex: Int, targetPageIndex: Int, direction: Int): Boolean {
        var targetSnapshot = preparedTargetSnapshot(targetChapterIndex, targetPageIndex)
        if (targetSnapshot == null || targetSnapshot.isRecycled) {
            targetSnapshot = captureDirectionalPreparedSnapshot(direction, targetChapterIndex, targetPageIndex)
        }
        if (targetSnapshot == null || targetSnapshot.isRecycled) return false
        setActiveIncomingSnapshot(targetSnapshot, targetChapterIndex, targetPageIndex)
        state.pagingSnapshotsVisible = true
        state.simulationStableCoverVisible = false
        views.pageSnapshotCurrent.setImageBitmap(targetSnapshot)
        resetAnimatedPage(views.pageSnapshotCurrent)
        views.pageSnapshotCurrent.visibility = View.VISIBLE
        views.pageSnapshotCurrent.bringToFront()
        resetAnimatedPage(views.pageSnapshotIncoming)
        views.pageSnapshotIncoming.visibility = View.GONE
        bringStableBookSpineOverlayToFront()
        return true
    }

    private fun showSimulationTargetSnapshotAndStopCurl(targetChapterIndex: Int, targetPageIndex: Int, direction: Int): Boolean {
        val shown = showIncomingSnapshotCoverBeforeCommit(targetChapterIndex, targetPageIndex, direction)
        if (shown) {
            state.simulationFinishCoverVisible = true
            state.simulationStableCoverVisible = false
            clearSimulationPagingLayer()
            keepSimulationFinishCoverOnTop()
        }
        return shown
    }

    private fun keepSimulationFinishCoverOnTop() {
        state.pagingSnapshotsVisible = true
        resetShadowView()
        hideInteractiveFoldEffects()
        views.simulationPageTurnView.clear()
        resetAnimatedPage(views.pageSnapshotIncoming)
        views.pageSnapshotIncoming.visibility = View.GONE
        views.pageIncoming.visibility = View.GONE
        resetAnimatedPage(views.pageSnapshotCurrent)
        views.pageSnapshotCurrent.visibility = View.VISIBLE
        views.pageSnapshotCurrent.bringToFront()
        bringStableBookSpineOverlayToFront()
    }

    private fun keepStableSimulationCoverOnTop() {
        if (!state.simulationStableCoverVisible) return
        state.pagingSnapshotsVisible = true
        resetAnimatedPage(views.pageSnapshotCurrent)
        views.pageSnapshotCurrent.visibility = View.VISIBLE
        views.pageSnapshotCurrent.bringToFront()
        bringStableBookSpineOverlayToFront()
    }

    private fun bringStableBookSpineOverlayToFront() {
        if (!isSimulationOuterPageTurnActive()) {
            views.pageBookSpineOverlay.visibility = View.GONE
            return
        }
        views.pageBookSpineOverlay.visibility = View.VISIBLE
        views.pageBookSpineOverlay.bringToFront()
    }

    private fun ensureCurrentSnapshotCoverBitmap(): Boolean {
        if (hasPreparedCurrentSnapshot(state.currentChapterIndex, state.currentPageIndex)) return true
        resetAnimatedPage(views.pageCurrent)
        views.pageCurrent.visibility = View.VISIBLE
        if (!layoutPageLayerForSnapshot(views.pageCurrent)) return false
        val bitmap = screenshotPageLayer(
            views.pageCurrent,
            state.currentPageSnapshotBitmap,
            state.currentChapterIndex,
            state.currentPageIndex,
        )
        if (bitmap == null || bitmap.isRecycled) {
            state.preparedCurrentSnapshotChapterIndex = -1
            state.preparedCurrentSnapshotPageIndex = -1
            return false
        }
        state.currentPageSnapshotBitmap = bitmap
        state.preparedCurrentSnapshotChapterIndex = state.currentChapterIndex
        state.preparedCurrentSnapshotPageIndex = state.currentPageIndex
        return true
    }

    private fun completeFinishedAnimationSwapAfterLiveDraw(targetChapterIndex: Int, targetPageIndex: Int, token: Long) {
        waitForFinishedAnimationPreDraw(
            targetChapterIndex,
            targetPageIndex,
            token,
            FINISH_SWAP_PREDRAW_PASSES,
        )
    }

    private fun waitForFinishedAnimationPreDraw(
        targetChapterIndex: Int,
        targetPageIndex: Int,
        token: Long,
        remainingPasses: Int,
    ) {
        if (token != state.animationToken) return
        if (remainingPasses <= 0) {
            scheduleFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token)
            return
        }
        val target: View = views.pageStage
        if (!target.isAttachedToWindow) {
            completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token)
            return
        }
        val completed = booleanArrayOf(false)
        val listenerRef = arrayOfNulls<ViewTreeObserver.OnPreDrawListener>(1)
        val complete = Runnable {
            if (completed[0]) return@Runnable
            completed[0] = true
            removePreDrawListener(target, listenerRef[0])
            waitForFinishedAnimationPreDraw(
                targetChapterIndex,
                targetPageIndex,
                token,
                remainingPasses - 1,
            )
        }
        listenerRef[0] = ViewTreeObserver.OnPreDrawListener {
            target.post(complete)
            true
        }
        val observer = target.viewTreeObserver
        if (observer == null || !observer.isAlive) {
            target.post(complete)
            return
        }
        observer.addOnPreDrawListener(listenerRef[0])
        views.pageCurrent.requestLayout()
        views.pageCurrent.invalidate()
        target.invalidate()
        target.postDelayed(complete, FINISH_SWAP_PREDRAW_FALLBACK_MS)
    }

    private fun scheduleFinishedAnimationSwap(targetChapterIndex: Int, targetPageIndex: Int, token: Long) {
        val target: View = views.pageStage
        target.postDelayed(
            { completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token) },
            FINISH_SWAP_COVER_HOLD_MS,
        )
    }

    private fun removePreDrawListener(target: View?, listener: ViewTreeObserver.OnPreDrawListener?) {
        if (target == null || listener == null) return
        val observer = target.viewTreeObserver
        if (observer != null && observer.isAlive) {
            observer.removeOnPreDrawListener(listener)
        }
    }

    private fun preparePagingSnapshots(targetChapterIndex: Int, targetPageIndex: Int, direction: Int) {
        val simulationMode = runtime.settingsStore.flipMode == "simulation"
        val bridgeStableCover = simulationMode && state.simulationStableCoverVisible
        if (!bridgeStableCover) clearStableSimulationCover(false)
        state.simulationFinishCoverVisible = false
        clearSimulationPagingLayer()
        ensurePreparedCurrentSnapshot()
        var targetSnapshot = preparedTargetSnapshot(targetChapterIndex, targetPageIndex)
        if (targetSnapshot == null) {
            targetSnapshot = captureDirectionalPreparedSnapshot(direction, targetChapterIndex, targetPageIndex)
        }
        setActiveIncomingSnapshot(targetSnapshot, targetChapterIndex, targetPageIndex)
        if (state.currentPageSnapshotBitmap == null || state.incomingPageSnapshotBitmap == null) {
            restoreLivePageLayers(true)
            return
        }
        views.pageSnapshotCurrent.setImageBitmap(state.currentPageSnapshotBitmap)
        views.pageSnapshotIncoming.setImageBitmap(state.incomingPageSnapshotBitmap)
        views.pageSnapshotCurrent.visibility = View.VISIBLE
        views.pageSnapshotIncoming.visibility = if (simulationMode) View.GONE else View.VISIBLE
        views.pageCurrent.visibility = View.INVISIBLE
        views.pageIncoming.visibility = View.INVISIBLE
        hideLiveHudDuringPaging()
        state.pagingSnapshotsVisible = true
        state.simulationStableCoverVisible = false
    }

    private fun screenshotPageLayer(source: View, reuse: Bitmap?, chapterIndex: Int, pageIndex: Int): Bitmap? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null
        var targetBitmap = reuse
        if (targetBitmap == null || targetBitmap.width != width || targetBitmap.height != height) {
            if (targetBitmap != null && !targetBitmap.isRecycled) targetBitmap.recycle()
            targetBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } else {
            targetBitmap.eraseColor(Color.TRANSPARENT)
        }
        pagingSnapshotCanvas.setBitmap(targetBitmap)
        drawSnapshotBaseLayer(pagingSnapshotCanvas, source)
        pagingSnapshotCanvas.save()
        pagingSnapshotCanvas.translate(-source.scrollX.toFloat(), -source.scrollY.toFloat())
        source.draw(pagingSnapshotCanvas)
        pagingSnapshotCanvas.restore()
        drawHudSnapshotLayer(pagingSnapshotCanvas, source, chapterIndex, pageIndex)
        pagingSnapshotCanvas.setBitmap(null)
        targetBitmap.prepareToDraw()
        return targetBitmap
    }

    private fun warmPreparedPagingSnapshots() {
        if (shouldSkipPagingSnapshotWarmup()) return
        if (!ensurePageAreaReady { schedulePagingSnapshotWarmup() }) return
        ensurePreparedCurrentSnapshot()
        if (isSimulationFlipMode()) {
            scheduleSimulationDirectionalWarmup(1, SIMULATION_PRIMARY_WARMUP_DELAY_MS)
            if (resolveSimulationRenderQuality() != SimulationPageTurnView.RENDER_QUALITY_LOW) {
                scheduleSimulationDirectionalWarmup(-1, SIMULATION_SECONDARY_WARMUP_DELAY_MS)
            }
            return
        }
        warmDirectionalSnapshot(1)
        warmDirectionalSnapshot(-1)
    }

    private fun snapshotWarmupPreDrawPasses(): Int {
        return if (isSimulationFlipMode()) 1 else SNAPSHOT_WARMUP_PREDRAW_PASSES
    }

    private fun scheduleSimulationDirectionalWarmup(direction: Int, delayMs: Long) {
        val runnable = if (direction >= 0) simulationNextSnapshotWarmupRunnable else simulationPreviousSnapshotWarmupRunnable
        views.pageStage.removeCallbacks(runnable)
        views.pageStage.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    private fun warmDeferredSimulationDirectionalSnapshot(direction: Int) {
        if (shouldSkipPagingSnapshotWarmup() || !isSimulationFlipMode()) return
        if (!ensurePageAreaReady { schedulePagingSnapshotWarmup() }) return
        warmDirectionalSnapshot(direction)
    }

    private fun removeSimulationWarmupCallbacks() {
        views.pageStage.removeCallbacks(simulationNextSnapshotWarmupRunnable)
        views.pageStage.removeCallbacks(simulationPreviousSnapshotWarmupRunnable)
    }

    private fun ensurePreparedCurrentSnapshot() {
        if (hasPreparedCurrentSnapshot(state.currentChapterIndex, state.currentPageIndex)) return
        navigation.bindCurrentSpread(state.currentChapterIndex, state.currentPageIndex)
        layoutPageLayerForSnapshot(views.pageCurrent)
        state.currentPageSnapshotBitmap = screenshotPageLayer(
            views.pageCurrent,
            state.currentPageSnapshotBitmap,
            state.currentChapterIndex,
            state.currentPageIndex,
        )
        if (state.currentPageSnapshotBitmap != null) {
            state.preparedCurrentSnapshotChapterIndex = state.currentChapterIndex
            state.preparedCurrentSnapshotPageIndex = state.currentPageIndex
        } else {
            state.preparedCurrentSnapshotChapterIndex = -1
            state.preparedCurrentSnapshotPageIndex = -1
        }
    }

    private fun warmDirectionalSnapshot(direction: Int) {
        val target = resolveInteractiveTarget(direction)
        if (target == null || hasPreparedDirectionalSnapshot(direction, target.chapterIndex, target.pageIndex)) return
        captureDirectionalPreparedSnapshot(direction, target.chapterIndex, target.pageIndex)
    }

    private fun preparedTargetSnapshot(chapterIndex: Int, pageIndex: Int): Bitmap? {
        if (hasPreparedNextSnapshot(chapterIndex, pageIndex)) return state.nextPageSnapshotBitmap
        if (hasPreparedPreviousSnapshot(chapterIndex, pageIndex)) return state.previousPageSnapshotBitmap
        if (hasPreparedIncomingSnapshot(chapterIndex, pageIndex)) return state.incomingPageSnapshotBitmap
        return null
    }

    private fun captureDirectionalPreparedSnapshot(direction: Int, chapterIndex: Int, pageIndex: Int): Bitmap? {
        if (direction >= 0) {
            val preparedBitmap = capturePreparedIncomingSnapshot(chapterIndex, pageIndex, state.nextPageSnapshotBitmap)
            if (preparedBitmap != null) {
                state.nextPageSnapshotBitmap = preparedBitmap
                state.preparedNextSnapshotChapterIndex = chapterIndex
                state.preparedNextSnapshotPageIndex = pageIndex
            } else {
                state.preparedNextSnapshotChapterIndex = -1
                state.preparedNextSnapshotPageIndex = -1
            }
            return preparedBitmap
        }
        val preparedBitmap = capturePreparedIncomingSnapshot(chapterIndex, pageIndex, state.previousPageSnapshotBitmap)
        if (preparedBitmap != null) {
            state.previousPageSnapshotBitmap = preparedBitmap
            state.preparedPreviousSnapshotChapterIndex = chapterIndex
            state.preparedPreviousSnapshotPageIndex = pageIndex
        } else {
            state.preparedPreviousSnapshotChapterIndex = -1
            state.preparedPreviousSnapshotPageIndex = -1
        }
        return preparedBitmap
    }

    private fun capturePreparedIncomingSnapshot(chapterIndex: Int, pageIndex: Int, reuse: Bitmap?): Bitmap? {
        val previousVisibility = views.pageIncoming.visibility
        val previousAlpha = views.pageIncoming.alpha
        val keepStableCover = state.simulationStableCoverVisible && views.pageSnapshotCurrent.visibility == View.VISIBLE
        navigation.bindIncomingSpread(chapterIndex, pageIndex)
        resetAnimatedPage(views.pageIncoming)
        if (!keepStableCover) {
            views.pageCurrent.bringToFront()
        } else {
            keepStableSimulationCoverOnTop()
        }
        views.pageIncoming.visibility = View.VISIBLE
        if (!layoutPageLayerForSnapshot(views.pageIncoming)) {
            views.pageIncoming.alpha = previousAlpha
            views.pageIncoming.visibility = previousVisibility
            keepStableSimulationCoverOnTop()
            return null
        }
        val bitmap = screenshotPageLayer(views.pageIncoming, reuse, chapterIndex, pageIndex)
        views.pageIncoming.alpha = previousAlpha
        views.pageIncoming.visibility = previousVisibility
        resetAnimatedPage(views.pageIncoming)
        if (!keepStableCover) views.pageCurrent.bringToFront()
        keepStableSimulationCoverOnTop()
        return bitmap
    }

    private fun layoutPageLayerForSnapshot(source: View?): Boolean {
        if (source == null) return false
        val width = snapshotDimensionFor(source, true)
        val height = snapshotDimensionFor(source, false)
        if (width <= 0 || height <= 0) return false
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        source.measure(widthSpec, heightSpec)
        source.layout(0, 0, width, height)
        return true
    }

    private fun snapshotDimensionFor(source: View?, width: Boolean): Int {
        if (source == null) return 0
        val currentValue = if (width) source.width else source.height
        if (currentValue > 0) return currentValue
        val currentPageValue = if (width) views.pageCurrent.width else views.pageCurrent.height
        if (currentPageValue > 0) return currentPageValue
        return if (width) {
            (views.pageStage.width - views.pageStage.paddingLeft - views.pageStage.paddingRight).coerceAtLeast(0)
        } else {
            (views.pageStage.height - views.pageStage.paddingTop - views.pageStage.paddingBottom).coerceAtLeast(0)
        }
    }

    private fun hasPreparedCurrentSnapshot(chapterIndex: Int, pageIndex: Int): Boolean {
        return hasPreparedSnapshot(
            state.currentPageSnapshotBitmap,
            views.pageCurrent,
            state.preparedCurrentSnapshotChapterIndex,
            state.preparedCurrentSnapshotPageIndex,
            chapterIndex,
            pageIndex,
        )
    }

    private fun hasPreparedIncomingSnapshot(chapterIndex: Int, pageIndex: Int): Boolean {
        return hasPreparedSnapshot(
            state.incomingPageSnapshotBitmap,
            views.pageIncoming,
            state.preparedIncomingSnapshotChapterIndex,
            state.preparedIncomingSnapshotPageIndex,
            chapterIndex,
            pageIndex,
        )
    }

    private fun hasPreparedNextSnapshot(chapterIndex: Int, pageIndex: Int): Boolean {
        return hasPreparedSnapshot(
            state.nextPageSnapshotBitmap,
            views.pageIncoming,
            state.preparedNextSnapshotChapterIndex,
            state.preparedNextSnapshotPageIndex,
            chapterIndex,
            pageIndex,
        )
    }

    private fun hasPreparedPreviousSnapshot(chapterIndex: Int, pageIndex: Int): Boolean {
        return hasPreparedSnapshot(
            state.previousPageSnapshotBitmap,
            views.pageIncoming,
            state.preparedPreviousSnapshotChapterIndex,
            state.preparedPreviousSnapshotPageIndex,
            chapterIndex,
            pageIndex,
        )
    }

    private fun hasPreparedDirectionalSnapshot(direction: Int, chapterIndex: Int, pageIndex: Int): Boolean {
        return if (direction >= 0) hasPreparedNextSnapshot(chapterIndex, pageIndex) else hasPreparedPreviousSnapshot(chapterIndex, pageIndex)
    }

    private fun setActiveIncomingSnapshot(bitmap: Bitmap?, chapterIndex: Int, pageIndex: Int) {
        state.incomingPageSnapshotBitmap = bitmap
        if (bitmap == null) {
            state.preparedIncomingSnapshotChapterIndex = -1
            state.preparedIncomingSnapshotPageIndex = -1
            return
        }
        state.preparedIncomingSnapshotChapterIndex = chapterIndex
        state.preparedIncomingSnapshotPageIndex = pageIndex
    }

    private fun hasPreparedSnapshot(
        bitmap: Bitmap?,
        source: View?,
        preparedChapterIndex: Int,
        preparedPageIndex: Int,
        chapterIndex: Int,
        pageIndex: Int,
    ): Boolean {
        if (bitmap == null || bitmap.isRecycled || source == null || preparedChapterIndex != chapterIndex || preparedPageIndex != pageIndex) {
            return false
        }
        val expectedWidth = snapshotDimensionFor(source, true)
        val expectedHeight = snapshotDimensionFor(source, false)
        return expectedWidth > 0 && expectedHeight > 0 && bitmap.width == expectedWidth && bitmap.height == expectedHeight
    }

    private fun promoteIncomingSnapshotToCurrent(
        chapterIndex: Int,
        pageIndex: Int,
        previousChapterIndex: Int,
        previousPageIndex: Int,
        direction: Int,
    ) {
        if (!hasPreparedIncomingSnapshot(chapterIndex, pageIndex)) {
            state.preparedCurrentSnapshotChapterIndex = -1
            state.preparedCurrentSnapshotPageIndex = -1
            return
        }
        val previousCurrentWasPrepared = hasPreparedCurrentSnapshot(previousChapterIndex, previousPageIndex)
        val previousCurrentBitmap = state.currentPageSnapshotBitmap
        val promotedBitmap = state.incomingPageSnapshotBitmap
        val previousNextBitmap = state.nextPageSnapshotBitmap
        val previousPreviousBitmap = state.previousPageSnapshotBitmap

        state.currentPageSnapshotBitmap = state.incomingPageSnapshotBitmap
        state.preparedCurrentSnapshotChapterIndex = chapterIndex
        state.preparedCurrentSnapshotPageIndex = pageIndex
        setActiveIncomingSnapshot(null, -1, -1)
        state.preparedIncomingSnapshotChapterIndex = -1
        state.preparedIncomingSnapshotPageIndex = -1

        state.nextPageSnapshotBitmap = if (previousNextBitmap === promotedBitmap) null else previousNextBitmap
        state.previousPageSnapshotBitmap = if (previousPreviousBitmap === promotedBitmap) null else previousPreviousBitmap
        state.preparedNextSnapshotChapterIndex = -1
        state.preparedNextSnapshotPageIndex = -1
        state.preparedPreviousSnapshotChapterIndex = -1
        state.preparedPreviousSnapshotPageIndex = -1

        if (!previousCurrentWasPrepared || previousCurrentBitmap == null || previousCurrentBitmap.isRecycled || previousCurrentBitmap === promotedBitmap) {
            return
        }
        if (direction >= 0) {
            val spare = state.previousPageSnapshotBitmap
            state.previousPageSnapshotBitmap = previousCurrentBitmap
            state.preparedPreviousSnapshotChapterIndex = previousChapterIndex
            state.preparedPreviousSnapshotPageIndex = previousPageIndex
            if (state.nextPageSnapshotBitmap == null && spare != null && spare !== previousCurrentBitmap && spare !== promotedBitmap) {
                state.nextPageSnapshotBitmap = spare
            }
            return
        }
        val spare = state.nextPageSnapshotBitmap
        state.nextPageSnapshotBitmap = previousCurrentBitmap
        state.preparedNextSnapshotChapterIndex = previousChapterIndex
        state.preparedNextSnapshotPageIndex = previousPageIndex
        if (state.previousPageSnapshotBitmap == null && spare != null && spare !== previousCurrentBitmap && spare !== promotedBitmap) {
            state.previousPageSnapshotBitmap = spare
        }
    }

    private fun drawSnapshotBaseLayer(canvas: Canvas, source: View) {
        canvas.drawColor(state.currentReaderPageColor)
        if (views.readerBackgroundImage.visibility != View.VISIBLE || views.readerBackgroundImage.drawable == null) return
        val sourceLocation = IntArray(2)
        val backgroundLocation = IntArray(2)
        source.getLocationOnScreen(sourceLocation)
        views.readerBackgroundImage.getLocationOnScreen(backgroundLocation)
        canvas.save()
        canvas.translate(
            (backgroundLocation[0] - sourceLocation[0]).toFloat(),
            (backgroundLocation[1] - sourceLocation[1]).toFloat(),
        )
        views.readerBackgroundImage.draw(canvas)
        canvas.restore()
    }

    private fun drawHudSnapshotLayer(canvas: Canvas, source: View, chapterIndex: Int, pageIndex: Int) {
        val hudSnapshot = chrome.captureHudSnapshotState()
        try {
            chrome.updateReaderHudForPageSnapshot(chapterIndex, pageIndex)
            drawHudContainerSnapshot(canvas, source, views.hudTopContainer, false)
            drawHudContainerSnapshot(canvas, source, views.hudBottomContainer, true)
        } finally {
            chrome.restoreHudSnapshotState(hudSnapshot)
        }
    }

    private fun drawHudContainerSnapshot(canvas: Canvas, source: View, hudContainer: View?, alignBottom: Boolean) {
        if (hudContainer == null || source.width <= 0 || source.height <= 0) return
        val previousVisibility = hudContainer.visibility
        val previousAlpha = hudContainer.alpha
        val previousTranslationX = hudContainer.translationX
        val previousTranslationY = hudContainer.translationY
        val previousLeft = hudContainer.left
        val previousTop = hudContainer.top
        val previousRight = hudContainer.right
        val previousBottom = hudContainer.bottom

        val width = source.width
        val height = source.height
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        hudContainer.measure(widthSpec, heightSpec)
        val measuredHeight = hudContainer.measuredHeight.coerceAtLeast(0)
        if (measuredHeight <= 0) {
            restoreHudContainerAfterSnapshot(
                hudContainer,
                previousVisibility,
                previousAlpha,
                previousTranslationX,
                previousTranslationY,
                previousLeft,
                previousTop,
                previousRight,
                previousBottom,
            )
            return
        }
        val top = if (alignBottom) (height - measuredHeight).coerceAtLeast(0) else 0
        hudContainer.visibility = View.VISIBLE
        hudContainer.alpha = 1f
        hudContainer.translationX = 0f
        hudContainer.translationY = 0f
        hudContainer.layout(0, top, width, top + measuredHeight)
        canvas.save()
        canvas.translate(0f, top.toFloat())
        hudContainer.draw(canvas)
        canvas.restore()
        restoreHudContainerAfterSnapshot(
            hudContainer,
            previousVisibility,
            previousAlpha,
            previousTranslationX,
            previousTranslationY,
            previousLeft,
            previousTop,
            previousRight,
            previousBottom,
        )
    }

    private fun restoreHudContainerAfterSnapshot(
        hudContainer: View,
        visibility: Int,
        alpha: Float,
        translationX: Float,
        translationY: Float,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        hudContainer.layout(left, top, right, bottom)
        hudContainer.visibility = visibility
        hudContainer.alpha = alpha
        hudContainer.translationX = translationX
        hudContainer.translationY = translationY
    }

    private fun hideLiveHudDuringPaging() {
        setLiveHudAlphaForPaging(0f)
    }

    private fun showLiveHudAfterPaging() {
        setLiveHudAlphaForPaging(1f)
    }

    private fun setLiveHudAlphaForPaging(alpha: Float) {
        setHudContainerAlpha(views.hudTopContainer, alpha)
        setHudContainerAlpha(views.hudBottomContainer, alpha)
    }

    private fun setHudContainerAlpha(hudContainer: View?, alpha: Float) {
        if (hudContainer == null) return
        hudContainer.animate().cancel()
        hudContainer.alpha = alpha
    }

    private fun activeCurrentPageLayer(): View {
        return if (state.pagingSnapshotsVisible) views.pageSnapshotCurrent else views.pageCurrent
    }

    private fun activeIncomingPageLayer(): View {
        return if (state.pagingSnapshotsVisible) views.pageSnapshotIncoming else views.pageIncoming
    }

    private fun arrangePagingLayers(mode: String) {
        if (mode == "simulation") {
            views.simulationPageTurnView.bringToFront()
            return
        }
        val currentLayer = activeCurrentPageLayer()
        val incomingLayer = activeIncomingPageLayer()
        if (mode == "scroll") {
            currentLayer.bringToFront()
            incomingLayer.bringToFront()
        } else {
            incomingLayer.bringToFront()
            currentLayer.bringToFront()
        }
        views.pageFoldHighlight.bringToFront()
    }

    private fun applyPagingVisuals(mode: String, direction: Int, progress: Float, touchY: Float) {
        if (mode == "simulation" && state.simulationFinishCoverVisible) {
            keepSimulationFinishCoverOnTop()
            return
        }
        if (mode == "simulation") {
            applySimulationPagingVisuals(direction)
            return
        }
        val width = views.pageStage.width.coerceAtLeast(ui.dp(240)).toFloat()
        val height = views.pageStage.height.coerceAtLeast(ui.dp(320)).toFloat()
        val safeProgress = progress.coerceIn(0f, 1f)
        val widthPx = kotlin.math.max(1, kotlin.math.round(width).toInt())
        val heightPx = kotlin.math.max(1, kotlin.math.round(height).toInt())
        val currentLayer = activeCurrentPageLayer()
        val incomingLayer = activeIncomingPageLayer()
        resetAnimatedPage(currentLayer)
        resetAnimatedPage(incomingLayer)
        incomingLayer.visibility = View.VISIBLE
        clearSimulationPagingLayer()
        hideInteractiveFoldEffects()

        if (mode == "cover") {
            val revealWidth = width * safeProgress
            incomingLayer.alpha = 1f
            if (direction > 0) {
                incomingLayer.bringToFront()
                currentLayer.bringToFront()
                currentLayer.translationX = -width * safeProgress
                applyRevealedIncomingClip(incomingLayer, direction, revealWidth, widthPx, heightPx)
            } else {
                currentLayer.bringToFront()
                incomingLayer.bringToFront()
                currentLayer.translationX = 0f
                incomingLayer.translationX = -width * (1f - safeProgress)
                incomingLayer.clipBounds = null
            }
            hideInteractiveShadow()
            return
        }

        if (mode == "scroll") {
            val offsetY = (if (direction > 0) 1f else -1f) * height * safeProgress
            currentLayer.translationY = -offsetY
            incomingLayer.translationY = (if (direction > 0) 1f else -1f) * height * (1f - safeProgress)
            incomingLayer.alpha = 0.94f + 0.06f * safeProgress
            hideInteractiveShadow()
            return
        }

        val revealWidth = width * safeProgress
        currentLayer.translationX = (if (direction > 0) -1f else 1f) * revealWidth
        incomingLayer.translationX = if (direction > 0) width - revealWidth else -width + revealWidth
        incomingLayer.alpha = 0.95f + 0.05f * safeProgress
        if (direction > 0) {
            applyPageClip(incomingLayer, 0, kotlin.math.round(revealWidth).toInt(), heightPx)
            hideInteractiveShadow()
        } else {
            applyPageClip(incomingLayer, widthPx - kotlin.math.round(revealWidth).toInt(), widthPx, heightPx)
            hideInteractiveShadow()
        }
    }

    private fun applySimulationPagingVisuals(direction: Int) {
        if (state.currentPageSnapshotBitmap == null || state.incomingPageSnapshotBitmap == null) {
            views.simulationPageTurnView.clear()
            return
        }
        if (!views.simulationPageTurnView.isActive()) {
            views.simulationPageTurnView.bringToFront()
        }
        views.simulationPageTurnView.setRenderQuality(resolveSimulationRenderQuality())
        views.simulationPageTurnView.setPagingState(
            direction,
            state.currentPageSnapshotBitmap,
            state.incomingPageSnapshotBitmap,
            state.interactiveStartX,
            state.interactiveStartY,
            state.interactiveTouchX,
            state.interactiveTouchY,
            simulationTurnMode(),
            state.currentReaderPageColor,
        )
    }

    private fun applyPageClip(view: View, left: Int, right: Int, height: Int) {
        val width = view.width.coerceAtLeast(1)
        val safeLeft = ui.clamp(left, 0, width)
        val safeRight = ui.clamp(right, 0, width)
        val safeHeight = height.coerceAtLeast(1)
        if (safeRight <= safeLeft) {
            view.clipBounds = Rect(0, 0, 0, safeHeight)
            return
        }
        view.clipBounds = Rect(safeLeft, 0, safeRight, safeHeight)
    }

    private fun applyRevealedIncomingClip(view: View, direction: Int, revealWidth: Float, width: Int, height: Int) {
        val revealPx = kotlin.math.round(revealWidth).toInt().coerceAtLeast(0)
        val seamBleedPx = if (revealPx > 0) ui.dp(2) else 0
        if (direction > 0) {
            applyPageClip(view, width - revealPx - seamBleedPx, width, height)
            return
        }
        applyPageClip(view, 0, revealPx + seamBleedPx, height)
    }

    private fun clearSimulationPagingLayer() {
        views.simulationPageTurnView.clear()
    }

    private fun captureInteractiveStartPoint(startX: Float, startY: Float) {
        if (isSimulationFlipMode() && state.interactiveDirection != 0) {
            captureSimulationStartPoint(state.interactiveDirection, startY, false)
            return
        }
        state.interactiveStartX = sanitizeStageTouchX(startX)
        state.interactiveStartY = sanitizeStageTouchY(startY)
        state.interactiveTouchX = state.interactiveStartX
        state.interactiveTouchY = state.interactiveStartY
    }

    private fun updateInteractiveTouchPoint(touchX: Float, touchY: Float) {
        state.interactiveTouchX = sanitizeStageTouchX(touchX)
        if (isSimulationSpineBoundTurn()) {
            state.interactiveTouchX = sanitizeSimulationSpineBoundTouchX(state.interactiveTouchX)
        }
        state.interactiveTouchY = sanitizeStageTouchY(touchY)
    }

    private fun initializeSimulationAutoStart(direction: Int, height: Float) {
        state.interactiveDirection = direction
        val tapY = if (state.lastTapY >= 0) {
            state.lastTapY
        } else if (direction > 0) {
            height * 0.85f
        } else {
            height * 0.15f
        }
        captureSimulationStartPoint(direction, tapY, true)
        state.lastTapY = -1f
    }

    private fun captureSimulationStartPoint(direction: Int, startY: Float, expandedCornerFold: Boolean) {
        val width = views.pageStage.width.coerceAtLeast(ui.dp(240)).toFloat()
        val cornerInset = if (expandedCornerFold) simulationAutoStartInsetPx(width) else simulationCornerInsetPx()
        state.interactiveStartX = sanitizeStageTouchX(if (direction > 0) width - cornerInset else cornerInset)
        state.interactiveStartY = sanitizeStageTouchY(
            if (expandedCornerFold) {
                resolveSimulationAutoStartY(direction, startY, width)
            } else {
                resolveSimulationStartY(startY)
            },
        )
        state.interactiveTouchX = state.interactiveStartX
        state.interactiveTouchY = state.interactiveStartY
    }

    private fun resolveSimulationStartY(touchY: Float): Float {
        val height = views.pageStage.height.coerceAtLeast(ui.dp(320)).toFloat()
        val safeTouchY = touchY.coerceIn(0f, height)
        if (safeTouchY < height / 3f) return 0.1f
        if (safeTouchY > height * 2f / 3f) return height - 0.1f
        return height / 2f
    }

    private fun simulationCornerInsetPx(): Float {
        return kotlin.math.max(2f, ui.dp(if (isPhoneReaderViewport()) 2 else 4).toFloat())
    }

    private fun simulationAutoStartInsetPx(stageWidth: Float): Float {
        val height = views.pageStage.height.coerceAtLeast(ui.dp(320)).toFloat()
        val pageWidth = if (isSimulationSpineBoundTurn()) stageWidth * 0.5f else stageWidth
        return kotlin.math.max(simulationCornerInsetPx(), kotlin.math.min(pageWidth * 0.28f, height / 10f))
    }

    private fun resolveSimulationAutoStartY(direction: Int, touchY: Float, stageWidth: Float): Float {
        val height = views.pageStage.height.coerceAtLeast(ui.dp(320)).toFloat()
        val safeTouchY = touchY.coerceIn(0f, height)
        val inset = simulationAutoStartInsetPx(stageWidth)
        if (safeTouchY < height / 3f) return inset
        if (safeTouchY > height * 2f / 3f) return height - inset
        return if (direction > 0) height - inset else inset
    }

    private fun sanitizeStageTouchX(value: Float): Float {
        val width = views.pageStage.width.coerceAtLeast(1).toFloat()
        if (isSimulationSpineBoundTurn()) {
            val pageWidth = width * 0.5f
            return value.coerceIn(-pageWidth * 0.14f, width + pageWidth * 0.14f)
        }
        if (runtime.settingsStore.flipMode == "simulation") {
            return value.coerceIn(0.1f, width - 0.1f)
        }
        return value.coerceIn(-width * 3f, width * 4f)
    }

    private fun sanitizeStageTouchY(value: Float): Float {
        val height = views.pageStage.height.coerceAtLeast(1).toFloat()
        if (runtime.settingsStore.flipMode == "simulation") {
            return value.coerceIn(0.1f, height - 0.1f)
        }
        return value.coerceIn(-height * 3f, height * 4f)
    }

    private fun resolveSimulationTargetTouchX(direction: Int, commit: Boolean): Float {
        val width = views.pageStage.width.coerceAtLeast(ui.dp(240)).toFloat()
        if (isSimulationSpineBoundTurn()) {
            val pageWidth = width * 0.5f
            val cornerInset = simulationCornerInsetPx()
            if (commit) return if (direction > 0) -pageWidth * 0.12f else width + pageWidth * 0.12f
            return if (direction > 0) width - cornerInset else cornerInset
        }
        if (commit) return if (direction > 0) -width * 1.12f else width - 0.1f
        val cornerInset = simulationCornerInsetPx()
        return if (direction > 0) width - cornerInset else cornerInset
    }

    private fun resolveSimulationTargetTouchY(direction: Int): Float {
        val height = views.pageStage.height.coerceAtLeast(ui.dp(320)).toFloat()
        return if (state.interactiveStartY < height / 3f) {
            0.1f
        } else if (state.interactiveStartY > height * 2f / 3f) {
            height - 0.1f
        } else {
            0.1f
        }
    }

    private fun isSimulationDiagonalStartZone(startY: Float): Boolean {
        val height = views.pageStage.height.coerceAtLeast(ui.dp(320)).toFloat()
        return startY < height / 3f || startY > height * 2f / 3f
    }

    private fun isSimulationFlipMode(): Boolean {
        return runtime.settingsStore.flipMode == "simulation"
    }

    private fun isSimulationSpineBoundTurn(): Boolean {
        return isSimulationOuterPageTurnActive() && views.pageStage.width > 0
    }

    private fun isSimulationOuterPageTurnActive(): Boolean {
        return isSimulationFlipMode() && content.isDoublePageActive() && runtime.settingsStore.simulationDoublePageTurnMode == "outerPage"
    }

    private fun simulationTurnMode(): Int {
        if (!isSimulationFlipMode() || !content.isDoublePageActive()) return SimulationPageTurnView.TURN_MODE_SINGLE
        return if (runtime.settingsStore.simulationDoublePageTurnMode == "outerPage") {
            SimulationPageTurnView.TURN_MODE_OUTER_PAGE
        } else {
            SimulationPageTurnView.TURN_MODE_SPREAD
        }
    }

    private fun interactiveProgressWidth(): Float {
        return views.pageStage.width.coerceAtLeast(ui.dp(240)).toFloat()
    }

    private fun sanitizeSimulationSpineBoundTouchX(touchX: Float): Float {
        val width = views.pageStage.width.coerceAtLeast(ui.dp(240)).toFloat()
        val pageWidth = width * 0.5f
        val minX = if (state.interactiveDirection > 0) -pageWidth * 0.14f else 0.1f
        val maxX = if (state.interactiveDirection > 0) width - 0.1f else width + pageWidth * 0.14f
        if (maxX < minX) return touchX
        return touchX.coerceIn(minX, maxX)
    }

    private fun isPhoneReaderViewport(): Boolean {
        return ReaderDisplayModeHelper.isPhoneViewport(
            activity,
            views.pageStage.width,
            views.pageStage.height,
        )
    }

    private fun isTabletReaderViewport(): Boolean {
        return ReaderDisplayModeHelper.isTabletViewport(
            activity,
            views.pageStage.width,
            views.pageStage.height,
        )
    }

    private fun resolveSimulationRenderQuality(): Int {
        val width = views.pageStage.width.coerceAtLeast(ui.dp(240))
        val height = views.pageStage.height.coerceAtLeast(ui.dp(320))
        val viewportPixels = width.coerceAtLeast(1).toLong() * height.coerceAtLeast(1).toLong()
        val doublePage = content.isDoublePageActive()
        if (isLowMemoryDevice() || viewportPixels >= LOW_VIEWPORT_PIXELS || (doublePage && viewportPixels >= DOUBLE_PAGE_LOW_VIEWPORT_PIXELS)) {
            return SimulationPageTurnView.RENDER_QUALITY_LOW
        }
        if (doublePage || viewportPixels >= BALANCED_VIEWPORT_PIXELS || runtimeMaxMemoryBytes <= BALANCED_MEMORY_MAX_HEAP_BYTES) {
            return SimulationPageTurnView.RENDER_QUALITY_BALANCED
        }
        return SimulationPageTurnView.RENDER_QUALITY_FULL
    }

    private fun isLowMemoryDevice(): Boolean {
        if (runtimeMaxMemoryBytes > 0 && runtimeMaxMemoryBytes <= LOW_MEMORY_MAX_HEAP_BYTES) return true
        if (lowRamDevice == null) {
            val service = activity.getSystemService(Context.ACTIVITY_SERVICE)
            lowRamDevice = service is ActivityManager && service.isLowRamDevice
        }
        return lowRamDevice == true
    }

    private fun resetInteractiveTouchState() {
        state.interactiveStartX = 0f
        state.interactiveStartY = 0f
        state.interactiveTouchX = 0f
        state.interactiveTouchY = 0f
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private fun normalizedAnimationValue(value: Float, start: Float, end: Float): Float {
        val range = end - start
        if (kotlin.math.abs(range) < 0.0001f) return 1f
        return ((value - start) / range).coerceIn(0f, 1f)
    }

    private fun resetShadowView() {
        resetOverlayView(views.pageShadow)
        resetOverlayView(views.pageFoldShadow)
        resetOverlayView(views.pageFoldHighlight)
    }

    private fun resetOverlayView(view: View?) {
        if (view == null) return
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.GONE
        view.translationX = 0f
        view.rotation = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private class PageTarget(
        val chapterIndex: Int,
        val pageIndex: Int,
    )

    companion object {
        private val PAGE_SLIDE_INTERPOLATOR = DecelerateInterpolator(1.35f)
        private val PAGE_TURN_INTERPOLATOR = DecelerateInterpolator(0.95f)
        private const val PHONE_SIMULATION_DIAGONAL_RATIO = 1.35f
        private const val TABLET_SIMULATION_DIAGONAL_RATIO = 1.25f
        private const val DEFAULT_DIAGONAL_RATIO = 1.15f
        private const val SIMULATION_FINISH_COVER_PROGRESS = 0.9995f
        private const val OUTER_PAGE_FINISH_COVER_PROGRESS = 0.95f
        private const val PAGE_TURN_PROGRESS_RATIO = 0.42f
        private const val SCROLL_PROGRESS_RATIO = 0.55f
        private const val DEFAULT_PROGRESS_RATIO = 0.5f
        private const val MIN_PROGRESS_THRESHOLD = 0.12f
        private const val MAX_PROGRESS_THRESHOLD = 0.42f
        private const val VELOCITY_THRESHOLD_BASE = 950f
        private const val VELOCITY_THRESHOLD_MIN = 520f
        private const val VELOCITY_THRESHOLD_MAX = 1600f
        private const val SNAPSHOT_WARMUP_PREDRAW_PASSES = 2
        private const val SNAPSHOT_WARMUP_PREDRAW_FALLBACK_MS = 96L
        private const val FINISH_SWAP_PREDRAW_PASSES = 2
        private const val FINISH_SWAP_PREDRAW_FALLBACK_MS = 240L
        private const val FINISH_SWAP_COVER_HOLD_MS = 96L
        private const val SIMULATION_PRIMARY_WARMUP_DELAY_MS = 72L
        private const val SIMULATION_SECONDARY_WARMUP_DELAY_MS = 180L
        private const val LOW_MEMORY_MAX_HEAP_BYTES = 192L * 1024L * 1024L
        private const val BALANCED_MEMORY_MAX_HEAP_BYTES = 256L * 1024L * 1024L
        private const val LOW_VIEWPORT_PIXELS = 3_600_000L
        private const val DOUBLE_PAGE_LOW_VIEWPORT_PIXELS = 2_600_000L
        private const val BALANCED_VIEWPORT_PIXELS = 2_000_000L
    }
}



