package com.metahumanz.pacilread.reader.modern.paging;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.SimulationPageTurnView;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;

import java.util.List;

public final class ReaderPagingAnimator {
    private static final DecelerateInterpolator PAGE_SLIDE_INTERPOLATOR = new DecelerateInterpolator(1.35f);
    private static final DecelerateInterpolator PAGE_TURN_INTERPOLATOR = new DecelerateInterpolator(0.95f);
    private static final float PHONE_SIMULATION_DIAGONAL_RATIO = 1.35f;
    private static final float TABLET_SIMULATION_DIAGONAL_RATIO = 1.25f;
    private static final float DEFAULT_DIAGONAL_RATIO = 1.15f;
    private static final int FINISH_SWAP_PREDRAW_PASSES = 2;
    private static final long FINISH_SWAP_PREDRAW_FALLBACK_MS = 240L;
    private static final long FINISH_SWAP_COVER_HOLD_MS = 96L;
    private static final int SNAPSHOT_WARMUP_PREDRAW_PASSES = 2;
    private static final long SNAPSHOT_WARMUP_PREDRAW_FALLBACK_MS = 96L;
    private static final float SIMULATION_FINISH_COVER_PROGRESS = 0.9995f;
    private static final float OUTER_PAGE_FINISH_COVER_PROGRESS = 0.95f;

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final Canvas pagingSnapshotCanvas = new Canvas();
    private final Runnable pagingSnapshotWarmupRunnable = this::warmPreparedPagingSnapshots;

    private ReaderNavigationController navigation;
    private ReaderContentController content;
    private ReaderChromeController chrome;
    private int pagingSnapshotWarmupRequestId = 0;

    public ReaderPagingAnimator(
            ModernReaderActivity activity,
            ReaderRuntime runtime,
            ReaderViewRefs views,
            ReaderSessionState state,
            ReaderUiUtils ui
    ) {
        this.activity = activity;
        this.runtime = runtime;
        this.views = views;
        this.state = state;
        this.ui = ui;
    }

    public void attachControllers(
            ReaderNavigationController navigation,
            ReaderContentController content,
            ReaderChromeController chrome
    ) {
        this.navigation = navigation;
        this.content = content;
        this.chrome = chrome;
    }

    public boolean handleReaderPagingTouchEvent(MotionEvent event) {
        if (state.controlsVisible
                || state.chapters.isEmpty()
                || views.pageStage == null
                || views.pageStage.getWidth() == 0
                || views.pageStage.getHeight() == 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (state.interactivePaging) {
                    cancelInteractiveAnimator();
                }
                state.pagingGestureCandidate = chrome.isInsideView(event, views.pageStage) && !state.isAnimating;
                state.pagingDownX = localTouchX(event);
                state.pagingDownY = localTouchY(event);
                state.pagingLastX = state.pagingDownX;
                state.pagingLastEventTime = event.getEventTime();
                state.pagingLastMoveDeltaX = 0f;
                state.pagingVelocityX = 0f;
                state.interactiveCancel = false;
                captureInteractiveStartPoint(state.pagingDownX, state.pagingDownY);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!state.pagingGestureCandidate && !state.interactivePaging) {
                    return false;
                }
                updatePagingVelocity(event);
                float currentTouchX = localTouchX(event);
                float currentTouchY = localTouchY(event);
                if (!state.interactivePaging) {
                    float deltaX = currentTouchX - state.pagingDownX;
                    float deltaY = currentTouchY - state.pagingDownY;
                    float slop = effectivePagingTouchSlop(event);
                    float distanceSquare = deltaX * deltaX + deltaY * deltaY;
                    if (distanceSquare <= slop * slop) {
                        return false;
                    }
                    boolean simulationDiagonalStart = isSimulationFlipMode()
                            && isSimulationDiagonalStartZone(state.pagingDownY);
                    float diagonalRatio = horizontalGestureRatio();
                    if (!simulationDiagonalStart && Math.abs(deltaY) > Math.abs(deltaX) * diagonalRatio) {
                        state.pagingGestureCandidate = false;
                        return false;
                    }
                    float minHorizontalDelta = simulationDiagonalStart
                            ? Math.max(1f, slop * 0.5f)
                            : Math.abs(deltaY) / diagonalRatio;
                    if (Math.abs(deltaX) <= minHorizontalDelta) {
                        return false;
                    }
                    int direction = deltaX < 0f ? 1 : -1;
                    if (!prepareInteractivePaging(direction, state.pagingDownX, state.pagingDownY)) {
                        state.pagingGestureCandidate = false;
                        return false;
                    }
                }
                updateInteractiveTouchPoint(currentTouchX, currentTouchY);
                updateInteractiveCancelState();
                float width = interactiveProgressWidth();
                float deltaX = currentTouchX - state.pagingDownX;
                float progress = state.interactiveDirection > 0 ? -deltaX / width : deltaX / width;
                applyInteractivePagingProgress(progress, state.interactiveTouchY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!state.interactivePaging) {
                    state.pagingGestureCandidate = false;
                    return false;
                }
                updatePagingVelocity(event);
                if (event.getActionMasked() != MotionEvent.ACTION_CANCEL && isSimulationFlipMode()) {
                    updateInteractiveTouchPoint(localTouchX(event), localTouchY(event));
                    if (shouldCompleteSimulationDragAtEdge(state.interactiveTouchX)) {
                        finishInteractivePaging(true);
                        return true;
                    }
                }
                boolean commit = event.getActionMasked() != MotionEvent.ACTION_CANCEL && shouldCommitInteractivePaging();
                finishInteractivePaging(commit);
                return true;
            default:
                return false;
        }
    }

    public boolean handleReaderVolumeKeyEvent(KeyEvent event) {
        if (event == null || state.controlsVisible) {
            return false;
        }
        String action = null;
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            action = runtime.settingsStore.getVolumeKeyUpAction();
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            action = runtime.settingsStore.getVolumeKeyDownAction();
        }
        if (action == null || "system".equals(action)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            state.lastTapY = -1f;
            navigation.requestTapPageTurn("page_up".equals(action) ? -1 : 1);
            return true;
        }
        return event.getAction() == KeyEvent.ACTION_UP;
    }

    public void animateTransition(int targetChapterIndex, int targetPageIndex, int direction) {
        long token = ++state.animationToken;
        state.isAnimating = true;
        rememberAnimationTarget(targetChapterIndex, targetPageIndex);
        String mode = runtime.settingsStore.getFlipMode();
        if (!"simulation".equals(mode)) {
            clearStableSimulationCover(false);
        }
        float width = Math.max(views.pageStage.getWidth(), ui.dp(240));
        float height = Math.max(views.pageStage.getHeight(), ui.dp(320));
        cancelInteractiveAnimator();
        resetAnimatedPage(views.pageCurrent);
        resetAnimatedPage(views.pageIncoming);
        if (views.simulationPageTurnView != null) {
            views.simulationPageTurnView.clear();
        }
        resetShadowView();
        views.pageIncoming.setVisibility(View.GONE);
        if ("none".equals(mode)) {
            finishAnimation(targetChapterIndex, targetPageIndex, direction, token);
            return;
        }
        if ("simulation".equals(mode)) {
            initializeSimulationAutoStart(direction, height);
        } else {
            resetInteractiveTouchState();
        }
        navigation.bindIncomingSpread(targetChapterIndex, targetPageIndex);
        preparePagingSnapshots(targetChapterIndex, targetPageIndex, direction);
        arrangePagingLayers(mode);
        applyPagingVisuals(mode, direction, 0f, "simulation".equals(mode) ? state.interactiveTouchY : height * 0.5f);
        state.interactiveAnimator = ValueAnimator.ofFloat(0f, 1f);
        state.interactiveAnimator.setDuration(readerFlipDurationMs());
        state.interactiveAnimator.setInterpolator("simulation".equals(mode) ? PAGE_TURN_INTERPOLATOR : PAGE_SLIDE_INTERPOLATOR);
        final float startTouchX = state.interactiveTouchX;
        final float startTouchY = state.interactiveTouchY;
        final float targetTouchX = "simulation".equals(mode)
                ? resolveSimulationTargetTouchX(direction, true)
                : 0f;
        final float targetTouchY = "simulation".equals(mode)
                ? resolveSimulationTargetTouchY(direction)
                : height * 0.5f;
        final float finishCoverProgress = "simulation".equals(mode)
                ? simulationFinishCoverProgressThreshold()
                : SIMULATION_FINISH_COVER_PROGRESS;
        final boolean[] cancelled = new boolean[]{false};
        final boolean[] finishCoverShown = new boolean[]{false};
        state.interactiveAnimator.addUpdateListener(animation -> {
            float animatedProgress = (float) animation.getAnimatedValue();
            if ("simulation".equals(mode)) {
                state.interactiveTouchX = lerp(startTouchX, targetTouchX, animatedProgress);
                state.interactiveTouchY = lerp(startTouchY, targetTouchY, animatedProgress);
                if (animatedProgress >= finishCoverProgress) {
                    finishCoverShown[0] = showSimulationTargetSnapshotAndStopCurl(
                            targetChapterIndex,
                            targetPageIndex,
                            direction
                    ) || finishCoverShown[0];
                    if (finishCoverShown[0]) {
                        return;
                    }
                }
            }
            applyPagingVisuals(
                    mode,
                    direction,
                    animatedProgress,
                    "simulation".equals(mode) ? state.interactiveTouchY : height * 0.5f
            );
        });
        state.interactiveAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled[0] = true;
                state.interactiveAnimator = null;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                state.interactiveAnimator = null;
                if (cancelled[0] || token != state.animationToken) {
                    return;
                }
                if ("simulation".equals(mode)) {
                    if (!finishCoverShown[0]) {
                        showSimulationTargetSnapshotAndStopCurl(targetChapterIndex, targetPageIndex, direction);
                    }
                }
                finishAnimation(targetChapterIndex, targetPageIndex, direction, token);
            }
        });
        state.interactiveAnimator.start();
    }

    public long readerFlipDurationMs() {
        String mode = runtime.settingsStore.getFlipMode();
        long baseDuration = 180L;
        if ("none".equals(mode)) {
            return 0L;
        }
        if ("cover".equals(mode)) {
            baseDuration = 220L;
        } else if ("simulation".equals(mode)) {
            baseDuration = 360L;
        } else if ("scroll".equals(mode)) {
            baseDuration = 190L;
        }
        String speed = runtime.settingsStore.getFlipSpeed();
        if ("fast".equals(speed)) {
            return (long) (baseDuration * 0.6f);
        }
        if ("slow".equals(speed)) {
            return (long) (baseDuration * 1.5f);
        }
        return baseDuration;
    }

    public boolean ensurePageAreaReady(Runnable action) {
        if (views.pageBodyCurrent.getWidth() > 0 && views.pageBodyCurrent.getHeight() > 0) {
            return true;
        }
        views.pageStage.post(action);
        return false;
    }

    public void schedulePagingSnapshotWarmup() {
        if (views.pageStage == null) {
            return;
        }
        int requestId = ++pagingSnapshotWarmupRequestId;
        views.pageStage.removeCallbacks(pagingSnapshotWarmupRunnable);
        if (shouldSkipPagingSnapshotWarmup()) {
            return;
        }
        views.pageStage.postOnAnimation(() ->
                waitForPagingSnapshotWarmupPreDraw(requestId, SNAPSHOT_WARMUP_PREDRAW_PASSES)
        );
    }

    private boolean shouldSkipPagingSnapshotWarmup() {
        return views.pageStage == null
                || state.chapters.isEmpty()
                || state.controlsVisible
                || activity.isReaderEnterTransitionActive()
                || state.isAnimating
                || state.interactivePaging;
    }

    private void waitForPagingSnapshotWarmupPreDraw(int requestId, int remainingPasses) {
        if (requestId != pagingSnapshotWarmupRequestId || shouldSkipPagingSnapshotWarmup()) {
            return;
        }
        if (remainingPasses <= 0) {
            views.pageStage.post(() -> {
                if (requestId == pagingSnapshotWarmupRequestId && !shouldSkipPagingSnapshotWarmup()) {
                    pagingSnapshotWarmupRunnable.run();
                }
            });
            return;
        }
        View target = views.pageStage;
        if (target == null || !target.isAttachedToWindow()) {
            return;
        }
        final boolean[] completed = new boolean[]{false};
        final ViewTreeObserver.OnPreDrawListener[] listenerRef = new ViewTreeObserver.OnPreDrawListener[1];
        Runnable complete = () -> {
            if (completed[0]) {
                return;
            }
            completed[0] = true;
            removePreDrawListener(target, listenerRef[0]);
            waitForPagingSnapshotWarmupPreDraw(requestId, remainingPasses - 1);
        };
        listenerRef[0] = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                target.post(complete);
                return true;
            }
        };
        ViewTreeObserver observer = target.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            target.post(complete);
            return;
        }
        observer.addOnPreDrawListener(listenerRef[0]);
        if (views.pageCurrent != null) {
            views.pageCurrent.requestLayout();
            views.pageCurrent.invalidate();
        }
        target.invalidate();
        target.postDelayed(complete, SNAPSHOT_WARMUP_PREDRAW_FALLBACK_MS);
    }

    public void invalidatePreparedPagingSnapshots() {
        pagingSnapshotWarmupRequestId++;
        clearStableSimulationCover(true);
        state.simulationFinishCoverVisible = false;
        state.preparedCurrentSnapshotChapterIndex = -1;
        state.preparedCurrentSnapshotPageIndex = -1;
        state.preparedIncomingSnapshotChapterIndex = -1;
        state.preparedIncomingSnapshotPageIndex = -1;
        if (views.pageStage != null) {
            views.pageStage.removeCallbacks(pagingSnapshotWarmupRunnable);
        }
        state.preparedNextSnapshotChapterIndex = -1;
        state.preparedNextSnapshotPageIndex = -1;
        state.preparedPreviousSnapshotChapterIndex = -1;
        state.preparedPreviousSnapshotPageIndex = -1;
    }

    public void restoreLivePageLayers(boolean incomingVisible) {
        state.pagingSnapshotsVisible = false;
        state.simulationFinishCoverVisible = false;
        state.simulationStableCoverVisible = false;
        clearSimulationPagingLayer();
        if (views.pageSnapshotCurrent != null) {
            resetAnimatedPage(views.pageSnapshotCurrent);
            views.pageSnapshotCurrent.setVisibility(View.GONE);
        }
        if (views.pageSnapshotIncoming != null) {
            resetAnimatedPage(views.pageSnapshotIncoming);
            views.pageSnapshotIncoming.setVisibility(View.GONE);
        }
        views.pageCurrent.setVisibility(View.VISIBLE);
        views.pageIncoming.setVisibility(incomingVisible ? View.VISIBLE : View.GONE);
    }

    public void clearStableSimulationCoverForLiveView() {
        clearStableSimulationCover(true);
    }

    private void clearStableSimulationCover(boolean restoreHud) {
        if (!state.simulationStableCoverVisible) {
            return;
        }
        state.simulationStableCoverVisible = false;
        state.pagingSnapshotsVisible = false;
        if (views.pageSnapshotCurrent != null) {
            resetAnimatedPage(views.pageSnapshotCurrent);
            views.pageSnapshotCurrent.setVisibility(View.GONE);
        }
        if (views.pageSnapshotIncoming != null) {
            resetAnimatedPage(views.pageSnapshotIncoming);
            views.pageSnapshotIncoming.setVisibility(View.GONE);
        }
        if (restoreHud && !state.controlsVisible) {
            showLiveHudAfterPaging();
        }
        bringStableBookSpineOverlayToFront();
    }

    public void resetAnimatedPage(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setRotationX(0f);
        view.setRotationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setPivotX(view.getWidth() * 0.5f);
        view.setPivotY(view.getHeight() * 0.5f);
        view.setAlpha(1f);
        view.setClipBounds(null);
    }

    public void updateBodyTopMargin(TextView bodyView, int topMargin) {
        android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) bodyView.getLayoutParams();
        params.topMargin = topMargin;
        bodyView.setLayoutParams(params);
    }

    public void cancelInteractiveAnimator() {
        if (state.interactiveAnimator != null) {
            state.interactiveAnimator.cancel();
            state.interactiveAnimator = null;
        }
    }

    public boolean settleInterruptedPagingAnimation() {
        if (!state.isAnimating && !state.interactivePaging && state.interactiveAnimator == null) {
            return false;
        }
        int targetChapterIndex = state.animationTargetChapterIndex >= 0
                ? state.animationTargetChapterIndex
                : state.interactiveTargetChapterIndex;
        int targetPageIndex = state.animationTargetPageIndex >= 0
                ? state.animationTargetPageIndex
                : state.interactiveTargetPageIndex;
        state.animationToken++;
        cancelInteractiveAnimator();
        if (targetChapterIndex >= 0 && targetPageIndex >= 0 && !state.chapters.isEmpty()) {
            settleOnPage(targetChapterIndex, targetPageIndex);
            return true;
        }
        if (!state.chapters.isEmpty()) {
            settleOnPage(state.currentChapterIndex, state.currentPageIndex);
            return true;
        }
        cancelInteractivePaging();
        clearAnimationTarget();
        return false;
    }

    public void cancelInteractivePaging() {
        state.pagingGestureCandidate = false;
        state.interactivePaging = false;
        state.interactiveDirection = 0;
        state.interactiveCancel = false;
        state.interactiveProgress = 0f;
        state.simulationFinishCoverVisible = false;
        state.simulationStableCoverVisible = false;
        state.interactiveTargetChapterIndex = -1;
        state.interactiveTargetPageIndex = -1;
        clearAnimationTarget();
        resetInteractiveTouchState();
        restoreLivePageLayers(false);
        showLiveHudAfterPaging();
        resetAnimatedPage(views.pageCurrent);
        resetAnimatedPage(views.pageIncoming);
        views.pageIncoming.setVisibility(View.GONE);
        resetShadowView();
        state.isAnimating = false;
    }

    public void recyclePagingSnapshots() {
        invalidatePreparedPagingSnapshots();
        if (state.currentPageSnapshotBitmap != null && !state.currentPageSnapshotBitmap.isRecycled()) {
            state.currentPageSnapshotBitmap.recycle();
        }
        if (state.incomingPageSnapshotBitmap != null && !state.incomingPageSnapshotBitmap.isRecycled()) {
            state.incomingPageSnapshotBitmap.recycle();
        }
        if (state.nextPageSnapshotBitmap != null && !state.nextPageSnapshotBitmap.isRecycled()) {
            state.nextPageSnapshotBitmap.recycle();
        }
        if (state.previousPageSnapshotBitmap != null && !state.previousPageSnapshotBitmap.isRecycled()) {
            state.previousPageSnapshotBitmap.recycle();
        }
        state.currentPageSnapshotBitmap = null;
        state.incomingPageSnapshotBitmap = null;
        state.nextPageSnapshotBitmap = null;
        state.previousPageSnapshotBitmap = null;
    }

    public void removeWarmupCallbacks() {
        pagingSnapshotWarmupRequestId++;
        if (views.pageStage != null) {
            views.pageStage.removeCallbacks(pagingSnapshotWarmupRunnable);
        }
    }

    private void settleOnPage(int targetChapterIndex, int targetPageIndex) {
        int safeChapterIndex = ui.clamp(targetChapterIndex, 0, state.chapters.size() - 1);
        List<PageSlice> pages = content.getPagesForChapter(safeChapterIndex);
        if (pages.isEmpty()) {
            cancelInteractivePaging();
            return;
        }
        int safePageIndex = ui.clamp(targetPageIndex, 0, pages.size() - 1);
        state.currentChapterIndex = safeChapterIndex;
        state.currentPageIndex = safePageIndex;
        state.simulationFinishCoverVisible = false;
        state.simulationStableCoverVisible = false;
        navigation.bindCurrentSpread(safeChapterIndex, safePageIndex);
        restoreLivePageLayers(false);
        resetAnimatedPage(views.pageCurrent);
        resetAnimatedPage(views.pageIncoming);
        views.pageCurrent.setVisibility(View.VISIBLE);
        views.pageCurrent.bringToFront();
        views.pageIncoming.setVisibility(View.GONE);
        resetShadowView();
        resetInteractiveTouchState();
        state.pagingGestureCandidate = false;
        state.interactivePaging = false;
        state.interactiveDirection = 0;
        state.interactiveCancel = false;
        state.interactiveProgress = 0f;
        state.interactiveTargetChapterIndex = -1;
        state.interactiveTargetPageIndex = -1;
        clearAnimationTarget();
        state.pendingTapPagingDelta = 0;
        state.isAnimating = false;
        activity.markReadingActivity();
        chrome.updateUiAfterPageChange();
        showLiveHudAfterPaging();
        content.scheduleProgressSave();
        chrome.scheduleAutoHide();
        schedulePagingSnapshotWarmup();
    }

    private void updatePagingVelocity(MotionEvent event) {
        long now = event.getEventTime();
        long elapsed = Math.max(1L, now - state.pagingLastEventTime);
        float currentX = localTouchX(event);
        state.pagingLastMoveDeltaX = currentX - state.pagingLastX;
        state.pagingVelocityX = state.pagingLastMoveDeltaX / elapsed;
        state.pagingLastX = currentX;
        state.pagingLastEventTime = now;
    }

    private void updateInteractiveCancelState() {
        if (!state.interactivePaging || Math.abs(state.pagingLastMoveDeltaX) < 0.1f) {
            return;
        }
        float directionalMove = state.interactiveDirection > 0
                ? -state.pagingLastMoveDeltaX
                : state.pagingLastMoveDeltaX;
        state.interactiveCancel = directionalMove < 0f;
    }

    private boolean prepareInteractivePaging(int direction, float startX, float startY) {
        PageTarget target = resolveInteractiveTarget(direction);
        if (target == null) {
            return false;
        }
        String mode = runtime.settingsStore.getFlipMode();
        cancelInteractiveAnimator();
        if (!"simulation".equals(mode)) {
            clearStableSimulationCover(false);
        }
        state.interactivePaging = true;
        state.isAnimating = true;
        state.interactiveDirection = direction;
        state.interactiveTargetChapterIndex = target.chapterIndex;
        state.interactiveTargetPageIndex = target.pageIndex;
        rememberAnimationTarget(target.chapterIndex, target.pageIndex);
        state.interactiveProgress = 0f;
        captureInteractiveStartPoint(startX, startY);
        navigation.bindIncomingSpread(target.chapterIndex, target.pageIndex);
        views.pageIncoming.setVisibility(View.VISIBLE);
        resetAnimatedPage(views.pageCurrent);
        resetAnimatedPage(views.pageIncoming);
        resetShadowView();
        preparePagingSnapshots(target.chapterIndex, target.pageIndex, direction);
        arrangePagingLayers(runtime.settingsStore.getFlipMode());
        applyInteractivePagingProgress(0f, state.interactiveTouchY);
        return true;
    }

    private PageTarget resolveInteractiveTarget(int direction) {
        if (direction > 0) {
            List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex);
            int nextPageIndex = state.currentPageIndex + navigation.pageStep();
            if (nextPageIndex < pages.size()) {
                return new PageTarget(state.currentChapterIndex, nextPageIndex);
            }
            if (state.currentChapterIndex < state.chapters.size() - 1) {
                return new PageTarget(state.currentChapterIndex + 1, 0);
            }
            return null;
        }
        if (state.currentPageIndex > 0) {
            return new PageTarget(state.currentChapterIndex, Math.max(0, state.currentPageIndex - navigation.pageStep()));
        }
        if (state.currentChapterIndex > 0) {
            List<PageSlice> previousPages = content.getPagesForChapter(state.currentChapterIndex - 1);
            return new PageTarget(state.currentChapterIndex - 1, navigation.lastSpreadStart(previousPages));
        }
        return null;
    }

    private void applyInteractivePagingProgress(float progress, float touchY) {
        if (!state.interactivePaging) {
            return;
        }
        state.interactiveProgress = Math.max(0f, Math.min(1f, progress));
        applyPagingVisuals(runtime.settingsStore.getFlipMode(), state.interactiveDirection, state.interactiveProgress, touchY);
    }

    private void hideInteractiveShadow() {
        resetOverlayView(views.pageShadow);
    }

    private void updateInteractiveFoldHighlight(float edgeX, int direction, float alpha, float scaleX, float rotation) {
        updatePagingOverlay(views.pageFoldHighlight, edgeX, direction, alpha, 0.58f, scaleX, rotation, 0.34f);
    }

    private void hideInteractiveFoldEffects() {
        resetOverlayView(views.pageFoldShadow);
        resetOverlayView(views.pageFoldHighlight);
    }

    private void updatePagingOverlay(View overlay, float edgeX, int direction, float alpha, float anchorRatio, float scaleX, float rotation, float maxAlpha) {
        if (overlay == null) {
            return;
        }
        overlay.animate().cancel();
        float safeAlpha = Math.max(0f, Math.min(maxAlpha, alpha));
        if (safeAlpha <= 0f) {
            resetOverlayView(overlay);
            return;
        }
        float overlayWidth = Math.max(overlay.getWidth(), 1f);
        float safeAnchorRatio = Math.max(0f, Math.min(1f, anchorRatio));
        float anchorX = overlayWidth * safeAnchorRatio;
        overlay.setVisibility(View.VISIBLE);
        overlay.setPivotX(anchorX);
        overlay.setPivotY(Math.max(overlay.getHeight(), 1) * 0.5f);
        overlay.setTranslationX(edgeX - anchorX);
        overlay.setScaleX(direction > 0 ? scaleX : -scaleX);
        overlay.setScaleY(1f);
        overlay.setRotation(rotation);
        overlay.setAlpha(safeAlpha);
    }

    private boolean shouldCommitInteractivePaging() {
        float directionalVelocity = state.interactiveDirection > 0 ? -state.pagingVelocityX : state.pagingVelocityX;
        String mode = runtime.settingsStore.getFlipMode();
        float progressThreshold = progressCommitThreshold(mode);
        float velocityThreshold = velocityCommitThreshold(mode);
        if (state.interactiveProgress >= progressThreshold) {
            return true;
        }
        if (directionalVelocity > velocityThreshold) {
            return true;
        }
        if (directionalVelocity < -0.25f || (state.interactiveCancel && state.interactiveProgress < progressThreshold * 0.5f)) {
            return false;
        }
        return false;
    }

    private float effectivePagingTouchSlop(MotionEvent event) {
        float slop = Math.max(state.pagingTouchSlop, 1);
        if (event == null || !isPhoneReaderViewport()) {
            return slop;
        }
        int toolType = event.getPointerCount() > 0 ? event.getToolType(0) : MotionEvent.TOOL_TYPE_UNKNOWN;
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_MOUSE) {
            return Math.max(1f, slop * 0.75f);
        }
        if (isSimulationFlipMode()) {
            return Math.max(2f, Math.min(slop, ui.dp(4)));
        }
        return Math.max(slop, ui.dp(9));
    }

    private float horizontalGestureRatio() {
        if (!isSimulationFlipMode()) {
            return DEFAULT_DIAGONAL_RATIO;
        }
        if (isPhoneReaderViewport()) {
            return PHONE_SIMULATION_DIAGONAL_RATIO;
        }
        if (isTabletReaderViewport()) {
            return TABLET_SIMULATION_DIAGONAL_RATIO;
        }
        return DEFAULT_DIAGONAL_RATIO;
    }

    private float progressCommitThreshold(String mode) {
        if ("cover".equals(mode)) {
            return isPhoneReaderViewport() ? 0.16f : 0.18f;
        }
        if ("simulation".equals(mode)) {
            if (isSimulationOuterPageTurnActive()) {
                return 0.38f;
            }
            if (isPhoneReaderViewport()) {
                return 0.21f;
            }
            return isTabletReaderViewport() ? 0.23f : 0.24f;
        }
        if ("scroll".equals(mode)) {
            if (isPhoneReaderViewport()) {
                return 0.26f;
            }
            return isTabletReaderViewport() ? 0.27f : 0.28f;
        }
        if (isPhoneReaderViewport()) {
            return 0.20f;
        }
        return isTabletReaderViewport() ? 0.21f : 0.22f;
    }

    private float velocityCommitThreshold(String mode) {
        float baseThreshold = "scroll".equals(mode) ? 0.7f : 0.85f;
        if (isPhoneReaderViewport()) {
            return baseThreshold * 0.9f;
        }
        return baseThreshold;
    }

    private float simulationFinishCoverProgressThreshold() {
        if (isSimulationOuterPageTurnActive()) {
            return OUTER_PAGE_FINISH_COVER_PROGRESS;
        }
        return SIMULATION_FINISH_COVER_PROGRESS;
    }

    private void finishInteractivePaging(boolean commit) {
        float start = state.interactiveProgress;
        float end = commit ? 1f : 0f;
        long token = ++state.animationToken;
        cancelInteractiveAnimator();
        if (commit) {
            rememberAnimationTarget(state.interactiveTargetChapterIndex, state.interactiveTargetPageIndex);
        } else {
            rememberAnimationTarget(state.currentChapterIndex, state.currentPageIndex);
        }
        state.interactiveAnimator = ValueAnimator.ofFloat(start, end);
        String mode = runtime.settingsStore.getFlipMode();
        float remainingDistance = Math.max(0.2f, Math.abs(end - start));
        long duration = Math.max(110L, Math.round(readerFlipDurationMs() * remainingDistance));
        if (Math.abs(state.pagingVelocityX) > 0.7f) {
            duration = Math.max(90L, Math.round(duration / Math.min(Math.abs(state.pagingVelocityX), 2.4f)));
        }
        state.interactiveAnimator.setDuration(duration);
        state.interactiveAnimator.setInterpolator("simulation".equals(mode) ? PAGE_TURN_INTERPOLATOR : PAGE_SLIDE_INTERPOLATOR);
        final float startTouchX = state.interactiveTouchX;
        final float startTouchY = state.interactiveTouchY;
        final float targetTouchX = "simulation".equals(mode)
                ? resolveSimulationTargetTouchX(state.interactiveDirection, commit)
                : state.interactiveTouchX;
        final float targetTouchY = "simulation".equals(mode)
                ? resolveSimulationTargetTouchY(state.interactiveDirection)
                : state.interactiveTouchY;
        final float finishCoverProgress = "simulation".equals(mode)
                ? simulationFinishCoverProgressThreshold()
                : SIMULATION_FINISH_COVER_PROGRESS;
        final boolean[] cancelled = new boolean[]{false};
        final boolean[] finishCoverShown = new boolean[]{false};
        state.interactiveAnimator.addUpdateListener(animation -> {
            float animatedProgress = (float) animation.getAnimatedValue();
            if ("simulation".equals(mode)) {
                float touchFraction = normalizedAnimationValue(animatedProgress, start, end);
                state.interactiveTouchX = lerp(startTouchX, targetTouchX, touchFraction);
                state.interactiveTouchY = lerp(startTouchY, targetTouchY, touchFraction);
                if (commit && animatedProgress >= finishCoverProgress) {
                    finishCoverShown[0] = showSimulationTargetSnapshotAndStopCurl(
                            state.interactiveTargetChapterIndex,
                            state.interactiveTargetPageIndex,
                            state.interactiveDirection
                    ) || finishCoverShown[0];
                    if (finishCoverShown[0]) {
                        return;
                    }
                }
            }
            applyInteractivePagingProgress(animatedProgress, state.interactiveTouchY);
        });
        state.interactiveAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled[0] = true;
                state.interactiveAnimator = null;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                state.interactiveAnimator = null;
                if (cancelled[0] || token != state.animationToken) {
                    return;
                }
                if (commit) {
                    if ("simulation".equals(mode)) {
                        if (!finishCoverShown[0]) {
                            showSimulationTargetSnapshotAndStopCurl(
                                    state.interactiveTargetChapterIndex,
                                    state.interactiveTargetPageIndex,
                                    state.interactiveDirection
                            );
                        }
                    }
                    finishAnimation(
                            state.interactiveTargetChapterIndex,
                            state.interactiveTargetPageIndex,
                            state.interactiveDirection,
                            token
                    );
                } else {
                    cancelInteractivePaging();
                }
            }
        });
        state.interactiveAnimator.start();
    }

    private boolean shouldCompleteSimulationDragAtEdge(float touchX) {
        if (!isSimulationFlipMode()
                || !isSimulationOuterPageTurnActive()
                || !state.interactivePaging
                || state.interactiveDirection == 0
                || state.interactiveTargetChapterIndex < 0
                || state.interactiveTargetPageIndex < 0
                || state.interactiveCancel
                || state.interactiveProgress < 0.92f) {
            return false;
        }
        float width = Math.max(views.pageStage == null ? 0f : views.pageStage.getWidth(), ui.dp(240));
        float edgeInset = Math.max(ui.dp(10), width * 0.018f);
        return state.interactiveDirection > 0
                ? touchX <= edgeInset
                : touchX >= width - edgeInset;
    }

    private void rememberAnimationTarget(int targetChapterIndex, int targetPageIndex) {
        state.animationTargetChapterIndex = targetChapterIndex;
        state.animationTargetPageIndex = targetPageIndex;
    }

    private void clearAnimationTarget() {
        state.animationTargetChapterIndex = -1;
        state.animationTargetPageIndex = -1;
    }

    private float localTouchX(MotionEvent event) {
        int[] stageLocation = new int[2];
        views.pageStage.getLocationOnScreen(stageLocation);
        return event.getRawX() - stageLocation[0];
    }

    private float localTouchY(MotionEvent event) {
        int[] stageLocation = new int[2];
        views.pageStage.getLocationOnScreen(stageLocation);
        return event.getRawY() - stageLocation[1];
    }

    private void finishAnimation(int targetChapterIndex, int targetPageIndex, int direction, long token) {
        if (token != state.animationToken) {
            return;
        }
        int previousChapterIndex = state.currentChapterIndex;
        int previousPageIndex = state.currentPageIndex;
        state.currentChapterIndex = targetChapterIndex;
        state.currentPageIndex = targetPageIndex;
        promoteIncomingSnapshotToCurrent(
                targetChapterIndex,
                targetPageIndex,
                previousChapterIndex,
                previousPageIndex,
                direction
        );
        navigation.bindCurrentSpread(targetChapterIndex, targetPageIndex);
        if ("simulation".equals(runtime.settingsStore.getFlipMode())) {
            resetAnimatedPage(views.pageCurrent);
            resetAnimatedPage(views.pageIncoming);
            resetShadowView();
            if (showPromotedCurrentSnapshotCover()) {
                prepareFinishedLivePageBehindSimulationCover();
                state.pagingGestureCandidate = false;
                state.interactivePaging = false;
                state.interactiveDirection = 0;
                state.interactiveCancel = false;
                state.interactiveProgress = 0f;
                state.interactiveTargetChapterIndex = -1;
                state.interactiveTargetPageIndex = -1;
                clearAnimationTarget();
                completeFinishedAnimationSwapAfterLiveDraw(targetChapterIndex, targetPageIndex, token);
                return;
            }
        }
        boolean keepIncomingCover = views.pageIncoming != null && views.pageIncoming.getVisibility() == View.VISIBLE;
        restoreLivePageLayers(keepIncomingCover);
        resetAnimatedPage(views.pageCurrent);
        resetAnimatedPage(views.pageIncoming);
        resetShadowView();
        state.pagingGestureCandidate = false;
        state.interactivePaging = false;
        state.interactiveDirection = 0;
        state.interactiveCancel = false;
        state.interactiveProgress = 0f;
        state.interactiveTargetChapterIndex = -1;
        state.interactiveTargetPageIndex = -1;
        clearAnimationTarget();
        if (keepIncomingCover) {
            views.pageIncoming.bringToFront();
            views.pageCurrent.setVisibility(View.INVISIBLE);
            views.pageStage.post(() -> completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token));
            return;
        }
        completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token);
    }

    private void completeFinishedAnimationSwap(int targetChapterIndex, int targetPageIndex, long token) {
        if (token != state.animationToken) {
            return;
        }
        boolean finishingSimulationCover = state.simulationFinishCoverVisible
                && views.pageSnapshotCurrent != null
                && views.pageSnapshotCurrent.getVisibility() == View.VISIBLE;
        if (finishingSimulationCover) {
            state.pagingSnapshotsVisible = true;
            state.simulationStableCoverVisible = true;
            resetAnimatedPage(views.pageCurrent);
            views.pageCurrent.setVisibility(View.VISIBLE);
            if (views.pageSnapshotIncoming != null) {
                resetAnimatedPage(views.pageSnapshotIncoming);
                views.pageSnapshotIncoming.setVisibility(View.GONE);
            }
            views.pageIncoming.setVisibility(View.GONE);
            keepStableSimulationCoverOnTop();
            if (views.simulationPageTurnView != null) {
                views.simulationPageTurnView.clear();
            }
            keepStableSimulationCoverOnTop();
            View target = views.pageStage != null ? views.pageStage : views.pageCurrent;
            if (target != null) {
                target.postOnAnimation(() -> completeFinishedAnimationSwapNow(token, true));
                return;
            }
            completeFinishedAnimationSwapNow(token, true);
            return;
        }
        if (views.simulationPageTurnView != null) {
            views.simulationPageTurnView.clear();
        }
        views.pageCurrent.setVisibility(View.VISIBLE);
        views.pageCurrent.bringToFront();
        completeFinishedAnimationSwapNow(token, false);
    }

    private void completeFinishedAnimationSwapNow(long token, boolean keepSimulationCover) {
        if (token != state.animationToken) {
            return;
        }
        views.pageCurrent.setVisibility(View.VISIBLE);
        if (!keepSimulationCover) {
            views.pageCurrent.bringToFront();
        }
        state.pagingSnapshotsVisible = keepSimulationCover;
        state.simulationStableCoverVisible = keepSimulationCover;
        if (views.pageSnapshotCurrent != null) {
            resetAnimatedPage(views.pageSnapshotCurrent);
            views.pageSnapshotCurrent.setVisibility(keepSimulationCover ? View.VISIBLE : View.GONE);
            if (keepSimulationCover) {
                views.pageSnapshotCurrent.bringToFront();
            }
        }
        if (views.pageSnapshotIncoming != null) {
            resetAnimatedPage(views.pageSnapshotIncoming);
            views.pageSnapshotIncoming.setVisibility(View.GONE);
        }
        views.pageIncoming.setVisibility(View.GONE);
        state.simulationFinishCoverVisible = false;
        if (keepSimulationCover) {
            keepStableSimulationCoverOnTop();
        }
        bringStableBookSpineOverlayToFront();
        resetInteractiveTouchState();
        state.pendingTapPagingDelta = 0;
        activity.markReadingActivity();
        chrome.updateUiAfterPageChange();
        state.isAnimating = false;
        if (keepSimulationCover) {
            hideLiveHudDuringPaging();
        } else {
            showLiveHudAfterPaging();
        }
        content.scheduleProgressSave();
        chrome.scheduleAutoHide();
        schedulePagingSnapshotWarmup();
    }

    private boolean showPromotedCurrentSnapshotCover() {
        if (views.pageSnapshotCurrent == null || !ensureCurrentSnapshotCoverBitmap()) {
            return false;
        }
        state.pagingSnapshotsVisible = true;
        state.simulationFinishCoverVisible = true;
        state.simulationStableCoverVisible = false;
        views.pageSnapshotCurrent.setImageBitmap(state.currentPageSnapshotBitmap);
        resetAnimatedPage(views.pageSnapshotCurrent);
        views.pageSnapshotCurrent.setVisibility(View.VISIBLE);
        views.pageSnapshotCurrent.bringToFront();
        bringStableBookSpineOverlayToFront();
        if (views.pageSnapshotIncoming != null) {
            resetAnimatedPage(views.pageSnapshotIncoming);
            views.pageSnapshotIncoming.setVisibility(View.GONE);
        }
        if (views.simulationPageTurnView != null) {
            views.simulationPageTurnView.clear();
        }
        views.pageIncoming.setVisibility(View.GONE);
        return true;
    }

    private void prepareFinishedLivePageBehindSimulationCover() {
        resetAnimatedPage(views.pageCurrent);
        views.pageCurrent.setVisibility(View.VISIBLE);
        layoutPageLayerForSnapshot(views.pageCurrent);
        views.pageCurrent.invalidate();
        if (views.pageSnapshotCurrent != null) {
            views.pageSnapshotCurrent.bringToFront();
        }
        bringStableBookSpineOverlayToFront();
    }

    private boolean showIncomingSnapshotCoverBeforeCommit(int targetChapterIndex, int targetPageIndex, int direction) {
        if (views.pageSnapshotCurrent == null) {
            return false;
        }
        Bitmap targetSnapshot = preparedTargetSnapshot(targetChapterIndex, targetPageIndex);
        if (targetSnapshot == null || targetSnapshot.isRecycled()) {
            targetSnapshot = captureDirectionalPreparedSnapshot(direction, targetChapterIndex, targetPageIndex);
        }
        if (targetSnapshot == null || targetSnapshot.isRecycled()) {
            return false;
        }
        setActiveIncomingSnapshot(targetSnapshot, targetChapterIndex, targetPageIndex);
        state.pagingSnapshotsVisible = true;
        state.simulationStableCoverVisible = false;
        views.pageSnapshotCurrent.setImageBitmap(targetSnapshot);
        resetAnimatedPage(views.pageSnapshotCurrent);
        views.pageSnapshotCurrent.setVisibility(View.VISIBLE);
        views.pageSnapshotCurrent.bringToFront();
        if (views.pageSnapshotIncoming != null) {
            resetAnimatedPage(views.pageSnapshotIncoming);
            views.pageSnapshotIncoming.setVisibility(View.GONE);
        }
        bringStableBookSpineOverlayToFront();
        return true;
    }

    private boolean showSimulationTargetSnapshotAndStopCurl(int targetChapterIndex, int targetPageIndex, int direction) {
        boolean shown = showIncomingSnapshotCoverBeforeCommit(targetChapterIndex, targetPageIndex, direction);
        if (shown) {
            state.simulationFinishCoverVisible = true;
            state.simulationStableCoverVisible = false;
            clearSimulationPagingLayer();
            keepSimulationFinishCoverOnTop();
        }
        return shown;
    }

    private void keepSimulationFinishCoverOnTop() {
        state.pagingSnapshotsVisible = true;
        resetShadowView();
        hideInteractiveFoldEffects();
        if (views.simulationPageTurnView != null) {
            views.simulationPageTurnView.clear();
        }
        if (views.pageSnapshotIncoming != null) {
            resetAnimatedPage(views.pageSnapshotIncoming);
            views.pageSnapshotIncoming.setVisibility(View.GONE);
        }
        if (views.pageIncoming != null) {
            views.pageIncoming.setVisibility(View.GONE);
        }
        if (views.pageSnapshotCurrent != null) {
            resetAnimatedPage(views.pageSnapshotCurrent);
            views.pageSnapshotCurrent.setVisibility(View.VISIBLE);
            views.pageSnapshotCurrent.bringToFront();
        }
        bringStableBookSpineOverlayToFront();
    }

    private void keepStableSimulationCoverOnTop() {
        if (!state.simulationStableCoverVisible || views.pageSnapshotCurrent == null) {
            return;
        }
        state.pagingSnapshotsVisible = true;
        resetAnimatedPage(views.pageSnapshotCurrent);
        views.pageSnapshotCurrent.setVisibility(View.VISIBLE);
        views.pageSnapshotCurrent.bringToFront();
        bringStableBookSpineOverlayToFront();
    }

    private void bringStableBookSpineOverlayToFront() {
        if (views.pageBookSpineOverlay == null) {
            return;
        }
        if (!isSimulationOuterPageTurnActive()) {
            views.pageBookSpineOverlay.setVisibility(View.GONE);
            return;
        }
        views.pageBookSpineOverlay.setVisibility(View.VISIBLE);
        views.pageBookSpineOverlay.bringToFront();
    }

    private boolean ensureCurrentSnapshotCoverBitmap() {
        if (hasPreparedCurrentSnapshot(state.currentChapterIndex, state.currentPageIndex)) {
            return true;
        }
        if (views.pageCurrent == null) {
            return false;
        }
        resetAnimatedPage(views.pageCurrent);
        views.pageCurrent.setVisibility(View.VISIBLE);
        if (!layoutPageLayerForSnapshot(views.pageCurrent)) {
            return false;
        }
        Bitmap bitmap = screenshotPageLayer(
                views.pageCurrent,
                state.currentPageSnapshotBitmap,
                state.currentChapterIndex,
                state.currentPageIndex
        );
        if (bitmap == null || bitmap.isRecycled()) {
            state.preparedCurrentSnapshotChapterIndex = -1;
            state.preparedCurrentSnapshotPageIndex = -1;
            return false;
        }
        state.currentPageSnapshotBitmap = bitmap;
        state.preparedCurrentSnapshotChapterIndex = state.currentChapterIndex;
        state.preparedCurrentSnapshotPageIndex = state.currentPageIndex;
        return true;
    }

    private void completeFinishedAnimationSwapAfterLiveDraw(int targetChapterIndex, int targetPageIndex, long token) {
        waitForFinishedAnimationPreDraw(
                targetChapterIndex,
                targetPageIndex,
                token,
                FINISH_SWAP_PREDRAW_PASSES
        );
    }

    private void waitForFinishedAnimationPreDraw(
            int targetChapterIndex,
            int targetPageIndex,
            long token,
            int remainingPasses
    ) {
        if (token != state.animationToken) {
            return;
        }
        if (remainingPasses <= 0) {
            scheduleFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token);
            return;
        }
        View target = views.pageStage != null ? views.pageStage : views.pageCurrent;
        if (target == null || !target.isAttachedToWindow()) {
            completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token);
            return;
        }
        final boolean[] completed = new boolean[]{false};
        final ViewTreeObserver.OnPreDrawListener[] listenerRef = new ViewTreeObserver.OnPreDrawListener[1];
        Runnable complete = () -> {
            if (completed[0]) {
                return;
            }
            completed[0] = true;
            removePreDrawListener(target, listenerRef[0]);
            waitForFinishedAnimationPreDraw(
                    targetChapterIndex,
                    targetPageIndex,
                    token,
                    remainingPasses - 1
            );
        };
        listenerRef[0] = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                target.post(complete);
                return true;
            }
        };
        ViewTreeObserver observer = target.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            target.post(complete);
            return;
        }
        observer.addOnPreDrawListener(listenerRef[0]);
        if (views.pageCurrent != null) {
            views.pageCurrent.requestLayout();
            views.pageCurrent.invalidate();
        }
        target.invalidate();
        target.postDelayed(complete, FINISH_SWAP_PREDRAW_FALLBACK_MS);
    }

    private void scheduleFinishedAnimationSwap(int targetChapterIndex, int targetPageIndex, long token) {
        View target = views.pageStage != null ? views.pageStage : views.pageCurrent;
        if (target == null) {
            completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token);
            return;
        }
        target.postDelayed(
                () -> completeFinishedAnimationSwap(targetChapterIndex, targetPageIndex, token),
                FINISH_SWAP_COVER_HOLD_MS
        );
    }

    private void removePreDrawListener(View target, ViewTreeObserver.OnPreDrawListener listener) {
        if (target == null || listener == null) {
            return;
        }
        ViewTreeObserver observer = target.getViewTreeObserver();
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(listener);
        }
    }

    private void preparePagingSnapshots(int targetChapterIndex, int targetPageIndex, int direction) {
        boolean simulationMode = "simulation".equals(runtime.settingsStore.getFlipMode());
        boolean bridgeStableCover = simulationMode && state.simulationStableCoverVisible;
        if (!bridgeStableCover) {
            clearStableSimulationCover(false);
        }
        state.simulationFinishCoverVisible = false;
        if (views.pageSnapshotCurrent == null || views.pageSnapshotIncoming == null) {
            state.pagingSnapshotsVisible = false;
            return;
        }
        clearSimulationPagingLayer();
        ensurePreparedCurrentSnapshot();
        Bitmap targetSnapshot = preparedTargetSnapshot(targetChapterIndex, targetPageIndex);
        if (targetSnapshot == null) {
            targetSnapshot = captureDirectionalPreparedSnapshot(direction, targetChapterIndex, targetPageIndex);
        }
        setActiveIncomingSnapshot(targetSnapshot, targetChapterIndex, targetPageIndex);
        if (state.currentPageSnapshotBitmap == null || state.incomingPageSnapshotBitmap == null) {
            restoreLivePageLayers(true);
            return;
        }
        views.pageSnapshotCurrent.setImageBitmap(state.currentPageSnapshotBitmap);
        views.pageSnapshotIncoming.setImageBitmap(state.incomingPageSnapshotBitmap);
        views.pageSnapshotCurrent.setVisibility(View.VISIBLE);
        views.pageSnapshotIncoming.setVisibility(simulationMode ? View.GONE : View.VISIBLE);
        views.pageCurrent.setVisibility(View.INVISIBLE);
        views.pageIncoming.setVisibility(View.INVISIBLE);
        hideLiveHudDuringPaging();
        state.pagingSnapshotsVisible = true;
        state.simulationStableCoverVisible = false;
    }

    private Bitmap screenshotPageLayer(View source, Bitmap reuse, int chapterIndex, int pageIndex) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        Bitmap targetBitmap = reuse;
        if (targetBitmap == null || targetBitmap.getWidth() != width || targetBitmap.getHeight() != height) {
            if (targetBitmap != null && !targetBitmap.isRecycled()) {
                targetBitmap.recycle();
            }
            targetBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } else {
            targetBitmap.eraseColor(Color.TRANSPARENT);
        }
        pagingSnapshotCanvas.setBitmap(targetBitmap);
        drawSnapshotBaseLayer(pagingSnapshotCanvas, source);
        pagingSnapshotCanvas.save();
        pagingSnapshotCanvas.translate(-source.getScrollX(), -source.getScrollY());
        source.draw(pagingSnapshotCanvas);
        pagingSnapshotCanvas.restore();
        drawHudSnapshotLayer(pagingSnapshotCanvas, source, chapterIndex, pageIndex);
        pagingSnapshotCanvas.setBitmap(null);
        targetBitmap.prepareToDraw();
        return targetBitmap;
    }

    private void warmPreparedPagingSnapshots() {
        if (shouldSkipPagingSnapshotWarmup()) {
            return;
        }
        if (!ensurePageAreaReady(this::schedulePagingSnapshotWarmup)) {
            return;
        }
        ensurePreparedCurrentSnapshot();
        warmDirectionalSnapshot(1);
        warmDirectionalSnapshot(-1);
    }

    private void ensurePreparedCurrentSnapshot() {
        if (hasPreparedCurrentSnapshot(state.currentChapterIndex, state.currentPageIndex)) {
            return;
        }
        navigation.bindCurrentSpread(state.currentChapterIndex, state.currentPageIndex);
        layoutPageLayerForSnapshot(views.pageCurrent);
        state.currentPageSnapshotBitmap = screenshotPageLayer(
                views.pageCurrent,
                state.currentPageSnapshotBitmap,
                state.currentChapterIndex,
                state.currentPageIndex
        );
        if (state.currentPageSnapshotBitmap != null) {
            state.preparedCurrentSnapshotChapterIndex = state.currentChapterIndex;
            state.preparedCurrentSnapshotPageIndex = state.currentPageIndex;
        } else {
            state.preparedCurrentSnapshotChapterIndex = -1;
            state.preparedCurrentSnapshotPageIndex = -1;
        }
    }

    private void warmDirectionalSnapshot(int direction) {
        PageTarget target = resolveInteractiveTarget(direction);
        if (target == null || hasPreparedDirectionalSnapshot(direction, target.chapterIndex, target.pageIndex)) {
            return;
        }
        captureDirectionalPreparedSnapshot(direction, target.chapterIndex, target.pageIndex);
    }

    private Bitmap preparedTargetSnapshot(int chapterIndex, int pageIndex) {
        if (hasPreparedNextSnapshot(chapterIndex, pageIndex)) {
            return state.nextPageSnapshotBitmap;
        }
        if (hasPreparedPreviousSnapshot(chapterIndex, pageIndex)) {
            return state.previousPageSnapshotBitmap;
        }
        if (hasPreparedIncomingSnapshot(chapterIndex, pageIndex)) {
            return state.incomingPageSnapshotBitmap;
        }
        return null;
    }

    private Bitmap captureDirectionalPreparedSnapshot(int direction, int chapterIndex, int pageIndex) {
        if (direction >= 0) {
            Bitmap preparedBitmap = capturePreparedIncomingSnapshot(
                    chapterIndex,
                    pageIndex,
                    state.nextPageSnapshotBitmap
            );
            if (preparedBitmap != null) {
                state.nextPageSnapshotBitmap = preparedBitmap;
                state.preparedNextSnapshotChapterIndex = chapterIndex;
                state.preparedNextSnapshotPageIndex = pageIndex;
            } else {
                state.preparedNextSnapshotChapterIndex = -1;
                state.preparedNextSnapshotPageIndex = -1;
            }
            return preparedBitmap;
        }
        Bitmap preparedBitmap = capturePreparedIncomingSnapshot(
                chapterIndex,
                pageIndex,
                state.previousPageSnapshotBitmap
        );
        if (preparedBitmap != null) {
            state.previousPageSnapshotBitmap = preparedBitmap;
            state.preparedPreviousSnapshotChapterIndex = chapterIndex;
            state.preparedPreviousSnapshotPageIndex = pageIndex;
        } else {
            state.preparedPreviousSnapshotChapterIndex = -1;
            state.preparedPreviousSnapshotPageIndex = -1;
        }
        return preparedBitmap;
    }

    private Bitmap capturePreparedIncomingSnapshot(int chapterIndex, int pageIndex, Bitmap reuse) {
        if (views.pageIncoming == null) {
            return null;
        }
        int previousVisibility = views.pageIncoming.getVisibility();
        float previousAlpha = views.pageIncoming.getAlpha();
        boolean keepStableCover = state.simulationStableCoverVisible
                && views.pageSnapshotCurrent != null
                && views.pageSnapshotCurrent.getVisibility() == View.VISIBLE;
        navigation.bindIncomingSpread(chapterIndex, pageIndex);
        resetAnimatedPage(views.pageIncoming);
        if (views.pageCurrent != null && !keepStableCover) {
            views.pageCurrent.bringToFront();
        } else if (keepStableCover) {
            keepStableSimulationCoverOnTop();
        }
        views.pageIncoming.setVisibility(View.VISIBLE);
        if (!layoutPageLayerForSnapshot(views.pageIncoming)) {
            views.pageIncoming.setAlpha(previousAlpha);
            views.pageIncoming.setVisibility(previousVisibility);
            keepStableSimulationCoverOnTop();
            return null;
        }
        Bitmap bitmap = screenshotPageLayer(views.pageIncoming, reuse, chapterIndex, pageIndex);
        views.pageIncoming.setAlpha(previousAlpha);
        views.pageIncoming.setVisibility(previousVisibility);
        resetAnimatedPage(views.pageIncoming);
        if (views.pageCurrent != null && !keepStableCover) {
            views.pageCurrent.bringToFront();
        }
        keepStableSimulationCoverOnTop();
        return bitmap;
    }

    private boolean layoutPageLayerForSnapshot(View source) {
        int width = snapshotDimensionFor(source, true);
        int height = snapshotDimensionFor(source, false);
        if (width <= 0 || height <= 0) {
            return false;
        }
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        source.measure(widthSpec, heightSpec);
        source.layout(0, 0, width, height);
        return true;
    }

    private int snapshotDimensionFor(View source, boolean width) {
        if (source == null) {
            return 0;
        }
        int currentValue = width ? source.getWidth() : source.getHeight();
        if (currentValue > 0) {
            return currentValue;
        }
        if (views.pageCurrent != null) {
            int currentPageValue = width ? views.pageCurrent.getWidth() : views.pageCurrent.getHeight();
            if (currentPageValue > 0) {
                return currentPageValue;
            }
        }
        if (views.pageStage == null) {
            return 0;
        }
        return width
                ? Math.max(0, views.pageStage.getWidth() - views.pageStage.getPaddingLeft() - views.pageStage.getPaddingRight())
                : Math.max(0, views.pageStage.getHeight() - views.pageStage.getPaddingTop() - views.pageStage.getPaddingBottom());
    }

    private boolean hasPreparedCurrentSnapshot(int chapterIndex, int pageIndex) {
        return hasPreparedSnapshot(
                state.currentPageSnapshotBitmap,
                views.pageCurrent,
                state.preparedCurrentSnapshotChapterIndex,
                state.preparedCurrentSnapshotPageIndex,
                chapterIndex,
                pageIndex
        );
    }

    private boolean hasPreparedIncomingSnapshot(int chapterIndex, int pageIndex) {
        return hasPreparedSnapshot(
                state.incomingPageSnapshotBitmap,
                views.pageIncoming,
                state.preparedIncomingSnapshotChapterIndex,
                state.preparedIncomingSnapshotPageIndex,
                chapterIndex,
                pageIndex
        );
    }

    private boolean hasPreparedNextSnapshot(int chapterIndex, int pageIndex) {
        return hasPreparedSnapshot(
                state.nextPageSnapshotBitmap,
                views.pageIncoming,
                state.preparedNextSnapshotChapterIndex,
                state.preparedNextSnapshotPageIndex,
                chapterIndex,
                pageIndex
        );
    }

    private boolean hasPreparedPreviousSnapshot(int chapterIndex, int pageIndex) {
        return hasPreparedSnapshot(
                state.previousPageSnapshotBitmap,
                views.pageIncoming,
                state.preparedPreviousSnapshotChapterIndex,
                state.preparedPreviousSnapshotPageIndex,
                chapterIndex,
                pageIndex
        );
    }

    private boolean hasPreparedDirectionalSnapshot(int direction, int chapterIndex, int pageIndex) {
        return direction >= 0
                ? hasPreparedNextSnapshot(chapterIndex, pageIndex)
                : hasPreparedPreviousSnapshot(chapterIndex, pageIndex);
    }

    private void setActiveIncomingSnapshot(Bitmap bitmap, int chapterIndex, int pageIndex) {
        state.incomingPageSnapshotBitmap = bitmap;
        if (bitmap == null) {
            state.preparedIncomingSnapshotChapterIndex = -1;
            state.preparedIncomingSnapshotPageIndex = -1;
            return;
        }
        state.preparedIncomingSnapshotChapterIndex = chapterIndex;
        state.preparedIncomingSnapshotPageIndex = pageIndex;
    }

    private boolean hasPreparedSnapshot(Bitmap bitmap, View source, int preparedChapterIndex, int preparedPageIndex, int chapterIndex, int pageIndex) {
        if (bitmap == null
                || bitmap.isRecycled()
                || source == null
                || preparedChapterIndex != chapterIndex
                || preparedPageIndex != pageIndex) {
            return false;
        }
        int expectedWidth = snapshotDimensionFor(source, true);
        int expectedHeight = snapshotDimensionFor(source, false);
        return expectedWidth > 0
                && expectedHeight > 0
                && bitmap.getWidth() == expectedWidth
                && bitmap.getHeight() == expectedHeight;
    }

    private void promoteIncomingSnapshotToCurrent(
            int chapterIndex,
            int pageIndex,
            int previousChapterIndex,
            int previousPageIndex,
            int direction
    ) {
        if (!hasPreparedIncomingSnapshot(chapterIndex, pageIndex)) {
            state.preparedCurrentSnapshotChapterIndex = -1;
            state.preparedCurrentSnapshotPageIndex = -1;
            return;
        }
        boolean previousCurrentWasPrepared = hasPreparedCurrentSnapshot(previousChapterIndex, previousPageIndex);
        Bitmap previousCurrentBitmap = state.currentPageSnapshotBitmap;
        Bitmap promotedBitmap = state.incomingPageSnapshotBitmap;
        Bitmap previousNextBitmap = state.nextPageSnapshotBitmap;
        Bitmap previousPreviousBitmap = state.previousPageSnapshotBitmap;

        state.currentPageSnapshotBitmap = state.incomingPageSnapshotBitmap;
        state.preparedCurrentSnapshotChapterIndex = chapterIndex;
        state.preparedCurrentSnapshotPageIndex = pageIndex;
        setActiveIncomingSnapshot(null, -1, -1);
        state.preparedIncomingSnapshotChapterIndex = -1;
        state.preparedIncomingSnapshotPageIndex = -1;

        state.nextPageSnapshotBitmap = previousNextBitmap == promotedBitmap ? null : previousNextBitmap;
        state.previousPageSnapshotBitmap = previousPreviousBitmap == promotedBitmap ? null : previousPreviousBitmap;
        state.preparedNextSnapshotChapterIndex = -1;
        state.preparedNextSnapshotPageIndex = -1;
        state.preparedPreviousSnapshotChapterIndex = -1;
        state.preparedPreviousSnapshotPageIndex = -1;

        if (!previousCurrentWasPrepared
                || previousCurrentBitmap == null
                || previousCurrentBitmap.isRecycled()
                || previousCurrentBitmap == promotedBitmap) {
            return;
        }
        if (direction >= 0) {
            Bitmap spare = state.previousPageSnapshotBitmap;
            state.previousPageSnapshotBitmap = previousCurrentBitmap;
            state.preparedPreviousSnapshotChapterIndex = previousChapterIndex;
            state.preparedPreviousSnapshotPageIndex = previousPageIndex;
            if (state.nextPageSnapshotBitmap == null
                    && spare != null
                    && spare != previousCurrentBitmap
                    && spare != promotedBitmap) {
                state.nextPageSnapshotBitmap = spare;
            }
            return;
        }
        Bitmap spare = state.nextPageSnapshotBitmap;
        state.nextPageSnapshotBitmap = previousCurrentBitmap;
        state.preparedNextSnapshotChapterIndex = previousChapterIndex;
        state.preparedNextSnapshotPageIndex = previousPageIndex;
        if (state.previousPageSnapshotBitmap == null
                && spare != null
                && spare != previousCurrentBitmap
                && spare != promotedBitmap) {
            state.previousPageSnapshotBitmap = spare;
        }
    }

    private void drawSnapshotBaseLayer(Canvas canvas, View source) {
        canvas.drawColor(state.currentReaderPageColor);
        if (views.readerBackgroundImage == null
                || views.readerBackgroundImage.getVisibility() != View.VISIBLE
                || views.readerBackgroundImage.getDrawable() == null) {
            return;
        }
        int[] sourceLocation = new int[2];
        int[] backgroundLocation = new int[2];
        source.getLocationOnScreen(sourceLocation);
        views.readerBackgroundImage.getLocationOnScreen(backgroundLocation);
        canvas.save();
        canvas.translate(
                backgroundLocation[0] - sourceLocation[0],
                backgroundLocation[1] - sourceLocation[1]
        );
        views.readerBackgroundImage.draw(canvas);
        canvas.restore();
    }

    private void drawHudSnapshotLayer(Canvas canvas, View source, int chapterIndex, int pageIndex) {
        if (chrome == null || canvas == null || source == null) {
            return;
        }
        ReaderChromeController.HudSnapshotState hudSnapshot = chrome.captureHudSnapshotState();
        try {
            chrome.updateReaderHudForPageSnapshot(chapterIndex, pageIndex);
            drawHudContainerSnapshot(canvas, source, views.hudTopContainer, false);
            drawHudContainerSnapshot(canvas, source, views.hudBottomContainer, true);
        } finally {
            chrome.restoreHudSnapshotState(hudSnapshot);
        }
    }

    private void drawHudContainerSnapshot(Canvas canvas, View source, View hudContainer, boolean alignBottom) {
        if (hudContainer == null || source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return;
        }
        int previousVisibility = hudContainer.getVisibility();
        float previousAlpha = hudContainer.getAlpha();
        float previousTranslationX = hudContainer.getTranslationX();
        float previousTranslationY = hudContainer.getTranslationY();
        int previousLeft = hudContainer.getLeft();
        int previousTop = hudContainer.getTop();
        int previousRight = hudContainer.getRight();
        int previousBottom = hudContainer.getBottom();

        int width = source.getWidth();
        int height = source.getHeight();
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        hudContainer.measure(widthSpec, heightSpec);
        int measuredHeight = Math.max(0, hudContainer.getMeasuredHeight());
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
                    previousBottom
            );
            return;
        }
        int top = alignBottom ? Math.max(0, height - measuredHeight) : 0;
        hudContainer.setVisibility(View.VISIBLE);
        hudContainer.setAlpha(1f);
        hudContainer.setTranslationX(0f);
        hudContainer.setTranslationY(0f);
        hudContainer.layout(0, top, width, top + measuredHeight);
        canvas.save();
        canvas.translate(0f, top);
        hudContainer.draw(canvas);
        canvas.restore();
        restoreHudContainerAfterSnapshot(
                hudContainer,
                previousVisibility,
                previousAlpha,
                previousTranslationX,
                previousTranslationY,
                previousLeft,
                previousTop,
                previousRight,
                previousBottom
        );
    }

    private void restoreHudContainerAfterSnapshot(
            View hudContainer,
            int visibility,
            float alpha,
            float translationX,
            float translationY,
            int left,
            int top,
            int right,
            int bottom
    ) {
        hudContainer.layout(left, top, right, bottom);
        hudContainer.setVisibility(visibility);
        hudContainer.setAlpha(alpha);
        hudContainer.setTranslationX(translationX);
        hudContainer.setTranslationY(translationY);
    }

    private void hideLiveHudDuringPaging() {
        setLiveHudAlphaForPaging(0f);
    }

    private void showLiveHudAfterPaging() {
        setLiveHudAlphaForPaging(1f);
    }

    private void setLiveHudAlphaForPaging(float alpha) {
        setHudContainerAlpha(views.hudTopContainer, alpha);
        setHudContainerAlpha(views.hudBottomContainer, alpha);
    }

    private void setHudContainerAlpha(View hudContainer, float alpha) {
        if (hudContainer == null) {
            return;
        }
        hudContainer.animate().cancel();
        hudContainer.setAlpha(alpha);
    }

    private View activeCurrentPageLayer() {
        if (state.pagingSnapshotsVisible && views.pageSnapshotCurrent != null) {
            return views.pageSnapshotCurrent;
        }
        return views.pageCurrent;
    }

    private View activeIncomingPageLayer() {
        if (state.pagingSnapshotsVisible && views.pageSnapshotIncoming != null) {
            return views.pageSnapshotIncoming;
        }
        return views.pageIncoming;
    }

    private void arrangePagingLayers(String mode) {
        if ("simulation".equals(mode)) {
            if (views.simulationPageTurnView != null) {
                views.simulationPageTurnView.bringToFront();
            }
            return;
        }
        View currentLayer = activeCurrentPageLayer();
        View incomingLayer = activeIncomingPageLayer();
        if ("scroll".equals(mode)) {
            currentLayer.bringToFront();
            incomingLayer.bringToFront();
        } else {
            incomingLayer.bringToFront();
            currentLayer.bringToFront();
        }
        if (views.pageFoldHighlight != null) {
            views.pageFoldHighlight.bringToFront();
        }
    }

    private void applyPagingVisuals(String mode, int direction, float progress, float touchY) {
        if ("simulation".equals(mode) && state.simulationFinishCoverVisible) {
            keepSimulationFinishCoverOnTop();
            return;
        }
        float width = Math.max(views.pageStage.getWidth(), ui.dp(240));
        float height = Math.max(views.pageStage.getHeight(), ui.dp(320));
        float safeProgress = Math.max(0f, Math.min(1f, progress));
        float safeTouchY = Math.max(0f, Math.min(height, touchY));
        float touchRatio = safeTouchY / height;
        float diagonalBias = touchRatio - 0.5f;
        int widthPx = Math.max(1, Math.round(width));
        int heightPx = Math.max(1, Math.round(height));
        View currentLayer = activeCurrentPageLayer();
        View incomingLayer = activeIncomingPageLayer();
        resetAnimatedPage(currentLayer);
        resetAnimatedPage(incomingLayer);
        if (!"simulation".equals(mode)) {
            incomingLayer.setVisibility(View.VISIBLE);
        }
        if (!"simulation".equals(mode)) {
            clearSimulationPagingLayer();
            hideInteractiveFoldEffects();
        }

        if ("simulation".equals(mode)) {
            resetShadowView();
            if (views.simulationPageTurnView != null
                    && state.currentPageSnapshotBitmap != null
                    && state.incomingPageSnapshotBitmap != null) {
                views.simulationPageTurnView.setPagingState(
                        direction,
                        state.currentPageSnapshotBitmap,
                        state.incomingPageSnapshotBitmap,
                        state.interactiveStartX,
                        state.interactiveStartY,
                        state.interactiveTouchX,
                        state.interactiveTouchY,
                        simulationTurnMode(),
                        state.currentReaderPageColor
                );
                views.simulationPageTurnView.bringToFront();
            }
            return;
        }

        if ("cover".equals(mode)) {
            float revealWidth = width * safeProgress;
            incomingLayer.setAlpha(1f);
            if (direction > 0) {
                incomingLayer.bringToFront();
                currentLayer.bringToFront();
                currentLayer.setTranslationX(-width * safeProgress);
                applyRevealedIncomingClip(incomingLayer, direction, revealWidth, widthPx, heightPx);
            } else {
                currentLayer.bringToFront();
                incomingLayer.bringToFront();
                currentLayer.setTranslationX(0f);
                incomingLayer.setTranslationX(-width * (1f - safeProgress));
                incomingLayer.setClipBounds(null);
            }
            hideInteractiveShadow();
            return;
        }

        if ("scroll".equals(mode)) {
            float offsetY = (direction > 0 ? 1f : -1f) * height * safeProgress;
            currentLayer.setTranslationY(-offsetY);
            incomingLayer.setTranslationY((direction > 0 ? 1f : -1f) * height * (1f - safeProgress));
            incomingLayer.setAlpha(0.94f + 0.06f * safeProgress);
            hideInteractiveShadow();
            return;
        }

        float revealWidth = width * safeProgress;
        currentLayer.setTranslationX((direction > 0 ? -1f : 1f) * revealWidth);
        incomingLayer.setTranslationX(direction > 0 ? width - revealWidth : -width + revealWidth);
        incomingLayer.setAlpha(0.95f + 0.05f * safeProgress);
        if (direction > 0) {
            applyPageClip(incomingLayer, 0, Math.round(revealWidth), heightPx);
            hideInteractiveShadow();
        } else {
            applyPageClip(incomingLayer, widthPx - Math.round(revealWidth), widthPx, heightPx);
            hideInteractiveShadow();
        }
    }

    private void applyPageClip(View view, int left, int right, int height) {
        int width = Math.max(view.getWidth(), 1);
        int safeLeft = ui.clamp(left, 0, width);
        int safeRight = ui.clamp(right, 0, width);
        int safeHeight = Math.max(height, 1);
        if (safeRight <= safeLeft) {
            view.setClipBounds(new Rect(0, 0, 0, safeHeight));
            return;
        }
        view.setClipBounds(new Rect(safeLeft, 0, safeRight, safeHeight));
    }

    private void applyRevealedIncomingClip(View view, int direction, float revealWidth, int width, int height) {
        int revealPx = Math.max(0, Math.round(revealWidth));
        int seamBleedPx = revealPx > 0 ? ui.dp(2) : 0;
        if (direction > 0) {
            applyPageClip(view, width - revealPx - seamBleedPx, width, height);
            return;
        }
        applyPageClip(view, 0, revealPx + seamBleedPx, height);
    }

    private void clearSimulationPagingLayer() {
        if (views.simulationPageTurnView != null) {
            views.simulationPageTurnView.clear();
        }
    }

    private void captureInteractiveStartPoint(float startX, float startY) {
        if (isSimulationFlipMode() && state.interactiveDirection != 0) {
            captureSimulationStartPoint(state.interactiveDirection, startY, false);
            return;
        }
        state.interactiveStartX = sanitizeStageTouchX(startX);
        state.interactiveStartY = sanitizeStageTouchY(startY);
        state.interactiveTouchX = state.interactiveStartX;
        state.interactiveTouchY = state.interactiveStartY;
    }

    private void updateInteractiveTouchPoint(float touchX, float touchY) {
        state.interactiveTouchX = sanitizeStageTouchX(touchX);
        if (isSimulationSpineBoundTurn()) {
            state.interactiveTouchX = sanitizeSimulationSpineBoundTouchX(state.interactiveTouchX);
        }
        state.interactiveTouchY = sanitizeStageTouchY(touchY);
    }

    private void initializeSimulationAutoStart(int direction, float height) {
        state.interactiveDirection = direction;
        float tapY = state.lastTapY >= 0
                ? state.lastTapY
                : (direction > 0 ? height * 0.85f : height * 0.15f);
        captureSimulationStartPoint(direction, tapY, true);
        state.lastTapY = -1f;
    }

    private void captureSimulationStartPoint(int direction, float startY, boolean expandedCornerFold) {
        float width = Math.max(views.pageStage == null ? 0f : views.pageStage.getWidth(), ui.dp(240));
        float cornerInset = expandedCornerFold ? simulationAutoStartInsetPx(width) : simulationCornerInsetPx();
        state.interactiveStartX = sanitizeStageTouchX(direction > 0 ? width - cornerInset : cornerInset);
        state.interactiveStartY = sanitizeStageTouchY(
                expandedCornerFold
                        ? resolveSimulationAutoStartY(direction, startY, width)
                        : resolveSimulationStartY(startY)
        );
        state.interactiveTouchX = state.interactiveStartX;
        state.interactiveTouchY = state.interactiveStartY;
    }

    private float resolveSimulationStartY(float touchY) {
        float height = Math.max(views.pageStage == null ? 0f : views.pageStage.getHeight(), ui.dp(320));
        float safeTouchY = Math.max(0f, Math.min(height, touchY));
        if (safeTouchY < height / 3f) {
            return 0.1f;
        }
        if (safeTouchY > height * 2f / 3f) {
            return height - 0.1f;
        }
        return height / 2f;
    }

    private float simulationCornerInsetPx() {
        return Math.max(2f, ui.dp(isPhoneReaderViewport() ? 2 : 4));
    }

    private float simulationAutoStartInsetPx(float stageWidth) {
        float height = Math.max(views.pageStage == null ? 0f : views.pageStage.getHeight(), ui.dp(320));
        float pageWidth = isSimulationSpineBoundTurn() ? stageWidth * 0.5f : stageWidth;
        return Math.max(simulationCornerInsetPx(), Math.min(pageWidth * 0.28f, height / 10f));
    }

    private float resolveSimulationAutoStartY(int direction, float touchY, float stageWidth) {
        float height = Math.max(views.pageStage == null ? 0f : views.pageStage.getHeight(), ui.dp(320));
        float safeTouchY = Math.max(0f, Math.min(height, touchY));
        float inset = simulationAutoStartInsetPx(stageWidth);
        if (safeTouchY < height / 3f) {
            return inset;
        }
        if (safeTouchY > height * 2f / 3f) {
            return height - inset;
        }
        return direction > 0 ? height - inset : inset;
    }

    private float sanitizeStageTouchX(float value) {
        float width = Math.max(views.pageStage == null ? 0f : views.pageStage.getWidth(), 1f);
        if (isSimulationSpineBoundTurn()) {
            float pageWidth = width * 0.5f;
            return Math.max(-pageWidth * 0.14f, Math.min(width + pageWidth * 0.14f, value));
        }
        if ("simulation".equals(runtime.settingsStore.getFlipMode())) {
            return Math.max(0.1f, Math.min(width - 0.1f, value));
        }
        return Math.max(-width * 3f, Math.min(width * 4f, value));
    }

    private float sanitizeStageTouchY(float value) {
        float height = Math.max(views.pageStage == null ? 0f : views.pageStage.getHeight(), 1f);
        if ("simulation".equals(runtime.settingsStore.getFlipMode())) {
            return Math.max(0.1f, Math.min(height - 0.1f, value));
        }
        return Math.max(-height * 3f, Math.min(height * 4f, value));
    }

    private float resolveSimulationTargetTouchX(int direction, boolean commit) {
        float width = Math.max(views.pageStage == null ? 0f : views.pageStage.getWidth(), ui.dp(240));
        if (isSimulationSpineBoundTurn()) {
            float pageWidth = width * 0.5f;
            float cornerInset = simulationCornerInsetPx();
            if (commit) {
                return direction > 0 ? -pageWidth * 0.12f : width + pageWidth * 0.12f;
            }
            return direction > 0 ? width - cornerInset : cornerInset;
        }
        if (commit) {
            return direction > 0 ? -width * 1.12f : width - 0.1f;
        }
        float cornerInset = simulationCornerInsetPx();
        return direction > 0 ? width - cornerInset : cornerInset;
    }

    private float resolveSimulationTargetTouchY(int direction) {
        float height = Math.max(views.pageStage == null ? 0f : views.pageStage.getHeight(), ui.dp(320));
        if (state.interactiveStartY < height / 3f) {
            return 0.1f;
        } else if (state.interactiveStartY > height * 2f / 3f) {
            return height - 0.1f;
        } else {
            return 0.1f;
        }
    }

    private boolean isSimulationDiagonalStartZone(float startY) {
        float height = Math.max(views.pageStage == null ? 0f : views.pageStage.getHeight(), ui.dp(320));
        return startY < height / 3f || startY > height * 2f / 3f;
    }

    private boolean isSimulationFlipMode() {
        return "simulation".equals(runtime.settingsStore.getFlipMode());
    }

    private boolean isSimulationSpineBoundTurn() {
        return isSimulationOuterPageTurnActive()
                && views.pageStage != null
                && views.pageStage.getWidth() > 0;
    }

    private boolean isSimulationOuterPageTurnActive() {
        return isSimulationFlipMode()
                && content != null
                && content.isDoublePageActive()
                && "outerPage".equals(runtime.settingsStore.getSimulationDoublePageTurnMode());
    }

    private int simulationTurnMode() {
        if (!isSimulationFlipMode() || content == null || !content.isDoublePageActive()) {
            return SimulationPageTurnView.TURN_MODE_SINGLE;
        }
        return "outerPage".equals(runtime.settingsStore.getSimulationDoublePageTurnMode())
                ? SimulationPageTurnView.TURN_MODE_OUTER_PAGE
                : SimulationPageTurnView.TURN_MODE_SPREAD;
    }

    private float interactiveProgressWidth() {
        float width = Math.max(views.pageStage == null ? 0f : views.pageStage.getWidth(), ui.dp(240));
        return width;
    }

    private float sanitizeSimulationSpineBoundTouchX(float touchX) {
        float width = Math.max(views.pageStage == null ? 0f : views.pageStage.getWidth(), ui.dp(240));
        float pageWidth = width * 0.5f;
        float minX = state.interactiveDirection > 0 ? -pageWidth * 0.14f : 0.1f;
        float maxX = state.interactiveDirection > 0 ? width - 0.1f : width + pageWidth * 0.14f;
        if (maxX < minX) {
            return touchX;
        }
        return Math.max(minX, Math.min(maxX, touchX));
    }

    private boolean isPhoneReaderViewport() {
        return ReaderDisplayModeHelper.isPhoneViewport(
                activity,
                views.pageStage == null ? 0 : views.pageStage.getWidth(),
                views.pageStage == null ? 0 : views.pageStage.getHeight()
        );
    }

    private boolean isTabletReaderViewport() {
        return ReaderDisplayModeHelper.isTabletViewport(
                activity,
                views.pageStage == null ? 0 : views.pageStage.getWidth(),
                views.pageStage == null ? 0 : views.pageStage.getHeight()
        );
    }

    private void resetInteractiveTouchState() {
        state.interactiveStartX = 0f;
        state.interactiveStartY = 0f;
        state.interactiveTouchX = 0f;
        state.interactiveTouchY = 0f;
    }

    private float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }

    private float normalizedAnimationValue(float value, float start, float end) {
        float range = end - start;
        if (Math.abs(range) < 0.0001f) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, (value - start) / range));
    }

    private void resetShadowView() {
        resetOverlayView(views.pageShadow);
        resetOverlayView(views.pageFoldShadow);
        resetOverlayView(views.pageFoldHighlight);
    }

    private void resetOverlayView(View view) {
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        view.setTranslationX(0f);
        view.setRotation(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    private static final class PageTarget {
        final int chapterIndex;
        final int pageIndex;

        private PageTarget(int chapterIndex, int pageIndex) {
            this.chapterIndex = chapterIndex;
            this.pageIndex = pageIndex;
        }
    }
}
