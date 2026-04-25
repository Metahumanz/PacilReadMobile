package com.metahumanz.pacilread;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;

import com.metahumanz.pacilread.storage.SettingsStore;

import java.util.ArrayList;
import java.util.List;

final class HomeNavigationResolver {
    static final int MODE_BOTTOM = 0;
    static final int MODE_SLIDE_SIDEBAR = 1;
    static final int MODE_FIXED_SIDEBAR = 2;

    private final Activity activity;
    private final SettingsStore settingsStore;
    private final View rootView;

    HomeNavigationResolver(Activity activity, SettingsStore settingsStore, View rootView) {
        this.activity = activity;
        this.settingsStore = settingsStore;
        this.rootView = rootView;
    }

    List<Integer> activePages(boolean readingTimeTrackingEnabled) {
        List<Integer> pages = new ArrayList<>();
        pages.add(HomeNavigationController.PAGE_BOOKSHELF);
        if (readingTimeTrackingEnabled) {
            pages.add(HomeNavigationController.PAGE_STATS);
        }
        pages.add(HomeNavigationController.PAGE_BOOKMARKS);
        pages.add(HomeNavigationController.PAGE_SETTINGS);
        return pages;
    }

    int resolveEffectiveMode() {
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

    int fixedSidebarWidth() {
        if ("icons".equals(settingsStore.getHomeFixedSidebarStyle())) {
            return AppUiUtils.dp(activity, 72);
        }
        int baseWidth = rootView == null || rootView.getWidth() <= 0
                ? AppUiUtils.dp(activity, 300)
                : Math.round(rootView.getWidth() * 0.24f);
        return clamp(baseWidth, AppUiUtils.dp(activity, 280), AppUiUtils.dp(activity, 360));
    }

    String labelForPage(int page) {
        if (page == HomeNavigationController.PAGE_STATS) return "时长";
        if (page == HomeNavigationController.PAGE_BOOKMARKS) return "书签";
        if (page == HomeNavigationController.PAGE_SETTINGS) return "设置";
        return "书架";
    }

    int iconResForPage(int page) {
        if (page == HomeNavigationController.PAGE_STATS) return R.drawable.ic_home_time;
        if (page == HomeNavigationController.PAGE_BOOKMARKS) return R.drawable.ic_home_bookmark;
        if (page == HomeNavigationController.PAGE_SETTINGS) return R.drawable.ic_home_settings;
        return R.drawable.ic_home_bookshelf;
    }

    private boolean isWide(Configuration configuration) {
        return configuration.smallestScreenWidthDp >= 600
                || configuration.screenWidthDp >= 600
                || (rootView != null && rootView.getWidth() >= AppUiUtils.dp(activity, 640));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
