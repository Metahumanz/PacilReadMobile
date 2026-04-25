package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ImageButton;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.WebDavBackupManager;
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.theme.ThemedActivity;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.tts.MimoTtsClient;
import com.metahumanz.pacilread.tts.SystemTtsClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends ThemedActivity {
    public static final String EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION =
            "com.metahumanz.pacilread.EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION";

    private static final int REQUEST_PICK_BOOK = 3001;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final String[] APP_THEME_KEYS = new String[]{"system", "light", "dark"};
    private static final String[] READER_THEME_KEYS = new String[]{"follow_app", "system", "light", "dark"};
    private static final String[] TTS_ENGINE_KEYS = new String[]{"system", "mimo"};
    private static final String[] TTS_ENGINE_LABELS = new String[]{"系统 TTS", "小米 MiMo"};
    private static final String TTS_TEST_TEXT = "这是一段听书测试，用来确认当前朗读引擎可以正常播放。";
    private static final String[] VOLUME_KEY_ACTION_KEYS = new String[]{"system", "page_up", "page_down"};
    private static final String[] VOLUME_KEY_ACTION_LABELS = new String[]{"系统音量", "上一页", "下一页"};

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private WebDavClient webDavClient;
    private WebDavBackupManager backupManager;
    private ReadingStatsSyncManager readingStatsSyncManager;
    private BookImportService importService;
    private SettingsHomeNavigationController homeNavigationSettingsController;
    private SettingsReadingStatsController readingStatsController;
    private final MimoTtsClient testMimoTtsClient = new MimoTtsClient();
    private SystemTtsClient testSystemTtsClient;

    private TextView statusText;
    private TextView fullBackupText;
    private TextView liteBackupText;

    private CheckBox autoOpenCheck;
    private CheckBox readerMenuAutoHideCheck;
    private CheckBox bookshelfShowAddEntryCheck;
    private CheckBox webDavEnabledCheck;
    private EditText urlInput;
    private EditText dirInput;
    private EditText settingsSubdirInput;
    private EditText userInput;
    private EditText passwordInput;
    private EditText mimoApiKeyInput;
    private Spinner appThemeSpinner;
    private Spinner readerUiThemeSpinner;
    private Button lightStyleYaobaiButton;
    private Button lightStyleYunbaiButton;
    private Button darkStyleYemuButton;
    private Button darkStyleJiyeButton;
    private Spinner ttsEngineSpinner;
    private Spinner volumeKeyUpActionSpinner;
    private Spinner volumeKeyDownActionSpinner;
    private SeekBar glassOpacitySeekBar;
    private TextView glassOpacityText;
    private Button testButton;
    private Button ttsTestButton;
    private Button fullBackupButton;
    private Button fullRestoreButton;
    private Button liteBackupButton;
    private Button liteRestoreButton;
    private Button webDavSyncBookshelfButton;
    private Button webDavSyncFilesButton;
    private Button webDavSyncUiButton;
    private Button webDavSyncThemesButton;
    private Button webDavSyncBackgroundsButton;
    private Button webDavSyncReadingStatsButton;
    private View webDavSyncOptionsLayout;
    private View ttsMimoKeyLayout;
    private boolean bindingSettingsValues = false;
    private boolean settingsBusy = false;
    private boolean homeBottomNavigationTransition = false;
    private String selectedLightStyleVariant = ThemeModeHelper.LIGHT_STYLE_YUNBAI;
    private String selectedDarkStyleVariant = ThemeModeHelper.DARK_STYLE_YEMU;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        homeBottomNavigationTransition = getIntent().getBooleanExtra(EXTRA_HOME_BOTTOM_NAVIGATION_TRANSITION, false);
        if (homeBottomNavigationTransition) {
            overridePendingTransition(R.anim.activity_home_settings_enter, R.anim.activity_home_settings_under_exit);
        } else {
            // Modern Windows 11 transition: incoming page slides forward
            overridePendingTransition(R.anim.activity_slide_forward, R.anim.activity_recede);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        databaseHelper = ReaderDatabaseHelper.getInstance(this);
        settingsStore = new SettingsStore(this);
        webDavClient = new WebDavClient(settingsStore);
        backupManager = new WebDavBackupManager(this, databaseHelper, settingsStore, webDavClient);
        readingStatsSyncManager = new ReadingStatsSyncManager(this, databaseHelper, settingsStore, webDavClient);
        importService = new BookImportService(this);


        autoOpenCheck = findViewById(R.id.check_auto_open);
        readerMenuAutoHideCheck = findViewById(R.id.check_reader_menu_auto_hide);
        bookshelfShowAddEntryCheck = findViewById(R.id.check_bookshelf_show_add_entry);
        webDavEnabledCheck = findViewById(R.id.check_webdav_enabled);
        urlInput = findViewById(R.id.input_webdav_url);
        dirInput = findViewById(R.id.input_webdav_dir);
        settingsSubdirInput = findViewById(R.id.input_webdav_settings_subdir);
        userInput = findViewById(R.id.input_webdav_user);
        passwordInput = findViewById(R.id.input_webdav_password);
        mimoApiKeyInput = findViewById(R.id.input_mimo_api_key);
        appThemeSpinner = findViewById(R.id.spinner_app_theme_mode);
        readerUiThemeSpinner = findViewById(R.id.spinner_reader_ui_theme_mode);
        lightStyleYaobaiButton = findViewById(R.id.button_light_style_yaobai);
        lightStyleYunbaiButton = findViewById(R.id.button_light_style_yunbai);
        darkStyleYemuButton = findViewById(R.id.button_dark_style_yemu);
        darkStyleJiyeButton = findViewById(R.id.button_dark_style_jiye);
        ttsEngineSpinner = findViewById(R.id.spinner_tts_engine);
        volumeKeyUpActionSpinner = findViewById(R.id.spinner_volume_key_up_action);
        volumeKeyDownActionSpinner = findViewById(R.id.spinner_volume_key_down_action);
        glassOpacitySeekBar = findViewById(R.id.seek_glass_opacity);
        glassOpacityText = findViewById(R.id.text_glass_opacity);
        statusText = findViewById(R.id.text_status);
        fullBackupText = findViewById(R.id.text_backup_full);
        liteBackupText = findViewById(R.id.text_backup_lite);
        fullBackupButton = findViewById(R.id.button_full_backup);
        fullRestoreButton = findViewById(R.id.button_full_restore);
        liteBackupButton = findViewById(R.id.button_lite_backup);
        liteRestoreButton = findViewById(R.id.button_lite_restore);
        testButton = findViewById(R.id.button_test_webdav);
        ttsTestButton = findViewById(R.id.button_test_tts);
        webDavSyncOptionsLayout = findViewById(R.id.layout_webdav_sync_options);
        webDavSyncBookshelfButton = findViewById(R.id.button_webdav_sync_bookshelf);
        webDavSyncFilesButton = findViewById(R.id.button_webdav_sync_files);
        webDavSyncUiButton = findViewById(R.id.button_webdav_sync_ui);
        webDavSyncThemesButton = findViewById(R.id.button_webdav_sync_themes);
        webDavSyncBackgroundsButton = findViewById(R.id.button_webdav_sync_backgrounds);
        webDavSyncReadingStatsButton = findViewById(R.id.button_webdav_sync_reading_stats);
        ttsMimoKeyLayout = findViewById(R.id.layout_tts_mimo_key);
        homeNavigationSettingsController = new SettingsHomeNavigationController(this, settingsStore, this::handleSettingsChanged);
        readingStatsController = new SettingsReadingStatsController(
                this,
                databaseHelper,
                settingsStore,
                readingStatsSyncManager,
                executor,
                new SettingsReadingStatsController.Callback() {
                    @Override
                    public boolean isSettingsBusy() {
                        return settingsBusy;
                    }

                    @Override
                    public void saveSettings() {
                        SettingsActivity.this.saveSettings();
                    }

                    @Override
                    public void setBusy(boolean busy) {
                        SettingsActivity.this.setBusy(busy);
                    }

                    @Override
                    public void setStatusText(String text) {
                        if (statusText != null) {
                            statusText.setText(text);
                        }
                    }

                    @Override
                    public void showToast(String text) {
                        SettingsActivity.this.showToast(text);
                    }
                }
        );

        setupThemeSpinners();
        setupStyleVariantButtons();
        bindCurrentValues();
        setupGlassOpacityControl();
        setupAutoSaveListeners();
        setupWebDavSyncButtons();
        refreshBackupLabels();

        testButton.setOnClickListener(v -> testWebDav());
        if (ttsTestButton != null) {
            ttsTestButton.setOnClickListener(v -> testTtsEngine());
        }
        fullBackupButton.setOnClickListener(v -> runWebDavAction("正在执行全量备份...", listener -> backupManager.fullBackup(listener)));
        liteBackupButton.setOnClickListener(v -> runWebDavAction("正在执行增量备份...", listener -> backupManager.incrementalBackup(listener)));
        fullRestoreButton.setOnClickListener(v -> confirmRestore("确定要从云端恢复吗？这将替换您当前的本地书架与设置。", listener -> backupManager.fullRestore(listener)));
        liteRestoreButton.setOnClickListener(v -> confirmRestore("确定要从云端增量恢复吗？这将覆盖您的书架列表与设置，但不会删除现有的本地缓存章节。", listener -> backupManager.incrementalRestore(listener)));

        // Setup back button
        ImageButton backButton = findViewById(R.id.button_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> onBackPressed());
        }
    }

    @Override
    public void onBackPressed() {
        persistSettingsIfReady();
        super.onBackPressed();
        if (homeBottomNavigationTransition) {
            overridePendingTransition(R.anim.activity_home_settings_under_enter, R.anim.activity_home_settings_exit);
        } else {
            // Modern Windows 11 transition: outgoing page slides backward, previous page returns from recede
            overridePendingTransition(R.anim.activity_return_from_recede, R.anim.activity_slide_backward);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (readingStatsController != null) {
            readingStatsController.refreshSummary(true);
        }
    }

    @Override
    protected void onPause() {
        persistSettingsIfReady();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        testMimoTtsClient.cancel();
        if (testSystemTtsClient != null) {
            testSystemTtsClient.shutdown();
        }
        executor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_BOOK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        importBook(data.getData());
    }

    private void bindCurrentValues() {
        bindingSettingsValues = true;
        autoOpenCheck.setChecked(settingsStore.isAutoOpenLastBook());
        readerMenuAutoHideCheck.setChecked(settingsStore.isReaderMenuAutoHideEnabled());
        bookshelfShowAddEntryCheck.setChecked(settingsStore.isBookshelfAddEntryVisible());
        webDavEnabledCheck.setChecked(settingsStore.isWebDavEnabled());
        urlInput.setText(settingsStore.getWebDavUrl());
        dirInput.setText(settingsStore.getWebDavDir());
        settingsSubdirInput.setText(settingsStore.getWebDavSettingsSubdir());
        userInput.setText(settingsStore.getWebDavUser());
        passwordInput.setText(settingsStore.getWebDavPassword());
        mimoApiKeyInput.setText(settingsStore.getTtsMimoApiKey());
        appThemeSpinner.setSelection(indexOf(APP_THEME_KEYS, settingsStore.getAppThemeMode(), 0));
        readerUiThemeSpinner.setSelection(indexOf(READER_THEME_KEYS, settingsStore.getReaderUiThemeMode(), 0));
        selectedLightStyleVariant = settingsStore.getAppLightStyleVariant();
        selectedDarkStyleVariant = settingsStore.getAppDarkStyleVariant();
        updateStyleVariantButtons();
        if (homeNavigationSettingsController != null) {
            homeNavigationSettingsController.bindValues();
        }
        if (ttsEngineSpinner != null) {
            ttsEngineSpinner.setSelection(indexOf(TTS_ENGINE_KEYS, settingsStore.getTtsEngine(), 0));
        }
        if (volumeKeyUpActionSpinner != null) {
            volumeKeyUpActionSpinner.setSelection(indexOf(VOLUME_KEY_ACTION_KEYS, settingsStore.getVolumeKeyUpAction(), 1));
        }
        if (volumeKeyDownActionSpinner != null) {
            volumeKeyDownActionSpinner.setSelection(indexOf(VOLUME_KEY_ACTION_KEYS, settingsStore.getVolumeKeyDownAction(), 2));
        }
        glassOpacitySeekBar.setProgress(settingsStore.getGlassOpacityPercent() - 20);
        updateGlassOpacityLabel(settingsStore.getGlassOpacityPercent());
        updateWebDavSyncButtons();
        if (readingStatsController != null) {
            readingStatsController.bindValues();
        }
        updateTtsSettingsVisibility();
        bindingSettingsValues = false;
        refreshStatusSummary();
    }

    private void refreshBackupLabels() {
        if (fullBackupText != null) fullBackupText.setText("全量备份：最近一次 " + backupManager.lastFullBackupLabel());
        if (liteBackupText != null) liteBackupText.setText("增量备份：最近一次 " + backupManager.lastLiteBackupLabel());
    }

    private void saveSettings() {
        String previousAppBucket = ThemeModeHelper.getResolvedAppBucket(this);
        String previousAppStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(this);
        settingsStore.setAutoOpenLastBook(autoOpenCheck.isChecked());
        settingsStore.setReaderMenuAutoHideEnabled(readerMenuAutoHideCheck.isChecked());
        settingsStore.setBookshelfAddEntryVisible(bookshelfShowAddEntryCheck.isChecked());
        settingsStore.setWebDavEnabled(webDavEnabledCheck.isChecked());
        settingsStore.setWebDavUrl(urlInput.getText().toString());
        settingsStore.setWebDavDir(dirInput.getText().toString());
        settingsStore.setWebDavSettingsSubdir(settingsSubdirInput.getText().toString());
        settingsStore.setWebDavUser(userInput.getText().toString());
        settingsStore.setWebDavPassword(passwordInput.getText().toString());
        if (ttsEngineSpinner != null) {
            settingsStore.setTtsEngine(TTS_ENGINE_KEYS[ttsEngineSpinner.getSelectedItemPosition()]);
        }
        settingsStore.setTtsMimoApiKey(mimoApiKeyInput.getText().toString());
        settingsStore.setAppThemeMode(APP_THEME_KEYS[appThemeSpinner.getSelectedItemPosition()]);
        settingsStore.setReaderUiThemeMode(READER_THEME_KEYS[readerUiThemeSpinner.getSelectedItemPosition()]);
        settingsStore.setAppLightStyleVariant(selectedLightStyleVariant);
        settingsStore.setAppDarkStyleVariant(selectedDarkStyleVariant);
        if (homeNavigationSettingsController != null) {
            homeNavigationSettingsController.saveValues();
        }
        if (readingStatsController != null) {
            readingStatsController.saveValues();
        }
        if (volumeKeyUpActionSpinner != null) {
            settingsStore.setVolumeKeyUpAction(VOLUME_KEY_ACTION_KEYS[volumeKeyUpActionSpinner.getSelectedItemPosition()]);
        }
        if (volumeKeyDownActionSpinner != null) {
            settingsStore.setVolumeKeyDownAction(VOLUME_KEY_ACTION_KEYS[volumeKeyDownActionSpinner.getSelectedItemPosition()]);
        }
        settingsStore.setGlassOpacityPercent(glassOpacitySeekBar.getProgress() + 20);
        settingsStore.setWebDavSyncBookshelfEnabled(webDavSyncBookshelfButton.isSelected());
        settingsStore.setWebDavSyncFilesEnabled(webDavSyncFilesButton.isSelected());
        settingsStore.setWebDavSyncUiSettingsEnabled(webDavSyncUiButton.isSelected());
        settingsStore.setWebDavSyncThemesEnabled(webDavSyncThemesButton.isSelected());
        settingsStore.setWebDavSyncBackgroundsEnabled(webDavSyncBackgroundsButton.isSelected());
        settingsStore.setWebDavSyncReadingStatsEnabled(webDavSyncReadingStatsButton.isSelected());
        updateTtsSettingsVisibility();
        refreshStatusSummary();
        String nextAppBucket = ThemeModeHelper.getResolvedAppBucket(this);
        String nextAppStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(this);
        if (!previousAppBucket.equals(nextAppBucket) || !previousAppStyleVariant.equals(nextAppStyleVariant)) {
            recreate();
        }
    }

    private void setupThemeSpinners() {
        ArrayAdapter<String> appThemeAdapter = new ArrayAdapter<>(
                this,
                R.layout.item_app_spinner_selected,
                new String[]{"跟随系统", "浅色", "深色"}
        );
        appThemeAdapter.setDropDownViewResource(R.layout.item_app_spinner_dropdown);
        appThemeSpinner.setAdapter(appThemeAdapter);

        ArrayAdapter<String> readerThemeAdapter = new ArrayAdapter<>(
                this,
                R.layout.item_app_spinner_selected,
                new String[]{"跟随应用", "跟随系统", "浅色", "深色"}
        );
        readerThemeAdapter.setDropDownViewResource(R.layout.item_app_spinner_dropdown);
        readerUiThemeSpinner.setAdapter(readerThemeAdapter);

        if (ttsEngineSpinner != null) {
            ArrayAdapter<String> ttsEngineAdapter = new ArrayAdapter<>(
                    this,
                    R.layout.item_app_spinner_selected,
                    TTS_ENGINE_LABELS
            );
            ttsEngineAdapter.setDropDownViewResource(R.layout.item_app_spinner_dropdown);
            ttsEngineSpinner.setAdapter(ttsEngineAdapter);
        }

        android.widget.AdapterView.OnItemSelectedListener autoSaveSpinnerListener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                handleSettingsChanged();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        };
        appThemeSpinner.setOnItemSelectedListener(autoSaveSpinnerListener);
        readerUiThemeSpinner.setOnItemSelectedListener(autoSaveSpinnerListener);
        if (ttsEngineSpinner != null) {
            ttsEngineSpinner.setOnItemSelectedListener(autoSaveSpinnerListener);
        }
        if (volumeKeyUpActionSpinner != null) {
            ArrayAdapter<String> volumeKeyUpAdapter = new ArrayAdapter<>(
                    this,
                    R.layout.item_app_spinner_selected,
                    VOLUME_KEY_ACTION_LABELS
            );
            volumeKeyUpAdapter.setDropDownViewResource(R.layout.item_app_spinner_dropdown);
            volumeKeyUpActionSpinner.setAdapter(volumeKeyUpAdapter);
            volumeKeyUpActionSpinner.setOnItemSelectedListener(autoSaveSpinnerListener);
        }
        if (volumeKeyDownActionSpinner != null) {
            ArrayAdapter<String> volumeKeyDownAdapter = new ArrayAdapter<>(
                    this,
                    R.layout.item_app_spinner_selected,
                    VOLUME_KEY_ACTION_LABELS
            );
            volumeKeyDownAdapter.setDropDownViewResource(R.layout.item_app_spinner_dropdown);
            volumeKeyDownActionSpinner.setAdapter(volumeKeyDownAdapter);
            volumeKeyDownActionSpinner.setOnItemSelectedListener(autoSaveSpinnerListener);
        }
    }

    private void setupGlassOpacityControl() {
        glassOpacitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int opacityPercent = progress + 20;
                updateGlassOpacityLabel(opacityPercent);
                if (fromUser) {
                    handleSettingsChanged();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                handleSettingsChanged();
            }
        });
    }

    private void setupStyleVariantButtons() {
        if (lightStyleYaobaiButton != null) {
            lightStyleYaobaiButton.setOnClickListener(v -> selectLightStyleVariant(ThemeModeHelper.LIGHT_STYLE_YAOBAI));
        }
        if (lightStyleYunbaiButton != null) {
            lightStyleYunbaiButton.setOnClickListener(v -> selectLightStyleVariant(ThemeModeHelper.LIGHT_STYLE_YUNBAI));
        }
        if (darkStyleYemuButton != null) {
            darkStyleYemuButton.setOnClickListener(v -> selectDarkStyleVariant(ThemeModeHelper.DARK_STYLE_YEMU));
        }
        if (darkStyleJiyeButton != null) {
            darkStyleJiyeButton.setOnClickListener(v -> selectDarkStyleVariant(ThemeModeHelper.DARK_STYLE_JIYE));
        }
    }

    private void setupAutoSaveListeners() {
        TextWatcher autoSaveTextWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                handleSettingsChanged();
            }
        };
        urlInput.addTextChangedListener(autoSaveTextWatcher);
        dirInput.addTextChangedListener(autoSaveTextWatcher);
        settingsSubdirInput.addTextChangedListener(autoSaveTextWatcher);
        userInput.addTextChangedListener(autoSaveTextWatcher);
        passwordInput.addTextChangedListener(autoSaveTextWatcher);
        mimoApiKeyInput.addTextChangedListener(autoSaveTextWatcher);
        autoOpenCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsChanged());
        readerMenuAutoHideCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsChanged());
        bookshelfShowAddEntryCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsChanged());
        webDavEnabledCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsChanged());
    }

    private void setupWebDavSyncButtons() {
        if (webDavSyncBookshelfButton != null) webDavSyncBookshelfButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncBookshelfButton));
        if (webDavSyncFilesButton != null) webDavSyncFilesButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncFilesButton));
        if (webDavSyncUiButton != null) webDavSyncUiButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncUiButton));
        if (webDavSyncThemesButton != null) webDavSyncThemesButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncThemesButton));
        if (webDavSyncBackgroundsButton != null) webDavSyncBackgroundsButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncBackgroundsButton));
        if (webDavSyncReadingStatsButton != null) webDavSyncReadingStatsButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncReadingStatsButton));
    }

    private void handleSettingsChanged() {
        if (bindingSettingsValues || settingsBusy) {
            return;
        }
        saveSettings();
    }

    private void refreshStatusSummary() {
        if (statusText == null || settingsBusy) {
            return;
        }
        updateWebDavSyncOptionsVisibility();
        if (!settingsStore.isWebDavEnabled()) {
            statusText.setText("当前未启用云同步");
            return;
        }
        statusText.setText("已启用自动进度同步\n手动备份范围：" + buildWebDavScopeSummary()
                + "\n设置快照目录：" + buildWebDavSettingsSnapshotSummary()
                + "\n阅读时长累计：" + (settingsStore.isWebDavSyncReadingStatsEnabled() ? "已启用" : "已关闭"));
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

    private void importBook(Uri uri) {
        setBusy(true);
        statusText.setText("正在导入书籍...");
        executor.execute(() -> {
            try {
                long bookId = databaseHelper.insertImportedBook(importService.importFromUri(uri, false));
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("书籍已导入");
                    showToast("导入成功");
                    Intent intent = new Intent(this, ReaderActivity.class);
                    intent.putExtra("book_id", bookId);
                    startActivity(intent);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("导入失败: " + error.getMessage());
                    showToast("导入失败");
                });
            }
        });
    }

    private void testWebDav() {
        saveSettings();
        setBusy(true);
        statusText.setText("正在探测并初始化目录...");
        executor.execute(() -> {
            try {
                WebDavClient.Response response = webDavClient.probe();
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("连接成功，HTTP " + response.code);
                    showToast("WebDAV 可用");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("连接失败: " + error.getMessage());
                    showToast("WebDAV 探测失败");
                });
            }
        });
    }

    private void testTtsEngine() {
        saveSettings();
        String engine = settingsStore.getTtsEngine();
        if ("mimo".equals(engine) && settingsStore.getTtsMimoApiKey().isBlank()) {
            showToast("请先填写 MiMo API Key");
            return;
        }

        String engineLabel = "mimo".equals(engine) ? "MiMo" : "系统 TTS";
        setBusy(true);
        if (ttsTestButton != null) {
            ttsTestButton.setText("正在测试朗读...");
        }
        executor.execute(() -> {
            try {
                if ("mimo".equals(engine)) {
                    testMimoTtsClient.speak(
                            TTS_TEST_TEXT,
                            settingsStore.getTtsMimoApiKey(),
                            settingsStore.getTtsMimoVoice(),
                            settingsStore.getTtsRate()
                    );
                } else {
                    getTestSystemTtsClient().speak(TTS_TEST_TEXT, settingsStore.getTtsRate());
                }
                runOnUiThread(() -> {
                    setBusy(false);
                    restoreTtsTestButton();
                    showToast(engineLabel + " 测试朗读完成");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    restoreTtsTestButton();
                    showToast(engineLabel + " 测试朗读失败: " + error.getMessage());
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
        saveSettings();
        setBusy(true);
        statusText.setText(startMessage);
        executor.execute(() -> {
            try {
                action.run(status -> runOnUiThread(() -> statusText.setText(status)));
                runOnUiThread(() -> {
                    setBusy(false);
                    bindCurrentValues();
                    refreshBackupLabels();
                    statusText.setText("操作完成，设置已自动保存");
                    showToast("WebDAV 操作已完成");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("操作失败: " + error.getMessage());
                    showToast("操作失败");
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        settingsBusy = busy;
        testButton.setEnabled(!busy);
        if (ttsTestButton != null) {
            ttsTestButton.setEnabled(!busy);
        }
        if (ttsEngineSpinner != null) {
            ttsEngineSpinner.setEnabled(!busy);
        }
        if (mimoApiKeyInput != null) {
            mimoApiKeyInput.setEnabled(!busy);
        }
        fullBackupButton.setEnabled(!busy);
        fullRestoreButton.setEnabled(!busy);
        liteBackupButton.setEnabled(!busy);
        liteRestoreButton.setEnabled(!busy);
        webDavSyncBookshelfButton.setEnabled(!busy);
        webDavSyncFilesButton.setEnabled(!busy);
        webDavSyncUiButton.setEnabled(!busy);
        webDavSyncThemesButton.setEnabled(!busy);
        webDavSyncBackgroundsButton.setEnabled(!busy);
        webDavSyncReadingStatsButton.setEnabled(!busy);
        if (readingStatsController != null) {
            readingStatsController.setBusy(busy);
        }
    }

    private void persistSettingsIfReady() {
        if (settingsStore == null || autoOpenCheck == null) {
            return;
        }
        saveSettings();
    }

    private void showToast(String text) {
        AppUiUtils.showToast(this, text);
    }

    private synchronized SystemTtsClient getTestSystemTtsClient() {
        if (testSystemTtsClient == null) {
            testSystemTtsClient = new SystemTtsClient(this);
        }
        return testSystemTtsClient;
    }

    private void restoreTtsTestButton() {
        if (ttsTestButton != null) {
            ttsTestButton.setText("测试朗读");
        }
    }

    private void updateGlassOpacityLabel(int opacityPercent) {
        glassOpacityText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "阅读菜单与弹窗当前不透明度 %d%%", opacityPercent));
    }

    private void toggleWebDavSyncButton(Button button) {
        button.setSelected(!button.isSelected());
        AppUiUtils.styleToggleButton(this, button, button.isSelected());
        handleSettingsChanged();
    }

    private void updateWebDavSyncButtons() {
        AppUiUtils.styleToggleButton(this, webDavSyncBookshelfButton, settingsStore.isWebDavSyncBookshelfEnabled());
        AppUiUtils.styleToggleButton(this, webDavSyncFilesButton, settingsStore.isWebDavSyncFilesEnabled());
        AppUiUtils.styleToggleButton(this, webDavSyncUiButton, settingsStore.isWebDavSyncUiSettingsEnabled());
        AppUiUtils.styleToggleButton(this, webDavSyncThemesButton, settingsStore.isWebDavSyncThemesEnabled());
        AppUiUtils.styleToggleButton(this, webDavSyncBackgroundsButton, settingsStore.isWebDavSyncBackgroundsEnabled());
        AppUiUtils.styleToggleButton(this, webDavSyncReadingStatsButton, settingsStore.isWebDavSyncReadingStatsEnabled());
    }

    private void selectLightStyleVariant(String styleVariant) {
        if (styleVariant.equals(selectedLightStyleVariant)) {
            return;
        }
        selectedLightStyleVariant = SettingsStore.normalizeAppLightStyleVariant(styleVariant);
        updateStyleVariantButtons();
        handleSettingsChanged();
    }

    private void selectDarkStyleVariant(String styleVariant) {
        if (styleVariant.equals(selectedDarkStyleVariant)) {
            return;
        }
        selectedDarkStyleVariant = SettingsStore.normalizeAppDarkStyleVariant(styleVariant);
        updateStyleVariantButtons();
        handleSettingsChanged();
    }

    private void updateStyleVariantButtons() {
        AppUiUtils.styleToggleButton(this, lightStyleYaobaiButton, ThemeModeHelper.LIGHT_STYLE_YAOBAI.equals(selectedLightStyleVariant));
        AppUiUtils.styleToggleButton(this, lightStyleYunbaiButton, ThemeModeHelper.LIGHT_STYLE_YUNBAI.equals(selectedLightStyleVariant));
        AppUiUtils.styleToggleButton(this, darkStyleYemuButton, ThemeModeHelper.DARK_STYLE_YEMU.equals(selectedDarkStyleVariant));
        AppUiUtils.styleToggleButton(this, darkStyleJiyeButton, ThemeModeHelper.DARK_STYLE_JIYE.equals(selectedDarkStyleVariant));
    }

    private void updateWebDavSyncOptionsVisibility() {
        if (webDavSyncOptionsLayout == null) {
            return;
        }
        webDavSyncOptionsLayout.setVisibility(webDavEnabledCheck != null && webDavEnabledCheck.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void updateTtsSettingsVisibility() {
        if (ttsMimoKeyLayout == null) {
            return;
        }
        ttsMimoKeyLayout.setVisibility("mimo".equals(settingsStore.getTtsEngine()) ? View.VISIBLE : View.GONE);
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

    private String buildWebDavSettingsSnapshotSummary() {
        String subdir = settingsStore.getWebDavSettingsSubdir();
        if (subdir.isBlank()) {
            return "默认共享 settings.json";
        }
        return "settings/" + subdir + "settings.json";
    }

    private interface BackgroundAction {
        void run(WebDavBackupManager.StatusListener listener) throws Exception;
    }

    private int indexOf(String[] values, String target, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                return i;
            }
        }
        return fallback;
    }
}
