package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemedActivity;
import com.metahumanz.pacilread.util.FileAssetHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ThemedActivity {
    private static final int REQUEST_PICK_BOOK = 1001;
    private static final int REQUEST_PICK_COVER = 1002;
    private static final int SECTION_BOOKSHELF = 0;
    private static final int SECTION_PREVIEW = 1;
    private static final int SECTION_SETTINGS = 2;
    private static final String VIEW_MODE_CARD = "card";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<BookRecord> allBooks = new ArrayList<>();

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private BookImportService importService;
    private BookListAdapter listAdapter;
    private BookGridAdapter gridAdapter;

    private View mainRoot;
    private View drawerPanel;
    private View drawerScrim;
    private View sectionBookshelf;
    private View sectionPreview;
    private View sectionSettingsOverview;
    private LinearLayout emptyLayout;
    private LinearLayout loadingLayout;
    private EditText searchInput;
    private GridView gridBooks;
    private ListView listBooks;
    private TextView sectionTitle;
    private TextView sectionSubtitle;
    private TextView loadingText;
    private TextView statsText;
    private TextView emptyTitle;
    private TextView emptyHint;
    private TextView previewBookshelfMode;
    private TextView previewTheme;
    private TextView previewShelfStats;
    private TextView overviewTheme;
    private TextView overviewSync;
    private TextView overviewReader;
    private TextView drawerStatus;
    private Button headerActionButton;
    private Button buttonModeCard;
    private Button buttonModeList;
    private Button emptyActionButton;

    private View navBookshelf;
    private View navPreview;
    private View navSettings;
    private TextView navBookshelfTitle;
    private TextView navBookshelfSubtitle;
    private TextView navPreviewTitle;
    private TextView navPreviewSubtitle;
    private TextView navSettingsTitle;
    private TextView navSettingsSubtitle;

    private LinearLayout previewBooksCard;
    private LinearLayout previewBooksList;
    private TextView previewShelfAction;
    private TextView previewCardInitialOne;
    private TextView previewCardInitialTwo;
    private TextView previewCardTitleOne;
    private TextView previewCardTitleTwo;
    private TextView previewListTitleOne;
    private TextView previewListTitleTwo;
    private TextView previewListTitleThree;
    private ImageView previewReaderBackground;
    private View previewReaderScrim;
    private LinearLayout previewReaderTop;
    private LinearLayout previewReaderBottom;
    private TextView previewReaderBack;
    private TextView previewReaderTitle;
    private TextView previewReaderSubtitle;
    private TextView previewReaderProgress;
    private TextView previewReaderChipOne;
    private TextView previewReaderChipTwo;
    private TextView previewReaderChipThree;
    private TextView previewReaderHeading;
    private TextView previewReaderBody;
    private TextView previewReaderHudLeft;
    private TextView previewReaderHudCenter;
    private TextView previewReaderHudRight;

    private long pendingCoverBookId = -1L;
    private int currentSection = SECTION_BOOKSHELF;

    private boolean drawerOpen = false;
    private boolean drawerGestureCandidate = false;
    private boolean drawerDragging = false;
    private float drawerDownX = 0f;
    private float drawerDownY = 0f;
    private float drawerBaseOffset = 0f;
    private float drawerLastX = 0f;
    private long drawerLastEventTime = 0L;
    private float drawerVelocityX = 0f;
    private int touchSlop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        databaseHelper = ReaderDatabaseHelper.getInstance(this);
        settingsStore = new SettingsStore(this);
        importService = new BookImportService(this);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        bindViews();
        setupAdapters();
        setupInteractions();
        configureDrawerInitialState();
        showSection(SECTION_BOOKSHELF);

        if (savedInstanceState == null && settingsStore.isAutoOpenLastBook()) {
            maybeAutoOpenLastBook();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBooks();
        refreshOverviewPanels();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (isDrawerVisible()) {
            closeDrawer();
            return;
        }
        if (currentSection != SECTION_BOOKSHELF) {
            showSection(SECTION_BOOKSHELF);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleDrawerGesture(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_BOOK) {
            importBook(data.getData());
        } else if (requestCode == REQUEST_PICK_COVER && pendingCoverBookId > 0) {
            attachCover(pendingCoverBookId, data.getData());
        }
    }

    private void bindViews() {
        mainRoot = findViewById(R.id.main_root);
        drawerPanel = findViewById(R.id.drawer_panel);
        drawerScrim = findViewById(R.id.drawer_scrim);
        sectionBookshelf = findViewById(R.id.section_bookshelf);
        sectionPreview = findViewById(R.id.section_preview);
        sectionSettingsOverview = findViewById(R.id.section_settings_overview);
        emptyLayout = findViewById(R.id.layout_empty);
        loadingLayout = findViewById(R.id.layout_loading);
        searchInput = findViewById(R.id.input_search);
        gridBooks = findViewById(R.id.grid_books);
        listBooks = findViewById(R.id.list_books);
        sectionTitle = findViewById(R.id.text_section_title);
        sectionSubtitle = findViewById(R.id.text_section_subtitle);
        loadingText = findViewById(R.id.text_loading);
        statsText = findViewById(R.id.text_stats);
        emptyTitle = findViewById(R.id.text_empty_title);
        emptyHint = findViewById(R.id.text_empty_hint);
        previewBookshelfMode = findViewById(R.id.text_preview_bookshelf_mode);
        previewTheme = findViewById(R.id.text_preview_theme);
        previewShelfStats = findViewById(R.id.text_preview_shelf_stats);
        overviewTheme = findViewById(R.id.text_overview_theme);
        overviewSync = findViewById(R.id.text_overview_sync);
        overviewReader = findViewById(R.id.text_overview_reader);
        drawerStatus = findViewById(R.id.text_drawer_status);
        headerActionButton = findViewById(R.id.button_header_action);
        buttonModeCard = findViewById(R.id.button_mode_card);
        buttonModeList = findViewById(R.id.button_mode_list);
        emptyActionButton = findViewById(R.id.button_empty_action);

        navBookshelf = findViewById(R.id.nav_bookshelf);
        navPreview = findViewById(R.id.nav_preview);
        navSettings = findViewById(R.id.nav_settings);
        navBookshelfTitle = findViewById(R.id.text_nav_bookshelf_title);
        navBookshelfSubtitle = findViewById(R.id.text_nav_bookshelf_subtitle);
        navPreviewTitle = findViewById(R.id.text_nav_preview_title);
        navPreviewSubtitle = findViewById(R.id.text_nav_preview_subtitle);
        navSettingsTitle = findViewById(R.id.text_nav_settings_title);
        navSettingsSubtitle = findViewById(R.id.text_nav_settings_subtitle);

        previewBooksCard = findViewById(R.id.layout_preview_books_card);
        previewBooksList = findViewById(R.id.layout_preview_books_list);
        previewShelfAction = findViewById(R.id.text_preview_shelf_action);
        previewCardInitialOne = findViewById(R.id.text_preview_card_initial_1);
        previewCardInitialTwo = findViewById(R.id.text_preview_card_initial_2);
        previewCardTitleOne = findViewById(R.id.text_preview_card_title_1);
        previewCardTitleTwo = findViewById(R.id.text_preview_card_title_2);
        previewListTitleOne = findViewById(R.id.text_preview_list_title_1);
        previewListTitleTwo = findViewById(R.id.text_preview_list_title_2);
        previewListTitleThree = findViewById(R.id.text_preview_list_title_3);
        previewReaderBackground = findViewById(R.id.image_preview_reader_background);
        previewReaderScrim = findViewById(R.id.view_preview_reader_scrim);
        previewReaderTop = findViewById(R.id.layout_preview_reader_top);
        previewReaderBottom = findViewById(R.id.layout_preview_reader_bottom);
        previewReaderBack = findViewById(R.id.text_preview_reader_back);
        previewReaderTitle = findViewById(R.id.text_preview_reader_title);
        previewReaderSubtitle = findViewById(R.id.text_preview_reader_subtitle);
        previewReaderProgress = findViewById(R.id.text_preview_reader_progress);
        previewReaderChipOne = findViewById(R.id.text_preview_reader_chip_1);
        previewReaderChipTwo = findViewById(R.id.text_preview_reader_chip_2);
        previewReaderChipThree = findViewById(R.id.text_preview_reader_chip_3);
        previewReaderHeading = findViewById(R.id.text_preview_reader_heading);
        previewReaderBody = findViewById(R.id.text_preview_reader_body);
        previewReaderHudLeft = findViewById(R.id.text_preview_reader_hud_left);
        previewReaderHudCenter = findViewById(R.id.text_preview_reader_hud_center);
        previewReaderHudRight = findViewById(R.id.text_preview_reader_hud_right);
    }

    private void setupAdapters() {
        View footerView = getLayoutInflater().inflate(R.layout.item_book_footer, listBooks, false);
        footerView.findViewById(R.id.button_footer_add_book).setOnClickListener(v -> openPicker());
        listBooks.addFooterView(footerView, null, true);
        listAdapter = new BookListAdapter(this);
        gridAdapter = new BookGridAdapter(this);
        listBooks.setAdapter(listAdapter);
        gridBooks.setAdapter(gridAdapter);
    }

    private void setupInteractions() {
        findViewById(R.id.button_open_drawer).setOnClickListener(v -> openDrawer());
        drawerScrim.setOnClickListener(v -> closeDrawer());

        navBookshelf.setOnClickListener(v -> {
            showSection(SECTION_BOOKSHELF);
            closeDrawer();
        });
        navPreview.setOnClickListener(v -> {
            showSection(SECTION_PREVIEW);
            closeDrawer();
        });
        navSettings.setOnClickListener(v -> {
            closeDrawer();
            openSettings();
        });

        headerActionButton.setOnClickListener(v -> {
            if (currentSection == SECTION_BOOKSHELF) {
                openPicker();
            } else {
                openSettings();
            }
        });
        findViewById(R.id.button_preview_open_settings).setOnClickListener(v -> openSettings());
        View overviewButton = findViewById(R.id.button_overview_open_settings);
        if (overviewButton != null) {
            overviewButton.setOnClickListener(v -> openSettings());
        }

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
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void configureDrawerInitialState() {
        drawerPanel.post(() -> {
            setDrawerOffset(-drawerPanel.getWidth());
            drawerPanel.setVisibility(View.INVISIBLE);
            drawerScrim.setVisibility(View.GONE);
            drawerScrim.setAlpha(0f);
        });
        if (mainRoot != null) {
            mainRoot.post(this::updateDrawerGestureExclusion);
            mainRoot.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateDrawerGestureExclusion());
        }
    }

    private void showSection(int section) {
        currentSection = section;
        sectionBookshelf.setVisibility(section == SECTION_BOOKSHELF ? View.VISIBLE : View.GONE);
        sectionPreview.setVisibility(section == SECTION_PREVIEW ? View.VISIBLE : View.GONE);
        sectionSettingsOverview.setVisibility(View.GONE);
        if (section == SECTION_BOOKSHELF) {
            sectionTitle.setText("书架");
            sectionSubtitle.setText("管理本地书籍");
            headerActionButton.setText("导入");
        } else {
            sectionTitle.setText("界面预览");
            sectionSubtitle.setText("阅读界面与排版预览");
            headerActionButton.setText("设置");
        }
        updateNavigationState();
        updateEmptyState(currentQuery());
        refreshOverviewPanels();
    }

    private void updateNavigationState() {
        styleDrawerItem(navBookshelf, navBookshelfTitle, navBookshelfSubtitle, currentSection == SECTION_BOOKSHELF);
        styleDrawerItem(navPreview, navPreviewTitle, navPreviewSubtitle, currentSection == SECTION_PREVIEW);
        styleDrawerItem(navSettings, navSettingsTitle, navSettingsSubtitle, false);
    }

    private void styleDrawerItem(View container, TextView titleView, TextView subtitleView, boolean selected) {
        container.setBackgroundResource(selected ? R.drawable.bg_sidebar_nav_active : R.drawable.bg_sidebar_nav_idle);
        int titleColor = getColor(selected ? android.R.color.white : R.color.on_surface);
        int subtitleColor = getColor(selected ? android.R.color.white : R.color.on_surface_muted);
        titleView.setTextColor(titleColor);
        subtitleView.setTextColor(subtitleColor);
    }

    private void setBookshelfMode(String mode) {
        settingsStore.setBookshelfViewMode(mode);
        applyBookshelfMode();
        updatePreviewPanels();
        updateDrawerStatus();
    }

    private void applyBookshelfMode() {
        boolean usingCardMode = isCardMode();
        styleSelectionButton(buttonModeCard, usingCardMode);
        styleSelectionButton(buttonModeList, !usingCardMode);
        if (currentSection == SECTION_BOOKSHELF && !shouldShowBookshelfEmptyState(currentQuery())) {
            gridBooks.setVisibility(usingCardMode ? View.VISIBLE : View.GONE);
            listBooks.setVisibility(usingCardMode ? View.GONE : View.VISIBLE);
        }
        previewBooksCard.setVisibility(usingCardMode ? View.VISIBLE : View.GONE);
        previewBooksList.setVisibility(usingCardMode ? View.GONE : View.VISIBLE);
    }

    private void styleSelectionButton(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setTextColor(getColor(selected ? android.R.color.white : R.color.on_surface));
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain",
                "application/epub+zip",
                "application/pdf",
                "application/octet-stream"
        });
        startActivityForResult(intent, REQUEST_PICK_BOOK);
    }

    private void openCoverPicker(long bookId) {
        pendingCoverBookId = bookId;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_COVER);
    }

    private void importBook(Uri uri) {
        showLoading("正在解析书籍...");
        executor.execute(() -> {
            try {
                long bookId = databaseHelper.insertImportedBook(importService.importFromUri(uri));
                runOnUiThread(() -> {
                    hideLoading();
                    refreshBooks();
                    showSection(SECTION_BOOKSHELF);
                    closeDrawer();
                    openBook(bookId);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    hideLoading();
                    showToast("导入失败: " + error.getMessage());
                });
            }
        });
    }

    private void refreshBooks() {
        executor.execute(() -> {
            List<BookRecord> books = databaseHelper.getBooks();
            runOnUiThread(() -> {
                allBooks.clear();
                allBooks.addAll(books);
                applyFilter(currentQuery());
                refreshOverviewPanels();
            });
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
        updateStats(filtered);
        updateEmptyState(query);
        updatePreviewPanels();
    }

    private void updateEmptyState(String query) {
        if (currentSection != SECTION_BOOKSHELF) {
            emptyLayout.setVisibility(View.GONE);
            return;
        }
        boolean showEmpty = shouldShowBookshelfEmptyState(query);
        emptyLayout.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
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
        return listAdapter.getCount() == 0;
    }

    private void refreshOverviewPanels() {
        previewTheme.setText(
                "界面: " + labelForReaderUiTheme(settingsStore.getReaderUiThemeMode())
                        + " · 阅读预设: " + labelForReaderPreset(settingsStore.getReaderTheme())
                        + " · 字号 " + Math.round(settingsStore.getFontSizeSp()) + "sp"
                        + " · 行距 " + Math.round(settingsStore.getLineSpacingExtraSp()) + "sp"
        );
        String bookshelfMode = isCardMode() ? "卡片书架" : "列表书架";
        overviewTheme.setText(
                "主题: " + labelForAppTheme(settingsStore.getAppThemeMode())
                        + " · 书架: " + bookshelfMode
        );
        overviewSync.setText(settingsStore.isWebDavEnabled() ? "WebDAV 已启用" : "WebDAV 未启用");
        overviewReader.setText(settingsStore.isAutoOpenLastBook()
                ? "启动后自动打开最近阅读"
                : "启动后停留在书架");
        applyBookshelfMode();
        updatePreviewPanels();
        updateDrawerStatus();
    }

    private void updateDrawerStatus() {
        String status = String.format(
                Locale.SIMPLIFIED_CHINESE,
                "已导入 %d 本书\n%s · %s",
                allBooks.size(),
                isCardMode() ? "卡片书架" : "列表书架",
                labelForAppTheme(settingsStore.getAppThemeMode())
        );
        drawerStatus.setText(status);
    }

    private void updatePreviewPanels() {
        boolean cardMode = isCardMode();
        previewBookshelfMode.setText(cardMode ? "当前书架: 卡片模式" : "当前书架: 列表模式");
        previewBooksCard.setVisibility(cardMode ? View.VISIBLE : View.GONE);
        previewBooksList.setVisibility(cardMode ? View.GONE : View.VISIBLE);
        previewShelfAction.setText(allBooks.isEmpty() ? "导入" : "添加");
        previewShelfStats.setText(String.format(
                Locale.SIMPLIFIED_CHINESE,
                "共 %d 本 · %s",
                allBooks.size(),
                cardMode ? "封面平铺" : "紧凑列表"
        ));
        updatePreviewBooks();
        updatePreviewReader();
    }

    private void updatePreviewBooks() {
        String firstTitle = previewTitleAt(0, allBooks.isEmpty() ? "添加第一本书" : "最近阅读");
        String secondTitle = previewTitleAt(1, allBooks.size() > 1 ? "继续阅读" : "再添加一本");
        String thirdTitle = previewTitleAt(2, "添加新书");

        previewCardTitleOne.setText(firstTitle);
        previewCardTitleTwo.setText(secondTitle);
        previewCardInitialOne.setText(initialsFor(firstTitle));
        previewCardInitialTwo.setText(initialsFor(secondTitle));
        previewListTitleOne.setText(firstTitle);
        previewListTitleTwo.setText(secondTitle);
        previewListTitleThree.setText(thirdTitle);
    }

    private void updatePreviewReader() {
        String selectedTitle = allBooks.isEmpty() ? "最近阅读" : titleOrDefault(allBooks.get(0).title, "最近阅读");
        previewReaderTitle.setText(selectedTitle);
        previewReaderSubtitle.setText(labelForReaderPreset(settingsStore.getReaderTheme())
                + " · " + labelForReaderUiTheme(settingsStore.getReaderUiThemeMode()));
        previewReaderProgress.setText("0%");
        previewReaderHudCenter.setText("第 1/2 页");
        previewReaderHeading.setVisibility(settingsStore.isChapterTitleVisible() ? View.VISIBLE : View.GONE);
        previewReaderBody.setText("更多精彩小说尽在知轩藏书下载：\nhttps://zxcs.zip/\n\n内容简介：一个普通山村穷小子，在机缘与谨慎并行的路上踏入修仙世界。");
        previewReaderBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12f, settingsStore.getFontSizeSp() - 4f));
        previewReaderBody.setLineSpacing(settingsStore.getLineSpacingExtraSp() * getResources().getDisplayMetrics().scaledDensity / 4f, 1f);
        applyReaderPreviewTheme();
    }

    private void applyReaderPreviewTheme() {
        String backgroundPath = settingsStore.getReaderBackgroundPath();
        boolean customBackgroundApplied = false;
        if (backgroundPath != null && !backgroundPath.isBlank()) {
            File file = new File(backgroundPath);
            if (file.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap != null) {
                    previewReaderBackground.setImageBitmap(bitmap);
                    customBackgroundApplied = true;
                }
            }
        }
        if (!customBackgroundApplied) {
            previewReaderBackground.setImageResource(readerBackgroundResource(settingsStore.getReaderTheme()));
        }

        boolean useDarkUi = shouldUseDarkReaderUi();
        int panelBackground = useDarkUi ? R.drawable.bg_menu_panel : R.drawable.bg_card;
        int actionBackground = useDarkUi ? R.drawable.bg_outline_button_light : R.drawable.bg_soft_button;
        int hudBackground = useDarkUi ? R.drawable.bg_reader_hud_pill : R.drawable.bg_soft_button;
        int foreground = getColor(useDarkUi ? android.R.color.white : R.color.on_surface);
        int secondary = getColor(useDarkUi ? android.R.color.white : R.color.on_surface_muted);

        previewReaderTop.setBackgroundResource(panelBackground);
        previewReaderBottom.setBackgroundResource(panelBackground);
        previewReaderBack.setBackgroundResource(actionBackground);
        previewReaderChipOne.setBackgroundResource(actionBackground);
        previewReaderChipTwo.setBackgroundResource(actionBackground);
        previewReaderChipThree.setBackgroundResource(actionBackground);
        previewReaderProgress.setBackgroundResource(hudBackground);
        previewReaderHudLeft.setBackgroundResource(hudBackground);
        previewReaderHudCenter.setBackgroundResource(hudBackground);
        previewReaderHudRight.setBackgroundResource(hudBackground);

        previewReaderBack.setTextColor(foreground);
        previewReaderTitle.setTextColor(foreground);
        previewReaderSubtitle.setTextColor(secondary);
        previewReaderProgress.setTextColor(foreground);
        previewReaderChipOne.setTextColor(foreground);
        previewReaderChipTwo.setTextColor(foreground);
        previewReaderChipThree.setTextColor(foreground);
        previewReaderHudLeft.setTextColor(foreground);
        previewReaderHudCenter.setTextColor(foreground);
        previewReaderHudRight.setTextColor(foreground);

        if ("forest".equals(settingsStore.getReaderTheme())) {
            previewReaderScrim.setBackgroundColor(getColor(R.color.overlay_dark));
            previewReaderHeading.setTextColor(getColor(R.color.reader_forest_text));
            previewReaderBody.setTextColor(getColor(R.color.reader_forest_text));
        } else if ("night".equals(settingsStore.getReaderTheme())) {
            previewReaderScrim.setBackgroundColor(getColor(R.color.overlay_dark));
            previewReaderHeading.setTextColor(getColor(R.color.reader_night_text));
            previewReaderBody.setTextColor(getColor(R.color.reader_night_text));
        } else {
            previewReaderScrim.setBackgroundColor(getColor(R.color.overlay_light));
            previewReaderHeading.setTextColor(getColor(R.color.reader_paper_text));
            previewReaderBody.setTextColor(getColor(R.color.reader_paper_text));
        }
    }

    private int readerBackgroundResource(String theme) {
        if ("forest".equals(theme)) {
            return R.drawable.theme_bg_forest;
        }
        if ("night".equals(theme)) {
            return R.drawable.theme_bg_night;
        }
        return R.drawable.theme_bg_paper;
    }

    private boolean shouldUseDarkReaderUi() {
        String mode = settingsStore.getReaderUiThemeMode();
        if ("dark".equals(mode)) {
            return true;
        }
        if ("light".equals(mode)) {
            return false;
        }
        if ("system".equals(mode)) {
            return isSystemDarkMode();
        }
        return isAppDarkMode();
    }

    private boolean isAppDarkMode() {
        String appTheme = settingsStore.getAppThemeMode();
        if ("dark".equals(appTheme)) {
            return true;
        }
        if ("light".equals(appTheme)) {
            return false;
        }
        return isSystemDarkMode();
    }

    private boolean isSystemDarkMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private String labelForAppTheme(String value) {
        if ("light".equals(value)) {
            return "浅色";
        }
        if ("dark".equals(value)) {
            return "深色";
        }
        return "跟随系统";
    }

    private String labelForReaderUiTheme(String value) {
        if ("system".equals(value)) {
            return "跟随系统";
        }
        if ("light".equals(value)) {
            return "浅色";
        }
        if ("dark".equals(value)) {
            return "深色";
        }
        return "跟随应用";
    }

    private String labelForReaderPreset(String value) {
        if ("forest".equals(value)) {
            return "护眼";
        }
        if ("night".equals(value)) {
            return "夜航";
        }
        return "纸控";
    }

    private String previewTitleAt(int index, String fallback) {
        if (index >= 0 && index < allBooks.size()) {
            return titleOrDefault(allBooks.get(index).title, fallback);
        }
        return fallback;
    }

    private String titleOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String initialsFor(String title) {
        String trimmed = titleOrDefault(title, "书");
        return trimmed.substring(0, Math.min(1, trimmed.length())).toUpperCase(Locale.ROOT);
    }

    private void openBook(long bookId) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("book_id", bookId);
        startActivity(intent);
    }

    private void maybeAutoOpenLastBook() {
        executor.execute(() -> {
            long bookId = databaseHelper.getMostRecentBookId();
            if (bookId > 0) {
                runOnUiThread(() -> openBook(bookId));
            }
        });
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

    private void updateStats(List<BookRecord> filtered) {
        int textCount = 0;
        int epubCount = 0;
        int pdfCount = 0;
        for (BookRecord book : allBooks) {
            if ("epub".equalsIgnoreCase(book.bookType)) {
                epubCount++;
            } else if ("pdf".equalsIgnoreCase(book.bookType)) {
                pdfCount++;
            } else {
                textCount++;
            }
        }
        String label = String.format(
                Locale.SIMPLIFIED_CHINESE,
                "共 %d 本 · 当前 %d 本 · TXT %d · EPUB %d · PDF %d",
                allBooks.size(),
                filtered.size(),
                textCount,
                epubCount,
                pdfCount
        );
        statsText.setText(label);
    }

    private boolean handleDrawerGesture(MotionEvent event) {
        if (drawerPanel.getWidth() == 0) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drawerGestureCandidate = shouldStartDrawerGesture(x);
                drawerDragging = false;
                drawerVelocityX = 0f;
                if (drawerGestureCandidate) {
                    drawerDownX = x;
                    drawerDownY = y;
                    drawerLastX = x;
                    drawerLastEventTime = event.getEventTime();
                    drawerBaseOffset = drawerPanel.getTranslationX();
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!drawerGestureCandidate) {
                    return false;
                }
                float deltaX = x - drawerDownX;
                float deltaY = y - drawerDownY;
                if (!drawerDragging) {
                    if (Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        drawerGestureCandidate = false;
                        return false;
                    }
                    if (Math.abs(deltaX) <= touchSlop || Math.abs(deltaX) <= Math.abs(deltaY)) {
                        return false;
                    }
                    drawerDragging = true;
                    prepareDrawerForGesture();
                }
                float targetOffset = clamp(drawerBaseOffset + deltaX, -drawerPanel.getWidth(), 0f);
                setDrawerOffset(targetOffset);
                long now = event.getEventTime();
                long elapsed = Math.max(1L, now - drawerLastEventTime);
                drawerVelocityX = (x - drawerLastX) / elapsed;
                drawerLastX = x;
                drawerLastEventTime = now;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drawerDragging) {
                    finishDrawerGesture();
                    return true;
                }
                if (drawerGestureCandidate && isDrawerVisible() && x > drawerPanel.getWidth()) {
                    closeDrawer();
                    drawerGestureCandidate = false;
                    return true;
                }
                drawerGestureCandidate = false;
                return false;
            default:
                return false;
        }
    }

    private boolean shouldStartDrawerGesture(float x) {
        if (!isDrawerVisible()) {
            return x <= dp(56);
        }
        float panelWidth = drawerPanel.getWidth();
        return x <= panelWidth + dp(24);
    }

    private void finishDrawerGesture() {
        float offset = drawerPanel.getTranslationX();
        float openThreshold = -drawerPanel.getWidth() * 0.45f;
        boolean shouldOpen = offset > openThreshold || drawerVelocityX > 0.8f;
        if (drawerVelocityX < -0.8f) {
            shouldOpen = false;
        }
        drawerGestureCandidate = false;
        drawerDragging = false;
        if (shouldOpen) {
            openDrawer();
        } else {
            closeDrawer();
        }
    }

    private void openDrawer() {
        if (drawerPanel.getWidth() == 0) {
            drawerPanel.post(this::openDrawer);
            return;
        }
        drawerOpen = true;
        animateDrawerTo(0f, 220L);
    }

    private void closeDrawer() {
        if (drawerPanel.getWidth() == 0) {
            return;
        }
        drawerOpen = false;
        animateDrawerTo(-drawerPanel.getWidth(), 180L);
    }

    private void animateDrawerTo(float targetOffset, long durationMs) {
        prepareDrawerForGesture();
        drawerPanel.animate().cancel();
        drawerScrim.animate().cancel();
        float progress = 1f - (-targetOffset / drawerPanel.getWidth());
        drawerPanel.animate()
                .translationX(targetOffset)
                .setDuration(durationMs)
                .withEndAction(() -> {
                    if (targetOffset <= -drawerPanel.getWidth()) {
                        drawerPanel.setVisibility(View.INVISIBLE);
                    }
                })
                .start();
        drawerScrim.animate()
                .alpha(progress)
                .setDuration(durationMs)
                .withEndAction(() -> {
                    if (progress <= 0f) {
                        drawerScrim.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void prepareDrawerForGesture() {
        drawerPanel.setVisibility(View.VISIBLE);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.animate().cancel();
        drawerScrim.animate().cancel();
    }

    private void setDrawerOffset(float offset) {
        float clamped = clamp(offset, -drawerPanel.getWidth(), 0f);
        float progress = 1f - (-clamped / drawerPanel.getWidth());
        drawerPanel.setVisibility(progress <= 0f ? View.INVISIBLE : View.VISIBLE);
        drawerPanel.setTranslationX(clamped);
        drawerScrim.setVisibility(progress <= 0f ? View.GONE : View.VISIBLE);
        drawerScrim.setAlpha(progress);
        drawerOpen = progress > 0.95f;
    }

    private boolean isDrawerVisible() {
        return drawerPanel.getVisibility() == View.VISIBLE || drawerDragging;
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

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void updateDrawerGestureExclusion() {
        if (mainRoot == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        List<Rect> exclusionRects = new ArrayList<>();
        exclusionRects.add(new Rect(0, 0, dp(40), mainRoot.getHeight()));
        mainRoot.setSystemGestureExclusionRects(exclusionRects);
    }
}
