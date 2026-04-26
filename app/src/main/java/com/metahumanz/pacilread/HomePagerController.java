package com.metahumanz.pacilread;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;

final class HomePagerController {
    interface Callback {
        View sectionForPage(int page);
        boolean isTouchBlocked(MotionEvent event);
        void onPageChanged(int page, boolean syncFirst);
        void onSelectionChanged();
    }

    private static final int[] ALL_PAGES = new int[]{
            HomeNavigationController.PAGE_BOOKSHELF,
            HomeNavigationController.PAGE_STATS,
            HomeNavigationController.PAGE_BOOKMARKS,
            HomeNavigationController.PAGE_SETTINGS
    };
    private static final long CLICK_ANIMATION_DURATION = 280L;
    private static final long SETTLE_ANIMATION_DURATION = 220L;
    private static final long REBOUND_ANIMATION_DURATION = 190L;
    private static final float EDGE_RESISTANCE = 0.22f;
    private static final float COMMIT_DISTANCE_RATIO = 0.23f;
    private static final float COMMIT_VELOCITY_PX_PER_MS = 0.62f;

    private final View pageContainer;
    private final Callback callback;
    private final int touchSlop;
    private final Interpolator pageSwitchInterpolator = new PathInterpolator(0.22f, 0f, 0f, 1f);
    private final Interpolator pageReboundInterpolator = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final List<Integer> activePages = new ArrayList<>();

    private int currentPage = HomeNavigationController.PAGE_BOOKSHELF;
    private int pendingPage = -1;
    private int pendingDirection = 0;
    private boolean swipeCandidate = false;
    private boolean swipeDragging = false;
    private boolean edgeDragging = false;
    private float downX = 0f;
    private float downY = 0f;
    private float lastX = 0f;
    private long lastEventTime = 0L;
    private float velocityX = 0f;
    private ValueAnimator animator;

    HomePagerController(View pageContainer, Callback callback) {
        this.pageContainer = pageContainer;
        this.callback = callback;
        this.touchSlop = pageContainer == null ? 0 : ViewConfiguration.get(pageContainer.getContext()).getScaledTouchSlop();
    }

    int getCurrentPage() {
        return currentPage;
    }

    void setActivePages(List<Integer> pages) {
        activePages.clear();
        if (pages != null) {
            activePages.addAll(pages);
        }
        if (!activePages.contains(currentPage) && !activePages.isEmpty()) {
            currentPage = activePages.get(0);
        }
    }

    void setCurrentPage(int page) {
        currentPage = page;
    }

    void showImmediate(int page) {
        cancelAnimation();
        currentPage = page;
        for (int candidate : ALL_PAGES) {
            View section = callback.sectionForPage(candidate);
            if (section == null) {
                continue;
            }
            section.animate().cancel();
            section.setTranslationX(0f);
            section.setVisibility(candidate == page ? View.VISIBLE : View.GONE);
        }
        callback.onSelectionChanged();
    }

    void selectPage(int page, boolean animate, boolean syncFirst) {
        if (!activePages.contains(page)) {
            return;
        }
        if (page == currentPage) {
            callback.onPageChanged(page, syncFirst);
            return;
        }
        int oldPage = currentPage;
        currentPage = page;
        if (animate && pageContainer != null && pageContainer.getWidth() > 0) {
            animatePageTransition(oldPage, page, CLICK_ANIMATION_DURATION);
        } else {
            showImmediate(page);
        }
        callback.onPageChanged(page, syncFirst);
    }

    boolean handleTouchEvent(MotionEvent event) {
        if (activePages.size() <= 1 || pageContainer == null) {
            return false;
        }
        if (callback.isTouchBlocked(event)) {
            resetSwipe();
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (animator != null && animator.isRunning()) {
                    cancelAnimation();
                    showImmediate(currentPage);
                }
                swipeCandidate = true;
                swipeDragging = false;
                edgeDragging = false;
                downX = x;
                downY = y;
                lastX = x;
                lastEventTime = event.getEventTime();
                velocityX = 0f;
                pendingPage = -1;
                pendingDirection = 0;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!swipeCandidate) {
                    return false;
                }
                float deltaX = x - downX;
                float deltaY = y - downY;
                if (!swipeDragging) {
                    if (Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        resetSwipe();
                        return false;
                    }
                    if (Math.abs(deltaX) <= touchSlop * 1.25f || Math.abs(deltaX) <= Math.abs(deltaY) * 1.15f) {
                        return false;
                    }
                    pendingDirection = deltaX < 0f ? 1 : -1;
                    pendingPage = pageForDirection(pendingDirection);
                    swipeDragging = true;
                    edgeDragging = pendingPage < 0;
                    prepareSwipe();
                    cancelChildTouch(event);
                }
                updateSwipe(deltaX);
                updateVelocity(x, event.getEventTime());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipeDragging) {
                    finishSwipe(x - downX);
                    return true;
                }
                resetSwipe();
                return false;
            default:
                return false;
        }
    }

    private void animatePageTransition(int oldPage, int newPage, long duration) {
        View oldSection = callback.sectionForPage(oldPage);
        View newSection = callback.sectionForPage(newPage);
        if (oldSection == null || newSection == null || pageContainer == null) {
            showImmediate(newPage);
            return;
        }
        int width = containerWidth();
        int direction = activePages.indexOf(newPage) > activePages.indexOf(oldPage) ? 1 : -1;
        oldSection.animate().cancel();
        newSection.animate().cancel();
        oldSection.setVisibility(View.VISIBLE);
        newSection.setVisibility(View.VISIBLE);
        oldSection.setTranslationX(0f);
        newSection.setTranslationX(direction * width);
        animatePair(
                oldSection,
                newSection,
                0f,
                -direction * width,
                direction * width,
                0f,
                duration,
                pageSwitchInterpolator,
                () -> {
                    if (currentPage != oldPage) {
                        oldSection.setVisibility(View.GONE);
                    }
                    oldSection.setTranslationX(0f);
                    newSection.setTranslationX(0f);
                    callback.onSelectionChanged();
                }
        );
        callback.onSelectionChanged();
    }

    private int pageForDirection(int direction) {
        int currentIndex = activePages.indexOf(currentPage);
        int targetIndex = currentIndex + direction;
        if (targetIndex < 0 || targetIndex >= activePages.size()) {
            return -1;
        }
        return activePages.get(targetIndex);
    }

    private void prepareSwipe() {
        View current = callback.sectionForPage(currentPage);
        if (current != null) {
            current.animate().cancel();
            current.setVisibility(View.VISIBLE);
        }
        if (!edgeDragging) {
            View target = callback.sectionForPage(pendingPage);
            if (target != null) {
                target.animate().cancel();
                target.setVisibility(View.VISIBLE);
                target.setTranslationX(pendingDirection * containerWidth());
            }
        }
    }

    private void updateSwipe(float deltaX) {
        View current = callback.sectionForPage(currentPage);
        if (current == null) {
            return;
        }
        int width = containerWidth();
        float clamped = Math.max(-width, Math.min(width, deltaX));
        if (edgeDragging) {
            current.setTranslationX(clamped * EDGE_RESISTANCE);
            return;
        }
        View target = callback.sectionForPage(pendingPage);
        if (target == null) {
            return;
        }
        current.setTranslationX(clamped);
        target.setTranslationX(pendingDirection * width + clamped);
    }

    private void finishSwipe(float deltaX) {
        if (edgeDragging || pendingPage < 0) {
            reboundCurrentPage();
            resetSwipe();
            return;
        }
        View current = callback.sectionForPage(currentPage);
        View target = callback.sectionForPage(pendingPage);
        if (current == null || target == null) {
            resetSwipe();
            showImmediate(currentPage);
            return;
        }
        int width = containerWidth();
        boolean commit = Math.abs(deltaX) > width * COMMIT_DISTANCE_RATIO
                || (Math.abs(velocityX) > COMMIT_VELOCITY_PX_PER_MS
                && Math.signum(velocityX) == -pendingDirection);
        int oldPage = currentPage;
        int targetPage = pendingPage;
        if (commit) {
            float currentFrom = current.getTranslationX();
            float targetFrom = target.getTranslationX();
            currentPage = targetPage;
            animatePair(
                    current,
                    target,
                    currentFrom,
                    -pendingDirection * width,
                    targetFrom,
                    0f,
                    SETTLE_ANIMATION_DURATION,
                    pageSwitchInterpolator,
                    () -> {
                        if (currentPage != oldPage) {
                            current.setVisibility(View.GONE);
                        }
                        current.setTranslationX(0f);
                        target.setTranslationX(0f);
                        callback.onSelectionChanged();
                    }
            );
            callback.onPageChanged(targetPage, false);
            callback.onSelectionChanged();
        } else {
            animatePair(
                    current,
                    target,
                    current.getTranslationX(),
                    0f,
                    target.getTranslationX(),
                    pendingDirection * width,
                    REBOUND_ANIMATION_DURATION,
                    pageReboundInterpolator,
                    () -> {
                        target.setVisibility(View.GONE);
                        current.setTranslationX(0f);
                        target.setTranslationX(0f);
                        callback.onSelectionChanged();
                    }
            );
        }
        resetSwipe();
    }

    private void reboundCurrentPage() {
        View current = callback.sectionForPage(currentPage);
        if (current == null) {
            return;
        }
        animateSingle(
                current,
                current.getTranslationX(),
                0f,
                REBOUND_ANIMATION_DURATION,
                pageReboundInterpolator,
                () -> callback.onSelectionChanged()
        );
    }

    private void updateVelocity(float x, long eventTime) {
        long elapsed = Math.max(1L, eventTime - lastEventTime);
        velocityX = (x - lastX) / elapsed;
        lastX = x;
        lastEventTime = eventTime;
    }

    private void animatePair(
            View first,
            View second,
            float firstFrom,
            float firstTo,
            float secondFrom,
            float secondTo,
            long duration,
            Interpolator interpolator,
            Runnable endAction
    ) {
        cancelAnimation();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            first.setTranslationX(lerp(firstFrom, firstTo, progress));
            second.setTranslationX(lerp(secondFrom, secondTo, progress));
        });
        animator.addListener(new SimpleAnimatorListener(endAction));
        animator.start();
    }

    private void animateSingle(View view, float from, float to, long duration, Interpolator interpolator, Runnable endAction) {
        cancelAnimation();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            view.setTranslationX(lerp(from, to, progress));
        });
        animator.addListener(new SimpleAnimatorListener(endAction));
        animator.start();
    }

    private void cancelAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void resetSwipe() {
        swipeCandidate = false;
        swipeDragging = false;
        edgeDragging = false;
        pendingPage = -1;
        pendingDirection = 0;
        velocityX = 0f;
    }

    private void cancelChildTouch(MotionEvent event) {
        if (pageContainer == null || event == null) {
            return;
        }
        MotionEvent cancelEvent = MotionEvent.obtain(event);
        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
        pageContainer.dispatchTouchEvent(cancelEvent);
        cancelEvent.recycle();
    }

    private int containerWidth() {
        return Math.max(pageContainer == null ? 0 : pageContainer.getWidth(), 1);
    }

    private float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static final class SimpleAnimatorListener implements android.animation.Animator.AnimatorListener {
        private final Runnable endAction;
        private boolean cancelled = false;

        SimpleAnimatorListener(Runnable endAction) {
            this.endAction = endAction;
        }

        @Override
        public void onAnimationStart(android.animation.Animator animation) {}

        @Override
        public void onAnimationEnd(android.animation.Animator animation) {
            if (!cancelled && endAction != null) {
                endAction.run();
            }
        }

        @Override
        public void onAnimationCancel(android.animation.Animator animation) {
            cancelled = true;
        }

        @Override
        public void onAnimationRepeat(android.animation.Animator animation) {}
    }
}
