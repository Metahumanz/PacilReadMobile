package com.metahumanz.pacilread

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.theme.ThemeModeHelper

class HomeBottomNavigationController(
    private val activity: Activity,
    private val settingsStore: SettingsStore,
    private val resolver: HomeNavigationResolver,
    private val callback: Callback,
) {
    interface Callback {
        fun onBottomPageSelected(page: Int)
    }

    private val bottomNavigation: View? = activity.findViewById(R.id.bottom_navigation)
    private val bookshelfItem = NavItem(
        activity.findViewById(R.id.nav_home_bookshelf),
        activity.findViewById(R.id.nav_home_bookshelf_icon),
        activity.findViewById(R.id.nav_home_bookshelf_label),
        HomeNavigationController.PAGE_BOOKSHELF,
    )
    private val statsItem = NavItem(
        activity.findViewById(R.id.nav_home_stats),
        activity.findViewById(R.id.nav_home_stats_icon),
        activity.findViewById(R.id.nav_home_stats_label),
        HomeNavigationController.PAGE_STATS,
    )
    private val bookmarksItem = NavItem(
        activity.findViewById(R.id.nav_home_bookmarks),
        activity.findViewById(R.id.nav_home_bookmarks_icon),
        activity.findViewById(R.id.nav_home_bookmarks_label),
        HomeNavigationController.PAGE_BOOKMARKS,
    )
    private val settingsItem = NavItem(
        activity.findViewById(R.id.nav_home_settings),
        activity.findViewById(R.id.nav_home_settings_icon),
        activity.findViewById(R.id.nav_home_settings_label),
        HomeNavigationController.PAGE_SETTINGS,
    )

    init {
        bindClicks()
    }

    fun update(activePages: List<Int>, currentPage: Int, effectiveMode: Int) {
        bottomNavigation?.visibility =
            if (effectiveMode == HomeNavigationResolver.MODE_BOTTOM) View.VISIBLE else View.GONE
        setVisible(statsItem, activePages.contains(HomeNavigationController.PAGE_STATS))
        updateLabels()
        updateSelection(currentPage)
    }

    fun updateSelection(currentPage: Int) {
        styleBottomItem(bookshelfItem, currentPage == HomeNavigationController.PAGE_BOOKSHELF)
        styleBottomItem(statsItem, currentPage == HomeNavigationController.PAGE_STATS)
        styleBottomItem(bookmarksItem, currentPage == HomeNavigationController.PAGE_BOOKMARKS)
        styleBottomItem(settingsItem, currentPage == HomeNavigationController.PAGE_SETTINGS)
    }

    fun isTouchInBottomNavigation(event: MotionEvent?): Boolean =
        AppUiUtils.isMotionEventInsideView(bottomNavigation, event)

    private fun bindClicks() {
        bindBottomClick(bookshelfItem)
        bindBottomClick(statsItem)
        bindBottomClick(bookmarksItem)
        bindBottomClick(settingsItem)
    }

    private fun bindBottomClick(item: NavItem) {
        item.container?.setOnClickListener { callback.onBottomPageSelected(item.page) }
    }

    private fun updateLabels() {
        updateContent(bookshelfItem)
        updateContent(statsItem)
        updateContent(bookmarksItem)
        updateContent(settingsItem)
    }

    private fun updateContent(item: NavItem) {
        val container = item.container ?: return
        val label = resolver.labelForPage(item.page)
        container.contentDescription = label
        container.tooltipText = label
        item.label?.text = label
        item.icon?.setImageResource(resolver.iconResForPage(item.page))
    }

    private fun styleBottomItem(item: NavItem, selected: Boolean) {
        val container = item.container ?: return
        val color = ThemeModeHelper.resolveColor(
            activity,
            if (selected) R.color.app_nav_text_active else R.color.app_nav_text_idle,
        )
        val textMode = "text" == settingsStore.homeBottomNavStyle
        container.setBackgroundResource(if (selected) R.drawable.bg_nav_item_active else R.drawable.bg_nav_item_idle)
        item.icon?.apply {
            visibility = if (textMode) View.GONE else View.VISIBLE
            setColorFilter(color)
        }
        item.label?.apply {
            visibility = if (textMode) View.VISIBLE else View.GONE
            setTextColor(color)
        }
    }

    private fun setVisible(item: NavItem, visible: Boolean) {
        item.container?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private class NavItem(
        val container: View?,
        val icon: ImageView?,
        val label: TextView?,
        val page: Int,
    )
}
