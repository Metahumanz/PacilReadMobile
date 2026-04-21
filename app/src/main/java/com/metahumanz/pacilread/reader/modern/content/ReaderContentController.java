package com.metahumanz.pacilread.reader.modern.content;

import android.graphics.Paint;
import android.text.TextPaint;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.ReaderPaginator;
import com.metahumanz.pacilread.reader.ReplacementEngine;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReaderContentController {
    private static final String TAG = "PacilReadReader";
    private static final int CHAPTER_TITLE_BODY_MARGIN_DP = 16;

    private static long lastCachedBookId = -1L;
    private static BookRecord cachedBook;
    private static final List<ChapterRecord> cachedChapters = new ArrayList<>();
    private static final List<ReplacementRuleRecord> cachedRules = new ArrayList<>();
    private static final Map<Integer, List<PageSlice>> cachedPageSlicesMap = new HashMap<>();
    private static String cachedLayoutFontFamily;
    private static int cachedLayoutFontWeight;
    private static float cachedLayoutFontSize;
    private static float cachedLayoutLineSpacing;
    private static int cachedLayoutLeftPadding;
    private static int cachedLayoutRightPadding;
    private static int cachedLayoutTopPadding;
    private static int cachedLayoutBottomPadding;
    private static int cachedLayoutWidth;
    private static int cachedLayoutHeight;

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final LruCache<Integer, String> processedChapterLruCache = new LruCache<>(100);
    private final Map<Integer, Integer> processedChapterLengthCache = new HashMap<>();
    private final Runnable saveProgressRunnable = this::persistProgress;

    private ReaderNavigationController navigation;
    private ReaderStyleController style;
    private ReaderPagingAnimator paging;
    private ReaderChromeController chrome;

    public ReaderContentController(
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
            ReaderNavigationController navigation,
            ReaderStyleController style,
            ReaderPagingAnimator paging,
            ReaderChromeController chrome
    ) {
        this.navigation = navigation;
        this.style = style;
        this.paging = paging;
        this.chrome = chrome;
    }

    public void loadBook() {
        if (lastCachedBookId == state.bookId && cachedBook != null && !cachedChapters.isEmpty()) {
            state.book = cachedBook;
            state.chapters.clear();
            state.chapters.addAll(cachedChapters);
            state.replacementRules.clear();
            state.replacementRules.addAll(cachedRules);

            if (!isLayoutFingerprintSame()) {
                cachedPageSlicesMap.clear();
                updateLayoutFingerprint();
            }

            int targetChapterIndex = ui.clamp(
                    navigation.chapterIndexFromOrder(state.book.progressIndex),
                    0,
                    state.chapters.size() - 1
            );
            state.currentChapterIndex = targetChapterIndex;
            style.applyReaderSettings();
            if (state.restoredChapterIndex >= 0) {
                navigation.showPage(
                        ui.clamp(state.restoredChapterIndex, 0, state.chapters.size() - 1),
                        Math.max(state.restoredPageIndex, 0),
                        false,
                        0
                );
                state.restoredChapterIndex = -1;
                state.restoredPageIndex = -1;
            } else {
                navigation.openChapter(state.currentChapterIndex, state.book.progressOffset, false, 0);
            }
            return;
        }

        runtime.executor.execute(() -> {
            try {
                BookRecord loadedBook = runtime.databaseHelper.getBook(state.bookId);
                List<ChapterRecord> loadedChapters = runtime.databaseHelper.getChapters(state.bookId, false);
                List<ReplacementRuleRecord> loadedRules = runtime.databaseHelper.getReplacementRules(state.bookId);
                activity.runOnUiThread(() -> {
                    if (loadedBook == null || loadedChapters.isEmpty()) {
                        ui.showToast("书籍不存在或内容为空");
                        activity.finish();
                        return;
                    }

                    lastCachedBookId = state.bookId;
                    cachedBook = loadedBook;
                    cachedChapters.clear();
                    cachedChapters.addAll(loadedChapters);
                    cachedRules.clear();
                    cachedRules.addAll(loadedRules);
                    cachedPageSlicesMap.clear();
                    updateLayoutFingerprint();

                    state.book = loadedBook;
                    state.chapters.clear();
                    state.chapters.addAll(loadedChapters);
                    state.replacementRules.clear();
                    state.replacementRules.addAll(loadedRules);

                    int targetChapterIndex = ui.clamp(
                            navigation.chapterIndexFromOrder(loadedBook.progressIndex),
                            0,
                            state.chapters.size() - 1
                    );
                    state.currentChapterIndex = targetChapterIndex;
                    style.applyReaderSettings();
                    if (state.restoredChapterIndex >= 0) {
                        navigation.showPage(
                                ui.clamp(state.restoredChapterIndex, 0, state.chapters.size() - 1),
                                Math.max(state.restoredPageIndex, 0),
                                false,
                                0
                        );
                        state.restoredChapterIndex = -1;
                        state.restoredPageIndex = -1;
                    } else {
                        navigation.openChapter(state.currentChapterIndex, loadedBook.progressOffset, false, 0);
                        state.sessionStartOffset = loadedBook.progressOffset;
                        runtime.mainHandler.postDelayed(() -> syncFromWebDav(true), 2000L);
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "Failed to load reader state", error);
                activity.runOnUiThread(() -> {
                    ui.showToast("打开书籍失败: " + readableError(error));
                    activity.finish();
                });
            }
        });
    }

    public boolean isLayoutFingerprintSame() {
        if (views.pageStage == null) {
            return true;
        }
        return runtime.settingsStore.getReaderFontFamily().equals(cachedLayoutFontFamily)
                && runtime.settingsStore.getReaderFontWeight() == cachedLayoutFontWeight
                && runtime.settingsStore.getFontSizeSp() == cachedLayoutFontSize
                && runtime.settingsStore.getLineSpacingExtraSp() == cachedLayoutLineSpacing
                && runtime.settingsStore.getLeftPaddingDp() == cachedLayoutLeftPadding
                && runtime.settingsStore.getRightPaddingDp() == cachedLayoutRightPadding
                && runtime.settingsStore.getTopPaddingDp() == cachedLayoutTopPadding
                && runtime.settingsStore.getBottomPaddingDp() == cachedLayoutBottomPadding
                && views.pageStage.getWidth() == cachedLayoutWidth
                && views.pageStage.getHeight() == cachedLayoutHeight;
    }

    public void updateLayoutFingerprint() {
        if (views.pageStage == null) {
            return;
        }
        cachedLayoutFontFamily = runtime.settingsStore.getReaderFontFamily();
        cachedLayoutFontWeight = runtime.settingsStore.getReaderFontWeight();
        cachedLayoutFontSize = runtime.settingsStore.getFontSizeSp();
        cachedLayoutLineSpacing = runtime.settingsStore.getLineSpacingExtraSp();
        cachedLayoutLeftPadding = runtime.settingsStore.getLeftPaddingDp();
        cachedLayoutRightPadding = runtime.settingsStore.getRightPaddingDp();
        cachedLayoutTopPadding = runtime.settingsStore.getTopPaddingDp();
        cachedLayoutBottomPadding = runtime.settingsStore.getBottomPaddingDp();
        cachedLayoutWidth = views.pageStage.getWidth();
        cachedLayoutHeight = views.pageStage.getHeight();
    }

    public void persistProgress() {
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        int offset = currentCharOffset();
        ChapterRecord chapter = state.chapters.get(state.currentChapterIndex);
        runtime.executor.execute(() -> {
            runtime.databaseHelper.updateProgress(state.book.id, chapter.orderIndex, offset);
            state.book.progressIndex = chapter.orderIndex;
            state.book.progressOffset = offset;
            state.book.lastReadAt = System.currentTimeMillis();
            if (runtime.settingsStore.isWebDavEnabled()) {
                try {
                    runtime.webDavClient.ensureProgressDirectory();
                    runtime.webDavClient.uploadProgress(state.book, chapter, offset);
                } catch (Exception ignore) {
                }
            }
        });
    }

    public void scheduleProgressSave() {
        runtime.mainHandler.removeCallbacks(saveProgressRunnable);
        runtime.mainHandler.postDelayed(saveProgressRunnable, 600L);
    }

    public void cancelPendingProgressSave() {
        runtime.mainHandler.removeCallbacks(saveProgressRunnable);
    }

    public void syncFromWebDav(boolean silent) {
        if (!runtime.settingsStore.isWebDavEnabled()) {
            if (!silent) {
                ui.showToast("尚未启用 WebDAV 进度同步");
            }
            return;
        }
        runtime.executor.execute(() -> {
            try {
                com.metahumanz.pacilread.sync.WebDavClient.ProgressPayload payload = runtime.webDavClient.downloadProgress(state.book);
                if (payload == null) {
                    if (!silent) {
                        activity.runOnUiThread(() -> ui.showToast("云端暂时没有可恢复的进度"));
                    }
                    return;
                }
                boolean shouldApply = payload.chapterTime > state.book.lastReadAt + 5000
                        || (state.book.progressIndex == 0 && state.book.progressOffset == 0);
                if (!shouldApply) {
                    return;
                }
                int remoteIndex = ui.clamp(
                        navigation.chapterIndexFromOrder(payload.chapterIndex),
                        0,
                        state.chapters.size() - 1
                );
                runtime.databaseHelper.updateProgress(
                        state.book.id,
                        state.chapters.get(remoteIndex).orderIndex,
                        payload.chapterPosition
                );
                state.book.lastReadAt = payload.chapterTime;
                activity.runOnUiThread(() -> navigation.openChapter(remoteIndex, payload.chapterPosition, false, 0));
            } catch (Exception error) {
                if (!silent) {
                    activity.runOnUiThread(() -> ui.showToast("同步失败: " + error.getMessage()));
                }
            }
        });
    }

    public List<PageSlice> getPagesForChapter(int chapterIndex) {
        if (cachedPageSlicesMap.containsKey(chapterIndex)) {
            return cachedPageSlicesMap.get(chapterIndex);
        }
        String text = getProcessedChapterText(chapterIndex);
        int pageWidth = getReaderPageTextWidth();
        int regularPageHeight = getRegularReaderPageHeight();
        if (pageWidth <= 0 || regularPageHeight <= 0) {
            List<PageSlice> fallback = new ArrayList<>();
            fallback.add(new PageSlice(0, text.length(), text));
            return fallback;
        }
        int firstPageHeight = runtime.settingsStore.isChapterTitleVisible()
                ? Math.max(1, regularPageHeight - measureChapterTitleOccupiedHeight(state.chapters.get(chapterIndex).title, pageWidth))
                : regularPageHeight;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(views.pageBodyCurrent.getTextSize());
        paint.setTypeface(views.pageBodyCurrent.getTypeface());
        List<PageSlice> pages = ReaderPaginator.paginate(
                text,
                paint,
                pageWidth,
                firstPageHeight,
                regularPageHeight,
                views.pageBodyCurrent.getLineSpacingExtra()
        );
        cachedPageSlicesMap.put(chapterIndex, pages);
        return pages;
    }

    public int getReaderPageTextWidth() {
        if (views.pageBodyCurrent != null && views.pageBodyCurrent.getWidth() > 0) {
            return views.pageBodyCurrent.getWidth()
                    - views.pageBodyCurrent.getPaddingLeft()
                    - views.pageBodyCurrent.getPaddingRight();
        }
        if (views.pageCurrent != null && views.pageCurrent.getWidth() > 0) {
            return views.pageCurrent.getWidth() - views.pageCurrent.getPaddingLeft() - views.pageCurrent.getPaddingRight();
        }
        return 0;
    }

    public int getRegularReaderPageHeight() {
        if (views.pageBodyCurrent != null && views.pageBodyCurrent.getHeight() > 0) {
            return views.pageBodyCurrent.getHeight();
        }
        if (views.pageCurrent != null && views.pageCurrent.getHeight() > 0) {
            return views.pageCurrent.getHeight()
                    - views.pageCurrent.getPaddingTop()
                    - views.pageCurrent.getPaddingBottom()
                    - ui.dp(14);
        }
        return 0;
    }

    public int measureChapterTitleOccupiedHeight(String title, int width) {
        if (title == null || title.isBlank() || width <= 0) {
            return 0;
        }
        TextView measureView = new TextView(activity);
        measureView.setIncludeFontPadding(false);
        measureView.setMaxLines(2);
        measureView.setTypeface(views.pageTitleCurrent.getTypeface());
        measureView.setTextSize(TypedValue.COMPLEX_UNIT_PX, views.pageTitleCurrent.getTextSize());
        measureView.setText(title);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        measureView.measure(widthSpec, heightSpec);
        int safetyBuffer = Math.max(ui.dp(2), Math.round(views.pageBodyCurrent.getPaint().getFontSpacing() * 0.08f));
        return measureView.getMeasuredHeight() + ui.dp(CHAPTER_TITLE_BODY_MARGIN_DP) + safetyBuffer;
    }

    public String getProcessedChapterText(int chapterIndex) {
        String cached = processedChapterLruCache.get(chapterIndex);
        if (cached != null) {
            return cached;
        }
        ChapterRecord chapter = state.chapters.get(chapterIndex);
        if (chapter.bodyText == null) {
            ChapterRecord fullChapter = runtime.databaseHelper.getChapterContent(chapter.id);
            if (fullChapter != null) {
                chapter.bodyText = fullChapter.bodyText;
                chapter.bodyHtml = fullChapter.bodyHtml;
            }
        }
        String body = chapter.bodyText == null ? "" : chapter.bodyText;
        String processed = ReplacementEngine.apply(body, state.replacementRules);
        processedChapterLruCache.put(chapterIndex, processed);
        processedChapterLengthCache.put(chapterIndex, processed.length());
        return processed;
    }

    public int getProcessedChapterLength(int chapterIndex) {
        Integer cached = processedChapterLengthCache.get(chapterIndex);
        if (cached != null) {
            return cached;
        }
        int length = getProcessedChapterText(chapterIndex).length();
        processedChapterLengthCache.put(chapterIndex, length);
        return length;
    }

    public int getTotalProcessedBookLength() {
        if (state.totalProcessedBookLength >= 0) {
            return state.totalProcessedBookLength;
        }
        int total = 0;
        for (int i = 0; i < state.chapters.size(); i++) {
            total += getProcessedChapterLength(i);
        }
        state.totalProcessedBookLength = total;
        return state.totalProcessedBookLength;
    }

    public int currentCharOffset() {
        List<PageSlice> pages = getPagesForChapter(state.currentChapterIndex);
        if (pages.isEmpty()) {
            return 0;
        }
        return pages.get(ui.clamp(state.currentPageIndex, 0, pages.size() - 1)).start;
    }

    public void clearPageCache() {
        cachedPageSlicesMap.clear();
    }

    public void clearAllReaderCaches() {
        processedChapterLruCache.evictAll();
        processedChapterLengthCache.clear();
        cachedPageSlicesMap.clear();
        state.totalProcessedBookLength = -1;
    }

    public String readableError(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "未知错误";
        }
        return error.getMessage();
    }

    public void recordSessionStats() {
        if (state.sessionStartTime <= 0) {
            return;
        }
        long durationMs = System.currentTimeMillis() - state.sessionStartTime;
        if (durationMs < 2000) {
            return;
        }
        int seconds = (int) (durationMs / 1000);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        runtime.executor.execute(() -> runtime.databaseHelper.recordReadingStats(today, seconds, 0));
        state.sessionStartTime = System.currentTimeMillis();
    }
}
