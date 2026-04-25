package com.metahumanz.pacilread;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

import java.util.ArrayList;
import java.util.List;

public final class HomeNavigationController {
    public static final int PAGE_BOOKSHELF = 0;
    public static final int PAGE_STATS = 1;
    public static final int PAGE_BOOKMARKS = 2;
    public static final int PAGE_SETTINGS = 3;

    private static final int MODE_BOTTOM = 0;
    private static final int MODE_SLIDE_SIDEBAR = 1;
    private static final int MODE_FIXED_SIDEBAR = 2;
    private static final int SWIPE_NONE = 0;

    public interface Callback {
        boolean isReadingTimeTrackingEnabled();
        void onHomePageSelected(int page, boolean syncFirst);
    }

    private final Activity activity;
    private final SettingsStore settingsStore;
    private final Callback callback;
    private final List<Integer> activePages = new ArrayList<>();
    private final List<NavRow> navRows = new ArrayList<>();

    private final View rootView;
    private final View pageContainer;
    private final View sectionBookshelf;
    private final View sectionReadingStats;
    private final View sectionBookmarks;
    private final View sectionHomeSettings;
    private final View bottomNavigation;
    private final TextView bottomBookshelf;
    private final TextView bottomStats;
    private final TextView bottomBookmarks;
    private final TextView bottomSettings;
    private final LinearLayout fixedSidebar;
    private final View sidebarScrim;
    private final FrameLayout slideSidebarContainer;
    private final View loadingLayout;
    private final int touchSlop;

    private int currentPage = PAGE_BOOKSHELF;
    private int effectiveMode = MODE_BOTTOM;
    private int pendingSwipePage = -1;
    private int pendingSwipeDirection = SWIPE_NONE;
    private float swipeDownX = 0f;
    private float swipeDownY = 0f;
    private float swipeLastX = 0f;
    private long swipeLastEventTime = 0L;
    private float swipeVelocityX = 0f;
    private boolean pageSwipeCandidate = false;
    private boolean pageSwipeDragging = false;

    private boolean drawerOpen = false;
    private boolean drawerCandidate = false;
    private boolean drawerDragging = false;
    private float drawerDownX = 0f;
    private float drawerDownY = 0f;
    private float drawerStartOffset = 0f;
    private float drawerLastX = 0f;
    private long drawerLastEventTime = 0L;
    private float drawerVelocityX = 0f;

    public HomeNavigationController(Activity activity, SettingsStore settingsStore, Callback callback) {
        this.activity = activity;
        this.settingsStore = settingsStore;
        this.callback = callback;
        this.rootView = activity.findViewById(R.id.main_root);
        this.pageContainer = activity.findViewById(R.id.page_container);
        this.sectionBookshelf = activity.findViewById(R.id.section_bookshelf);
        this.sectionReadingStats = activity.findViewById(R.id.section_reading_stats);
        this.sectionBookmarks = activity.findViewById(R.id.section_bookmarks);
        this.sectionHomeSettings = activity.findViewById(R.id.section_home_settings);
        this.bottomNavigation = activity.findViewById(R.id.bottom_navigation);
        this.bottomBookshelf = activity.findViewById(R.id.nav_home_bookshelf);
        this.bottomStats = activity.findViewById(R.id.nav_home_stats);
        this.bottomBookmarks = activity.findViewById(R.id.nav_home_bookmarks);
        this.bottomSettings = activity.findViewById(R.id.nav_home_settings);
        this.fixedSidebar = activity.findViewById(R.id.home_fixed_sidebar);
        this.sidebarScrim = activity.findViewById(R.id.home_sidebar_scrim);
        this.slideSidebarContainer = activity.findViewById(R.id.home_slide_sidebar_container);
        this.loadingLayout = activity.findViewById(R.id.layout_loading);
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        setup();
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void refreshFromSettings() {
        buildActivePages();
        int previousMode = effectiveMode;
        effectiveMode = resolveEffectiveMode();
        if (!activePages.contains(currentPage)) {
            currentPage = PAGE_BOOKSHELF;
        }
        updateNavigationShell(previousMode != effectiveMode);
        updateBottomNavLabels();
        rebuildSidebars();
        showHomePageImmediate(currentPage);
    }

    public boolean onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
            return true;
        }
        if (currentPage != PAGE_BOOKSHELF) {
            selectHomePage(PAGE_BOOKSHELF, true, false);
            return true;
        }
        return false;
    }

    public boolean handleTouchEvent(MotionEvent event) {
        if (effectiveMode == MODE_SLIDE_SIDEBAR && handleSidebarTouch(event)) {
            return true;
        }
        if (effectiveMode == MODE_BOTTOM && handlePageSwipe(event)) {
            return true;
        }
        return false;
    }

    private void setup() {
        bindBottomClick(bottomBookshelf, PAGE_BOOKSHELF);
        bindBottomClick(bottomStats, PAGE_STATS);
        bindBottomClick(bottomBookmarks, PAGE_BOOKMARKS);
        bindBottomClick(bottomSettings, PAGE_SETTINGS);
        if (sidebarScrim != null) {
            sidebarScrim.setOnClickListener(v -> closeDrawer());
        }
        if (rootView != null) {
            rootView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    refreshFromSettings();
                }
            });
        }
        if (slideSidebarContainer != null) {
            slideSidebarContainer.setTranslationX(-dp(320));
            slideSidebarContainer.setVisibility(View.INVISIBLE);
        }
        if (sidebarScrim != null) {
            sidebarScrim.setVisibility(View.GONE);
            sidebarScrim.setAlpha(0f);
        }
        refreshFromSettings();
    }

    private void bindBottomClick(View view, int page) {
        if (view != null) {
            view.setOnClickListener(v -> selectHomePage(page, true, false));
        }
    }

    private void buildActivePages() {
        activePages.clear();
        activePages.add(PAGE_BOOKSHELF);
        if (callback.isReadingTimeTrackingEnabled()) {
            activePages.add(PAGE_STATS);
            if (bottomStats != null) {
                bottomStats.setVisibility(View.VISIBLE);
            }
        } else if (bottomStats != null) {
            bottomStats.setVisibility(View.GONE);
        }
        activePages.add(PAGE_BOOKMARKS);
        activePages.add(PAGE_SETTINGS);
    }

    private int resolveEffectiveMode() {
        Configuration configuration = activity.getResources().getConfiguration();
        boolean landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean wide = isWide(configuration);
        String requested = landscape
                ? settingsStore.getLandscapeHomeNavigationMode()
                : settingsStore.getPortraitHomeNavigationMode();
        boolean sidebar = "sidebar".equals(requested)
                || ("auto".equals(requested) && (landscape || wide));
        if (!sidebar) {
            return MODE_BOTTOM;
        }
        boolean canFix = landscape || wide;
        boolean fixed = canFix && "fixed_wide".equals(settingsStore.getHomeSidebarPresentation());
        return fixed ? MODE_FIXED_SIDEBAR : MODE_SLIDE_SIDEBAR;
    }

    private boolean isWide(Configuration configuration) {
        return configuration.smallestScreenWidthDp >= 600
                || configuration.screenWidthDp >= 600
                || (rootView != null && rootView.getWidth() >= dp(640));
    }

    private void updateNavigationShell(boolean modeChanged) {
        if (bottomNavigation != null) {
            bottomNavigation.setVisibility(effectiveMode == MODE_BOTTOM ? View.VISIBLE : View.GONE);
        }
        if (fixedSidebar != null) {
            fixedSidebar.setVisibility(effectiveMode == MODE_FIXED_SIDEBAR ? View.VISIBLE : View.GONE);
            int width = effectiveMode == MODE_FIXED_SIDEBAR ? fixedSidebarWidth() : 0;
            ViewGroup.LayoutParams params = fixedSidebar.getLayoutParams();
            if (params != null && params.width != width) {
                params.width = width;
                fixedSidebar.setLayoutParams(params);
            }
        }
        if (modeChanged || effectiveMode != MODE_SLIDE_SIDEBAR) {
            closeDrawerImmediate();
        }
    }

    private void selectHomePage(int page, boolean animate, boolean syncFirst) {
        if (!activePages.contains(page)) {
            return;
        }
        if (drawerOpen) {
            closeDrawer();
        }
        if (page == currentPage) {
            callback.onHomePageSelected(page, syncFirst);
            return;
        }
        int oldPage = currentPage;
        currentPage = page;
        if (effectiveMode == MODE_BOTTOM && animate && pageContainer != null && pageContainer.getWidth() > 0) {
            animateHomePageTransition(oldPage, page);
        } else {
            showHomePageImmediate(page);
        }
        callback.onHomePageSelected(page, syncFirst);
    }

    private void showHomePageImmediate(int page) {
        for (int candidate : new int[]{PAGE_BOOKSHELF, PAGE_STATS, PAGE_BOOKMARKS, PAGE_SETTINGS}) {
            View section = sectionForPage(candidate);
            if (section == null) {
                continue;
            }
            section.animate().cancel();
            section.setTranslationX(0f);
            section.setVisibility(candidate == page ? View.VISIBLE : View.GONE);
        }
        updateNavigationSelection();
    }

    private void animateHomePageTransition(int oldPage, int newPage) {
        View oldSection = sectionForPage(oldPage);
        View newSection = sectionForPage(newPage);
        if (oldSection == null || newSection == null || pageContainer == null) {
            showHomePageImmediate(newPage);
            return;
        }
        int width = Math.max(pageContainer.getWidth(), 1);
        int direction = activePages.indexOf(newPage) > activePages.indexOf(oldPage) ? 1 : -1;
        oldSection.animate().cancel();
        newSection.animate().cancel();
        newSection.setVisibility(View.VISIBLE);
        newSection.setTranslationX(direction * width);
        oldSection.animate()
                .translationX(-direction * width)
                .setDuration(220L)
                .withEndAction(() -> {
                    if (oldPage != currentPage) {
                        oldSection.setVisibility(View.GONE);
                        oldSection.setTranslationX(0f);
                    }
                })
                .start();
        newSection.animate()
                .translationX(0f)
                .setDuration(220L)
                .withEndAction(this::updateNavigationSelection)
                .start();
        updateNavigationSelection();
    }

    private void updateBottomNavLabels() {
        boolean textMode = "text".equals(settingsStore.getHomeBottomNavStyle());
        setBottomNavText(bottomBookshelf, textMode ? "书架" : "▦", textMode);
        setBottomNavText(bottomStats, textMode ? "时长" : "◷", textMode);
        setBottomNavText(bottomBookmarks, textMode ? "书签" : "★", textMode);
        setBottomNavText(bottomSettings, textMode ? "设置" : "⚙", textMode);
        updateNavigationSelection();
    }

    private void setBottomNavText(TextView item, String text, boolean textMode) {
        if (item == null) {
            return;
        }
        item.setText(text);
        item.setTextSize(textMode ? 15f : 22f);
    }

    private void rebuildSidebars() {
        navRows.clear();
        buildFixedSidebar();
        buildSlideSidebar();
        updateNavigationSelection();
    }

    private void buildFixedSidebar() {
        if (fixedSidebar == null) {
            return;
        }
        fixedSidebar.removeAllViews();
        fixedSidebar.setOrientation(LinearLayout.VERTICAL);
        fixedSidebar.setBackgroundResource(R.drawable.bg_sidebar_panel);
        fixedSidebar.setPadding(dp(10), dp(20), dp(10), dp(16));
        boolean iconOnly = "icons".equals(settingsStore.getHomeFixedSidebarStyle());
        addBrand(fixedSidebar, iconOnly);
        addNavigationRows(fixedSidebar, iconOnly, true);
    }

    private void buildSlideSidebar() {
        if (slideSidebarContainer == null) {
            return;
        }
        slideSidebarContainer.removeAllViews();
        int width = dp(320);
        ViewGroup.LayoutParams containerParams = slideSidebarContainer.getLayoutParams();
        if (containerParams != null && containerParams.width != width) {
            containerParams.width = width;
            slideSidebarContainer.setLayoutParams(containerParams);
        }
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_sidebar_panel);
        panel.setClickable(true);
        panel.setPadding(dp(16), dp(24), dp(16), dp(18));
        slideSidebarContainer.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        addBrand(panel, false);
        addNavigationRows(panel, false, false);
        if (!drawerOpen) {
            slideSidebarContainer.setTranslationX(-width);
        }
    }

    private void addBrand(LinearLayout parent, boolean iconOnly) {
        LinearLayout brand = new LinearLayout(activity);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(iconOnly ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        brand.setPadding(0, 0, 0, dp(18));
        TextView mark = new TextView(activity);
        mark.setGravity(Gravity.CENTER);
        mark.setText("P");
        mark.setTextSize(18f);
        mark.setTypeface(null, android.graphics.Typeface.BOLD_ITALIC);
        mark.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_button_primary_text));
        mark.setBackgroundResource(R.drawable.bg_app_primary_button);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
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
            labelParams.setMargins(dp(12), 0, 0, 0);
            brand.addView(label, labelParams);
        }
        parent.addView(brand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
    }

    private void addNavigationRows(LinearLayout parent, boolean iconOnly, boolean fixed) {
        for (int page : activePages) {
            if (page == PAGE_SETTINGS) {
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
        parent.addView(createSidebarRow(PAGE_SETTINGS, iconOnly, fixed));
    }

    private View createSidebarRow(int page, boolean iconOnly, boolean fixed) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(iconOnly ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(labelForPage(page));
        row.setTooltipText(labelForPage(page));
        row.setPadding(iconOnly ? 0 : dp(10), dp(12), iconOnly ? 0 : dp(12), dp(12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                iconOnly ? dp(48) : LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, dp(4), 0, 0);
        row.setLayoutParams(rowParams);

        TextView icon = new TextView(activity);
        icon.setText(iconForPage(page));
        icon.setTextSize(18f);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(iconOnly ? LinearLayout.LayoutParams.MATCH_PARENT : dp(28), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(activity);
        label.setText(labelForPage(page));
        label.setTextSize(15f);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setVisibility(iconOnly ? View.GONE : View.VISIBLE);
        if (!iconOnly) {
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            labelParams.setMargins(dp(12), 0, 0, 0);
            row.addView(label, labelParams);
        }
        row.setOnClickListener(v -> selectHomePage(page, fixed ? false : true, false));
        navRows.add(new NavRow(page, row, icon, label));
        return row;
    }

    private void updateNavigationSelection() {
        styleBottomItem(bottomBookshelf, currentPage == PAGE_BOOKSHELF);
        styleBottomItem(bottomStats, currentPage == PAGE_STATS);
        styleBottomItem(bottomBookmarks, currentPage == PAGE_BOOKMARKS);
        styleBottomItem(bottomSettings, currentPage == PAGE_SETTINGS);
        for (NavRow row : navRows) {
            boolean selected = row.page == currentPage;
            row.container.setBackgroundResource(selected ? R.drawable.bg_nav_item_active : R.drawable.bg_nav_item_idle);
            int color = ThemeModeHelper.resolveColor(
                    activity,
                    selected ? R.color.app_nav_text_active : R.color.app_nav_text_idle
            );
            row.icon.setTextColor(color);
            row.label.setTextColor(color);
        }
    }

    private void styleBottomItem(TextView item, boolean selected) {
        if (item == null) {
            return;
        }
        item.setBackgroundResource(selected ? R.drawable.bg_nav_item_active : R.drawable.bg_nav_item_idle);
        item.setTextColor(ThemeModeHelper.resolveColor(
                activity,
                selected ? R.color.app_nav_text_active : R.color.app_nav_text_idle
        ));
    }

    private View sectionForPage(int page) {
        if (page == PAGE_STATS) return sectionReadingStats;
        if (page == PAGE_BOOKMARKS) return sectionBookmarks;
        if (page == PAGE_SETTINGS) return sectionHomeSettings;
        return sectionBookshelf;
    }

    private boolean handlePageSwipe(MotionEvent event) {
        if (activePages.size() <= 1 || pageContainer == null || loadingLayout == null) {
            return false;
        }
        if (loadingLayout.getVisibility() == View.VISIBLE || isTouchInBottomNavigation(event)) {
            resetPageSwipe();
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pageSwipeCandidate = true;
                pageSwipeDragging = false;
                swipeDownX = x;
                swipeDownY = y;
                swipeLastX = x;
                swipeLastEventTime = event.getEventTime();
                swipeVelocityX = 0f;
                pendingSwipePage = -1;
                pendingSwipeDirection = SWIPE_NONE;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!pageSwipeCandidate) {
                    return false;
                }
                float deltaX = x - swipeDownX;
                float deltaY = y - swipeDownY;
                if (!pageSwipeDragging) {
                    if (Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        resetPageSwipe();
                        return false;
                    }
                    if (Math.abs(deltaX) <= touchSlop * 1.5f || Math.abs(deltaX) <= Math.abs(deltaY) * 1.2f) {
                        return false;
                    }
                    int targetPage = pageForSwipeDelta(deltaX);
                    if (targetPage < 0) {
                        resetPageSwipe();
                        return false;
                    }
                    pageSwipeDragging = true;
                    pendingSwipePage = targetPage;
                    pendingSwipeDirection = deltaX < 0 ? 1 : -1;
                    preparePageSwipe();
                    MotionEvent cancelEvent = MotionEvent.obtain(event);
                    cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
                    activity.getWindow().getDecorView().dispatchTouchEvent(cancelEvent);
                    cancelEvent.recycle();
                }
                updatePageSwipe(deltaX);
                long now = event.getEventTime();
                long elapsed = Math.max(1L, now - swipeLastEventTime);
                swipeVelocityX = (x - swipeLastX) / elapsed;
                swipeLastX = x;
                swipeLastEventTime = now;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pageSwipeDragging) {
                    finishPageSwipe(x - swipeDownX);
                    return true;
                }
                resetPageSwipe();
                return false;
            default:
                return false;
        }
    }

    private boolean isTouchInBottomNavigation(MotionEvent event) {
        return bottomNavigation != null
                && bottomNavigation.getVisibility() == View.VISIBLE
                && event.getY() >= bottomNavigation.getTop();
    }

    private int pageForSwipeDelta(float deltaX) {
        int currentIndex = activePages.indexOf(currentPage);
        int targetIndex = deltaX < 0 ? currentIndex + 1 : currentIndex - 1;
        if (targetIndex < 0 || targetIndex >= activePages.size()) {
            return -1;
        }
        return activePages.get(targetIndex);
    }

    private void preparePageSwipe() {
        View current = sectionForPage(currentPage);
        View target = sectionForPage(pendingSwipePage);
        if (current == null || target == null || pageContainer == null) {
            return;
        }
        current.animate().cancel();
        target.animate().cancel();
        current.setVisibility(View.VISIBLE);
        target.setVisibility(View.VISIBLE);
        target.setTranslationX(pendingSwipeDirection * Math.max(pageContainer.getWidth(), 1));
    }

    private void updatePageSwipe(float deltaX) {
        View current = sectionForPage(currentPage);
        View target = sectionForPage(pendingSwipePage);
        if (current == null || target == null || pageContainer == null) {
            return;
        }
        int width = Math.max(pageContainer.getWidth(), 1);
        float clamped = Math.max(-width, Math.min(width, deltaX));
        current.setTranslationX(clamped);
        target.setTranslationX(pendingSwipeDirection * width + clamped);
    }

    private void finishPageSwipe(float deltaX) {
        View current = sectionForPage(currentPage);
        View target = sectionForPage(pendingSwipePage);
        if (current == null || target == null || pageContainer == null || pendingSwipePage < 0) {
            resetPageSwipe();
            showHomePageImmediate(currentPage);
            return;
        }
        int width = Math.max(pageContainer.getWidth(), 1);
        boolean commit = Math.abs(deltaX) > width * 0.24f
                || (Math.abs(swipeVelocityX) > 0.65f && Math.signum(swipeVelocityX) == -pendingSwipeDirection);
        int oldPage = currentPage;
        int targetPage = pendingSwipePage;
        if (commit) {
            current.animate()
                    .translationX(-pendingSwipeDirection * width)
                    .setDuration(180L)
                    .withEndAction(() -> {
                        if (currentPage != oldPage) {
                            current.setVisibility(View.GONE);
                            current.setTranslationX(0f);
                        }
                    })
                    .start();
            target.animate()
                    .translationX(0f)
                    .setDuration(180L)
                    .withEndAction(this::updateNavigationSelection)
                    .start();
            currentPage = targetPage;
            callback.onHomePageSelected(targetPage, false);
        } else {
            current.animate().translationX(0f).setDuration(160L).start();
            target.animate()
                    .translationX(pendingSwipeDirection * width)
                    .setDuration(160L)
                    .withEndAction(() -> {
                        target.setVisibility(View.GONE);
                        target.setTranslationX(0f);
                    })
                    .start();
        }
        resetPageSwipe();
        updateNavigationSelection();
    }

    private void resetPageSwipe() {
        pageSwipeCandidate = false;
        pageSwipeDragging = false;
        pendingSwipePage = -1;
        pendingSwipeDirection = SWIPE_NONE;
        swipeVelocityX = 0f;
    }

    private boolean handleSidebarTouch(MotionEvent event) {
        if (slideSidebarContainer == null || slideSidebarContainer.getWidth() == 0) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drawerCandidate = drawerOpen || x <= dp(32);
                drawerDragging = false;
                drawerVelocityX = 0f;
                if (drawerCandidate) {
                    drawerDownX = x;
                    drawerDownY = y;
                    drawerLastX = x;
                    drawerLastEventTime = event.getEventTime();
                    drawerStartOffset = slideSidebarContainer.getTranslationX();
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!drawerCandidate) {
                    return false;
                }
                float deltaX = x - drawerDownX;
                float deltaY = y - drawerDownY;
                if (!drawerDragging) {
                    if (Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        resetDrawerGesture();
                        return false;
                    }
                    if (Math.abs(deltaX) <= touchSlop || Math.abs(deltaX) <= Math.abs(deltaY) * 1.2f) {
                        return false;
                    }
                    if (!drawerOpen && deltaX < 0f) {
                        resetDrawerGesture();
                        return false;
                    }
                    drawerDragging = true;
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
        slideSidebarContainer.animate().cancel();
        sidebarScrim.animate().cancel();
        sidebarScrim.bringToFront();
        slideSidebarContainer.bringToFront();
        slideSidebarContainer.setVisibility(View.VISIBLE);
        sidebarScrim.setVisibility(View.VISIBLE);
    }

    private void finishDrawerGesture() {
        if (slideSidebarContainer == null) {
            resetDrawerGesture();
            return;
        }
        int width = Math.max(slideSidebarContainer.getWidth(), 1);
        float offset = slideSidebarContainer.getTranslationX();
        boolean shouldOpen = offset > -width * 0.58f || drawerVelocityX > 0.55f;
        if (drawerOpen) {
            shouldOpen = !(offset < -width * 0.24f || drawerVelocityX < -0.55f);
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
    }

    private void openDrawer() {
        if (effectiveMode != MODE_SLIDE_SIDEBAR || slideSidebarContainer == null || sidebarScrim == null) {
            return;
        }
        prepareDrawerForInteraction();
        drawerOpen = true;
        slideSidebarContainer.animate().translationX(0f).setDuration(220L).start();
        sidebarScrim.animate().alpha(0.34f).setDuration(220L).start();
    }

    private void closeDrawer() {
        if (slideSidebarContainer == null || sidebarScrim == null) {
            return;
        }
        drawerOpen = false;
        int width = Math.max(slideSidebarContainer.getWidth(), dp(320));
        slideSidebarContainer.animate()
                .translationX(-width)
                .setDuration(180L)
                .withEndAction(() -> {
                    if (!drawerOpen) {
                        slideSidebarContainer.setVisibility(View.INVISIBLE);
                    }
                })
                .start();
        sidebarScrim.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction(() -> {
                    if (!drawerOpen) {
                        sidebarScrim.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void closeDrawerImmediate() {
        drawerOpen = false;
        resetDrawerGesture();
        if (slideSidebarContainer != null) {
            slideSidebarContainer.animate().cancel();
            int width = Math.max(slideSidebarContainer.getWidth(), dp(320));
            slideSidebarContainer.setTranslationX(-width);
            slideSidebarContainer.setVisibility(View.INVISIBLE);
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
        int width = Math.max(slideSidebarContainer.getWidth(), 1);
        float clamped = Math.max(-width, Math.min(0f, offset));
        float progress = 1f + clamped / width;
        slideSidebarContainer.setTranslationX(clamped);
        sidebarScrim.setAlpha(progress * 0.34f);
    }

    private int fixedSidebarWidth() {
        if ("icons".equals(settingsStore.getHomeFixedSidebarStyle())) {
            return dp(72);
        }
        int baseWidth = rootView == null || rootView.getWidth() <= 0
                ? dp(300)
                : Math.round(rootView.getWidth() * 0.24f);
        return clamp(baseWidth, dp(280), dp(360));
    }

    private String labelForPage(int page) {
        if (page == PAGE_STATS) return "时长";
        if (page == PAGE_BOOKMARKS) return "书签";
        if (page == PAGE_SETTINGS) return "设置";
        return "书架";
    }

    private String iconForPage(int page) {
        if (page == PAGE_STATS) return "◷";
        if (page == PAGE_BOOKMARKS) return "★";
        if (page == PAGE_SETTINGS) return "⚙";
        return "▦";
    }

    private int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class NavRow {
        final int page;
        final LinearLayout container;
        final TextView icon;
        final TextView label;

        NavRow(int page, LinearLayout container, TextView icon, TextView label) {
            this.page = page;
            this.container = container;
            this.icon = icon;
            this.label = label;
        }
    }
}
