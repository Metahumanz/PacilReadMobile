package com.metahumanz.pacilread

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import com.metahumanz.pacilread.storage.SettingsStore

class HomeNavigationResolver(
    private val activity: Activity,
    private val settingsStore: SettingsStore,
    private val rootView: View?,
) {
    fun activePages(readingTimeTrackingEnabled: Boolean): List<Int> = ArrayList<Int>().apply {
        add(HomeNavigationController.PAGE_BOOKSHELF)
        if (readingTimeTrackingEnabled) add(HomeNavigationController.PAGE_STATS)
        add(HomeNavigationController.PAGE_BOOKMARKS)
        add(HomeNavigationController.PAGE_SETTINGS)
    }

    fun resolveEffectiveMode(): Int {
        val configuration = activity.resources.configuration
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wide = isWide(configuration)
        val requested = if (landscape) settingsStore.landscapeHomeNavigationMode else settingsStore.portraitHomeNavigationMode
        val sidebar = requested == "sidebar" || requested == "auto" && (landscape || wide)
        if (!sidebar) return MODE_BOTTOM
        val canFix = landscape || wide
        val fixed = canFix && settingsStore.homeSidebarPresentation == "fixed_wide"
        return if (fixed) MODE_FIXED_SIDEBAR else MODE_SLIDE_SIDEBAR
    }

    fun fixedSidebarWidth(): Int {
        if (settingsStore.homeFixedSidebarStyle == "icons") return AppUiUtils.dp(activity, 72)
        val baseWidth = if (rootView == null || rootView.width <= 0) {
            AppUiUtils.dp(activity, 300)
        } else {
            Math.round(rootView.width * 0.24f)
        }
        return baseWidth.coerceIn(AppUiUtils.dp(activity, 280), AppUiUtils.dp(activity, 360))
    }

    fun labelForPage(page: Int): String = when (page) {
        HomeNavigationController.PAGE_STATS -> "时长"
        HomeNavigationController.PAGE_BOOKMARKS -> "书签"
        HomeNavigationController.PAGE_SETTINGS -> "设置"
        else -> "书架"
    }

    fun iconResForPage(page: Int): Int = when (page) {
        HomeNavigationController.PAGE_STATS -> R.drawable.ic_home_time
        HomeNavigationController.PAGE_BOOKMARKS -> R.drawable.ic_home_bookmark
        HomeNavigationController.PAGE_SETTINGS -> R.drawable.ic_home_settings
        else -> R.drawable.ic_home_bookshelf
    }

    private fun isWide(configuration: Configuration): Boolean =
        configuration.smallestScreenWidthDp >= 600 ||
            configuration.screenWidthDp >= 600 ||
            rootView != null && rootView.width >= AppUiUtils.dp(activity, 640)

    companion object {
        const val MODE_BOTTOM = 0
        const val MODE_SLIDE_SIDEBAR = 1
        const val MODE_FIXED_SIDEBAR = 2
    }
}
