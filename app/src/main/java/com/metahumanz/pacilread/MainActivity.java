package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ReaderThemeRecord;
import com.metahumanz.pacilread.reader.JustifiedPageTextView;
import com.metahumanz.pacilread.reader.ReaderThemeConfig;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.WebDavBackupManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.theme.ThemedActivity;
import com.metahumanz.pacilread.ui.GlassUiHelper;
import com.metahumanz.pacilread.util.FileAssetHelper;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ThemedActivity {
    private static final String TAG = "PacilReadMain";
    public static final String EXTRA_START_SECTION = "start_section";
    public static final String START_SECTION_BOOKSHELF = "bookshelf";
    public static final String START_SECTION_PREVIEW = "preview";
    public static final String START_SECTION_SETTINGS = "settings";
    private static final int REQUEST_PICK_BOOK = 1001;
    private static final int REQUEST_PICK_COVER = 1002;
    private static final int REQUEST_PICK_READER_BACKGROUND = 1003;
    private static final int SECTION_BOOKSHELF = 0;
    private static final int SECTION_PREVIEW = 1;
    private static final int SECTION_SETTINGS = 2;
    private static final String VIEW_MODE_CARD = "card";
    private static final String[] APP_THEME_KEYS = new String[]{"system", "light", "dark"};
    private static final String[] READER_THEME_KEYS = new String[]{"follow_app", "system", "light", "dark"};
    private static final String[] READER_FONT_FAMILY_KEYS = new String[]{"serif", "sans-serif", "monospace", "system_default"};
    private static final String[] READER_FONT_FAMILY_LABELS = new String[]{"默认阅读体", "无衬线", "等宽体", "系统默认"};
    private static final int[] READER_FONT_WEIGHT_VALUES = new int[]{250, 400, 700};
    private static final String[] READER_FONT_WEIGHT_LABELS = new String[]{"细体", "标准", "粗体"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<BookRecord> allBooks = new ArrayList<>();

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private BookImportService importService;
    private AppDrawerController drawerController;
    private WebDavClient webDavClient;
    private WebDavBackupManager backupManager;
    private BookListAdapter listAdapter;
    private BookGridAdapter gridAdapter;

    // Common views
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

    // Section containers
    private View sectionBookshelf;
    private View sectionPreview;
    private View sectionSettings;

    // Preview section views
    private TextView previewTheme;
    private ImageView previewReaderBackground;
    private View previewReaderScrim;
    private View previewReaderPage;
    private TextView previewReaderHeading;
    private JustifiedPageTextView previewReaderBody;
    private Spinner previewStyleUiThemeSpinner;
    private Spinner previewStyleFontFamilySpinner;
    private SeekBar previewStyleFontSeekBar;
    private SeekBar previewStyleFontWeightSeekBar;
    private SeekBar previewStyleLineSeekBar;
    private SeekBar previewStyleSideSeekBar;
    private SeekBar previewStyleVerticalSeekBar;
    private TextView previewStyleFontText;
    private TextView previewStyleFontWeightText;
    private TextView previewStyleLineText;
    private TextView previewStyleSideText;
    private TextView previewStyleVerticalText;
    private CheckBox previewStyleKeepScreenOnCheck;
    private CheckBox previewStyleShowTitleCheck;
    private TextView previewStyleBackgroundText;
    private Button previewThemePaperButton;
    private Button previewThemeForestButton;
    private Button previewThemeNightButton;
    private LinearLayout previewStyleCustomThemeList;
    private boolean bindingPreviewStyleValues = false;
    private String previewSelectedReaderTheme = "paper";

    // Settings section views
    private CheckBox autoOpenCheck;
    private CheckBox webDavEnabledCheck;
    private EditText urlInput;
    private EditText dirInput;
    private EditText userInput;
    private EditText passwordInput;
    private EditText mimoApiKeyInput;
    private Spinner appThemeSpinner;
    private Spinner readerUiThemeSpinner;
    private SeekBar glassOpacitySeekBar;
    private TextView glassOpacityText;
    private TextView settingsStatusText;
    private TextView fullBackupText;
    private TextView liteBackupText;
    private Button testButton;

    private Button fullBackupButton;
    private Button fullRestoreButton;
    private Button liteBackupButton;
    private Button liteRestoreButton;
    private Button webDavSyncBookshelfButton;
    private Button webDavSyncFilesButton;
    private Button webDavSyncUiButton;
    private Button webDavSyncThemesButton;
    private Button webDavSyncBackgroundsButton;
    private View webDavSyncOptionsLayout;

    private long pendingCoverBookId = -1L;
    private int currentSection = SECTION_BOOKSHELF;
    private boolean bindingSettingsValues = false;
    private boolean settingsBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        databaseHelper = ReaderDatabaseHelper.getInstance(this);
        settingsStore = new SettingsStore(this);
        importService = new BookImportService(this);
        webDavClient = new WebDavClient(settingsStore);
        backupManager = new WebDavBackupManager(this, databaseHelper, settingsStore, webDavClient);

        bindViews();
        setupAdapters();
        setupInteractions();
        setupSettingsSection();
        setupPreviewStyleSection();
        configureDrawer();
        showSection(resolveStartSection(getIntent()));

        if (savedInstanceState == null && settingsStore.isAutoOpenLastBook()) {
            maybeAutoOpenLastBook();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBooks();
        if (currentSection == SECTION_SETTINGS) {
            bindSettingsValues();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showSection(resolveStartSection(intent));
        if (drawerController != null) {
            drawerController.closeDrawer();
        }
    }

    @Override
    protected void onPause() {
        if (currentSection == SECTION_SETTINGS) {
            persistSettings(true);
        }
        super.onPause();
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
        if (currentSection != SECTION_BOOKSHELF) {
            showSection(SECTION_BOOKSHELF);
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_BOOK) {
            importBook(data.getData());
        } else if (requestCode == REQUEST_PICK_COVER && pendingCoverBookId > 0) {
            attachCover(pendingCoverBookId, data.getData());
        } else if (requestCode == REQUEST_PICK_READER_BACKGROUND) {
            attachPreviewBackground(data.getData());
        }
    }

    // ==================== View Binding ====================

    private void bindViews() {
        mainRoot = findViewById(R.id.main_root);
        sectionBookshelf = findViewById(R.id.section_bookshelf);
        sectionPreview = findViewById(R.id.section_preview);
        sectionSettings = findViewById(R.id.section_settings);
        emptyLayout = findViewById(R.id.layout_empty);
        loadingLayout = findViewById(R.id.layout_loading);
        searchInput = findViewById(R.id.input_search);
        gridBooks = findViewById(R.id.grid_books);
        listBooks = findViewById(R.id.list_books);
        sectionTitle = findViewById(R.id.text_section_title);
        loadingText = findViewById(R.id.text_loading);
        statsText = findViewById(R.id.text_stats);
        emptyTitle = findViewById(R.id.text_empty_title);
        emptyHint = findViewById(R.id.text_empty_hint);
        headerActionButton = findViewById(R.id.button_header_action);
        buttonModeCard = findViewById(R.id.button_mode_card);
        buttonModeList = findViewById(R.id.button_mode_list);
        emptyActionButton = findViewById(R.id.button_empty_action);

        // Preview section
        previewTheme = findViewById(R.id.text_preview_theme);
        previewReaderBackground = findViewById(R.id.image_preview_reader_background);
        previewReaderScrim = findViewById(R.id.view_preview_reader_scrim);
        previewReaderPage = findViewById(R.id.layout_preview_reader_page);
        previewReaderHeading = findViewById(R.id.text_preview_reader_heading);
        previewReaderBody = findViewById(R.id.text_preview_reader_body);
        previewStyleUiThemeSpinner = findViewById(R.id.preview_style_spinner_ui_theme_mode);
        previewStyleFontFamilySpinner = findViewById(R.id.preview_style_spinner_font_family);
        previewStyleFontSeekBar = findViewById(R.id.preview_style_seek_font);
        previewStyleFontWeightSeekBar = findViewById(R.id.preview_style_seek_font_weight);
        previewStyleLineSeekBar = findViewById(R.id.preview_style_seek_line_spacing);
        previewStyleSideSeekBar = findViewById(R.id.preview_style_seek_side_padding);
        previewStyleVerticalSeekBar = findViewById(R.id.preview_style_seek_vertical_padding);
        previewStyleFontText = findViewById(R.id.preview_style_text_font);
        previewStyleFontWeightText = findViewById(R.id.preview_style_text_font_weight);
        previewStyleLineText = findViewById(R.id.preview_style_text_line_spacing);
        previewStyleSideText = findViewById(R.id.preview_style_text_side_padding);
        previewStyleVerticalText = findViewById(R.id.preview_style_text_vertical_padding);
        previewStyleKeepScreenOnCheck = findViewById(R.id.preview_style_check_keep_screen_on);
        previewStyleShowTitleCheck = findViewById(R.id.preview_style_check_show_title);
        previewStyleBackgroundText = findViewById(R.id.preview_style_text_background);
        previewThemePaperButton = findViewById(R.id.preview_style_button_theme_paper);
        previewThemeForestButton = findViewById(R.id.preview_style_button_theme_forest);
        previewThemeNightButton = findViewById(R.id.preview_style_button_theme_night);
        previewStyleCustomThemeList = findViewById(R.id.preview_style_custom_theme_list);

        // Settings section
        autoOpenCheck = findViewById(R.id.check_auto_open);
        webDavEnabledCheck = findViewById(R.id.check_webdav_enabled);
        urlInput = findViewById(R.id.input_webdav_url);
        dirInput = findViewById(R.id.input_webdav_dir);
        userInput = findViewById(R.id.input_webdav_user);
        passwordInput = findViewById(R.id.input_webdav_password);
        mimoApiKeyInput = findViewById(R.id.input_mimo_api_key);
        appThemeSpinner = findViewById(R.id.spinner_app_theme_mode);
        readerUiThemeSpinner = findViewById(R.id.spinner_reader_ui_theme_mode);
        glassOpacitySeekBar = findViewById(R.id.seek_glass_opacity);
        glassOpacityText = findViewById(R.id.text_glass_opacity);
        settingsStatusText = findViewById(R.id.text_status);
        fullBackupText = findViewById(R.id.text_backup_full);
        liteBackupText = findViewById(R.id.text_backup_lite);
        testButton = findViewById(R.id.button_test_webdav);

        fullBackupButton = findViewById(R.id.button_full_backup);
        fullRestoreButton = findViewById(R.id.button_full_restore);
        liteBackupButton = findViewById(R.id.button_lite_backup);
        liteRestoreButton = findViewById(R.id.button_lite_restore);
        webDavSyncOptionsLayout = findViewById(R.id.layout_webdav_sync_options);
        webDavSyncBookshelfButton = findViewById(R.id.button_webdav_sync_bookshelf);
        webDavSyncFilesButton = findViewById(R.id.button_webdav_sync_files);
        webDavSyncUiButton = findViewById(R.id.button_webdav_sync_ui);
        webDavSyncThemesButton = findViewById(R.id.button_webdav_sync_themes);
        webDavSyncBackgroundsButton = findViewById(R.id.button_webdav_sync_backgrounds);
    }

    // ==================== Adapters ====================

    private void setupAdapters() {
        View footerView = getLayoutInflater().inflate(R.layout.item_book_footer, listBooks, false);
        footerView.findViewById(R.id.button_footer_add_book).setOnClickListener(v -> openPicker());
        listBooks.addFooterView(footerView, null, true);
        listAdapter = new BookListAdapter(this);
        gridAdapter = new BookGridAdapter(this);
        listBooks.setAdapter(listAdapter);
        gridBooks.setAdapter(gridAdapter);
    }

    // ==================== Interactions ====================

    private void setupInteractions() {
        headerActionButton.setOnClickListener(v -> {
            if (currentSection == SECTION_BOOKSHELF) {
                openPicker();
            }
        });

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

    // ==================== Settings Section ====================

    private void setupSettingsSection() {
        setupThemeSpinners();
        setupGlassOpacityControl();

        testButton.setOnClickListener(v -> testWebDav());
        fullBackupButton.setOnClickListener(v -> runWebDavAction("正在执行全量备份...", listener -> backupManager.fullBackup(listener)));
        liteBackupButton.setOnClickListener(v -> runWebDavAction("正在执行增量备份...", listener -> backupManager.incrementalBackup(listener)));
        fullRestoreButton.setOnClickListener(v -> confirmRestore("确定要从云端恢复吗？这将替换您当前的本地书架与设置。", listener -> backupManager.fullRestore(listener)));
        liteRestoreButton.setOnClickListener(v -> confirmRestore("确定要从云端增量恢复吗？这将覆盖您的书架列表与设置，但不会删除现有的本地缓存章节。", listener -> backupManager.incrementalRestore(listener)));
        setupWebDavSyncButtons();

        // Auto-save listeners
        TextWatcher autoSaveTextWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { handleSettingsInputChanged(true); }
        };
        urlInput.addTextChangedListener(autoSaveTextWatcher);
        dirInput.addTextChangedListener(autoSaveTextWatcher);
        userInput.addTextChangedListener(autoSaveTextWatcher);
        passwordInput.addTextChangedListener(autoSaveTextWatcher);
        mimoApiKeyInput.addTextChangedListener(autoSaveTextWatcher);

        autoOpenCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsInputChanged(true));
        webDavEnabledCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsInputChanged(true));
    }

    private void setupPreviewStyleSection() {
        if (previewStyleUiThemeSpinner == null) {
            return;
        }
        bindingPreviewStyleValues = true;
        previewStyleUiThemeSpinner.setAdapter(buildSpinnerAdapter(new String[]{"跟随应用", "跟随系统", "浅色", "深色"}));
        previewStyleFontFamilySpinner.setAdapter(buildSpinnerAdapter(READER_FONT_FAMILY_LABELS));

        android.widget.AdapterView.OnItemSelectedListener previewSpinnerListener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                applyPreviewStyleControls();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        };
        previewStyleUiThemeSpinner.setOnItemSelectedListener(previewSpinnerListener);
        previewStyleFontFamilySpinner.setOnItemSelectedListener(previewSpinnerListener);

        SeekBar.OnSeekBarChangeListener previewSeekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updatePreviewStyleLabels();
                if (fromUser) {
                    applyPreviewStyleControls();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyPreviewStyleControls();
            }
        };
        previewStyleFontSeekBar.setOnSeekBarChangeListener(previewSeekListener);
        previewStyleFontWeightSeekBar.setOnSeekBarChangeListener(previewSeekListener);
        previewStyleLineSeekBar.setOnSeekBarChangeListener(previewSeekListener);
        previewStyleSideSeekBar.setOnSeekBarChangeListener(previewSeekListener);
        previewStyleVerticalSeekBar.setOnSeekBarChangeListener(previewSeekListener);

        previewStyleKeepScreenOnCheck.setOnCheckedChangeListener((buttonView, isChecked) -> applyPreviewStyleControls());
        previewStyleShowTitleCheck.setOnCheckedChangeListener((buttonView, isChecked) -> applyPreviewStyleControls());

        previewThemePaperButton.setOnClickListener(v -> {
            previewSelectedReaderTheme = "paper";
            updatePreviewThemeButtons();
            applyPreviewStyleControls();
        });
        previewThemeForestButton.setOnClickListener(v -> {
            previewSelectedReaderTheme = "forest";
            updatePreviewThemeButtons();
            applyPreviewStyleControls();
        });
        previewThemeNightButton.setOnClickListener(v -> {
            previewSelectedReaderTheme = "night";
            updatePreviewThemeButtons();
            applyPreviewStyleControls();
        });

        findViewById(R.id.preview_style_button_pick_background).setOnClickListener(v -> openPreviewBackgroundPicker());
        findViewById(R.id.preview_style_button_clear_background).setOnClickListener(v -> {
            FileAssetHelper.deleteIfExists(settingsStore.getReaderBackgroundPath());
            settingsStore.setReaderBackgroundPath("");
            updatePreviewPanels();
        });
        findViewById(R.id.preview_style_button_save_theme).setOnClickListener(v -> promptSavePreviewTheme());

        bindingPreviewStyleValues = false;
        renderPreviewThemeRows();
        bindPreviewStyleValues();
    }

    private void setupWebDavSyncButtons() {
        webDavSyncBookshelfButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncBookshelfButton));
        webDavSyncFilesButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncFilesButton));
        webDavSyncUiButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncUiButton));
        webDavSyncThemesButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncThemesButton));
        webDavSyncBackgroundsButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncBackgroundsButton));
    }

    private void setupThemeSpinners() {
        ArrayAdapter<String> appThemeAdapter = new ArrayAdapter<>(
                this, R.layout.item_spinner_selected, new String[]{"跟随系统", "浅色", "深色"});
        appThemeAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        appThemeSpinner.setAdapter(appThemeAdapter);

        ArrayAdapter<String> readerThemeAdapter = new ArrayAdapter<>(
                this, R.layout.item_spinner_selected, new String[]{"跟随应用", "跟随系统", "浅色", "深色"});
        readerThemeAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        readerUiThemeSpinner.setAdapter(readerThemeAdapter);

        android.widget.AdapterView.OnItemSelectedListener autoSaveSpinnerListener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { handleSettingsInputChanged(true); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        };
        appThemeSpinner.setOnItemSelectedListener(autoSaveSpinnerListener);
        readerUiThemeSpinner.setOnItemSelectedListener(autoSaveSpinnerListener);
    }

    private void setupGlassOpacityControl() {
        glassOpacitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateGlassOpacityLabel(progress + 20);
                if (fromUser) {
                    handleSettingsInputChanged(false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                handleSettingsInputChanged(false);
            }
        });
    }

    private void bindSettingsValues() {
        bindingSettingsValues = true;
        autoOpenCheck.setChecked(settingsStore.isAutoOpenLastBook());
        webDavEnabledCheck.setChecked(settingsStore.isWebDavEnabled());
        urlInput.setText(settingsStore.getWebDavUrl());
        dirInput.setText(settingsStore.getWebDavDir());
        userInput.setText(settingsStore.getWebDavUser());
        passwordInput.setText(settingsStore.getWebDavPassword());
        mimoApiKeyInput.setText(settingsStore.getTtsMimoApiKey());
        appThemeSpinner.setSelection(indexOf(APP_THEME_KEYS, settingsStore.getAppThemeMode(), 0));
        readerUiThemeSpinner.setSelection(indexOf(READER_THEME_KEYS, settingsStore.getReaderUiThemeMode(), 0));
        glassOpacitySeekBar.setProgress(settingsStore.getGlassOpacityPercent() - 20);
        updateGlassOpacityLabel(settingsStore.getGlassOpacityPercent());
        updateWebDavSyncButtons();
        bindingSettingsValues = false;
        refreshSettingsStatusSummary();
        updatePreviewPanels();
        refreshBackupLabels();
    }

    private void handleSettingsInputChanged(boolean allowRecreate) {
        if (bindingSettingsValues || settingsBusy) {
            return;
        }
        persistSettings(allowRecreate);
    }

    private void persistSettings(boolean allowRecreate) {
        if (settingsStore == null || autoOpenCheck == null) {
            return;
        }
        String previousAppThemeMode = settingsStore.getAppThemeMode();
        settingsStore.setAutoOpenLastBook(autoOpenCheck.isChecked());
        settingsStore.setWebDavEnabled(webDavEnabledCheck.isChecked());
        settingsStore.setWebDavUrl(urlInput.getText().toString());
        settingsStore.setWebDavDir(dirInput.getText().toString());
        settingsStore.setWebDavUser(userInput.getText().toString());
        settingsStore.setWebDavPassword(passwordInput.getText().toString());
        settingsStore.setTtsMimoApiKey(mimoApiKeyInput.getText().toString());
        settingsStore.setAppThemeMode(APP_THEME_KEYS[appThemeSpinner.getSelectedItemPosition()]);
        settingsStore.setReaderUiThemeMode(READER_THEME_KEYS[readerUiThemeSpinner.getSelectedItemPosition()]);
        settingsStore.setGlassOpacityPercent(glassOpacitySeekBar.getProgress() + 20);
        settingsStore.setWebDavSyncBookshelfEnabled(webDavSyncBookshelfButton.isSelected());
        settingsStore.setWebDavSyncFilesEnabled(webDavSyncFilesButton.isSelected());
        settingsStore.setWebDavSyncUiSettingsEnabled(webDavSyncUiButton.isSelected());
        settingsStore.setWebDavSyncThemesEnabled(webDavSyncThemesButton.isSelected());
        settingsStore.setWebDavSyncBackgroundsEnabled(webDavSyncBackgroundsButton.isSelected());
        refreshSettingsStatusSummary();
        updatePreviewPanels();
        updateDrawerStatus();
        if (allowRecreate && !previousAppThemeMode.equals(settingsStore.getAppThemeMode())) {
            recreate();
        }
    }

    private void refreshBackupLabels() {
        fullBackupText.setText("全量备份：最近一次 " + backupManager.lastFullBackupLabel());
        liteBackupText.setText("增量备份：最近一次 " + backupManager.lastLiteBackupLabel());
    }

    private void refreshSettingsStatusSummary() {
        if (settingsStatusText == null || settingsBusy) {
            return;
        }
        updateWebDavSyncOptionsVisibility();
        if (!settingsStore.isWebDavEnabled()) {
            settingsStatusText.setText("当前未启用云同步");
            return;
        }
        settingsStatusText.setText("已启用自动进度同步\n手动备份范围：" + buildWebDavScopeSummary());
    }

    private void testWebDav() {
        persistSettings(true);
        setSettingsBusy(true);
        settingsStatusText.setText("正在探测并初始化目录...");
        executor.execute(() -> {
            try {
                WebDavClient.Response response = webDavClient.probe();
                runOnUiThread(() -> {
                    setSettingsBusy(false);
                    settingsStatusText.setText("连接成功，HTTP " + response.code);
                    showToast("WebDAV 可用");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setSettingsBusy(false);
                    settingsStatusText.setText("连接失败: " + error.getMessage());
                    showToast("WebDAV 探测失败");
                });
            }
        });
    }

    private void confirmRestore(String message, BackgroundAction action) {
        new AlertDialog.Builder(this)
                .setTitle("确认恢复")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> runWebDavAction("正在恢复数据...", action))
                .show();
    }

    private void runWebDavAction(String startMessage, BackgroundAction action) {
        persistSettings(true);
        setSettingsBusy(true);
        settingsStatusText.setText(startMessage);
        executor.execute(() -> {
            try {
                action.run(status -> runOnUiThread(() -> settingsStatusText.setText(status)));
                runOnUiThread(() -> {
                    setSettingsBusy(false);
                    bindSettingsValues();
                    settingsStatusText.setText("操作完成，设置已自动保存");
                    showToast("WebDAV 操作已完成");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setSettingsBusy(false);
                    settingsStatusText.setText("操作失败: " + error.getMessage());
                    showToast("操作失败");
                });
            }
        });
    }

    private void setSettingsBusy(boolean busy) {
        settingsBusy = busy;
        testButton.setEnabled(!busy);
        fullBackupButton.setEnabled(!busy);
        fullRestoreButton.setEnabled(!busy);
        liteBackupButton.setEnabled(!busy);
        liteRestoreButton.setEnabled(!busy);
        webDavSyncBookshelfButton.setEnabled(!busy);
        webDavSyncFilesButton.setEnabled(!busy);
        webDavSyncUiButton.setEnabled(!busy);
        webDavSyncThemesButton.setEnabled(!busy);
        webDavSyncBackgroundsButton.setEnabled(!busy);
    }

    private void updateGlassOpacityLabel(int opacityPercent) {
        glassOpacityText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "阅读菜单与弹窗当前不透明度 %d%%", opacityPercent));
    }

    private void toggleWebDavSyncButton(Button button) {
        button.setSelected(!button.isSelected());
        styleWebDavSyncButton(button, button.isSelected());
        handleSettingsInputChanged(true);
    }

    private void updateWebDavSyncButtons() {
        styleWebDavSyncButton(webDavSyncBookshelfButton, settingsStore.isWebDavSyncBookshelfEnabled());
        styleWebDavSyncButton(webDavSyncFilesButton, settingsStore.isWebDavSyncFilesEnabled());
        styleWebDavSyncButton(webDavSyncUiButton, settingsStore.isWebDavSyncUiSettingsEnabled());
        styleWebDavSyncButton(webDavSyncThemesButton, settingsStore.isWebDavSyncThemesEnabled());
        styleWebDavSyncButton(webDavSyncBackgroundsButton, settingsStore.isWebDavSyncBackgroundsEnabled());
    }

    private void styleWebDavSyncButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setTextColor(getColor(selected ? android.R.color.white : R.color.on_surface));
    }

    private void updateWebDavSyncOptionsVisibility() {
        if (webDavSyncOptionsLayout == null) {
            return;
        }
        webDavSyncOptionsLayout.setVisibility(webDavEnabledCheck != null && webDavEnabledCheck.isChecked() ? View.VISIBLE : View.GONE);
    }

    private String buildWebDavScopeSummary() {
        List<String> items = new ArrayList<>();
        if (settingsStore.isWebDavSyncBookshelfEnabled()) {
            items.add("书架内容");
        }
        if (settingsStore.isWebDavSyncFilesEnabled()) {
            items.add("书籍文件");
        }
        if (settingsStore.isWebDavSyncUiSettingsEnabled()) {
            items.add("界面设置");
        }
        if (settingsStore.isWebDavSyncThemesEnabled()) {
            items.add("阅读主题");
        }
        if (settingsStore.isWebDavSyncBackgroundsEnabled()) {
            items.add("背景图片");
        }
        return items.isEmpty() ? "未选择" : String.join(" / ", items);
    }

    private interface BackgroundAction {
        void run(WebDavBackupManager.StatusListener listener) throws Exception;
    }

    // ==================== Drawer ====================

    private void configureDrawer() {
        drawerController = new AppDrawerController(this, mainRoot, this::handleDrawerDestination);
        drawerController.bindMenuButton(R.id.button_open_drawer);
    }

    private void handleDrawerDestination(int destination) {
        if (destination == AppDrawerController.SECTION_PREVIEW) {
            showSection(SECTION_PREVIEW);
            return;
        }
        if (destination == AppDrawerController.SECTION_SETTINGS) {
            showSection(SECTION_SETTINGS);
            return;
        }
        showSection(SECTION_BOOKSHELF);
    }

    // ==================== Section Switching ====================

    private void showSection(int section) {
        if (currentSection == SECTION_SETTINGS && section != SECTION_SETTINGS) {
            persistSettings(true);
        }
        currentSection = section;
        sectionBookshelf.setVisibility(section == SECTION_BOOKSHELF ? View.VISIBLE : View.GONE);
        sectionPreview.setVisibility(section == SECTION_PREVIEW ? View.VISIBLE : View.GONE);
        sectionSettings.setVisibility(section == SECTION_SETTINGS ? View.VISIBLE : View.GONE);

        switch (section) {
            case SECTION_BOOKSHELF:
                sectionTitle.setText("书架大厅");
                headerActionButton.setText("+ 添加");
                headerActionButton.setVisibility(View.VISIBLE);
                break;
            case SECTION_PREVIEW:
                sectionTitle.setText("排版与预览");
                headerActionButton.setVisibility(View.GONE);
                renderPreviewThemeRows();
                updatePreviewPanels();
                break;
            case SECTION_SETTINGS:
                sectionTitle.setText("偏好设置");
                headerActionButton.setVisibility(View.GONE);
                bindSettingsValues();
                break;
        }
        if (drawerController != null) {
            drawerController.setCurrentSection(section);
        }
        updateEmptyState(currentQuery());
        updateDrawerStatus();
    }

    // ==================== Preview Section ====================

    private void updatePreviewPanels() {
        previewTheme.setText(
                "界面: " + labelForReaderUiTheme(settingsStore.getReaderUiThemeMode())
                        + " · 阅读预设: " + labelForReaderPreset(settingsStore.getReaderTheme())
                        + " · 字体 " + labelForReaderFontFamily(settingsStore.getReaderFontFamily())
                        + " · 字重 " + labelForReaderFontWeight(settingsStore.getReaderFontWeight())
                        + " · 字号 " + Math.round(settingsStore.getFontSizeSp()) + "sp"
        );
        bindPreviewStyleValues();
        updatePreviewReader();
    }

    private void bindPreviewStyleValues() {
        if (previewStyleUiThemeSpinner == null) {
            return;
        }
        bindingPreviewStyleValues = true;
        previewSelectedReaderTheme = settingsStore.getReaderTheme();
        previewStyleUiThemeSpinner.setSelection(indexOf(READER_THEME_KEYS, settingsStore.getReaderUiThemeMode(), 0), false);
        previewStyleFontFamilySpinner.setSelection(indexOf(READER_FONT_FAMILY_KEYS, settingsStore.getReaderFontFamily(), 0), false);
        previewStyleFontSeekBar.setProgress(Math.round(settingsStore.getFontSizeSp()) - 12);
        previewStyleFontWeightSeekBar.setProgress(fontWeightProgress(settingsStore.getReaderFontWeight()));
        previewStyleLineSeekBar.setProgress(Math.round(settingsStore.getLineSpacingExtraSp()));
        previewStyleSideSeekBar.setProgress(settingsStore.getSidePaddingDp() - 8);
        previewStyleVerticalSeekBar.setProgress(settingsStore.getVerticalPaddingDp() - 8);
        previewStyleKeepScreenOnCheck.setChecked(settingsStore.isKeepScreenOn());
        previewStyleShowTitleCheck.setChecked(settingsStore.isChapterTitleVisible());
        previewStyleBackgroundText.setText(currentPreviewBackgroundLabel());
        updatePreviewThemeButtons();
        updatePreviewStyleLabels();
        bindingPreviewStyleValues = false;
    }

    private void applyPreviewStyleControls() {
        if (bindingPreviewStyleValues || settingsBusy) {
            return;
        }
        settingsStore.setReaderUiThemeMode(READER_THEME_KEYS[previewStyleUiThemeSpinner.getSelectedItemPosition()]);
        settingsStore.setReaderFontFamily(READER_FONT_FAMILY_KEYS[previewStyleFontFamilySpinner.getSelectedItemPosition()]);
        settingsStore.setFontSizeSp(previewStyleFontSeekBar.getProgress() + 12f);
        settingsStore.setReaderFontWeight(fontWeightValueForProgress(previewStyleFontWeightSeekBar.getProgress()));
        settingsStore.setLineSpacingExtraSp(previewStyleLineSeekBar.getProgress());
        settingsStore.setSidePaddingDp(previewStyleSideSeekBar.getProgress() + 8);
        settingsStore.setVerticalPaddingDp(previewStyleVerticalSeekBar.getProgress() + 8);
        settingsStore.setKeepScreenOn(previewStyleKeepScreenOnCheck.isChecked());
        settingsStore.setChapterTitleVisible(previewStyleShowTitleCheck.isChecked());
        settingsStore.setReaderTheme(previewSelectedReaderTheme);
        updatePreviewPanels();
    }

    private void updatePreviewReader() {
        ReaderThemePalette palette = ReaderThemePalette.from(settingsStore.getReaderTheme());
        Typeface bodyTypeface = buildReaderTypeface(settingsStore.getReaderFontFamily(), settingsStore.getReaderFontWeight());
        Typeface titleTypeface = buildReaderTypeface(
                settingsStore.getReaderFontFamily(),
                Math.max(600, Math.min(900, settingsStore.getReaderFontWeight() + 200))
        );
        previewReaderHeading.setVisibility(settingsStore.isChapterTitleVisible() ? View.VISIBLE : View.GONE);
        previewReaderHeading.setText("第一章 雨落书页时");
        previewReaderHeading.setIncludeFontPadding(false);
        previewReaderHeading.setTypeface(titleTypeface);
        previewReaderHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, settingsStore.getFontSizeSp() + 2f);
        previewReaderHeading.setTextColor(palette.textColor);
        previewReaderBody.setText("雨点敲在窗沿上时，旧书的纸页也跟着轻轻起伏。"
                + "字距、行距、边距与字重会共同决定这页文字是松弛、沉稳，还是压迫。\n\n"
                + "如果一段文字像现在这样安静地铺开，说明当前排版已经接近真实阅读状态。"
                + "你在这里滑动的每一个选项，都会直接映射到正式阅读页的正文排版、标题占位和背景效果。");
        previewReaderBody.setTypeface(bodyTypeface);
        previewReaderBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, settingsStore.getFontSizeSp());
        previewReaderBody.setTextColor(palette.textColor);
        previewReaderBody.setLineSpacing(settingsStore.getLineSpacingExtraSp(), 1f);
        previewReaderBody.setFullJustifyEnabled(true);
        previewReaderPage.setBackgroundColor(palette.pageColor);
        previewReaderScrim.setBackgroundColor(palette.overlayColor);
        int sidePadding = dp(settingsStore.getSidePaddingDp());
        int verticalPadding = dp(settingsStore.getVerticalPaddingDp());
        previewReaderPage.setPadding(sidePadding, verticalPadding, sidePadding, verticalPadding);
        LinearLayout.LayoutParams bodyParams = (LinearLayout.LayoutParams) previewReaderBody.getLayoutParams();
        bodyParams.topMargin = settingsStore.isChapterTitleVisible() ? dp(14) : 0;
        previewReaderBody.setLayoutParams(bodyParams);
        applyReaderPreviewTheme(palette);
    }

    private void applyReaderPreviewTheme(ReaderThemePalette palette) {
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
            previewReaderBackground.setImageResource(palette.backgroundDrawableRes);
        }
    }

    private void updatePreviewThemeButtons() {
        stylePreviewThemeButton(previewThemePaperButton, "paper".equals(previewSelectedReaderTheme));
        stylePreviewThemeButton(previewThemeForestButton, "forest".equals(previewSelectedReaderTheme));
        stylePreviewThemeButton(previewThemeNightButton, "night".equals(previewSelectedReaderTheme));
    }

    private void stylePreviewThemeButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setBackgroundResource(active ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setTextColor(getColor(active ? android.R.color.white : R.color.on_surface));
        GlassUiHelper.applyToView(this, button, settingsStore.getGlassOpacityPercent());
    }

    private void updatePreviewStyleLabels() {
        previewStyleFontText.setText((previewStyleFontSeekBar.getProgress() + 12) + " sp");
        previewStyleFontWeightText.setText(readerFontWeightLabelForProgress(previewStyleFontWeightSeekBar.getProgress())
                + " (" + fontWeightValueForProgress(previewStyleFontWeightSeekBar.getProgress()) + ")");
        previewStyleLineText.setText(previewStyleLineSeekBar.getProgress() + " px");
        previewStyleSideText.setText((previewStyleSideSeekBar.getProgress() + 8) + " dp");
        previewStyleVerticalText.setText((previewStyleVerticalSeekBar.getProgress() + 8) + " dp");
    }

    private void openPreviewBackgroundPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_READER_BACKGROUND);
    }

    private void attachPreviewBackground(Uri uri) {
        executor.execute(() -> {
            try {
                String oldPath = settingsStore.getReaderBackgroundPath();
                File newFile = FileAssetHelper.copyUriToFolder(this, uri, "backgrounds", "reader_bg");
                if (oldPath != null && !oldPath.isBlank()) {
                    FileAssetHelper.deleteIfExists(oldPath);
                }
                settingsStore.setReaderBackgroundPath(newFile.getAbsolutePath());
                runOnUiThread(this::updatePreviewPanels);
            } catch (Exception error) {
                runOnUiThread(() -> showToast("设置背景失败: " + error.getMessage()));
            }
        });
    }

    private String currentPreviewBackgroundLabel() {
        String path = settingsStore.getReaderBackgroundPath();
        if (path == null || path.isBlank()) {
            return "当前背景：使用" + labelForReaderPreset(settingsStore.getReaderTheme()) + "内置壁纸";
        }
        return "当前背景：" + new File(path).getName();
    }

    private void renderPreviewThemeRows() {
        if (previewStyleCustomThemeList == null) {
            return;
        }
        previewStyleCustomThemeList.removeAllViews();
        executor.execute(() -> {
            List<ReaderThemeRecord> themes = databaseHelper.getCustomThemes();
            runOnUiThread(() -> {
                if (previewStyleCustomThemeList == null) {
                    return;
                }
                previewStyleCustomThemeList.removeAllViews();
                for (ReaderThemeRecord theme : themes) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    Button applyButton = new Button(this);
                    applyButton.setText(theme.name);
                    applyButton.setAllCaps(false);
                    applyButton.setBackgroundResource(R.drawable.bg_outline_button);
                    applyButton.setTextColor(getColor(R.color.primary));
                    GlassUiHelper.applyToView(this, applyButton, settingsStore.getGlassOpacityPercent());
                    LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    row.addView(applyButton, applyParams);

                    Button deleteButton = new Button(this);
                    deleteButton.setText("删除");
                    deleteButton.setAllCaps(false);
                    deleteButton.setBackgroundResource(R.drawable.bg_danger_button);
                    LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    deleteParams.leftMargin = dp(8);
                    row.addView(deleteButton, deleteParams);

                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    rowParams.bottomMargin = dp(8);
                    row.setLayoutParams(rowParams);

                    applyButton.setOnClickListener(v -> {
                        try {
                            ReaderThemeConfig.apply(settingsStore, new JSONObject(theme.configJson));
                            previewSelectedReaderTheme = settingsStore.getReaderTheme();
                            bindPreviewStyleValues();
                            updatePreviewPanels();
                        } catch (Exception error) {
                            showToast("主题配置损坏");
                        }
                    });
                    deleteButton.setOnClickListener(v -> executor.execute(() -> {
                        databaseHelper.deleteCustomTheme(theme.id);
                        runOnUiThread(this::renderPreviewThemeRows);
                    }));
                    previewStyleCustomThemeList.addView(row);
                }
            });
        });
    }

    private void promptSavePreviewTheme() {
        EditText input = new EditText(this);
        input.setHint("主题名称");
        new AlertDialog.Builder(this)
                .setTitle("保存当前主题")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        showToast("请输入主题名称");
                        return;
                    }
                    executor.execute(() -> {
                        databaseHelper.saveCustomTheme(name, ReaderThemeConfig.export(settingsStore).toString());
                        runOnUiThread(this::renderPreviewThemeRows);
                    });
                })
                .show();
    }

    private int fontWeightProgress(int weight) {
        for (int i = 0; i < READER_FONT_WEIGHT_VALUES.length; i++) {
            if (READER_FONT_WEIGHT_VALUES[i] == weight) {
                return i;
            }
        }
        return 1;
    }

    private int fontWeightValueForProgress(int progress) {
        int safeProgress = clamp(progress, 0, READER_FONT_WEIGHT_VALUES.length - 1);
        return READER_FONT_WEIGHT_VALUES[safeProgress];
    }

    private String readerFontWeightLabelForProgress(int progress) {
        int safeProgress = clamp(progress, 0, READER_FONT_WEIGHT_LABELS.length - 1);
        return READER_FONT_WEIGHT_LABELS[safeProgress];
    }

    private Typeface buildReaderTypeface(String familyKey, int weight) {
        Typeface familyTypeface;
        switch (familyKey) {
            case "sans-serif":
                familyTypeface = Typeface.SANS_SERIF;
                break;
            case "monospace":
                familyTypeface = Typeface.MONOSPACE;
                break;
            case "system_default":
                familyTypeface = Typeface.DEFAULT;
                break;
            case "serif":
            default:
                familyTypeface = Typeface.SERIF;
                break;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(familyTypeface, weight, false);
        }
        return Typeface.create(familyTypeface, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
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
        if (currentSection == SECTION_BOOKSHELF && !shouldShowBookshelfEmptyState(currentQuery())) {
            gridBooks.setVisibility(usingCardMode ? View.VISIBLE : View.GONE);
            listBooks.setVisibility(usingCardMode ? View.GONE : View.VISIBLE);
        }
    }

    private void styleSelectionButton(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setTextColor(getColor(selected ? android.R.color.white : R.color.on_surface));
    }

    private void refreshBooks() {
        executor.execute(() -> {
            try {
                List<BookRecord> books = databaseHelper.getBooks();
                runOnUiThread(() -> {
                    allBooks.clear();
                    allBooks.addAll(books);
                    applyFilter(currentQuery());
                });
            } catch (Exception error) {
                Log.e(TAG, "Failed to refresh bookshelf", error);
                runOnUiThread(() -> showToast("加载书架失败: " + readableError(error)));
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
        updateStats(filtered);
        updateEmptyState(query);
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

    private void updateStats(List<BookRecord> filtered) {
        String label = String.format(
                Locale.SIMPLIFIED_CHINESE,
                "共 %d 本书籍",
                filtered.size()
        );
        statsText.setText(label);
    }

    private void updateDrawerStatus() {
        String status = String.format(
                Locale.SIMPLIFIED_CHINESE,
                "已导入 %d 本书\n%s · %s",
                allBooks.size(),
                isCardMode() ? "网格书架" : "列表书架",
                labelForAppTheme(settingsStore.getAppThemeMode())
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
                    if (drawerController != null) {
                        drawerController.closeDrawer();
                    }
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

    // ==================== Navigation Helpers ====================

    public static Intent createSectionIntent(Context context, String startSection) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(EXTRA_START_SECTION, startSection);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    private int resolveStartSection(Intent intent) {
        if (intent == null) {
            return SECTION_BOOKSHELF;
        }
        String startSection = intent.getStringExtra(EXTRA_START_SECTION);
        if (START_SECTION_PREVIEW.equals(startSection)) {
            return SECTION_PREVIEW;
        }
        if (START_SECTION_SETTINGS.equals(startSection)) {
            return SECTION_SETTINGS;
        }
        return SECTION_BOOKSHELF;
    }

    private void maybeAutoOpenLastBook() {
        executor.execute(() -> {
            try {
                long bookId = databaseHelper.getMostRecentBookId();
                if (bookId > 0) {
                    runOnUiThread(() -> openBook(bookId));
                }
            } catch (Exception error) {
                Log.e(TAG, "Failed to resolve last opened book", error);
            }
        });
    }

    // ==================== UI Helpers ====================

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

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private ArrayAdapter<String> buildSpinnerAdapter(String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner_selected, items);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        return adapter;
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

    private String labelForReaderFontFamily(String value) {
        if ("sans-serif".equals(value)) {
            return "无衬线";
        }
        if ("monospace".equals(value)) {
            return "等宽体";
        }
        if ("system_default".equals(value)) {
            return "系统默认";
        }
        return "默认阅读体";
    }

    private String labelForReaderFontWeight(int value) {
        if (value <= 325) {
            return "细体";
        }
        if (value >= 550) {
            return "粗体";
        }
        return "标准";
    }

    private int indexOf(String[] values, String target, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return fallback;
    }

    private static class ReaderThemePalette {
        final int pageColor;
        final int textColor;
        final int overlayColor;
        final int backgroundDrawableRes;

        private ReaderThemePalette(int pageColor, int textColor, int overlayColor, int backgroundDrawableRes) {
            this.pageColor = pageColor;
            this.textColor = textColor;
            this.overlayColor = overlayColor;
            this.backgroundDrawableRes = backgroundDrawableRes;
        }

        private static ReaderThemePalette from(String key) {
            if ("forest".equals(key)) {
                return new ReaderThemePalette(
                        0xFFEAF4E6,
                        0xFF2A4B2A,
                        0xB8EEF6E9,
                        R.drawable.theme_bg_forest
                );
            }
            if ("night".equals(key)) {
                return new ReaderThemePalette(
                        0xFF172033,
                        0xFFE2E8F0,
                        0xCC0A0F17,
                        R.drawable.theme_bg_night
                );
            }
            return new ReaderThemePalette(
                    0xFFF7F0E1,
                    0xFF5C4B37,
                    0xA6FFF8ED,
                    R.drawable.theme_bg_paper
            );
        }
    }
}
