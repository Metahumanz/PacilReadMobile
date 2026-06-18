package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.importer.BookDuplicateDetector;
import com.metahumanz.pacilread.export.BookExportNaming;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.reader.search.BookSearchIndex;
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.storage.SnapshotManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.sync.WebDavProgressSyncCoordinator;
import com.metahumanz.pacilread.theme.ThemedActivity;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.ui.LaunchSourceTransition;
import com.metahumanz.pacilread.util.CoverImageStore;
import com.metahumanz.pacilread.util.FileAssetHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class BookshelfActivity extends ThemedActivity {
    private static final String TAG = "BookshelfActivity";
    private static final int REQUEST_PICK_BOOK = 1001;
    private static final int REQUEST_PICK_COVER = 1002;
    private static final int REQUEST_EXPORT_BOOKS_DIRECTORY = 1003;
    private static final String VIEW_MODE_CARD = "card";
    private static final String STATE_HOME_PAGE = "state_home_page";
    private static final long PROGRESS_PREFETCH_FAILURE_HINT_MS = 3000L;
    public static final String EXTRA_AUTO_OPEN_BOOK_ID =
            "com.metahumanz.pacilread.EXTRA_AUTO_OPEN_BOOK_ID";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService progressPrefetchExecutor = Executors.newSingleThreadExecutor();
    private final List<BookRecord> allBooks = new ArrayList<>();
    private final Set<Long> selectedBookIds = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private JsonDatabase databaseHelper;
    private SettingsStore settingsStore;
    private WebDavProgressSyncCoordinator progressSyncCoordinator;
    private BookImportService importService;
    private BookListAdapter listAdapter;
    private BookGridAdapter gridAdapter;
    private HomeNavigationController homeNavigationController;
    private HomeStatsPanelController homeStatsPanelController;
    private HomeBookmarksPanelController homeBookmarksPanelController;
    private SettingsScreenController homeSettingsController;
    private View listFooterView;

    // Views
    private LinearLayout emptyLayout;
    private LinearLayout loadingLayout;
    private EditText searchInput;
    private GridView gridBooks;
    private ListView listBooks;
    private TextView sectionTitle;
    private TextView loadingText;
    private TextView statsText;
    private TextView emptyTitle;
    private TextView emptyHint;
    private Button headerActionButton;
    private Button headerManageButton;
    private Button buttonModeCard;
    private Button buttonModeList;
    private Button emptyActionButton;
    private View containerSearch;
    private View iconSearch;
    private View bookshelfFiltersLayout;
    private View bookshelfBatchActionsLayout;
    private Button filterTagButton;
    private Button filterSeriesButton;
    private Button filterStatusButton;
    private Button filterClearButton;
    private Button batchClassifyButton;
    private Button batchExportButton;
    private Button batchDeleteButton;
    private TextView selectionCountText;
    private long pendingCoverBookId = -1L;
    private boolean booksLoaded = false;
    private boolean booksLoading = false;
    private boolean autoOpenConsumed;
    private volatile boolean bookshelfDestroyed;
    private Runnable clearProgressPrefetchStatusRunnable;
    private boolean progressPrefetchRunning;
    private int progressPrefetchCurrent;
    private int progressPrefetchTotal;
    private boolean progressPrefetchFailed;
    private PopupWindow bookActionsPopup;
    private boolean bookshelfManagementMode;
    private String selectedTagFilter = "";
    private String selectedSeriesFilter = "";
    private String selectedStatusFilter = "";
    private List<BookRecord> pendingExportBooks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bookshelf);

        databaseHelper = JsonDatabase.getInstance(this);
        settingsStore = new SettingsStore(this);
        progressSyncCoordinator = new WebDavProgressSyncCoordinator(
                databaseHelper,
                settingsStore,
                new WebDavClient(settingsStore)
        );
        importService = new BookImportService(this);

        bindViews();
        setupAdapters();
        setupInteractions();
        setupHomeControllers();
        if (savedInstanceState != null && homeNavigationController != null) {
            homeNavigationController.restoreHomePage(
                    savedInstanceState.getInt(STATE_HOME_PAGE, HomeNavigationController.PAGE_BOOKSHELF)
            );
        }
        long autoOpenBookId = getIntent().getLongExtra(EXTRA_AUTO_OPEN_BOOK_ID, -1L);
        if (savedInstanceState == null && autoOpenBookId > 0) {
            performAutoOpenFastPath(autoOpenBookId);
        } else {
            showBookshelfLoadingState();
            if (savedInstanceState == null && settingsStore.isAutoOpenLastBook()) {
                maybeAutoOpenLastBook();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAddEntryVisibility();
        if (homeNavigationController != null) {
            homeNavigationController.refreshFromSettings();
        }
        if (!autoOpenConsumed) {
            refreshBooks();
        }
        refreshCurrentHomePage(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (homeNavigationController != null) {
            outState.putInt(STATE_HOME_PAGE, homeNavigationController.getCurrentPage());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        dismissBookActionsPopup();
        if (homeSettingsController != null) {
            homeSettingsController.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        bookshelfDestroyed = true;
        if (clearProgressPrefetchStatusRunnable != null) {
            mainHandler.removeCallbacks(clearProgressPrefetchStatusRunnable);
            clearProgressPrefetchStatusRunnable = null;
        }
        if (homeSettingsController != null) {
            homeSettingsController.onDestroy();
        }
        super.onDestroy();
        executor.shutdownNow();
        progressPrefetchExecutor.shutdownNow();
    }

    private boolean isBookshelfActive() {
        return !bookshelfDestroyed && !isFinishing() && !isDestroyed();
    }

    private void runOnBookshelfUiThread(Runnable action) {
        if (action == null || !isBookshelfActive()) {
            return;
        }
        runOnUiThread(() -> {
            if (!isBookshelfActive()) {
                return;
            }
            try {
                action.run();
            } catch (RuntimeException error) {
                Log.w(TAG, "Bookshelf UI task failed after lifecycle change", error);
            }
        });
    }

    private boolean safeExecute(Runnable action, String label) {
        return safeExecute(executor, action, label);
    }

    private boolean safeExecuteProgressPrefetch(Runnable action, String label) {
        return safeExecute(progressPrefetchExecutor, action, label);
    }

    private boolean safeExecute(ExecutorService targetExecutor, Runnable action, String label) {
        if (action == null || targetExecutor == null || bookshelfDestroyed || targetExecutor.isShutdown()) {
            return false;
        }
        try {
            targetExecutor.execute(() -> {
                try {
                    if (!bookshelfDestroyed) {
                        action.run();
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Bookshelf background task failed: " + safeTaskLabel(label), error);
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            Log.d(TAG, "Bookshelf background task rejected after shutdown: " + safeTaskLabel(label), error);
            return false;
        }
    }

    private String safeTaskLabel(String label) {
        return label == null || label.isBlank() ? "unnamed" : label;
    }

    @Override
    public void onBackPressed() {
        if (bookshelfManagementMode) {
            setBookshelfManagementMode(false);
            return;
        }
        if (homeNavigationController != null && homeNavigationController.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (homeNavigationController != null && homeNavigationController.handleTouchEvent(event)) {
            if (homeNavigationController.consumePendingChildTouchCancel()) {
                MotionEvent cancelEvent = MotionEvent.obtain(event);
                cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancelEvent);
                cancelEvent.recycle();
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (homeStatsPanelController != null
                && homeStatsPanelController.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == SettingsScreenController.REQUEST_PICK_BOOK) {
            if (homeSettingsController != null && data.getData() != null) {
                homeSettingsController.onBookPicked(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_PICK_BOOK) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) {
                importBooks(uris);
            }
        } else if (requestCode == REQUEST_EXPORT_BOOKS_DIRECTORY && data.getData() != null) {
            exportPendingBooks(data.getData());
        } else if (requestCode == REQUEST_PICK_COVER && pendingCoverBookId > 0) {
            attachCover(pendingCoverBookId, data.getData());
        }
    }

    // ==================== View Binding ====================

    private void bindViews() {
        sectionTitle = findViewById(R.id.text_section_title);
        emptyLayout = findViewById(R.id.layout_empty);
        loadingLayout = findViewById(R.id.layout_loading);
        searchInput = findViewById(R.id.input_search);
        gridBooks = findViewById(R.id.grid_books);
        listBooks = findViewById(R.id.list_books);
        loadingText = findViewById(R.id.text_loading);
        statsText = findViewById(R.id.text_stats);
        emptyTitle = findViewById(R.id.text_empty_title);
        emptyHint = findViewById(R.id.text_empty_hint);
        headerActionButton = findViewById(R.id.button_header_action);
        headerManageButton = findViewById(R.id.button_header_manage);
        buttonModeCard = findViewById(R.id.button_mode_card);
        buttonModeList = findViewById(R.id.button_mode_list);
        emptyActionButton = findViewById(R.id.button_empty_action);
        containerSearch = findViewById(R.id.container_search);
        iconSearch = findViewById(R.id.icon_search);
        bookshelfFiltersLayout = findViewById(R.id.layout_bookshelf_filters);
        bookshelfBatchActionsLayout = findViewById(R.id.layout_bookshelf_batch_actions);
        filterTagButton = findViewById(R.id.button_filter_tag);
        filterSeriesButton = findViewById(R.id.button_filter_series);
        filterStatusButton = findViewById(R.id.button_filter_status);
        filterClearButton = findViewById(R.id.button_filter_clear);
        batchClassifyButton = findViewById(R.id.button_batch_classify);
        batchExportButton = findViewById(R.id.button_batch_export);
        batchDeleteButton = findViewById(R.id.button_batch_delete);
        selectionCountText = findViewById(R.id.text_bookshelf_selection_count);
    }

    // ==================== Adapters ====================

    private void setupAdapters() {
        listFooterView = getLayoutInflater().inflate(R.layout.item_book_footer, listBooks, false);
        listFooterView.findViewById(R.id.button_footer_add_book).setOnClickListener(v -> openPicker());
        listBooks.addFooterView(listFooterView, null, true);
        listAdapter = new BookListAdapter(this);
        gridAdapter = new BookGridAdapter(this);
        listBooks.setAdapter(listAdapter);
        gridBooks.setAdapter(gridAdapter);
        updateAddEntryVisibility();
    }

    // ==================== Interactions ====================

    private void setupInteractions() {
        headerActionButton.setOnClickListener(v -> openPicker());
        headerManageButton.setOnClickListener(v -> setBookshelfManagementMode(!bookshelfManagementMode));

        buttonModeCard.setOnClickListener(v -> setBookshelfMode(VIEW_MODE_CARD));
        buttonModeList.setOnClickListener(v -> setBookshelfMode("list"));

        emptyActionButton.setOnClickListener(v -> {
            String query = currentQuery();
            if (query.isEmpty() && !hasActiveBookshelfFilters()) {
                openPicker();
            } else {
                searchInput.setText("");
                clearBookshelfFilters();
            }
        });

        gridBooks.setOnItemClickListener((parent, view, position, id) -> {
            if (gridAdapter.isAddPosition(position)) {
                if (!bookshelfManagementMode) openPicker();
                return;
            }
            BookRecord book = gridAdapter.getItem(position);
            if (book != null) {
                if (bookshelfManagementMode) toggleBookSelection(book.id);
                else openBook(book.id, view);
            }
        });
        gridBooks.setOnItemLongClickListener((parent, view, position, id) -> {
            if (gridAdapter.isAddPosition(position)) {
                openPicker();
                return true;
            }
            BookRecord book = gridAdapter.getItem(position);
            if (book != null) {
                if (bookshelfManagementMode) toggleBookSelection(book.id);
                else showBookActions(book, view);
                return true;
            }
            return false;
        });

        listBooks.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= listAdapter.getCount()) {
                openPicker();
                return;
            }
            BookRecord book = listAdapter.getItem(position);
            if (bookshelfManagementMode) toggleBookSelection(book.id);
            else openBook(book.id, view);
        });
        listBooks.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= listAdapter.getCount()) {
                return true;
            }
            BookRecord book = listAdapter.getItem(position);
            if (bookshelfManagementMode) toggleBookSelection(book.id);
            else showBookActions(book, view);
            return true;
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        View.OnClickListener focusSearch = v -> {
            searchInput.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        };
        if (containerSearch != null) containerSearch.setOnClickListener(focusSearch);
        if (iconSearch != null) iconSearch.setOnClickListener(focusSearch);

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                }
                searchInput.clearFocus();
                return true;
            }
            return false;
        });

        filterTagButton.setOnClickListener(v -> showTagFilterDialog());
        filterSeriesButton.setOnClickListener(v -> showSeriesFilterDialog());
        filterStatusButton.setOnClickListener(v -> showStatusFilterDialog());
        filterClearButton.setOnClickListener(v -> clearBookshelfFilters());
        batchClassifyButton.setOnClickListener(v -> showBatchClassificationActions());
        batchExportButton.setOnClickListener(v -> startBatchExport());
        batchDeleteButton.setOnClickListener(v -> confirmBatchDelete());

    }

    // ==================== Home Controllers ====================

    private void setupHomeControllers() {
        homeStatsPanelController = new HomeStatsPanelController(this, databaseHelper, settingsStore, executor);
        homeBookmarksPanelController = new HomeBookmarksPanelController(this, databaseHelper, executor);
        homeSettingsController = new SettingsScreenController(this, new SettingsScreenController.Host() {
            @Override
            public void openBookPicker(Intent intent, int requestCode) {
                startActivityForResult(intent, requestCode);
            }

            @Override
            public void openReader(long bookId) {
                refreshBooks();
                Intent intent = new Intent(BookshelfActivity.this, ReaderActivity.class);
                intent.putExtra("book_id", bookId);
                startActivity(intent);
            }

            @Override
            public void onSettingsSaved() {
                handleEmbeddedSettingsSaved();
            }

            @Override
            public void onLibraryDataRestored() {
                refreshBooks();
                refreshCurrentHomePage(false);
            }

            @Override
            public void onThemeChanged() {
                recreate();
            }
        });
        homeNavigationController = new HomeNavigationController(this, settingsStore, new HomeNavigationController.Callback() {
            @Override
            public boolean isReadingTimeTrackingEnabled() {
                return settingsStore.isReadingTimeTrackingEnabled();
            }

            @Override
            public void onHomePageSelected(int page, boolean syncFirst) {
                refreshCurrentHomePage(syncFirst);
            }
        });
    }

    private void handleEmbeddedSettingsSaved() {
        updateAddEntryVisibility();
        if (homeNavigationController != null) {
            homeNavigationController.refreshFromSettings();
        }
        if (homeNavigationController == null
                || homeNavigationController.getCurrentPage() != HomeNavigationController.PAGE_SETTINGS) {
            refreshCurrentHomePage(false);
        }
    }

    private void refreshCurrentHomePage(boolean syncFirst) {
        if (homeNavigationController == null) {
            return;
        }
        int currentPage = homeNavigationController.getCurrentPage();
        if (homeStatsPanelController != null) {
            homeStatsPanelController.refreshIfVisible(currentPage, syncFirst);
        }
        if (homeBookmarksPanelController != null) {
            homeBookmarksPanelController.refreshIfVisible(currentPage);
        }
        if (currentPage == HomeNavigationController.PAGE_SETTINGS) {
            if (homeSettingsController != null) {
                homeSettingsController.bindCurrentValues();
                homeSettingsController.refreshReadingStatsSummary(syncFirst);
            }
        }
    }

    // ==================== Bookshelf ====================

    private void setBookshelfMode(String mode) {
        settingsStore.setBookshelfViewMode(mode);
        applyBookshelfMode();
    }

    private void applyBookshelfMode() {
        boolean usingCardMode = isCardMode();
        AppUiUtils.styleSelectionButton(this, buttonModeCard, usingCardMode);
        AppUiUtils.styleSelectionButton(this, buttonModeList, !usingCardMode);
        if (!booksLoaded) {
            gridBooks.setVisibility(View.GONE);
            listBooks.setVisibility(View.GONE);
            return;
        }
        if (shouldShowBookshelfEmptyState(currentQuery())) {
            return;
        }
        gridBooks.setVisibility(usingCardMode ? View.VISIBLE : View.GONE);
        listBooks.setVisibility(usingCardMode ? View.GONE : View.VISIBLE);
    }

    private void refreshBooks() {
        refreshBooks(true);
    }

    private void refreshBooks(boolean prefetchAfterLoad) {
        booksLoading = true;
        if (!booksLoaded) {
            showBookshelfLoadingState();
        }
        safeExecute(() -> {
            try {
                List<BookRecord> books = databaseHelper.getBooks();
                runOnBookshelfUiThread(() -> {
                    booksLoading = false;
                    booksLoaded = true;
                    allBooks.clear();
                    allBooks.addAll(books);
                    applyFilter(currentQuery());
                    if (prefetchAfterLoad) {
                        scheduleBookshelfProgressPrefetch(books);
                    }
                });
            } catch (Exception error) {
                runOnBookshelfUiThread(() -> {
                    booksLoading = false;
                    booksLoaded = true;
                    applyFilter(currentQuery());
                    showToast("加载书架失败: " + readableError(error));
                });
            }
        }, "refresh books");
    }

    private void scheduleBookshelfProgressPrefetch(List<BookRecord> books) {
        if (books == null
                || books.isEmpty()
                || !settingsStore.isWebDavEnabled()
                || progressSyncCoordinator == null) {
            return;
        }
        int configuredLimit = settingsStore.getWebDavBookshelfProgressPrefetchLimit();
        if (configuredLimit <= 0) {
            return;
        }
        int limit = Math.min(configuredLimit, books.size());
        List<BookRecord> candidates = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            candidates.add(snapshotBookForProgressPrefetch(books.get(i)));
        }
        safeExecuteProgressPrefetch(() -> {
            boolean changed = false;
            boolean failed = false;
            runOnBookshelfUiThread(() -> startBookshelfProgressPrefetch(candidates.size()));
            for (int i = 0; i < candidates.size(); i++) {
                if (Thread.currentThread().isInterrupted() || bookshelfDestroyed) {
                    return;
                }
                int current = i + 1;
                BookRecord book = candidates.get(i);
                runOnBookshelfUiThread(() -> updateBookshelfProgressPrefetchCurrent(current));
                try {
                    WebDavProgressSyncCoordinator.SyncResult result =
                            progressSyncCoordinator.syncBookProgressIfNeeded(book);
                    changed = changed || result.remoteApplied;
                    if (result.remoteApplied) {
                        BookRecord latestBook = databaseHelper.getBook(book.id);
                        if (latestBook != null) {
                            runOnBookshelfUiThread(() -> applyRemoteProgressToBookItem(latestBook));
                        }
                    }
                } catch (Exception error) {
                    failed = true;
                    Log.d(TAG, "WebDAV progress prefetch skipped for book " + book.id, error);
                }
            }
            if (!Thread.currentThread().isInterrupted()) {
                boolean refreshCards = changed;
                boolean showFailureHint = failed;
                runOnBookshelfUiThread(() -> {
                    finishBookshelfProgressPrefetch(showFailureHint);
                    if (refreshCards) {
                        refreshBooks(false);
                    }
                });
            }
        }, "prefetch bookshelf WebDAV progress");
    }

    private void startBookshelfProgressPrefetch(int total) {
        if (clearProgressPrefetchStatusRunnable != null) {
            mainHandler.removeCallbacks(clearProgressPrefetchStatusRunnable);
            clearProgressPrefetchStatusRunnable = null;
        }
        progressPrefetchRunning = true;
        progressPrefetchCurrent = 0;
        progressPrefetchTotal = total;
        progressPrefetchFailed = false;
        updateBookshelfStatsText();
    }

    private void updateBookshelfProgressPrefetchCurrent(int current) {
        progressPrefetchCurrent = current;
        updateBookshelfStatsText();
    }

    private void finishBookshelfProgressPrefetch(boolean failed) {
        progressPrefetchRunning = false;
        progressPrefetchFailed = failed;
        updateBookshelfStatsText();
        if (!failed) {
            return;
        }
        if (clearProgressPrefetchStatusRunnable != null) {
            mainHandler.removeCallbacks(clearProgressPrefetchStatusRunnable);
        }
        clearProgressPrefetchStatusRunnable = () -> {
            clearProgressPrefetchStatusRunnable = null;
            progressPrefetchFailed = false;
            updateBookshelfStatsText();
        };
        mainHandler.postDelayed(
                clearProgressPrefetchStatusRunnable,
                PROGRESS_PREFETCH_FAILURE_HINT_MS
        );
    }

    private BookRecord snapshotBookForProgressPrefetch(BookRecord source) {
        BookRecord snapshot = new BookRecord();
        snapshot.id = source.id;
        snapshot.title = source.title;
        snapshot.author = source.author;
        snapshot.localPath = source.localPath;
        snapshot.coverPath = source.coverPath;
        snapshot.bookType = source.bookType;
        snapshot.readingStatsKey = source.readingStatsKey;
        snapshot.progressIndex = source.progressIndex;
        snapshot.progressOffset = source.progressOffset;
        snapshot.lastReadAt = source.lastReadAt;
        snapshot.pinned = source.pinned;
        snapshot.currentChapterTitle = source.currentChapterTitle;
        snapshot.chapterCount = source.chapterCount;
        snapshot.createdAt = source.createdAt;
        snapshot.updatedAt = source.updatedAt;
        snapshot.copyExtendedFieldsFrom(source);
        return snapshot;
    }

    private void applyRemoteProgressToBookItem(BookRecord updatedBook) {
        if (updatedBook == null || updatedBook.id <= 0) {
            return;
        }
        boolean replaced = false;
        for (int i = 0; i < allBooks.size(); i++) {
            BookRecord book = allBooks.get(i);
            if (book != null && book.id == updatedBook.id) {
                allBooks.set(i, updatedBook);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            return;
        }
        applyFilter(currentQuery());
    }

    private void applyFilter(String query) {
        String normalized = normalizeQuery(query);
        List<BookRecord> filtered = new ArrayList<>();
        for (BookRecord book : allBooks) {
            if (BookshelfFilter.matches(
                    book, normalized, selectedTagFilter, selectedSeriesFilter, selectedStatusFilter)) {
                filtered.add(book);
            }
        }
        listAdapter.setItems(filtered);
        gridAdapter.setItems(filtered);
        Set<Long> existingIds = new HashSet<>();
        for (BookRecord book : allBooks) existingIds.add(book.id);
        selectedBookIds.removeIf(id -> !existingIds.contains(id));
        updateSelectionViews();
        updateAddEntryVisibility();
        updateBookshelfStatsText();
        updateEmptyState(query);
    }

    private void updateAddEntryVisibility() {
        if (settingsStore == null) {
            return;
        }
        boolean visible = settingsStore.isBookshelfAddEntryVisible() && !bookshelfManagementMode;
        if (gridAdapter != null) {
            gridAdapter.setShowAddEntry(visible);
        }
        if (listFooterView != null) {
            listFooterView.setVisibility(visible ? View.VISIBLE : View.GONE);
            listFooterView.setEnabled(visible);
        }
    }

    private void updateEmptyState(String query) {
        boolean showEmpty = shouldShowBookshelfEmptyState(query);
        emptyLayout.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (!booksLoaded) {
            emptyLayout.setVisibility(View.GONE);
            gridBooks.setVisibility(View.GONE);
            listBooks.setVisibility(View.GONE);
            return;
        }
        if (showEmpty) {
            gridBooks.setVisibility(View.GONE);
            listBooks.setVisibility(View.GONE);
            if (normalizeQuery(query).isEmpty() && !hasActiveBookshelfFilters()) {
                emptyTitle.setText(getString(R.string.empty_bookshelf));
                emptyHint.setText(getString(R.string.empty_bookshelf_hint));
                emptyActionButton.setText("添加书籍");
            } else {
                emptyTitle.setText("没有找到匹配的书籍");
                emptyHint.setText("调整搜索词或清除当前筛选。");
                emptyActionButton.setText("清除筛选");
            }
            return;
        }
        emptyLayout.setVisibility(View.GONE);
        applyBookshelfMode();
    }

    private boolean shouldShowBookshelfEmptyState(String query) {
        return booksLoaded && listAdapter.getCount() == 0;
    }

    private void updateBookshelfStatsText() {
        if (progressPrefetchFailed) {
            statsText.setText("云端进度同步失败，已展示本地进度");
            return;
        }
        if (progressPrefetchRunning) {
            statsText.setText(String.format(
                    Locale.SIMPLIFIED_CHINESE,
                    "正在同步云端阅读进度 %d/%d...",
                    progressPrefetchCurrent,
                    progressPrefetchTotal
            ));
            return;
        }
        if (booksLoading) {
            statsText.setText(booksLoaded ? "正在刷新书架..." : "正在加载书架...");
            return;
        }
        int visibleBookCount = listAdapter == null ? allBooks.size() : listAdapter.getCount();
        if (hasActiveBookshelfFilters()) {
            statsText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "筛选结果 %d 本，共 %d 本", visibleBookCount, allBooks.size()));
        } else {
            statsText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "共 %d 本书籍", visibleBookCount));
        }
    }

    private void setBookshelfManagementMode(boolean enabled) {
        bookshelfManagementMode = enabled;
        if (!enabled) selectedBookIds.clear();
        headerManageButton.setText(enabled ? "完成" : "管理");
        headerActionButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
        bookshelfFiltersLayout.setVisibility(enabled ? View.VISIBLE : View.GONE);
        bookshelfBatchActionsLayout.setVisibility(enabled ? View.VISIBLE : View.GONE);
        sectionTitle.setText(enabled ? "已选择 " + selectedBookIds.size() + " 本" : "书架");
        updateAddEntryVisibility();
        updateSelectionViews();
    }

    private void toggleBookSelection(long bookId) {
        if (!selectedBookIds.add(bookId)) selectedBookIds.remove(bookId);
        updateSelectionViews();
    }

    private void updateSelectionViews() {
        if (listAdapter != null) listAdapter.setSelectedBookIds(selectedBookIds);
        if (gridAdapter != null) gridAdapter.setSelectedBookIds(selectedBookIds);
        if (selectionCountText != null) selectionCountText.setText("已选 " + selectedBookIds.size() + " 本");
        if (bookshelfManagementMode && sectionTitle != null) {
            sectionTitle.setText("已选择 " + selectedBookIds.size() + " 本");
        }
        boolean hasSelection = !selectedBookIds.isEmpty();
        if (batchClassifyButton != null) batchClassifyButton.setEnabled(hasSelection);
        if (batchExportButton != null) batchExportButton.setEnabled(hasSelection);
        if (batchDeleteButton != null) batchDeleteButton.setEnabled(hasSelection);
    }

    private boolean hasActiveBookshelfFilters() {
        return !selectedTagFilter.isEmpty() || !selectedSeriesFilter.isEmpty() || !selectedStatusFilter.isEmpty();
    }

    private void clearBookshelfFilters() {
        selectedTagFilter = "";
        selectedSeriesFilter = "";
        selectedStatusFilter = "";
        updateFilterButtonLabels();
        applyFilter(currentQuery());
    }

    private void updateFilterButtonLabels() {
        filterTagButton.setText(selectedTagFilter.isEmpty() ? "标签 ▾" : "标签·" + selectedTagFilter);
        filterSeriesButton.setText(selectedSeriesFilter.isEmpty() ? "系列 ▾" : "系列·" + selectedSeriesFilter);
        filterStatusButton.setText(selectedStatusFilter.isEmpty() ? "状态 ▾" : statusLabel(selectedStatusFilter));
    }

    private void showTagFilterDialog() {
        Set<String> values = new java.util.TreeSet<>();
        for (BookRecord book : allBooks) if (book.tags != null) values.addAll(book.tags);
        showFilterDialog("按标签筛选", new ArrayList<>(values), selectedTagFilter, value -> selectedTagFilter = value);
    }

    private void showSeriesFilterDialog() {
        Set<String> values = new java.util.TreeSet<>();
        for (BookRecord book : allBooks) {
            String value = book.series == null ? "" : book.series.trim();
            if (!value.isEmpty()) values.add(value);
        }
        showFilterDialog("按系列筛选", new ArrayList<>(values), selectedSeriesFilter, value -> selectedSeriesFilter = value);
    }

    private void showStatusFilterDialog() {
        List<String> values = new ArrayList<>();
        values.add(BookRecord.STATUS_UNREAD);
        values.add(BookRecord.STATUS_READING);
        values.add(BookRecord.STATUS_FINISHED);
        String[] labels = new String[]{"全部状态", "未读", "阅读中", "已读完"};
        new AlertDialog.Builder(this)
                .setTitle("按阅读状态筛选")
                .setItems(labels, (dialog, which) -> {
                    selectedStatusFilter = which == 0 ? "" : values.get(which - 1);
                    updateFilterButtonLabels();
                    applyFilter(currentQuery());
                })
                .show();
    }

    private interface FilterSetter { void set(String value); }

    private void showFilterDialog(String title, List<String> values, String selected, FilterSetter setter) {
        List<String> options = new ArrayList<>();
        options.add("全部");
        options.addAll(values);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    setter.set(which == 0 ? "" : options.get(which));
                    updateFilterButtonLabels();
                    applyFilter(currentQuery());
                })
                .show();
    }

    private String statusLabel(String status) {
        if (BookRecord.STATUS_FINISHED.equals(status)) return "已读完";
        if (BookRecord.STATUS_READING.equals(status)) return "阅读中";
        return "未读";
    }

    // ==================== Book Actions ====================

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "application/epub+zip", "application/pdf", "application/octet-stream"
        });
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_PICK_BOOK);
    }

    private void openCoverPicker(long bookId) {
        pendingCoverBookId = bookId;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_COVER);
    }

    private void importBooks(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        showLoading("正在检查 " + uris.size() + " 本书籍...");
        safeExecute(() -> {
            List<PreparedBookImport> prepared = new ArrayList<>();
            int failCount = 0;
            String firstError = null;
            for (int index = 0; index < uris.size(); index++) {
                Uri uri = uris.get(index);
                if (Thread.currentThread().isInterrupted() || bookshelfDestroyed) {
                    cleanupPreparedImports(prepared);
                    return;
                }
                try {
                    prepared.add(new PreparedBookImport(
                            "incoming-" + index,
                            importService.prepareFromUri(uri)
                    ));
                } catch (Exception e) {
                    failCount++;
                    Log.w(TAG, "导入预检失败: " + uri, e);
                    if (firstError == null) {
                        firstError = readableError(e);
                    }
                }
            }
            List<BookRecord> existing = databaseHelper.backfillMissingContentHashes();
            List<BookDuplicateDetector.Candidate> existingCandidates = new ArrayList<>();
            for (BookRecord book : existing) {
                existingCandidates.add(new BookDuplicateDetector.Candidate(
                        "existing-" + book.id, book.title, book.author, book.contentSha256));
            }
            List<BookDuplicateDetector.Candidate> incomingCandidates = new ArrayList<>();
            for (PreparedBookImport item : prepared) {
                BookImportService.PreparedImport value = item.prepared;
                incomingCandidates.add(new BookDuplicateDetector.Candidate(
                        item.key, value.title, value.author, value.contentSha256));
            }
            Map<String, BookDuplicateDetector.MatchType> duplicates =
                    BookDuplicateDetector.detect(existingCandidates, incomingCandidates);
            int finalFailCount = failCount;
            String finalFirstError = firstError;
            runOnUiThread(() -> {
                if (!isBookshelfActive()) {
                    cleanupPreparedImports(prepared);
                    return;
                }
                handlePreparedImports(prepared, duplicates, finalFailCount, finalFirstError);
            });
        }, "prepare imported books");
    }

    private void handlePreparedImports(
            List<PreparedBookImport> prepared,
            Map<String, BookDuplicateDetector.MatchType> duplicates,
            int preparationFailures,
            String firstError
    ) {
        hideLoading();
        if (prepared.isEmpty()) {
            showToast(firstError == null ? "没有可导入的书籍" : "导入失败: " + firstError);
            return;
        }
        if (duplicates.isEmpty()) {
            continuePreparedImports(prepared, preparationFailures, 0, firstError);
            return;
        }
        int exact = 0;
        int suspected = 0;
        List<String> names = new ArrayList<>();
        for (PreparedBookImport item : prepared) {
            BookDuplicateDetector.MatchType type = duplicates.get(item.key);
            if (type == null) continue;
            if (type == BookDuplicateDetector.MatchType.EXACT_CONTENT) exact++;
            else suspected++;
            if (names.size() < 4) names.add(item.prepared.displayName);
        }
        StringBuilder message = new StringBuilder();
        message.append("发现完全相同内容 ").append(exact).append(" 本，书名作者相同 ")
                .append(suspected).append(" 本。\n\n");
        for (String name : names) message.append("• ").append(name).append('\n');
        if (duplicates.size() > names.size()) {
            message.append("另有 ").append(duplicates.size() - names.size()).append(" 本");
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发现重复书籍")
                .setMessage(message.toString().trim())
                .setNegativeButton("跳过重复", (ignored, which) -> {
                    List<PreparedBookImport> remaining = new ArrayList<>();
                    for (PreparedBookImport item : prepared) {
                        if (duplicates.containsKey(item.key)) item.prepared.deleteLocalCopy();
                        else remaining.add(item);
                    }
                    continuePreparedImports(
                            remaining, preparationFailures, duplicates.size(), firstError);
                })
                .setPositiveButton("仍然全部导入", (ignored, which) ->
                        continuePreparedImports(prepared, preparationFailures, 0, firstError))
                .create();
        dialog.setOnCancelListener(ignored -> cleanupPreparedImports(prepared));
        dialog.show();
    }

    private void continuePreparedImports(
            List<PreparedBookImport> prepared,
            int previousFailures,
            int skippedDuplicates,
            String previousError
    ) {
        if (prepared.isEmpty()) {
            refreshBooks();
            showToast(skippedDuplicates > 0 ? "已跳过 " + skippedDuplicates + " 本重复书籍" : "没有可导入的书籍");
            return;
        }
        showLoading("正在导入 " + prepared.size() + " 本书籍...");
        safeExecute(() -> {
            int success = 0;
            int failed = previousFailures;
            String firstError = previousError;
            List<Long> importedBookIds = new ArrayList<>();
            for (PreparedBookImport item : prepared) {
                try {
                    long bookId = databaseHelper.insertImportedBook(importService.parsePrepared(item.prepared, false));
                    importedBookIds.add(bookId);
                    success++;
                } catch (Exception error) {
                    failed++;
                    item.prepared.deleteLocalCopy();
                    if (firstError == null) firstError = readableError(error);
                    Log.w(TAG, "导入书籍失败: " + item.prepared.displayName, error);
                }
            }
            int finalSuccess = success;
            int finalFailed = failed;
            String finalError = firstError;
            runOnBookshelfUiThread(() -> {
                hideLoading();
                refreshBooks();
                StringBuilder summary = new StringBuilder("导入完成：成功 ").append(finalSuccess);
                if (skippedDuplicates > 0) summary.append("，跳过重复 ").append(skippedDuplicates);
                if (finalFailed > 0) summary.append("，失败 ").append(finalFailed);
                if (finalSuccess == 0 && finalFailed > 0 && finalError != null) {
                    showToast("导入失败: " + finalError);
                } else {
                    showToast(summary.toString());
                }
                for (Long bookId : importedBookIds) {
                    safeExecute(() -> {
                        try {
                            new BookSearchIndex(this, databaseHelper).build(bookId, () -> bookshelfDestroyed);
                        } catch (Exception error) {
                            Log.d(TAG, "Search index warmup skipped for book " + bookId, error);
                        }
                    }, "build imported book search index");
                }
            });
        }, "parse imported books");
    }

    private void cleanupPreparedImports(List<PreparedBookImport> prepared) {
        if (prepared == null) return;
        for (PreparedBookImport item : prepared) {
            if (item != null && item.prepared != null) item.prepared.deleteLocalCopy();
        }
    }

    private void openBook(long bookId) {
        openBook(bookId, null);
    }

    private void openBook(long bookId, View sourceView) {
        if (!isBookshelfActive()) {
            return;
        }
        launchReader(bookId, sourceView);
    }

    private void launchReader(long bookId, View sourceView) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("book_id", bookId);
        if (com.metahumanz.pacilread.ui.TransitionMotionModeHelper.isFluidMode(settingsStore)) {
            LaunchSourceTransition.attachBoundsOnly(intent, sourceView);
        }
        startActivity(intent);
    }

    private void showBookActions(BookRecord book, View sourceView) {
        dismissBookActionsPopup();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_app_dialog);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            panel.setElevation(dp(10));
        }

        TextView title = new TextView(this);
        title.setText(book.title == null || book.title.isBlank() ? "未命名书籍" : book.title);
        title.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_primary));
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        View divider = new View(this);
        divider.setBackgroundColor(ThemeModeHelper.resolveColor(this, R.color.app_border));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))
        );
        dividerParams.topMargin = dp(10);
        dividerParams.bottomMargin = dp(6);
        panel.addView(divider, dividerParams);

        List<String> itemList = new ArrayList<>();
        itemList.add("打开");
        itemList.add(book.pinned ? "取消置顶" : "置顶到顶部");
        itemList.add("分类");
        itemList.add("设置自定义封面");
        if (book.coverPath != null && !book.coverPath.isBlank()) {
            itemList.add("移除封面");
        }
        itemList.add("删除");
        for (String item : itemList) {
            panel.addView(createBookActionRow(item, "删除".equals(item), () -> {
                dismissBookActionsPopup();
                if ("打开".equals(item)) {
                    openBook(book.id, sourceView);
                } else if ("分类".equals(item)) {
                    showBookClassificationDialog(book);
                } else if ("设置自定义封面".equals(item)) {
                    openCoverPicker(book.id);
                } else if ("移除封面".equals(item)) {
                    removeCover(book);
                } else if ("删除".equals(item)) {
                    confirmDelete(book);
                } else {
                    safeExecute(() -> {
                        databaseHelper.setPinned(book.id, !book.pinned);
                        runOnBookshelfUiThread(this::refreshBooks);
                    }, "toggle book pin");
                }
            }));
        }

        int popupWidth = Math.min(dp(300), Math.max(dp(232), getResources().getDisplayMetrics().widthPixels - dp(32)));
        bookActionsPopup = new PopupWindow(panel, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        bookActionsPopup.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        bookActionsPopup.setOutsideTouchable(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            bookActionsPopup.setElevation(dp(10));
        }
        if (sourceView != null) {
            bookActionsPopup.showAsDropDown(sourceView, 0, -sourceView.getHeight(), Gravity.END);
        } else {
            View root = findViewById(android.R.id.content);
            bookActionsPopup.showAtLocation(root, Gravity.CENTER, 0, 0);
        }
    }

    private TextView createBookActionRow(String text, boolean danger, Runnable action) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(44));
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setTextSize(14f);
        row.setTextColor(ThemeModeHelper.resolveColor(
                this,
                danger ? R.color.app_danger : R.color.app_text_primary
        ));
        row.setBackgroundResource(R.drawable.bg_app_soft_button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(6);
        row.setLayoutParams(params);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private void showBookClassificationDialog(BookRecord book) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), 0);

        EditText tagsInput = classificationInput("标签，用逗号分隔");
        tagsInput.setText(book.tags == null ? "" : String.join(", ", book.tags));
        content.addView(tagsInput);

        EditText seriesInput = classificationInput("系列名称，可留空");
        seriesInput.setText(book.series == null ? "" : book.series);
        LinearLayout.LayoutParams seriesParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seriesParams.topMargin = dp(10);
        content.addView(seriesInput, seriesParams);

        TextView statusTitle = new TextView(this);
        statusTitle.setText("阅读状态");
        statusTitle.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_primary));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(14);
        content.addView(statusTitle, titleParams);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] statusHolder = new String[]{BookRecord.normalizeReadingStatus(book.readingStatus, false)};
        Button unread = classificationStatusButton("未读");
        Button reading = classificationStatusButton("阅读中");
        Button finished = classificationStatusButton("已读完");
        statusRow.addView(unread, weightedButtonParams(0));
        statusRow.addView(reading, weightedButtonParams(dp(6)));
        statusRow.addView(finished, weightedButtonParams(dp(6)));
        Runnable refreshStatus = () -> {
            AppUiUtils.styleSelectionButton(this, unread, BookRecord.STATUS_UNREAD.equals(statusHolder[0]));
            AppUiUtils.styleSelectionButton(this, reading, BookRecord.STATUS_READING.equals(statusHolder[0]));
            AppUiUtils.styleSelectionButton(this, finished, BookRecord.STATUS_FINISHED.equals(statusHolder[0]));
        };
        unread.setOnClickListener(v -> { statusHolder[0] = BookRecord.STATUS_UNREAD; refreshStatus.run(); });
        reading.setOnClickListener(v -> { statusHolder[0] = BookRecord.STATUS_READING; refreshStatus.run(); });
        finished.setOnClickListener(v -> { statusHolder[0] = BookRecord.STATUS_FINISHED; refreshStatus.run(); });
        refreshStatus.run();
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        content.addView(statusRow, rowParams);

        new AlertDialog.Builder(this)
                .setTitle("分类")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> safeExecute(() -> {
                    databaseHelper.updateBookClassification(
                            book.id,
                            parseTags(tagsInput.getText() == null ? "" : tagsInput.getText().toString()),
                            seriesInput.getText() == null ? "" : seriesInput.getText().toString(),
                            statusHolder[0]
                    );
                    runOnBookshelfUiThread(this::refreshBooks);
                }, "update book classification"))
                .show();
    }

    private void showBatchClassificationActions() {
        if (selectedBookIds.isEmpty()) return;
        String[] actions = new String[]{"添加标签", "移除标签", "设置系列", "清除系列", "设置阅读状态"};
        new AlertDialog.Builder(this)
                .setTitle("批量分类")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showBatchTagInput(true);
                    else if (which == 1) showBatchTagInput(false);
                    else if (which == 2) showBatchSeriesInput();
                    else if (which == 3) applyBatchSeries("");
                    else showBatchStatusDialog();
                })
                .show();
    }

    private void showBatchTagInput(boolean add) {
        EditText input = classificationInput("标签，用逗号分隔");
        int padding = dp(20);
        input.setPadding(padding, dp(12), padding, dp(12));
        new AlertDialog.Builder(this)
                .setTitle(add ? "添加标签" : "移除标签")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    List<String> tags = parseTags(input.getText() == null ? "" : input.getText().toString());
                    Set<Long> ids = new HashSet<>(selectedBookIds);
                    safeExecute(() -> {
                        if (add) databaseHelper.addTagsToBooks(ids, tags);
                        else databaseHelper.removeTagsFromBooks(ids, tags);
                        runOnBookshelfUiThread(this::refreshBooks);
                    }, add ? "batch add tags" : "batch remove tags");
                })
                .show();
    }

    private void showBatchSeriesInput() {
        EditText input = classificationInput("系列名称");
        int padding = dp(20);
        input.setPadding(padding, dp(12), padding, dp(12));
        new AlertDialog.Builder(this)
                .setTitle("设置系列")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> applyBatchSeries(
                        input.getText() == null ? "" : input.getText().toString()))
                .show();
    }

    private void applyBatchSeries(String series) {
        Set<Long> ids = new HashSet<>(selectedBookIds);
        safeExecute(() -> {
            databaseHelper.setSeriesForBooks(ids, series);
            runOnBookshelfUiThread(this::refreshBooks);
        }, "batch set series");
    }

    private void showBatchStatusDialog() {
        String[] labels = new String[]{"未读", "阅读中", "已读完"};
        String[] values = new String[]{BookRecord.STATUS_UNREAD, BookRecord.STATUS_READING, BookRecord.STATUS_FINISHED};
        new AlertDialog.Builder(this)
                .setTitle("设置阅读状态")
                .setItems(labels, (dialog, which) -> {
                    Set<Long> ids = new HashSet<>(selectedBookIds);
                    safeExecute(() -> {
                        databaseHelper.setReadingStatusForBooks(ids, values[which]);
                        runOnBookshelfUiThread(this::refreshBooks);
                    }, "batch set reading status");
                })
                .show();
    }

    private EditText classificationInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setBackgroundResource(R.drawable.bg_app_input);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        return input;
    }

    private Button classificationStatusButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(dp(40));
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams(int startMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), 1f);
        params.setMarginStart(startMargin);
        return params;
    }

    private List<String> parseTags(String value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        for (String part : value.split("[,，]")) {
            String tag = part.trim();
            if (!tag.isEmpty() && !result.contains(tag)) result.add(tag);
        }
        return result;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void dismissBookActionsPopup() {
        if (bookActionsPopup != null && bookActionsPopup.isShowing()) {
            bookActionsPopup.dismiss();
        }
        bookActionsPopup = null;
    }

    private void attachCover(long bookId, Uri uri) {
        showLoading("正在保存封面...");
        safeExecute(() -> {
            try {
                BookRecord currentBook = databaseHelper.getBook(bookId);
                File coverFile = CoverImageStore.saveCompressedCover(this, uri, "cover_" + bookId);
                if (currentBook != null && currentBook.coverPath != null && !currentBook.coverPath.isBlank()) {
                    FileAssetHelper.deleteIfExists(currentBook.coverPath);
                }
                databaseHelper.setCoverPath(bookId, coverFile.getAbsolutePath());
                runOnBookshelfUiThread(() -> {
                    pendingCoverBookId = -1L;
                    hideLoading();
                    refreshBooks();
                    showToast("封面已更新");
                });
            } catch (Exception error) {
                runOnBookshelfUiThread(() -> {
                    pendingCoverBookId = -1L;
                    hideLoading();
                    showToast("保存封面失败: " + error.getMessage());
                });
            }
        }, "attach book cover");
    }

    private void removeCover(BookRecord book) {
        safeExecute(() -> {
            FileAssetHelper.deleteIfExists(book.coverPath);
            databaseHelper.setCoverPath(book.id, null);
            runOnBookshelfUiThread(() -> {
                refreshBooks();
                showToast("已移除封面");
            });
        }, "remove book cover");
    }

    private void confirmDelete(BookRecord book) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除书籍")
                .setMessage("确定要删除《" + book.title + "》吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (ignoredDialog, which) -> safeExecute(() -> {
                    try {
                        new SnapshotManager(this, databaseHelper, settingsStore)
                                .createSnapshot("delete-book");
                    } catch (Exception error) {
                        Log.w(TAG, "Create snapshot before delete failed", error);
                    }
                    databaseHelper.deleteBook(book.id);
                    BookSearchIndex.delete(this, book.id);
                    runOnBookshelfUiThread(() -> {
                        refreshBooks();
                        showToast("已删除");
                    });
                }, "delete book"))
                .create();
        dialog.setOnShowListener(unused -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_app_dialog);
            }
        });
        dialog.show();
    }

    private List<BookRecord> selectedBooksSnapshot() {
        List<BookRecord> result = new ArrayList<>();
        for (BookRecord book : allBooks) {
            if (selectedBookIds.contains(book.id)) result.add(book);
        }
        return result;
    }

    private void confirmBatchDelete() {
        List<BookRecord> selected = selectedBooksSnapshot();
        if (selected.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("删除 " + selected.size() + " 本书籍")
                .setMessage("将删除所选书籍、章节、书签和本地文件，此操作不可撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    Set<Long> ids = new HashSet<>(selectedBookIds);
                    showLoading("正在删除 " + ids.size() + " 本书籍...");
                    safeExecute(() -> {
                        try {
                            new SnapshotManager(this, databaseHelper, settingsStore)
                                    .createSnapshot("delete-books");
                        } catch (Exception error) {
                            Log.w(TAG, "Create snapshot before batch delete failed", error);
                        }
                        databaseHelper.deleteBooks(ids);
                        for (Long id : ids) BookSearchIndex.delete(this, id);
                        runOnBookshelfUiThread(() -> {
                            selectedBookIds.clear();
                            setBookshelfManagementMode(false);
                            hideLoading();
                            refreshBooks();
                            showToast("已删除 " + ids.size() + " 本书籍");
                        });
                    }, "batch delete books");
                })
                .show();
    }

    private void startBatchExport() {
        pendingExportBooks = selectedBooksSnapshot();
        if (pendingExportBooks.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_EXPORT_BOOKS_DIRECTORY);
    }

    private void exportPendingBooks(Uri directoryUri) {
        List<BookRecord> books = new ArrayList<>(pendingExportBooks);
        pendingExportBooks.clear();
        if (books.isEmpty()) return;
        showLoading("正在导出 " + books.size() + " 本书籍...");
        safeExecute(() -> {
            int success = 0;
            int failed = 0;
            DocumentFile directory = DocumentFile.fromTreeUri(this, directoryUri);
            if (directory == null || !directory.canWrite()) {
                runOnBookshelfUiThread(() -> {
                    hideLoading();
                    showToast("无法写入所选目录");
                });
                return;
            }
            Set<String> usedNames = new HashSet<>();
            for (DocumentFile child : directory.listFiles()) {
                if (child.getName() != null) usedNames.add(child.getName());
            }
            ContentResolver resolver = getContentResolver();
            for (BookRecord book : books) {
                File source = book.localPath == null ? null : new File(book.localPath);
                if (source == null || !source.isFile()) {
                    failed++;
                    continue;
                }
                String fileName = BookExportNaming.uniqueFileName(book, usedNames);
                DocumentFile target = directory.createFile(mimeTypeForBook(book), fileName);
                if (target == null) {
                    failed++;
                    continue;
                }
                try (FileInputStream input = new FileInputStream(source);
                     OutputStream output = resolver.openOutputStream(target.getUri(), "w")) {
                    if (output == null) throw new java.io.IOException("无法创建导出文件");
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    success++;
                } catch (Exception error) {
                    failed++;
                    target.delete();
                    Log.w(TAG, "Export book failed: " + book.id, error);
                }
            }
            int finalSuccess = success;
            int finalFailed = failed;
            runOnBookshelfUiThread(() -> {
                hideLoading();
                if (finalFailed == 0) showToast("已导出 " + finalSuccess + " 本书籍");
                else showToast("导出完成：成功 " + finalSuccess + "，失败 " + finalFailed);
            });
        }, "batch export books");
    }

    private String mimeTypeForBook(BookRecord book) {
        if (book != null && "epub".equalsIgnoreCase(book.bookType)) return "application/epub+zip";
        if (book != null && "pdf".equalsIgnoreCase(book.bookType)) return "application/pdf";
        return "text/plain";
    }

    private void maybeAutoOpenLastBook() {
        safeExecute(() -> {
            try {
                long bookId = databaseHelper.getMostRecentBookId();
                if (bookId > 0) {
                    runOnBookshelfUiThread(() -> openBook(bookId));
                }
            } catch (Exception ignored) {}
        }, "auto open last book");
    }

    private void performAutoOpenFastPath(long bookId) {
        autoOpenConsumed = true;
        safeExecute(() -> {
            try {
                BookRecord book = databaseHelper.getBook(bookId);
                if (book == null) {
                    runOnBookshelfUiThread(() -> {
                        autoOpenConsumed = false;
                        showBookshelfLoadingState();
                        refreshBooks();
                    });
                    return;
                }
                runOnBookshelfUiThread(() -> {
                    List<BookRecord> single = new ArrayList<>();
                    single.add(book);
                    listAdapter.setItems(single);
                    gridAdapter.setItems(single);
                    updateAddEntryVisibility();
                    updateBookshelfStatsText();
                    booksLoaded = true;
                    booksLoading = false;
                    applyBookshelfMode();

                    View container = isCardMode() ? (View) gridBooks : (View) listBooks;
                    container.post(() -> {
                        if (!isBookshelfActive()) {
                            return;
                        }
                        View sourceView = null;
                        if (isCardMode() && gridBooks.getChildCount() > 0) {
                            sourceView = gridBooks.getChildAt(0);
                        } else if (!isCardMode() && listBooks.getChildCount() > 0) {
                            sourceView = listBooks.getChildAt(0);
                        }
                        openBook(book.id, sourceView);
                        autoOpenConsumed = false;

                        // 在阅读器背后异步加载完整书架
                        safeExecute(() -> {
                            try {
                                List<BookRecord> books = databaseHelper.getBooks();
                                runOnBookshelfUiThread(() -> {
                                    booksLoading = false;
                                    booksLoaded = true;
                                    allBooks.clear();
                                    allBooks.addAll(books);
                                    applyFilter(currentQuery());
                                    scrollToBook(bookId);
                                });
                            } catch (Exception error) {
                                runOnBookshelfUiThread(() -> {
                                    booksLoading = false;
                                    applyFilter(currentQuery());
                                });
                            }
                        }, "load full bookshelf behind reader");
                    });
                });
            } catch (Exception e) {
                runOnBookshelfUiThread(() -> {
                    autoOpenConsumed = false;
                    showBookshelfLoadingState();
                    refreshBooks();
                });
            }
        }, "auto open fast path");
    }

    private void scrollToBook(long bookId) {
        if (isCardMode()) {
            for (int i = 0; i < gridAdapter.getCount(); i++) {
                BookRecord item = gridAdapter.getItem(i);
                if (item != null && item.id == bookId) {
                    gridBooks.setSelection(i);
                    return;
                }
            }
        } else {
            for (int i = 0; i < listAdapter.getCount(); i++) {
                BookRecord item = listAdapter.getItem(i);
                if (item != null && item.id == bookId) {
                    listBooks.setSelection(i);
                    return;
                }
            }
        }
    }

    // ==================== UI Helpers ====================

    private void showBookshelfLoadingState() {
        if (!booksLoaded) {
            emptyLayout.setVisibility(View.GONE);
            gridBooks.setVisibility(View.GONE);
            listBooks.setVisibility(View.GONE);
            applyBookshelfMode();
        }
        updateBookshelfStatsText();
    }

    private void showLoading(String message) {
        loadingText.setText(message);
        loadingLayout.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingLayout.setVisibility(View.GONE);
    }

    private void showToast(String message) {
        AppUiUtils.showToast(this, message);
    }

    private boolean isCardMode() {
        return VIEW_MODE_CARD.equals(settingsStore.getBookshelfViewMode());
    }

    private String currentQuery() {
        return searchInput.getText() == null ? "" : searchInput.getText().toString();
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    private String readableError(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "未知错误";
        }
        return error.getMessage();
    }

    private static final class PreparedBookImport {
        final String key;
        final BookImportService.PreparedImport prepared;

        PreparedBookImport(String key, BookImportService.PreparedImport prepared) {
            this.key = key;
            this.prepared = prepared;
        }
    }
}
