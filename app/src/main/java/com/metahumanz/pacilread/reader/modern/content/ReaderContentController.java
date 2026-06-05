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
import com.metahumanz.pacilread.sync.WebDavProgressSyncCoordinator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class ReaderContentController {
    private static final String TAG = "PacilReadReader";
    private static final long REFLOW_DEBOUNCE_MS = 32L;
    private static final long LAYOUT_WAIT_FALLBACK_MS = 180L;
    private static final int MAX_LAYOUT_WAIT_PASSES = 2;
    private static final long PROGRESSIVE_WAIT_LOG_MS = 800L;
    private static final long PROGRESSIVE_HARD_FALLBACK_MS = 5000L;
    private static final int MAX_LAYOUT_PAGE_CACHE_SIGNATURES = 4;
    private static final String EMPTY_CHAPTER_TEXT_PLACEHOLDER = "章节正文为空或外置正文文件缺失。";
    private static final Pattern VOLUME_CHAPTER_TITLE_PATTERN = Pattern.compile(
            "^\\s*第\\s*[0-9０-９一二三四五六七八九十百千万零〇两]+\\s*卷(?:\\s*|[：:、.．·\\-].*)$"
    );

    private static long lastCachedBookId = -1L;
    private static BookRecord cachedBook;
    private static final List<ChapterRecord> cachedChapters = new ArrayList<>();
    private static final List<ReplacementRuleRecord> cachedRules = new ArrayList<>();
    private static final Map<Integer, List<PageSlice>> cachedPageSlicesMap = new HashMap<>();
    private static final Map<ReaderLayoutSignature, Map<Integer, List<PageSlice>>> cachedPageSlicesByLayout =
            new LinkedHashMap<ReaderLayoutSignature, Map<Integer, List<PageSlice>>>(
                    MAX_LAYOUT_PAGE_CACHE_SIGNATURES,
                    0.75f,
                    true
            ) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ReaderLayoutSignature, Map<Integer, List<PageSlice>>> eldest) {
                    return size() > MAX_LAYOUT_PAGE_CACHE_SIGNATURES;
                }
            };
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
    private volatile int waitingForProgressiveChapterIndex = -1;
    private volatile int progressivePaginationGeneration = 0;
    private boolean forceInitialFullPagination = false;
    private boolean initialVisiblePageBound = false;
    private PartialPagination activePartialPagination = null;
    private ReaderLayoutSignature runningProgressiveSignature = null;
    private String runningProgressiveSource = null;
    private final Object progressSyncLock = new Object();
    private boolean initialRemoteProgressSyncPending = false;
    private long initialRemoteProgressBaselineLastReadAt = 0L;
    private int initialRemoteProgressBaselineIndex = 0;
    private int initialRemoteProgressBaselineOffset = 0;
    private DeferredProgressUpload deferredProgressUpload = null;

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
        if (!activity.isReaderActive()) {
            return;
        }
        Log.d(TAG, "[时序] performDeferredInitialReflow 被调用 - deferred=" + initialReflowDeferred);
        deferReflow = false;
        onInitialReflowComplete = onComplete;
        if (!initialReflowDeferred) {
            Log.d(TAG, "[时序] performDeferredInitialReflow 提前返回 - initialReflowDeferred=false");
            return;
        }
        if (initialVisiblePageBound) {
            Log.d(TAG, "[时序] performDeferredInitialReflow 提前返回 - 首屏已由渐进分页绑定");
            initialReflowDeferred = false;
            deferReflow = false;
            if (onInitialReflowComplete != null) {
                Runnable callback = onInitialReflowComplete;
                onInitialReflowComplete = null;
                callback.run();
            }
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
        if (!activity.isReaderActive()) {
            return;
        }
        View anchor = views.pageCurrent != null ? views.pageCurrent : views.pageBodyCurrent;
        if (anchor == null) return;
        anchor.post(() -> startBackgroundPaginationAfterLayout(chapterIndex));
    }

    private void startBackgroundPaginationAfterLayout(int chapterIndex) {
        if (!activity.isReaderActive()) {
            return;
        }
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
            activateLayoutSignature(sig);
        }
        launchBackgroundPagination(chapterIndex, pageWidth, pageHeight);
    }

    private void startBackgroundPaginationAfterLayoutRetry(int chapterIndex) {
        if (!activity.isReaderActive()) {
            return;
        }
        int pageWidth = getReaderPageTextWidth();
        int pageHeight = getRegularReaderPageHeight();
        if (pageWidth <= 0 || pageHeight <= 0) return;
        ReaderLayoutSignature sig = captureCurrentLayoutSignature();
        if (sig != null) {
            activateLayoutSignature(sig);
        }
        launchBackgroundPagination(chapterIndex, pageWidth, pageHeight);
    }

    private void launchBackgroundPagination(int chapterIndex, int pageWidth, int pageHeight) {
        if (!activity.isReaderActive()) {
            return;
        }
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

        runtime.safeExecutePagination(() -> {
            if (!activity.isReaderActive()) {
                return;
            }
            synchronized (cachedPageSlicesMap) {
                if (cachedPageSlicesMap.containsKey(chapterIndex)) return;
            }
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
            activity.runOnReaderUiThread(() -> {
                ReaderLayoutSignature currentSig = captureCurrentLayoutSignature();
                if (capturedSig != null
                        && currentSig != null
                        && !capturedSig.isPaginationCompatibleWith(currentSig)) {
                    Log.d(TAG, "[时序] 后台分页结果丢弃 - layout签名已变化 chapter=" + chapterIndex);
                    return;
                }
                activateLayoutSignature(currentSig);
                putPagesForActiveLayout(chapterIndex, pages);
                boolean hadPartial = activePartialPagination != null
                        && activePartialPagination.chapterIndex == chapterIndex;
                clearPartialPaginationForChapter(chapterIndex);
                if (hadPartial && state.currentChapterIndex == chapterIndex) {
                    Log.d(TAG, "[时序] 完整分页替换首屏partial - chapter=" + chapterIndex);
                    chrome.updateUiAfterPageChange();
                    paging.schedulePagingSnapshotWarmup();
                    prewarmAdjacentChapters(chapterIndex);
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
        }, "background pagination");
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

            int targetChapterIndex = resolveInitialChapterIndex(state.book, state.chapters);
            state.currentChapterIndex = targetChapterIndex;
            int initialAnchorOffset = resolveInitialAnchorOffset(state.book.progressOffset);
            state.sessionStartOffset = initialAnchorOffset;
            rememberChapterAnchor(targetChapterIndex, initialAnchorOffset);
            prepareInitialRemoteProgressSync();
            if (!deferReflow) {
                style.applyReaderSettings();
            }
            activity.onReaderBookLoaded();
            chrome.updateReaderHud();
            Log.d(TAG, "[时序] loadBook 缓存命中 UI回调 - chapter=" + targetChapterIndex);
            if (deferReflow) {
                initialReflowDeferred = true;
                clearPartialPagination();
                resetInitialPaginationState();
                startInitialProgressivePaginationAfterSnapshot(0, false);
                prewarmChapterText(targetChapterIndex);
                Log.d(TAG, "[时序] 初始加载仅预热正文(缓存命中) - chapter=" + targetChapterIndex);
            } else {
                resetInitialPaginationState();
                Log.d(TAG, "[时序] 调度初始渐进分页(缓存命中) - chapter=" + targetChapterIndex);
                scheduleInitialReflowAfterLayout(targetChapterIndex, initialAnchorOffset);
            }
            scheduleRemoteProgressSync();
            return;
        }

        runtime.safeExecute(() -> {
            if (!activity.isReaderActive()) {
                return;
            }
            try {
                BookRecord loadedBook = runtime.databaseHelper.getBook(state.bookId);
                List<ChapterRecord> loadedChapters = runtime.databaseHelper.getChapters(state.bookId, false);
                List<ReplacementRuleRecord> loadedRules = runtime.databaseHelper.getReplacementRules(state.bookId);

                // 在同一个后台任务中提前加载目标章节正文+替换处理，消除第二次 executor 调度的空隙
                String prewarmedText = null;
                int prewarmChapterIndex = -1;
                if (loadedBook != null && !loadedChapters.isEmpty()) {
                    int targetIndex = resolveInitialChapterIndex(loadedBook, loadedChapters);
                    if (targetIndex >= 0 && targetIndex < loadedChapters.size()) {
                        ChapterRecord targetChapter = loadedChapters.get(targetIndex);
                        ChapterRecord fullChapter = runtime.databaseHelper.getChapterContent(targetChapter.id);
                        if (fullChapter != null) {
                            targetChapter.bodyText = fullChapter.bodyText;
                        }
                        String body = targetChapter.bodyText == null ? "" : targetChapter.bodyText;
                        if (isVolumeHeadingWithoutBody(targetChapter, body)) {
                            body = "";
                        }
                        prewarmedText = ReplacementEngine.apply(body, loadedRules);
                        prewarmChapterIndex = targetIndex;
                    }
                }
                final String finalPrewarmedText = prewarmedText;
                final int finalPrewarmIndex = prewarmChapterIndex;

                activity.runOnReaderUiThread(() -> {
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
                    clearAllPageSliceCaches();

                    state.book = loadedBook;
                    state.chapters.clear();
                    state.chapters.addAll(loadedChapters);
                    state.replacementRules.clear();
                    state.replacementRules.addAll(loadedRules);

                    int targetChapterIndex = resolveInitialChapterIndex(loadedBook, state.chapters);
                    state.currentChapterIndex = targetChapterIndex;
                    int initialAnchorOffset = resolveInitialAnchorOffset(loadedBook.progressOffset);
                    state.sessionStartOffset = initialAnchorOffset;
                    rememberChapterAnchor(targetChapterIndex, initialAnchorOffset);
                    prepareInitialRemoteProgressSync();

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
                        resetInitialPaginationState();
                        // 正文已缓存，后台只需分页（不含解压+替换）
                        startInitialProgressivePaginationAfterSnapshot(0, false);
                        prewarmChapterText(targetChapterIndex);
                        Log.d(TAG, "[时序] 初始加载仅预热正文(缓存未命中) - chapter=" + targetChapterIndex);
                    } else {
                        resetInitialPaginationState();
                        Log.d(TAG, "[时序] 调度初始渐进分页(缓存未命中) - chapter=" + targetChapterIndex);
                        scheduleInitialReflowAfterLayout(targetChapterIndex, initialAnchorOffset);
                    }
                    scheduleRemoteProgressSync();
                });
            } catch (Exception error) {
                Log.e(TAG, "Failed to load reader state", error);
                activity.runOnReaderUiThread(() -> {
                    ui.showToast("打开书籍失败: " + readableError(error));
                    activity.finishReaderActivity();
                });
            }
        }, "load reader book");
    }

    public void persistProgress() {
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        ReadingPosition position = captureCurrentReadingPosition();
        int safeChapterIndex = ui.clamp(position.chapterIndex, 0, state.chapters.size() - 1);
        int offset = Math.max(position.chapterOffset, 0);
        ChapterRecord chapter = state.chapters.get(safeChapterIndex);
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
            if (deferProgressUploadIfInitialSyncPending(bookSnapshot, chapterSnapshot, offset)) {
                return;
            }
            uploadProgressSnapshot(bookSnapshot, chapterSnapshot, offset);
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
        RemoteProgressComparison initialComparison = captureInitialRemoteProgressComparison();
        runtime.safeExecute(() -> {
            boolean remoteApplied = false;
            try {
                if (!activity.isReaderActive()) {
                    return;
                }
                BookRecord currentBook = state.book;
                if (currentBook == null) {
                    return;
                }
                WebDavProgressSyncCoordinator.ProgressBaseline baseline = initialComparison == null
                        ? null
                        : new WebDavProgressSyncCoordinator.ProgressBaseline(
                        initialComparison.lastReadAt,
                        initialComparison.progressIndex,
                        initialComparison.progressOffset
                );
                WebDavProgressSyncCoordinator.SyncResult result =
                        runtime.progressSyncCoordinator.syncBookProgressIfNeeded(currentBook, baseline);
                if (result.checkedRemote && !result.remoteAvailable) {
                    if (!silent) {
                        activity.runOnReaderUiThread(() -> ui.showToast("云端暂时没有可恢复的进度"));
                    }
                    return;
                }
                if (!result.remoteApplied) {
                    if (result.skippedFresh && applyFreshLocalProgressFromDatabase(initialComparison)) {
                        remoteApplied = true;
                    }
                    return;
                }
                if (!activity.isReaderActive() || state.chapters.isEmpty()) {
                    return;
                }
                int remoteIndex = ui.clamp(
                        navigation.chapterIndexFromOrder(result.chapterOrderIndex),
                        0,
                        state.chapters.size() - 1
                );
                state.book.progressIndex = state.chapters.get(remoteIndex).orderIndex;
                state.book.progressOffset = Math.max(result.chapterPosition, 0);
                state.book.lastReadAt = result.chapterTime;
                remoteApplied = true;
                activity.runOnReaderUiThread(() -> scheduleReflowAfterLayout(remoteIndex, result.chapterPosition));
            } catch (Exception error) {
                if (!silent) {
                    activity.runOnReaderUiThread(() -> ui.showToast("同步失败: " + error.getMessage()));
                }
            } finally {
                DeferredProgressUpload upload = finishInitialRemoteProgressSync(initialComparison, remoteApplied);
                if (upload != null) {
                    uploadProgressSnapshot(upload.book, upload.chapter, upload.offset);
                }
            }
        }, "sync reader progress from WebDAV");
    }

    private boolean applyFreshLocalProgressFromDatabase(RemoteProgressComparison initialComparison) {
        if (initialComparison == null || state.book == null || state.chapters.isEmpty()) {
            return false;
        }
        BookRecord latestBook = runtime.databaseHelper.getBook(state.book.id);
        if (latestBook == null || !isProgressNewerThanInitial(latestBook, initialComparison)) {
            return false;
        }
        int latestIndex = ui.clamp(
                navigation.chapterIndexFromOrder(latestBook.progressIndex),
                0,
                state.chapters.size() - 1
        );
        ChapterRecord latestChapter = state.chapters.get(latestIndex);
        int latestChapterOrderIndex = latestChapter == null ? latestBook.progressIndex : latestChapter.orderIndex;
        int latestOffset = Math.max(latestBook.progressOffset, 0);
        if (state.book.progressIndex == latestChapterOrderIndex
                && state.book.progressOffset == latestOffset) {
            state.book.lastReadAt = Math.max(state.book.lastReadAt, latestBook.lastReadAt);
            return false;
        }
        state.book.progressIndex = latestChapterOrderIndex;
        state.book.progressOffset = latestOffset;
        state.book.lastReadAt = latestBook.lastReadAt;
        activity.runOnReaderUiThread(() -> scheduleReflowAfterLayout(latestIndex, latestOffset));
        return true;
    }

    private boolean isProgressNewerThanInitial(BookRecord latestBook, RemoteProgressComparison initialComparison) {
        if (latestBook == null || initialComparison == null) {
            return false;
        }
        boolean positionChanged = latestBook.progressIndex != initialComparison.progressIndex
                || latestBook.progressOffset != initialComparison.progressOffset;
        if (!positionChanged) {
            return latestBook.lastReadAt > initialComparison.lastReadAt;
        }
        boolean initialEmpty = initialComparison.lastReadAt <= 0L
                && initialComparison.progressIndex == 0
                && initialComparison.progressOffset == 0;
        return initialEmpty || latestBook.lastReadAt > initialComparison.lastReadAt;
    }

    private void scheduleRemoteProgressSync() {
        if (state.book != null && WebDavProgressSyncCoordinator.isProgressFresh(state.book.id)) {
            completeInitialRemoteProgressSyncWithoutRemoteApply();
            return;
        }
        runtime.mainHandler.postDelayed(() -> {
            if (!activity.isReaderActive()) {
                return;
            }
            if (state.book != null && WebDavProgressSyncCoordinator.isProgressFresh(state.book.id)) {
                completeInitialRemoteProgressSyncWithoutRemoteApply();
                return;
            }
            syncFromWebDav(true);
        }, 250L);
    }

    private void completeInitialRemoteProgressSyncWithoutRemoteApply() {
        RemoteProgressComparison comparison = captureInitialRemoteProgressComparison();
        DeferredProgressUpload upload = finishInitialRemoteProgressSync(comparison, false);
        if (upload != null) {
            uploadProgressSnapshot(upload.book, upload.chapter, upload.offset);
        }
    }

    private void prepareInitialRemoteProgressSync() {
        synchronized (progressSyncLock) {
            deferredProgressUpload = null;
            if (!runtime.settingsStore.isWebDavEnabled() || state.book == null) {
                initialRemoteProgressSyncPending = false;
                return;
            }
            initialRemoteProgressSyncPending = true;
            initialRemoteProgressBaselineLastReadAt = state.book.lastReadAt;
            initialRemoteProgressBaselineIndex = state.book.progressIndex;
            initialRemoteProgressBaselineOffset = state.book.progressOffset;
        }
    }

    private RemoteProgressComparison captureInitialRemoteProgressComparison() {
        synchronized (progressSyncLock) {
            if (!initialRemoteProgressSyncPending) {
                return null;
            }
            return new RemoteProgressComparison(
                    initialRemoteProgressBaselineLastReadAt,
                    initialRemoteProgressBaselineIndex,
                    initialRemoteProgressBaselineOffset
            );
        }
    }

    private boolean deferProgressUploadIfInitialSyncPending(
            BookRecord bookSnapshot,
            ChapterRecord chapterSnapshot,
            int offset
    ) {
        synchronized (progressSyncLock) {
            if (!initialRemoteProgressSyncPending) {
                return false;
            }
            deferredProgressUpload = new DeferredProgressUpload(bookSnapshot, chapterSnapshot, offset);
            return true;
        }
    }

    private DeferredProgressUpload finishInitialRemoteProgressSync(
            RemoteProgressComparison comparison,
            boolean remoteApplied
    ) {
        if (comparison == null) {
            return null;
        }
        synchronized (progressSyncLock) {
            if (!initialRemoteProgressSyncPending) {
                return null;
            }
            initialRemoteProgressSyncPending = false;
            initialRemoteProgressBaselineLastReadAt = 0L;
            initialRemoteProgressBaselineIndex = 0;
            initialRemoteProgressBaselineOffset = 0;
            DeferredProgressUpload upload = remoteApplied ? null : deferredProgressUpload;
            deferredProgressUpload = null;
            return upload;
        }
    }

    private void uploadProgressSnapshot(BookRecord bookSnapshot, ChapterRecord chapterSnapshot, int offset) {
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

    private static final class RemoteProgressComparison {
        final long lastReadAt;
        final int progressIndex;
        final int progressOffset;

        RemoteProgressComparison(long lastReadAt, int progressIndex, int progressOffset) {
            this.lastReadAt = lastReadAt;
            this.progressIndex = progressIndex;
            this.progressOffset = progressOffset;
        }
    }

    private static final class DeferredProgressUpload {
        final BookRecord book;
        final ChapterRecord chapter;
        final int offset;

        DeferredProgressUpload(BookRecord book, ChapterRecord chapter, int offset) {
            this.book = book;
            this.chapter = chapter;
            this.offset = offset;
        }
    }

    public List<PageSlice> getPagesForChapter(int chapterIndex) {
        ensurePaginationCacheMatchesLayout();
        synchronized (cachedPageSlicesMap) {
            List<PageSlice> cached = cachedPageSlicesMap.get(chapterIndex);
            if (cached != null) {
                Log.d(TAG, "[时序] getPagesForChapter 缓存命中 - chapter=" + chapterIndex);
                return cached;
            }
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
        putPagesForActiveLayout(chapterIndex, pages);
        clearPartialPaginationForChapter(chapterIndex);
        Log.w(TAG, "[时序] 主线程分页完成 - chapter=" + chapterIndex + " 页数=" + pages.size() + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        return pages;
    }

    public int getKnownPageCountForChapter(int chapterIndex) {
        ensurePaginationCacheMatchesLayout();
        synchronized (cachedPageSlicesMap) {
            List<PageSlice> cached = cachedPageSlicesMap.get(chapterIndex);
            if (cached != null) {
                return cached.size();
            }
        }
        PartialPagination partial = activePartialPagination;
        if (partial != null && partial.chapterIndex == chapterIndex) {
            return partial.pages.size();
        }
        return 1;
    }

    public boolean isPageCountCompleteForChapter(int chapterIndex) {
        ensurePaginationCacheMatchesLayout();
        synchronized (cachedPageSlicesMap) {
            return cachedPageSlicesMap.containsKey(chapterIndex);
        }
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
                    return Math.max(0, (contentWidth - doublePageGutterWidth()) / 2);
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
        if (isVolumeHeadingWithoutBody(chapter, body)) {
            body = "";
        }
        String processed = ReplacementEngine.apply(body, state.replacementRules);
        synchronized (processedChapterLruCache) {
            processedChapterLruCache.put(chapterIndex, processed);
        }
        synchronized (processedChapterLengthCache) {
            processedChapterLengthCache.put(chapterIndex, processed.length());
        }
        return processed;
    }

    private boolean isVolumeHeadingWithoutBody(ChapterRecord chapter, String body) {
        if (chapter == null || body == null || !EMPTY_CHAPTER_TEXT_PLACEHOLDER.equals(body.trim())) {
            return false;
        }
        String title = chapter.title == null ? "" : chapter.title.trim();
        return VOLUME_CHAPTER_TITLE_PATTERN.matcher(title).matches();
    }

    /** 在后台预加载章节正文+替换处理，让动画结束后的排版能直接命中缓存。 */
    public void prewarmChapterText(int chapterIndex) {
        if (state.book == null || state.chapters.isEmpty()) return;
        if (chapterIndex < 0 || chapterIndex >= state.chapters.size()) return;
        int safeIndex = Math.max(0, Math.min(chapterIndex, state.chapters.size() - 1));
        runtime.safeExecute(() -> {
            if (!activity.isReaderActive()) {
                return;
            }
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
                if (isVolumeHeadingWithoutBody(chapter, body)) {
                    body = "";
                }
                String processed = ReplacementEngine.apply(body, state.replacementRules);
                synchronized (processedChapterLruCache) {
                    processedChapterLruCache.put(safeIndex, processed);
                }
                synchronized (processedChapterLengthCache) {
                    processedChapterLengthCache.put(safeIndex, processed.length());
                }
            }
        }, "prewarm chapter text");
    }

    /** 排版快照就绪后调用，动画期间就启动首屏渐进分页。
     *  仅供 ModernReaderActivity.startFluidEnterAnimation 调用。 */
    public void prewarmChapterTextAfterSnapshot() {
        if (!initialReflowDeferred) {
            return;
        }
        startInitialProgressivePaginationAfterSnapshot(0, false);
    }

    /** 后台预加载相邻章节正文，让翻页时无需等待解压+替换处理。 */
    private void prewarmAdjacentChapters(int currentChapterIndex) {
        int nextIndex = currentChapterIndex + 1;
        if (nextIndex < 0 || nextIndex >= state.chapters.size()) return;
        runtime.safeExecute(() -> {
            if (!activity.isReaderActive()) {
                return;
            }
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
            if (isVolumeHeadingWithoutBody(chapter, body)) {
                body = "";
            }
            String processed = ReplacementEngine.apply(body, state.replacementRules);
            synchronized (processedChapterLruCache) {
                processedChapterLruCache.put(nextIndex, processed);
            }
            synchronized (processedChapterLengthCache) {
                processedChapterLengthCache.put(nextIndex, processed.length());
            }
        }, "prewarm adjacent chapter text");
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
            if (isVolumeHeadingWithoutBody(ch, ch.bodyText)) {
                processedChapterLengthCache.put(chapterIndex, 0);
                return 0;
            }
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
        return captureCurrentReadingPosition().chapterOffset;
    }

    public ReadingPosition captureCurrentReadingPosition() {
        if (state.chapters.isEmpty()) {
            return new ReadingPosition(
                    Math.max(state.currentChapterIndex, 0),
                    Math.max(state.currentPageIndex, 0),
                    0
            );
        }
        int safeChapterIndex = ui.clamp(state.currentChapterIndex, 0, state.chapters.size() - 1);
        int safePageIndex = Math.max(state.currentPageIndex, 0);
        int offset = resolvePageStartOffset(safeChapterIndex, safePageIndex);
        rememberChapterAnchor(safeChapterIndex, safePageIndex, offset);
        return new ReadingPosition(safeChapterIndex, safePageIndex, offset);
    }

    private int resolvePageStartOffset(int chapterIndex, int pageIndex) {
        PartialPagination partial = activePartialPagination;
        if (partial != null
                && partial.chapterIndex == chapterIndex
                && pageIndex >= 0
                && pageIndex < partial.pages.size()) {
            return Math.max(partial.pages.get(pageIndex).start, 0);
        }
        List<PageSlice> pages;
        synchronized (cachedPageSlicesMap) {
            pages = cachedPageSlicesMap.get(chapterIndex);
        }
        if (pages != null && !pages.isEmpty()) {
            return Math.max(pages.get(ui.clamp(pageIndex, 0, pages.size() - 1)).start, 0);
        }
        return fallbackChapterOffset(chapterIndex);
    }

    public int rememberCurrentPageAnchor() {
        return currentCharOffset();
    }

    private void rememberChapterAnchor(int chapterIndex, int offset) {
        rememberChapterAnchor(chapterIndex, 0, offset);
    }

    private void rememberChapterAnchor(int chapterIndex, int pageIndex, int offset) {
        if (state.chapters.isEmpty()) {
            state.lastKnownChapterIndex = -1;
            state.lastKnownPageIndex = -1;
            state.lastKnownChapterOffset = 0;
            return;
        }
        int safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        state.lastKnownChapterIndex = safeChapterIndex;
        state.lastKnownPageIndex = Math.max(pageIndex, 0);
        state.lastKnownChapterOffset = Math.max(offset, 0);
    }

    private int fallbackChapterOffset(int chapterIndex) {
        int safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size() - 1);
        if (state.lastKnownChapterIndex == safeChapterIndex) {
            return Math.max(state.lastKnownChapterOffset, 0);
        }
        if (state.book != null) {
            ChapterRecord chapter = state.chapters.get(safeChapterIndex);
            if (chapter != null && state.book.progressIndex == chapter.orderIndex) {
                return Math.max(state.book.progressOffset, 0);
            }
        }
        return safeChapterIndex == ui.clamp(state.currentChapterIndex, 0, state.chapters.size() - 1)
                ? Math.max(state.sessionStartOffset, 0)
                : 0;
    }

    public static final class ReadingPosition {
        public final int chapterIndex;
        public final int pageIndex;
        public final int chapterOffset;

        private ReadingPosition(int chapterIndex, int pageIndex, int chapterOffset) {
            this.chapterIndex = chapterIndex;
            this.pageIndex = pageIndex;
            this.chapterOffset = chapterOffset;
        }
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
        rememberCurrentPageAnchor();
        clearAllPageSliceCaches();
        clearPartialPagination();
    }

    public void clearAllReaderCaches() {
        rememberCurrentPageAnchor();
        processedChapterLruCache.evictAll();
        processedChapterLengthCache.clear();
        clearAllPageSliceCaches();
        clearPartialPagination();
        state.totalProcessedBookLength = -1;
    }

    private static void clearAllPageSliceCaches() {
        synchronized (cachedPageSlicesMap) {
            cachedPageSlicesMap.clear();
            cachedPageSlicesByLayout.clear();
            cachedLayoutSignature = null;
        }
    }

    private static void rememberActiveLayoutCacheLocked() {
        if (cachedLayoutSignature == null) {
            return;
        }
        if (cachedPageSlicesMap.isEmpty()) {
            cachedPageSlicesByLayout.remove(cachedLayoutSignature);
        } else {
            cachedPageSlicesByLayout.put(cachedLayoutSignature, new HashMap<>(cachedPageSlicesMap));
        }
    }

    private boolean activateLayoutSignature(ReaderLayoutSignature signature) {
        if (signature == null) {
            return false;
        }
        synchronized (cachedPageSlicesMap) {
            if (signature.equals(cachedLayoutSignature)) {
                return false;
            }
            if (cachedLayoutSignature != null
                    && cachedLayoutSignature.isPaginationCompatibleWith(signature)) {
                Map<Integer, List<PageSlice>> restored = cachedPageSlicesByLayout.get(signature);
                if (cachedPageSlicesMap.isEmpty() && restored != null) {
                    cachedPageSlicesMap.putAll(restored);
                    Log.d(TAG, "[时序] 恢复兼容布局分页缓存 - chapters=" + restored.size());
                }
                cachedPageSlicesByLayout.remove(cachedLayoutSignature);
                cachedLayoutSignature = signature;
                rememberActiveLayoutCacheLocked();
                return false;
            }
            rememberCurrentPageAnchor();
            rememberActiveLayoutCacheLocked();
            cachedPageSlicesMap.clear();
            Map<Integer, List<PageSlice>> restored = cachedPageSlicesByLayout.get(signature);
            if (restored != null) {
                cachedPageSlicesMap.putAll(restored);
                Log.d(TAG, "[时序] 恢复布局分页缓存 - chapters=" + restored.size());
            }
            cachedLayoutSignature = signature;
            return true;
        }
    }

    private void putPagesForActiveLayout(int chapterIndex, List<PageSlice> pages) {
        synchronized (cachedPageSlicesMap) {
            cachedPageSlicesMap.put(chapterIndex, pages);
            rememberActiveLayoutCacheLocked();
        }
    }

    private void clearPartialPagination() {
        activePartialPagination = null;
    }

    private void clearPartialPaginationForChapter(int chapterIndex) {
        PartialPagination partial = activePartialPagination;
        if (partial != null && partial.chapterIndex == chapterIndex) {
            activePartialPagination = null;
        }
    }

    private boolean hasAnyCachedPagesForChapter(int chapterIndex) {
        synchronized (cachedPageSlicesMap) {
            if (cachedPageSlicesMap.containsKey(chapterIndex)) {
                return true;
            }
            for (Map<Integer, List<PageSlice>> pagesByChapter : cachedPageSlicesByLayout.values()) {
                if (pagesByChapter != null && pagesByChapter.containsKey(chapterIndex)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isProgressivePaginationCancelled(int chapterIndex, int paginationGeneration) {
        return paginationGeneration != progressivePaginationGeneration
                || waitingForProgressiveChapterIndex != chapterIndex;
    }

    private void clearRunningProgressiveState() {
        runningProgressiveSignature = null;
        runningProgressiveSource = null;
    }

    private void cancelInitialProgressivePagination(int chapterIndex, String reason) {
        if (waitingForProgressiveChapterIndex != chapterIndex) {
            return;
        }
        Log.d(TAG, "[时序] 取消初始渐进分页 - chapter=" + chapterIndex
                + " gen=" + progressivePaginationGeneration + " reason=" + reason
                + " source=" + runningProgressiveSource);
        waitingForProgressiveChapterIndex = -1;
        progressivePaginationGeneration++;
        clearRunningProgressiveState();
    }

    private void resetInitialPaginationState() {
        waitingForProgressiveChapterIndex = -1;
        waitingForPaginationChapterIndex = -1;
        progressivePaginationGeneration++;
        forceInitialFullPagination = false;
        initialVisiblePageBound = false;
        clearRunningProgressiveState();
        clearPartialPagination();
    }

    public void scheduleReflowAfterLayout(int chapterIndex, int anchorOffset) {
        if (!activity.isReaderActive()) {
            return;
        }
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
        if (!activity.isReaderActive()) {
            return;
        }
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
        waitingForProgressiveChapterIndex = -1;
        waitingForPaginationChapterIndex = -1;
        progressivePaginationGeneration++;
        forceInitialFullPagination = false;
        clearRunningProgressiveState();
        reflowGeneration++;
        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable);
    }

    public void onReaderInsetsChanged(boolean suppressReflow, boolean paginationInsetsChanged) {
        if (!activity.isReaderActive()) {
            return;
        }
        if (style != null) {
            style.applyReaderSettings();
        }
        cachedPaginationSnapshot = null;
        if (state.book == null || state.chapters.isEmpty()) {
            return;
        }
        int chapterIndex = ui.clamp(state.currentChapterIndex, 0, state.chapters.size() - 1);
        if (paginationInsetsChanged && initialReflowDeferred && !initialVisiblePageBound) {
            cancelInitialProgressivePagination(chapterIndex, "insets_changed");
            clearPartialPagination();
            View target = views.pageCurrent == null ? views.pageBodyCurrent : views.pageCurrent;
            if (target != null) {
                target.post(() -> {
                    if (!activity.isReaderActive()) {
                        return;
                    }
                    capturePaginationSnapshot();
                    startInitialProgressivePaginationAfterSnapshot(0, true);
                });
            }
            return;
        }
        if (paginationInsetsChanged) {
            scheduleReflowAfterLayout(chapterIndex, currentCharOffset());
        } else if (!suppressReflow && paging != null) {
            paging.invalidatePreparedPagingSnapshots();
        }
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
        if (activateLayoutSignature(currentSignature)) {
            clearPartialPagination();
        }
    }

    private ReaderLayoutSignature captureCurrentLayoutSignature() {
        if (!isPaginationLayoutReady()) {
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
                state.readerContentInsetTop,
                state.readerContentInsetBottom,
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

    private int resolveInitialChapterIndex(BookRecord book, List<ChapterRecord> chapters) {
        if (book == null || chapters == null || chapters.isEmpty()) {
            return 0;
        }
        int targetChapterIndex = ui.clamp(
                chapterIndexFromOrder(chapters, book.progressIndex),
                0,
                chapters.size() - 1
        );
        if (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0) {
            targetChapterIndex = ui.clamp(
                    chapterIndexFromOrder(chapters, state.requestedChapterOrderIndex),
                    0,
                    chapters.size() - 1
            );
        }
        if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
            targetChapterIndex = ui.clamp(state.restoredChapterIndex, 0, chapters.size() - 1);
        }
        return targetChapterIndex;
    }

    private int chapterIndexFromOrder(List<ChapterRecord> chapters, int orderIndex) {
        for (int i = 0; i < chapters.size(); i++) {
            ChapterRecord chapter = chapters.get(i);
            if (chapter != null && chapter.orderIndex == orderIndex) {
                return i;
            }
        }
        return orderIndex;
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

    private void startInitialProgressivePaginationAfterSnapshot(int attempt, boolean styleApplied) {
        if (!activity.isReaderActive()) {
            return;
        }
        if (!initialReflowDeferred || state.book == null || state.chapters.isEmpty()) {
            return;
        }
        int chapterIndex = ui.clamp(state.currentChapterIndex, 0, state.chapters.size() - 1);
        PaginationSnapshot snapshot = cachedPaginationSnapshot;
        if (snapshot == null && !styleApplied && style != null) {
            style.applyReaderSettings();
            View target = views.pageCurrent == null ? views.pageBodyCurrent : views.pageCurrent;
            if (target != null) {
                target.post(() -> startInitialProgressivePaginationAfterSnapshot(attempt, true));
                return;
            }
        }
        if (snapshot == null) {
            if (attempt >= MAX_LAYOUT_WAIT_PASSES + 2) {
                Log.d(TAG, "[时序] 提前渐进分页等待动画快照超限 - chapter=" + chapterIndex);
                return;
            }
            if (!isPaginationLayoutReady()) {
                requestPageLayerLayout();
            }
            View target = views.pageCurrent == null ? views.pageBodyCurrent : views.pageCurrent;
            if (target != null) {
                target.postDelayed(
                        () -> startInitialProgressivePaginationAfterSnapshot(attempt + 1, true),
                        16L
                );
            }
            return;
        }
        if (snapshot.pageWidth <= 0 || snapshot.regularPageHeight <= 0) {
            Log.w(TAG, "[时序] 动画快照尺寸无效，等待 deferred reflow 兜底 - chapter=" + chapterIndex
                    + " w=" + snapshot.pageWidth + " h=" + snapshot.regularPageHeight);
            return;
        }
        if (hasAnyCachedPagesForChapter(chapterIndex)) {
            Log.d(TAG, "[时序] 已有分页缓存，跳过动画期渐进分页 - chapter=" + chapterIndex);
            return;
        }
        if (snapshot.layoutSignature != null) {
            activateLayoutSignature(snapshot.layoutSignature);
        } else {
            ensurePaginationCacheMatchesLayout();
        }
        synchronized (cachedPageSlicesMap) {
            if (cachedPageSlicesMap.containsKey(chapterIndex)) {
                return;
            }
        }
        PartialPagination partial = activePartialPagination;
        if ((partial != null && partial.chapterIndex == chapterIndex)
                || waitingForProgressiveChapterIndex == chapterIndex
                || initialVisiblePageBound) {
            return;
        }
        int anchorOffset = Math.max(state.sessionStartOffset, 0);
        pendingReflowChapterIndex = chapterIndex;
        pendingReflowAnchorOffset = anchorOffset;
        initialReflowPending = true;
        Log.d(TAG, "[时序] 动画期间提前启动初始渐进分页 - chapter=" + chapterIndex
                + " source=cached_snapshot");
        startInitialProgressivePagination(
                chapterIndex,
                anchorOffset,
                hasInitialPositionRequest(),
                snapshot,
                "cached_snapshot"
        );
    }

    private void startInitialProgressivePagination(
            int chapterIndex,
            int anchorOffset,
            boolean shouldResetInitialPosition
    ) {
        PaginationSnapshot snapshot = captureLivePaginationSnapshot();
        if (snapshot == null) {
            fallbackInitialFullPagination(chapterIndex, 0, "invalid_live_snapshot", null);
            return;
        }
        startInitialProgressivePagination(
                chapterIndex,
                anchorOffset,
                shouldResetInitialPosition,
                snapshot,
                "live_snapshot"
        );
    }

    private void startInitialProgressivePagination(
            int chapterIndex,
            int anchorOffset,
            boolean shouldResetInitialPosition,
            PaginationSnapshot snapshot,
            String source
    ) {
        if (snapshot == null || snapshot.pageWidth <= 0 || snapshot.regularPageHeight <= 0) {
            fallbackInitialFullPagination(chapterIndex, 0, "invalid_snapshot:" + source, null);
            return;
        }
        ReaderLayoutSignature sig = snapshot.layoutSignature;
        if (sig != null) {
            activateLayoutSignature(sig);
        }
        int paginationGeneration = ++progressivePaginationGeneration;
        waitingForProgressiveChapterIndex = chapterIndex;
        runningProgressiveSignature = sig;
        runningProgressiveSource = source;
        final long startTime = System.currentTimeMillis();
        final int extraPagesAfterTarget = Math.max(1, pagesPerScreen());
        Log.d(TAG, "[时序] 初始渐进分页开始 - chapter=" + chapterIndex
                + " offset=" + anchorOffset + " extra=" + extraPagesAfterTarget
                + " source=" + source + " w=" + snapshot.pageWidth
                + " h=" + snapshot.regularPageHeight);
        runtime.safeExecutePagination(() -> {
            if (!activity.isReaderActive()) {
                return;
            }
            try {
                long workerStartTime = System.currentTimeMillis();
                if (isProgressivePaginationCancelled(chapterIndex, paginationGeneration)) {
                    return;
                }
                Log.d(TAG, "[时序] 初始渐进分页worker开始 - chapter=" + chapterIndex
                        + " gen=" + paginationGeneration
                        + " queueWait=" + (workerStartTime - startTime) + "ms"
                        + " source=" + source);
                DisplayChapterText display = buildDisplayChapterTextForBackground(chapterIndex, snapshot);
                TextPaint paint = new TextPaint(snapshot.basePaint);
                ReaderPaginator.ProgressiveResult result = ReaderPaginator.paginateUntilOffset(
                        display.text,
                        paint,
                        snapshot.pageWidth,
                        snapshot.regularPageHeight,
                        snapshot.regularPageHeight,
                        snapshot.lineSpacingExtra,
                        display.bodyStartIndex,
                        anchorOffset,
                        extraPagesAfterTarget,
                        () -> isProgressivePaginationCancelled(chapterIndex, paginationGeneration)
                );
                List<PageSlice> pages = sanitizePageSlices(result.pages);
                int targetPageIndex = pages.isEmpty()
                        ? 0
                        : ui.clamp(result.targetPageIndex, 0, pages.size() - 1);
                long elapsed = System.currentTimeMillis() - startTime;
                Log.d(TAG, "[时序] 初始渐进分页worker完成 - chapter=" + chapterIndex
                        + " gen=" + paginationGeneration
                        + " pages=" + pages.size()
                        + " complete=" + result.complete
                        + " worker耗时=" + (System.currentTimeMillis() - workerStartTime) + "ms"
                        + " total=" + elapsed + "ms");
                activity.runOnReaderUiThread(() -> handleInitialProgressivePaginationResult(
                        chapterIndex,
                        anchorOffset,
                        paginationGeneration,
                        shouldResetInitialPosition,
                        snapshot.layoutSignature,
                        pages,
                        targetPageIndex,
                        result.complete,
                        elapsed
                ));
            } catch (CancellationException cancelled) {
                Log.d(TAG, "[时序] 初始渐进分页worker取消 - chapter=" + chapterIndex
                        + " gen=" + paginationGeneration + " source=" + source);
            } catch (Exception error) {
                activity.runOnReaderUiThread(() -> fallbackInitialFullPagination(
                        chapterIndex,
                        paginationGeneration,
                        "progressive_exception:" + source,
                        error
                ));
            }
        }, "initial progressive pagination");
        runtime.mainHandler.postDelayed(() -> {
            if (!activity.isReaderActive()) {
                return;
            }
            if (paginationGeneration != progressivePaginationGeneration
                    || waitingForProgressiveChapterIndex != chapterIndex) {
                return;
            }
            Log.d(TAG, "[时序] 初始渐进分页仍在后台运行 - chapter=" + chapterIndex
                    + " gen=" + paginationGeneration + " wait=" + PROGRESSIVE_WAIT_LOG_MS
                    + "ms source=" + source);
        }, PROGRESSIVE_WAIT_LOG_MS);
        runtime.mainHandler.postDelayed(
                () -> {
                    if (!activity.isReaderActive()) {
                        return;
                    }
                    fallbackInitialFullPagination(
                            chapterIndex,
                            paginationGeneration,
                            "progressive_timeout_hard:" + source,
                            null
                    );
                },
                PROGRESSIVE_HARD_FALLBACK_MS
        );
    }

    private void fallbackInitialFullPagination(
            int chapterIndex,
            int paginationGeneration,
            String reason,
            Throwable error
    ) {
        if (paginationGeneration > 0
                && (paginationGeneration != progressivePaginationGeneration
                || waitingForProgressiveChapterIndex != chapterIndex)) {
            return;
        }
        waitingForProgressiveChapterIndex = -1;
        clearRunningProgressiveState();
        forceInitialFullPagination = true;
        String message = "[时序] 初始渐进分页转完整分页兜底 - chapter=" + chapterIndex
                + " gen=" + paginationGeneration + " reason=" + reason;
        if (error == null) {
            Log.w(TAG, message);
        } else {
            Log.w(TAG, message, error);
        }
        performScheduledReflow();
    }

    private void handleInitialProgressivePaginationResult(
            int chapterIndex,
            int anchorOffset,
            int paginationGeneration,
            boolean shouldResetInitialPosition,
            ReaderLayoutSignature capturedSignature,
            List<PageSlice> pages,
            int targetPageIndex,
            boolean complete,
            long elapsedMs
    ) {
        if (paginationGeneration != progressivePaginationGeneration
                || waitingForProgressiveChapterIndex != chapterIndex) {
            Log.d(TAG, "[时序] 初始渐进分页结果丢弃 - gen/等待状态已变化 chapter=" + chapterIndex);
            return;
        }
        ReaderLayoutSignature currentSignature = captureCurrentLayoutSignature();
        if (capturedSignature != null
                && currentSignature != null
                && !capturedSignature.isPaginationCompatibleWith(currentSignature)) {
            fallbackInitialFullPagination(
                    chapterIndex,
                    paginationGeneration,
                    "layout_signature_changed",
                    null
            );
            return;
        }
        if (pages == null || pages.isEmpty()) {
            fallbackInitialFullPagination(
                    chapterIndex,
                    paginationGeneration,
                    "empty_progressive_pages",
                    null
            );
            return;
        }
        waitingForProgressiveChapterIndex = -1;
        clearRunningProgressiveState();
        ReaderLayoutSignature activeSignature = currentSignature != null ? currentSignature : capturedSignature;
        activateLayoutSignature(activeSignature);
        Log.d(TAG, "[时序] 初始渐进分页目标页完成 - chapter=" + chapterIndex
                + " page=" + targetPageIndex + " knownPages=" + pages.size()
                + " complete=" + complete + " 耗时=" + elapsedMs + "ms");
        if (complete) {
            putPagesForActiveLayout(chapterIndex, pages);
            clearPartialPaginationForChapter(chapterIndex);
            navigation.openChapter(chapterIndex, anchorOffset, false, 0);
            finishInitialReflowAfterVisiblePage(chapterIndex, shouldResetInitialPosition, true);
            return;
        }
        activePartialPagination = new PartialPagination(chapterIndex, pages, targetPageIndex, activeSignature);
        navigation.openChapterWithPartialPages(chapterIndex, targetPageIndex, pages, false);
        finishInitialReflowAfterVisiblePage(chapterIndex, shouldResetInitialPosition, false);
        startBackgroundPagination(chapterIndex);
    }

    private void finishInitialReflowAfterVisiblePage(
            int chapterIndex,
            boolean shouldResetInitialPosition,
            boolean prewarmAdjacent
    ) {
        if (shouldResetInitialPosition) {
            resetInitialPositionRequest();
        }
        Log.d(TAG, "[时序] 初始排版完成 - 触发前景淡入");
        initialReflowPending = false;
        forceInitialFullPagination = false;
        initialVisiblePageBound = true;
        if (prewarmAdjacent) {
            prewarmAdjacentChapters(chapterIndex);
        }
        if (onInitialReflowComplete != null) {
            Runnable callback = onInitialReflowComplete;
            onInitialReflowComplete = null;
            callback.run();
        }
        lastAppliedDoublePageActive = isDoublePageActive();
    }

    private void performScheduledReflow() {
        if (!activity.isReaderActive()) {
            return;
        }
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
        final boolean doublePageStateChanged = previousDoublePageActive != null
                && previousDoublePageActive != nextDoublePageActive;
        runAfterNextPageLayout(generation, () -> {
            if (generation != reflowGeneration) {
                Log.d(TAG, "[时序] performScheduledReflow 回调被丢弃 - gen不匹配");
                return;
            }
            Log.d(TAG, "[时序] performScheduledReflow layout就绪 - 检查缓存");
            if (doublePageStateChanged) {
                paging.invalidatePreparedPagingSnapshots();
            }
            ensurePaginationCacheMatchesLayout();
            boolean cacheHit = cachedPageSlicesMap.containsKey(chapterIndex);
            Log.d(TAG, "[时序] 分页缓存检查 - chapter=" + chapterIndex + " hit=" + cacheHit + " initialReflow=" + completingInitialReflow);
            if (cacheHit) {
                cancelInitialProgressivePagination(chapterIndex, "cache_hit");
            }
            if (completingInitialReflow && !cacheHit && !forceInitialFullPagination) {
                if (activePartialPagination != null && activePartialPagination.chapterIndex == chapterIndex) {
                    Log.d(TAG, "[时序] 初始排版已有 partial 可绑定，跳过完整分页 - chapter=" + chapterIndex);
                    lastAppliedDoublePageActive = isDoublePageActive();
                    return;
                }
                if (waitingForProgressiveChapterIndex == chapterIndex) {
                    ReaderLayoutSignature currentSignature = captureCurrentLayoutSignature();
                    if (runningProgressiveSignature != null
                            && currentSignature != null
                            && !runningProgressiveSignature.isPaginationCompatibleWith(currentSignature)) {
                        cancelInitialProgressivePagination(
                                chapterIndex,
                                "layout_signature_changed_before_result"
                        );
                    } else {
                        Log.d(TAG, "[时序] 初始排版等待后台渐进分页结果 - chapter=" + chapterIndex);
                        lastAppliedDoublePageActive = isDoublePageActive();
                        return;
                    }
                }
                PaginationSnapshot snapshot = cachedPaginationSnapshot;
                String snapshotSource = "cached_snapshot_reflow";
                ReaderLayoutSignature currentSignature = captureCurrentLayoutSignature();
                if (snapshot == null
                        || (snapshot.layoutSignature != null
                        && currentSignature != null
                        && !snapshot.layoutSignature.isPaginationCompatibleWith(currentSignature))) {
                    snapshot = captureLivePaginationSnapshot();
                    snapshotSource = "live_reflow";
                }
                if (snapshot == null || snapshot.pageWidth <= 0 || snapshot.regularPageHeight <= 0) {
                    Log.w(TAG, "[时序] 初始排版缓存未命中且快照无效，走完整分页兜底 - chapter="
                            + chapterIndex + " reason=invalid_reflow_snapshot");
                    forceInitialFullPagination = true;
                } else {
                    Log.d(TAG, "[时序] 初始排版缓存未命中，启动渐进分页 - chapter="
                            + chapterIndex + " source=" + snapshotSource);
                    startInitialProgressivePagination(
                            chapterIndex,
                            anchorOffset,
                            shouldResetInitialPosition,
                            snapshot,
                            snapshotSource
                    );
                    lastAppliedDoublePageActive = isDoublePageActive();
                    return;
                }
            }
            if (forceInitialFullPagination && !cacheHit) {
                Log.d(TAG, "[时序] 初始排版走完整分页兜底 - chapter=" + chapterIndex);
            }
            navigation.openChapter(chapterIndex, anchorOffset, false, 0);
            if (completingInitialReflow) {
                finishInitialReflowAfterVisiblePage(chapterIndex, shouldResetInitialPosition, true);
            } else if (shouldResetInitialPosition) {
                resetInitialPositionRequest();
            }
            lastAppliedDoublePageActive = isDoublePageActive();
        });
    }

    private void runAfterNextPageLayout(int generation, Runnable action) {
        if (!activity.isReaderActive()) {
            return;
        }
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
            if (!activity.isReaderActive()) {
                return;
            }
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
        int availableHeight = getRegularReaderPageHeight();
        if (availableHeight <= 0) {
            return false;
        }
        int contentWidth = Math.max(0, pageWidth
                - views.pageCurrent.getPaddingLeft()
                - views.pageCurrent.getPaddingRight());
        if (contentWidth <= 0) {
            return false;
        }
        int tolerance = ui.dp(4);
        if (Math.abs(bodyHeight - availableHeight) > tolerance) {
            return false;
        }
        boolean doublePageActive = isDoublePageActive();
        int expectedRightVisibility = doublePageActive ? View.VISIBLE : View.GONE;
        int expectedGutterVisibility = doublePageActive && shouldShowDoublePageGutter()
                ? View.VISIBLE
                : View.GONE;
        if (!hasVisibility(views.pageCurrentRightPane, expectedRightVisibility)
                || !hasVisibility(views.pageCurrentGutter, expectedGutterVisibility)) {
            return false;
        }
        if (!doublePageActive) {
            return bodyWidth >= contentWidth - tolerance;
        }
        if (views.pageBodyCurrentRight != null
                && views.pageBodyCurrentRight.getVisibility() == View.VISIBLE
                && Math.abs(views.pageBodyCurrentRight.getHeight() - availableHeight) > tolerance) {
            return false;
        }
        int expectedPaneWidth = Math.max(0, (contentWidth - doublePageGutterWidth()) / 2);
        return Math.abs(bodyWidth - expectedPaneWidth) <= tolerance;
    }

    public boolean shouldShowDoublePageGutter() {
        return true;
    }

    private int doublePageGutterWidth() {
        if (!shouldShowDoublePageGutter()) {
            return 0;
        }
        if (views.pageCurrentGutter == null) {
            return ui.dp(22);
        }
        return Math.max(views.pageCurrentGutter.getWidth(), ui.dp(22));
    }

    private boolean hasVisibility(View view, int visibility) {
        return view == null || view.getVisibility() == visibility;
    }

    private List<PageSlice> sanitizePageSlices(List<PageSlice> pages) {
        if (pages == null || pages.isEmpty()) {
            return new ArrayList<>();
        }
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

    private PaginationSnapshot captureLivePaginationSnapshot() {
        if (views.pageBodyCurrent == null || !isPaginationLayoutReady()) {
            return null;
        }
        int pageWidth = getReaderPageTextWidth();
        int regularPageHeight = getRegularReaderPageHeight();
        if (pageWidth <= 0 || regularPageHeight <= 0) {
            return null;
        }
        return new PaginationSnapshot(
                pageWidth,
                regularPageHeight,
                views.pageBodyCurrent.getLineSpacingExtra(),
                views.pageBodyCurrent.getPaint(),
                captureCurrentLayoutSignature(),
                resolveChapterTitleTypeface(),
                resolveChapterTitleTextSizePx(),
                getChapterTitleBodyMarginPx(),
                computeParagraphIndentPx()
        );
    }

    public void capturePaginationSnapshot() {
        PaginationSnapshot snapshot = captureLivePaginationSnapshot();
        if (snapshot == null) {
            Log.d(TAG, "[时序] 捕获初始分页快照失败 - layout尚未就绪");
            return;
        }
        cachedPaginationSnapshot = snapshot;
        if (snapshot.layoutSignature != null) {
            activateLayoutSignature(snapshot.layoutSignature);
        }
        Log.d(TAG, "[时序] 捕获初始分页快照 - w=" + snapshot.pageWidth
                + " h=" + snapshot.regularPageHeight
                + " hasSignature=" + (snapshot.layoutSignature != null));
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

    private static final class PartialPagination {
        final int chapterIndex;
        final List<PageSlice> pages;
        final int targetPageIndex;
        final ReaderLayoutSignature layoutSignature;

        private PartialPagination(
                int chapterIndex,
                List<PageSlice> pages,
                int targetPageIndex,
                ReaderLayoutSignature layoutSignature
        ) {
            this.chapterIndex = chapterIndex;
            this.pages = pages == null ? new ArrayList<>() : pages;
            this.targetPageIndex = Math.max(targetPageIndex, 0);
            this.layoutSignature = layoutSignature;
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
