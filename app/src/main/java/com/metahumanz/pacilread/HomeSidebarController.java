package com.metahumanz.pacilread;

import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

import java.util.ArrayList;
import java.util.List;

final class HomeSidebarController {
    private static final int OPEN_DRAWER_DRAG_EXTRA_DP = 72;
    private static final float OPEN_THRESHOLD_RATIO = 0.34f;
    private static final float CLOSE_THRESHOLD_RATIO = 0.22f;
    private static final float FLING_VELOCITY_THRESHOLD = 0.55f;
    private static final float SCRIM_MAX_ALPHA = 0.34f;

    interface Callback {
        void onSidebarPageSelected(int page, boolean animate);
    }

    private final Activity activity;
    private final SettingsStore settingsStore;
    private final HomeNavigationResolver resolver;
    private final Callback callback;
    private final LinearLayout fixedSidebar;
    private final View sidebarScrim;
    private final FrameLayout slideSidebarContainer;
    private final int touchSlop;
    private final List<Integer> activePages = new ArrayList<>();
    private final List<NavRow> navRows = new ArrayList<>();

    private int effectiveMode = HomeNavigationResolver.MODE_BOTTOM;
    private int currentPage = HomeNavigationController.PAGE_BOOKSHELF;
    private boolean drawerOpen = false;
    private boolean drawerCandidate = false;
    private boolean drawerDragging = false;
    private float drawerDownX = 0f;
    private float drawerDownY = 0f;
    private float drawerStartOffset = 0f;
    private float drawerLastX = 0f;
    private long drawerLastEventTime = 0L;
    private float drawerVelocityX = 0f;
    private boolean pendingChildTouchCancel = false;
    private boolean drawerGestureStartedOpen = false;
    private boolean drawerAnimating = false;
    private long drawerAnimationToken = 0L;

    HomeSidebarController(
            Activity activity,
            SettingsStore settingsStore,
            HomeNavigationResolver resolver,
            Callback callback
    ) {
        this.activity = activity;
        this.settingsStore = settingsStore;
        this.resolver = resolver;
        this.callback = callback;
        this.fixedSidebar = activity.findViewById(R.id.home_fixed_sidebar);
        this.sidebarScrim = activity.findViewById(R.id.home_sidebar_scrim);
        this.slideSidebarContainer = activity.findViewById(R.id.home_slide_sidebar_container);
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        setup();
    }

    void setActivePages(List<Integer> pages) {
        activePages.clear();
        activePages.addAll(pages);
    }

    void updateShell(int mode, boolean modeChanged) {
        effectiveMode = mode;
        if (fixedSidebar != null) {
            fixedSidebar.setVisibility(effectiveMode == HomeNavigationResolver.MODE_FIXED_SIDEBAR ? View.VISIBLE : View.GONE);
            int width = effectiveMode == HomeNavigationResolver.MODE_FIXED_SIDEBAR ? resolver.fixedSidebarWidth() : 0;
            ViewGroup.LayoutParams params = fixedSidebar.getLayoutParams();
            if (params != null && params.width != width) {
                params.width = width;
                fixedSidebar.setLayoutParams(params);
            }
        }
        if (modeChanged || effectiveMode != HomeNavigationResolver.MODE_SLIDE_SIDEBAR) {
            closeDrawerImmediate();
        } else {
            syncDrawerOffsetToCurrentState();
        }
    }

    void rebuild() {
        navRows.clear();
        buildFixedSidebar();
        buildSlideSidebar();
        updateSelection(currentPage);
    }

    void updateSelection(int page) {
        currentPage = page;
        for (NavRow row : navRows) {
            boolean selected = row.page == currentPage;
            row.container.setBackgroundResource(selected ? R.drawable.bg_nav_item_active : R.drawable.bg_nav_item_idle);
            int color = ThemeModeHelper.resolveColor(
                    activity,
                    selected ? R.color.app_nav_text_active : R.color.app_nav_text_idle
            );
            row.icon.setColorFilter(color);
            row.label.setTextColor(color);
        }
    }

    boolean isDrawerOpen() {
        return isDrawerVisible();
    }

    boolean onBackPressed() {
        if (!isDrawerVisible()) {
            return false;
        }
        closeDrawer();
        return true;
    }

    boolean consumePendingChildTouchCancel() {
        boolean shouldCancel = pendingChildTouchCancel;
        pendingChildTouchCancel = false;
        return shouldCancel;
    }

    boolean handleTouchEvent(MotionEvent event) {
        if (effectiveMode != HomeNavigationResolver.MODE_SLIDE_SIDEBAR) {
            return false;
        }
        return handleSidebarTouch(event);
    }

    void closeDrawer() {
        if (slideSidebarContainer == null || sidebarScrim == null) {
            return;
        }
        animateDrawerTo(-drawerWidth(), 240L);
    }

    private boolean isDrawerVisible() {
        return slideSidebarContainer != null
                && (drawerOpen || drawerDragging || slideSidebarContainer.getVisibility() == View.VISIBLE);
    }

    private void setup() {
        if (sidebarScrim != null) {
            sidebarScrim.setOnClickListener(v -> closeDrawer());
            sidebarScrim.setVisibility(View.GONE);
            sidebarScrim.setAlpha(0f);
        }
        if (slideSidebarContainer != null) {
            slideSidebarContainer.setTranslationX(-9999f);
            slideSidebarContainer.setVisibility(View.INVISIBLE);
            slideSidebarContainer.setAlpha(0f);
            slideSidebarContainer.setScaleY(0.986f);
            slideSidebarContainer.post(() -> {
                if (!drawerOpen) {
                    applyDrawerOffsetState(-drawerWidth(), false);
                }
            });
        }
    }

    private void buildFixedSidebar() {
        if (fixedSidebar == null) {
            return;
        }
        fixedSidebar.removeAllViews();
        fixedSidebar.setOrientation(LinearLayout.VERTICAL);
        fixedSidebar.setBackgroundResource(R.drawable.bg_sidebar_panel);
        fixedSidebar.setPadding(AppUiUtils.dp(activity, 10), AppUiUtils.dp(activity, 20), AppUiUtils.dp(activity, 10), AppUiUtils.dp(activity, 16));
        boolean iconOnly = "icons".equals(settingsStore.getHomeFixedSidebarStyle());
        addBrand(fixedSidebar, iconOnly);
        addNavigationRows(fixedSidebar, iconOnly, true);
    }

    private void buildSlideSidebar() {
        if (slideSidebarContainer == null) {
            return;
        }
        slideSidebarContainer.removeAllViews();
        int width = AppUiUtils.dp(activity, 320);
        ViewGroup.LayoutParams containerParams = slideSidebarContainer.getLayoutParams();
        if (containerParams != null && containerParams.width != width) {
            containerParams.width = width;
            slideSidebarContainer.setLayoutParams(containerParams);
        }
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_sidebar_panel);
        panel.setClickable(true);
        panel.setPadding(AppUiUtils.dp(activity, 16), AppUiUtils.dp(activity, 24), AppUiUtils.dp(activity, 16), AppUiUtils.dp(activity, 18));
        slideSidebarContainer.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        addBrand(panel, false);
        addNavigationRows(panel, false, false);
        if (!drawerOpen) {
            applyDrawerOffsetState(-drawerWidth(), false);
        }
    }

    private void addBrand(LinearLayout parent, boolean iconOnly) {
        LinearLayout brand = new LinearLayout(activity);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(iconOnly ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        brand.setPadding(0, 0, 0, AppUiUtils.dp(activity, 18));
        TextView mark = new TextView(activity);
        mark.setGravity(Gravity.CENTER);
        mark.setText("P");
        mark.setTextSize(18f);
        mark.setTypeface(null, android.graphics.Typeface.BOLD_ITALIC);
        mark.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_button_primary_text));
        mark.setBackgroundResource(R.drawable.bg_app_primary_button);
        brand.addView(mark, new LinearLayout.LayoutParams(AppUiUtils.dp(activity, 36), AppUiUtils.dp(activity, 36)));
        if (!iconOnly) {
            TextView label = new TextView(activity);
            label.setText("PacilRead");
            label.setTextSize(18f);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
            label.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            labelParams.setMargins(AppUiUtils.dp(activity, 12), 0, 0, 0);
            brand.addView(label, labelParams);
        }
        parent.addView(brand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addNavigationRows(LinearLayout parent, boolean iconOnly, boolean fixed) {
        for (int page : activePages) {
            if (page == HomeNavigationController.PAGE_SETTINGS) {
                continue;
            }
            parent.addView(createSidebarRow(page, iconOnly, fixed));
        }
        View spacer = new View(activity);
        parent.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        parent.addView(createSidebarRow(HomeNavigationController.PAGE_SETTINGS, iconOnly, fixed));
    }

    private View createSidebarRow(int page, boolean iconOnly, boolean fixed) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(iconOnly ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(resolver.labelForPage(page));
        row.setTooltipText(resolver.labelForPage(page));
        row.setPadding(iconOnly ? 0 : AppUiUtils.dp(activity, 10), AppUiUtils.dp(activity, 12), iconOnly ? 0 : AppUiUtils.dp(activity, 12), AppUiUtils.dp(activity, 12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                iconOnly ? AppUiUtils.dp(activity, 48) : LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, AppUiUtils.dp(activity, 4), 0, 0);
        row.setLayoutParams(rowParams);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(resolver.iconResForPage(page));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(
                iconOnly ? LinearLayout.LayoutParams.MATCH_PARENT : AppUiUtils.dp(activity, 28),
                AppUiUtils.dp(activity, 24)
        ));

        TextView label = new TextView(activity);
        label.setText(resolver.labelForPage(page));
        label.setTextSize(15f);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setVisibility(iconOnly ? View.GONE : View.VISIBLE);
        if (!iconOnly) {
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            labelParams.setMargins(AppUiUtils.dp(activity, 12), 0, 0, 0);
            row.addView(label, labelParams);
        }
        row.setOnClickListener(v -> callback.onSidebarPageSelected(page, !fixed));
        navRows.add(new NavRow(page, row, icon, label));
        return row;
    }

    private boolean handleSidebarTouch(MotionEvent event) {
        if (slideSidebarContainer == null) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pendingChildTouchCancel = false;
                drawerCandidate = shouldStartDrawerGesture(x);
                drawerDragging = false;
                drawerVelocityX = 0f;
                drawerGestureStartedOpen = isDrawerVisible();
                if (drawerCandidate) {
                    drawerDownX = x;
                    drawerDownY = y;
                    drawerLastX = x;
                    drawerLastEventTime = event.getEventTime();
                    drawerStartOffset = currentDrawerOffset();
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!drawerCandidate) {
                    return false;
                }
                float deltaX = x - drawerDownX;
                float deltaY = y - drawerDownY;
                if (!drawerDragging) {
                    int horizontalThreshold = isDrawerVisible() ? touchSlop : Math.max(4, touchSlop / 2);
                    if (Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        resetDrawerGesture();
                        return false;
                    }
                    if (Math.abs(deltaX) <= horizontalThreshold || Math.abs(deltaX) <= Math.abs(deltaY)) {
                        return false;
                    }
                    if (!isDrawerVisible() && deltaX < 0f) {
                        resetDrawerGesture();
                        return false;
                    }
                    drawerDragging = true;
                    pendingChildTouchCancel = true;
                    prepareDrawerForInteraction();
                }
                updateDrawerOffset(drawerStartOffset + deltaX);
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
                if (drawerCandidate && isDrawerVisible() && x > drawerWidth()) {
                    closeDrawer();
                    resetDrawerGesture();
                    return true;
                }
                resetDrawerGesture();
                return false;
            default:
                return false;
        }
    }

    private void prepareDrawerForInteraction() {
        if (slideSidebarContainer == null || sidebarScrim == null) {
            return;
        }
        drawerAnimationToken++;
        drawerAnimating = false;
        slideSidebarContainer.animate().cancel();
        sidebarScrim.animate().cancel();
        sidebarScrim.bringToFront();
        slideSidebarContainer.bringToFront();
        applyDrawerOffsetState(currentDrawerOffset(), true);
    }

    private void finishDrawerGesture() {
        if (slideSidebarContainer == null) {
            resetDrawerGesture();
            return;
        }
        int width = drawerWidth();
        float offset = slideSidebarContainer.getTranslationX();
        boolean shouldOpen;
        if (drawerGestureStartedOpen) {
            shouldOpen = !(offset <= -width * CLOSE_THRESHOLD_RATIO
                    || drawerVelocityX < -FLING_VELOCITY_THRESHOLD);
            if (drawerVelocityX > FLING_VELOCITY_THRESHOLD) {
                shouldOpen = true;
            }
        } else {
            shouldOpen = offset > -width * (1f - OPEN_THRESHOLD_RATIO)
                    || drawerVelocityX > FLING_VELOCITY_THRESHOLD;
            if (drawerVelocityX < -FLING_VELOCITY_THRESHOLD) {
                shouldOpen = false;
            }
        }
        resetDrawerGesture();
        if (shouldOpen) {
            openDrawer();
        } else {
            closeDrawer();
        }
    }

    private void resetDrawerGesture() {
        drawerCandidate = false;
        drawerDragging = false;
        drawerVelocityX = 0f;
        drawerGestureStartedOpen = false;
    }

    void openDrawer() {
        if (effectiveMode != HomeNavigationResolver.MODE_SLIDE_SIDEBAR || slideSidebarContainer == null || sidebarScrim == null) {
            return;
        }
        if (slideSidebarContainer.getWidth() == 0) {
            slideSidebarContainer.post(this::openDrawer);
            return;
        }
        animateDrawerTo(0f, 320L);
    }

    private void closeDrawerImmediate() {
        drawerOpen = false;
        drawerAnimating = false;
        drawerAnimationToken++;
        resetDrawerGesture();
        if (slideSidebarContainer != null) {
            slideSidebarContainer.animate().cancel();
            applyDrawerOffsetState(-drawerWidth(), false);
        }
        if (sidebarScrim != null) {
            sidebarScrim.animate().cancel();
            sidebarScrim.setVisibility(View.GONE);
            sidebarScrim.setAlpha(0f);
        }
    }

    private void updateDrawerOffset(float offset) {
        if (slideSidebarContainer == null || sidebarScrim == null) {
            return;
        }
        int width = drawerWidth();
        float clamped = Math.max(-width, Math.min(0f, offset));
        applyDrawerOffsetState(clamped, false);
    }

    private void animateDrawerTo(float targetOffset, long durationMs) {
        if (slideSidebarContainer == null || sidebarScrim == null || drawerWidth() == 0) {
            return;
        }
        slideSidebarContainer.animate().cancel();
        sidebarScrim.animate().cancel();

        final long animationToken = ++drawerAnimationToken;
        drawerAnimating = true;
        int width = drawerWidth();
        float startOffset = Math.max(-width, Math.min(0f, currentDrawerOffset()));
        float remainingRatio = Math.abs(targetOffset - startOffset) / Math.max(width, 1f);
        if (remainingRatio <= 0.015f) {
            drawerAnimating = false;
            applyDrawerOffsetState(targetOffset, false);
            return;
        }

        boolean opening = targetOffset == 0f;
        Interpolator interpolator = opening
                ? new DecelerateInterpolator(1.3f)
                : new AccelerateInterpolator(1.2f);
        float targetProgress = drawerProgressForOffset(targetOffset);
        float targetScrimAlpha = drawerScrimAlpha(targetProgress);
        float targetPanelAlpha = drawerPanelAlpha(targetProgress);
        float targetPanelScaleY = drawerPanelScaleY(targetProgress);

        slideSidebarContainer.setVisibility(View.VISIBLE);
        if (sidebarScrim.getVisibility() != View.VISIBLE) {
            sidebarScrim.setVisibility(View.VISIBLE);
            sidebarScrim.setAlpha(drawerScrimAlpha(drawerProgressForOffset(startOffset)));
        }
        sidebarScrim.bringToFront();
        slideSidebarContainer.bringToFront();

        slideSidebarContainer.animate()
                .translationX(targetOffset)
                .alpha(targetPanelAlpha)
                .scaleY(targetPanelScaleY)
                .setDuration(durationMs)
                .setInterpolator(interpolator)
                .withEndAction(() -> {
                    if (animationToken != drawerAnimationToken) {
                        return;
                    }
                    drawerAnimating = false;
                    applyDrawerOffsetState(targetOffset, false);
                })
                .start();
        sidebarScrim.animate()
                .alpha(targetScrimAlpha)
                .setDuration(durationMs)
                .setInterpolator(interpolator)
                .withEndAction(() -> {
                    if (animationToken != drawerAnimationToken) {
                        return;
                    }
                    if (targetScrimAlpha <= 0.01f) {
                        sidebarScrim.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private boolean shouldStartDrawerGesture(float x) {
        if (!isDrawerVisible()) {
            return true;
        }
        return x <= drawerWidth() + AppUiUtils.dp(activity, OPEN_DRAWER_DRAG_EXTRA_DP);
    }

    private float currentDrawerOffset() {
        if (slideSidebarContainer == null) {
            return -drawerWidth();
        }
        float offset = slideSidebarContainer.getTranslationX();
        if (offset < -drawerWidth() || offset > 0f) {
            return drawerOpen ? 0f : -drawerWidth();
        }
        return offset;
    }

    private void applyDrawerOffsetState(float offset, boolean keepVisible) {
        if (slideSidebarContainer == null || sidebarScrim == null) {
            return;
        }
        int width = drawerWidth();
        float clamped = Math.max(-width, Math.min(0f, offset));
        float progress = drawerProgressForOffset(clamped);
        boolean showPanel = keepVisible || progress > 0.001f;
        float scrimAlpha = drawerScrimAlpha(progress);
        boolean showScrim = keepVisible || scrimAlpha > 0f;
        slideSidebarContainer.setPivotX(0f);
        slideSidebarContainer.setPivotY(slideSidebarContainer.getHeight() * 0.5f);
        slideSidebarContainer.setTranslationX(clamped);
        slideSidebarContainer.setVisibility(showPanel ? View.VISIBLE : View.INVISIBLE);
        slideSidebarContainer.setAlpha(showPanel ? drawerPanelAlpha(progress) : 0f);
        slideSidebarContainer.setScaleY(drawerPanelScaleY(progress));
        if (showScrim) {
            sidebarScrim.setVisibility(View.VISIBLE);
            sidebarScrim.setAlpha(scrimAlpha);
        } else {
            sidebarScrim.setVisibility(View.GONE);
            sidebarScrim.setAlpha(0f);
        }
        drawerOpen = progress > 0.95f;
    }

    private float drawerProgressForOffset(float offset) {
        int width = drawerWidth();
        float clamped = Math.max(-width, Math.min(0f, offset));
        return 1f - (-clamped / Math.max(width, 1f));
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
        return Math.min(SCRIM_MAX_ALPHA, SCRIM_MAX_ALPHA * easedProgress);
    }

    private int drawerWidth() {
        if (slideSidebarContainer == null) {
            return AppUiUtils.dp(activity, 320);
        }
        if (slideSidebarContainer.getWidth() > 0) {
            return slideSidebarContainer.getWidth();
        }
        ViewGroup.LayoutParams params = slideSidebarContainer.getLayoutParams();
        if (params != null && params.width > 0) {
            return params.width;
        }
        return AppUiUtils.dp(activity, 320);
    }

    private void syncDrawerOffsetToCurrentState() {
        if (slideSidebarContainer == null || drawerDragging || drawerAnimating) {
            return;
        }
        if (effectiveMode != HomeNavigationResolver.MODE_SLIDE_SIDEBAR) {
            return;
        }
        applyDrawerOffsetState(drawerOpen ? 0f : -drawerWidth(), false);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class NavRow {
        final int page;
        final LinearLayout container;
        final ImageView icon;
        final TextView label;

        NavRow(int page, LinearLayout container, ImageView icon, TextView label) {
            this.page = page;
            this.container = container;
            this.icon = icon;
            this.label = label;
        }
    }
}
