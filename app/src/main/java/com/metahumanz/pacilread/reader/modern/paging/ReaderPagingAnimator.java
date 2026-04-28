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
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;

import java.util.List;

public final class ReaderPagingAnimator {
    private static final DecelerateInterpolator PAGE_SLIDE_INTERPOLATOR = new DecelerateInterpolator(1.35f);
    private static final AccelerateDecelerateInterpolator PAGE_TURN_INTERPOLATOR = new AccelerateDecelerateInterpolator();

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
                    float slop = Math.max(state.pagingTouchSlop, 1);
                    float distanceSquare = deltaX * deltaX + deltaY * deltaY;
                    if (distanceSquare <= slop * slop) {
                        return false;
                    }
                    if (Math.abs(deltaY) > Math.abs(deltaX)) {
                        state.pagingGestureCandidate = false;
                        return false;
                    }
                    if (Math.abs(deltaX) <= Math.abs(deltaY)) {
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
                float width = Math.max(views.pageStage.getWidth(), ui.dp(240));
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
            finishAnimation(targetChapterIndex, targetPageIndex, token);
            return;
        }
        if ("simulation".equals(mode)) {
            initializeSimulationAutoStart(direction, width, height);
        } else {
            resetInteractiveTouchState();
        }
        navigation.bindIncomingSpread(targetChapterIndex, targetPageIndex);
        preparePagingSnapshots(targetChapterIndex, targetPageIndex);
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
        final boolean[] cancelled = new boolean[]{false};
        state.interactiveAnimator.addUpdateListener(animation -> {
            if ("simulation".equals(mode)) {
                float fraction = animation.getAnimatedFraction();
                state.interactiveTouchX = lerp(startTouchX, targetTouchX, fraction);
                state.interactiveTouchY = lerp(startTouchY, targetTouchY, fraction);
            }
            applyPagingVisuals(
                    mode,
                    direction,
                    (float) animation.getAnimatedValue(),
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
                finishAnimation(targetChapterIndex, targetPageIndex, token);
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
            baseDuration = 300L;
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
        views.pageStage.removeCallbacks(pagingSnapshotWarmupRunnable);
        if (state.chapters.isEmpty() || state.isAnimating || state.interactivePaging) {
            return;
        }
        views.pageStage.post(pagingSnapshotWarmupRunnable);
    }

    public void invalidatePreparedPagingSnapshots() {
        state.preparedCurrentSnapshotChapterIndex = -1;
        state.preparedCurrentSnapshotPageIndex = -1;
        state.preparedIncomingSnapshotChapterIndex = -1;
        state.preparedIncomingSnapshotPageIndex = -1;
        if (views.pageStage != null) {
            views.pageStage.removeCallbacks(pagingSnapshotWarmupRunnable);
        }
    }

    public void restoreLivePageLayers(boolean incomingVisible) {
        state.pagingSnapshotsVisible = false;
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
        state.interactiveTargetChapterIndex = -1;
        state.interactiveTargetPageIndex = -1;
        clearAnimationTarget();
        resetInteractiveTouchState();
        restoreLivePageLayers(false);
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
        state.currentPageSnapshotBitmap = null;
        state.incomingPageSnapshotBitmap = null;
    }

    public void removeWarmupCallbacks() {
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
        cancelInteractiveAnimator();
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
        preparePagingSnapshots(target.chapterIndex, target.pageIndex);
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

    private void updateInteractiveShadow(float edgeX, int direction, float alpha) {
        updatePagingOverlay(views.pageShadow, edgeX, direction, alpha, 1f, 1f, 0f, 0.56f);
    }

    private void updateInteractiveFoldShadow(float edgeX, int direction, float alpha, float scaleX, float rotation) {
        updatePagingOverlay(views.pageFoldShadow, edgeX, direction, alpha, 1f, scaleX, rotation, 0.8f);
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
        if (directionalVelocity > 1.05f) {
            return true;
        }
        if (directionalVelocity < -0.25f || state.interactiveCancel) {
            return false;
        }
        float directionalDrag = state.interactiveDirection > 0
                ? state.pagingDownX - state.interactiveTouchX
                : state.interactiveTouchX - state.pagingDownX;
        return directionalDrag > Math.max(state.pagingTouchSlop, 1);
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
        final boolean[] cancelled = new boolean[]{false};
        state.interactiveAnimator.addUpdateListener(animation -> {
            if ("simulation".equals(mode)) {
                float fraction = animation.getAnimatedFraction();
                state.interactiveTouchX = lerp(startTouchX, targetTouchX, fraction);
                state.interactiveTouchY = lerp(startTouchY, targetTouchY, fraction);
            }
            applyInteractivePagingProgress((float) animation.getAnimatedValue(), state.interactiveTouchY);
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
                    finishAnimation(state.interactiveTargetChapterIndex, state.interactiveTargetPageIndex, token);
                } else {
                    cancelInteractivePaging();
                }
            }
        });
        state.interactiveAnimator.start();
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

    private void finishAnimation(int targetChapterIndex, int targetPageIndex, long token) {
        if (token != state.animationToken) {
            return;
        }
        state.currentChapterIndex = targetChapterIndex;
        state.currentPageIndex = targetPageIndex;
        promoteIncomingSnapshotToCurrent(targetChapterIndex, targetPageIndex);
        navigation.bindCurrentSpread(targetChapterIndex, targetPageIndex);
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
        views.pageCurrent.setVisibility(View.VISIBLE);
        views.pageCurrent.bringToFront();
        views.pageIncoming.setVisibility(View.GONE);
        resetInteractiveTouchState();
        state.isAnimating = false;
        state.pendingTapPagingDelta = 0;
        activity.markReadingActivity();
        chrome.updateUiAfterPageChange();
        content.scheduleProgressSave();
        chrome.scheduleAutoHide();
        schedulePagingSnapshotWarmup();
    }

    private void preparePagingSnapshots(int targetChapterIndex, int targetPageIndex) {
        if (views.pageSnapshotCurrent == null || views.pageSnapshotIncoming == null) {
            state.pagingSnapshotsVisible = false;
            return;
        }
        clearSimulationPagingLayer();
        if (!hasPreparedCurrentSnapshot(state.currentChapterIndex, state.currentPageIndex)) {
            navigation.bindCurrentSpread(state.currentChapterIndex, state.currentPageIndex);
            layoutPageLayerForSnapshot(views.pageCurrent);
            state.currentPageSnapshotBitmap = screenshotPageLayer(views.pageCurrent, state.currentPageSnapshotBitmap);
            if (state.currentPageSnapshotBitmap != null) {
                state.preparedCurrentSnapshotChapterIndex = state.currentChapterIndex;
                state.preparedCurrentSnapshotPageIndex = state.currentPageIndex;
            }
        }
        if (!hasPreparedIncomingSnapshot(targetChapterIndex, targetPageIndex)) {
            Bitmap preparedBitmap = capturePreparedIncomingSnapshot(targetChapterIndex, targetPageIndex);
            if (preparedBitmap != null) {
                state.incomingPageSnapshotBitmap = preparedBitmap;
                state.preparedIncomingSnapshotChapterIndex = targetChapterIndex;
                state.preparedIncomingSnapshotPageIndex = targetPageIndex;
            }
        }
        if (state.currentPageSnapshotBitmap == null || state.incomingPageSnapshotBitmap == null) {
            restoreLivePageLayers(true);
            return;
        }
        views.pageSnapshotCurrent.setImageBitmap(state.currentPageSnapshotBitmap);
        views.pageSnapshotIncoming.setImageBitmap(state.incomingPageSnapshotBitmap);
        views.pageSnapshotCurrent.setVisibility(View.VISIBLE);
        views.pageSnapshotIncoming.setVisibility(View.VISIBLE);
        views.pageCurrent.setVisibility(View.INVISIBLE);
        views.pageIncoming.setVisibility(View.INVISIBLE);
        state.pagingSnapshotsVisible = true;
    }

    private Bitmap screenshotPageLayer(View source, Bitmap reuse) {
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
        pagingSnapshotCanvas.setBitmap(null);
        targetBitmap.prepareToDraw();
        return targetBitmap;
    }

    private void warmPreparedPagingSnapshots() {
        if (views.pageStage == null || state.chapters.isEmpty() || state.isAnimating || state.interactivePaging) {
            return;
        }
        if (!ensurePageAreaReady(this::schedulePagingSnapshotWarmup)) {
            return;
        }
        if (!hasPreparedCurrentSnapshot(state.currentChapterIndex, state.currentPageIndex)) {
            state.currentPageSnapshotBitmap = screenshotPageLayer(views.pageCurrent, state.currentPageSnapshotBitmap);
            if (state.currentPageSnapshotBitmap != null) {
                state.preparedCurrentSnapshotChapterIndex = state.currentChapterIndex;
                state.preparedCurrentSnapshotPageIndex = state.currentPageIndex;
            }
        }
        PageTarget target = resolveInteractiveTarget(1);
        if (target == null) {
            target = resolveInteractiveTarget(-1);
        }
        if (target == null || hasPreparedIncomingSnapshot(target.chapterIndex, target.pageIndex)) {
            return;
        }
        Bitmap preparedBitmap = capturePreparedIncomingSnapshot(target.chapterIndex, target.pageIndex);
        if (preparedBitmap == null) {
            state.preparedIncomingSnapshotChapterIndex = -1;
            state.preparedIncomingSnapshotPageIndex = -1;
            return;
        }
        state.incomingPageSnapshotBitmap = preparedBitmap;
        state.preparedIncomingSnapshotChapterIndex = target.chapterIndex;
        state.preparedIncomingSnapshotPageIndex = target.pageIndex;
    }

    private Bitmap capturePreparedIncomingSnapshot(int chapterIndex, int pageIndex) {
        if (views.pageIncoming == null) {
            return null;
        }
        int previousVisibility = views.pageIncoming.getVisibility();
        float previousAlpha = views.pageIncoming.getAlpha();
        navigation.bindIncomingSpread(chapterIndex, pageIndex);
        resetAnimatedPage(views.pageIncoming);
        if (views.pageCurrent != null) {
            views.pageCurrent.bringToFront();
        }
        views.pageIncoming.setVisibility(View.VISIBLE);
        if (!layoutPageLayerForSnapshot(views.pageIncoming)) {
            views.pageIncoming.setAlpha(previousAlpha);
            views.pageIncoming.setVisibility(previousVisibility);
            return null;
        }
        Bitmap bitmap = screenshotPageLayer(views.pageIncoming, state.incomingPageSnapshotBitmap);
        views.pageIncoming.setAlpha(previousAlpha);
        views.pageIncoming.setVisibility(previousVisibility);
        resetAnimatedPage(views.pageIncoming);
        if (views.pageCurrent != null) {
            views.pageCurrent.bringToFront();
        }
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

    private void promoteIncomingSnapshotToCurrent(int chapterIndex, int pageIndex) {
        if (!hasPreparedIncomingSnapshot(chapterIndex, pageIndex)) {
            state.preparedCurrentSnapshotChapterIndex = -1;
            state.preparedCurrentSnapshotPageIndex = -1;
            return;
        }
        Bitmap previousCurrentBitmap = state.currentPageSnapshotBitmap;
        state.currentPageSnapshotBitmap = state.incomingPageSnapshotBitmap;
        state.incomingPageSnapshotBitmap = previousCurrentBitmap;
        state.preparedCurrentSnapshotChapterIndex = chapterIndex;
        state.preparedCurrentSnapshotPageIndex = pageIndex;
        state.preparedIncomingSnapshotChapterIndex = -1;
        state.preparedIncomingSnapshotPageIndex = -1;
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
        if (views.pageShadow != null) {
            views.pageShadow.bringToFront();
        }
        if (views.pageFoldShadow != null) {
            views.pageFoldShadow.bringToFront();
        }
        if (views.pageFoldHighlight != null) {
            views.pageFoldHighlight.bringToFront();
        }
    }

    private void applyPagingVisuals(String mode, int direction, float progress, float touchY) {
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
        incomingLayer.setVisibility(View.VISIBLE);
        if (!"simulation".equals(mode)) {
            clearSimulationPagingLayer();
            hideInteractiveFoldEffects();
        }

        if ("simulation".equals(mode)) {
            resetShadowView();
            if (views.pageSnapshotCurrent != null) {
                views.pageSnapshotCurrent.setVisibility(View.GONE);
            }
            if (views.pageSnapshotIncoming != null) {
                views.pageSnapshotIncoming.setVisibility(View.GONE);
            }
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
                        state.currentReaderPageColor
                );
            }
            return;
        }

        if ("cover".equals(mode)) {
            float revealWidth = width * safeProgress;
            currentLayer.setTranslationX((direction > 0 ? -1f : 1f) * width * safeProgress);
            applyRevealedIncomingClip(incomingLayer, direction, revealWidth, widthPx, heightPx);
            incomingLayer.setAlpha(1f);
            float edgeX = direction > 0 ? width + currentLayer.getTranslationX() : currentLayer.getTranslationX();
            updateInteractiveShadow(edgeX, direction, 0.18f + 0.24f * safeProgress);
            return;
        }

        if ("scroll".equals(mode)) {
            float offsetY = (direction > 0 ? 1f : -1f) * height * safeProgress;
            currentLayer.setTranslationY(-offsetY);
            incomingLayer.setTranslationY((direction > 0 ? 1f : -1f) * height * (1f - safeProgress));
            incomingLayer.setAlpha(0.94f + 0.06f * safeProgress);
            updateInteractiveShadow(width * 0.5f, direction, 0f);
            return;
        }

        float revealWidth = width * safeProgress;
        currentLayer.setTranslationX((direction > 0 ? -1f : 1f) * revealWidth);
        incomingLayer.setTranslationX(direction > 0 ? width - revealWidth : -width + revealWidth);
        incomingLayer.setAlpha(0.95f + 0.05f * safeProgress);
        if (direction > 0) {
            applyPageClip(incomingLayer, 0, Math.round(revealWidth), heightPx);
            updateInteractiveShadow(width - revealWidth, direction, "none".equals(mode) ? 0f : 0.14f + 0.16f * safeProgress);
        } else {
            applyPageClip(incomingLayer, widthPx - Math.round(revealWidth), widthPx, heightPx);
            updateInteractiveShadow(revealWidth, direction, "none".equals(mode) ? 0f : 0.14f + 0.16f * safeProgress);
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
        state.interactiveStartX = sanitizeStageTouchX(startX);
        state.interactiveStartY = sanitizeStageTouchY(startY);
        state.interactiveTouchX = state.interactiveStartX;
        state.interactiveTouchY = state.interactiveStartY;
    }

    private void updateInteractiveTouchPoint(float touchX, float touchY) {
        state.interactiveTouchX = sanitizeStageTouchX(touchX);
        state.interactiveTouchY = sanitizeStageTouchY(touchY);
    }

    private void initializeSimulationAutoStart(int direction, float width, float height) {
        float startX = direction > 0 ? width - 5f : 5f;
        float startY;
        float tapY = state.lastTapY >= 0 ? state.lastTapY : height / 2f;
        if (tapY < height / 3f) {
            startY = 5f;
        } else if (tapY > height * 2f / 3f) {
            startY = height - 5f;
        } else {
            startY = height / 2f;
        }
        captureInteractiveStartPoint(startX, startY);
        state.lastTapY = -1f;
    }

    private float sanitizeStageTouchX(float value) {
        float width = Math.max(views.pageStage == null ? 0f : views.pageStage.getWidth(), 1f);
        return Math.max(-width * 3f, Math.min(width * 4f, value));
    }

    private float sanitizeStageTouchY(float value) {
        float height = Math.max(views.pageStage == null ? 0f : views.pageStage.getHeight(), 1f);
        return Math.max(-height * 3f, Math.min(height * 4f, value));
    }

    private float resolveSimulationTargetTouchX(int direction, boolean commit) {
        float width = Math.max(views.pageStage == null ? 0f : views.pageStage.getWidth(), ui.dp(240));
        if (commit) {
            return direction > 0 ? -width * 2.5f : width * 3.5f;
        }
        return direction > 0 ? width * 1.5f : -width * 0.5f;
    }

    private float resolveSimulationTargetTouchY(int direction) {
        float height = Math.max(views.pageStage == null ? 0f : views.pageStage.getHeight(), ui.dp(320));
        if (state.interactiveStartY < height / 3f) {
            return height * 1.5f;
        } else if (state.interactiveStartY > height * 2f / 3f) {
            return -height * 0.5f;
        } else {
            return state.interactiveStartY;
        }
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
