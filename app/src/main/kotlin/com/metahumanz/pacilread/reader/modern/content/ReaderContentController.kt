package com.metahumanz.pacilread.reader.modern.content

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.ChapterRecord
import com.metahumanz.pacilread.model.ReplacementRuleRecord
import com.metahumanz.pacilread.reader.PageSlice
import com.metahumanz.pacilread.reader.ReaderPaginator
import com.metahumanz.pacilread.reader.ReaderParagraphBottomSpacingSpan
import com.metahumanz.pacilread.reader.ReaderTitleSpan
import com.metahumanz.pacilread.reader.ReplacementEngine
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity
import com.metahumanz.pacilread.reader.modern.ReaderRuntime
import com.metahumanz.pacilread.reader.modern.ReaderSessionState
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController
import com.metahumanz.pacilread.reader.modern.paging.ReaderPagingAnimator
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController
import com.metahumanz.pacilread.sync.WebDavProgressSyncCoordinator
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.regex.Pattern

class ReaderContentController(
    private val activity: ModernReaderActivity,
    private val runtime: ReaderRuntime,
    private val views: ReaderViewRefs,
    private val state: ReaderSessionState,
    private val ui: ReaderUiUtils,
) {
    private val processedChapterLruCache = LruCache<Int, String>(100)
    private val processedChapterLengthCache: MutableMap<Int, Int> = HashMap()
    private val saveProgressRunnable = Runnable { persistProgress() }
    private val scheduledReflowRunnable = Runnable { performScheduledReflow() }

    private var navigation: ReaderNavigationController? = null
    private var style: ReaderStyleController? = null
    private var paging: ReaderPagingAnimator? = null
    private var chrome: ReaderChromeController? = null
    private var pendingReflowChapterIndex = -1
    private var pendingReflowAnchorOffset = 0
    private var reflowGeneration = 0
    private var initialReflowPending = false
    private var deferReflow = false
    private var initialReflowDeferred = false
    private var cacheHit = false
    private var onInitialReflowComplete: Runnable? = null
    private var lastAppliedDoublePageActive: Boolean? = null

    private var waitingForPaginationChapterIndex = -1

    @Volatile
    private var waitingForProgressiveChapterIndex = -1

    @Volatile
    private var progressivePaginationGeneration = 0

    private var forceInitialFullPagination = false
    private var initialVisiblePageBound = false
    private var activePartialPagination: PartialPagination? = null
    private var runningProgressiveSignature: ReaderLayoutSignature? = null
    private var runningProgressiveSource: String? = null
    private val progressSyncLock = Object()
    private var initialRemoteProgressSyncPending = false
    private var initialRemoteProgressBaselineLastReadAt = 0L
    private var initialRemoteProgressBaselineIndex = 0
    private var initialRemoteProgressBaselineOffset = 0
    private var deferredProgressUpload: DeferredProgressUpload? = null

    @Volatile
    private var pendingRemoteProgressSuggestion: RemoteProgressSuggestion? = null

    @Volatile
    private var pendingRemoteProgressComparison: RemoteProgressComparison? = null

    @Volatile
    private var cachedPaginationSnapshot: PaginationSnapshot? = null

    init {
        views.keepLocalProgressButton.setOnClickListener { resolveRemoteProgressSuggestion(false) }
        views.jumpRemoteProgressButton.setOnClickListener { resolveRemoteProgressSuggestion(true) }
    }

    fun attachControllers(
        navigation: ReaderNavigationController,
        style: ReaderStyleController,
        paging: ReaderPagingAnimator,
        chrome: ReaderChromeController,
    ) {
        this.navigation = navigation
        this.style = style
        this.paging = paging
        this.chrome = chrome
    }

    fun setDeferReflow(defer: Boolean) {
        deferReflow = defer
        if (defer) {
            initialReflowDeferred = true
        }
    }

    fun performDeferredInitialReflow(onComplete: Runnable?) {
        if (!activity.isReaderActive) return
        Log.d(TAG, "[时序] performDeferredInitialReflow 被调用 - deferred=$initialReflowDeferred")
        deferReflow = false
        onInitialReflowComplete = onComplete
        if (!initialReflowDeferred) {
            Log.d(TAG, "[时序] performDeferredInitialReflow 提前返回 - initialReflowDeferred=false")
            return
        }
        if (initialVisiblePageBound) {
            Log.d(TAG, "[时序] performDeferredInitialReflow 提前返回 - 首屏已由渐进分页绑定")
            initialReflowDeferred = false
            deferReflow = false
            val callback = onInitialReflowComplete
            if (callback != null) {
                onInitialReflowComplete = null
                callback.run()
            }
            return
        }
        initialReflowDeferred = false
        if (state.book == null || state.chapters.isEmpty()) {
            Log.d(TAG, "[时序] performDeferredInitialReflow 提前返回 - book/chapters 未就绪")
            return
        }
        val chapterIndex = state.currentChapterIndex
        val anchorOffset = state.sessionStartOffset
        pendingReflowChapterIndex = chapterIndex
        pendingReflowAnchorOffset = anchorOffset
        initialReflowPending = true
        reflowGeneration++
        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable)
        Log.d(TAG, "[时序] 立即触发 performScheduledReflow - chapter=$chapterIndex gen=$reflowGeneration")
        runtime.mainHandler.post(scheduledReflowRunnable)
    }

    private fun startBackgroundPagination(chapterIndex: Int) {
        if (!activity.isReaderActive) return
        val anchor = views.pageCurrent ?: views.pageBodyCurrent
        anchor.post { startBackgroundPaginationAfterLayout(chapterIndex) }
    }

    private fun startBackgroundPaginationAfterLayout(chapterIndex: Int) {
        if (!activity.isReaderActive) return
        val pageWidth = getReaderPageTextWidth()
        val pageHeight = getRegularReaderPageHeight()
        if (pageWidth <= 0 || pageHeight <= 0) {
            Log.d(TAG, "[时序] 后台分页尺寸无效 w=$pageWidth h=$pageHeight - 重试")
            val anchor = views.pageCurrent ?: views.pageBodyCurrent
            anchor.post { startBackgroundPaginationAfterLayoutRetry(chapterIndex) }
            return
        }
        Log.d(TAG, "[时序] 后台分页尺寸就绪 w=$pageWidth h=$pageHeight - 启动")
        val sig = captureCurrentLayoutSignature()
        if (sig != null) {
            activateLayoutSignature(sig)
        }
        launchBackgroundPagination(chapterIndex, pageWidth, pageHeight)
    }

    private fun startBackgroundPaginationAfterLayoutRetry(chapterIndex: Int) {
        if (!activity.isReaderActive) return
        val pageWidth = getReaderPageTextWidth()
        val pageHeight = getRegularReaderPageHeight()
        if (pageWidth <= 0 || pageHeight <= 0) return
        val sig = captureCurrentLayoutSignature()
        if (sig != null) {
            activateLayoutSignature(sig)
        }
        launchBackgroundPagination(chapterIndex, pageWidth, pageHeight)
    }

    private fun launchBackgroundPagination(chapterIndex: Int, pageWidth: Int, pageHeight: Int) {
        if (!activity.isReaderActive) return
        if (PAGE_CACHE.contains(chapterIndex)) return
        Log.d(TAG, "[时序] 后台分页开始执行 - chapter=$chapterIndex w=$pageWidth h=$pageHeight")
        val startTime = System.currentTimeMillis()
        val lineSpacing = views.pageBodyCurrent.lineSpacingExtra
        val basePaint = TextPaint(views.pageBodyCurrent.paint)
        val indentPx = computeParagraphIndentPx()
        val titleTypeface = resolveChapterTitleTypeface()
        val titleTextSize = resolveChapterTitleTextSizePx()
        val titleMargin = getChapterTitleBodyMarginPx()
        val sig = captureCurrentLayoutSignature()

        runtime.safeExecutePagination(Runnable {
            if (!activity.isReaderActive) return@Runnable
            if (PAGE_CACHE.contains(chapterIndex)) return@Runnable
            val snapshot = PaginationSnapshot(
                pageWidth,
                pageHeight,
                lineSpacing,
                basePaint,
                sig,
                titleTypeface,
                titleTextSize,
                titleMargin,
                indentPx,
            )
            val processed = getProcessedChapterText(chapterIndex)
            if (processed.isEmpty()) return@Runnable
            val display = buildDisplayChapterTextForBackground(chapterIndex, snapshot)
            val paint = TextPaint(snapshot.basePaint)
            val pages = sanitizePageSlices(
                ReaderPaginator.paginate(
                    display.text,
                    paint,
                    snapshot.pageWidth,
                    snapshot.regularPageHeight,
                    snapshot.regularPageHeight,
                    snapshot.lineSpacingExtra,
                    display.bodyStartIndex,
                ),
            )
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "[时序] 后台分页完成 - chapter=$chapterIndex 页数=${pages.size} 耗时=${elapsed}ms")
            val capturedSig = sig
            activity.runOnReaderUiThread {
                val currentSig = captureCurrentLayoutSignature()
                if (capturedSig != null && currentSig != null &&
                    !capturedSig.isPaginationCompatibleWith(currentSig)
                ) {
                    Log.d(TAG, "[时序] 后台分页结果丢弃 - layout签名已变化 chapter=$chapterIndex")
                    return@runOnReaderUiThread
                }
                activateLayoutSignature(currentSig)
                putPagesForActiveLayout(chapterIndex, pages)
                val partial = activePartialPagination
                val hadPartial = partial != null && partial.chapterIndex == chapterIndex
                clearPartialPaginationForChapter(chapterIndex)
                if (hadPartial && state.currentChapterIndex == chapterIndex) {
                    Log.d(TAG, "[时序] 完整分页替换首屏partial - chapter=$chapterIndex")
                    chrome?.updateUiAfterPageChange()
                    paging?.schedulePagingSnapshotWarmup()
                    prewarmAdjacentChapters(chapterIndex)
                }
                val isInitialLoad = initialReflowDeferred
                val isWaiting = waitingForPaginationChapterIndex == chapterIndex
                if (isInitialLoad || isWaiting) {
                    Log.d(
                        TAG,
                        "[时序] 后台分页触发reflow - chapter=$chapterIndex deferred=$isInitialLoad waiting=$isWaiting",
                    )
                    if (isWaiting) {
                        waitingForPaginationChapterIndex = -1
                        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable)
                    }
                    runtime.mainHandler.post(scheduledReflowRunnable)
                }
            }
        }, "background pagination")
    }

    fun isCacheHit(): Boolean = cacheHit

    fun loadBook() {
        PAGE_CACHE.setBookId(state.bookId)
        if (lastCachedBookId == state.bookId && cachedBook != null && cachedChapters.isNotEmpty()) {
            cacheHit = true
            state.book = cachedBook
            state.chapters.clear()
            state.chapters.addAll(cachedChapters)
            state.replacementRules.clear()
            state.replacementRules.addAll(cachedRules)

            val book = requireNotNull(state.book)
            val targetChapterIndex = resolveInitialChapterIndex(book, state.chapters)
            state.currentChapterIndex = targetChapterIndex
            val initialAnchorOffset = resolveInitialAnchorOffset(book.progressOffset)
            state.sessionStartOffset = initialAnchorOffset
            rememberChapterAnchor(targetChapterIndex, initialAnchorOffset)
            prepareInitialRemoteProgressSync()
            if (!deferReflow) {
                style?.applyReaderSettings()
            }
            activity.onReaderBookLoaded()
            chrome?.updateReaderHud()
            Log.d(TAG, "[时序] loadBook 缓存命中 UI回调 - chapter=$targetChapterIndex")
            if (deferReflow) {
                initialReflowDeferred = true
                clearPartialPagination()
                resetInitialPaginationState()
                startInitialProgressivePaginationAfterSnapshot(0, false)
                prewarmChapterText(targetChapterIndex)
                Log.d(TAG, "[时序] 初始加载仅预热正文(缓存命中) - chapter=$targetChapterIndex")
            } else {
                resetInitialPaginationState()
                Log.d(TAG, "[时序] 调度初始渐进分页(缓存命中) - chapter=$targetChapterIndex")
                scheduleInitialReflowAfterLayout(targetChapterIndex, initialAnchorOffset)
            }
            scheduleRemoteProgressSync()
            return
        }

        runtime.safeExecute(Runnable {
            if (!activity.isReaderActive) return@Runnable
            try {
                val loadedBook = runtime.databaseHelper.getBook(state.bookId)
                val loadedChapters = runtime.databaseHelper.getChapters(state.bookId, false)
                val loadedRules = runtime.databaseHelper.getReplacementRules(state.bookId)

                var prewarmedText: String? = null
                var prewarmChapterIndex = -1
                if (loadedBook != null && loadedChapters.isNotEmpty()) {
                    val targetIndex = resolveInitialChapterIndex(loadedBook, loadedChapters)
                    if (targetIndex >= 0 && targetIndex < loadedChapters.size) {
                        val targetChapter = loadedChapters[targetIndex]
                        val fullChapter = runtime.databaseHelper.getChapterContent(targetChapter.id)
                        if (fullChapter != null) {
                            targetChapter.bodyText = fullChapter.bodyText
                        }
                        var body = targetChapter.bodyText ?: ""
                        if (isVolumeHeadingWithoutBody(targetChapter, body)) {
                            body = ""
                        }
                        prewarmedText = ReplacementEngine.apply(body, loadedRules)
                        prewarmChapterIndex = targetIndex
                    }
                }
                val finalPrewarmedText = prewarmedText
                val finalPrewarmIndex = prewarmChapterIndex

                activity.runOnReaderUiThread {
                    if (loadedBook == null || loadedChapters.isEmpty()) {
                        ui.showToast("书籍不存在或内容为空")
                        activity.finishReaderActivity()
                        return@runOnReaderUiThread
                    }

                    lastCachedBookId = state.bookId
                    cacheHit = false
                    cachedBook = loadedBook
                    cachedChapters.clear()
                    cachedChapters.addAll(loadedChapters)
                    cachedRules.clear()
                    cachedRules.addAll(loadedRules)
                    clearAllPageSliceCaches()

                    state.book = loadedBook
                    state.chapters.clear()
                    state.chapters.addAll(loadedChapters)
                    state.replacementRules.clear()
                    state.replacementRules.addAll(loadedRules)

                    val targetChapterIndex = resolveInitialChapterIndex(loadedBook, state.chapters)
                    state.currentChapterIndex = targetChapterIndex
                    val initialAnchorOffset = resolveInitialAnchorOffset(loadedBook.progressOffset)
                    state.sessionStartOffset = initialAnchorOffset
                    rememberChapterAnchor(targetChapterIndex, initialAnchorOffset)
                    prepareInitialRemoteProgressSync()

                    if (finalPrewarmedText != null && finalPrewarmIndex >= 0) {
                        synchronized(processedChapterLruCache) {
                            processedChapterLruCache.put(finalPrewarmIndex, finalPrewarmedText)
                        }
                        synchronized(processedChapterLengthCache) {
                            processedChapterLengthCache[finalPrewarmIndex] = finalPrewarmedText.length
                        }
                    }

                    if (!deferReflow) {
                        style?.applyReaderSettings()
                    }
                    activity.onReaderBookLoaded()
                    chrome?.updateReaderHud()
                    Log.d(TAG, "[时序] loadBook 缓存未命中 UI回调 - chapter=$targetChapterIndex")
                    if (deferReflow) {
                        initialReflowDeferred = true
                        resetInitialPaginationState()
                        startInitialProgressivePaginationAfterSnapshot(0, false)
                        prewarmChapterText(targetChapterIndex)
                        Log.d(TAG, "[时序] 初始加载仅预热正文(缓存未命中) - chapter=$targetChapterIndex")
                    } else {
                        resetInitialPaginationState()
                        Log.d(TAG, "[时序] 调度初始渐进分页(缓存未命中) - chapter=$targetChapterIndex")
                        scheduleInitialReflowAfterLayout(targetChapterIndex, initialAnchorOffset)
                    }
                    scheduleRemoteProgressSync()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to load reader state", error)
                activity.runOnReaderUiThread {
                    ui.showToast("打开书籍失败: ${readableError(error)}")
                    activity.finishReaderActivity()
                }
            }
        }, "load reader book")
    }

    fun persistProgress() {
        val book = state.book ?: return
        if (state.chapters.isEmpty()) return
        val position = captureCurrentReadingPosition()
        val safeChapterIndex = ui.clamp(position.chapterIndex, 0, state.chapters.size - 1)
        val offset = Math.max(position.chapterOffset, 0)
        val chapter = state.chapters[safeChapterIndex]
        val chapterOrderIndex = chapter.orderIndex
        val persistedAt = System.currentTimeMillis()
        book.progressIndex = chapterOrderIndex
        book.progressOffset = offset
        book.lastReadAt = persistedAt
        runtime.databaseHelper.updateProgress(book.id, chapterOrderIndex, offset)
        if (isAtBookEnd(safeChapterIndex, position.pageIndex)) {
            book.readingStatus = BookRecord.STATUS_FINISHED
            runtime.databaseHelper.updateBookReadingStatus(book.id, BookRecord.STATUS_FINISHED)
        } else if (BookRecord.STATUS_FINISHED != book.readingStatus) {
            book.readingStatus = BookRecord.STATUS_READING
        }
        if (runtime.settingsStore.isWebDavEnabled) {
            val bookSnapshot = snapshotBookForProgressUpload(book)
            val chapterSnapshot = snapshotChapterForProgressUpload(chapter)
            if (deferProgressUploadIfInitialSyncPending(bookSnapshot, chapterSnapshot, offset)) {
                return
            }
            uploadProgressSnapshot(bookSnapshot, chapterSnapshot, offset)
        }
    }

    fun scheduleProgressSave() {
        runtime.mainHandler.removeCallbacks(saveProgressRunnable)
        runtime.mainHandler.postDelayed(saveProgressRunnable, 600L)
    }

    fun cancelPendingProgressSave() {
        runtime.mainHandler.removeCallbacks(saveProgressRunnable)
    }

    fun syncFromWebDav(silent: Boolean) {
        if (!runtime.settingsStore.isWebDavEnabled) {
            if (!silent) {
                ui.showToast("尚未启用 WebDAV 进度同步")
            }
            return
        }
        if (pendingRemoteProgressSuggestion != null) return
        val initialComparison = captureInitialRemoteProgressComparison()
        runtime.safeExecute(Runnable {
            var waitingForUserDecision = false
            try {
                if (!activity.isReaderActive) return@Runnable
                val currentBook = state.book ?: return@Runnable
                val baseline = if (initialComparison == null) {
                    null
                } else {
                    WebDavProgressSyncCoordinator.ProgressBaseline(
                        initialComparison.lastReadAt,
                        initialComparison.progressIndex,
                        initialComparison.progressOffset,
                    )
                }
                val result = runtime.progressSyncCoordinator.findRemoteProgressIfNeeded(currentBook, baseline)
                if (result.checkedRemote && !result.remoteAvailable) {
                    if (!silent) {
                        activity.runOnReaderUiThread { ui.showToast("云端暂时没有可恢复的进度") }
                    }
                    return@Runnable
                }
                val suggestion = if (result.remoteSuggested) {
                    buildRemoteProgressSuggestion(
                        result.chapterOrderIndex,
                        result.chapterPosition,
                        result.chapterTime,
                    )
                } else if (result.skippedFresh) {
                    buildFreshDatabaseProgressSuggestion(initialComparison)
                } else {
                    null
                }
                if (isSimilarToLocalProgress(suggestion, initialComparison)) return@Runnable
                if (suggestion == null) return@Runnable
                if (!activity.isReaderActive || state.chapters.isEmpty()) return@Runnable
                pendingRemoteProgressSuggestion = suggestion
                pendingRemoteProgressComparison = initialComparison
                waitingForUserDecision = true
                activity.runOnReaderUiThread { showRemoteProgressSuggestion(suggestion) }
            } catch (error: Exception) {
                if (!silent) {
                    activity.runOnReaderUiThread { ui.showToast("同步失败: ${error.message}") }
                }
            } finally {
                if (!waitingForUserDecision) {
                    val upload = finishInitialRemoteProgressSync(initialComparison, false)
                    if (upload != null) {
                        uploadProgressSnapshot(upload.book, upload.chapter, upload.offset)
                    }
                }
            }
        }, "sync reader progress from WebDAV")
    }

    private fun buildFreshDatabaseProgressSuggestion(
        initialComparison: RemoteProgressComparison?,
    ): RemoteProgressSuggestion? {
        val book = state.book
        if (initialComparison == null || book == null || state.chapters.isEmpty()) return null
        val latestBook = runtime.databaseHelper.getBook(book.id)
        if (latestBook == null || !isProgressNewerThanInitial(latestBook, initialComparison)) return null
        return buildRemoteProgressSuggestion(
            latestBook.progressIndex,
            latestBook.progressOffset,
            latestBook.lastReadAt,
        )
    }

    private fun isProgressNewerThanInitial(
        latestBook: BookRecord?,
        initialComparison: RemoteProgressComparison?,
    ): Boolean {
        if (latestBook == null || initialComparison == null) return false
        val positionChanged = latestBook.progressIndex != initialComparison.progressIndex ||
            latestBook.progressOffset != initialComparison.progressOffset
        if (!positionChanged) {
            return latestBook.lastReadAt > initialComparison.lastReadAt
        }
        val initialEmpty = initialComparison.lastReadAt <= 0L &&
            initialComparison.progressIndex == 0 &&
            initialComparison.progressOffset == 0
        return initialEmpty || latestBook.lastReadAt > initialComparison.lastReadAt
    }

    private fun isSimilarToLocalProgress(
        suggestion: RemoteProgressSuggestion?,
        initialComparison: RemoteProgressComparison?,
    ): Boolean {
        val book = state.book
        if (suggestion == null || book == null) return false
        val localChapterOrderIndex = initialComparison?.progressIndex ?: book.progressIndex
        val localOffset = initialComparison?.progressOffset ?: book.progressOffset
        return suggestion.chapterOrderIndex == localChapterOrderIndex &&
            Math.abs(suggestion.chapterOffset.toLong() - Math.max(localOffset, 0).toLong()) <=
            SIMILAR_PROGRESS_MAX_OFFSET_DELTA
    }

    private fun scheduleRemoteProgressSync() {
        runtime.mainHandler.postDelayed({
            if (!activity.isReaderActive) return@postDelayed
            syncFromWebDav(true)
        }, 250L)
    }

    private fun buildRemoteProgressSuggestion(
        chapterOrderIndex: Int,
        chapterOffset: Int,
        chapterTime: Long,
    ): RemoteProgressSuggestion? {
        if (state.chapters.isEmpty()) return null
        val chapterIndex = ui.clamp(
            requireNavigation().chapterIndexFromOrder(chapterOrderIndex),
            0,
            state.chapters.size - 1,
        )
        val chapter = state.chapters[chapterIndex]
        val safeOffset = Math.max(chapterOffset, 0)
        val title = if (chapter.title.isNullOrBlank()) {
            "第 ${chapterIndex + 1} 章"
        } else {
            chapter.title!!.trim()
        }
        return RemoteProgressSuggestion(
            chapterIndex,
            chapter.orderIndex,
            safeOffset,
            chapterTime,
            title,
            buildProgressExcerpt(chapter, safeOffset),
        )
    }

    private fun buildProgressExcerpt(chapter: ChapterRecord, offset: Int): String {
        val excerpt = runtime.databaseHelper.getChapterTextExcerpt(chapter.id, offset, 64)
        if (excerpt.isEmpty()) {
            return "打开后可从该章的云端位置继续阅读"
        }
        return excerpt
    }

    private fun showRemoteProgressSuggestion(suggestion: RemoteProgressSuggestion?) {
        if (suggestion == null || pendingRemoteProgressSuggestion !== suggestion) return
        views.remoteProgressTitle.text = "云端进度 · ${suggestion.chapterTitle}"
        views.remoteProgressDetail.text = "大约读到：${suggestion.excerpt}"
        views.remoteProgressBanner.animate().cancel()
        views.remoteProgressBanner.alpha = 0f
        views.remoteProgressBanner.translationY = -ui.dp(16).toFloat()
        views.remoteProgressBanner.visibility = View.VISIBLE
        views.remoteProgressBanner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
    }

    private fun resolveRemoteProgressSuggestion(jump: Boolean) {
        val suggestion = pendingRemoteProgressSuggestion ?: return
        val comparison = pendingRemoteProgressComparison
        pendingRemoteProgressSuggestion = null
        pendingRemoteProgressComparison = null
        hideRemoteProgressSuggestion()
        val book = state.book
        if (jump && book != null) {
            runtime.databaseHelper.updateProgressFromRemote(
                book.id,
                suggestion.chapterOrderIndex,
                suggestion.chapterOffset,
                suggestion.chapterTime,
            )
            book.progressIndex = suggestion.chapterOrderIndex
            book.progressOffset = suggestion.chapterOffset
            book.lastReadAt = suggestion.chapterTime
            finishInitialRemoteProgressSync(comparison, true)
            scheduleReflowAfterLayout(suggestion.chapterIndex, suggestion.chapterOffset)
            return
        }
        val upload = finishInitialRemoteProgressSync(comparison, false)
        if (upload != null) {
            uploadProgressSnapshot(upload.book, upload.chapter, upload.offset)
        }
    }

    private fun hideRemoteProgressSuggestion() {
        views.remoteProgressBanner.animate().cancel()
        views.remoteProgressBanner.animate()
            .alpha(0f)
            .translationY(-ui.dp(12).toFloat())
            .setDuration(140L)
            .withEndAction {
                if (pendingRemoteProgressSuggestion == null) {
                    views.remoteProgressBanner.visibility = View.GONE
                }
            }
            .start()
    }

    fun releasePendingRemoteProgressSuggestion() {
        if (pendingRemoteProgressSuggestion == null) return
        resolveRemoteProgressSuggestion(false)
        views.remoteProgressBanner.animate().cancel()
        views.remoteProgressBanner.visibility = View.GONE
    }

    private fun prepareInitialRemoteProgressSync() {
        synchronized(progressSyncLock) {
            deferredProgressUpload = null
            val book = state.book
            if (!runtime.settingsStore.isWebDavEnabled || book == null) {
                initialRemoteProgressSyncPending = false
                return@synchronized
            }
            initialRemoteProgressSyncPending = true
            initialRemoteProgressBaselineLastReadAt = book.lastReadAt
            initialRemoteProgressBaselineIndex = book.progressIndex
            initialRemoteProgressBaselineOffset = book.progressOffset
        }
    }

    private fun captureInitialRemoteProgressComparison(): RemoteProgressComparison? = synchronized(progressSyncLock) {
        if (!initialRemoteProgressSyncPending) {
            null
        } else {
            RemoteProgressComparison(
                initialRemoteProgressBaselineLastReadAt,
                initialRemoteProgressBaselineIndex,
                initialRemoteProgressBaselineOffset,
            )
        }
    }

    private fun deferProgressUploadIfInitialSyncPending(
        bookSnapshot: BookRecord,
        chapterSnapshot: ChapterRecord,
        offset: Int,
    ): Boolean = synchronized(progressSyncLock) {
        if (!initialRemoteProgressSyncPending) {
            false
        } else {
            deferredProgressUpload = DeferredProgressUpload(bookSnapshot, chapterSnapshot, offset)
            true
        }
    }

    private fun finishInitialRemoteProgressSync(
        comparison: RemoteProgressComparison?,
        remoteApplied: Boolean,
    ): DeferredProgressUpload? {
        if (comparison == null) return null
        return synchronized(progressSyncLock) {
            if (!initialRemoteProgressSyncPending) {
                null
            } else {
                initialRemoteProgressSyncPending = false
                initialRemoteProgressBaselineLastReadAt = 0L
                initialRemoteProgressBaselineIndex = 0
                initialRemoteProgressBaselineOffset = 0
                val upload = if (remoteApplied) null else deferredProgressUpload
                deferredProgressUpload = null
                upload
            }
        }
    }

    private fun uploadProgressSnapshot(bookSnapshot: BookRecord, chapterSnapshot: ChapterRecord, offset: Int) {
        val webDavClient = runtime.webDavClient
        PROGRESS_UPLOAD_EXECUTOR.execute {
            try {
                webDavClient.ensureProgressDirectory()
                webDavClient.uploadProgress(bookSnapshot, chapterSnapshot, offset)
            } catch (error: Exception) {
                Log.w(TAG, "Failed to upload reader progress", error)
            }
        }
    }

    private fun snapshotBookForProgressUpload(source: BookRecord): BookRecord {
        val snapshot = BookRecord()
        snapshot.id = source.id
        snapshot.title = source.title
        snapshot.author = source.author
        snapshot.readingStatsKey = source.readingStatsKey
        snapshot.progressIndex = source.progressIndex
        snapshot.progressOffset = source.progressOffset
        snapshot.lastReadAt = source.lastReadAt
        snapshot.copyExtendedFieldsFrom(source)
        return snapshot
    }

    private fun snapshotChapterForProgressUpload(source: ChapterRecord): ChapterRecord {
        val snapshot = ChapterRecord()
        snapshot.id = source.id
        snapshot.bookId = source.bookId
        snapshot.title = source.title
        snapshot.orderIndex = source.orderIndex
        return snapshot
    }

    fun getPagesForChapter(chapterIndex: Int): List<PageSlice> {
        ensurePaginationCacheMatchesLayout()
        val cached = PAGE_CACHE.get(chapterIndex)
        if (cached != null) {
            Log.d(TAG, "[时序] getPagesForChapter 缓存命中 - chapter=$chapterIndex")
            return cached
        }
        Log.w(TAG, "[时序] getPagesForChapter 缓存未命中! 主线程分页 - chapter=$chapterIndex")
        val t0 = System.currentTimeMillis()
        val display = buildDisplayChapterText(chapterIndex)
        val pageWidth = getReaderPageTextWidth()
        val regularPageHeight = getRegularReaderPageHeight()
        val paint = TextPaint(views.pageBodyCurrent.paint)
        val pages = sanitizePageSlices(
            ReaderPaginator.paginate(
                display.text,
                paint,
                pageWidth,
                regularPageHeight,
                regularPageHeight,
                views.pageBodyCurrent.lineSpacingExtra,
                display.bodyStartIndex,
            ),
        )
        putPagesForActiveLayout(chapterIndex, pages)
        clearPartialPaginationForChapter(chapterIndex)
        Log.w(
            TAG,
            "[时序] 主线程分页完成 - chapter=$chapterIndex 页数=${pages.size} 耗时=${System.currentTimeMillis() - t0}ms",
        )
        return pages
    }

    fun getKnownPageCountForChapter(chapterIndex: Int): Int {
        ensurePaginationCacheMatchesLayout()
        val cached = PAGE_CACHE.get(chapterIndex)
        if (cached != null) {
            return cached.size
        }
        val partial = activePartialPagination
        if (partial != null && partial.chapterIndex == chapterIndex) {
            return partial.pages.size
        }
        return 1
    }

    fun isPageCountCompleteForChapter(chapterIndex: Int): Boolean {
        ensurePaginationCacheMatchesLayout()
        return PAGE_CACHE.contains(chapterIndex)
    }

    fun getReaderPageTextWidth(): Int {
        val pageCurrent = views.pageCurrent
        val width = if (pageCurrent.width > 0) pageCurrent.width else pageCurrent.measuredWidth
        if (width > 0) {
            val contentWidth = Math.max(0, width - pageCurrent.paddingLeft - pageCurrent.paddingRight)
            if (isDoublePageActive()) {
                return Math.max(0, (contentWidth - doublePageGutterWidth()) / 2)
            }
            return contentWidth
        }
        return 0
    }

    fun getRegularReaderPageHeight(): Int {
        val pageCurrent = views.pageCurrent
        val height = if (pageCurrent.height > 0) pageCurrent.height else pageCurrent.measuredHeight
        if (height > 0) {
            return Math.max(0, height - pageCurrent.paddingTop - pageCurrent.paddingBottom)
        }
        return 0
    }

    fun getChapterTitleBodyMarginPx(): Int {
        return Math.max(ui.dp(16), Math.round(views.pageTitleCurrent.textSize * 1.5f))
    }

    fun isDoublePageActive(): Boolean = ReaderDisplayModeHelper.isDoublePageActive(
        activity,
        runtime.settingsStore,
        views.pageStage.width,
        views.pageStage.height,
    )

    fun pagesPerScreen(): Int = if (isDoublePageActive()) 2 else 1

    fun getProcessedChapterText(chapterIndex: Int): String {
        synchronized(processedChapterLruCache) {
            val cached = processedChapterLruCache.get(chapterIndex)
            if (cached != null) return cached
        }
        val chapter = state.chapters[chapterIndex]
        if (chapter.bodyText == null) {
            val fullChapter = runtime.databaseHelper.getChapterContent(chapter.id)
            if (fullChapter != null) {
                chapter.bodyText = fullChapter.bodyText
                chapter.bodyHtml = fullChapter.bodyHtml
            }
        }
        var body = chapter.bodyText ?: ""
        if (isVolumeHeadingWithoutBody(chapter, body)) {
            body = ""
        }
        val processed = ReplacementEngine.apply(body, state.replacementRules)
        synchronized(processedChapterLruCache) {
            processedChapterLruCache.put(chapterIndex, processed)
        }
        synchronized(processedChapterLengthCache) {
            processedChapterLengthCache[chapterIndex] = processed.length
        }
        return processed
    }

    private fun isVolumeHeadingWithoutBody(chapter: ChapterRecord?, body: String?): Boolean {
        if (chapter == null || body == null || EMPTY_CHAPTER_TEXT_PLACEHOLDER != body.trim()) {
            return false
        }
        val title = chapter.title?.trim() ?: ""
        return VOLUME_CHAPTER_TITLE_PATTERN.matcher(title).matches()
    }

    fun prewarmChapterText(chapterIndex: Int) {
        if (state.book == null || state.chapters.isEmpty()) return
        if (chapterIndex < 0 || chapterIndex >= state.chapters.size) return
        val safeIndex = Math.max(0, Math.min(chapterIndex, state.chapters.size - 1))
        runtime.safeExecute(Runnable {
            if (!activity.isReaderActive) return@Runnable
            val textAlreadyCached = synchronized(processedChapterLruCache) {
                processedChapterLruCache.get(safeIndex) != null
            }
            if (!textAlreadyCached) {
                val chapter = state.chapters[safeIndex]
                if (chapter.bodyText == null) {
                    val fullChapter = runtime.databaseHelper.getChapterContent(chapter.id)
                    if (fullChapter != null) {
                        chapter.bodyText = fullChapter.bodyText
                        chapter.bodyHtml = fullChapter.bodyHtml
                    }
                }
                var body = chapter.bodyText ?: ""
                if (isVolumeHeadingWithoutBody(chapter, body)) {
                    body = ""
                }
                val processed = ReplacementEngine.apply(body, state.replacementRules)
                synchronized(processedChapterLruCache) {
                    processedChapterLruCache.put(safeIndex, processed)
                }
                synchronized(processedChapterLengthCache) {
                    processedChapterLengthCache[safeIndex] = processed.length
                }
            }
        }, "prewarm chapter text")
    }

    fun prewarmChapterTextAfterSnapshot() {
        if (!initialReflowDeferred) return
        startInitialProgressivePaginationAfterSnapshot(0, false)
    }

    private fun prewarmAdjacentChapters(currentChapterIndex: Int) {
        val nextIndex = currentChapterIndex + 1
        if (nextIndex < 0 || nextIndex >= state.chapters.size) return
        runtime.safeExecute(Runnable {
            if (!activity.isReaderActive) return@Runnable
            synchronized(processedChapterLruCache) {
                if (processedChapterLruCache.get(nextIndex) != null) return@Runnable
            }
            val chapter = state.chapters[nextIndex]
            if (chapter.bodyText == null) {
                val fullChapter = runtime.databaseHelper.getChapterContent(chapter.id)
                if (fullChapter != null) {
                    chapter.bodyText = fullChapter.bodyText
                    chapter.bodyHtml = fullChapter.bodyHtml
                }
            }
            var body = chapter.bodyText ?: ""
            if (isVolumeHeadingWithoutBody(chapter, body)) {
                body = ""
            }
            val processed = ReplacementEngine.apply(body, state.replacementRules)
            synchronized(processedChapterLruCache) {
                processedChapterLruCache.put(nextIndex, processed)
            }
            synchronized(processedChapterLengthCache) {
                processedChapterLengthCache[nextIndex] = processed.length
            }
        }, "prewarm adjacent chapter text")
    }

    private fun buildDisplayChapterText(chapterIndex: Int): DisplayChapterText {
        val body = buildDisplayBodyText(chapterIndex)
        val chapter = state.chapters[chapterIndex]
        if (!runtime.settingsStore.isChapterTitleVisible || !hasDisplayableChapterTitle(chapter)) {
            return DisplayChapterText(body, 0)
        }

        val builder = SpannableStringBuilder()
        val title = chapter.title!!.trim()
        val titleStart = builder.length
        builder.append(title)
        val titleEnd = builder.length
        builder.append('\n')
        val titleParagraphEnd = builder.length
        builder.setSpan(
            ReaderTitleSpan(resolveChapterTitleTypeface(), resolveChapterTitleTextSizePx()),
            titleStart,
            titleEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (runtime.settingsStore.chapterTitleAlignment == "center") {
            builder.setSpan(
                AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                titleStart,
                titleParagraphEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        val spacerStart = builder.length
        builder.append(' ')
        val spacerEnd = builder.length
        builder.append('\n')
        builder.setSpan(
            ForegroundColorSpan(Color.TRANSPARENT),
            spacerStart,
            spacerEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            FixedLineHeightSpan(getChapterTitleBodyMarginPx()),
            spacerStart,
            spacerEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        val bodyStartIndex = builder.length
        builder.append(body)
        return DisplayChapterText(builder, bodyStartIndex)
    }

    private fun buildDisplayBodyText(chapterIndex: Int): CharSequence {
        val processed = getProcessedChapterText(chapterIndex)
        val indentPx = computeParagraphIndentPx()
        val paragraphSpacingPx = computeParagraphSpacingPx()
        if (processed.isEmpty()) return processed
        val spannable = SpannableString(processed)
        var start = 0
        val length = processed.length
        while (start < length) {
            var end = start
            while (end < length && processed[end] != '\n') {
                end++
            }
            val paragraphLimit = if (end < length) end + 1 else end
            if (hasVisibleParagraphText(processed, start, end)) {
                if (indentPx > 0) {
                    spannable.setSpan(
                        LeadingMarginSpan.Standard(indentPx, 0),
                        start,
                        paragraphLimit,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (end < length && isNextLineVisible(processed, paragraphLimit) && paragraphSpacingPx > 0) {
                    spannable.setSpan(
                        ReaderParagraphBottomSpacingSpan(paragraphSpacingPx),
                        end,
                        paragraphLimit,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            } else if (end < length && paragraphSpacingPx > 0) {
                spannable.setSpan(
                    FixedLineHeightSpan(paragraphSpacingPx),
                    start,
                    paragraphLimit,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            start = paragraphLimit
        }
        return spannable
    }

    fun getProcessedChapterLength(chapterIndex: Int): Int {
        processedChapterLengthCache[chapterIndex]?.let { return it }
        val ch = if (chapterIndex >= 0 && chapterIndex < state.chapters.size) state.chapters[chapterIndex] else null
        if (ch != null && !ch.bodyText.isNullOrEmpty()) {
            if (isVolumeHeadingWithoutBody(ch, ch.bodyText)) {
                processedChapterLengthCache[chapterIndex] = 0
                return 0
            }
            val length = ch.bodyText!!.length
            processedChapterLengthCache[chapterIndex] = length
            return length
        }
        if (ch != null && ch.bodyTextSize > 0) {
            val estimated = Math.max(1, ch.bodyTextSize / 3).toInt()
            processedChapterLengthCache[chapterIndex] = estimated
            return estimated
        }
        val length = getProcessedChapterText(chapterIndex).length
        processedChapterLengthCache[chapterIndex] = length
        return length
    }

    fun getTotalProcessedBookLength(): Int {
        if (state.totalProcessedBookLength >= 0) {
            return state.totalProcessedBookLength
        }
        var total = 0
        for (i in state.chapters.indices) {
            total += getProcessedChapterLength(i)
        }
        state.totalProcessedBookLength = total
        return state.totalProcessedBookLength
    }

    fun currentCharOffset(): Int = captureCurrentReadingPosition().chapterOffset

    fun currentReadingStatsPageKey(): String {
        val book = state.book ?: return ""
        if (state.chapters.isEmpty()) return ""
        val chapterIndex = ui.clamp(state.currentChapterIndex, 0, state.chapters.size - 1)
        return "${book.id}:$chapterIndex:${Math.max(state.currentPageIndex, 0)}:${pagesPerScreen()}"
    }

    fun currentVisibleBodyCharCount(): Int {
        if (state.chapters.isEmpty()) return 0
        val chapterIndex = ui.clamp(state.currentChapterIndex, 0, state.chapters.size - 1)
        val pages = getPagesForChapter(chapterIndex)
        if (pages.isEmpty()) return 0
        val startPage = ui.clamp(state.currentPageIndex, 0, pages.size - 1)
        val endPage = Math.min(pages.size - 1, startPage + pagesPerScreen() - 1)
        var count = 0
        for (i in startPage..endPage) {
            val slice = pages[i]
            if (slice.hasBodyText()) {
                count += Math.max(0, slice.bodyEndInSlice - slice.bodyStartInSlice)
            }
        }
        return count
    }

    fun captureCurrentReadingPosition(): ReadingPosition {
        if (state.chapters.isEmpty()) {
            return ReadingPosition(
                Math.max(state.currentChapterIndex, 0),
                Math.max(state.currentPageIndex, 0),
                0,
            )
        }
        val safeChapterIndex = ui.clamp(state.currentChapterIndex, 0, state.chapters.size - 1)
        val safePageIndex = Math.max(state.currentPageIndex, 0)
        val offset = resolvePageStartOffset(safeChapterIndex, safePageIndex)
        rememberChapterAnchor(safeChapterIndex, safePageIndex, offset)
        return ReadingPosition(safeChapterIndex, safePageIndex, offset)
    }

    private fun resolvePageStartOffset(chapterIndex: Int, pageIndex: Int): Int {
        val partial = activePartialPagination
        if (partial != null &&
            partial.chapterIndex == chapterIndex &&
            pageIndex >= 0 &&
            pageIndex < partial.pages.size
        ) {
            return Math.max(partial.pages[pageIndex].start, 0)
        }
        val pages = PAGE_CACHE.get(chapterIndex)
        if (!pages.isNullOrEmpty()) {
            return Math.max(pages[ui.clamp(pageIndex, 0, pages.size - 1)].start, 0)
        }
        return fallbackChapterOffset(chapterIndex)
    }

    private fun isAtBookEnd(chapterIndex: Int, pageIndex: Int): Boolean {
        if (state.chapters.isEmpty() || chapterIndex < state.chapters.size - 1) return false
        val pages = getPagesForChapter(chapterIndex)
        if (pages.isEmpty()) return false
        return Math.max(pageIndex, 0) >= requireNavigation().lastSpreadStart(pages)
    }

    fun rememberCurrentPageAnchor(): Int = currentCharOffset()

    private fun rememberChapterAnchor(chapterIndex: Int, offset: Int) {
        rememberChapterAnchor(chapterIndex, 0, offset)
    }

    private fun rememberChapterAnchor(chapterIndex: Int, pageIndex: Int, offset: Int) {
        if (state.chapters.isEmpty()) {
            state.lastKnownChapterIndex = -1
            state.lastKnownPageIndex = -1
            state.lastKnownChapterOffset = 0
            return
        }
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        state.lastKnownChapterIndex = safeChapterIndex
        state.lastKnownPageIndex = Math.max(pageIndex, 0)
        state.lastKnownChapterOffset = Math.max(offset, 0)
    }

    private fun fallbackChapterOffset(chapterIndex: Int): Int {
        if (state.chapters.isEmpty()) return 0
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        if (state.lastKnownChapterIndex == safeChapterIndex) {
            return Math.max(state.lastKnownChapterOffset, 0)
        }
        val book = state.book
        if (book != null) {
            val chapter = state.chapters[safeChapterIndex]
            if (book.progressIndex == chapter.orderIndex) {
                return Math.max(book.progressOffset, 0)
            }
        }
        return if (safeChapterIndex == ui.clamp(state.currentChapterIndex, 0, state.chapters.size - 1)) {
            Math.max(state.sessionStartOffset, 0)
        } else {
            0
        }
    }

    class ReadingPosition(
        @JvmField val chapterIndex: Int,
        @JvmField val pageIndex: Int,
        @JvmField val chapterOffset: Int,
    )

    fun bookProgressPercentFor(chapterIndex: Int, chapterOffset: Int): Float {
        if (state.chapters.isEmpty()) return 0f
        val total = Math.max(getTotalProcessedBookLength(), 1)
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        var completed = 0
        for (i in 0 until safeChapterIndex) {
            completed += getProcessedChapterLength(i)
        }
        val safeOffset = ui.clamp(chapterOffset, 0, getProcessedChapterLength(safeChapterIndex))
        return Math.max(0f, Math.min(100f, (completed + safeOffset) * 100f / total))
    }

    fun buildBookmarkSummary(chapterIndex: Int, chapterOffset: Int, maxChars: Int): String {
        if (state.chapters.isEmpty()) return ""
        val safeChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
        val text = getProcessedChapterText(safeChapterIndex)
        if (text.isBlank()) return ""
        val safeOffset = ui.clamp(chapterOffset, 0, text.length)
        val end = Math.min(text.length, safeOffset + Math.max(maxChars, 24))
        var summary = text.substring(safeOffset, end).replace(Regex("\\s+"), " ").trim()
        if (summary.isEmpty() && safeOffset > 0) {
            val start = Math.max(0, safeOffset - Math.max(maxChars, 24))
            summary = text.substring(start, safeOffset).replace(Regex("\\s+"), " ").trim()
        }
        return summary
    }

    fun clearPageCache() {
        rememberCurrentPageAnchor()
        clearAllPageSliceCaches()
        clearPartialPagination()
    }

    fun clearAllReaderCaches() {
        rememberCurrentPageAnchor()
        processedChapterLruCache.evictAll()
        processedChapterLengthCache.clear()
        clearAllPageSliceCaches()
        clearPartialPagination()
        state.totalProcessedBookLength = -1
    }

    private fun clearAllPageSliceCaches() {
        PAGE_CACHE.clear()
    }

    private fun activateLayoutSignature(signature: ReaderLayoutSignature?): Boolean =
        PAGE_CACHE.activate(signature, Runnable { rememberCurrentPageAnchor() })

    private fun putPagesForActiveLayout(chapterIndex: Int, pages: List<PageSlice>) {
        PAGE_CACHE.put(chapterIndex, pages)
    }

    private fun clearPartialPagination() {
        activePartialPagination = null
    }

    private fun clearPartialPaginationForChapter(chapterIndex: Int) {
        val partial = activePartialPagination
        if (partial != null && partial.chapterIndex == chapterIndex) {
            activePartialPagination = null
        }
    }

    private fun hasAnyCachedPagesForChapter(chapterIndex: Int): Boolean = PAGE_CACHE.hasAny(chapterIndex)

    private fun isProgressivePaginationCancelled(chapterIndex: Int, paginationGeneration: Int): Boolean =
        paginationGeneration != progressivePaginationGeneration ||
            waitingForProgressiveChapterIndex != chapterIndex

    private fun clearRunningProgressiveState() {
        runningProgressiveSignature = null
        runningProgressiveSource = null
    }

    private fun cancelInitialProgressivePagination(chapterIndex: Int, reason: String) {
        if (waitingForProgressiveChapterIndex != chapterIndex) return
        Log.d(
            TAG,
            "[时序] 取消初始渐进分页 - chapter=$chapterIndex gen=$progressivePaginationGeneration reason=$reason source=$runningProgressiveSource",
        )
        waitingForProgressiveChapterIndex = -1
        progressivePaginationGeneration++
        clearRunningProgressiveState()
    }

    private fun resetInitialPaginationState() {
        waitingForProgressiveChapterIndex = -1
        waitingForPaginationChapterIndex = -1
        progressivePaginationGeneration++
        forceInitialFullPagination = false
        initialVisiblePageBound = false
        clearRunningProgressiveState()
        clearPartialPagination()
    }

    fun scheduleReflowAfterLayout(chapterIndex: Int, anchorOffset: Int) {
        if (!activity.isReaderActive) return
        if (state.book == null || state.chapters.isEmpty()) return
        if (deferReflow || initialReflowDeferred) return
        if (initialReflowPending) {
            reflowGeneration++
            runtime.mainHandler.removeCallbacks(scheduledReflowRunnable)
            runtime.mainHandler.postDelayed(scheduledReflowRunnable, REFLOW_DEBOUNCE_MS)
            return
        }
        scheduleReflowAfterLayoutInternal(chapterIndex, anchorOffset)
    }

    private fun scheduleInitialReflowAfterLayout(chapterIndex: Int, anchorOffset: Int) {
        if (!activity.isReaderActive) return
        initialReflowPending = true
        scheduleReflowAfterLayoutInternal(chapterIndex, anchorOffset)
    }

    private fun scheduleReflowAfterLayoutInternal(chapterIndex: Int, anchorOffset: Int) {
        if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
            pendingReflowChapterIndex = ui.clamp(state.restoredChapterIndex, 0, state.chapters.size - 1)
            pendingReflowAnchorOffset = Math.max(state.restoredProgressOffset, 0)
        } else if (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0) {
            pendingReflowChapterIndex = ui.clamp(
                requireNavigation().chapterIndexFromOrder(state.requestedChapterOrderIndex),
                0,
                state.chapters.size - 1,
            )
            pendingReflowAnchorOffset = Math.max(state.requestedChapterOffset, 0)
        } else {
            pendingReflowChapterIndex = ui.clamp(chapterIndex, 0, state.chapters.size - 1)
            pendingReflowAnchorOffset = Math.max(anchorOffset, 0)
        }
        reflowGeneration++
        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable)
        runtime.mainHandler.postDelayed(scheduledReflowRunnable, REFLOW_DEBOUNCE_MS)
    }

    fun cancelPendingReflow() {
        initialReflowPending = false
        waitingForProgressiveChapterIndex = -1
        waitingForPaginationChapterIndex = -1
        progressivePaginationGeneration++
        forceInitialFullPagination = false
        clearRunningProgressiveState()
        reflowGeneration++
        runtime.mainHandler.removeCallbacks(scheduledReflowRunnable)
    }

    fun onReaderInsetsChanged(suppressReflow: Boolean, paginationInsetsChanged: Boolean) {
        if (!activity.isReaderActive) return
        style?.applyReaderSettings()
        cachedPaginationSnapshot = null
        if (state.book == null || state.chapters.isEmpty()) return
        val chapterIndex = ui.clamp(state.currentChapterIndex, 0, state.chapters.size - 1)
        if (paginationInsetsChanged && initialReflowDeferred && !initialVisiblePageBound) {
            cancelInitialProgressivePagination(chapterIndex, "insets_changed")
            clearPartialPagination()
            val target = views.pageCurrent ?: views.pageBodyCurrent
            target.post {
                if (!activity.isReaderActive) return@post
                capturePaginationSnapshot()
                startInitialProgressivePaginationAfterSnapshot(0, true)
            }
            return
        }
        if (paginationInsetsChanged) {
            scheduleReflowAfterLayout(chapterIndex, currentCharOffset())
        } else if (!suppressReflow) {
            paging?.invalidatePreparedPagingSnapshots()
        }
    }

    fun readableError(error: Throwable?): String {
        if (error?.message.isNullOrBlank()) {
            return "未知错误"
        }
        return error.message!!
    }

    private fun ensurePaginationCacheMatchesLayout() {
        val currentSignature = captureCurrentLayoutSignature()
        if (currentSignature == null) return
        if (activateLayoutSignature(currentSignature)) {
            clearPartialPagination()
        }
    }

    private fun captureCurrentLayoutSignature(): ReaderLayoutSignature? {
        if (!isPaginationLayoutReady()) return null
        val availableWidth = getReaderPageTextWidth()
        val availableHeight = getRegularReaderPageHeight()
        if (availableWidth <= 0 || availableHeight <= 0) return null
        return ReaderLayoutSignature(
            availableWidth,
            availableHeight,
            runtime.settingsStore.isChapterTitleVisible,
            runtime.settingsStore.chapterTitleAlignment,
            resolveChapterTitleTextSizePx(),
            views.pageBodyCurrent.textSize,
            runtime.settingsStore.readerFontWeight,
            runtime.settingsStore.readerFontFamily,
            views.pageBodyCurrent.lineSpacingExtra,
            views.pageBodyCurrent.letterSpacing,
            runtime.settingsStore.firstLineIndentDp,
            runtime.settingsStore.paragraphSpacingDp,
            runtime.settingsStore.leftPaddingDp,
            runtime.settingsStore.rightPaddingDp,
            runtime.settingsStore.topPaddingDp,
            runtime.settingsStore.bottomPaddingDp,
            state.readerContentInsetTop,
            state.readerContentInsetBottom,
            isDoublePageActive(),
        )
    }

    private fun computeParagraphIndentPx(): Int {
        val indentChars = Math.max(runtime.settingsStore.firstLineIndentDp, 0)
        if (indentChars <= 0) return 0
        var emWidth = views.pageBodyCurrent.paint.measureText("\u3000")
        if (emWidth <= 0f) {
            emWidth = views.pageBodyCurrent.textSize
        }
        return Math.round(emWidth * indentChars)
    }

    private fun computeParagraphSpacingPx(): Int = ui.dp(runtime.settingsStore.paragraphSpacingDp)

    private fun hasDisplayableChapterTitle(chapter: ChapterRecord?): Boolean =
        chapter != null && !chapter.title?.trim().isNullOrEmpty()

    private fun hasVisibleParagraphText(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (!Character.isWhitespace(text[i])) {
                return true
            }
        }
        return false
    }

    private fun isNextLineVisible(text: String?, start: Int): Boolean {
        val length = text?.length ?: 0
        if (start >= length) return false
        var end = start
        while (end < length && text!![end] != '\n') {
            end++
        }
        return hasVisibleParagraphText(text!!, start, end)
    }

    private fun resolveInitialAnchorOffset(defaultOffset: Int): Int {
        if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
            return Math.max(state.restoredProgressOffset, 0)
        }
        if (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0) {
            return Math.max(state.requestedChapterOffset, 0)
        }
        return Math.max(defaultOffset, 0)
    }

    private fun resolveInitialChapterIndex(book: BookRecord?, chapters: List<ChapterRecord>?): Int {
        if (book == null || chapters.isNullOrEmpty()) return 0
        var targetChapterIndex = ui.clamp(
            chapterIndexFromOrder(chapters, book.progressIndex),
            0,
            chapters.size - 1,
        )
        if (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0) {
            targetChapterIndex = ui.clamp(
                chapterIndexFromOrder(chapters, state.requestedChapterOrderIndex),
                0,
                chapters.size - 1,
            )
        }
        if (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) {
            targetChapterIndex = ui.clamp(state.restoredChapterIndex, 0, chapters.size - 1)
        }
        return targetChapterIndex
    }

    private fun chapterIndexFromOrder(chapters: List<ChapterRecord>, orderIndex: Int): Int {
        for (i in chapters.indices) {
            val chapter = chapters[i]
            if (chapter.orderIndex == orderIndex) {
                return i
            }
        }
        return orderIndex
    }

    private fun hasInitialPositionRequest(): Boolean =
        (state.restoredChapterIndex >= 0 && state.restoredProgressOffset >= 0) ||
            (state.requestedChapterOrderIndex >= 0 && state.requestedChapterOffset >= 0)

    private fun resetInitialPositionRequest() {
        state.restoredChapterIndex = -1
        state.restoredPageIndex = -1
        state.restoredProgressOffset = -1
        state.requestedChapterOrderIndex = -1
        state.requestedChapterOffset = -1
    }

    private fun startInitialProgressivePaginationAfterSnapshot(attempt: Int, styleApplied: Boolean) {
        if (!activity.isReaderActive) return
        if (!initialReflowDeferred || state.book == null || state.chapters.isEmpty()) return
        val chapterIndex = ui.clamp(state.currentChapterIndex, 0, state.chapters.size - 1)
        val snapshot = cachedPaginationSnapshot
        if (snapshot == null && !styleApplied && style != null) {
            style?.applyReaderSettings()
            val target = views.pageCurrent ?: views.pageBodyCurrent
            target.post { startInitialProgressivePaginationAfterSnapshot(attempt, true) }
            return
        }
        if (snapshot == null) {
            if (attempt >= MAX_LAYOUT_WAIT_PASSES + 2) {
                Log.d(TAG, "[时序] 提前渐进分页等待动画快照超限 - chapter=$chapterIndex")
                return
            }
            if (!isPaginationLayoutReady()) {
                requestPageLayerLayout()
            }
            val target = views.pageCurrent ?: views.pageBodyCurrent
            target.postDelayed(
                { startInitialProgressivePaginationAfterSnapshot(attempt + 1, true) },
                16L,
            )
            return
        }
        if (snapshot.pageWidth <= 0 || snapshot.regularPageHeight <= 0) {
            Log.w(
                TAG,
                "[时序] 动画快照尺寸无效，等待 deferred reflow 兜底 - chapter=$chapterIndex w=${snapshot.pageWidth} h=${snapshot.regularPageHeight}",
            )
            return
        }
        if (hasAnyCachedPagesForChapter(chapterIndex)) {
            Log.d(TAG, "[时序] 已有分页缓存，跳过动画期渐进分页 - chapter=$chapterIndex")
            return
        }
        if (snapshot.layoutSignature != null) {
            activateLayoutSignature(snapshot.layoutSignature)
        } else {
            ensurePaginationCacheMatchesLayout()
        }
        if (PAGE_CACHE.contains(chapterIndex)) return
        val partial = activePartialPagination
        if ((partial != null && partial.chapterIndex == chapterIndex) ||
            waitingForProgressiveChapterIndex == chapterIndex ||
            initialVisiblePageBound
        ) {
            return
        }
        val anchorOffset = Math.max(state.sessionStartOffset, 0)
        pendingReflowChapterIndex = chapterIndex
        pendingReflowAnchorOffset = anchorOffset
        initialReflowPending = true
        Log.d(TAG, "[时序] 动画期间提前启动初始渐进分页 - chapter=$chapterIndex source=cached_snapshot")
        startInitialProgressivePagination(
            chapterIndex,
            anchorOffset,
            hasInitialPositionRequest(),
            snapshot,
            "cached_snapshot",
        )
    }

    private fun startInitialProgressivePagination(
        chapterIndex: Int,
        anchorOffset: Int,
        shouldResetInitialPosition: Boolean,
    ) {
        val snapshot = captureLivePaginationSnapshot()
        if (snapshot == null) {
            fallbackInitialFullPagination(chapterIndex, 0, "invalid_live_snapshot", null)
            return
        }
        startInitialProgressivePagination(
            chapterIndex,
            anchorOffset,
            shouldResetInitialPosition,
            snapshot,
            "live_snapshot",
        )
    }

    private fun startInitialProgressivePagination(
        chapterIndex: Int,
        anchorOffset: Int,
        shouldResetInitialPosition: Boolean,
        snapshot: PaginationSnapshot?,
        source: String,
    ) {
        if (snapshot == null || snapshot.pageWidth <= 0 || snapshot.regularPageHeight <= 0) {
            fallbackInitialFullPagination(chapterIndex, 0, "invalid_snapshot:$source", null)
            return
        }
        val sig = snapshot.layoutSignature
        if (sig != null) {
            activateLayoutSignature(sig)
        }
        val paginationGeneration = ++progressivePaginationGeneration
        waitingForProgressiveChapterIndex = chapterIndex
        runningProgressiveSignature = sig
        runningProgressiveSource = source
        val startTime = System.currentTimeMillis()
        val extraPagesAfterTarget = Math.max(1, pagesPerScreen())
        Log.d(
            TAG,
            "[时序] 初始渐进分页开始 - chapter=$chapterIndex offset=$anchorOffset extra=$extraPagesAfterTarget source=$source w=${snapshot.pageWidth} h=${snapshot.regularPageHeight}",
        )
        runtime.safeExecutePagination(Runnable {
            if (!activity.isReaderActive) return@Runnable
            try {
                val workerStartTime = System.currentTimeMillis()
                if (isProgressivePaginationCancelled(chapterIndex, paginationGeneration)) return@Runnable
                Log.d(
                    TAG,
                    "[时序] 初始渐进分页worker开始 - chapter=$chapterIndex gen=$paginationGeneration queueWait=${workerStartTime - startTime}ms source=$source",
                )
                val display = buildDisplayChapterTextForBackground(chapterIndex, snapshot)
                val paint = TextPaint(snapshot.basePaint)
                val result = ReaderPaginator.paginateUntilOffset(
                    display.text,
                    paint,
                    snapshot.pageWidth,
                    snapshot.regularPageHeight,
                    snapshot.regularPageHeight,
                    snapshot.lineSpacingExtra,
                    display.bodyStartIndex,
                    anchorOffset,
                    extraPagesAfterTarget,
                ) { isProgressivePaginationCancelled(chapterIndex, paginationGeneration) }
                val pages = sanitizePageSlices(result.pages)
                val targetPageIndex = if (pages.isEmpty()) {
                    0
                } else {
                    ui.clamp(result.targetPageIndex, 0, pages.size - 1)
                }
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(
                    TAG,
                    "[时序] 初始渐进分页worker完成 - chapter=$chapterIndex gen=$paginationGeneration pages=${pages.size} complete=${result.complete} worker耗时=${System.currentTimeMillis() - workerStartTime}ms total=${elapsed}ms",
                )
                activity.runOnReaderUiThread {
                    handleInitialProgressivePaginationResult(
                        chapterIndex,
                        anchorOffset,
                        paginationGeneration,
                        shouldResetInitialPosition,
                        snapshot.layoutSignature,
                        pages,
                        targetPageIndex,
                        result.complete,
                        elapsed,
                    )
                }
            } catch (cancelled: CancellationException) {
                Log.d(TAG, "[时序] 初始渐进分页worker取消 - chapter=$chapterIndex gen=$paginationGeneration source=$source")
            } catch (error: Exception) {
                activity.runOnReaderUiThread {
                    fallbackInitialFullPagination(
                        chapterIndex,
                        paginationGeneration,
                        "progressive_exception:$source",
                        error,
                    )
                }
            }
        }, "initial progressive pagination")
        runtime.mainHandler.postDelayed({
            if (!activity.isReaderActive) return@postDelayed
            if (paginationGeneration != progressivePaginationGeneration ||
                waitingForProgressiveChapterIndex != chapterIndex
            ) {
                return@postDelayed
            }
            Log.d(
                TAG,
                "[时序] 初始渐进分页仍在后台运行 - chapter=$chapterIndex gen=$paginationGeneration wait=${PROGRESSIVE_WAIT_LOG_MS}ms source=$source",
            )
        }, PROGRESSIVE_WAIT_LOG_MS)
        runtime.mainHandler.postDelayed(
            {
                if (!activity.isReaderActive) return@postDelayed
                fallbackInitialFullPagination(
                    chapterIndex,
                    paginationGeneration,
                    "progressive_timeout_hard:$source",
                    null,
                )
            },
            PROGRESSIVE_HARD_FALLBACK_MS,
        )
    }

    private fun fallbackInitialFullPagination(
        chapterIndex: Int,
        paginationGeneration: Int,
        reason: String,
        error: Throwable?,
    ) {
        if (paginationGeneration > 0 &&
            (paginationGeneration != progressivePaginationGeneration ||
                waitingForProgressiveChapterIndex != chapterIndex)
        ) {
            return
        }
        waitingForProgressiveChapterIndex = -1
        clearRunningProgressiveState()
        forceInitialFullPagination = true
        val message = "[时序] 初始渐进分页转完整分页兜底 - chapter=$chapterIndex gen=$paginationGeneration reason=$reason"
        if (error == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, error)
        }
        performScheduledReflow()
    }

    private fun handleInitialProgressivePaginationResult(
        chapterIndex: Int,
        anchorOffset: Int,
        paginationGeneration: Int,
        shouldResetInitialPosition: Boolean,
        capturedSignature: ReaderLayoutSignature?,
        pages: List<PageSlice>?,
        targetPageIndex: Int,
        complete: Boolean,
        elapsedMs: Long,
    ) {
        if (paginationGeneration != progressivePaginationGeneration ||
            waitingForProgressiveChapterIndex != chapterIndex
        ) {
            Log.d(TAG, "[时序] 初始渐进分页结果丢弃 - gen/等待状态已变化 chapter=$chapterIndex")
            return
        }
        val currentSignature = captureCurrentLayoutSignature()
        if (capturedSignature != null && currentSignature != null &&
            !capturedSignature.isPaginationCompatibleWith(currentSignature)
        ) {
            fallbackInitialFullPagination(chapterIndex, paginationGeneration, "layout_signature_changed", null)
            return
        }
        if (pages.isNullOrEmpty()) {
            fallbackInitialFullPagination(chapterIndex, paginationGeneration, "empty_progressive_pages", null)
            return
        }
        waitingForProgressiveChapterIndex = -1
        clearRunningProgressiveState()
        val activeSignature = currentSignature ?: capturedSignature
        activateLayoutSignature(activeSignature)
        Log.d(
            TAG,
            "[时序] 初始渐进分页目标页完成 - chapter=$chapterIndex page=$targetPageIndex knownPages=${pages.size} complete=$complete 耗时=${elapsedMs}ms",
        )
        if (complete) {
            putPagesForActiveLayout(chapterIndex, pages)
            clearPartialPaginationForChapter(chapterIndex)
            requireNavigation().openChapter(chapterIndex, anchorOffset, false, 0)
            finishInitialReflowAfterVisiblePage(chapterIndex, shouldResetInitialPosition, true)
            return
        }
        activePartialPagination = PartialPagination(chapterIndex, pages, targetPageIndex, activeSignature)
        requireNavigation().openChapterWithPartialPages(chapterIndex, targetPageIndex, pages, false)
        finishInitialReflowAfterVisiblePage(chapterIndex, shouldResetInitialPosition, false)
        startBackgroundPagination(chapterIndex)
    }

    private fun finishInitialReflowAfterVisiblePage(
        chapterIndex: Int,
        shouldResetInitialPosition: Boolean,
        prewarmAdjacent: Boolean,
    ) {
        if (shouldResetInitialPosition) {
            resetInitialPositionRequest()
        }
        Log.d(TAG, "[时序] 初始排版完成 - 触发前景淡入")
        initialReflowPending = false
        forceInitialFullPagination = false
        initialVisiblePageBound = true
        if (prewarmAdjacent) {
            prewarmAdjacentChapters(chapterIndex)
        }
        val callback = onInitialReflowComplete
        if (callback != null) {
            onInitialReflowComplete = null
            callback.run()
        }
        lastAppliedDoublePageActive = isDoublePageActive()
    }

    private fun performScheduledReflow() {
        if (!activity.isReaderActive) return
        if (state.book == null || state.chapters.isEmpty()) return
        val generation = reflowGeneration
        val chapterIndex = if (pendingReflowChapterIndex >= 0) {
            ui.clamp(pendingReflowChapterIndex, 0, state.chapters.size - 1)
        } else {
            ui.clamp(state.currentChapterIndex, 0, state.chapters.size - 1)
        }
        val anchorOffset = if (pendingReflowChapterIndex >= 0) {
            Math.max(pendingReflowAnchorOffset, 0)
        } else {
            state.sessionStartOffset
        }
        val shouldResetInitialPosition = hasInitialPositionRequest()
        val completingInitialReflow = initialReflowPending
        val previousDoublePageActive = lastAppliedDoublePageActive
        Log.d(TAG, "[时序] performScheduledReflow 开始 - chapter=$chapterIndex gen=$generation initialReflow=$completingInitialReflow")
        if (!initialReflowDeferred) {
            style?.applyReaderSettings()
        }
        val nextDoublePageActive = isDoublePageActive()
        val doublePageStateChanged = previousDoublePageActive != null &&
            previousDoublePageActive != nextDoublePageActive
        runAfterNextPageLayout(generation) {
            if (generation != reflowGeneration) {
                Log.d(TAG, "[时序] performScheduledReflow 回调被丢弃 - gen不匹配")
                return@runAfterNextPageLayout
            }
            Log.d(TAG, "[时序] performScheduledReflow layout就绪 - 检查缓存")
            if (doublePageStateChanged) {
                paging?.invalidatePreparedPagingSnapshots()
            }
            ensurePaginationCacheMatchesLayout()
            val cacheHit = PAGE_CACHE.contains(chapterIndex)
            Log.d(TAG, "[时序] 分页缓存检查 - chapter=$chapterIndex hit=$cacheHit initialReflow=$completingInitialReflow")
            if (cacheHit) {
                cancelInitialProgressivePagination(chapterIndex, "cache_hit")
            }
            if (completingInitialReflow && !cacheHit && !forceInitialFullPagination) {
                val partial = activePartialPagination
                if (partial != null && partial.chapterIndex == chapterIndex) {
                    Log.d(TAG, "[时序] 初始排版已有 partial 可绑定，跳过完整分页 - chapter=$chapterIndex")
                    lastAppliedDoublePageActive = isDoublePageActive()
                    return@runAfterNextPageLayout
                }
                if (waitingForProgressiveChapterIndex == chapterIndex) {
                    val currentSignature = captureCurrentLayoutSignature()
                    if (runningProgressiveSignature != null && currentSignature != null &&
                        !runningProgressiveSignature!!.isPaginationCompatibleWith(currentSignature)
                    ) {
                        cancelInitialProgressivePagination(chapterIndex, "layout_signature_changed_before_result")
                    } else {
                        Log.d(TAG, "[时序] 初始排版等待后台渐进分页结果 - chapter=$chapterIndex")
                        lastAppliedDoublePageActive = isDoublePageActive()
                        return@runAfterNextPageLayout
                    }
                }
                var snapshot = cachedPaginationSnapshot
                var snapshotSource = "cached_snapshot_reflow"
                val currentSignature = captureCurrentLayoutSignature()
                if (snapshot == null ||
                    (snapshot.layoutSignature != null && currentSignature != null &&
                        !snapshot.layoutSignature.isPaginationCompatibleWith(currentSignature))
                ) {
                    snapshot = captureLivePaginationSnapshot()
                    snapshotSource = "live_reflow"
                }
                if (snapshot == null || snapshot.pageWidth <= 0 || snapshot.regularPageHeight <= 0) {
                    Log.w(
                        TAG,
                        "[时序] 初始排版缓存未命中且快照无效，走完整分页兜底 - chapter=$chapterIndex reason=invalid_reflow_snapshot",
                    )
                    forceInitialFullPagination = true
                } else {
                    Log.d(TAG, "[时序] 初始排版缓存未命中，启动渐进分页 - chapter=$chapterIndex source=$snapshotSource")
                    startInitialProgressivePagination(
                        chapterIndex,
                        anchorOffset,
                        shouldResetInitialPosition,
                        snapshot,
                        snapshotSource,
                    )
                    lastAppliedDoublePageActive = isDoublePageActive()
                    return@runAfterNextPageLayout
                }
            }
            if (forceInitialFullPagination && !cacheHit) {
                Log.d(TAG, "[时序] 初始排版走完整分页兜底 - chapter=$chapterIndex")
            }
            requireNavigation().openChapter(chapterIndex, anchorOffset, false, 0)
            if (completingInitialReflow) {
                finishInitialReflowAfterVisiblePage(chapterIndex, shouldResetInitialPosition, true)
            } else if (shouldResetInitialPosition) {
                resetInitialPositionRequest()
            }
            lastAppliedDoublePageActive = isDoublePageActive()
        }
    }

    private fun runAfterNextPageLayout(generation: Int, action: Runnable) {
        if (!activity.isReaderActive) return
        val target = views.pageCurrent ?: views.pageBodyCurrent
        waitForNextPagePreDraw(generation, target, 0, action)
    }

    private fun waitForNextPagePreDraw(generation: Int, target: View, pass: Int, action: Runnable) {
        if (generation != reflowGeneration) return
        if (pass > 0 && isPaginationLayoutReady()) {
            action.run()
            return
        }
        val completed = booleanArrayOf(false)
        val listenerRef = arrayOfNulls<ViewTreeObserver.OnPreDrawListener>(1)
        val complete = Runnable {
            if (!activity.isReaderActive) return@Runnable
            if (completed[0]) return@Runnable
            completed[0] = true
            removePreDrawListener(target, listenerRef[0])
            if (generation != reflowGeneration) return@Runnable
            if (isPaginationLayoutReady() || pass >= MAX_LAYOUT_WAIT_PASSES) {
                action.run()
                return@Runnable
            }
            requestPageLayerLayout()
            waitForNextPagePreDraw(generation, target, pass + 1, action)
        }
        listenerRef[0] = ViewTreeObserver.OnPreDrawListener {
            target.post(complete)
            true
        }
        target.viewTreeObserver.addOnPreDrawListener(listenerRef[0])
        requestPageLayerLayout()
        target.postDelayed(complete, LAYOUT_WAIT_FALLBACK_MS)
    }

    private fun requestPageLayerLayout() {
        requestLayout(views.pageCurrent)
        requestLayout(views.pageIncoming)
        requestLayout(views.pageCurrentLeftPane)
        requestLayout(views.pageCurrentRightPane)
        requestLayout(views.pageCurrentGutter)
        requestLayout(views.pageIncomingLeftPane)
        requestLayout(views.pageIncomingRightPane)
        requestLayout(views.pageIncomingGutter)
        requestLayout(views.pageBodyCurrent)
        requestLayout(views.pageBodyCurrentRight)
        requestLayout(views.pageBodyIncoming)
        requestLayout(views.pageBodyIncomingRight)
    }

    private fun requestLayout(view: View?) {
        view?.requestLayout()
    }

    private fun removePreDrawListener(target: View?, listener: ViewTreeObserver.OnPreDrawListener?) {
        if (target == null || listener == null) return
        val observer = target.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(listener)
        }
    }

    private fun isPaginationLayoutReady(): Boolean {
        if (views.pageCurrent.isLayoutRequested || views.pageBodyCurrent.isLayoutRequested) return false
        val pageWidth = views.pageCurrent.width
        val bodyWidth = views.pageBodyCurrent.width
        val bodyHeight = views.pageBodyCurrent.height
        if (pageWidth <= 0 || bodyWidth <= 0 || bodyHeight <= 0) return false
        val availableHeight = getRegularReaderPageHeight()
        if (availableHeight <= 0) return false
        val contentWidth = Math.max(
            0,
            pageWidth - views.pageCurrent.paddingLeft - views.pageCurrent.paddingRight,
        )
        if (contentWidth <= 0) return false
        val tolerance = ui.dp(4)
        if (Math.abs(bodyHeight - availableHeight) > tolerance) return false
        val doublePageActive = isDoublePageActive()
        val expectedRightVisibility = if (doublePageActive) View.VISIBLE else View.GONE
        val expectedGutterVisibility = if (doublePageActive && shouldShowDoublePageGutter()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (!hasVisibility(views.pageCurrentRightPane, expectedRightVisibility) ||
            !hasVisibility(views.pageCurrentGutter, expectedGutterVisibility)
        ) {
            return false
        }
        if (!doublePageActive) {
            return bodyWidth >= contentWidth - tolerance
        }
        if (views.pageBodyCurrentRight.visibility == View.VISIBLE &&
            Math.abs(views.pageBodyCurrentRight.height - availableHeight) > tolerance
        ) {
            return false
        }
        val expectedPaneWidth = Math.max(0, (contentWidth - doublePageGutterWidth()) / 2)
        return Math.abs(bodyWidth - expectedPaneWidth) <= tolerance
    }

    fun shouldShowDoublePageGutter(): Boolean = true

    private fun doublePageGutterWidth(): Int {
        if (!shouldShowDoublePageGutter()) return 0
        return Math.max(views.pageCurrentGutter.width, ui.dp(22))
    }

    private fun hasVisibility(view: View?, visibility: Int): Boolean =
        view == null || view.visibility == visibility

    private fun sanitizePageSlices(pages: List<PageSlice>?): List<PageSlice> {
        if (pages.isNullOrEmpty()) return ArrayList()
        val sanitized = ArrayList<PageSlice>(pages.size)
        for (slice in pages) {
            sanitized.add(
                PageSlice(
                    slice.start,
                    slice.end,
                    slice.bodyStartInSlice,
                    slice.bodyEndInSlice,
                    slice.text,
                ),
            )
        }
        return sanitized
    }

    private fun resolveChapterTitleTypeface(): Typeface? {
        if (views.pageTitleCurrent.typeface != null) {
            return views.pageTitleCurrent.typeface
        }
        return views.pageBodyCurrent.typeface
    }

    private fun resolveChapterTitleTextSizePx(): Float {
        if (views.pageTitleCurrent.textSize > 0f) {
            return views.pageTitleCurrent.textSize
        }
        return views.pageBodyCurrent.textSize
    }

    private fun captureLivePaginationSnapshot(): PaginationSnapshot? {
        if (!isPaginationLayoutReady()) return null
        val pageWidth = getReaderPageTextWidth()
        val regularPageHeight = getRegularReaderPageHeight()
        if (pageWidth <= 0 || regularPageHeight <= 0) return null
        return PaginationSnapshot(
            pageWidth,
            regularPageHeight,
            views.pageBodyCurrent.lineSpacingExtra,
            views.pageBodyCurrent.paint,
            captureCurrentLayoutSignature(),
            resolveChapterTitleTypeface(),
            resolveChapterTitleTextSizePx(),
            getChapterTitleBodyMarginPx(),
            computeParagraphIndentPx(),
        )
    }

    fun capturePaginationSnapshot() {
        val snapshot = captureLivePaginationSnapshot()
        if (snapshot == null) {
            Log.d(TAG, "[时序] 捕获初始分页快照失败 - layout尚未就绪")
            return
        }
        cachedPaginationSnapshot = snapshot
        if (snapshot.layoutSignature != null) {
            activateLayoutSignature(snapshot.layoutSignature)
        }
        Log.d(
            TAG,
            "[时序] 捕获初始分页快照 - w=${snapshot.pageWidth} h=${snapshot.regularPageHeight} hasSignature=${snapshot.layoutSignature != null}",
        )
    }

    private fun buildDisplayChapterTextForBackground(
        chapterIndex: Int,
        snapshot: PaginationSnapshot,
    ): DisplayChapterText {
        val body = buildDisplayBodyTextForBackground(chapterIndex, snapshot.indentPx)
        val chapter = state.chapters[chapterIndex]
        if (!runtime.settingsStore.isChapterTitleVisible || !hasDisplayableChapterTitle(chapter)) {
            return DisplayChapterText(body, 0)
        }

        val builder = SpannableStringBuilder()
        val title = chapter.title!!.trim()
        val titleStart = builder.length
        builder.append(title)
        val titleEnd = builder.length
        builder.append('\n')
        val titleParagraphEnd = builder.length
        builder.setSpan(
            ReaderTitleSpan(snapshot.titleTypeface, snapshot.titleTextSizePx),
            titleStart,
            titleEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (runtime.settingsStore.chapterTitleAlignment == "center") {
            builder.setSpan(
                AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                titleStart,
                titleParagraphEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        val spacerStart = builder.length
        builder.append(' ')
        val spacerEnd = builder.length
        builder.append('\n')
        builder.setSpan(
            ForegroundColorSpan(Color.TRANSPARENT),
            spacerStart,
            spacerEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            FixedLineHeightSpan(snapshot.titleBodyMarginPx),
            spacerStart,
            spacerEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )

        val bodyStartIndex = builder.length
        builder.append(body)
        return DisplayChapterText(builder, bodyStartIndex)
    }

    private fun buildDisplayBodyTextForBackground(chapterIndex: Int, indentPx: Int): CharSequence {
        val processed = getProcessedChapterText(chapterIndex)
        val paragraphSpacingPx = computeParagraphSpacingPx()
        if (processed.isEmpty()) return processed

        val spannable = SpannableString(processed)
        var start = 0
        val length = processed.length
        while (start < length) {
            var end = start
            while (end < length && processed[end] != '\n') {
                end++
            }
            val paragraphLimit = if (end < length) end + 1 else end
            if (hasVisibleParagraphText(processed, start, end)) {
                if (indentPx > 0) {
                    spannable.setSpan(
                        LeadingMarginSpan.Standard(indentPx, 0),
                        start,
                        paragraphLimit,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (end < length && isNextLineVisible(processed, paragraphLimit) && paragraphSpacingPx > 0) {
                    spannable.setSpan(
                        ReaderParagraphBottomSpacingSpan(paragraphSpacingPx),
                        end,
                        paragraphLimit,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            } else if (end < length && paragraphSpacingPx > 0) {
                spannable.setSpan(
                    FixedLineHeightSpan(paragraphSpacingPx),
                    start,
                    paragraphLimit,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            start = paragraphLimit
        }
        return spannable
    }

    private fun requireNavigation(): ReaderNavigationController = requireNotNull(navigation)

    private class RemoteProgressComparison(
        val lastReadAt: Long,
        val progressIndex: Int,
        val progressOffset: Int,
    )

    private class DeferredProgressUpload(
        val book: BookRecord,
        val chapter: ChapterRecord,
        val offset: Int,
    )

    private class RemoteProgressSuggestion(
        val chapterIndex: Int,
        val chapterOrderIndex: Int,
        val chapterOffset: Int,
        val chapterTime: Long,
        val chapterTitle: String,
        val excerpt: String,
    )

    private class PaginationSnapshot(
        val pageWidth: Int,
        val regularPageHeight: Int,
        val lineSpacingExtra: Float,
        basePaint: TextPaint,
        val layoutSignature: ReaderLayoutSignature?,
        val titleTypeface: Typeface?,
        val titleTextSizePx: Float,
        val titleBodyMarginPx: Int,
        val indentPx: Int,
    ) {
        val basePaint: TextPaint = TextPaint(basePaint)
    }

    private class DisplayChapterText(text: CharSequence?, bodyStartIndex: Int) {
        val text: CharSequence = text ?: ""
        val bodyStartIndex: Int = Math.max(0, Math.min(bodyStartIndex, this.text.length))
    }

    private class PartialPagination(
        val chapterIndex: Int,
        pages: List<PageSlice>?,
        targetPageIndex: Int,
        val layoutSignature: ReaderLayoutSignature?,
    ) {
        val pages: List<PageSlice> = pages ?: ArrayList()
        val targetPageIndex: Int = Math.max(targetPageIndex, 0)
    }

    private class FixedLineHeightSpan(heightPx: Int) : LineHeightSpan {
        private val heightPx = Math.max(heightPx, 0)

        override fun chooseHeight(
            text: CharSequence?,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fontMetricsInt: Paint.FontMetricsInt?,
        ) {
            if (fontMetricsInt == null) return
            fontMetricsInt.ascent = -heightPx
            fontMetricsInt.top = fontMetricsInt.ascent
            fontMetricsInt.descent = 0
            fontMetricsInt.bottom = 0
        }
    }

    companion object {
        private const val TAG = "PacilReadReader"
        private const val REFLOW_DEBOUNCE_MS = 32L
        private const val LAYOUT_WAIT_FALLBACK_MS = 180L
        private const val MAX_LAYOUT_WAIT_PASSES = 2
        private const val PROGRESSIVE_WAIT_LOG_MS = 800L
        private const val PROGRESSIVE_HARD_FALLBACK_MS = 5000L
        private const val MAX_LAYOUT_PAGE_CACHE_SIGNATURES = 4
        private const val SIMILAR_PROGRESS_MAX_OFFSET_DELTA = 800
        private const val EMPTY_CHAPTER_TEXT_PLACEHOLDER = "章节正文为空或外置正文文件缺失。"
        private val VOLUME_CHAPTER_TITLE_PATTERN: Pattern = Pattern.compile(
            "^\\s*第\\s*[0-9０-９一二三四五六七八九十百千万零〇两]+\\s*卷(?:\\s*|[：:、.．·\\-].*)$",
        )

        private var lastCachedBookId = -1L
        private var cachedBook: BookRecord? = null
        private val cachedChapters: MutableList<ChapterRecord> = ArrayList()
        private val cachedRules: MutableList<ReplacementRuleRecord> = ArrayList()
        private val PAGE_CACHE = ReaderPageCache(MAX_LAYOUT_PAGE_CACHE_SIGNATURES)
        private val PROGRESS_UPLOAD_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
