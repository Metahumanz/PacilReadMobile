package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
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

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.sync.WebDavProgressSyncCoordinator;
import com.metahumanz.pacilread.theme.ThemedActivity;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.ui.LaunchSourceTransition;
import com.metahumanz.pacilread.util.CoverImageStore;
import com.metahumanz.pacilread.util.FileAssetHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BookshelfActivity extends ThemedActivity {
    private static final String TAG = "BookshelfActivity";
    private static final int REQUEST_PICK_BOOK = 1001;
    private static final int REQUEST_PICK_COVER = 1002;
    private static final String VIEW_MODE_CARD = "card";
    private static final String STATE_HOME_PAGE = "state_home_page";
    public static final String EXTRA_AUTO_OPEN_BOOK_ID =
            "com.metahumanz.pacilread.EXTRA_AUTO_OPEN_BOOK_ID";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService progressPrefetchExecutor = Executors.newSingleThreadExecutor();
    private final List<BookRecord> allBooks = new ArrayList<>();

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
    private Button buttonModeCard;
    private Button buttonModeList;
    private Button emptyActionButton;
    private View containerSearch;
    private View iconSearch;
    private long pendingCoverBookId = -1L;
    private boolean booksLoaded = false;
    private boolean booksLoading = false;
    private boolean autoOpenConsumed;
    private PopupWindow bookActionsPopup;

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
        if (homeSettingsController != null) {
            homeSettingsController.onDestroy();
        }
        super.onDestroy();
        executor.shutdownNow();
        progressPrefetchExecutor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
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
        buttonModeCard = findViewById(R.id.button_mode_card);
        buttonModeList = findViewById(R.id.button_mode_list);
        emptyActionButton = findViewById(R.id.button_empty_action);
        containerSearch = findViewById(R.id.container_search);
        iconSearch = findViewById(R.id.icon_search);
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

        buttonModeCard.setOnClickListener(v -> setBookshelfMode(VIEW_MODE_CARD));
        buttonModeList.setOnClickListener(v -> setBookshelfMode("list"));

        emptyActionButton.setOnClickListener(v -> {
            String query = currentQuery();
            if (query.isEmpty()) {
                openPicker();
            } else {
                searchInput.setText("");
            }
        });

        gridBooks.setOnItemClickListener((parent, view, position, id) -> {
            if (gridAdapter.isAddPosition(position)) {
                openPicker();
                return;
            }
            BookRecord book = gridAdapter.getItem(position);
            if (book != null) {
                openBook(book.id, view);
            }
        });
        gridBooks.setOnItemLongClickListener((parent, view, position, id) -> {
            if (gridAdapter.isAddPosition(position)) {
                openPicker();
                return true;
            }
            BookRecord book = gridAdapter.getItem(position);
            if (book != null) {
                showBookActions(book, view);
                return true;
            }
            return false;
        });

        listBooks.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= listAdapter.getCount()) {
                openPicker();
                return;
            }
            openBook(listAdapter.getItem(position).id, view);
        });
        listBooks.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= listAdapter.getCount()) {
                return true;
            }
            showBookActions(listAdapter.getItem(position), view);
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
        executor.execute(() -> {
            try {
                List<BookRecord> books = databaseHelper.getBooks();
                runOnUiThread(() -> {
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
                runOnUiThread(() -> {
                    booksLoading = false;
                    booksLoaded = true;
                    applyFilter(currentQuery());
                    showToast("加载书架失败: " + readableError(error));
                });
            }
        });
    }

    private void scheduleBookshelfProgressPrefetch(List<BookRecord> books) {
        if (books == null
                || books.isEmpty()
                || !settingsStore.isWebDavEnabled()
                || progressSyncCoordinator == null) {
            return;
        }
        int limit = Math.min(WebDavProgressSyncCoordinator.BOOKSHELF_PREFETCH_LIMIT, books.size());
        List<BookRecord> candidates = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            candidates.add(snapshotBookForProgressPrefetch(books.get(i)));
        }
        progressPrefetchExecutor.execute(() -> {
            boolean changed = false;
            for (BookRecord book : candidates) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    WebDavProgressSyncCoordinator.SyncResult result =
                            progressSyncCoordinator.syncBookProgressIfNeeded(book);
                    changed = changed || result.remoteApplied;
                } catch (Exception error) {
                    Log.d(TAG, "WebDAV progress prefetch skipped for book " + book.id, error);
                }
            }
            if (changed && !Thread.currentThread().isInterrupted()) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        refreshBooks(false);
                    }
                });
            }
        });
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
        return snapshot;
    }

    private void applyFilter(String query) {
        String normalized = normalizeQuery(query);
        List<BookRecord> filtered = new ArrayList<>();
        if (normalized.isEmpty()) {
            filtered.addAll(allBooks);
        } else {
            for (BookRecord book : allBooks) {
                String title = book.title == null ? "" : book.title.toLowerCase(Locale.ROOT);
                String author = book.author == null ? "" : book.author.toLowerCase(Locale.ROOT);
                if (title.contains(normalized) || author.contains(normalized)) {
                    filtered.add(book);
                }
            }
        }
        listAdapter.setItems(filtered);
        gridAdapter.setItems(filtered);
        updateAddEntryVisibility();
        updateStats(filtered);
        updateEmptyState(query);
    }

    private void updateAddEntryVisibility() {
        if (settingsStore == null) {
            return;
        }
        boolean visible = settingsStore.isBookshelfAddEntryVisible();
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
            if (normalizeQuery(query).isEmpty()) {
                emptyTitle.setText(getString(R.string.empty_bookshelf));
                emptyHint.setText(getString(R.string.empty_bookshelf_hint));
                emptyActionButton.setText("添加书籍");
            } else {
                emptyTitle.setText("没有找到匹配的书籍");
                emptyHint.setText("换个关键词试试，或者直接添加一本新书。");
                emptyActionButton.setText("清除搜索");
            }
            return;
        }
        emptyLayout.setVisibility(View.GONE);
        applyBookshelfMode();
    }

    private boolean shouldShowBookshelfEmptyState(String query) {
        return booksLoaded && listAdapter.getCount() == 0;
    }

    private void updateStats(List<BookRecord> filtered) {
        if (!booksLoaded && booksLoading) {
            statsText.setText("正在加载书架...");
            return;
        }
        statsText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "共 %d 本书籍", filtered.size()));
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
        showLoading("正在导入 " + uris.size() + " 本书籍...");
        executor.execute(() -> {
            int successCount = 0, failCount = 0;
            String firstError = null;
            for (Uri uri : uris) {
                try {
                    databaseHelper.insertImportedBook(importService.importFromUri(uri, false));
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    Log.w(TAG, "导入书籍失败: " + uri, e);
                    if (firstError == null) {
                        firstError = readableError(e);
                    }
                }
            }
            final int sCount = successCount, fCount = failCount;
            final String errorMessage = firstError;
            runOnUiThread(() -> {
                hideLoading();
                refreshBooks();
                if (fCount > 0) {
                    if (sCount == 0 && errorMessage != null && !errorMessage.isBlank()) {
                        showToast("导入失败: " + errorMessage);
                    } else {
                        showToast("导入完成: 成功 " + sCount + "，失败 " + fCount);
                    }
                } else {
                    showToast("成功导入 " + sCount + " 本书籍");
                }
            });
        });
    }

    private void openBook(long bookId) {
        openBook(bookId, null);
    }

    private void openBook(long bookId, View sourceView) {
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
                } else if ("设置自定义封面".equals(item)) {
                    openCoverPicker(book.id);
                } else if ("移除封面".equals(item)) {
                    removeCover(book);
                } else if ("删除".equals(item)) {
                    confirmDelete(book);
                } else {
                    executor.execute(() -> {
                        databaseHelper.setPinned(book.id, !book.pinned);
                        runOnUiThread(this::refreshBooks);
                    });
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
        executor.execute(() -> {
            try {
                BookRecord currentBook = databaseHelper.getBook(bookId);
                File coverFile = CoverImageStore.saveCompressedCover(this, uri, "cover_" + bookId);
                if (currentBook != null && currentBook.coverPath != null && !currentBook.coverPath.isBlank()) {
                    FileAssetHelper.deleteIfExists(currentBook.coverPath);
                }
                databaseHelper.setCoverPath(bookId, coverFile.getAbsolutePath());
                runOnUiThread(() -> {
                    pendingCoverBookId = -1L;
                    hideLoading();
                    refreshBooks();
                    showToast("封面已更新");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    pendingCoverBookId = -1L;
                    hideLoading();
                    showToast("保存封面失败: " + error.getMessage());
                });
            }
        });
    }

    private void removeCover(BookRecord book) {
        executor.execute(() -> {
            FileAssetHelper.deleteIfExists(book.coverPath);
            databaseHelper.setCoverPath(book.id, null);
            runOnUiThread(() -> {
                refreshBooks();
                showToast("已移除封面");
            });
        });
    }

    private void confirmDelete(BookRecord book) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除书籍")
                .setMessage("确定要删除《" + book.title + "》吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (ignoredDialog, which) -> executor.execute(() -> {
                    databaseHelper.deleteBook(book.id);
                    runOnUiThread(() -> {
                        refreshBooks();
                        showToast("已删除");
                    });
                }))
                .create();
        dialog.setOnShowListener(unused -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_app_dialog);
            }
        });
        dialog.show();
    }

    private void maybeAutoOpenLastBook() {
        executor.execute(() -> {
            try {
                long bookId = databaseHelper.getMostRecentBookId();
                if (bookId > 0) {
                    runOnUiThread(() -> openBook(bookId));
                }
            } catch (Exception ignored) {}
        });
    }

    private void performAutoOpenFastPath(long bookId) {
        autoOpenConsumed = true;
        executor.execute(() -> {
            try {
                BookRecord book = databaseHelper.getBook(bookId);
                if (book == null) {
                    runOnUiThread(() -> {
                        autoOpenConsumed = false;
                        showBookshelfLoadingState();
                        refreshBooks();
                    });
                    return;
                }
                runOnUiThread(() -> {
                    List<BookRecord> single = new ArrayList<>();
                    single.add(book);
                    listAdapter.setItems(single);
                    gridAdapter.setItems(single);
                    updateAddEntryVisibility();
                    updateStats(single);
                    booksLoaded = true;
                    booksLoading = false;
                    applyBookshelfMode();

                    View container = isCardMode() ? (View) gridBooks : (View) listBooks;
                    container.post(() -> {
                        View sourceView = null;
                        if (isCardMode() && gridBooks.getChildCount() > 0) {
                            sourceView = gridBooks.getChildAt(0);
                        } else if (!isCardMode() && listBooks.getChildCount() > 0) {
                            sourceView = listBooks.getChildAt(0);
                        }
                        openBook(book.id, sourceView);
                        autoOpenConsumed = false;

                        // 在阅读器背后异步加载完整书架
                        executor.execute(() -> {
                            try {
                                List<BookRecord> books = databaseHelper.getBooks();
                                runOnUiThread(() -> {
                                    booksLoading = false;
                                    booksLoaded = true;
                                    allBooks.clear();
                                    allBooks.addAll(books);
                                    applyFilter(currentQuery());
                                    scrollToBook(bookId);
                                });
                            } catch (Exception error) {
                                runOnUiThread(() -> {
                                    booksLoading = false;
                                    applyFilter(currentQuery());
                                });
                            }
                        });
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    autoOpenConsumed = false;
                    showBookshelfLoadingState();
                    refreshBooks();
                });
            }
        });
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
        if (booksLoaded) {
            statsText.setText("正在刷新书架...");
            return;
        }
        emptyLayout.setVisibility(View.GONE);
        gridBooks.setVisibility(View.GONE);
        listBooks.setVisibility(View.GONE);
        statsText.setText("正在加载书架...");
        applyBookshelfMode();
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
}
