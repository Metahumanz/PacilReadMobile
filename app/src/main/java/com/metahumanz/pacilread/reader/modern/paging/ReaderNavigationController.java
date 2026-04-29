package com.metahumanz.pacilread.reader.modern.paging;

import android.view.View;
import android.widget.TextView;

import com.metahumanz.pacilread.reader.JustifiedPageTextView;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.ReaderPaginator;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;

import java.util.List;

public final class ReaderNavigationController {
    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;

    private ReaderContentController content;
    private ReaderPagingAnimator paging;
    private ReaderChromeController chrome;

    public ReaderNavigationController(
            ModernReaderActivity activity,
            ReaderRuntime runtime,
            ReaderViewRefs views,
            ReaderSessionState state,
            ReaderUiUtils ui
    ) {
        this.activity = activity;
        this.runtime = runtime;
        this.views = views;
        this.state = state;
        this.ui = ui;
    }

    public void attachControllers(
            ReaderContentController content,
            ReaderPagingAnimator paging,
            ReaderChromeController chrome
    ) {
        this.content = content;
        this.paging = paging;
        this.chrome = chrome;
    }

    public void openChapter(int chapterIndex, int charOffset, boolean animate, int direction) {
        if (state.chapters.isEmpty()) {
            return;
        }
        if (!paging.ensurePageAreaReady(() -> openChapter(chapterIndex, charOffset, animate, direction))) {
            return;
        }
        int safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        List<PageSlice> pages = content.getPagesForChapter(safeChapterIndex);
        int pageIndex = ReaderPaginator.findPageForOffset(pages, Math.max(charOffset, 0));
        showPage(safeChapterIndex, pageIndex, animate, direction);
    }

    public void openChapterFromStart(int chapterIndex, boolean animate, int direction) {
        if (state.chapters.isEmpty()) {
            return;
        }
        if (!paging.ensurePageAreaReady(() -> openChapterFromStart(chapterIndex, animate, direction))) {
            return;
        }
        int safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        showPage(safeChapterIndex, 0, animate, direction);
    }

    public void showPage(int chapterIndex, int pageIndex, boolean animate, int direction) {
        if (state.chapters.isEmpty()) {
            return;
        }
        if (!paging.ensurePageAreaReady(() -> showPage(chapterIndex, pageIndex, animate, direction))) {
            return;
        }
        activity.clearTextSelection();
        int safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        List<PageSlice> pages = content.getPagesForChapter(safeChapterIndex);
        int safePageIndex = ui.clamp(pageIndex, 0, pages.size() - 1);
        if (!animate || state.isAnimating || state.book == null) {
            state.pendingTapPagingDelta = 0;
            paging.invalidatePreparedPagingSnapshots();
            bindCurrentSpread(safeChapterIndex, safePageIndex);
            state.currentChapterIndex = safeChapterIndex;
            state.currentPageIndex = safePageIndex;
            activity.markReadingActivity();
            paging.restoreLivePageLayers(false);
            paging.resetAnimatedPage(views.pageCurrent);
            paging.resetAnimatedPage(views.pageIncoming);
            views.pageIncoming.setVisibility(View.GONE);
            chrome.updateUiAfterPageChange();
            content.scheduleProgressSave();
            chrome.scheduleAutoHide();
            paging.schedulePagingSnapshotWarmup();
            views.pageCurrent.post(activity::onReaderPageReadyForLaunchPreview);
            return;
        }
        bindIncomingSpread(safeChapterIndex, safePageIndex);
        views.pageIncoming.setVisibility(View.VISIBLE);
        paging.animateTransition(safeChapterIndex, safePageIndex, direction == 0 ? 1 : direction);
    }

    public boolean pageDown() {
        if (state.chapters.isEmpty() || state.isAnimating) {
            return false;
        }
        List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex);
        int nextPageIndex = state.currentPageIndex + pageStep();
        if (nextPageIndex < pages.size()) {
            showPage(state.currentChapterIndex, nextPageIndex, true, 1);
            return true;
        }
        if (state.currentChapterIndex < state.chapters.size() - 1) {
            openChapterFromStart(state.currentChapterIndex + 1, true, 1);
            return true;
        }
        return false;
    }

    public boolean requestTapPageTurn(int direction) {
        if (direction == 0 || state.chapters.isEmpty()) {
            return false;
        }
        if (state.isAnimating || state.interactivePaging) {
            paging.settleInterruptedPagingAnimation();
        }
        return direction > 0 ? pageDown() : pageUp();
    }

    public void consumePendingTapPageTurn() {
        if (state.pendingTapPagingDelta == 0 || state.controlsVisible || state.isAnimating || state.interactivePaging) {
            return;
        }
        boolean moved;
        if (state.pendingTapPagingDelta > 0) {
            moved = pageDown();
            if (moved) {
                state.pendingTapPagingDelta--;
            }
        } else {
            moved = pageUp();
            if (moved) {
                state.pendingTapPagingDelta++;
            }
        }
        if (!moved) {
            state.pendingTapPagingDelta = 0;
        }
    }

    public boolean pageUp() {
        if (state.chapters.isEmpty() || state.isAnimating) {
            return false;
        }
        if (state.currentPageIndex > 0) {
            showPage(state.currentChapterIndex, Math.max(0, state.currentPageIndex - pageStep()), true, -1);
            return true;
        }
        if (state.currentChapterIndex > 0) {
            List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex - 1);
            showPage(state.currentChapterIndex - 1, lastSpreadStart(pages), true, -1);
            return true;
        }
        return false;
    }

    public void bindCurrentSpread(int chapterIndex, int pageIndex) {
        bindSpread(
                views.pageTitleCurrent,
                views.pageBodyCurrent,
                views.pageTitleCurrentRight,
                views.pageBodyCurrentRight,
                views.pageCurrentRightPane,
                views.pageCurrentGutter,
                chapterIndex,
                pageIndex
        );
    }

    public void bindIncomingSpread(int chapterIndex, int pageIndex) {
        bindSpread(
                views.pageTitleIncoming,
                views.pageBodyIncoming,
                views.pageTitleIncomingRight,
                views.pageBodyIncomingRight,
                views.pageIncomingRightPane,
                views.pageIncomingGutter,
                chapterIndex,
                pageIndex
        );
    }

    public int pageStep() {
        if (content == null || !content.isDoublePageActive()) {
            return 1;
        }
        return "one".equals(runtime.settingsStore.getReaderDoublePageTurnStep()) ? 1 : 2;
    }

    public int lastSpreadStart(List<PageSlice> pages) {
        int pageCount = pages == null ? 0 : pages.size();
        return Math.max(0, pageCount - pageStep());
    }

    public void bindPage(TextView titleView, JustifiedPageTextView bodyView, int chapterIndex, int pageIndex) {
        List<PageSlice> pages = content.getPagesForChapter(chapterIndex);
        int safePageIndex = ui.clamp(pageIndex, 0, pages.size() - 1);
        PageSlice slice = pages.get(safePageIndex);
        titleView.setVisibility(View.GONE);
        titleView.setText(null);
        paging.updateBodyTopMargin(bodyView, 0);
        bodyView.setTreatFinalLineAsParagraphEnd(safePageIndex >= pages.size() - 1);
        bodyView.setText(slice.text == null ? "" : slice.text);
    }

    private void bindSpread(
            TextView leftTitleView,
            JustifiedPageTextView leftBodyView,
            TextView rightTitleView,
            JustifiedPageTextView rightBodyView,
            View rightPane,
            View gutter,
            int chapterIndex,
            int pageIndex
    ) {
        bindPage(leftTitleView, leftBodyView, chapterIndex, pageIndex);
        boolean showRight = content.isDoublePageActive();
        if (!showRight) {
            clearRightPane(rightTitleView, rightBodyView, rightPane, gutter, false);
            return;
        }
        List<PageSlice> pages = content.getPagesForChapter(chapterIndex);
        int rightPageIndex = pageIndex + 1;
        if (rightPageIndex >= pages.size()) {
            clearRightPane(rightTitleView, rightBodyView, rightPane, gutter, true);
            return;
        }
        if (rightPane != null) {
            rightPane.setVisibility(View.VISIBLE);
        }
        if (gutter != null) {
            gutter.setVisibility(View.VISIBLE);
        }
        bindPage(rightTitleView, rightBodyView, chapterIndex, rightPageIndex);
    }

    private void clearRightPane(
            TextView rightTitleView,
            JustifiedPageTextView rightBodyView,
            View rightPane,
            View gutter,
            boolean reserveSpace
    ) {
        if (rightTitleView != null) {
            rightTitleView.setText(null);
            rightTitleView.setVisibility(View.GONE);
        }
        if (rightBodyView != null) {
            rightBodyView.setTreatFinalLineAsParagraphEnd(true);
            rightBodyView.setText("");
        }
        if (rightPane != null) {
            rightPane.setVisibility(reserveSpace ? View.VISIBLE : View.GONE);
        }
        if (gutter != null) {
            gutter.setVisibility(reserveSpace ? View.VISIBLE : View.GONE);
        }
    }

    public int chapterIndexFromOrder(int orderIndex) {
        for (int i = 0; i < state.chapters.size(); i++) {
            if (state.chapters.get(i).orderIndex == orderIndex) {
                return i;
            }
        }
        return orderIndex;
    }
}
