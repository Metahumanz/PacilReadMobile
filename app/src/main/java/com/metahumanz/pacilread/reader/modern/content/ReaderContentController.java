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
import android.view.ViewTreeObserver;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.reader.PageSlice;
import com.metahumanz.pacilread.reader.ReaderPaginator;
import com.metahumanz.pacilread.reader.ReaderParagraphBottomSpacingSpan;
import com.metahumanz.pacilread.reader.ReaderTitleSpan;
import com.metahumanz.pacilread.reader.ReplacementEngine;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator;
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController;
import com.metahumanz.pacilread.sync.WebDavClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderContentController {
    private static final String TAG = "PacilReadReader";
    private static final long REFLOW_DEBOUNCE_MS = 32L;
    private static final long LAYOUT_WAIT_FALLBACK_MS = 180L;
    private static final int MAX_LAYOUT_WAIT_PASSES = 2;

    private static long lastCachedBookId = -1L;
    private static BookRecord cachedBook;
    private static final List<ChapterRecord> cachedChapters = new ArrayList<>();
    private static final List<ReplacementRuleRecord> cachedRules = new ArrayList<>();
    private static final Map<Integer, List<PageSlice>> cachedPageSlicesMap = new HashMap<>();
    private static ReaderLayoutSignature cachedLayoutSignature;
    private static final ExecutorService PROGRESS_UPLOAD_EXECUTOR = Executors.newSingleThreadExecutor();

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
    private boolean initialReflowPending = false;
    private boolean deferReflow = false;
    private boolean initialReflowDeferred = false;
    private boolean isCacheHit = false;
    private Runnable onInitialReflowComplete = null;
    private Boolean lastAppliedDoublePageActive = null;
    // 等待后台分页完成再触发排版（章节索引，-1 表示未等待）
    private int waitingForPaginationChapterIndex = -1;

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

    /** 设置是否推迟初始排版，等动画完成后再执行。 */
    public void setDeferReflow(boolean defer) {
        this.deferReflow = defer;
        if (defer) {
            this.initialReflowDeferred = true;
        }
    }

    /** 执行被推迟的初始排版。动画已完成，跳过 debounce 直接执行。 */
    public void performDeferredInitialReflow(Runnable onComplete) {
        Log.d(TAG, "[时序] performDeferredInitialReflow 被调用 - deferred=" + initialReflowDeferred);
        deferReflow = false;
        onInitialReflowComplete = onComplete;
        if (!initialReflowDeferred) {
            Log.d(TAG, "[时序] performDeferredInitialReflow 提前返回 - initialReflowDeferred=false");
            return;
        }
        initialReflowDeferred = false;
        if (state.book == null || state.chapters.isEmpty()) {
            Log.d(TAG, "[时序] performDeferredInitialReflow 提前返回 - book/chapters 未就绪");
            return;
        }
        int chapterIndex = state.currentChapterIndex;
        int anchorOffset = state.sessionStartOffset;
        pendingReflowChapterIndex = chapterIndex;
        pendingReflowAnchorOffset = anchorOffset;
        initialReflowPending = true;
        reflowGeneration++;
        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable);
        Log.d(TAG, "[时序] 立即触发 performScheduledReflow - chapter=" + chapterIndex + " gen=" + reflowGeneration);
        runtime.mainHandler.post(scheduledReflowRunnable);
    }

    /** 从 loadBook UI 回调调用，尽早启动后台分页（不等动画快照）。
     *  用 post 确保 view layout 完成后再捕获尺寸。 */
    private void startBackgroundPagination(int chapterIndex) {
        View anchor = views.pageCurrent != null ? views.pageCurrent : views.pageBodyCurrent;
        if (anchor == null) return;
        anchor.post(() -> startBackgroundPaginationAfterLayout(chapterIndex));
    }

    private void startBackgroundPaginationAfterLayout(int chapterIndex) {
        int pageWidth = getReaderPageTextWidth();
        int pageHeight = getRegularReaderPageHeight();
        if (pageWidth <= 0 || pageHeight <= 0) {
            Log.d(TAG, "[时序] 后台分页尺寸无效 w=" + pageWidth + " h=" + pageHeight + " - 重试");
            View anchor = views.pageCurrent != null ? views.pageCurrent : views.pageBodyCurrent;
            if (anchor != null) {
                anchor.post(() -> startBackgroundPaginationAfterLayoutRetry(chapterIndex));
            }
            return;
        }
        Log.d(TAG, "[时序] 后台分页尺寸就绪 w=" + pageWidth + " h=" + pageHeight + " - 启动");
        ReaderLayoutSignature sig = captureCurrentLayoutSignature();
        if (sig != null) {
            cachedLayoutSignature = sig;
        }
        launchBackgroundPagination(chapterIndex, pageWidth, pageHeight);
    }

    private void startBackgroundPaginationAfterLayoutRetry(int chapterIndex) {
        int pageWidth = getReaderPageTextWidth();
        int pageHeight = getRegularReaderPageHeight();
        if (pageWidth <= 0 || pageHeight <= 0) return;
        ReaderLayoutSignature sig = captureCurrentLayoutSignature();
        if (sig != null) {
            cachedLayoutSignature = sig;
        }
        launchBackgroundPagination(chapterIndex, pageWidth, pageHeight);
    }

    private void launchBackgroundPagination(int chapterIndex, int pageWidth, int pageHeight) {
        synchronized (cachedPageSlicesMap) {
            if (cachedPageSlicesMap.containsKey(chapterIndex)) return;
        }
        Log.d(TAG, "[时序] 后台分页开始执行 - chapter=" + chapterIndex + " w=" + pageWidth + " h=" + pageHeight);
        final long startTime = System.currentTimeMillis();
        float lineSpacing = views.pageBodyCurrent.getLineSpacingExtra();
        TextPaint basePaint = new TextPaint(views.pageBodyCurrent.getPaint());
        int indentPx = computeParagraphIndentPx();
        Typeface titleTypeface = resolveChapterTitleTypeface();
        float titleTextSize = resolveChapterTitleTextSizePx();
        int titleMargin = getChapterTitleBodyMarginPx();
        ReaderLayoutSignature sig = captureCurrentLayoutSignature();

        runtime.executor.execute(() -> {
            PaginationSnapshot snapshot = new PaginationSnapshot(
                    pageWidth, pageHeight, lineSpacing, basePaint, sig,
                    titleTypeface, titleTextSize, titleMargin, indentPx);
            String processed = getProcessedChapterText(chapterIndex);
            if (processed.isEmpty()) return;
            DisplayChapterText display = buildDisplayChapterTextForBackground(chapterIndex, snapshot);
            TextPaint paint = new TextPaint(snapshot.basePaint);
            List<PageSlice> pages = sanitizePageSlices(ReaderPaginator.paginate(
                    display.text, paint,
                    snapshot.pageWidth, snapshot.regularPageHeight, snapshot.regularPageHeight,
                    snapshot.lineSpacingExtra, display.bodyStartIndex));
            final long elapsed = System.currentTimeMillis() - startTime;
            Log.d(TAG, "[时序] 后台分页完成 - chapter=" + chapterIndex + " 页数=" + pages.size() + " 耗时=" + elapsed + "ms");
            final ReaderLayoutSignature capturedSig = sig;
            activity.runOnUiThread(() -> {
                synchronized (cachedPageSlicesMap) {
                    cachedPageSlicesMap.put(chapterIndex, pages);
                }
                // 更新布局签名，防止 ensurePaginationCacheMatchesLayout 因签名不匹配清空缓存
                ReaderLayoutSignature currentSig = captureCurrentLayoutSignature();
                if (currentSig != null) {
                    cachedLayoutSignature = currentSig;
                }
                // 初始加载期间分页完成：主动触发排版，让文字与背景一起缩放出现
                boolean isInitialLoad = initialReflowDeferred;
                boolean isWaiting = waitingForPaginationChapterIndex == chapterIndex;
                if (isInitialLoad || isWaiting) {
                    Log.d(TAG, "[时序] 后台分页触发reflow - chapter=" + chapterIndex
                            + " deferred=" + isInitialLoad + " waiting=" + isWaiting);
                    if (isWaiting) {
                        waitingForPaginationChapterIndex = -1;
                        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable);
                    }
                    runtime.mainHandler.post(scheduledReflowRunnable);
                }
            });
        });
    }

    public boolean isCacheHit() {
        return isCacheHit;
    }

    public void loadBook() {
        if (lastCachedBookId == state.bookId && cachedBook != null && !cachedChapters.isEmpty()) {
            isCacheHit = true;
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
            if (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0) {
                targetChapterIndex = ui.clamp(
                        navigation.chapterIndexFromOrder(state.requestedChapterOrderIndex),
                        0,
                        state.chapters.size() - 1
                );
            }
            if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
                targetChapterIndex = ui.clamp(state.restoredChapterIndex, 0, state.chapters.size() - 1);
            }
            state.currentChapterIndex = targetChapterIndex;
            int initialAnchorOffset = resolveInitialAnchorOffset(state.book.progressOffset);
            state.sessionStartOffset = initialAnchorOffset;
            if (!deferReflow) {
                style.applyReaderSettings();
            }
            activity.onReaderBookLoaded();
            chrome.updateReaderHud();
            Log.d(TAG, "[时序] loadBook 缓存命中 UI回调 - chapter=" + targetChapterIndex);
            if (deferReflow) {
                initialReflowDeferred = true;
                // 清除旧缓存，后台分页会用 layout 就绪后的尺寸重新构建
                cachedPageSlicesMap.clear();
                cachedLayoutSignature = null;
                prewarmChapterText(targetChapterIndex);
                prewarmAdjacentChapters(targetChapterIndex);
                Log.d(TAG, "[时序] 启动后台分页(缓存命中) - chapter=" + targetChapterIndex);
                startBackgroundPagination(targetChapterIndex);
            } else {
                scheduleInitialReflowAfterLayout(targetChapterIndex, initialAnchorOffset);
            }
            scheduleRemoteProgressSync();
            return;
        }

        runtime.executor.execute(() -> {
            try {
                BookRecord loadedBook = runtime.databaseHelper.getBook(state.bookId);
                List<ChapterRecord> loadedChapters = runtime.databaseHelper.getChapters(state.bookId, false);
                List<ReplacementRuleRecord> loadedRules = runtime.databaseHelper.getReplacementRules(state.bookId);

                // 在同一个后台任务中提前加载目标章节正文+替换处理，消除第二次 executor 调度的空隙
                String prewarmedText = null;
                int prewarmChapterIndex = -1;
                if (loadedBook != null && !loadedChapters.isEmpty()) {
                    int targetIndex = ui.clamp(
                            navigation.chapterIndexFromOrder(loadedBook.progressIndex), 0, loadedChapters.size() - 1);
                    if (targetIndex >= 0 && targetIndex < loadedChapters.size()) {
                        ChapterRecord targetChapter = loadedChapters.get(targetIndex);
                        ChapterRecord fullChapter = runtime.databaseHelper.getChapterContent(targetChapter.id);
                        if (fullChapter != null) {
                            targetChapter.bodyText = fullChapter.bodyText;
                        }
                        String body = targetChapter.bodyText == null ? "" : targetChapter.bodyText;
                        prewarmedText = ReplacementEngine.apply(body, loadedRules);
                        prewarmChapterIndex = targetIndex;
                    }
                }
                final String finalPrewarmedText = prewarmedText;
                final int finalPrewarmIndex = prewarmChapterIndex;

                activity.runOnUiThread(() -> {
                    if (loadedBook == null || loadedChapters.isEmpty()) {
                        ui.showToast("书籍不存在或内容为空");
                        activity.finishReaderActivity();
                        return;
                    }

                    lastCachedBookId = state.bookId;
                    isCacheHit = false;
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
                    if (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0) {
                        targetChapterIndex = ui.clamp(
                                navigation.chapterIndexFromOrder(state.requestedChapterOrderIndex),
                                0,
                                state.chapters.size() - 1
                        );
                    }
                    if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
                        targetChapterIndex = ui.clamp(state.restoredChapterIndex, 0, state.chapters.size() - 1);
                    }
                    state.currentChapterIndex = targetChapterIndex;
                    int initialAnchorOffset = resolveInitialAnchorOffset(loadedBook.progressOffset);
                    state.sessionStartOffset = initialAnchorOffset;

                    // 预先注入已处理好的正文到缓存，后续 prewarm/prefetch 只需分页
                    if (finalPrewarmedText != null && finalPrewarmIndex >= 0) {
                        synchronized (processedChapterLruCache) {
                            processedChapterLruCache.put(finalPrewarmIndex, finalPrewarmedText);
                        }
                        synchronized (processedChapterLengthCache) {
                            processedChapterLengthCache.put(finalPrewarmIndex, finalPrewarmedText.length());
                        }
                    }

                    if (!deferReflow) {
                        style.applyReaderSettings();
                    }
                    activity.onReaderBookLoaded();
                    chrome.updateReaderHud();
                    Log.d(TAG, "[时序] loadBook 缓存未命中 UI回调 - chapter=" + targetChapterIndex);
                    if (deferReflow) {
                        initialReflowDeferred = true;
                        // 清除旧缓存，后台分页会用 layout 就绪后的尺寸重新构建
                        cachedPageSlicesMap.clear();
                        cachedLayoutSignature = null;
                        // 正文已缓存，后台只需分页（不含解压+替换）
                        prewarmChapterText(targetChapterIndex);
                        prewarmAdjacentChapters(targetChapterIndex);
                        Log.d(TAG, "[时序] 启动后台分页(缓存未命中) - chapter=" + targetChapterIndex);
                        startBackgroundPagination(targetChapterIndex);
                    } else {
                        scheduleInitialReflowAfterLayout(targetChapterIndex, initialAnchorOffset);
                    }
                    scheduleRemoteProgressSync();
                });
            } catch (Exception error) {
                Log.e(TAG, "Failed to load reader state", error);
                activity.runOnUiThread(() -> {
                    ui.showToast("打开书籍失败: " + readableError(error));
                    activity.finishReaderActivity();
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
        // 数据库更新必须在主线程同步完成，防止 onDestroy 中 executor.shutdownNow() 中断写入
        runtime.databaseHelper.updateProgress(state.book.id, chapterOrderIndex, offset);
        if (runtime.settingsStore.isWebDavEnabled()) {
            BookRecord bookSnapshot = snapshotBookForProgressUpload(state.book);
            ChapterRecord chapterSnapshot = snapshotChapterForProgressUpload(chapter);
            WebDavClient webDavClient = runtime.webDavClient;
            PROGRESS_UPLOAD_EXECUTOR.execute(() -> {
                try {
                    webDavClient.ensureProgressDirectory();
                    webDavClient.uploadProgress(bookSnapshot, chapterSnapshot, offset);
                } catch (Exception error) {
                    Log.w(TAG, "Failed to upload reader progress", error);
                }
            });
        }
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

    private void scheduleRemoteProgressSync() {
        runtime.mainHandler.postDelayed(() -> syncFromWebDav(true), 2000L);
    }

    private BookRecord snapshotBookForProgressUpload(BookRecord source) {
        BookRecord snapshot = new BookRecord();
        snapshot.id = source.id;
        snapshot.title = source.title;
        snapshot.author = source.author;
        snapshot.readingStatsKey = source.readingStatsKey;
        snapshot.progressIndex = source.progressIndex;
        snapshot.progressOffset = source.progressOffset;
        snapshot.lastReadAt = source.lastReadAt;
        return snapshot;
    }

    private ChapterRecord snapshotChapterForProgressUpload(ChapterRecord source) {
        ChapterRecord snapshot = new ChapterRecord();
        snapshot.id = source.id;
        snapshot.bookId = source.bookId;
        snapshot.title = source.title;
        snapshot.orderIndex = source.orderIndex;
        return snapshot;
    }

    public List<PageSlice> getPagesForChapter(int chapterIndex) {
        ensurePaginationCacheMatchesLayout();
        if (cachedPageSlicesMap.containsKey(chapterIndex)) {
            Log.d(TAG, "[时序] getPagesForChapter 缓存命中 - chapter=" + chapterIndex);
            return cachedPageSlicesMap.get(chapterIndex);
        }
        Log.w(TAG, "[时序] getPagesForChapter 缓存未命中! 主线程分页 - chapter=" + chapterIndex);
        final long t0 = System.currentTimeMillis();
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
        Log.w(TAG, "[时序] 主线程分页完成 - chapter=" + chapterIndex + " 页数=" + pages.size() + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        return pages;
    }

    public int getReaderPageTextWidth() {
        if (views.pageCurrent != null) {
            int width = views.pageCurrent.getWidth() > 0
                    ? views.pageCurrent.getWidth()
                    : views.pageCurrent.getMeasuredWidth();
            if (width > 0) {
                int contentWidth = Math.max(0, width
                        - views.pageCurrent.getPaddingLeft()
                        - views.pageCurrent.getPaddingRight());
                if (isDoublePageActive()) {
                    int gutterWidth = views.pageCurrentGutter == null
                            ? ui.dp(22)
                            : Math.max(views.pageCurrentGutter.getWidth(), ui.dp(22));
                    return Math.max(0, (contentWidth - gutterWidth) / 2);
                }
                return contentWidth;
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

    public boolean isDoublePageActive() {
        return ReaderDisplayModeHelper.isDoublePageActive(
                activity,
                runtime.settingsStore,
                views.pageStage == null ? 0 : views.pageStage.getWidth(),
                views.pageStage == null ? 0 : views.pageStage.getHeight()
        );
    }

    public int pagesPerScreen() {
        return isDoublePageActive() ? 2 : 1;
    }

    public String getProcessedChapterText(int chapterIndex) {
        synchronized (processedChapterLruCache) {
            String cached = processedChapterLruCache.get(chapterIndex);
            if (cached != null) {
                return cached;
            }
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
        synchronized (processedChapterLruCache) {
            processedChapterLruCache.put(chapterIndex, processed);
        }
        synchronized (processedChapterLengthCache) {
            processedChapterLengthCache.put(chapterIndex, processed.length());
        }
        return processed;
    }

    /** 在后台预加载章节正文+替换处理，让动画结束后的排版能直接命中缓存。 */
    /** 在后台预加载章节正文+替换处理，如果排版参数快照已就绪则同时异步分页。 */
    public void prewarmChapterText(int chapterIndex) {
        if (state.book == null || state.chapters.isEmpty()) return;
        if (chapterIndex < 0 || chapterIndex >= state.chapters.size()) return;
        int safeIndex = Math.max(0, Math.min(chapterIndex, state.chapters.size() - 1));
        runtime.executor.execute(() -> {
            // 如果正文已缓存（例如 DB 查询时已预处理），跳过加载直接分页
            boolean textAlreadyCached;
            synchronized (processedChapterLruCache) {
                textAlreadyCached = processedChapterLruCache.get(safeIndex) != null;
            }
            if (!textAlreadyCached) {
                ChapterRecord chapter = state.chapters.get(safeIndex);
                if (chapter == null) return;
                String body;
                if (chapter.bodyText == null) {
                    ChapterRecord fullChapter = runtime.databaseHelper.getChapterContent(chapter.id);
                    if (fullChapter != null) {
                        chapter.bodyText = fullChapter.bodyText;
                        chapter.bodyHtml = fullChapter.bodyHtml;
                    }
                }
                body = chapter.bodyText == null ? "" : chapter.bodyText;
                String processed = ReplacementEngine.apply(body, state.replacementRules);
                synchronized (processedChapterLruCache) {
                    processedChapterLruCache.put(safeIndex, processed);
                }
                synchronized (processedChapterLengthCache) {
                    processedChapterLengthCache.put(safeIndex, processed.length());
                }
            }

            // 如果排版参数快照已就绪，继续异步分页
            PaginationSnapshot snapshot = cachedPaginationSnapshot;
            if (snapshot == null || snapshot.pageWidth <= 0) return;
            if (cachedPageSlicesMap.containsKey(safeIndex)) return;

            synchronized (cachedPageSlicesMap) {
                if (cachedPageSlicesMap.containsKey(safeIndex)) return;
            }
            DisplayChapterText display = buildDisplayChapterTextForBackground(safeIndex, snapshot);
            TextPaint paint = new TextPaint(snapshot.basePaint);
            List<PageSlice> pages = sanitizePageSlices(ReaderPaginator.paginate(
                    display.text, paint,
                    snapshot.pageWidth, snapshot.regularPageHeight, snapshot.regularPageHeight,
                    snapshot.lineSpacingExtra, display.bodyStartIndex));

            final List<PageSlice> finalPages = pages;
            final ReaderLayoutSignature capturedSig = snapshot.layoutSignature;
            activity.runOnUiThread(() -> {
                if (capturedSig != null && capturedSig.equals(cachedLayoutSignature)) {
                    synchronized (cachedPageSlicesMap) {
                        cachedPageSlicesMap.put(safeIndex, finalPages);
                    }
                }
            });
        });
    }

    /** 排版快照就绪后调用，补发被跳过的后台分页。
     *  仅供 ModernReaderActivity.startFluidEnterAnimation 调用。 */
    public void prewarmChapterTextAfterSnapshot() {
        if (!initialReflowDeferred || state.book == null || state.chapters.isEmpty()) return;
        int chapterIndex = state.currentChapterIndex;
        if (chapterIndex < 0 || chapterIndex >= state.chapters.size()) return;
        synchronized (cachedPageSlicesMap) {
            if (cachedPageSlicesMap.containsKey(chapterIndex)) return;
        }
        prewarmChapterText(chapterIndex);
    }

    /** 后台预加载相邻章节正文，让翻页时无需等待解压+替换处理。 */
    private void prewarmAdjacentChapters(int currentChapterIndex) {
        int nextIndex = currentChapterIndex + 1;
        if (nextIndex < 0 || nextIndex >= state.chapters.size()) return;
        runtime.executor.execute(() -> {
            synchronized (processedChapterLruCache) {
                if (processedChapterLruCache.get(nextIndex) != null) return;
            }
            ChapterRecord chapter = state.chapters.get(nextIndex);
            if (chapter == null) return;
            if (chapter.bodyText == null) {
                ChapterRecord fullChapter = runtime.databaseHelper.getChapterContent(chapter.id);
                if (fullChapter != null) {
                    chapter.bodyText = fullChapter.bodyText;
                    chapter.bodyHtml = fullChapter.bodyHtml;
                }
            }
            String body = chapter.bodyText == null ? "" : chapter.bodyText;
            String processed = ReplacementEngine.apply(body, state.replacementRules);
            synchronized (processedChapterLruCache) {
                processedChapterLruCache.put(nextIndex, processed);
            }
            synchronized (processedChapterLengthCache) {
                processedChapterLengthCache.put(nextIndex, processed.length());
            }
        });
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
                            new ReaderParagraphBottomSpacingSpan(paragraphSpacingPx),
                            end,
                            paragraphLimit,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            } else if (end < length && paragraphSpacingPx > 0) {
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
        ChapterRecord ch = (chapterIndex >= 0 && chapterIndex < state.chapters.size())
                ? state.chapters.get(chapterIndex) : null;
        // 正文已加载：直接用字符串长度，不触发解压
        if (ch != null && ch.bodyText != null && !ch.bodyText.isEmpty()) {
            int length = ch.bodyText.length();
            processedChapterLengthCache.put(chapterIndex, length);
            return length;
        }
        // 正文未加载：用元数据 bodyTextSize 估算（UTF-8 中文约 3 字节/字符）
        // 避免为计算全书进度解压全部章节（大书可达数千章）
        if (ch != null && ch.bodyTextSize > 0) {
            int estimated = (int) Math.max(1, ch.bodyTextSize / 3);
            processedChapterLengthCache.put(chapterIndex, estimated);
            return estimated;
        }
        // 回退：解压加载
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

    public float bookProgressPercentFor(int chapterIndex, int chapterOffset) {
        if (state.chapters.isEmpty()) {
            return 0f;
        }
        int total = Math.max(getTotalProcessedBookLength(), 1);
        int safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        int completed = 0;
        for (int i = 0; i < safeChapterIndex; i++) {
            completed += getProcessedChapterLength(i);
        }
        int safeOffset = ui.clamp(chapterOffset, 0, getProcessedChapterLength(safeChapterIndex));
        return Math.max(0f, Math.min(100f, (completed + safeOffset) * 100f / total));
    }

    public String buildBookmarkSummary(int chapterIndex, int chapterOffset, int maxChars) {
        if (state.chapters.isEmpty()) {
            return "";
        }
        int safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        String text = getProcessedChapterText(safeChapterIndex);
        if (text == null || text.isBlank()) {
            return "";
        }
        int safeOffset = ui.clamp(chapterOffset, 0, text.length());
        int end = Math.min(text.length(), safeOffset + Math.max(maxChars, 24));
        String summary = text.substring(safeOffset, end).replaceAll("\\s+", " ").trim();
        if (summary.isEmpty() && safeOffset > 0) {
            int start = Math.max(0, safeOffset - Math.max(maxChars, 24));
            summary = text.substring(start, safeOffset).replaceAll("\\s+", " ").trim();
        }
        return summary;
    }

    public void clearPageCache() {
        cachedPageSlicesMap.clear();
        cachedLayoutSignature = null;
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
        // 初始加载期间屏蔽系统触发的 reflow（insets 变化、尺寸变化），
        // 防止动画期间不必要的 layout + 分页阻塞主线程导致掉帧
        if (deferReflow || initialReflowDeferred) {
            return;
        }
        if (initialReflowPending) {
            reflowGeneration++;
            runtime.mainHandler.removeCallbacks(scheduledReflowRunnable);
            runtime.mainHandler.postDelayed(scheduledReflowRunnable, REFLOW_DEBOUNCE_MS);
            return;
        }
        scheduleReflowAfterLayoutInternal(chapterIndex, anchorOffset);
    }

    private void scheduleInitialReflowAfterLayout(int chapterIndex, int anchorOffset) {
        initialReflowPending = true;
        scheduleReflowAfterLayoutInternal(chapterIndex, anchorOffset);
    }

    private void scheduleReflowAfterLayoutInternal(int chapterIndex, int anchorOffset) {
        if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
            pendingReflowChapterIndex = ui.clamp(state.restoredChapterIndex, 0, state.chapters.size() - 1);
            pendingReflowAnchorOffset = Math.max(state.restoredProgressOffset, 0);
        } else if (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0) {
            pendingReflowChapterIndex = ui.clamp(
                    navigation.chapterIndexFromOrder(state.requestedChapterOrderIndex),
                    0,
                    state.chapters.size() - 1
            );
            pendingReflowAnchorOffset = Math.max(state.requestedChapterOffset, 0);
        } else {
            pendingReflowChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
            pendingReflowAnchorOffset = Math.max(anchorOffset, 0);
        }
        reflowGeneration++;
        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable);
        runtime.mainHandler.postDelayed(scheduledReflowRunnable, REFLOW_DEBOUNCE_MS);
    }

    public void cancelPendingReflow() {
        initialReflowPending = false;
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
                state.systemInsetBottom,
                isDoublePageActive()
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
        if (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0) {
            return Math.max(state.requestedChapterOffset, 0);
        }
        return Math.max(defaultOffset, 0);
    }

    private boolean hasInitialPositionRequest() {
        return (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0)
                || (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0);
    }

    private void resetInitialPositionRequest() {
        state.restoredChapterIndex = -1;
        state.restoredPageIndex = -1;
        state.restoredProgressOffset = -1;
        state.requestedChapterOrderIndex = -1;
        state.requestedChapterOffset = -1;
    }

    private void performScheduledReflow() {
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        final int generation = reflowGeneration;
        // pendingReflowChapterIndex=-1 表示尚未通过 performDeferredInitialReflow 设置，
        // 此时使用 loadBook 已确定的 currentChapterIndex（后台分页可能在动画期间就触发 reflow）
        final int chapterIndex = pendingReflowChapterIndex >= 0
                ? ui.clamp(pendingReflowChapterIndex, 0, state.chapters.size() - 1)
                : ui.clamp(state.currentChapterIndex, 0, state.chapters.size() - 1);
        final int anchorOffset = pendingReflowChapterIndex >= 0
                ? Math.max(pendingReflowAnchorOffset, 0)
                : state.sessionStartOffset;
        final boolean shouldResetInitialPosition = hasInitialPositionRequest();
        final boolean completingInitialReflow = initialReflowPending;
        final Boolean previousDoublePageActive = lastAppliedDoublePageActive;
        Log.d(TAG, "[时序] performScheduledReflow 开始 - chapter=" + chapterIndex + " gen=" + generation + " initialReflow=" + completingInitialReflow);
        // 仅在初始加载早期跳过（动画期间），deferred reflow 时需调用以获取正确的系统 insets
        if (!initialReflowDeferred) {
            style.applyReaderSettings();
        }
        final boolean nextDoublePageActive = isDoublePageActive();
        final boolean doublePageStateChanged = previousDoublePageActive == null
                || previousDoublePageActive != nextDoublePageActive;
        runAfterNextPageLayout(generation, () -> {
            if (generation != reflowGeneration) {
                Log.d(TAG, "[时序] performScheduledReflow 回调被丢弃 - gen不匹配");
                return;
            }
            Log.d(TAG, "[时序] performScheduledReflow layout就绪 - 检查缓存");
            if (doublePageStateChanged) {
                clearPageCache();
                paging.invalidatePreparedPagingSnapshots();
            }
            ensurePaginationCacheMatchesLayout();
            boolean cacheHit = cachedPageSlicesMap.containsKey(chapterIndex);
            Log.d(TAG, "[时序] 分页缓存检查 - chapter=" + chapterIndex + " hit=" + cacheHit + " initialReflow=" + completingInitialReflow);
            if (completingInitialReflow && !cacheHit) {
                Log.d(TAG, "[时序] 初始排版缓存未命中，等待后台分页...");
                startBackgroundPagination(chapterIndex);
                waitingForPaginationChapterIndex = chapterIndex;
                lastAppliedDoublePageActive = isDoublePageActive();
                final int savedGeneration = generation;
                runtime.mainHandler.postDelayed(() -> {
                    if (waitingForPaginationChapterIndex == chapterIndex) {
                        Log.d(TAG, "[时序] 等待后台分页超时(800ms)，回退主线程分页");
                        waitingForPaginationChapterIndex = -1;
                        if (savedGeneration == reflowGeneration) {
                            performScheduledReflow();
                        }
                    }
                }, 800L);
                return;
            }
            navigation.openChapter(chapterIndex, anchorOffset, false, 0);
            if (shouldResetInitialPosition) {
                resetInitialPositionRequest();
            }
            if (completingInitialReflow) {
                Log.d(TAG, "[时序] 初始排版完成 - 触发前景淡入");
                initialReflowPending = false;
                if (onInitialReflowComplete != null) {
                    Runnable callback = onInitialReflowComplete;
                    onInitialReflowComplete = null;
                    callback.run();
                }
            }
            lastAppliedDoublePageActive = isDoublePageActive();
        });
    }

    private void runAfterNextPageLayout(int generation, Runnable action) {
        View target = views.pageCurrent == null ? views.pageBodyCurrent : views.pageCurrent;
        if (target == null) {
            action.run();
            return;
        }
        waitForNextPagePreDraw(generation, target, 0, action);
    }

    private void waitForNextPagePreDraw(int generation, View target, int pass, Runnable action) {
        if (generation != reflowGeneration) {
            return;
        }
        if (pass > 0 && isPaginationLayoutReady()) {
            action.run();
            return;
        }
        final boolean[] completed = new boolean[]{false};
        final ViewTreeObserver.OnPreDrawListener[] listenerRef = new ViewTreeObserver.OnPreDrawListener[1];
        Runnable complete = () -> {
            if (completed[0]) {
                return;
            }
            completed[0] = true;
            removePreDrawListener(target, listenerRef[0]);
            if (generation != reflowGeneration) {
                return;
            }
            if (isPaginationLayoutReady() || pass >= MAX_LAYOUT_WAIT_PASSES) {
                action.run();
                return;
            }
            requestPageLayerLayout();
            waitForNextPagePreDraw(generation, target, pass + 1, action);
        };
        listenerRef[0] = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                target.post(complete);
                return true;
            }
        };
        target.getViewTreeObserver().addOnPreDrawListener(listenerRef[0]);
        requestPageLayerLayout();
        target.postDelayed(complete, LAYOUT_WAIT_FALLBACK_MS);
    }

    private void requestPageLayerLayout() {
        requestLayout(views.pageCurrent);
        requestLayout(views.pageIncoming);
        requestLayout(views.pageCurrentLeftPane);
        requestLayout(views.pageCurrentRightPane);
        requestLayout(views.pageCurrentGutter);
        requestLayout(views.pageIncomingLeftPane);
        requestLayout(views.pageIncomingRightPane);
        requestLayout(views.pageIncomingGutter);
        requestLayout(views.pageBodyCurrent);
        requestLayout(views.pageBodyCurrentRight);
        requestLayout(views.pageBodyIncoming);
        requestLayout(views.pageBodyIncomingRight);
    }

    private void requestLayout(View view) {
        if (view != null) {
            view.requestLayout();
        }
    }

    private void removePreDrawListener(View target, ViewTreeObserver.OnPreDrawListener listener) {
        if (target == null || listener == null) {
            return;
        }
        ViewTreeObserver observer = target.getViewTreeObserver();
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(listener);
        }
    }

    private boolean isPaginationLayoutReady() {
        if (views.pageCurrent == null || views.pageBodyCurrent == null) {
            return true;
        }
        if (views.pageCurrent.isLayoutRequested() || views.pageBodyCurrent.isLayoutRequested()) {
            return false;
        }
        int pageWidth = views.pageCurrent.getWidth();
        int bodyWidth = views.pageBodyCurrent.getWidth();
        int bodyHeight = views.pageBodyCurrent.getHeight();
        if (pageWidth <= 0 || bodyWidth <= 0 || bodyHeight <= 0) {
            return false;
        }
        int contentWidth = Math.max(0, pageWidth
                - views.pageCurrent.getPaddingLeft()
                - views.pageCurrent.getPaddingRight());
        if (contentWidth <= 0) {
            return false;
        }
        boolean doublePageActive = isDoublePageActive();
        int expectedVisibility = doublePageActive ? View.VISIBLE : View.GONE;
        if (!hasVisibility(views.pageCurrentRightPane, expectedVisibility)
                || !hasVisibility(views.pageCurrentGutter, expectedVisibility)) {
            return false;
        }
        int tolerance = ui.dp(4);
        if (!doublePageActive) {
            return bodyWidth >= contentWidth - tolerance;
        }
        int gutterWidth = views.pageCurrentGutter == null
                ? ui.dp(22)
                : Math.max(views.pageCurrentGutter.getWidth(), ui.dp(22));
        int expectedPaneWidth = Math.max(0, (contentWidth - gutterWidth) / 2);
        return Math.abs(bodyWidth - expectedPaneWidth) <= tolerance;
    }

    private boolean hasVisibility(View view, int visibility) {
        return view == null || view.getVisibility() == visibility;
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

    /** 主线程捕获的排版参数快照，供后台线程异步分页使用。 */
    private static final class PaginationSnapshot {
        final int pageWidth;
        final int regularPageHeight;
        final float lineSpacingExtra;
        final TextPaint basePaint;
        final ReaderLayoutSignature layoutSignature;
        final Typeface titleTypeface;
        final float titleTextSizePx;
        final int titleBodyMarginPx;
        final int indentPx;

        PaginationSnapshot(int pageWidth, int regularPageHeight, float lineSpacingExtra,
                           TextPaint basePaint, ReaderLayoutSignature layoutSignature,
                           Typeface titleTypeface, float titleTextSizePx, int titleBodyMarginPx,
                           int indentPx) {
            this.pageWidth = pageWidth;
            this.regularPageHeight = regularPageHeight;
            this.lineSpacingExtra = lineSpacingExtra;
            this.basePaint = new TextPaint(basePaint);
            this.layoutSignature = layoutSignature;
            this.titleTypeface = titleTypeface;
            this.titleTextSizePx = titleTextSizePx;
            this.titleBodyMarginPx = titleBodyMarginPx;
            this.indentPx = indentPx;
        }
    }

    private volatile PaginationSnapshot cachedPaginationSnapshot;

    public void capturePaginationSnapshot() {
        if (views.pageBodyCurrent == null || views.pageBodyCurrent.getWidth() <= 0) return;
        cachedPaginationSnapshot = new PaginationSnapshot(
                getReaderPageTextWidth(),
                getRegularReaderPageHeight(),
                views.pageBodyCurrent.getLineSpacingExtra(),
                views.pageBodyCurrent.getPaint(),
                captureCurrentLayoutSignature(),
                resolveChapterTitleTypeface(),
                resolveChapterTitleTextSizePx(),
                getChapterTitleBodyMarginPx(),
                computeParagraphIndentPx()
        );
    }

    /** 后台线程安全版 buildDisplayChapterText，使用快照中的 view 参数。 */
    private DisplayChapterText buildDisplayChapterTextForBackground(int chapterIndex, PaginationSnapshot snapshot) {
        CharSequence body = buildDisplayBodyTextForBackground(chapterIndex, snapshot.indentPx);
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
                new ReaderTitleSpan(snapshot.titleTypeface, snapshot.titleTextSizePx),
                titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if ("center".equals(runtime.settingsStore.getChapterTitleAlignment())) {
            builder.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    titleStart, titleParagraphEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        int spacerStart = builder.length();
        builder.append(' ');
        int spacerEnd = builder.length();
        builder.append('\n');
        builder.setSpan(new ForegroundColorSpan(Color.TRANSPARENT),
                spacerStart, spacerEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new FixedLineHeightSpan(snapshot.titleBodyMarginPx),
                spacerStart, spacerEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int bodyStartIndex = builder.length();
        builder.append(body);
        return new DisplayChapterText(builder, bodyStartIndex);
    }

    /** 后台线程安全版 buildDisplayBodyText，getProcessedChapterText 已加同步锁。 */
    private CharSequence buildDisplayBodyTextForBackground(int chapterIndex, int indentPx) {
        String processed = getProcessedChapterText(chapterIndex);
        int paragraphSpacingPx = computeParagraphSpacingPx();
        if (processed.isEmpty()) return processed;

        SpannableString spannable = new SpannableString(processed);
        int start = 0;
        int length = processed.length();
        while (start < length) {
            int end = start;
            while (end < length && processed.charAt(end) != '\n') end++;
            int paragraphLimit = end < length ? end + 1 : end;
            if (hasVisibleParagraphText(processed, start, end)) {
                if (indentPx > 0) {
                    spannable.setSpan(new LeadingMarginSpan.Standard(indentPx, 0),
                            start, paragraphLimit, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (end < length && isNextLineVisible(processed, paragraphLimit) && paragraphSpacingPx > 0) {
                    spannable.setSpan(new ReaderParagraphBottomSpacingSpan(paragraphSpacingPx),
                            end, paragraphLimit, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else if (end < length && paragraphSpacingPx > 0) {
                spannable.setSpan(new FixedLineHeightSpan(paragraphSpacingPx),
                        start, paragraphLimit, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = paragraphLimit;
        }
        return spannable;
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

}
