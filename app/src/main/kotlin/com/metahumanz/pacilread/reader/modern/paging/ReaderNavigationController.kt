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
        val pages = requireNotNull(contentController).getPagesForChapter(safeChapterIndex)
        showPage(safeChapterIndex, ReaderPaginator.findPageForOffset(pages, Math.max(charOffset, 0)), animate, direction)
    }

    fun openChapterFromStart(chapterIndex: Int, animate: Boolean, direction: Int) {
        if (state.chapters.isEmpty()) return
        val paging = requireNotNull(pagingController)
        if (!paging.ensurePageAreaReady(Runnable { openChapterFromStart(chapterIndex, animate, direction) })) return
        showPage(ui.clamp(chapterIndex, 0, state.chapters.size - 1), 0, animate, direction)
    }

    fun showPage(chapterIndex: Int, pageIndex: Int, animate: Boolean, direction: Int) {
        if (state.chapters.isEmpty()) return
        val paging = requireNotNull(pagingController)
        val content = requireNotNull(contentController)
        val chrome = requireNotNull(chromeController)
        if (!paging.ensurePageAreaReady(Runnable { showPage(chapterIndex, pageIndex, animate, direction) })) return
        activity.clearTextSelection()
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        val pages = content.getPagesForChapter(safeChapterIndex)
        val safePageIndex = ui.clamp(pageIndex, 0, pages.size - 1)
        if (!animate || state.isAnimating || state.book == null) {
            state.pendingTapPagingDelta = 0
            paging.invalidatePreparedPagingSnapshots()
            bindCurrentSpread(safeChapterIndex, safePageIndex)
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
            views.pageCurrent.post { activity.onReaderPageReadyForLaunchPreview() }
            return
        }
        bindIncomingSpread(safeChapterIndex, safePageIndex)
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
        views.pageCurrent.post { activity.onReaderPageReadyForLaunchPreview() }
    }

    fun pageDown(): Boolean {
        if (state.chapters.isEmpty() || state.isAnimating) return false
        val pages = requireNotNull(contentController).getPagesForChapter(state.currentChapterIndex)
        val nextPageIndex = state.currentPageIndex + pageStep()
        if (nextPageIndex < pages.size) {
            showPage(state.currentChapterIndex, nextPageIndex, true, 1)
            return true
        }
        if (state.currentChapterIndex < state.chapters.size - 1) {
            openChapterFromStart(state.currentChapterIndex + 1, true, 1)
            return true
        }
        return false
    }

    fun requestTapPageTurn(direction: Int): Boolean {
        if (direction == 0 || state.chapters.isEmpty()) return false
        if (state.isAnimating || state.interactivePaging) requireNotNull(pagingController).settleInterruptedPagingAnimation()
        return if (direction > 0) pageDown() else pageUp()
    }

    fun consumePendingTapPageTurn() {
        if (state.pendingTapPagingDelta == 0 || state.controlsVisible || state.isAnimating || state.interactivePaging) return
        val moved = if (state.pendingTapPagingDelta > 0) {
            pageDown().also { if (it) state.pendingTapPagingDelta-- }
        } else {
            pageUp().also { if (it) state.pendingTapPagingDelta++ }
        }
        if (!moved) state.pendingTapPagingDelta = 0
    }

    fun pageUp(): Boolean {
        if (state.chapters.isEmpty() || state.isAnimating) return false
        if (state.currentPageIndex > 0) {
            showPage(state.currentChapterIndex, Math.max(0, state.currentPageIndex - pageStep()), true, -1)
            return true
        }
        if (state.currentChapterIndex > 0) {
            val pages = requireNotNull(contentController).getPagesForChapter(state.currentChapterIndex - 1)
            showPage(state.currentChapterIndex - 1, lastSpreadStart(pages), true, -1)
            return true
        }
        return false
    }

    fun bindCurrentSpread(chapterIndex: Int, pageIndex: Int) = bindSpread(
        views.pageTitleCurrent, views.pageBodyCurrent, views.pageTitleCurrentRight, views.pageBodyCurrentRight,
        views.pageCurrentRightPane, views.pageCurrentGutter, chapterIndex, pageIndex,
    )

    fun bindIncomingSpread(chapterIndex: Int, pageIndex: Int) = bindSpread(
        views.pageTitleIncoming, views.pageBodyIncoming, views.pageTitleIncomingRight, views.pageBodyIncomingRight,
        views.pageIncomingRightPane, views.pageIncomingGutter, chapterIndex, pageIndex,
    )

    private fun bindCurrentSpreadFromPages(chapterIndex: Int, pageIndex: Int, pages: List<PageSlice>, complete: Boolean) =
        bindSpreadFromPages(
            views.pageTitleCurrent, views.pageBodyCurrent, views.pageTitleCurrentRight, views.pageBodyCurrentRight,
            views.pageCurrentRightPane, views.pageCurrentGutter, chapterIndex, pageIndex, pages, complete,
        )

    fun pageStep(): Int {
        val content = contentController
        if (content == null || !content.isDoublePageActive()) return 1
        return if (runtime.settingsStore.readerDoublePageTurnStep == "one") 1 else 2
    }

    fun lastSpreadStart(pages: List<PageSlice>?): Int = Math.max(0, (pages?.size ?: 0) - pageStep())

    fun bindPage(titleView: TextView, bodyView: JustifiedPageTextView, chapterIndex: Int, pageIndex: Int) {
        val pages = requireNotNull(contentController).getPagesForChapter(chapterIndex)
        bindPageFromPages(titleView, bodyView, pages, ui.clamp(pageIndex, 0, pages.size - 1), true)
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

    private fun bindSpread(
        leftTitleView: TextView, leftBodyView: JustifiedPageTextView, rightTitleView: TextView,
        rightBodyView: JustifiedPageTextView, rightPane: View, gutter: View, chapterIndex: Int, pageIndex: Int,
    ) {
        bindPage(leftTitleView, leftBodyView, chapterIndex, pageIndex)
        val content = requireNotNull(contentController)
        if (!content.isDoublePageActive()) {
            clearRightPane(rightTitleView, rightBodyView, rightPane, gutter, false)
            return
        }
        val pages = content.getPagesForChapter(chapterIndex)
        val rightPageIndex = pageIndex + 1
        if (rightPageIndex >= pages.size) {
            clearRightPane(rightTitleView, rightBodyView, rightPane, gutter, true)
            return
        }
        rightPane.visibility = View.VISIBLE
        gutter.visibility = if (shouldShowDoublePageGutter()) View.VISIBLE else View.GONE
        bindPage(rightTitleView, rightBodyView, chapterIndex, rightPageIndex)
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
