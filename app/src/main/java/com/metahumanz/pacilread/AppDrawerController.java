package com.metahumanz.pacilread;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class AppDrawerController {
    private static final float DRAWER_WIDTH_RATIO = 0.84f;
    private static final int DRAWER_MIN_WIDTH_DP = 300;
    private static final int DRAWER_MAX_WIDTH_DP = 420;
    private static final int EDGE_ACTIVATION_MIN_DP = 112;
    private static final int EDGE_ACTIVATION_MAX_DP = 156;
    private static final int EDGE_EXCLUSION_DP = 156;
    private static final int OPEN_DRAWER_DRAG_EXTRA_DP = 72;
    private static final float OPEN_THRESHOLD_RATIO = 0.34f;
    private static final float CLOSE_THRESHOLD_RATIO = 0.22f;
    private static final float FLING_VELOCITY_THRESHOLD = 0.55f;
    public static final int SECTION_NONE = -1;
    public static final int SECTION_BOOKSHELF = 0;
    public static final int SECTION_PREVIEW = 1;
    public static final int SECTION_SETTINGS = 2;

    public interface NavigationListener {
        void onDrawerDestinationSelected(int destination);
    }

    private final Activity activity;
    private final View rootView;
    private final View drawerPanel;
    private final View drawerScrim;
    private final View navBookshelf;
    private final View navPreview;
    private final View navSettings;
    private final TextView navBookshelfText;
    private final TextView navPreviewText;
    private final TextView navSettingsText;
    private final TextView statusText;
    private final NavigationListener navigationListener;
    private final int touchSlop;

    private int currentSection = SECTION_NONE;
    private boolean drawerOpen = false;
    private boolean drawerGestureCandidate = false;
    private boolean drawerDragging = false;
    private float drawerDownX = 0f;
    private float drawerDownY = 0f;
    private float drawerBaseOffset = 0f;
    private float drawerLastX = 0f;
    private long drawerLastEventTime = 0L;
    private float drawerVelocityX = 0f;
    private boolean pendingChildTouchCancel = false;
    private boolean drawerGestureStartedOpen = false;
    private long drawerAnimationToken = 0L;

    public AppDrawerController(Activity activity, View rootView, NavigationListener navigationListener) {
        this.activity = activity;
        this.rootView = rootView;
        this.navigationListener = navigationListener;
        this.drawerPanel = activity.findViewById(R.id.drawer_panel);
        this.drawerScrim = activity.findViewById(R.id.drawer_scrim);
        this.navBookshelf = activity.findViewById(R.id.nav_bookshelf);
        this.navPreview = activity.findViewById(R.id.nav_preview);
        this.navSettings = activity.findViewById(R.id.nav_settings);
        this.navBookshelfText = activity.findViewById(R.id.text_nav_bookshelf);
        this.navPreviewText = activity.findViewById(R.id.text_nav_preview);
        this.navSettingsText = activity.findViewById(R.id.text_nav_settings);
        this.statusText = activity.findViewById(R.id.text_drawer_status);
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        setupViews();
    }

    private void setupViews() {
        if (drawerPanel == null || drawerScrim == null) {
            return;
        }
        drawerScrim.setOnClickListener(v -> closeDrawer());
        navBookshelf.setOnClickListener(v -> {
            navigationListener.onDrawerDestinationSelected(SECTION_BOOKSHELF);
            closeDrawer();
        });
        navPreview.setOnClickListener(v -> {
            navigationListener.onDrawerDestinationSelected(SECTION_PREVIEW);
            closeDrawer();
        });
        navSettings.setOnClickListener(v -> {
            navigationListener.onDrawerDestinationSelected(SECTION_SETTINGS);
            closeDrawer();
        });

        drawerPanel.post(() -> {
            updateDrawerPanelWidth();
            setDrawerOffset(-drawerPanel.getWidth());
            drawerPanel.setVisibility(View.INVISIBLE);
            drawerScrim.setVisibility(View.GONE);
            drawerScrim.setAlpha(0f);
        });
        if (rootView != null) {
            rootView.post(() -> {
                updateDrawerPanelWidth();
                syncDrawerOffsetToCurrentState();
                updateDrawerGestureExclusion();
            });
            rootView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    handleRootLayoutChanged()
            );
        }
        updateNavigationState();
    }

    public void bindMenuButton(int viewId) {
        View button = activity.findViewById(viewId);
        if (button != null) {
            button.setOnClickListener(v -> openDrawer());
        }
    }

    public void setCurrentSection(int section) {
        currentSection = section;
        updateNavigationState();
    }

    public void setStatusText(String text) {
        if (statusText != null) {
            statusText.setText(text == null ? "" : text);
        }
    }

    public boolean onBackPressed() {
        if (isDrawerVisible()) {
            closeDrawer();
            return true;
        }
        return false;
    }

    public boolean handleTouchEvent(MotionEvent event) {
        if (drawerPanel == null || drawerPanel.getWidth() == 0) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pendingChildTouchCancel = false;
                drawerGestureCandidate = shouldStartDrawerGesture(x);
                drawerDragging = false;
                drawerVelocityX = 0f;
                drawerGestureStartedOpen = isDrawerVisible();
                if (drawerGestureCandidate) {
                    drawerDownX = x;
                    drawerDownY = y;
                    drawerLastX = x;
                    drawerLastEventTime = event.getEventTime();
                    drawerBaseOffset = drawerPanel.getTranslationX();
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!drawerGestureCandidate) {
                    return false;
                }
                float deltaX = x - drawerDownX;
                float deltaY = y - drawerDownY;
                if (!drawerDragging) {
                    int horizontalThreshold = isDrawerVisible() ? touchSlop : Math.max(4, touchSlop / 2);
                    if (Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        drawerGestureCandidate = false;
                        return false;
                    }
                    if (Math.abs(deltaX) <= horizontalThreshold || Math.abs(deltaX) <= Math.abs(deltaY)) {
                        return false;
                    }
                    if (!isDrawerVisible() && deltaX < 0f) {
                        drawerGestureCandidate = false;
                        return false;
                    }
                    drawerDragging = true;
                    pendingChildTouchCancel = true;
                    prepareDrawerForInteraction(false);
                }
                float targetOffset = clamp(drawerBaseOffset + deltaX, -drawerPanel.getWidth(), 0f);
                setDrawerOffset(targetOffset);
                long now = event.getEventTime();
                long elapsed = Math.max(1L, now - drawerLastEventTime);
                drawerVelocityX = (x - drawerLastX) / elapsed;
                drawerLastX = x;
                drawerLastEventTime = now;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drawerDragging) {
                    finishDrawerGesture();
                    return true;
                }
                if (drawerGestureCandidate && isDrawerVisible() && x > drawerPanel.getWidth()) {
                    closeDrawer();
                    drawerGestureCandidate = false;
                    drawerGestureStartedOpen = false;
                    return true;
                }
                drawerGestureCandidate = false;
                drawerGestureStartedOpen = false;
                return false;
            default:
                return false;
        }
    }

    public void openDrawer() {
        if (drawerPanel == null) {
            return;
        }
        if (drawerPanel.getWidth() == 0) {
            drawerPanel.post(this::openDrawer);
            return;
        }
        drawerOpen = true;
        animateDrawerTo(0f, 320L);
    }

    public void closeDrawer() {
        if (drawerPanel == null || drawerPanel.getWidth() == 0) {
            return;
        }
        drawerOpen = false;
        animateDrawerTo(-drawerPanel.getWidth(), 240L);
    }

    public boolean isDrawerVisible() {
        return drawerPanel != null && (drawerPanel.getVisibility() == View.VISIBLE || drawerDragging);
    }

    public boolean consumePendingChildTouchCancel() {
        boolean shouldCancel = pendingChildTouchCancel;
        pendingChildTouchCancel = false;
        return shouldCancel;
    }

    private void updateNavigationState() {
        styleNavItem(navBookshelf, navBookshelfText, currentSection == SECTION_BOOKSHELF);
        styleNavItem(navPreview, navPreviewText, currentSection == SECTION_PREVIEW);
        styleNavItem(navSettings, navSettingsText, currentSection == SECTION_SETTINGS);
    }

    private void styleNavItem(View container, TextView textView, boolean selected) {
        if (container == null) {
            return;
        }
        container.setBackgroundResource(selected ? R.drawable.bg_nav_item_active : R.drawable.bg_nav_item_idle);
        if (textView != null) {
            textView.setTextColor(activity.getColor(selected ? R.color.primary : R.color.on_surface));
        }
    }

    private boolean shouldStartDrawerGesture(float x) {
        if (!isDrawerVisible()) {
            if (currentSection == SECTION_BOOKSHELF || currentSection == SECTION_PREVIEW) {
                return true;
            }
            return x <= closedDrawerActivationWidth();
        }
        float panelWidth = drawerPanel.getWidth();
        return x <= panelWidth + dp(OPEN_DRAWER_DRAG_EXTRA_DP);
    }

    private void finishDrawerGesture() {
        float offset = drawerPanel.getTranslationX();
        float panelWidth = Math.max(drawerPanel.getWidth(), 1);
        boolean shouldOpen;
        if (drawerGestureStartedOpen) {
            float closeThreshold = -panelWidth * CLOSE_THRESHOLD_RATIO;
            shouldOpen = !(offset <= closeThreshold || drawerVelocityX < -FLING_VELOCITY_THRESHOLD);
            if (drawerVelocityX > FLING_VELOCITY_THRESHOLD) {
                shouldOpen = true;
            }
        } else {
            float openThreshold = -panelWidth * (1f - OPEN_THRESHOLD_RATIO);
            shouldOpen = offset > openThreshold || drawerVelocityX > FLING_VELOCITY_THRESHOLD;
            if (drawerVelocityX < -FLING_VELOCITY_THRESHOLD) {
                shouldOpen = false;
            }
        }
        drawerGestureCandidate = false;
        drawerDragging = false;
        drawerGestureStartedOpen = false;
        if (shouldOpen) {
            openDrawer();
        } else {
            closeDrawer();
        }
    }

    private void animateDrawerTo(float targetOffset, long durationMs) {
        prepareDrawerForInteraction(true);
        drawerPanel.animate().cancel();
        drawerScrim.animate().cancel();
        final long animationToken = ++drawerAnimationToken;
        float startOffset = clamp(drawerPanel.getTranslationX(), -drawerPanel.getWidth(), 0f);
        float panelWidth = Math.max(drawerPanel.getWidth(), 1f);
        float remainingRatio = Math.abs(targetOffset - startOffset) / panelWidth;
        if (remainingRatio <= 0.015f) {
            applyDrawerOffsetState(targetOffset, false);
            return;
        }
        float progress = drawerProgressForOffset(targetOffset);
        float scrimAlpha = drawerScrimAlpha(progress);
        
        // Use different interpolators for open and close actions
        boolean isOpening = targetOffset == 0f;
        android.view.animation.Interpolator interpolator = isOpening 
                ? new DecelerateInterpolator(1.3f) 
                : new android.view.animation.AccelerateInterpolator(1.2f);

        drawerPanel.animate()
                .translationX(targetOffset)
                .alpha(drawerPanelAlpha(progress))
                .scaleY(drawerPanelScaleY(progress))
                .setDuration(durationMs)
                .setInterpolator(interpolator)
                .withEndAction(() -> {
                    if (animationToken != drawerAnimationToken) {
                        return;
                    }
                    applyDrawerOffsetState(targetOffset, false);
                })
                .start();
        drawerScrim.animate()
                .alpha(scrimAlpha)
                .setDuration(durationMs)
                .setInterpolator(isOpening ? new DecelerateInterpolator(1.3f) : new android.view.animation.AccelerateInterpolator(1.2f))
                .start();
    }

    private void prepareDrawerForInteraction(boolean forceVisible) {
        drawerAnimationToken++;
        drawerScrim.bringToFront();
        drawerPanel.bringToFront();
        drawerPanel.animate().cancel();
        drawerScrim.animate().cancel();
        float panelWidth = Math.max(drawerPanel.getWidth(), 1);
        float currentOffset = clamp(drawerPanel.getTranslationX(), -panelWidth, 0f);
        applyDrawerOffsetState(currentOffset, forceVisible);
    }

    private void setDrawerOffset(float offset) {
        float clamped = clamp(offset, -drawerPanel.getWidth(), 0f);
        applyDrawerOffsetState(clamped, false);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private int closedDrawerActivationWidth() {
        int panelWidth = drawerPanel == null ? 0 : drawerPanel.getWidth();
        int preferred = panelWidth > 0 ? Math.round(panelWidth * 0.56f) : dp(EDGE_ACTIVATION_MIN_DP);
        return clamp(preferred, dp(EDGE_ACTIVATION_MIN_DP), dp(EDGE_ACTIVATION_MAX_DP));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void applyDrawerOffsetState(float offset, boolean keepVisible) {
        if (drawerPanel == null || drawerScrim == null) {
            return;
        }
        float panelWidth = Math.max(drawerPanel.getWidth(), 1f);
        float clampedOffset = clamp(offset, -panelWidth, 0f);
        float progress = drawerProgressForOffset(clampedOffset);
        float scrimAlpha = drawerScrimAlpha(progress);
        boolean showPanel = keepVisible || progress > 0.001f;
        boolean showScrim = keepVisible || scrimAlpha > 0f;

        drawerPanel.setPivotX(0f);
        drawerPanel.setPivotY(drawerPanel.getHeight() * 0.5f);
        drawerPanel.setVisibility(showPanel ? View.VISIBLE : View.INVISIBLE);
        drawerPanel.setTranslationX(clampedOffset);
        drawerPanel.setAlpha(showPanel ? drawerPanelAlpha(progress) : 0f);
        drawerPanel.setScaleY(drawerPanelScaleY(progress));

        drawerScrim.setVisibility(showScrim ? View.VISIBLE : View.GONE);
        drawerScrim.setAlpha(showScrim ? scrimAlpha : 0f);
        drawerOpen = progress > 0.95f;
    }

    private float drawerProgressForOffset(float offset) {
        float panelWidth = Math.max(drawerPanel.getWidth(), 1f);
        float clampedOffset = clamp(offset, -panelWidth, 0f);
        return 1f - (-clampedOffset / panelWidth);
    }

    private float drawerPanelAlpha(float progress) {
        float safeProgress = Math.max(0f, Math.min(1f, progress));
        if (safeProgress <= 0f) {
            return 0f;
        }
        float easedProgress = 1f - (float) Math.pow(1f - safeProgress, 1.18f);
        return 0.74f + 0.26f * easedProgress;
    }

    private float drawerPanelScaleY(float progress) {
        float safeProgress = Math.max(0f, Math.min(1f, progress));
        if (safeProgress <= 0f) {
            return 0.986f;
        }
        float easedProgress = 1f - (float) Math.pow(1f - safeProgress, 1.08f);
        return 0.986f + 0.014f * easedProgress;
    }

    private float drawerScrimAlpha(float progress) {
        float safeProgress = Math.max(0f, Math.min(1f, progress));
        if (safeProgress <= 0.04f) {
            return 0f;
        }
        float easedProgress = (safeProgress - 0.04f) / 0.96f;
        easedProgress = (float) Math.pow(easedProgress, 1.35f);
        return Math.min(0.92f, easedProgress);
    }

    private void handleRootLayoutChanged() {
        updateDrawerPanelWidth();
        syncDrawerOffsetToCurrentState();
        updateDrawerGestureExclusion();
    }

    private void updateDrawerPanelWidth() {
        if (drawerPanel == null) {
            return;
        }
        int containerWidth = rootView != null ? rootView.getWidth() : 0;
        if (containerWidth <= 0) {
            return;
        }
        int desiredWidth = clamp(
                Math.round(containerWidth * DRAWER_WIDTH_RATIO),
                dp(DRAWER_MIN_WIDTH_DP),
                dp(DRAWER_MAX_WIDTH_DP)
        );
        ViewGroup.LayoutParams layoutParams = drawerPanel.getLayoutParams();
        if (layoutParams == null || layoutParams.width == desiredWidth) {
            return;
        }
        layoutParams.width = desiredWidth;
        drawerPanel.setLayoutParams(layoutParams);
    }

    private void syncDrawerOffsetToCurrentState() {
        if (drawerPanel == null || drawerPanel.getWidth() == 0) {
            return;
        }
        if (drawerDragging) {
            setDrawerOffset(drawerPanel.getTranslationX());
            return;
        }
        setDrawerOffset(drawerOpen ? 0f : -drawerPanel.getWidth());
    }

    private void updateDrawerGestureExclusion() {
        if (rootView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        List<Rect> exclusionRects = new ArrayList<>();
        exclusionRects.add(new Rect(0, 0, Math.max(dp(EDGE_EXCLUSION_DP), closedDrawerActivationWidth()), rootView.getHeight()));
        rootView.setSystemGestureExclusionRects(exclusionRects);
    }
}
