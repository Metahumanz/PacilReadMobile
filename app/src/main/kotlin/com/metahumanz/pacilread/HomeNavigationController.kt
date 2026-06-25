package com.metahumanz.pacilread

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import com.metahumanz.pacilread.storage.SettingsStore

class HomeNavigationController(
    private val activity: Activity,
    settingsStore: SettingsStore,
    private val callback: Callback,
) {
    interface Callback {
        fun isReadingTimeTrackingEnabled(): Boolean
        fun onHomePageSelected(page: Int, syncFirst: Boolean)
    }

    private val activePages = ArrayList<Int>()
    private val rootView: View? = activity.findViewById(R.id.main_root)
    private val pageContainer: View? = activity.findViewById(R.id.page_container)
    private val sectionBookshelf: View? = activity.findViewById(R.id.section_bookshelf)
    private val sectionReadingStats: View? = activity.findViewById(R.id.section_reading_stats)
    private val sectionBookmarks: View? = activity.findViewById(R.id.section_bookmarks)
    private val sectionSettings: View? = activity.findViewById(R.id.section_home_settings)
    private val loadingLayout: View? = activity.findViewById(R.id.layout_loading)
    private val sidebarBookshelfButton: View? = activity.findViewById(R.id.button_home_sidebar_bookshelf)
    private val sidebarStatsButton: View? = activity.findViewById(R.id.button_home_sidebar_stats)
    private val sidebarBookmarksButton: View? = activity.findViewById(R.id.button_home_sidebar_bookmarks)
    private val sidebarSettingsButton: View? = activity.findViewById(R.id.button_home_sidebar_settings)
    private val resolver = HomeNavigationResolver(activity, settingsStore, rootView)
    private val bottomNavigationController = HomeBottomNavigationController(
        activity,
        settingsStore,
        resolver,
        object : HomeBottomNavigationController.Callback {
            override fun onBottomPageSelected(page: Int) = selectHomePage(page, true, page == PAGE_STATS)
        },
    )
    private val sidebarController = HomeSidebarController(
        activity,
        settingsStore,
        resolver,
    ) { page, animate -> selectHomePage(page, animate, page == PAGE_STATS) }
    private val pagerController = HomePagerController(pageContainer, object : HomePagerController.Callback {
        override fun sectionForPage(page: Int): View? = this@HomeNavigationController.sectionForPage(page)

        override fun isTouchBlocked(event: MotionEvent): Boolean =
            loadingLayout == null || loadingLayout.visibility == View.VISIBLE ||
                bottomNavigationController.isTouchInBottomNavigation(event)

        override fun onPageChanged(page: Int, syncFirst: Boolean) {
            currentPage = page
            callback.onHomePageSelected(page, syncFirst || page == PAGE_STATS)
        }

        override fun onSelectionChanged() = updateNavigationSelection()
    })

    private var currentPage = PAGE_BOOKSHELF
    private var effectiveMode = HomeNavigationResolver.MODE_BOTTOM

    init {
        bindSidebarMenuButtons()
        setup()
    }

    fun getCurrentPage(): Int = currentPage

    fun restoreHomePage(page: Int) = selectHomePage(page, false, false)

    fun refreshFromSettings() {
        activePages.clear()
        activePages.addAll(resolver.activePages(callback.isReadingTimeTrackingEnabled()))
        val previousMode = effectiveMode
        effectiveMode = resolver.resolveEffectiveMode()
        if (!activePages.contains(currentPage)) currentPage = PAGE_BOOKSHELF
        pagerController.setActivePages(activePages)
        pagerController.setCurrentPage(currentPage)
        sidebarController.setActivePages(activePages)
        bottomNavigationController.update(activePages, currentPage, effectiveMode)
        sidebarController.updateShell(effectiveMode, previousMode != effectiveMode)
        sidebarController.rebuild()
        updateSidebarMenuButtons()
        pagerController.showImmediate(currentPage)
        currentPage = pagerController.currentPage
    }

    fun onBackPressed(): Boolean {
        if (sidebarController.onBackPressed()) return true
        if (currentPage != PAGE_BOOKSHELF) {
            selectHomePage(PAGE_BOOKSHELF, true, false)
            return true
        }
        return false
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        if (sidebarController.handleTouchEvent(event)) return true
        return effectiveMode == HomeNavigationResolver.MODE_BOTTOM && pagerController.handleTouchEvent(event)
    }

    fun consumePendingChildTouchCancel(): Boolean = sidebarController.consumePendingChildTouchCancel()

    private fun setup() {
        rootView?.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) refreshFromSettings()
        }
        refreshFromSettings()
    }

    private fun bindSidebarMenuButtons() {
        bindSidebarMenuButton(sidebarBookshelfButton)
        bindSidebarMenuButton(sidebarStatsButton)
        bindSidebarMenuButton(sidebarBookmarksButton)
        bindSidebarMenuButton(sidebarSettingsButton)
    }

    private fun bindSidebarMenuButton(button: View?) {
        button?.setOnClickListener { sidebarController.openDrawer() }
    }

    private fun updateSidebarMenuButtons() {
        val visibility = if (effectiveMode == HomeNavigationResolver.MODE_SLIDE_SIDEBAR) View.VISIBLE else View.GONE
        setVisibility(sidebarBookshelfButton, visibility)
        setVisibility(sidebarStatsButton, visibility)
        setVisibility(sidebarBookmarksButton, visibility)
        setVisibility(sidebarSettingsButton, visibility)
    }

    private fun setVisibility(view: View?, visibility: Int) {
        view?.visibility = visibility
    }

    private fun selectHomePage(page: Int, animate: Boolean, syncFirst: Boolean) {
        if (!activePages.contains(page)) return
        if (sidebarController.isDrawerOpen) sidebarController.closeDrawer()
        pagerController.selectPage(page, animate, syncFirst)
        currentPage = pagerController.currentPage
        updateNavigationSelection()
    }

    private fun updateNavigationSelection() {
        bottomNavigationController.updateSelection(currentPage)
        sidebarController.updateSelection(currentPage)
    }

    private fun sectionForPage(page: Int): View? = when (page) {
        PAGE_STATS -> sectionReadingStats
        PAGE_BOOKMARKS -> sectionBookmarks
        PAGE_SETTINGS -> sectionSettings
        PAGE_BOOKSHELF -> sectionBookshelf
        else -> null
    }

    companion object {
        const val PAGE_BOOKSHELF = 0
        const val PAGE_STATS = 1
        const val PAGE_BOOKMARKS = 2
        const val PAGE_SETTINGS = 3
    }
}
