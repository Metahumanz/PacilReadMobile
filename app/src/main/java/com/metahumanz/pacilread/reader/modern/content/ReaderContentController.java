package com.metahumanz.pacilread.reader.modern.content;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineHeightSpan;
import android.util.Log;
import android.util.LruCache;
import android.view.View;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.ReaderPaginator;
import com.metahumanz.pacilread.reader.ReaderTitleSpan;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReaderContentController {
    private static final String TAG = "PacilReadReader";
    private static final long REFLOW_DEBOUNCE_MS = 32L;

    private static long lastCachedBookId = -1L;
    private static BookRecord cachedBook;
    private static final List<ChapterRecord> cachedChapters = new ArrayList<>();
    private static final List<ReplacementRuleRecord> cachedRules = new ArrayList<>();
    private static final Map<Integer, List<PageSlice>> cachedPageSlicesMap = new HashMap<>();
    private static ReaderLayoutSignature cachedLayoutSignature;

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final LruCache<Integer, String> processedChapterLruCache = new LruCache<>(100);
    private final Map<Integer, Integer> processedChapterLengthCache = new HashMap<>();
    private final Runnable saveProgressRunnable = this::persistProgress;
    private final Runnable scheduledReflowRunnable = this::performScheduledReflow;

    private ReaderNavigationController navigation;
    private ReaderStyleController style;
    private ReaderPagingAnimator paging;
    private ReaderChromeController chrome;
    private int pendingReflowChapterIndex = -1;
    private int pendingReflowAnchorOffset = 0;
    private int reflowGeneration = 0;

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

            int targetChapterIndex = ui.clamp(
                    navigation.chapterIndexFromOrder(state.book.progressIndex),
                    0,
                    state.chapters.size() - 1
            );
            if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
                targetChapterIndex = ui.clamp(state.restoredChapterIndex, 0, state.chapters.size() - 1);
            }
            state.currentChapterIndex = targetChapterIndex;
            int initialAnchorOffset = resolveInitialAnchorOffset(state.book.progressOffset);
            state.sessionStartOffset = initialAnchorOffset;
            resetRestoredPosition();
            style.applyReaderSettings();
            activity.onReaderBookLoaded();
            scheduleReflowAfterLayout(targetChapterIndex, initialAnchorOffset);
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
                    cachedLayoutSignature = null;

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
                    if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
                        targetChapterIndex = ui.clamp(state.restoredChapterIndex, 0, state.chapters.size() - 1);
                    }
                    state.currentChapterIndex = targetChapterIndex;
                    int initialAnchorOffset = resolveInitialAnchorOffset(loadedBook.progressOffset);
                    state.sessionStartOffset = initialAnchorOffset;
                    resetRestoredPosition();
                    style.applyReaderSettings();
                    activity.onReaderBookLoaded();
                    scheduleReflowAfterLayout(targetChapterIndex, initialAnchorOffset);
                    runtime.mainHandler.postDelayed(() -> syncFromWebDav(true), 2000L);
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

    public void persistProgress() {
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        int offset = currentCharOffset();
        ChapterRecord chapter = state.chapters.get(state.currentChapterIndex);
        int chapterOrderIndex = chapter.orderIndex;
        long persistedAt = System.currentTimeMillis();
        state.book.progressIndex = chapterOrderIndex;
        state.book.progressOffset = offset;
        state.book.lastReadAt = persistedAt;
        runtime.executor.execute(() -> {
            runtime.databaseHelper.updateProgress(state.book.id, chapterOrderIndex, offset);
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
                state.book.progressIndex = state.chapters.get(remoteIndex).orderIndex;
                state.book.progressOffset = Math.max(payload.chapterPosition, 0);
                state.book.lastReadAt = payload.chapterTime;
                activity.runOnUiThread(() -> scheduleReflowAfterLayout(remoteIndex, payload.chapterPosition));
            } catch (Exception error) {
                if (!silent) {
                    activity.runOnUiThread(() -> ui.showToast("同步失败: " + error.getMessage()));
                }
            }
        });
    }

    public List<PageSlice> getPagesForChapter(int chapterIndex) {
        ensurePaginationCacheMatchesLayout();
        if (cachedPageSlicesMap.containsKey(chapterIndex)) {
            return cachedPageSlicesMap.get(chapterIndex);
        }
        DisplayChapterText display = buildDisplayChapterText(chapterIndex);
        int pageWidth = getReaderPageTextWidth();
        int regularPageHeight = getRegularReaderPageHeight();
        TextPaint paint = new TextPaint(views.pageBodyCurrent.getPaint());
        List<PageSlice> pages = sanitizePageSlices(ReaderPaginator.paginate(
                display.text,
                paint,
                pageWidth,
                regularPageHeight,
                regularPageHeight,
                views.pageBodyCurrent.getLineSpacingExtra(),
                display.bodyStartIndex
        ));
        cachedPageSlicesMap.put(chapterIndex, pages);
        return pages;
    }

    public int getReaderPageTextWidth() {
        if (views.pageCurrent != null) {
            int width = views.pageCurrent.getWidth() > 0
                    ? views.pageCurrent.getWidth()
                    : views.pageCurrent.getMeasuredWidth();
            if (width > 0) {
                return Math.max(0, width
                        - views.pageCurrent.getPaddingLeft()
                        - views.pageCurrent.getPaddingRight());
            }
        }
        return 0;
    }

    public int getRegularReaderPageHeight() {
        if (views.pageCurrent != null) {
            int height = views.pageCurrent.getHeight() > 0
                    ? views.pageCurrent.getHeight()
                    : views.pageCurrent.getMeasuredHeight();
            if (height > 0) {
                return Math.max(0, height
                        - views.pageCurrent.getPaddingTop()
                        - views.pageCurrent.getPaddingBottom());
            }
        }
        return 0;
    }

    public int getChapterTitleBodyMarginPx() {
        if (views.pageTitleCurrent == null) {
            return ui.dp(16);
        }
        return Math.max(ui.dp(16), Math.round(views.pageTitleCurrent.getTextSize() * 1.5f));
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

    private DisplayChapterText buildDisplayChapterText(int chapterIndex) {
        CharSequence body = buildDisplayBodyText(chapterIndex);
        ChapterRecord chapter = state.chapters.get(chapterIndex);
        if (!runtime.settingsStore.isChapterTitleVisible() || !hasDisplayableChapterTitle(chapter)) {
            return new DisplayChapterText(body, 0);
        }

        SpannableStringBuilder builder = new SpannableStringBuilder();
        String title = chapter.title.trim();
        int titleStart = builder.length();
        builder.append(title);
        int titleEnd = builder.length();
        builder.append('\n');
        int titleParagraphEnd = builder.length();
        builder.setSpan(
                new ReaderTitleSpan(resolveChapterTitleTypeface(), resolveChapterTitleTextSizePx()),
                titleStart,
                titleEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        if ("center".equals(runtime.settingsStore.getChapterTitleAlignment())) {
            builder.setSpan(
                    new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    titleStart,
                    titleParagraphEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }

        int spacerStart = builder.length();
        builder.append(' ');
        int spacerEnd = builder.length();
        builder.append('\n');
        builder.setSpan(
                new ForegroundColorSpan(Color.TRANSPARENT),
                spacerStart,
                spacerEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        builder.setSpan(
                new FixedLineHeightSpan(getChapterTitleBodyMarginPx()),
                spacerStart,
                spacerEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        int bodyStartIndex = builder.length();
        builder.append(body);
        return new DisplayChapterText(builder, bodyStartIndex);
    }

    private CharSequence buildDisplayBodyText(int chapterIndex) {
        String processed = getProcessedChapterText(chapterIndex);
        int indentPx = computeParagraphIndentPx();
        int paragraphSpacingPx = computeParagraphSpacingPx();
        if (processed.isEmpty()) {
            return processed;
        }
        SpannableString spannable = new SpannableString(processed);
        int start = 0;
        int length = processed.length();
        while (start < length) {
            int end = start;
            while (end < length && processed.charAt(end) != '\n') {
                end++;
            }
            int paragraphLimit = end < length ? end + 1 : end;
            if (hasVisibleParagraphText(processed, start, end)) {
                if (indentPx > 0) {
                    spannable.setSpan(
                            new LeadingMarginSpan.Standard(indentPx, 0),
                            start,
                            paragraphLimit,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
                if (end < length && isNextLineVisible(processed, paragraphLimit) && paragraphSpacingPx > 0) {
                    spannable.setSpan(
                            new ParagraphBottomSpacingSpan(paragraphSpacingPx),
                            end,
                            paragraphLimit,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            } else if (end < length) {
                spannable.setSpan(
                        new FixedLineHeightSpan(paragraphSpacingPx),
                        start,
                        paragraphLimit,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
            start = paragraphLimit;
        }
        return spannable;
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
        if (state.chapters.isEmpty()) {
            return 0;
        }
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
        cachedLayoutSignature = null;
        state.totalProcessedBookLength = -1;
    }

    public void scheduleReflowAfterLayout(int chapterIndex, int anchorOffset) {
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        pendingReflowChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        pendingReflowAnchorOffset = Math.max(anchorOffset, 0);
        reflowGeneration++;
        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable);
        runtime.mainHandler.postDelayed(scheduledReflowRunnable, REFLOW_DEBOUNCE_MS);
    }

    public void cancelPendingReflow() {
        reflowGeneration++;
        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable);
    }

    public String readableError(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "未知错误";
        }
        return error.getMessage();
    }

    private void ensurePaginationCacheMatchesLayout() {
        ReaderLayoutSignature currentSignature = captureCurrentLayoutSignature();
        if (currentSignature == null) {
            return;
        }
        if (!currentSignature.equals(cachedLayoutSignature)) {
            cachedPageSlicesMap.clear();
            cachedLayoutSignature = currentSignature;
        }
    }

    private ReaderLayoutSignature captureCurrentLayoutSignature() {
        if (views.pageBodyCurrent == null
                || views.pageCurrent == null
                || views.pageCurrent.isLayoutRequested()) {
            return null;
        }
        int availableWidth = getReaderPageTextWidth();
        int availableHeight = getRegularReaderPageHeight();
        if (availableWidth <= 0 || availableHeight <= 0) {
            return null;
        }
        return new ReaderLayoutSignature(
                availableWidth,
                availableHeight,
                runtime.settingsStore.isChapterTitleVisible(),
                runtime.settingsStore.getChapterTitleAlignment(),
                resolveChapterTitleTextSizePx(),
                views.pageBodyCurrent.getTextSize(),
                runtime.settingsStore.getReaderFontWeight(),
                runtime.settingsStore.getReaderFontFamily(),
                views.pageBodyCurrent.getLineSpacingExtra(),
                views.pageBodyCurrent.getLetterSpacing(),
                runtime.settingsStore.getFirstLineIndentDp(),
                runtime.settingsStore.getParagraphSpacingDp(),
                runtime.settingsStore.getLeftPaddingDp(),
                runtime.settingsStore.getRightPaddingDp(),
                runtime.settingsStore.getTopPaddingDp(),
                runtime.settingsStore.getBottomPaddingDp(),
                state.systemInsetTop,
                state.systemInsetBottom
        );
    }

    private int computeParagraphIndentPx() {
        int indentChars = Math.max(runtime.settingsStore.getFirstLineIndentDp(), 0);
        if (indentChars <= 0 || views.pageBodyCurrent == null) {
            return 0;
        }
        float emWidth = views.pageBodyCurrent.getPaint().measureText("\u3000");
        if (emWidth <= 0f) {
            emWidth = views.pageBodyCurrent.getTextSize();
        }
        return Math.round(emWidth * indentChars);
    }

    private int computeParagraphSpacingPx() {
        return ui.dp(runtime.settingsStore.getParagraphSpacingDp());
    }

    private boolean hasDisplayableChapterTitle(ChapterRecord chapter) {
        return chapter != null
                && chapter.title != null
                && !chapter.title.trim().isEmpty();
    }

    private boolean hasVisibleParagraphText(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isNextLineVisible(String text, int start) {
        int length = text == null ? 0 : text.length();
        if (start >= length) {
            return false;
        }
        int end = start;
        while (end < length && text.charAt(end) != '\n') {
            end++;
        }
        return hasVisibleParagraphText(text, start, end);
    }

    private int resolveInitialAnchorOffset(int defaultOffset) {
        if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
            return Math.max(state.restoredProgressOffset, 0);
        }
        return Math.max(defaultOffset, 0);
    }

    private void resetRestoredPosition() {
        state.restoredChapterIndex = -1;
        state.restoredPageIndex = -1;
        state.restoredProgressOffset = -1;
    }

    private void performScheduledReflow() {
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        final int generation = reflowGeneration;
        final int chapterIndex = ui.clamp(pendingReflowChapterIndex, 0, state.chapters.size() - 1);
        final int anchorOffset = Math.max(pendingReflowAnchorOffset, 0);
        style.applyReaderSettings();
        runAfterNextPageLayout(generation, () -> {
            if (generation != reflowGeneration) {
                return;
            }
            ensurePaginationCacheMatchesLayout();
            navigation.openChapter(chapterIndex, anchorOffset, false, 0);
        });
    }

    private void runAfterNextPageLayout(int generation, Runnable action) {
        if (views.pageBodyCurrent == null) {
            action.run();
            return;
        }
        final boolean[] completed = new boolean[]{false};
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View view,
                    int left,
                    int top,
                    int right,
                    int bottom,
                    int oldLeft,
                    int oldTop,
                    int oldRight,
                    int oldBottom
            ) {
                if (completed[0]) {
                    return;
                }
                completed[0] = true;
                view.removeOnLayoutChangeListener(this);
                if (generation == reflowGeneration) {
                    action.run();
                }
            }
        };
        views.pageBodyCurrent.addOnLayoutChangeListener(listener);
        views.pageCurrent.requestLayout();
        views.pageIncoming.requestLayout();
        views.pageBodyCurrent.requestLayout();
        views.pageBodyCurrent.post(() -> {
            if (completed[0]) {
                return;
            }
            completed[0] = true;
            views.pageBodyCurrent.removeOnLayoutChangeListener(listener);
            if (generation == reflowGeneration) {
                action.run();
            }
        });
    }

    private List<PageSlice> sanitizePageSlices(List<PageSlice> pages) {
        List<PageSlice> sanitized = new ArrayList<>(pages.size());
        for (PageSlice slice : pages) {
            sanitized.add(new PageSlice(
                    slice.start,
                    slice.end,
                    slice.bodyStartInSlice,
                    slice.bodyEndInSlice,
                    slice.text
            ));
        }
        return sanitized;
    }

    private Typeface resolveChapterTitleTypeface() {
        if (views.pageTitleCurrent != null && views.pageTitleCurrent.getTypeface() != null) {
            return views.pageTitleCurrent.getTypeface();
        }
        return views.pageBodyCurrent == null ? null : views.pageBodyCurrent.getTypeface();
    }

    private float resolveChapterTitleTextSizePx() {
        if (views.pageTitleCurrent != null && views.pageTitleCurrent.getTextSize() > 0f) {
            return views.pageTitleCurrent.getTextSize();
        }
        return views.pageBodyCurrent == null ? 0f : views.pageBodyCurrent.getTextSize();
    }

    private static final class DisplayChapterText {
        final CharSequence text;
        final int bodyStartIndex;

        private DisplayChapterText(CharSequence text, int bodyStartIndex) {
            this.text = text == null ? "" : text;
            this.bodyStartIndex = Math.max(0, Math.min(bodyStartIndex, this.text.length()));
        }
    }

    private static final class FixedLineHeightSpan implements LineHeightSpan {
        private final int heightPx;

        private FixedLineHeightSpan(int heightPx) {
            this.heightPx = Math.max(heightPx, 0);
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int v, android.graphics.Paint.FontMetricsInt fontMetricsInt) {
            if (fontMetricsInt == null) {
                return;
            }
            fontMetricsInt.ascent = -heightPx;
            fontMetricsInt.top = fontMetricsInt.ascent;
            fontMetricsInt.descent = 0;
            fontMetricsInt.bottom = 0;
        }
    }

    private static final class ParagraphBottomSpacingSpan implements LineHeightSpan {
        private final int spacingPx;

        private ParagraphBottomSpacingSpan(int spacingPx) {
            this.spacingPx = Math.max(spacingPx, 0);
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int v, android.graphics.Paint.FontMetricsInt fontMetricsInt) {
            if (fontMetricsInt == null || spacingPx <= 0) {
                return;
            }
            fontMetricsInt.descent += spacingPx;
            fontMetricsInt.bottom += spacingPx;
        }
    }
}
