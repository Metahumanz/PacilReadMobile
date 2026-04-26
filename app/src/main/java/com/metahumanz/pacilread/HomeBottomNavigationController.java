package com.metahumanz.pacilread;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemeModeHelper;

import java.util.List;

final class HomeBottomNavigationController {
    interface Callback {
        void onBottomPageSelected(int page);
    }

    private final Activity activity;
    private final SettingsStore settingsStore;
    private final HomeNavigationResolver resolver;
    private final Callback callback;
    private final View bottomNavigation;
    private final NavItem bookshelfItem;
    private final NavItem statsItem;
    private final NavItem bookmarksItem;
    private final NavItem settingsItem;

    HomeBottomNavigationController(
            Activity activity,
            SettingsStore settingsStore,
            HomeNavigationResolver resolver,
            Callback callback
    ) {
        this.activity = activity;
        this.settingsStore = settingsStore;
        this.resolver = resolver;
        this.callback = callback;
        this.bottomNavigation = activity.findViewById(R.id.bottom_navigation);
        this.bookshelfItem = new NavItem(
                activity.findViewById(R.id.nav_home_bookshelf),
                activity.findViewById(R.id.nav_home_bookshelf_icon),
                activity.findViewById(R.id.nav_home_bookshelf_label),
                HomeNavigationController.PAGE_BOOKSHELF
        );
        this.statsItem = new NavItem(
                activity.findViewById(R.id.nav_home_stats),
                activity.findViewById(R.id.nav_home_stats_icon),
                activity.findViewById(R.id.nav_home_stats_label),
                HomeNavigationController.PAGE_STATS
        );
        this.bookmarksItem = new NavItem(
                activity.findViewById(R.id.nav_home_bookmarks),
                activity.findViewById(R.id.nav_home_bookmarks_icon),
                activity.findViewById(R.id.nav_home_bookmarks_label),
                HomeNavigationController.PAGE_BOOKMARKS
        );
        this.settingsItem = new NavItem(
                activity.findViewById(R.id.nav_home_settings),
                activity.findViewById(R.id.nav_home_settings_icon),
                activity.findViewById(R.id.nav_home_settings_label),
                HomeNavigationController.PAGE_SETTINGS
        );
        bindClicks();
    }

    void update(List<Integer> activePages, int currentPage, int effectiveMode) {
        if (bottomNavigation != null) {
            bottomNavigation.setVisibility(effectiveMode == HomeNavigationResolver.MODE_BOTTOM ? View.VISIBLE : View.GONE);
        }
        setVisible(statsItem, activePages.contains(HomeNavigationController.PAGE_STATS));
        updateLabels();
        updateSelection(currentPage);
    }

    void updateSelection(int currentPage) {
        styleBottomItem(bookshelfItem, currentPage == HomeNavigationController.PAGE_BOOKSHELF);
        styleBottomItem(statsItem, currentPage == HomeNavigationController.PAGE_STATS);
        styleBottomItem(bookmarksItem, currentPage == HomeNavigationController.PAGE_BOOKMARKS);
        styleBottomItem(settingsItem, currentPage == HomeNavigationController.PAGE_SETTINGS);
    }

    boolean isTouchInBottomNavigation(MotionEvent event) {
        return AppUiUtils.isMotionEventInsideView(bottomNavigation, event);
    }

    private void bindClicks() {
        bindBottomClick(bookshelfItem);
        bindBottomClick(statsItem);
        bindBottomClick(bookmarksItem);
        bindBottomClick(settingsItem);
    }

    private void bindBottomClick(NavItem item) {
        if (item.container != null) {
            item.container.setOnClickListener(v -> callback.onBottomPageSelected(item.page));
        }
    }

    private void updateLabels() {
        updateContent(bookshelfItem);
        updateContent(statsItem);
        updateContent(bookmarksItem);
        updateContent(settingsItem);
    }

    private void updateContent(NavItem item) {
        if (item.container == null) {
            return;
        }
        String label = resolver.labelForPage(item.page);
        item.container.setContentDescription(label);
        item.container.setTooltipText(label);
        if (item.label != null) {
            item.label.setText(label);
        }
        if (item.icon != null) {
            item.icon.setImageResource(resolver.iconResForPage(item.page));
        }
    }

    private void styleBottomItem(NavItem item, boolean selected) {
        if (item.container == null) {
            return;
        }
        int color = ThemeModeHelper.resolveColor(
                activity,
                selected ? R.color.app_nav_text_active : R.color.app_nav_text_idle
        );
        boolean textMode = "text".equals(settingsStore.getHomeBottomNavStyle());
        item.container.setBackgroundResource(selected ? R.drawable.bg_nav_item_active : R.drawable.bg_nav_item_idle);
        if (item.icon != null) {
            item.icon.setVisibility(textMode ? View.GONE : View.VISIBLE);
            item.icon.setColorFilter(color);
        }
        if (item.label != null) {
            item.label.setVisibility(textMode ? View.VISIBLE : View.GONE);
            item.label.setTextColor(color);
        }
    }

    private void setVisible(NavItem item, boolean visible) {
        if (item.container != null) {
            item.container.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private static final class NavItem {
        final View container;
        final ImageView icon;
        final TextView label;
        final int page;

        NavItem(View container, ImageView icon, TextView label, int page) {
            this.container = container;
            this.icon = icon;
            this.label = label;
            this.page = page;
        }
    }
}
