package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemedActivity;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
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

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<BookRecord> allBooks = new ArrayList<>();

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private BookImportService importService;
    private AppDrawerController drawerController;
    private BookListAdapter listAdapter;
    private BookGridAdapter gridAdapter;
    private View listFooterView;

    // Views
    private View mainRoot;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bookshelf);

        databaseHelper = ReaderDatabaseHelper.getInstance(this);
        settingsStore = new SettingsStore(this);
        importService = new BookImportService(this);

        bindViews();
        setupAdapters();
        setupInteractions();
        configureDrawer();
        showBookshelfLoadingState();

        if (savedInstanceState == null && settingsStore.isAutoOpenLastBook()) {
            maybeAutoOpenLastBook();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAddEntryVisibility();
        refreshBooks();
        updateDrawerStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (drawerController != null && drawerController.onBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (drawerController != null && drawerController.handleTouchEvent(event)) {
            if (drawerController.consumePendingChildTouchCancel()) {
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
        mainRoot = findViewById(R.id.main_root);
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
                openBook(book.id);
            }
        });
        gridBooks.setOnItemLongClickListener((parent, view, position, id) -> {
            if (gridAdapter.isAddPosition(position)) {
                openPicker();
                return true;
            }
            BookRecord book = gridAdapter.getItem(position);
            if (book != null) {
                showBookActions(book);
                return true;
            }
            return false;
        });

        listBooks.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= listAdapter.getCount()) {
                openPicker();
                return;
            }
            openBook(listAdapter.getItem(position).id);
        });
        listBooks.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= listAdapter.getCount()) {
                return true;
            }
            showBookActions(listAdapter.getItem(position));
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

    // ==================== Drawer ====================

    private void configureDrawer() {
        drawerController = new AppDrawerController(this, mainRoot, destination -> {
            if (destination == AppDrawerController.SECTION_PREVIEW) {
                Intent intent = new Intent(this, PreviewActivity.class);
                startActivity(intent);
                drawerController.closeDrawer();
                return;
            }
            if (destination == AppDrawerController.SECTION_SETTINGS) {
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                drawerController.closeDrawer();
                return;
            }
            // SECTION_BOOKSHELF - already here, just close drawer
            drawerController.closeDrawer();
        });
        drawerController.bindMenuButton(R.id.button_open_drawer);
        drawerController.setCurrentSection(AppDrawerController.SECTION_BOOKSHELF);
    }

    // ==================== Bookshelf ====================

    private void setBookshelfMode(String mode) {
        settingsStore.setBookshelfViewMode(mode);
        applyBookshelfMode();
        updateDrawerStatus();
    }

    private void applyBookshelfMode() {
        boolean usingCardMode = isCardMode();
        styleSelectionButton(buttonModeCard, usingCardMode);
        styleSelectionButton(buttonModeList, !usingCardMode);
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

    private void styleSelectionButton(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_app_primary_button : R.drawable.bg_app_outline_button);
        button.setTextColor(ThemeModeHelper.resolveColor(
                this,
                selected ? R.color.app_button_primary_text : R.color.app_button_outline_text
        ));
    }

    private void refreshBooks() {
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
                    updateDrawerStatus();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    booksLoading = false;
                    booksLoaded = true;
                    applyFilter(currentQuery());
                    updateDrawerStatus();
                    showToast("加载书架失败: " + readableError(error));
                });
            }
        });
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

    private void updateDrawerStatus() {
        String status = String.format(
                Locale.SIMPLIFIED_CHINESE,
                "已导入 %d 本书\n%s · %s",
                allBooks.size(),
                isCardMode() ? "网格书架" : "列表书架",
                ThemeModeHelper.getResolvedAppAppearanceLabel(this)
        );
        if (drawerController != null) {
            drawerController.setStatusText(status);
        }
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
            for (Uri uri : uris) {
                try {
                    databaseHelper.insertImportedBook(importService.importFromUri(uri, false));
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                }
            }
            final int sCount = successCount, fCount = failCount;
            runOnUiThread(() -> {
                hideLoading();
                refreshBooks();
                if (fCount > 0) {
                    showToast("导入完成: 成功 " + sCount + "，失败 " + fCount);
                } else {
                    showToast("成功导入 " + sCount + " 本书籍");
                }
            });
        });
    }

    private void openBook(long bookId) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("book_id", bookId);
        startActivity(intent);
    }

    private void showBookActions(BookRecord book) {
        List<String> itemList = new ArrayList<>();
        itemList.add("打开");
        itemList.add(book.pinned ? "取消置顶" : "置顶到顶部");
        itemList.add("设置自定义封面");
        if (book.coverPath != null && !book.coverPath.isBlank()) {
            itemList.add("移除封面");
        }
        itemList.add("删除");
        String[] items = itemList.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(book.title)
                .setItems(items, (dialog, which) -> {
                    String selected = items[which];
                    if ("打开".equals(selected)) {
                        openBook(book.id);
                    } else if ("设置自定义封面".equals(selected)) {
                        openCoverPicker(book.id);
                    } else if ("移除封面".equals(selected)) {
                        removeCover(book);
                    } else if ("删除".equals(selected)) {
                        confirmDelete(book);
                    } else {
                        executor.execute(() -> {
                            databaseHelper.setPinned(book.id, !book.pinned);
                            runOnUiThread(this::refreshBooks);
                        });
                    }
                })
                .show();
    }

    private void attachCover(long bookId, Uri uri) {
        showLoading("正在保存封面...");
        executor.execute(() -> {
            try {
                BookRecord currentBook = databaseHelper.getBook(bookId);
                File coverFile = FileAssetHelper.copyUriToFolder(this, uri, "covers", "cover_" + bookId);
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
        new AlertDialog.Builder(this)
                .setTitle("删除书籍")
                .setMessage("确定要删除《" + book.title + "》吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> executor.execute(() -> {
                    databaseHelper.deleteBook(book.id);
                    runOnUiThread(() -> {
                        refreshBooks();
                        showToast("已删除");
                    });
                }))
                .show();
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

    // ==================== UI Helpers ====================

    private void showBookshelfLoadingState() {
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
