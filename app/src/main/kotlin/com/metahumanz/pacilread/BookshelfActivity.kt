package com.metahumanz.pacilread

import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.metahumanz.pacilread.export.BookExportNaming
import com.metahumanz.pacilread.importer.BookDuplicateDetector
import com.metahumanz.pacilread.importer.BookImportService
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.reader.search.BookSearchIndex
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.storage.SnapshotManager
import com.metahumanz.pacilread.sync.WebDavClient
import com.metahumanz.pacilread.sync.WebDavProgressSyncCoordinator
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.theme.ThemedActivity
import com.metahumanz.pacilread.ui.LaunchSourceTransition
import com.metahumanz.pacilread.util.CoverImageStore
import com.metahumanz.pacilread.util.FileAssetHelper
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class BookshelfActivity : ThemedActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val progressPrefetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val allBooks: MutableList<BookRecord> = ArrayList()
    private val selectedBookIds: MutableSet<Long> = HashSet()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var databaseHelper: JsonDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var progressSyncCoordinator: WebDavProgressSyncCoordinator
    private lateinit var importService: BookImportService
    private lateinit var listAdapter: BookListAdapter
    private lateinit var gridAdapter: BookGridAdapter
    private var homeNavigationController: HomeNavigationController? = null
    private var homeStatsPanelController: HomeStatsPanelController? = null
    private var homeBookmarksPanelController: HomeBookmarksPanelController? = null
    private var homeSettingsController: SettingsScreenController? = null
    private lateinit var listFooterView: View

    private lateinit var emptyLayout: LinearLayout
    private lateinit var loadingLayout: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var gridBooks: GridView
    private lateinit var listBooks: ListView
    private lateinit var sectionTitle: TextView
    private lateinit var loadingText: TextView
    private lateinit var statsText: TextView
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var headerActionButton: Button
    private lateinit var headerManageButton: Button
    private lateinit var buttonModeCard: Button
    private lateinit var buttonModeList: Button
    private lateinit var emptyActionButton: Button
    private var containerSearch: View? = null
    private var iconSearch: View? = null
    private lateinit var bookshelfFiltersLayout: View
    private lateinit var bookshelfBatchActionsLayout: View
    private lateinit var filterTagButton: Button
    private lateinit var filterSeriesButton: Button
    private lateinit var filterStatusButton: Button
    private lateinit var filterClearButton: Button
    private lateinit var batchClassifyButton: Button
    private lateinit var batchExportButton: Button
    private lateinit var batchDeleteButton: Button
    private lateinit var selectionCountText: TextView
    private var pendingCoverBookId = -1L
    private var booksLoaded = false
    private var booksLoading = false
    private var autoOpenConsumed = false
    @Volatile private var bookshelfDestroyed = false
    private var clearProgressPrefetchStatusRunnable: Runnable? = null
    private var progressPrefetchRunning = false
    private var progressPrefetchCurrent = 0
    private var progressPrefetchTotal = 0
    private var progressPrefetchFailed = false
    private var bookActionsPopup: PopupWindow? = null
    private var bookshelfManagementMode = false
    private var selectedTagFilter = ""
    private var selectedSeriesFilter = ""
    private var selectedStatusFilter = ""
    private var pendingExportBooks: MutableList<BookRecord> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookshelf)

        databaseHelper = JsonDatabase.getInstance(this)
        settingsStore = SettingsStore(this)
        progressSyncCoordinator = WebDavProgressSyncCoordinator(
            databaseHelper,
            settingsStore,
            WebDavClient(settingsStore),
        )
        importService = BookImportService(this)

        bindViews()
        setupAdapters()
        setupInteractions()
        setupHomeControllers()
        if (savedInstanceState != null && homeNavigationController != null) {
            homeNavigationController?.restoreHomePage(
                savedInstanceState.getInt(STATE_HOME_PAGE, HomeNavigationController.PAGE_BOOKSHELF),
            )
        }
        val autoOpenBookId = intent.getLongExtra(EXTRA_AUTO_OPEN_BOOK_ID, -1L)
        if (savedInstanceState == null && autoOpenBookId > 0) {
            performAutoOpenFastPath(autoOpenBookId)
        } else {
            showBookshelfLoadingState()
            if (savedInstanceState == null && settingsStore.isAutoOpenLastBook) {
                maybeAutoOpenLastBook()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAddEntryVisibility()
        homeNavigationController?.refreshFromSettings()
        if (!autoOpenConsumed) {
            refreshBooks()
        }
        refreshCurrentHomePage(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        homeNavigationController?.let { outState.putInt(STATE_HOME_PAGE, it.getCurrentPage()) }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        dismissBookActionsPopup()
        homeSettingsController?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        bookshelfDestroyed = true
        clearProgressPrefetchStatusRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            clearProgressPrefetchStatusRunnable = null
        }
        homeSettingsController?.onDestroy()
        super.onDestroy()
        executor.shutdownNow()
        progressPrefetchExecutor.shutdownNow()
    }

    private fun isBookshelfActive(): Boolean = !bookshelfDestroyed && !isFinishing && !isDestroyed

    private fun runOnBookshelfUiThread(action: Runnable?) {
        if (action == null || !isBookshelfActive()) {
            return
        }
        runOnUiThread {
            if (!isBookshelfActive()) {
                return@runOnUiThread
            }
            try {
                action.run()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Bookshelf UI task failed after lifecycle change", error)
            }
        }
    }

    private fun safeExecute(action: Runnable?, label: String?): Boolean = safeExecute(executor, action, label)

    private fun safeExecuteProgressPrefetch(action: Runnable?, label: String?): Boolean =
        safeExecute(progressPrefetchExecutor, action, label)

    private fun safeExecute(targetExecutor: ExecutorService?, action: Runnable?, label: String?): Boolean {
        if (action == null || targetExecutor == null || bookshelfDestroyed || targetExecutor.isShutdown) {
            return false
        }
        return try {
            targetExecutor.execute {
                try {
                    if (!bookshelfDestroyed) {
                        action.run()
                    }
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Bookshelf background task failed: " + safeTaskLabel(label), error)
                }
            }
            true
        } catch (error: RejectedExecutionException) {
            Log.d(TAG, "Bookshelf background task rejected after shutdown: " + safeTaskLabel(label), error)
            false
        }
    }

    private fun safeTaskLabel(label: String?): String = if (label.isNullOrBlank()) "unnamed" else label

    override fun onBackPressed() {
        if (bookshelfManagementMode) {
            setBookshelfManagementMode(false)
            return
        }
        if (homeNavigationController?.onBackPressed() == true) {
            return
        }
        super.onBackPressed()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val navigationController = homeNavigationController
        if (navigationController != null && navigationController.handleTouchEvent(event)) {
            if (navigationController.consumePendingChildTouchCancel()) {
                val cancelEvent = MotionEvent.obtain(event)
                cancelEvent.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancelEvent)
                cancelEvent.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (homeStatsPanelController?.onActivityResult(requestCode, resultCode, data) == true) {
            return
        }
        if (resultCode != RESULT_OK || data == null) {
            return
        }
        if (requestCode == SettingsScreenController.REQUEST_PICK_BOOK) {
            val pickedUri = data.data
            if (homeSettingsController != null && pickedUri != null) {
                homeSettingsController?.onBookPicked(pickedUri)
            }
            return
        }
        if (requestCode == REQUEST_PICK_BOOK) {
            val uris: MutableList<Uri> = ArrayList()
            val clipData = data.clipData
            if (clipData != null) {
                val count = clipData.itemCount
                for (i in 0 until count) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) {
                importBooks(uris)
            }
        } else if (requestCode == REQUEST_EXPORT_BOOKS_DIRECTORY && data.data != null) {
            exportPendingBooks(data.data!!)
        } else if (requestCode == REQUEST_PICK_COVER && pendingCoverBookId > 0) {
            attachCover(pendingCoverBookId, data.data)
        }
    }

    private fun bindViews() {
        sectionTitle = findViewById(R.id.text_section_title)
        emptyLayout = findViewById(R.id.layout_empty)
        loadingLayout = findViewById(R.id.layout_loading)
        searchInput = findViewById(R.id.input_search)
        gridBooks = findViewById(R.id.grid_books)
        listBooks = findViewById(R.id.list_books)
        loadingText = findViewById(R.id.text_loading)
        statsText = findViewById(R.id.text_stats)
        emptyTitle = findViewById(R.id.text_empty_title)
        emptyHint = findViewById(R.id.text_empty_hint)
        headerActionButton = findViewById(R.id.button_header_action)
        headerManageButton = findViewById(R.id.button_header_manage)
        buttonModeCard = findViewById(R.id.button_mode_card)
        buttonModeList = findViewById(R.id.button_mode_list)
        emptyActionButton = findViewById(R.id.button_empty_action)
        containerSearch = findViewById(R.id.container_search)
        iconSearch = findViewById(R.id.icon_search)
        bookshelfFiltersLayout = findViewById(R.id.layout_bookshelf_filters)
        bookshelfBatchActionsLayout = findViewById(R.id.layout_bookshelf_batch_actions)
        filterTagButton = findViewById(R.id.button_filter_tag)
        filterSeriesButton = findViewById(R.id.button_filter_series)
        filterStatusButton = findViewById(R.id.button_filter_status)
        filterClearButton = findViewById(R.id.button_filter_clear)
        batchClassifyButton = findViewById(R.id.button_batch_classify)
        batchExportButton = findViewById(R.id.button_batch_export)
        batchDeleteButton = findViewById(R.id.button_batch_delete)
        selectionCountText = findViewById(R.id.text_bookshelf_selection_count)
    }

    private fun setupAdapters() {
        listFooterView = layoutInflater.inflate(R.layout.item_book_footer, listBooks, false)
        listFooterView.findViewById<View>(R.id.button_footer_add_book).setOnClickListener { openPicker() }
        listBooks.addFooterView(listFooterView, null, true)
        listAdapter = BookListAdapter(this)
        gridAdapter = BookGridAdapter(this)
        listBooks.adapter = listAdapter
        gridBooks.adapter = gridAdapter
        updateAddEntryVisibility()
    }

    private fun setupInteractions() {
        headerActionButton.setOnClickListener { openPicker() }
        headerManageButton.setOnClickListener { setBookshelfManagementMode(!bookshelfManagementMode) }

        buttonModeCard.setOnClickListener { setBookshelfMode(VIEW_MODE_CARD) }
        buttonModeList.setOnClickListener { setBookshelfMode("list") }

        emptyActionButton.setOnClickListener {
            val query = currentQuery()
            if (query.isEmpty() && !hasActiveBookshelfFilters()) {
                openPicker()
            } else {
                searchInput.setText("")
                clearBookshelfFilters()
            }
        }

        gridBooks.setOnItemClickListener { _, view, position, _ ->
            if (gridAdapter.isAddPosition(position)) {
                if (!bookshelfManagementMode) openPicker()
                return@setOnItemClickListener
            }
            val book = gridAdapter.getItem(position)
            if (book != null) {
                if (bookshelfManagementMode) toggleBookSelection(book.id) else openBook(book.id, view)
            }
        }
        gridBooks.setOnItemLongClickListener { _, view, position, _ ->
            if (gridAdapter.isAddPosition(position)) {
                openPicker()
                return@setOnItemLongClickListener true
            }
            val book = gridAdapter.getItem(position)
            if (book != null) {
                if (bookshelfManagementMode) toggleBookSelection(book.id) else showBookActions(book, view)
                return@setOnItemLongClickListener true
            }
            false
        }

        listBooks.setOnItemClickListener { _, view, position, _ ->
            if (position >= listAdapter.count) {
                openPicker()
                return@setOnItemClickListener
            }
            val book = listAdapter.getItem(position)
            if (bookshelfManagementMode) toggleBookSelection(book.id) else openBook(book.id, view)
        }
        listBooks.setOnItemLongClickListener { _, view, position, _ ->
            if (position >= listAdapter.count) {
                return@setOnItemLongClickListener true
            }
            val book = listAdapter.getItem(position)
            if (bookshelfManagementMode) toggleBookSelection(book.id) else showBookActions(book, view)
            true
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        val focusSearch = View.OnClickListener {
            searchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        containerSearch?.setOnClickListener(focusSearch)
        iconSearch?.setOnClickListener(focusSearch)

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            ) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
                searchInput.clearFocus()
                return@setOnEditorActionListener true
            }
            false
        }

        filterTagButton.setOnClickListener { showTagFilterDialog() }
        filterSeriesButton.setOnClickListener { showSeriesFilterDialog() }
        filterStatusButton.setOnClickListener { showStatusFilterDialog() }
        filterClearButton.setOnClickListener { clearBookshelfFilters() }
        batchClassifyButton.setOnClickListener { showBatchClassificationActions() }
        batchExportButton.setOnClickListener { startBatchExport() }
        batchDeleteButton.setOnClickListener { confirmBatchDelete() }
    }

    private fun setupHomeControllers() {
        homeStatsPanelController = HomeStatsPanelController(this, databaseHelper, settingsStore, executor)
        homeBookmarksPanelController = HomeBookmarksPanelController(this, databaseHelper, executor)
        homeSettingsController = SettingsScreenController(this, object : SettingsScreenController.Host {
            override fun openBookPicker(intent: Intent, requestCode: Int) {
                startActivityForResult(intent, requestCode)
            }

            override fun openReader(bookId: Long) {
                refreshBooks()
                val intent = Intent(this@BookshelfActivity, ReaderActivity::class.java)
                intent.putExtra("book_id", bookId)
                startActivity(intent)
            }

            override fun onSettingsSaved() {
                handleEmbeddedSettingsSaved()
            }

            override fun onLibraryDataRestored() {
                refreshBooks()
                refreshCurrentHomePage(false)
            }

            override fun onThemeChanged() {
                recreate()
            }
        })
        homeNavigationController = HomeNavigationController(this, settingsStore, object : HomeNavigationController.Callback {
            override fun isReadingTimeTrackingEnabled(): Boolean = settingsStore.isReadingTimeTrackingEnabled

            override fun onHomePageSelected(page: Int, syncFirst: Boolean) {
                refreshCurrentHomePage(syncFirst)
            }
        })
    }

    private fun handleEmbeddedSettingsSaved() {
        updateAddEntryVisibility()
        homeNavigationController?.refreshFromSettings()
        val navigationController = homeNavigationController
        if (navigationController == null || navigationController.getCurrentPage() != HomeNavigationController.PAGE_SETTINGS) {
            refreshCurrentHomePage(false)
        }
    }

    private fun refreshCurrentHomePage(syncFirst: Boolean) {
        val navigationController = homeNavigationController ?: return
        val currentPage = navigationController.getCurrentPage()
        homeStatsPanelController?.refreshIfVisible(currentPage, syncFirst)
        homeBookmarksPanelController?.refreshIfVisible(currentPage)
        if (currentPage == HomeNavigationController.PAGE_SETTINGS) {
            homeSettingsController?.bindCurrentValues()
            homeSettingsController?.refreshReadingStatsSummary(syncFirst)
        }
    }

    private fun setBookshelfMode(mode: String) {
        settingsStore.bookshelfViewMode = mode
        applyBookshelfMode()
    }

    private fun applyBookshelfMode() {
        val usingCardMode = isCardMode()
        AppUiUtils.styleSelectionButton(this, buttonModeCard, usingCardMode)
        AppUiUtils.styleSelectionButton(this, buttonModeList, !usingCardMode)
        if (!booksLoaded) {
            gridBooks.visibility = View.GONE
            listBooks.visibility = View.GONE
            return
        }
        if (shouldShowBookshelfEmptyState(currentQuery())) {
            return
        }
        gridBooks.visibility = if (usingCardMode) View.VISIBLE else View.GONE
        listBooks.visibility = if (usingCardMode) View.GONE else View.VISIBLE
    }

    private fun refreshBooks() {
        refreshBooks(true)
    }

    private fun refreshBooks(prefetchAfterLoad: Boolean) {
        booksLoading = true
        if (!booksLoaded) {
            showBookshelfLoadingState()
        }
        safeExecute({
            try {
                val books = databaseHelper.getBooks()
                runOnBookshelfUiThread {
                    booksLoading = false
                    booksLoaded = true
                    allBooks.clear()
                    allBooks.addAll(books)
                    applyFilter(currentQuery())
                    if (prefetchAfterLoad) {
                        scheduleBookshelfProgressPrefetch(books)
                    }
                }
            } catch (error: Exception) {
                runOnBookshelfUiThread {
                    booksLoading = false
                    booksLoaded = true
                    applyFilter(currentQuery())
                    showToast("加载书架失败: " + readableError(error))
                }
            }
        }, "refresh books")
    }

    private fun scheduleBookshelfProgressPrefetch(books: List<BookRecord>?) {
        if (books == null || books.isEmpty() || !settingsStore.isWebDavEnabled) {
            return
        }
        val configuredLimit = settingsStore.webDavBookshelfProgressPrefetchLimit
        if (configuredLimit <= 0) {
            return
        }
        val limit = Math.min(configuredLimit, books.size)
        val candidates: MutableList<BookRecord> = ArrayList()
        for (i in 0 until limit) {
            candidates.add(snapshotBookForProgressPrefetch(books[i]))
        }
        safeExecuteProgressPrefetch({
            var changed = false
            var failed = false
            runOnBookshelfUiThread { startBookshelfProgressPrefetch(candidates.size) }
            for (i in candidates.indices) {
                if (Thread.currentThread().isInterrupted || bookshelfDestroyed) {
                    return@safeExecuteProgressPrefetch
                }
                val current = i + 1
                val book = candidates[i]
                runOnBookshelfUiThread { updateBookshelfProgressPrefetchCurrent(current) }
                try {
                    val result = progressSyncCoordinator.syncBookProgressIfNeeded(book)
                    changed = changed || result.remoteApplied
                    if (result.remoteApplied) {
                        val latestBook = databaseHelper.getBook(book.id)
                        if (latestBook != null) {
                            runOnBookshelfUiThread { applyRemoteProgressToBookItem(latestBook) }
                        }
                    }
                } catch (error: Exception) {
                    failed = true
                    Log.d(TAG, "WebDAV progress prefetch skipped for book " + book.id, error)
                }
            }
            if (!Thread.currentThread().isInterrupted) {
                val refreshCards = changed
                val showFailureHint = failed
                runOnBookshelfUiThread {
                    finishBookshelfProgressPrefetch(showFailureHint)
                    if (refreshCards) {
                        refreshBooks(false)
                    }
                }
            }
        }, "prefetch bookshelf WebDAV progress")
    }

    private fun startBookshelfProgressPrefetch(total: Int) {
        clearProgressPrefetchStatusRunnable?.let { mainHandler.removeCallbacks(it) }
        clearProgressPrefetchStatusRunnable = null
        progressPrefetchRunning = true
        progressPrefetchCurrent = 0
        progressPrefetchTotal = total
        progressPrefetchFailed = false
        updateBookshelfStatsText()
    }

    private fun updateBookshelfProgressPrefetchCurrent(current: Int) {
        progressPrefetchCurrent = current
        updateBookshelfStatsText()
    }

    private fun finishBookshelfProgressPrefetch(failed: Boolean) {
        progressPrefetchRunning = false
        progressPrefetchFailed = failed
        updateBookshelfStatsText()
        if (!failed) {
            return
        }
        clearProgressPrefetchStatusRunnable?.let { mainHandler.removeCallbacks(it) }
        clearProgressPrefetchStatusRunnable = Runnable {
            clearProgressPrefetchStatusRunnable = null
            progressPrefetchFailed = false
            updateBookshelfStatsText()
        }
        mainHandler.postDelayed(
            clearProgressPrefetchStatusRunnable!!,
            PROGRESS_PREFETCH_FAILURE_HINT_MS,
        )
    }

    private fun snapshotBookForProgressPrefetch(source: BookRecord): BookRecord {
        val snapshot = BookRecord()
        snapshot.id = source.id
        snapshot.title = source.title
        snapshot.author = source.author
        snapshot.localPath = source.localPath
        snapshot.coverPath = source.coverPath
        snapshot.bookType = source.bookType
        snapshot.readingStatsKey = source.readingStatsKey
        snapshot.progressIndex = source.progressIndex
        snapshot.progressOffset = source.progressOffset
        snapshot.lastReadAt = source.lastReadAt
        snapshot.pinned = source.pinned
        snapshot.currentChapterTitle = source.currentChapterTitle
        snapshot.chapterCount = source.chapterCount
        snapshot.createdAt = source.createdAt
        snapshot.updatedAt = source.updatedAt
        snapshot.copyExtendedFieldsFrom(source)
        return snapshot
    }

    private fun applyRemoteProgressToBookItem(updatedBook: BookRecord?) {
        if (updatedBook == null || updatedBook.id <= 0) {
            return
        }
        var replaced = false
        for (i in allBooks.indices) {
            val book = allBooks[i]
            if (book.id == updatedBook.id) {
                allBooks[i] = updatedBook
                replaced = true
                break
            }
        }
        if (!replaced) {
            return
        }
        applyFilter(currentQuery())
    }

    private fun applyFilter(query: String?) {
        val normalized = normalizeQuery(query)
        val filtered: MutableList<BookRecord> = ArrayList()
        for (book in allBooks) {
            if (BookshelfFilter.matches(book, normalized, selectedTagFilter, selectedSeriesFilter, selectedStatusFilter)) {
                filtered.add(book)
            }
        }
        listAdapter.setItems(filtered)
        gridAdapter.setItems(filtered)
        val existingIds: MutableSet<Long> = HashSet()
        for (book in allBooks) existingIds.add(book.id)
        val iterator = selectedBookIds.iterator()
        while (iterator.hasNext()) {
            if (!existingIds.contains(iterator.next())) {
                iterator.remove()
            }
        }
        updateSelectionViews()
        updateAddEntryVisibility()
        updateBookshelfStatsText()
        updateEmptyState(query ?: "")
    }

    private fun updateAddEntryVisibility() {
        if (!::settingsStore.isInitialized) {
            return
        }
        val visible = settingsStore.isBookshelfAddEntryVisible && !bookshelfManagementMode
        if (::gridAdapter.isInitialized) {
            gridAdapter.setShowAddEntry(visible)
        }
        if (::listFooterView.isInitialized) {
            listFooterView.visibility = if (visible) View.VISIBLE else View.GONE
            listFooterView.isEnabled = visible
        }
    }

    private fun updateEmptyState(query: String?) {
        val showEmpty = shouldShowBookshelfEmptyState(query)
        emptyLayout.visibility = if (showEmpty) View.VISIBLE else View.GONE
        if (!booksLoaded) {
            emptyLayout.visibility = View.GONE
            gridBooks.visibility = View.GONE
            listBooks.visibility = View.GONE
            return
        }
        if (showEmpty) {
            gridBooks.visibility = View.GONE
            listBooks.visibility = View.GONE
            if (normalizeQuery(query).isEmpty() && !hasActiveBookshelfFilters()) {
                emptyTitle.setText(R.string.empty_bookshelf)
                emptyHint.setText(R.string.empty_bookshelf_hint)
                emptyActionButton.text = "添加书籍"
            } else {
                emptyTitle.text = "没有找到匹配的书籍"
                emptyHint.text = "调整搜索词或清除当前筛选。"
                emptyActionButton.text = "清除筛选"
            }
            return
        }
        emptyLayout.visibility = View.GONE
        applyBookshelfMode()
    }

    private fun shouldShowBookshelfEmptyState(query: String?): Boolean = booksLoaded && listAdapter.count == 0

    private fun updateBookshelfStatsText() {
        if (progressPrefetchFailed) {
            statsText.text = "云端进度同步失败，已展示本地进度"
            return
        }
        if (progressPrefetchRunning) {
            statsText.text = String.format(
                Locale.SIMPLIFIED_CHINESE,
                "正在同步云端阅读进度 %d/%d...",
                progressPrefetchCurrent,
                progressPrefetchTotal,
            )
            return
        }
        if (booksLoading) {
            statsText.text = if (booksLoaded) "正在刷新书架..." else "正在加载书架..."
            return
        }
        val visibleBookCount = if (::listAdapter.isInitialized) listAdapter.count else allBooks.size
        if (hasActiveBookshelfFilters()) {
            statsText.text = String.format(
                Locale.SIMPLIFIED_CHINESE,
                "筛选结果 %d 本，共 %d 本",
                visibleBookCount,
                allBooks.size,
            )
        } else {
            statsText.text = String.format(Locale.SIMPLIFIED_CHINESE, "共 %d 本书籍", visibleBookCount)
        }
    }

    private fun setBookshelfManagementMode(enabled: Boolean) {
        bookshelfManagementMode = enabled
        if (!enabled) selectedBookIds.clear()
        headerManageButton.text = if (enabled) "完成" else "管理"
        headerActionButton.visibility = if (enabled) View.GONE else View.VISIBLE
        bookshelfFiltersLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        bookshelfBatchActionsLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        sectionTitle.text = if (enabled) "已选择 " + selectedBookIds.size + " 本" else "书架"
        updateAddEntryVisibility()
        updateSelectionViews()
    }

    private fun toggleBookSelection(bookId: Long) {
        if (!selectedBookIds.add(bookId)) selectedBookIds.remove(bookId)
        updateSelectionViews()
    }

    private fun updateSelectionViews() {
        if (::listAdapter.isInitialized) listAdapter.setSelectedBookIds(selectedBookIds)
        if (::gridAdapter.isInitialized) gridAdapter.setSelectedBookIds(selectedBookIds)
        if (::selectionCountText.isInitialized) selectionCountText.text = "已选 " + selectedBookIds.size + " 本"
        if (bookshelfManagementMode && ::sectionTitle.isInitialized) {
            sectionTitle.text = "已选择 " + selectedBookIds.size + " 本"
        }
        val hasSelection = selectedBookIds.isNotEmpty()
        if (::batchClassifyButton.isInitialized) batchClassifyButton.isEnabled = hasSelection
        if (::batchExportButton.isInitialized) batchExportButton.isEnabled = hasSelection
        if (::batchDeleteButton.isInitialized) batchDeleteButton.isEnabled = hasSelection
    }

    private fun hasActiveBookshelfFilters(): Boolean =
        selectedTagFilter.isNotEmpty() || selectedSeriesFilter.isNotEmpty() || selectedStatusFilter.isNotEmpty()

    private fun clearBookshelfFilters() {
        selectedTagFilter = ""
        selectedSeriesFilter = ""
        selectedStatusFilter = ""
        updateFilterButtonLabels()
        applyFilter(currentQuery())
    }

    private fun updateFilterButtonLabels() {
        filterTagButton.text = if (selectedTagFilter.isEmpty()) "标签 ▾" else "标签·" + selectedTagFilter
        filterSeriesButton.text = if (selectedSeriesFilter.isEmpty()) "系列 ▾" else "系列·" + selectedSeriesFilter
        filterStatusButton.text = if (selectedStatusFilter.isEmpty()) "状态 ▾" else statusLabel(selectedStatusFilter)
    }

    private fun showTagFilterDialog() {
        val values = TreeSet<String>()
        for (book in allBooks) {
            val tags = book.tags
            if (tags != null) values.addAll(tags)
        }
        showFilterDialog("按标签筛选", ArrayList(values), selectedTagFilter) { value -> selectedTagFilter = value }
    }

    private fun showSeriesFilterDialog() {
        val values = TreeSet<String>()
        for (book in allBooks) {
            val value = book.series?.trim() ?: ""
            if (value.isNotEmpty()) values.add(value)
        }
        showFilterDialog("按系列筛选", ArrayList(values), selectedSeriesFilter) { value -> selectedSeriesFilter = value }
    }

    private fun showStatusFilterDialog() {
        val values: MutableList<String> = ArrayList()
        values.add(BookRecord.STATUS_UNREAD)
        values.add(BookRecord.STATUS_READING)
        values.add(BookRecord.STATUS_FINISHED)
        val labels = arrayOf("全部状态", "未读", "阅读中", "已读完")
        AlertDialog.Builder(this)
            .setTitle("按阅读状态筛选")
            .setItems(labels) { _, which ->
                selectedStatusFilter = if (which == 0) "" else values[which - 1]
                updateFilterButtonLabels()
                applyFilter(currentQuery())
            }
            .show()
    }

    private fun showFilterDialog(title: String, values: List<String>, selected: String, setter: (String) -> Unit) {
        val options: MutableList<String> = ArrayList()
        options.add("全部")
        options.addAll(values)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                setter(if (which == 0) "" else options[which])
                updateFilterButtonLabels()
                applyFilter(currentQuery())
            }
            .show()
    }

    private fun statusLabel(status: String?): String {
        if (BookRecord.STATUS_FINISHED == status) return "已读完"
        if (BookRecord.STATUS_READING == status) return "阅读中"
        return "未读"
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("text/plain", "application/epub+zip", "application/pdf", "application/octet-stream"),
        )
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, REQUEST_PICK_BOOK)
    }

    private fun openCoverPicker(bookId: Long) {
        pendingCoverBookId = bookId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        startActivityForResult(intent, REQUEST_PICK_COVER)
    }

    private fun importBooks(uris: List<Uri>?) {
        if (uris.isNullOrEmpty()) return
        showLoading("正在检查 " + uris.size + " 本书籍...")
        safeExecute({
            val prepared: MutableList<PreparedBookImport> = ArrayList()
            var failCount = 0
            var firstError: String? = null
            for (index in uris.indices) {
                val uri = uris[index]
                if (Thread.currentThread().isInterrupted || bookshelfDestroyed) {
                    cleanupPreparedImports(prepared)
                    return@safeExecute
                }
                try {
                    prepared.add(
                        PreparedBookImport(
                            "incoming-" + index,
                            importService.prepareFromUri(uri),
                        ),
                    )
                } catch (error: Exception) {
                    failCount++
                    Log.w(TAG, "导入预检失败: " + uri, error)
                    if (firstError == null) {
                        firstError = readableError(error)
                    }
                }
            }
            val existing = databaseHelper.backfillMissingContentHashes()
            val existingCandidates: MutableList<BookDuplicateDetector.Candidate> = ArrayList()
            for (book in existing) {
                existingCandidates.add(
                    BookDuplicateDetector.Candidate(
                        "existing-" + book.id,
                        book.title,
                        book.author,
                        book.contentSha256,
                    ),
                )
            }
            val incomingCandidates: MutableList<BookDuplicateDetector.Candidate> = ArrayList()
            for (item in prepared) {
                val value = item.prepared
                incomingCandidates.add(
                    BookDuplicateDetector.Candidate(
                        item.key,
                        value.title,
                        value.author,
                        value.contentSha256,
                    ),
                )
            }
            val duplicates = BookDuplicateDetector.detect(existingCandidates, incomingCandidates)
            val finalFailCount = failCount
            val finalFirstError = firstError
            runOnUiThread {
                if (!isBookshelfActive()) {
                    cleanupPreparedImports(prepared)
                    return@runOnUiThread
                }
                handlePreparedImports(prepared, duplicates, finalFailCount, finalFirstError)
            }
        }, "prepare imported books")
    }

    private fun handlePreparedImports(
        prepared: List<PreparedBookImport>,
        duplicates: Map<String, BookDuplicateDetector.MatchType>,
        preparationFailures: Int,
        firstError: String?,
    ) {
        hideLoading()
        if (prepared.isEmpty()) {
            showToast(if (firstError == null) "没有可导入的书籍" else "导入失败: " + firstError)
            return
        }
        if (duplicates.isEmpty()) {
            continuePreparedImports(prepared, preparationFailures, 0, firstError)
            return
        }
        var exact = 0
        var suspected = 0
        val names: MutableList<String> = ArrayList()
        for (item in prepared) {
            val type = duplicates[item.key] ?: continue
            if (type == BookDuplicateDetector.MatchType.EXACT_CONTENT) exact++ else suspected++
            if (names.size < 4) names.add(item.prepared.displayName)
        }
        val message = StringBuilder()
        message.append("发现完全相同内容 ").append(exact).append(" 本，书名作者相同 ")
            .append(suspected).append(" 本。\n\n")
        for (name in names) message.append("• ").append(name).append('\n')
        if (duplicates.size > names.size) {
            message.append("另有 ").append(duplicates.size - names.size).append(" 本")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("发现重复书籍")
            .setMessage(message.toString().trim())
            .setNegativeButton("跳过重复") { _, _ ->
                val remaining: MutableList<PreparedBookImport> = ArrayList()
                for (item in prepared) {
                    if (duplicates.containsKey(item.key)) item.prepared.deleteLocalCopy() else remaining.add(item)
                }
                continuePreparedImports(remaining, preparationFailures, duplicates.size, firstError)
            }
            .setPositiveButton("仍然全部导入") { _, _ ->
                continuePreparedImports(prepared, preparationFailures, 0, firstError)
            }
            .create()
        dialog.setOnCancelListener { cleanupPreparedImports(prepared) }
        dialog.show()
    }

    private fun continuePreparedImports(
        prepared: List<PreparedBookImport>,
        previousFailures: Int,
        skippedDuplicates: Int,
        previousError: String?,
    ) {
        if (prepared.isEmpty()) {
            refreshBooks()
            showToast(if (skippedDuplicates > 0) "已跳过 " + skippedDuplicates + " 本重复书籍" else "没有可导入的书籍")
            return
        }
        showLoading("正在导入 " + prepared.size + " 本书籍...")
        safeExecute({
            var success = 0
            var failed = previousFailures
            var firstError = previousError
            val importedBookIds: MutableList<Long> = ArrayList()
            for (item in prepared) {
                try {
                    val bookId = databaseHelper.insertImportedBook(importService.parsePrepared(item.prepared, false))
                    importedBookIds.add(bookId)
                    success++
                } catch (error: Exception) {
                    failed++
                    item.prepared.deleteLocalCopy()
                    if (firstError == null) firstError = readableError(error)
                    Log.w(TAG, "导入书籍失败: " + item.prepared.displayName, error)
                }
            }
            val finalSuccess = success
            val finalFailed = failed
            val finalError = firstError
            runOnBookshelfUiThread {
                hideLoading()
                refreshBooks()
                val summary = StringBuilder("导入完成：成功 ").append(finalSuccess)
                if (skippedDuplicates > 0) summary.append("，跳过重复 ").append(skippedDuplicates)
                if (finalFailed > 0) summary.append("，失败 ").append(finalFailed)
                if (finalSuccess == 0 && finalFailed > 0 && finalError != null) {
                    showToast("导入失败: " + finalError)
                } else {
                    showToast(summary.toString())
                }
                for (bookId in importedBookIds) {
                    safeExecute({
                        try {
                            BookSearchIndex(this, databaseHelper).build(bookId) { bookshelfDestroyed }
                        } catch (error: Exception) {
                            Log.d(TAG, "Search index warmup skipped for book " + bookId, error)
                        }
                    }, "build imported book search index")
                }
            }
        }, "parse imported books")
    }

    private fun cleanupPreparedImports(prepared: List<PreparedBookImport>?) {
        if (prepared == null) return
        for (item in prepared) {
            item.prepared.deleteLocalCopy()
        }
    }

    private fun openBook(bookId: Long) {
        openBook(bookId, null)
    }

    private fun openBook(bookId: Long, sourceView: View?) {
        if (!isBookshelfActive()) {
            return
        }
        launchReader(bookId, sourceView)
    }

    private fun launchReader(bookId: Long, sourceView: View?) {
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putExtra("book_id", bookId)
        if (com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(settingsStore)) {
            LaunchSourceTransition.attachBoundsOnly(intent, sourceView)
        }
        startActivity(intent)
    }

    private fun showBookActions(book: BookRecord, sourceView: View?) {
        dismissBookActionsPopup()
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundResource(R.drawable.bg_app_dialog)
        panel.setPadding(dp(12), dp(12), dp(12), dp(12))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            panel.elevation = dp(10).toFloat()
        }

        val title = TextView(this)
        title.text = if (book.title.isNullOrBlank()) "未命名书籍" else book.title
        title.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_primary))
        title.textSize = 15f
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        panel.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val divider = View(this)
        divider.setBackgroundColor(ThemeModeHelper.resolveColor(this, R.color.app_border))
        val dividerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, dp(1)),
        )
        dividerParams.topMargin = dp(10)
        dividerParams.bottomMargin = dp(6)
        panel.addView(divider, dividerParams)

        val itemList: MutableList<String> = ArrayList()
        itemList.add("打开")
        itemList.add(if (book.pinned) "取消置顶" else "置顶到顶部")
        itemList.add("分类")
        itemList.add("设置自定义封面")
        if (!book.coverPath.isNullOrBlank()) {
            itemList.add("移除封面")
        }
        itemList.add("删除")
        for (item in itemList) {
            panel.addView(
                createBookActionRow(item, "删除" == item) {
                    dismissBookActionsPopup()
                    when (item) {
                        "打开" -> openBook(book.id, sourceView)
                        "分类" -> showBookClassificationDialog(book)
                        "设置自定义封面" -> openCoverPicker(book.id)
                        "移除封面" -> removeCover(book)
                        "删除" -> confirmDelete(book)
                        else -> safeExecute({
                            databaseHelper.setPinned(book.id, !book.pinned)
                            runOnBookshelfUiThread { refreshBooks() }
                        }, "toggle book pin")
                    }
                },
            )
        }

        val popupWidth = Math.min(dp(300), Math.max(dp(232), resources.displayMetrics.widthPixels - dp(32)))
        val popup = PopupWindow(panel, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        bookActionsPopup = popup
        popup.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            popup.elevation = dp(10).toFloat()
        }
        if (sourceView != null) {
            popup.showAsDropDown(sourceView, 0, -sourceView.height, Gravity.END)
        } else {
            val root = findViewById<View>(android.R.id.content)
            popup.showAtLocation(root, Gravity.CENTER, 0, 0)
        }
    }

    private fun createBookActionRow(text: String, danger: Boolean, action: Runnable): TextView {
        val row = TextView(this)
        row.text = text
        row.gravity = Gravity.CENTER_VERTICAL
        row.minHeight = dp(44)
        row.setPadding(dp(12), dp(9), dp(12), dp(9))
        row.textSize = 14f
        row.setTextColor(
            ThemeModeHelper.resolveColor(
                this,
                if (danger) R.color.app_danger else R.color.app_text_primary,
            ),
        )
        row.setBackgroundResource(R.drawable.bg_app_soft_button)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dp(6)
        row.layoutParams = params
        row.setOnClickListener { action.run() }
        return row
    }

    private fun showBookClassificationDialog(book: BookRecord) {
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(8), dp(20), 0)

        val tagsInput = classificationInput("标签，用逗号分隔")
        tagsInput.setText(book.tags?.joinToString(", ") ?: "")
        content.addView(tagsInput)

        val seriesInput = classificationInput("系列名称，可留空")
        seriesInput.setText(book.series ?: "")
        val seriesParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        seriesParams.topMargin = dp(10)
        content.addView(seriesInput, seriesParams)

        val statusTitle = TextView(this)
        statusTitle.text = "阅读状态"
        statusTitle.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_primary))
        val titleParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        titleParams.topMargin = dp(14)
        content.addView(statusTitle, titleParams)

        val statusRow = LinearLayout(this)
        statusRow.orientation = LinearLayout.HORIZONTAL
        val statusHolder = arrayOf(BookRecord.normalizeReadingStatus(book.readingStatus, false))
        val unread = classificationStatusButton("未读")
        val reading = classificationStatusButton("阅读中")
        val finished = classificationStatusButton("已读完")
        statusRow.addView(unread, weightedButtonParams(0))
        statusRow.addView(reading, weightedButtonParams(dp(6)))
        statusRow.addView(finished, weightedButtonParams(dp(6)))
        val refreshStatus = Runnable {
            AppUiUtils.styleSelectionButton(this, unread, BookRecord.STATUS_UNREAD == statusHolder[0])
            AppUiUtils.styleSelectionButton(this, reading, BookRecord.STATUS_READING == statusHolder[0])
            AppUiUtils.styleSelectionButton(this, finished, BookRecord.STATUS_FINISHED == statusHolder[0])
        }
        unread.setOnClickListener {
            statusHolder[0] = BookRecord.STATUS_UNREAD
            refreshStatus.run()
        }
        reading.setOnClickListener {
            statusHolder[0] = BookRecord.STATUS_READING
            refreshStatus.run()
        }
        finished.setOnClickListener {
            statusHolder[0] = BookRecord.STATUS_FINISHED
            refreshStatus.run()
        }
        refreshStatus.run()
        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        rowParams.topMargin = dp(8)
        content.addView(statusRow, rowParams)

        AlertDialog.Builder(this)
            .setTitle("分类")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                safeExecute({
                    databaseHelper.updateBookClassification(
                        book.id,
                        parseTags(tagsInput.text?.toString() ?: ""),
                        seriesInput.text?.toString() ?: "",
                        statusHolder[0],
                    )
                    runOnBookshelfUiThread { refreshBooks() }
                }, "update book classification")
            }
            .show()
    }

    private fun showBatchClassificationActions() {
        if (selectedBookIds.isEmpty()) return
        val actions = arrayOf("添加标签", "移除标签", "设置系列", "清除系列", "设置阅读状态")
        AlertDialog.Builder(this)
            .setTitle("批量分类")
            .setItems(actions) { _, which ->
                if (which == 0) showBatchTagInput(true)
                else if (which == 1) showBatchTagInput(false)
                else if (which == 2) showBatchSeriesInput()
                else if (which == 3) applyBatchSeries("")
                else showBatchStatusDialog()
            }
            .show()
    }

    private fun showBatchTagInput(add: Boolean) {
        val input = classificationInput("标签，用逗号分隔")
        val padding = dp(20)
        input.setPadding(padding, dp(12), padding, dp(12))
        AlertDialog.Builder(this)
            .setTitle(if (add) "添加标签" else "移除标签")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val tags = parseTags(input.text?.toString() ?: "")
                val ids: Set<Long> = HashSet(selectedBookIds)
                safeExecute({
                    if (add) databaseHelper.addTagsToBooks(ids, tags) else databaseHelper.removeTagsFromBooks(ids, tags)
                    runOnBookshelfUiThread { refreshBooks() }
                }, if (add) "batch add tags" else "batch remove tags")
            }
            .show()
    }

    private fun showBatchSeriesInput() {
        val input = classificationInput("系列名称")
        val padding = dp(20)
        input.setPadding(padding, dp(12), padding, dp(12))
        AlertDialog.Builder(this)
            .setTitle("设置系列")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> applyBatchSeries(input.text?.toString() ?: "") }
            .show()
    }

    private fun applyBatchSeries(series: String) {
        val ids: Set<Long> = HashSet(selectedBookIds)
        safeExecute({
            databaseHelper.setSeriesForBooks(ids, series)
            runOnBookshelfUiThread { refreshBooks() }
        }, "batch set series")
    }

    private fun showBatchStatusDialog() {
        val labels = arrayOf("未读", "阅读中", "已读完")
        val values = arrayOf(BookRecord.STATUS_UNREAD, BookRecord.STATUS_READING, BookRecord.STATUS_FINISHED)
        AlertDialog.Builder(this)
            .setTitle("设置阅读状态")
            .setItems(labels) { _, which ->
                val ids: Set<Long> = HashSet(selectedBookIds)
                safeExecute({
                    databaseHelper.setReadingStatusForBooks(ids, values[which])
                    runOnBookshelfUiThread { refreshBooks() }
                }, "batch set reading status")
            }
            .show()
    }

    private fun classificationInput(hint: String): EditText {
        val input = EditText(this)
        input.hint = hint
        input.isSingleLine = true
        input.setBackgroundResource(R.drawable.bg_app_input)
        input.setPadding(dp(12), dp(10), dp(12), dp(10))
        return input
    }

    private fun classificationStatusButton(text: String): Button {
        val button = Button(this)
        button.text = text
        button.isAllCaps = false
        button.minWidth = 0
        button.minHeight = dp(40)
        return button
    }

    private fun weightedButtonParams(startMargin: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, dp(40), 1f)
        params.marginStart = startMargin
        return params
    }

    private fun parseTags(value: String?): List<String> {
        val result: MutableList<String> = ArrayList()
        if (value == null) return result
        for (part in value.split(Regex("[,，]"))) {
            val tag = part.trim()
            if (tag.isNotEmpty() && !result.contains(tag)) result.add(tag)
        }
        return result
    }

    private fun dp(value: Int): Int = Math.round(resources.displayMetrics.density * value)

    private fun dismissBookActionsPopup() {
        val popup = bookActionsPopup
        if (popup != null && popup.isShowing) {
            popup.dismiss()
        }
        bookActionsPopup = null
    }

    private fun attachCover(bookId: Long, uri: Uri?) {
        if (uri == null) return
        showLoading("正在保存封面...")
        safeExecute({
            try {
                val currentBook = databaseHelper.getBook(bookId)
                val coverFile = CoverImageStore.saveCompressedCover(this, uri, "cover_" + bookId)
                if (currentBook != null && !currentBook.coverPath.isNullOrBlank()) {
                    FileAssetHelper.deleteIfExists(currentBook.coverPath)
                }
                databaseHelper.setCoverPath(bookId, coverFile.absolutePath)
                runOnBookshelfUiThread {
                    pendingCoverBookId = -1L
                    hideLoading()
                    refreshBooks()
                    showToast("封面已更新")
                }
            } catch (error: Exception) {
                runOnBookshelfUiThread {
                    pendingCoverBookId = -1L
                    hideLoading()
                    showToast("保存封面失败: " + error.message)
                }
            }
        }, "attach book cover")
    }

    private fun removeCover(book: BookRecord) {
        safeExecute({
            FileAssetHelper.deleteIfExists(book.coverPath)
            databaseHelper.setCoverPath(book.id, null)
            runOnBookshelfUiThread {
                refreshBooks()
                showToast("已移除封面")
            }
        }, "remove book cover")
    }

    private fun confirmDelete(book: BookRecord) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除书籍")
            .setMessage("确定要删除《" + book.title + "》吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                safeExecute({
                    try {
                        SnapshotManager(this, databaseHelper, settingsStore).createSnapshot("delete-book")
                    } catch (error: Exception) {
                        Log.w(TAG, "Create snapshot before delete failed", error)
                    }
                    databaseHelper.deleteBook(book.id)
                    BookSearchIndex.delete(this, book.id)
                    runOnBookshelfUiThread {
                        refreshBooks()
                        showToast("已删除")
                    }
                }, "delete book")
            }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_app_dialog)
        }
        dialog.show()
    }

    private fun selectedBooksSnapshot(): List<BookRecord> {
        val result: MutableList<BookRecord> = ArrayList()
        for (book in allBooks) {
            if (selectedBookIds.contains(book.id)) result.add(book)
        }
        return result
    }

    private fun confirmBatchDelete() {
        val selected = selectedBooksSnapshot()
        if (selected.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除 " + selected.size + " 本书籍")
            .setMessage("将删除所选书籍、章节、书签和本地文件，此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val ids: Set<Long> = HashSet(selectedBookIds)
                showLoading("正在删除 " + ids.size + " 本书籍...")
                safeExecute({
                    try {
                        SnapshotManager(this, databaseHelper, settingsStore).createSnapshot("delete-books")
                    } catch (error: Exception) {
                        Log.w(TAG, "Create snapshot before batch delete failed", error)
                    }
                    databaseHelper.deleteBooks(ids)
                    for (id in ids) BookSearchIndex.delete(this, id)
                    runOnBookshelfUiThread {
                        selectedBookIds.clear()
                        setBookshelfManagementMode(false)
                        hideLoading()
                        refreshBooks()
                        showToast("已删除 " + ids.size + " 本书籍")
                    }
                }, "batch delete books")
            }
            .show()
    }

    private fun startBatchExport() {
        pendingExportBooks = ArrayList(selectedBooksSnapshot())
        if (pendingExportBooks.isEmpty()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_EXPORT_BOOKS_DIRECTORY)
    }

    private fun exportPendingBooks(directoryUri: Uri) {
        val books: List<BookRecord> = ArrayList(pendingExportBooks)
        pendingExportBooks.clear()
        if (books.isEmpty()) return
        showLoading("正在导出 " + books.size + " 本书籍...")
        safeExecute({
            var success = 0
            var failed = 0
            val directory = DocumentFile.fromTreeUri(this, directoryUri)
            if (directory == null || !directory.canWrite()) {
                runOnBookshelfUiThread {
                    hideLoading()
                    showToast("无法写入所选目录")
                }
                return@safeExecute
            }
            val usedNames: MutableSet<String> = HashSet()
            for (child in directory.listFiles()) {
                child.name?.let { usedNames.add(it) }
            }
            val resolver: ContentResolver = contentResolver
            for (book in books) {
                val source = book.localPath?.let { File(it) }
                if (source == null || !source.isFile) {
                    failed++
                    continue
                }
                val fileName = BookExportNaming.uniqueFileName(book, usedNames)
                val target = directory.createFile(mimeTypeForBook(book), fileName)
                if (target == null) {
                    failed++
                    continue
                }
                try {
                    FileInputStream(source).use { input ->
                        val output: OutputStream = resolver.openOutputStream(target.uri, "w")
                            ?: throw java.io.IOException("无法创建导出文件")
                        output.use { out ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    success++
                } catch (error: Exception) {
                    failed++
                    target.delete()
                    Log.w(TAG, "Export book failed: " + book.id, error)
                }
            }
            val finalSuccess = success
            val finalFailed = failed
            runOnBookshelfUiThread {
                hideLoading()
                if (finalFailed == 0) showToast("已导出 " + finalSuccess + " 本书籍")
                else showToast("导出完成：成功 " + finalSuccess + "，失败 " + finalFailed)
            }
        }, "batch export books")
    }

    private fun mimeTypeForBook(book: BookRecord?): String {
        if (book != null && "epub".equals(book.bookType, ignoreCase = true)) return "application/epub+zip"
        if (book != null && "pdf".equals(book.bookType, ignoreCase = true)) return "application/pdf"
        return "text/plain"
    }

    private fun maybeAutoOpenLastBook() {
        safeExecute({
            try {
                val bookId = databaseHelper.getMostRecentBookId()
                if (bookId > 0) {
                    runOnBookshelfUiThread { openBook(bookId) }
                }
            } catch (_: Exception) {
            }
        }, "auto open last book")
    }

    private fun performAutoOpenFastPath(bookId: Long) {
        autoOpenConsumed = true
        safeExecute({
            try {
                val book = databaseHelper.getBook(bookId)
                if (book == null) {
                    runOnBookshelfUiThread {
                        autoOpenConsumed = false
                        showBookshelfLoadingState()
                        refreshBooks()
                    }
                    return@safeExecute
                }
                runOnBookshelfUiThread {
                    val single: MutableList<BookRecord> = ArrayList()
                    single.add(book)
                    listAdapter.setItems(single)
                    gridAdapter.setItems(single)
                    updateAddEntryVisibility()
                    updateBookshelfStatsText()
                    booksLoaded = true
                    booksLoading = false
                    applyBookshelfMode()

                    val container: View = if (isCardMode()) gridBooks else listBooks
                    container.post {
                        if (!isBookshelfActive()) {
                            return@post
                        }
                        var sourceView: View? = null
                        if (isCardMode() && gridBooks.childCount > 0) {
                            sourceView = gridBooks.getChildAt(0)
                        } else if (!isCardMode() && listBooks.childCount > 0) {
                            sourceView = listBooks.getChildAt(0)
                        }
                        openBook(book.id, sourceView)
                        autoOpenConsumed = false

                        safeExecute({
                            try {
                                val books = databaseHelper.getBooks()
                                runOnBookshelfUiThread {
                                    booksLoading = false
                                    booksLoaded = true
                                    allBooks.clear()
                                    allBooks.addAll(books)
                                    applyFilter(currentQuery())
                                    scrollToBook(bookId)
                                }
                            } catch (_: Exception) {
                                runOnBookshelfUiThread {
                                    booksLoading = false
                                    applyFilter(currentQuery())
                                }
                            }
                        }, "load full bookshelf behind reader")
                    }
                }
            } catch (_: Exception) {
                runOnBookshelfUiThread {
                    autoOpenConsumed = false
                    showBookshelfLoadingState()
                    refreshBooks()
                }
            }
        }, "auto open fast path")
    }

    private fun scrollToBook(bookId: Long) {
        if (isCardMode()) {
            for (i in 0 until gridAdapter.count) {
                val item = gridAdapter.getItem(i)
                if (item != null && item.id == bookId) {
                    gridBooks.setSelection(i)
                    return
                }
            }
        } else {
            for (i in 0 until listAdapter.count) {
                val item = listAdapter.getItem(i)
                if (item.id == bookId) {
                    listBooks.setSelection(i)
                    return
                }
            }
        }
    }

    private fun showBookshelfLoadingState() {
        if (!booksLoaded) {
            emptyLayout.visibility = View.GONE
            gridBooks.visibility = View.GONE
            listBooks.visibility = View.GONE
            applyBookshelfMode()
        }
        updateBookshelfStatsText()
    }

    private fun showLoading(message: String) {
        loadingText.text = message
        loadingLayout.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingLayout.visibility = View.GONE
    }

    private fun showToast(message: String) {
        AppUiUtils.showToast(this, message)
    }

    private fun isCardMode(): Boolean = VIEW_MODE_CARD == settingsStore.bookshelfViewMode

    private fun currentQuery(): String = searchInput.text?.toString() ?: ""

    private fun normalizeQuery(query: String?): String = query?.trim()?.lowercase(Locale.ROOT) ?: ""

    private fun readableError(error: Throwable?): String {
        val message = error?.message
        if (message.isNullOrBlank()) {
            return "未知错误"
        }
        return message
    }

    private class PreparedBookImport(
        val key: String,
        val prepared: BookImportService.PreparedImport,
    )

    companion object {
        private const val TAG = "BookshelfActivity"
        private const val REQUEST_PICK_BOOK = 1001
        private const val REQUEST_PICK_COVER = 1002
        private const val REQUEST_EXPORT_BOOKS_DIRECTORY = 1003
        private const val VIEW_MODE_CARD = "card"
        private const val STATE_HOME_PAGE = "state_home_page"
        private const val PROGRESS_PREFETCH_FAILURE_HINT_MS = 3000L
        const val EXTRA_AUTO_OPEN_BOOK_ID = "com.metahumanz.pacilread.EXTRA_AUTO_OPEN_BOOK_ID"
    }
}
