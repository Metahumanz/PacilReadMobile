package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.model.BookmarkRecord;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ReadingBookStatRecord;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
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
    private static final int PAGE_BOOKSHELF = 0;
    private static final int PAGE_STATS = 1;
    private static final int PAGE_BOOKMARKS = 2;
    private static final int PAGE_SETTINGS = 3;
    private static final int SWIPE_NONE = 0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<BookRecord> allBooks = new ArrayList<>();
    private final List<Integer> activeHomePages = new ArrayList<>();

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private BookImportService importService;
    private BookListAdapter listAdapter;
    private BookGridAdapter gridAdapter;
    private View listFooterView;

    // Views
    private View mainRoot;
    private View pageContainer;
    private View sectionBookshelf;
    private View sectionReadingStats;
    private View sectionBookmarks;
    private View sectionHomeSettings;
    private View bottomNavigation;
    private TextView navBookshelf;
    private TextView navStats;
    private TextView navBookmarks;
    private TextView navSettings;
    private LinearLayout emptyLayout;
    private LinearLayout loadingLayout;
    private LinearLayout homeStatsListLayout;
    private LinearLayout homeBookmarksListLayout;
    private EditText searchInput;
    private GridView gridBooks;
    private ListView listBooks;
    private TextView sectionTitle;
    private TextView loadingText;
    private TextView statsText;
    private TextView homeStatsStatusText;
    private TextView homeStatsTotalText;
    private TextView homeStatsEmptyText;
    private TextView homeBookmarksEmptyText;
    private TextView emptyTitle;
    private TextView emptyHint;
    private Button headerActionButton;
    private Button buttonModeCard;
    private Button buttonModeList;
    private Button emptyActionButton;
    private Button homeStatsTodayButton;
    private Button homeStatsWeekButton;
    private Button homeStatsYearButton;
    private Button homeNavIconsButton;
    private Button homeNavTextButton;
    private Button openFullSettingsButton;
    private CheckBox homeReadingTimeTrackingCheck;
    private View containerSearch;
    private View iconSearch;
    private long pendingCoverBookId = -1L;
    private boolean booksLoaded = false;
    private boolean booksLoading = false;
    private String selectedHomeStatsPeriod = ReadingStatsUtils.PERIOD_TODAY;
    private int currentHomePage = PAGE_BOOKSHELF;
    private int pendingSwipePage = -1;
    private int pendingSwipeDirection = SWIPE_NONE;
    private int touchSlop = 0;
    private float swipeDownX = 0f;
    private float swipeDownY = 0f;
    private float swipeLastX = 0f;
    private long swipeLastEventTime = 0L;
    private float swipeVelocityX = 0f;
    private boolean homeSwipeCandidate = false;
    private boolean homeSwipeDragging = false;
    private boolean bindingHomeSettings = false;

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
        setupHomeNavigation();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        showBookshelfLoadingState();

        if (savedInstanceState == null && settingsStore.isAutoOpenLastBook()) {
            maybeAutoOpenLastBook();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAddEntryVisibility();
        bindHomeSettingsValues();
        rebuildHomeTabs();
        refreshBooks();
        refreshHomeStatsIfVisible(true);
        refreshBookmarksIfVisible();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (currentHomePage != PAGE_BOOKSHELF) {
            selectHomePage(PAGE_BOOKSHELF, true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleHomeSwipe(event)) {
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
        pageContainer = findViewById(R.id.page_container);
        sectionBookshelf = findViewById(R.id.section_bookshelf);
        sectionReadingStats = findViewById(R.id.section_reading_stats);
        sectionBookmarks = findViewById(R.id.section_bookmarks);
        sectionHomeSettings = findViewById(R.id.section_home_settings);
        bottomNavigation = findViewById(R.id.bottom_navigation);
        navBookshelf = findViewById(R.id.nav_home_bookshelf);
        navStats = findViewById(R.id.nav_home_stats);
        navBookmarks = findViewById(R.id.nav_home_bookmarks);
        navSettings = findViewById(R.id.nav_home_settings);
        sectionTitle = findViewById(R.id.text_section_title);
        emptyLayout = findViewById(R.id.layout_empty);
        loadingLayout = findViewById(R.id.layout_loading);
        homeStatsListLayout = findViewById(R.id.layout_home_stats_list);
        homeBookmarksListLayout = findViewById(R.id.layout_home_bookmarks_list);
        searchInput = findViewById(R.id.input_search);
        gridBooks = findViewById(R.id.grid_books);
        listBooks = findViewById(R.id.list_books);
        loadingText = findViewById(R.id.text_loading);
        statsText = findViewById(R.id.text_stats);
        homeStatsStatusText = findViewById(R.id.text_home_stats_status);
        homeStatsTotalText = findViewById(R.id.text_home_stats_total);
        homeStatsEmptyText = findViewById(R.id.text_home_stats_empty);
        homeBookmarksEmptyText = findViewById(R.id.text_home_bookmarks_empty);
        emptyTitle = findViewById(R.id.text_empty_title);
        emptyHint = findViewById(R.id.text_empty_hint);
        headerActionButton = findViewById(R.id.button_header_action);
        buttonModeCard = findViewById(R.id.button_mode_card);
        buttonModeList = findViewById(R.id.button_mode_list);
        emptyActionButton = findViewById(R.id.button_empty_action);
        homeStatsTodayButton = findViewById(R.id.button_home_stats_today);
        homeStatsWeekButton = findViewById(R.id.button_home_stats_week);
        homeStatsYearButton = findViewById(R.id.button_home_stats_year);
        homeNavIconsButton = findViewById(R.id.button_home_nav_icons);
        homeNavTextButton = findViewById(R.id.button_home_nav_text);
        openFullSettingsButton = findViewById(R.id.button_open_full_settings);
        homeReadingTimeTrackingCheck = findViewById(R.id.check_home_reading_time_tracking);
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

        if (homeStatsTodayButton != null) {
            homeStatsTodayButton.setOnClickListener(v -> selectHomeStatsPeriod(ReadingStatsUtils.PERIOD_TODAY));
        }
        if (homeStatsWeekButton != null) {
            homeStatsWeekButton.setOnClickListener(v -> selectHomeStatsPeriod(ReadingStatsUtils.PERIOD_WEEK));
        }
        if (homeStatsYearButton != null) {
            homeStatsYearButton.setOnClickListener(v -> selectHomeStatsPeriod(ReadingStatsUtils.PERIOD_YEAR));
        }
        if (homeNavIconsButton != null) {
            homeNavIconsButton.setOnClickListener(v -> selectHomeBottomNavStyle("icons"));
        }
        if (homeNavTextButton != null) {
            homeNavTextButton.setOnClickListener(v -> selectHomeBottomNavStyle("text"));
        }
        if (openFullSettingsButton != null) {
            openFullSettingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        }
        if (homeReadingTimeTrackingCheck != null) {
            homeReadingTimeTrackingCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (bindingHomeSettings) {
                    return;
                }
                settingsStore.setReadingTimeTrackingEnabled(isChecked);
                rebuildHomeTabs();
                refreshHomeStatsIfVisible(false);
            });
        }
    }

    // ==================== Home Navigation ====================

    private void setupHomeNavigation() {
        if (navBookshelf != null) navBookshelf.setOnClickListener(v -> selectHomePage(PAGE_BOOKSHELF, true));
        if (navStats != null) navStats.setOnClickListener(v -> selectHomePage(PAGE_STATS, true));
        if (navBookmarks != null) navBookmarks.setOnClickListener(v -> selectHomePage(PAGE_BOOKMARKS, true));
        if (navSettings != null) navSettings.setOnClickListener(v -> selectHomePage(PAGE_SETTINGS, true));
        rebuildHomeTabs();
    }

    private void rebuildHomeTabs() {
        activeHomePages.clear();
        activeHomePages.add(PAGE_BOOKSHELF);
        if (settingsStore != null && settingsStore.isReadingTimeTrackingEnabled()) {
            activeHomePages.add(PAGE_STATS);
            if (navStats != null) navStats.setVisibility(View.VISIBLE);
        } else if (navStats != null) {
            navStats.setVisibility(View.GONE);
        }
        activeHomePages.add(PAGE_BOOKMARKS);
        activeHomePages.add(PAGE_SETTINGS);
        if (!activeHomePages.contains(currentHomePage)) {
            currentHomePage = PAGE_BOOKSHELF;
        }
        updateBottomNavLabels();
        showHomePageImmediate(currentHomePage);
    }

    private void selectHomePage(int page, boolean animate) {
        if (!activeHomePages.contains(page)) {
            return;
        }
        if (page == currentHomePage) {
            refreshCurrentHomePage(false);
            return;
        }
        int oldPage = currentHomePage;
        currentHomePage = page;
        if (!animate || pageContainer == null || pageContainer.getWidth() <= 0) {
            showHomePageImmediate(page);
        } else {
            animateHomePageTransition(oldPage, page);
        }
        refreshCurrentHomePage(false);
    }

    private void showHomePageImmediate(int page) {
        for (int candidate : new int[]{PAGE_BOOKSHELF, PAGE_STATS, PAGE_BOOKMARKS, PAGE_SETTINGS}) {
            View section = sectionForPage(candidate);
            if (section == null) {
                continue;
            }
            section.animate().cancel();
            section.setTranslationX(0f);
            section.setVisibility(candidate == page ? View.VISIBLE : View.GONE);
        }
        updateHomeNavSelection();
    }

    private void animateHomePageTransition(int oldPage, int newPage) {
        View oldSection = sectionForPage(oldPage);
        View newSection = sectionForPage(newPage);
        if (oldSection == null || newSection == null || pageContainer == null) {
            showHomePageImmediate(newPage);
            return;
        }
        int width = Math.max(pageContainer.getWidth(), 1);
        int direction = activeHomePages.indexOf(newPage) > activeHomePages.indexOf(oldPage) ? 1 : -1;
        oldSection.animate().cancel();
        newSection.animate().cancel();
        newSection.setVisibility(View.VISIBLE);
        newSection.setTranslationX(direction * width);
        oldSection.animate()
                .translationX(-direction * width)
                .setDuration(220L)
                .withEndAction(() -> {
                    if (oldPage != currentHomePage) {
                        oldSection.setVisibility(View.GONE);
                        oldSection.setTranslationX(0f);
                    }
                })
                .start();
        newSection.animate()
                .translationX(0f)
                .setDuration(220L)
                .withEndAction(this::updateHomeNavSelection)
                .start();
        updateHomeNavSelection();
    }

    private void updateBottomNavLabels() {
        boolean textMode = settingsStore != null && "text".equals(settingsStore.getHomeBottomNavStyle());
        setHomeNavText(navBookshelf, textMode ? "书架" : "▦", textMode);
        setHomeNavText(navStats, textMode ? "时长" : "◷", textMode);
        setHomeNavText(navBookmarks, textMode ? "书签" : "★", textMode);
        setHomeNavText(navSettings, textMode ? "设置" : "⚙", textMode);
        updateHomeNavSelection();
    }

    private void setHomeNavText(TextView item, String text, boolean textMode) {
        if (item == null) {
            return;
        }
        item.setText(text);
        item.setTextSize(textMode ? 15f : 22f);
    }

    private void updateHomeNavSelection() {
        styleHomeNavItem(navBookshelf, currentHomePage == PAGE_BOOKSHELF);
        styleHomeNavItem(navStats, currentHomePage == PAGE_STATS);
        styleHomeNavItem(navBookmarks, currentHomePage == PAGE_BOOKMARKS);
        styleHomeNavItem(navSettings, currentHomePage == PAGE_SETTINGS);
    }

    private void styleHomeNavItem(TextView item, boolean selected) {
        if (item == null) {
            return;
        }
        item.setBackgroundResource(selected ? R.drawable.bg_nav_item_active : R.drawable.bg_nav_item_idle);
        item.setTextColor(ThemeModeHelper.resolveColor(
                this,
                selected ? R.color.app_nav_text_active : R.color.app_nav_text_idle
        ));
    }

    private View sectionForPage(int page) {
        if (page == PAGE_STATS) return sectionReadingStats;
        if (page == PAGE_BOOKMARKS) return sectionBookmarks;
        if (page == PAGE_SETTINGS) return sectionHomeSettings;
        return sectionBookshelf;
    }

    private void refreshCurrentHomePage(boolean syncFirst) {
        if (currentHomePage == PAGE_STATS) {
            refreshHomeStatsIfVisible(syncFirst);
        } else if (currentHomePage == PAGE_BOOKMARKS) {
            refreshBookmarksIfVisible();
        } else if (currentHomePage == PAGE_SETTINGS) {
            bindHomeSettingsValues();
        }
    }

    private boolean handleHomeSwipe(MotionEvent event) {
        if (activeHomePages.size() <= 1 || pageContainer == null || loadingLayout == null) {
            return false;
        }
        if (loadingLayout.getVisibility() == View.VISIBLE || isTouchInBottomNavigation(event)) {
            resetHomeSwipe();
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                homeSwipeCandidate = true;
                homeSwipeDragging = false;
                swipeDownX = x;
                swipeDownY = y;
                swipeLastX = x;
                swipeLastEventTime = event.getEventTime();
                swipeVelocityX = 0f;
                pendingSwipePage = -1;
                pendingSwipeDirection = SWIPE_NONE;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!homeSwipeCandidate) {
                    return false;
                }
                float deltaX = x - swipeDownX;
                float deltaY = y - swipeDownY;
                if (!homeSwipeDragging) {
                    if (Math.abs(deltaY) > touchSlop && Math.abs(deltaY) > Math.abs(deltaX)) {
                        resetHomeSwipe();
                        return false;
                    }
                    if (Math.abs(deltaX) <= touchSlop * 1.5f || Math.abs(deltaX) <= Math.abs(deltaY) * 1.2f) {
                        return false;
                    }
                    int targetPage = pageForSwipeDelta(deltaX);
                    if (targetPage < 0) {
                        resetHomeSwipe();
                        return false;
                    }
                    homeSwipeDragging = true;
                    pendingSwipePage = targetPage;
                    pendingSwipeDirection = deltaX < 0 ? 1 : -1;
                    prepareHomeSwipe();
                    MotionEvent cancelEvent = MotionEvent.obtain(event);
                    cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancelEvent);
                    cancelEvent.recycle();
                }
                updateHomeSwipe(deltaX);
                long now = event.getEventTime();
                long elapsed = Math.max(1L, now - swipeLastEventTime);
                swipeVelocityX = (x - swipeLastX) / elapsed;
                swipeLastX = x;
                swipeLastEventTime = now;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (homeSwipeDragging) {
                    finishHomeSwipe(x - swipeDownX);
                    return true;
                }
                resetHomeSwipe();
                return false;
            default:
                return false;
        }
    }

    private boolean isTouchInBottomNavigation(MotionEvent event) {
        return bottomNavigation != null && event.getY() >= bottomNavigation.getTop();
    }

    private int pageForSwipeDelta(float deltaX) {
        int currentIndex = activeHomePages.indexOf(currentHomePage);
        int targetIndex = deltaX < 0 ? currentIndex + 1 : currentIndex - 1;
        if (targetIndex < 0 || targetIndex >= activeHomePages.size()) {
            return -1;
        }
        return activeHomePages.get(targetIndex);
    }

    private void prepareHomeSwipe() {
        View current = sectionForPage(currentHomePage);
        View target = sectionForPage(pendingSwipePage);
        if (current == null || target == null || pageContainer == null) {
            return;
        }
        current.animate().cancel();
        target.animate().cancel();
        current.setVisibility(View.VISIBLE);
        target.setVisibility(View.VISIBLE);
        target.setTranslationX(pendingSwipeDirection * Math.max(pageContainer.getWidth(), 1));
    }

    private void updateHomeSwipe(float deltaX) {
        View current = sectionForPage(currentHomePage);
        View target = sectionForPage(pendingSwipePage);
        if (current == null || target == null || pageContainer == null) {
            return;
        }
        int width = Math.max(pageContainer.getWidth(), 1);
        float clamped = Math.max(-width, Math.min(width, deltaX));
        current.setTranslationX(clamped);
        target.setTranslationX(pendingSwipeDirection * width + clamped);
    }

    private void finishHomeSwipe(float deltaX) {
        View current = sectionForPage(currentHomePage);
        View target = sectionForPage(pendingSwipePage);
        if (current == null || target == null || pageContainer == null || pendingSwipePage < 0) {
            resetHomeSwipe();
            showHomePageImmediate(currentHomePage);
            return;
        }
        int width = Math.max(pageContainer.getWidth(), 1);
        boolean commit = Math.abs(deltaX) > width * 0.24f
                || (Math.abs(swipeVelocityX) > 0.65f && Math.signum(swipeVelocityX) == -pendingSwipeDirection);
        int oldPage = currentHomePage;
        int targetPage = pendingSwipePage;
        if (commit) {
            current.animate()
                    .translationX(-pendingSwipeDirection * width)
                    .setDuration(180L)
                    .withEndAction(() -> {
                        if (currentHomePage != oldPage) {
                            current.setVisibility(View.GONE);
                            current.setTranslationX(0f);
                        }
                    })
                    .start();
            target.animate()
                    .translationX(0f)
                    .setDuration(180L)
                    .withEndAction(this::updateHomeNavSelection)
                    .start();
            currentHomePage = targetPage;
            refreshCurrentHomePage(false);
        } else {
            current.animate().translationX(0f).setDuration(160L).start();
            target.animate()
                    .translationX(pendingSwipeDirection * width)
                    .setDuration(160L)
                    .withEndAction(() -> {
                        target.setVisibility(View.GONE);
                        target.setTranslationX(0f);
                    })
                    .start();
        }
        resetHomeSwipe();
        updateHomeNavSelection();
    }

    private void resetHomeSwipe() {
        homeSwipeCandidate = false;
        homeSwipeDragging = false;
        pendingSwipePage = -1;
        pendingSwipeDirection = SWIPE_NONE;
        swipeVelocityX = 0f;
    }

    private void bindHomeSettingsValues() {
        if (homeReadingTimeTrackingCheck == null) {
            return;
        }
        bindingHomeSettings = true;
        homeReadingTimeTrackingCheck.setChecked(settingsStore.isReadingTimeTrackingEnabled());
        styleToggleButton(homeNavIconsButton, "icons".equals(settingsStore.getHomeBottomNavStyle()));
        styleToggleButton(homeNavTextButton, "text".equals(settingsStore.getHomeBottomNavStyle()));
        bindingHomeSettings = false;
    }

    private void selectHomeBottomNavStyle(String style) {
        settingsStore.setHomeBottomNavStyle(style);
        bindHomeSettingsValues();
        updateBottomNavLabels();
    }

    private void selectHomeStatsPeriod(String period) {
        selectedHomeStatsPeriod = ReadingStatsUtils.normalizePeriodKey(period);
        updateHomeStatsPeriodButtons();
        refreshHomeStatsIfVisible(false);
    }

    private void updateHomeStatsPeriodButtons() {
        styleToggleButton(homeStatsTodayButton, ReadingStatsUtils.PERIOD_TODAY.equals(selectedHomeStatsPeriod));
        styleToggleButton(homeStatsWeekButton, ReadingStatsUtils.PERIOD_WEEK.equals(selectedHomeStatsPeriod));
        styleToggleButton(homeStatsYearButton, ReadingStatsUtils.PERIOD_YEAR.equals(selectedHomeStatsPeriod));
    }

    private void refreshHomeStatsIfVisible(boolean syncFirst) {
        if (currentHomePage != PAGE_STATS || !settingsStore.isReadingTimeTrackingEnabled()) {
            return;
        }
        updateHomeStatsPeriodButtons();
        if (homeStatsStatusText != null) {
            homeStatsStatusText.setText("正在加载本地阅读统计...");
        }
        if (homeStatsTotalText != null) {
            homeStatsTotalText.setText("...");
        }
        executor.execute(() -> {
            ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(
                    selectedHomeStatsPeriod,
                    java.time.ZoneId.systemDefault()
            );
            int totalSeconds = databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null);
            List<ReadingBookStatRecord> records = databaseHelper.getReadingBookStats(range.startDateString(), range.endDateString());
            runOnUiThread(() -> renderHomeStats(totalSeconds, records));
        });
    }

    private void renderHomeStats(int totalSeconds, List<ReadingBookStatRecord> records) {
        if (homeStatsStatusText != null) {
            homeStatsStatusText.setText("当前展示的是本地阅读统计");
        }
        if (homeStatsTotalText != null) {
            homeStatsTotalText.setText(ReadingStatsUtils.formatDuration(totalSeconds));
        }
        if (homeStatsListLayout == null || homeStatsEmptyText == null) {
            return;
        }
        homeStatsListLayout.removeAllViews();
        boolean empty = records == null || records.isEmpty();
        homeStatsEmptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (ReadingBookStatRecord record : records) {
            View row = inflater.inflate(R.layout.item_reading_book_stat, homeStatsListLayout, false);
            TextView titleText = row.findViewById(R.id.text_stat_row_title);
            TextView authorText = row.findViewById(R.id.text_stat_row_author);
            TextView metaText = row.findViewById(R.id.text_stat_row_meta);
            TextView durationText = row.findViewById(R.id.text_stat_row_duration);
            titleText.setText(ReadingStatsUtils.safeBookTitle(record.bookTitle));
            authorText.setText(ReadingStatsUtils.safeBookAuthor(record.bookAuthor));
            metaText.setText(record.localBookId > 0L ? "点击查看本书统计详情" : "当前设备没有这本书的本地副本");
            durationText.setText(ReadingStatsUtils.formatDuration(record.totalDurationSeconds));
            if (record.localBookId > 0L) {
                row.setOnClickListener(v -> {
                    Intent intent = new Intent(this, ReadingStatsActivity.class);
                    intent.putExtra("book_id", record.localBookId);
                    startActivity(intent);
                });
            } else {
                row.setEnabled(false);
                row.setAlpha(0.78f);
            }
            homeStatsListLayout.addView(row);
        }
    }

    private void refreshBookmarksIfVisible() {
        if (currentHomePage != PAGE_BOOKMARKS) {
            return;
        }
        executor.execute(() -> {
            List<BookmarkRecord> bookmarks = databaseHelper.getBookmarks();
            runOnUiThread(() -> renderHomeBookmarks(bookmarks));
        });
    }

    private void renderHomeBookmarks(List<BookmarkRecord> bookmarks) {
        if (homeBookmarksListLayout == null || homeBookmarksEmptyText == null) {
            return;
        }
        homeBookmarksListLayout.removeAllViews();
        boolean empty = bookmarks == null || bookmarks.isEmpty();
        homeBookmarksEmptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }
        for (BookmarkRecord bookmark : bookmarks) {
            homeBookmarksListLayout.addView(createBookmarkRow(bookmark));
        }
    }

    private View createBookmarkRow(BookmarkRecord bookmark) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_app_input);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(rowParams);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openBookmark(bookmark));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(ReadingStatsUtils.safeBookTitle(bookmark.bookTitle));
        title.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_primary));
        title.setTextSize(15f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView meta = new TextView(this);
        meta.setText(String.format(
                Locale.SIMPLIFIED_CHINESE,
                "%s · %.1f%%",
                bookmark.chapterTitle == null || bookmark.chapterTitle.isBlank() ? "未命名章节" : bookmark.chapterTitle,
                bookmark.progressPercent
        ));
        meta.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_secondary));
        meta.setTextSize(13f);
        meta.setMaxLines(1);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView summary = new TextView(this);
        summary.setText(bookmark.summary == null || bookmark.summary.isBlank() ? "无摘要" : bookmark.summary);
        summary.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_secondary));
        summary.setTextSize(13f);
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);

        texts.addView(title);
        texts.addView(meta);
        texts.addView(summary);

        Button deleteButton = new Button(this);
        deleteButton.setText("删除");
        deleteButton.setTextSize(12f);
        deleteButton.setAllCaps(false);
        deleteButton.setMinWidth(0);
        deleteButton.setMinHeight(0);
        deleteButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        deleteButton.setBackgroundResource(R.drawable.bg_app_danger_button);
        deleteButton.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_button_danger_text));
        deleteButton.setOnClickListener(v -> confirmDeleteBookmark(bookmark));

        row.addView(texts);
        row.addView(deleteButton);
        return row;
    }

    private void openBookmark(BookmarkRecord bookmark) {
        executor.execute(() -> {
            BookRecord book = bookmark.bookId > 0L ? databaseHelper.getBook(bookmark.bookId) : null;
            if (book == null) {
                book = databaseHelper.findBookByReadingStatsKey(bookmark.bookIdentity);
            }
            BookRecord finalBook = book;
            runOnUiThread(() -> {
                if (finalBook == null) {
                    showToast("这本书已经不在当前设备的书架中");
                    return;
                }
                Intent intent = new Intent(this, ReaderActivity.class);
                intent.putExtra("book_id", finalBook.id);
                intent.putExtra("bookmark_chapter_order_index", bookmark.chapterOrderIndex);
                intent.putExtra("bookmark_chapter_offset", bookmark.chapterOffset);
                startActivity(intent);
            });
        });
    }

    private void confirmDeleteBookmark(BookmarkRecord bookmark) {
        new AlertDialog.Builder(this)
                .setTitle("删除书签")
                .setMessage("确定删除这个书签吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> executor.execute(() -> {
                    databaseHelper.deleteBookmark(bookmark.id);
                    runOnUiThread(this::refreshBookmarksIfVisible);
                }))
                .show();
    }

    // ==================== Bookshelf ====================

    private void setBookshelfMode(String mode) {
        settingsStore.setBookshelfViewMode(mode);
        applyBookshelfMode();
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

    private void styleToggleButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        styleSelectionButton(button, selected);
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

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private String readableError(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "未知错误";
        }
        return error.getMessage();
    }
}
