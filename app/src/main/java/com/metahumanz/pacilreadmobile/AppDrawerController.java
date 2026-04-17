package com.metahumanz.pacilread;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class AppDrawerController {
    private static final int EDGE_ACTIVATION_DP = 88;
    private static final int EDGE_EXCLUSION_DP = 88;
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
    private final TextView navBookshelfTitle;
    private final TextView navBookshelfSubtitle;
    private final TextView navPreviewTitle;
    private final TextView navPreviewSubtitle;
    private final TextView navSettingsTitle;
    private final TextView navSettingsSubtitle;
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

    public AppDrawerController(Activity activity, View rootView, NavigationListener navigationListener) {
        this.activity = activity;
        this.rootView = rootView;
        this.navigationListener = navigationListener;
        this.drawerPanel = activity.findViewById(R.id.drawer_panel);
        this.drawerScrim = activity.findViewById(R.id.drawer_scrim);
        this.navBookshelf = activity.findViewById(R.id.nav_bookshelf);
        this.navPreview = activity.findViewById(R.id.nav_preview);
        this.navSettings = activity.findViewById(R.id.nav_settings);
        this.navBookshelfTitle = activity.findViewById(R.id.text_nav_bookshelf_title);
        this.navBookshelfSubtitle = activity.findViewById(R.id.text_nav_bookshelf_subtitle);
        this.navPreviewTitle = activity.findViewById(R.id.text_nav_preview_title);
        this.navPreviewSubtitle = activity.findViewById(R.id.text_nav_preview_subtitle);
        this.navSettingsTitle = activity.findViewById(R.id.text_nav_settings_title);
        this.navSettingsSubtitle = activity.findViewById(R.id.text_nav_settings_subtitle);
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
            setDrawerOffset(-drawerPanel.getWidth());
            drawerPanel.setVisibility(View.INVISIBLE);
            drawerScrim.setVisibility(View.GONE);
            drawerScrim.setAlpha(0f);
        });
        if (rootView != null) {
            rootView.post(this::updateDrawerGestureExclusion);
            rootView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    updateDrawerGestureExclusion()
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
                drawerGestureCandidate = shouldStartDrawerGesture(x);
                drawerDragging = false;
                drawerVelocityX = 0f;
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
                    if (Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        drawerGestureCandidate = false;
                        return false;
                    }
                    if (Math.abs(deltaX) <= touchSlop || Math.abs(deltaX) <= Math.abs(deltaY)) {
                        return false;
                    }
                    drawerDragging = true;
                    prepareDrawerForGesture();
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
                    return true;
                }
                drawerGestureCandidate = false;
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
        animateDrawerTo(0f, 220L);
    }

    public void closeDrawer() {
        if (drawerPanel == null || drawerPanel.getWidth() == 0) {
            return;
        }
        drawerOpen = false;
        animateDrawerTo(-drawerPanel.getWidth(), 180L);
    }

    public boolean isDrawerVisible() {
        return drawerPanel != null && (drawerPanel.getVisibility() == View.VISIBLE || drawerDragging);
    }

    private void updateNavigationState() {
        styleDrawerItem(navBookshelf, navBookshelfTitle, navBookshelfSubtitle, currentSection == SECTION_BOOKSHELF);
        styleDrawerItem(navPreview, navPreviewTitle, navPreviewSubtitle, currentSection == SECTION_PREVIEW);
        styleDrawerItem(navSettings, navSettingsTitle, navSettingsSubtitle, currentSection == SECTION_SETTINGS);
    }

    private void styleDrawerItem(View container, TextView titleView, TextView subtitleView, boolean selected) {
        if (container == null || titleView == null || subtitleView == null) {
            return;
        }
        container.setBackgroundResource(selected ? R.drawable.bg_sidebar_nav_active : R.drawable.bg_sidebar_nav_idle);
        int titleColor = activity.getColor(selected ? android.R.color.white : R.color.on_surface);
        int subtitleColor = activity.getColor(selected ? android.R.color.white : R.color.on_surface_muted);
        titleView.setTextColor(titleColor);
        subtitleView.setTextColor(subtitleColor);
    }

    private boolean shouldStartDrawerGesture(float x) {
        if (!isDrawerVisible()) {
            return x <= dp(EDGE_ACTIVATION_DP);
        }
        float panelWidth = drawerPanel.getWidth();
        return x <= panelWidth + dp(36);
    }

    private void finishDrawerGesture() {
        float offset = drawerPanel.getTranslationX();
        float openThreshold = -drawerPanel.getWidth() * 0.45f;
        boolean shouldOpen = offset > openThreshold || drawerVelocityX > 0.8f;
        if (drawerVelocityX < -0.8f) {
            shouldOpen = false;
        }
        drawerGestureCandidate = false;
        drawerDragging = false;
        if (shouldOpen) {
            openDrawer();
        } else {
            closeDrawer();
        }
    }

    private void animateDrawerTo(float targetOffset, long durationMs) {
        prepareDrawerForGesture();
        drawerPanel.animate().cancel();
        drawerScrim.animate().cancel();
        float progress = 1f - (-targetOffset / drawerPanel.getWidth());
        drawerPanel.animate()
                .translationX(targetOffset)
                .setDuration(durationMs)
                .withEndAction(() -> {
                    if (targetOffset <= -drawerPanel.getWidth()) {
                        drawerPanel.setVisibility(View.INVISIBLE);
                    }
                })
                .start();
        drawerScrim.animate()
                .alpha(progress)
                .setDuration(durationMs)
                .withEndAction(() -> {
                    if (progress <= 0f) {
                        drawerScrim.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void prepareDrawerForGesture() {
        drawerPanel.setVisibility(View.VISIBLE);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.animate().cancel();
        drawerScrim.animate().cancel();
    }

    private void setDrawerOffset(float offset) {
        float clamped = clamp(offset, -drawerPanel.getWidth(), 0f);
        float progress = 1f - (-clamped / drawerPanel.getWidth());
        drawerPanel.setVisibility(progress <= 0f ? View.INVISIBLE : View.VISIBLE);
        drawerPanel.setTranslationX(clamped);
        drawerScrim.setVisibility(progress <= 0f ? View.GONE : View.VISIBLE);
        drawerScrim.setAlpha(progress);
        drawerOpen = progress > 0.95f;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private void updateDrawerGestureExclusion() {
        if (rootView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        List<Rect> exclusionRects = new ArrayList<>();
        exclusionRects.add(new Rect(0, 0, dp(EDGE_EXCLUSION_DP), rootView.getHeight()));
        rootView.setSystemGestureExclusionRects(exclusionRects);
    }
}
