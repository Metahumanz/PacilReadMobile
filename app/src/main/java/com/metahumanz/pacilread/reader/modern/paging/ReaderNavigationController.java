package com.metahumanz.pacilread.reader.modern.paging;

import android.view.View;
import android.widget.TextView;

import com.metahumanz.pacilread.model.ChapterRecord;
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

    public void showPage(int chapterIndex, int pageIndex, boolean animate, int direction) {
        if (state.chapters.isEmpty()) {
            return;
        }
        if (!paging.ensurePageAreaReady(() -> showPage(chapterIndex, pageIndex, animate, direction))) {
            return;
        }
        int safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        List<PageSlice> pages = content.getPagesForChapter(safeChapterIndex);
        int safePageIndex = ui.clamp(pageIndex, 0, pages.size() - 1);
        if (!animate || state.isAnimating || state.book == null) {
            state.pendingTapPagingDelta = 0;
            paging.invalidatePreparedPagingSnapshots();
            bindPage(views.pageTitleCurrent, views.pageBodyCurrent, safeChapterIndex, safePageIndex);
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
            return;
        }
        bindPage(views.pageTitleIncoming, views.pageBodyIncoming, safeChapterIndex, safePageIndex);
        views.pageIncoming.setVisibility(View.VISIBLE);
        paging.animateTransition(safeChapterIndex, safePageIndex, direction == 0 ? 1 : direction);
    }

    public boolean pageDown() {
        if (state.chapters.isEmpty() || state.isAnimating) {
            return false;
        }
        List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex);
        if (state.currentPageIndex < pages.size() - 1) {
            showPage(state.currentChapterIndex, state.currentPageIndex + 1, true, 1);
            return true;
        }
        if (state.currentChapterIndex < state.chapters.size() - 1) {
            openChapter(state.currentChapterIndex + 1, 0, true, 1);
            return true;
        }
        return false;
    }

    public boolean requestTapPageTurn(int direction) {
        if (direction == 0 || state.chapters.isEmpty()) {
            return false;
        }
        if (state.isAnimating || state.interactivePaging) {
            paging.cancelInteractiveAnimator();
            state.isAnimating = false;
            state.interactivePaging = false;
            state.pendingTapPagingDelta = 0;
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
            showPage(state.currentChapterIndex, state.currentPageIndex - 1, true, -1);
            return true;
        }
        if (state.currentChapterIndex > 0) {
            List<PageSlice> pages = content.getPagesForChapter(state.currentChapterIndex - 1);
            showPage(state.currentChapterIndex - 1, pages.size() - 1, true, -1);
            return true;
        }
        return false;
    }

    public void bindPage(TextView titleView, TextView bodyView, int chapterIndex, int pageIndex) {
        ChapterRecord chapter = state.chapters.get(chapterIndex);
        List<PageSlice> pages = content.getPagesForChapter(chapterIndex);
        PageSlice slice = pages.get(ui.clamp(pageIndex, 0, pages.size() - 1));
        boolean showTitle = runtime.settingsStore.isChapterTitleVisible() && pageIndex == 0;
        titleView.setVisibility(showTitle ? View.VISIBLE : View.GONE);
        titleView.setText(chapter.title);
        paging.updateBodyTopMargin(bodyView, showTitle ? content.getChapterTitleBodyMarginPx() : 0);
        bodyView.setText(slice.text == null ? "" : slice.text);
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
