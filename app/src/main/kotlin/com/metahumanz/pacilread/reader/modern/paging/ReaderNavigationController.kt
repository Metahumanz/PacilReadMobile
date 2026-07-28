package com.metahumanz.pacilread.reader.modern.paging

import android.view.View
import android.widget.TextView
import com.metahumanz.pacilread.reader.JustifiedPageTextView
import com.metahumanz.pacilread.reader.PageSlice
import com.metahumanz.pacilread.reader.ReaderPaginator
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController

class ReaderNavigationController(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val views: ReaderViewRefs,
    private val state: ReaderSessionState,
    private val ui: ReaderUiUtils,
) {
    private var contentController: ReaderContentController? = null
    private var pagingController: ReaderPagingAnimator? = null
    private var chromeController: ReaderChromeController? = null

    fun attachControllers(content: ReaderContentController, paging: ReaderPagingAnimator, chrome: ReaderChromeController) {
        contentController = content
        pagingController = paging
        chromeController = chrome
    }

    fun openChapter(chapterIndex: Int, charOffset: Int, animate: Boolean, direction: Int) {
        if (state.chapters.isEmpty()) return
        val paging = requireNotNull(pagingController)
        if (!paging.ensurePageAreaReady(Runnable { openChapter(chapterIndex, charOffset, animate, direction) })) return
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        val content = requireNotNull(contentController)
        val safeOffset = Math.max(charOffset, 0)
        val navigationPages = if (!animate && direction == 0) {
            ReaderContentController.NavigationPages(content.getPagesForChapter(safeChapterIndex), true, "blocking_reflow")
        } else {
            content.getNavigationPagesForOffset(safeChapterIndex, safeOffset, "open_chapter", true)
        }
        showPageWithPages(
            safeChapterIndex,
            ReaderPaginator.findPageForOffset(navigationPages.pages, safeOffset),
            navigationPages,
            animate,
            direction,
        )
    }

    fun openChapterFromStart(chapterIndex: Int, animate: Boolean, direction: Int) {
        if (state.chapters.isEmpty()) return
        val paging = requireNotNull(pagingController)
        if (!paging.ensurePageAreaReady(Runnable { openChapterFromStart(chapterIndex, animate, direction) })) return
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        val content = requireNotNull(contentController)
        val navigationPages = content.getNavigationPagesForPage(safeChapterIndex, 0, "open_chapter_start", animate)
        showPageWithPages(safeChapterIndex, 0, navigationPages, animate, direction)
    }

    fun showPage(chapterIndex: Int, pageIndex: Int, animate: Boolean, direction: Int) {
        if (state.chapters.isEmpty()) return
        val paging = requireNotNull(pagingController)
        val content = requireNotNull(contentController)
        if (!paging.ensurePageAreaReady(Runnable { showPage(chapterIndex, pageIndex, animate, direction) })) return
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        val navigationPages = if (!animate && direction == 0) {
            ReaderContentController.NavigationPages(content.getPagesForChapter(safeChapterIndex), true, "blocking_reflow")
        } else {
            content.getNavigationPagesForPage(safeChapterIndex, pageIndex, "show_page", animate)
        }
        showPageWithPages(safeChapterIndex, pageIndex, navigationPages, animate, direction)
    }

    private fun showPageWithPages(
        safeChapterIndex: Int,
        pageIndex: Int,
        navigationPages: ReaderContentController.NavigationPages,
        animate: Boolean,
        direction: Int,
    ) {
        val paging = requireNotNull(pagingController)
        val content = requireNotNull(contentController)
        val chrome = requireNotNull(chromeController)
        val pages = navigationPages.pages
        if (pages.isEmpty()) return
        activity.clearTextSelection()
        val safePageIndex = ui.clamp(pageIndex, 0, pages.size - 1)
        if (!animate || state.isAnimating || state.book == null) {
            state.pendingTapPagingDelta = 0
            paging.invalidatePreparedPagingSnapshots()
            bindCurrentSpreadFromPages(safeChapterIndex, safePageIndex, pages, navigationPages.complete)
            state.currentChapterIndex = safeChapterIndex
            state.currentPageIndex = safePageIndex
            content.rememberCurrentPageAnchor()
            activity.markReadingActivity()
            paging.restoreLivePageLayers(false)
            paging.resetAnimatedPage(views.pageCurrent)
            paging.resetAnimatedPage(views.pageIncoming)
            views.pageIncoming.visibility = View.GONE
            chrome.updateUiAfterPageChange()
            content.scheduleProgressSave()
            chrome.scheduleAutoHide()
            paging.schedulePagingSnapshotWarmup()
            content.prewarmNavigationAroundCurrentPage("show_page")
            views.pageCurrent.post { activity.onReaderPageReadyForLaunchPreview() }
            return
        }
        bindIncomingSpreadFromPages(safeChapterIndex, safePageIndex, pages, navigationPages.complete)
        views.pageIncoming.visibility = if (runtime.settingsStore.flipMode == "simulation") View.GONE else View.VISIBLE
        paging.animateTransition(safeChapterIndex, safePageIndex, if (direction == 0) 1 else direction)
    }

    fun refreshPagingPresentationAfterSettingsChange() {
        val paging = pagingController ?: return
        paging.cancelInteractiveAnimator()
        paging.cancelInteractivePaging()
        paging.invalidatePreparedPagingSnapshots()
        requireNotNull(chromeController).updateUiAfterPageChange()
        requireNotNull(chromeController).scheduleAutoHide()
    }

    fun openChapterWithPartialPages(chapterIndex: Int, pageIndex: Int, pages: List<PageSlice>?, complete: Boolean) {
        if (state.chapters.isEmpty() || pages.isNullOrEmpty()) return
        val paging = requireNotNull(pagingController)
        val content = requireNotNull(contentController)
        val chrome = requireNotNull(chromeController)
        if (!paging.ensurePageAreaReady(Runnable { openChapterWithPartialPages(chapterIndex, pageIndex, pages, complete) })) return
        activity.clearTextSelection()
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        val safePageIndex = ui.clamp(pageIndex, 0, pages.size - 1)
        state.pendingTapPagingDelta = 0
        paging.invalidatePreparedPagingSnapshots()
        bindCurrentSpreadFromPages(safeChapterIndex, safePageIndex, pages, complete)
        state.currentChapterIndex = safeChapterIndex
        state.currentPageIndex = safePageIndex
        content.rememberCurrentPageAnchor()
        activity.markReadingActivity()
        paging.restoreLivePageLayers(false)
        paging.resetAnimatedPage(views.pageCurrent)
        paging.resetAnimatedPage(views.pageIncoming)
        views.pageIncoming.visibility = View.GONE
        chrome.updateUiAfterPageChange()
        content.scheduleProgressSave()
        chrome.scheduleAutoHide()
        paging.schedulePagingSnapshotWarmup()
        content.prewarmNavigationAroundCurrentPage("partial_page")
        views.pageCurrent.post { activity.onReaderPageReadyForLaunchPreview() }
    }

    fun pageDown(): Boolean {
        if (state.chapters.isEmpty() || state.isAnimating) return false
        val content = requireNotNull(contentController)
        val navigationPages = content.getNavigationPagesForPage(state.currentChapterIndex, state.currentPageIndex, "page_down")
        val pages = navigationPages.pages
        val nextPageIndex = state.currentPageIndex + pageStep()
        if (nextPageIndex < pages.size) {
            pagingController?.notePageTurnRequestStarted("page_down")
            showPageWithPages(state.currentChapterIndex, nextPageIndex, navigationPages, true, 1)
            return true
        }
        if (!navigationPages.complete) {
            content.requestBackgroundPaginationForChapter(state.currentChapterIndex, "page_down_partial_gap")
            return false
        }
        if (state.currentChapterIndex < state.chapters.size - 1) {
            content.requestBackgroundPaginationForChapter(state.currentChapterIndex + 1, "page_down_next_chapter")
            pagingController?.notePageTurnRequestStarted("page_down_next_chapter")
            openChapterFromStart(state.currentChapterIndex + 1, true, 1)
            return true
        }
        return false
    }

    fun requestTapPageTurn(direction: Int): Boolean {
        if (direction == 0 || state.chapters.isEmpty() || state.controlsTransitionActive) return false
        if (state.isAnimating || state.interactivePaging) requireNotNull(pagingController).settleInterruptedPagingAnimation()
        return if (direction > 0) pageDown() else pageUp()
    }

    fun pageUp(): Boolean {
        if (state.chapters.isEmpty() || state.isAnimating) return false
        val content = requireNotNull(contentController)
        if (state.currentPageIndex > 0) {
            val targetPageIndex = Math.max(0, state.currentPageIndex - pageStep())
            val navigationPages = content.getNavigationPagesForPage(state.currentChapterIndex, targetPageIndex, "page_up")
            pagingController?.notePageTurnRequestStarted("page_up")
            showPageWithPages(state.currentChapterIndex, targetPageIndex, navigationPages, true, -1)
            return true
        }
        if (state.currentChapterIndex > 0) {
            val previousChapterIndex = state.currentChapterIndex - 1
            val navigationPages = content.getNavigationPagesForPage(previousChapterIndex, Int.MAX_VALUE, "page_up_previous")
            if (!navigationPages.complete) {
                content.requestBackgroundPaginationForChapter(previousChapterIndex, "page_up_previous_wait")
                return false
            }
            pagingController?.notePageTurnRequestStarted("page_up_previous_chapter")
            showPageWithPages(previousChapterIndex, lastSpreadStart(navigationPages.pages), navigationPages, true, -1)
            return true
        }
        return false
    }

    fun bindCurrentSpread(chapterIndex: Int, pageIndex: Int) {
        val navigationPages = requireNotNull(contentController).getNavigationPagesForPage(chapterIndex, pageIndex, "bind_current")
        bindCurrentSpreadFromPages(chapterIndex, pageIndex, navigationPages.pages, navigationPages.complete)
    }

    fun bindIncomingSpread(chapterIndex: Int, pageIndex: Int) {
        val navigationPages = requireNotNull(contentController).getNavigationPagesForPage(chapterIndex, pageIndex, "bind_incoming")
        bindIncomingSpreadFromPages(chapterIndex, pageIndex, navigationPages.pages, navigationPages.complete)
    }

    private fun bindCurrentSpreadFromPages(chapterIndex: Int, pageIndex: Int, pages: List<PageSlice>, complete: Boolean) =
        bindSpreadFromPages(
            views.pageTitleCurrent, views.pageBodyCurrent, views.pageTitleCurrentRight, views.pageBodyCurrentRight,
            views.pageCurrentRightPane, views.pageCurrentGutter, chapterIndex, pageIndex, pages, complete,
        )

    private fun bindIncomingSpreadFromPages(chapterIndex: Int, pageIndex: Int, pages: List<PageSlice>, complete: Boolean) =
        bindSpreadFromPages(
            views.pageTitleIncoming, views.pageBodyIncoming, views.pageTitleIncomingRight, views.pageBodyIncomingRight,
            views.pageIncomingRightPane, views.pageIncomingGutter, chapterIndex, pageIndex, pages, complete,
        )

    fun pageStep(): Int {
        val content = contentController
        if (content == null || !content.isDoublePageActive()) return 1
        return if (runtime.settingsStore.readerDoublePageTurnStep == "one") 1 else 2
    }

    fun lastSpreadStart(pages: List<PageSlice>?): Int = Math.max(0, (pages?.size ?: 0) - pageStep())

    fun bindPage(titleView: TextView, bodyView: JustifiedPageTextView, chapterIndex: Int, pageIndex: Int) {
        val navigationPages = requireNotNull(contentController).getNavigationPagesForPage(chapterIndex, pageIndex, "bind_page")
        val pages = navigationPages.pages
        if (pages.isEmpty()) return
        bindPageFromPages(titleView, bodyView, pages, ui.clamp(pageIndex, 0, pages.size - 1), navigationPages.complete)
    }

    private fun bindPageFromPages(
        titleView: TextView, bodyView: JustifiedPageTextView, pages: List<PageSlice>, pageIndex: Int, complete: Boolean,
    ) {
        val safePageIndex = ui.clamp(pageIndex, 0, pages.size - 1)
        val slice = pages[safePageIndex]
        titleView.visibility = View.GONE
        titleView.text = null
        requireNotNull(pagingController).updateBodyTopMargin(bodyView, 0)
        bodyView.setTreatFinalLineAsParagraphEnd(complete && safePageIndex >= pages.size - 1)
        bodyView.setBottomJustifyEnabled(slice.hasBodyText() && safePageIndex < pages.size - 1)
        bodyView.text = slice.text
    }

    private fun bindSpreadFromPages(
        leftTitleView: TextView, leftBodyView: JustifiedPageTextView, rightTitleView: TextView,
        rightBodyView: JustifiedPageTextView, rightPane: View, gutter: View, chapterIndex: Int,
        pageIndex: Int, pages: List<PageSlice>, complete: Boolean,
    ) {
        val safePageIndex = ui.clamp(pageIndex, 0, pages.size - 1)
        bindPageFromPages(leftTitleView, leftBodyView, pages, safePageIndex, complete)
        if (requireNotNull(contentController).isDoublePageActive().not()) {
            clearRightPane(rightTitleView, rightBodyView, rightPane, gutter, false)
            return
        }
        val rightPageIndex = safePageIndex + 1
        if (rightPageIndex >= pages.size) {
            clearRightPane(rightTitleView, rightBodyView, rightPane, gutter, true)
            return
        }
        rightPane.visibility = View.VISIBLE
        gutter.visibility = if (shouldShowDoublePageGutter()) View.VISIBLE else View.GONE
        bindPageFromPages(rightTitleView, rightBodyView, pages, rightPageIndex, complete)
    }

    private fun clearRightPane(
        rightTitleView: TextView, rightBodyView: JustifiedPageTextView, rightPane: View, gutter: View, reserveSpace: Boolean,
    ) {
        rightTitleView.text = null
        rightTitleView.visibility = View.GONE
        rightBodyView.setTreatFinalLineAsParagraphEnd(true)
        rightBodyView.setBottomJustifyEnabled(false)
        rightBodyView.text = ""
        rightPane.visibility = if (reserveSpace) View.VISIBLE else View.GONE
        gutter.visibility = if (reserveSpace && shouldShowDoublePageGutter()) View.VISIBLE else View.GONE
    }

    private fun shouldShowDoublePageGutter(): Boolean = contentController?.shouldShowDoublePageGutter() ?: true

    fun chapterIndexFromOrder(orderIndex: Int): Int {
        for (i in state.chapters.indices) if (state.chapters[i].orderIndex == orderIndex) return i
        return orderIndex
    }
}
