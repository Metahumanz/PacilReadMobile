package com.metahumanz.pacilread;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;

import com.metahumanz.pacilread.storage.SettingsStore;

import java.util.ArrayList;
import java.util.List;

public final class HomeNavigationController {
    public static final int PAGE_BOOKSHELF = 0;
    public static final int PAGE_STATS = 1;
    public static final int PAGE_BOOKMARKS = 2;
    public static final int PAGE_SETTINGS = 3;

    public interface Callback {
        boolean isReadingTimeTrackingEnabled();
        void onHomePageSelected(int page, boolean syncFirst);
    }

    private final Activity activity;
    private final Callback callback;
    private final List<Integer> activePages = new ArrayList<>();
    private final View rootView;
    private final View pageContainer;
    private final View sectionBookshelf;
    private final View sectionReadingStats;
    private final View sectionBookmarks;
    private final View sectionSettings;
    private final View loadingLayout;
    private final View sidebarBookshelfButton;
    private final View sidebarStatsButton;
    private final View sidebarBookmarksButton;
    private final View sidebarSettingsButton;
    private final HomeNavigationResolver resolver;
    private final HomeBottomNavigationController bottomNavigationController;
    private final HomeSidebarController sidebarController;
    private final HomePagerController pagerController;

    private int currentPage = PAGE_BOOKSHELF;
    private int effectiveMode = HomeNavigationResolver.MODE_BOTTOM;

    public HomeNavigationController(Activity activity, SettingsStore settingsStore, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.rootView = activity.findViewById(R.id.main_root);
        this.pageContainer = activity.findViewById(R.id.page_container);
        this.sectionBookshelf = activity.findViewById(R.id.section_bookshelf);
        this.sectionReadingStats = activity.findViewById(R.id.section_reading_stats);
        this.sectionBookmarks = activity.findViewById(R.id.section_bookmarks);
        this.sectionSettings = activity.findViewById(R.id.section_home_settings);
        this.loadingLayout = activity.findViewById(R.id.layout_loading);
        this.sidebarBookshelfButton = activity.findViewById(R.id.button_home_sidebar_bookshelf);
        this.sidebarStatsButton = activity.findViewById(R.id.button_home_sidebar_stats);
        this.sidebarBookmarksButton = activity.findViewById(R.id.button_home_sidebar_bookmarks);
        this.sidebarSettingsButton = activity.findViewById(R.id.button_home_sidebar_settings);
        this.resolver = new HomeNavigationResolver(activity, settingsStore, rootView);
        this.bottomNavigationController = new HomeBottomNavigationController(
                activity,
                settingsStore,
                resolver,
                page -> selectHomePage(page, true, page == PAGE_STATS)
        );
        this.sidebarController = new HomeSidebarController(
                activity,
                settingsStore,
                resolver,
                (page, animate) -> selectHomePage(page, animate, page == PAGE_STATS)
        );
        this.pagerController = new HomePagerController(pageContainer, new HomePagerController.Callback() {
            @Override
            public View sectionForPage(int page) {
                return HomeNavigationController.this.sectionForPage(page);
            }

            @Override
            public boolean isTouchBlocked(MotionEvent event) {
                return loadingLayout == null
                        || loadingLayout.getVisibility() == View.VISIBLE
                        || bottomNavigationController.isTouchInBottomNavigation(event);
            }

            @Override
            public void onPageChanged(int page, boolean syncFirst) {
                currentPage = page;
                callback.onHomePageSelected(page, syncFirst || page == PAGE_STATS);
            }

            @Override
            public void onSelectionChanged() {
                updateNavigationSelection();
            }
        });
        bindSidebarMenuButtons();
        setup();
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void restoreHomePage(int page) {
        selectHomePage(page, false, false);
    }

    public void refreshFromSettings() {
        activePages.clear();
        activePages.addAll(resolver.activePages(callback.isReadingTimeTrackingEnabled()));
        int previousMode = effectiveMode;
        effectiveMode = resolver.resolveEffectiveMode();
        if (!activePages.contains(currentPage)) {
            currentPage = PAGE_BOOKSHELF;
        }
        pagerController.setActivePages(activePages);
        pagerController.setCurrentPage(currentPage);
        sidebarController.setActivePages(activePages);
        bottomNavigationController.update(activePages, currentPage, effectiveMode);
        sidebarController.updateShell(effectiveMode, previousMode != effectiveMode);
        sidebarController.rebuild();
        updateSidebarMenuButtons();
        pagerController.showImmediate(currentPage);
        currentPage = pagerController.getCurrentPage();
    }

    public boolean onBackPressed() {
        if (sidebarController.onBackPressed()) {
            return true;
        }
        if (currentPage != PAGE_BOOKSHELF) {
            selectHomePage(PAGE_BOOKSHELF, true, false);
            return true;
        }
        return false;
    }

    public boolean handleTouchEvent(MotionEvent event) {
        if (sidebarController.handleTouchEvent(event)) {
            return true;
        }
        return effectiveMode == HomeNavigationResolver.MODE_BOTTOM
                && pagerController.handleTouchEvent(event);
    }

    public boolean consumePendingChildTouchCancel() {
        return sidebarController.consumePendingChildTouchCancel();
    }

    private void setup() {
        if (rootView != null) {
            rootView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    refreshFromSettings();
                }
            });
        }
        refreshFromSettings();
    }

    private void bindSidebarMenuButtons() {
        bindSidebarMenuButton(sidebarBookshelfButton);
        bindSidebarMenuButton(sidebarStatsButton);
        bindSidebarMenuButton(sidebarBookmarksButton);
        bindSidebarMenuButton(sidebarSettingsButton);
    }

    private void bindSidebarMenuButton(View button) {
        if (button != null) {
            button.setOnClickListener(v -> sidebarController.openDrawer());
        }
    }

    private void updateSidebarMenuButtons() {
        int visibility = effectiveMode == HomeNavigationResolver.MODE_SLIDE_SIDEBAR ? View.VISIBLE : View.GONE;
        setVisibility(sidebarBookshelfButton, visibility);
        setVisibility(sidebarStatsButton, visibility);
        setVisibility(sidebarBookmarksButton, visibility);
        setVisibility(sidebarSettingsButton, visibility);
    }

    private void setVisibility(View view, int visibility) {
        if (view != null) {
            view.setVisibility(visibility);
        }
    }

    private void selectHomePage(int page, boolean animate, boolean syncFirst) {
        if (!activePages.contains(page)) {
            return;
        }
        if (sidebarController.isDrawerOpen()) {
            sidebarController.closeDrawer();
        }
        pagerController.selectPage(page, animate, syncFirst);
        currentPage = pagerController.getCurrentPage();
        updateNavigationSelection();
    }

    private void updateNavigationSelection() {
        bottomNavigationController.updateSelection(currentPage);
        sidebarController.updateSelection(currentPage);
    }

    private View sectionForPage(int page) {
        if (page == PAGE_STATS) return sectionReadingStats;
        if (page == PAGE_BOOKMARKS) return sectionBookmarks;
        if (page == PAGE_SETTINGS) return sectionSettings;
        if (page == PAGE_BOOKSHELF) return sectionBookshelf;
        return null;
    }
}
