package com.metahumanz.pacilread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager;
import com.metahumanz.pacilread.sync.WebDavBackupManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.tts.MimoTtsClient;
import com.metahumanz.pacilread.tts.SystemTtsClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SettingsScreenController {
    static final int REQUEST_PICK_BOOK = 3001;

    interface Host {
        void openBookPicker(Intent intent, int requestCode);
        void openReader(long bookId);
        void onSettingsSaved();
        void onThemeChanged();
    }

    private static final String[] APP_THEME_KEYS = new String[]{"system", "light", "dark"};
    private static final String[] READER_THEME_KEYS = new String[]{"follow_app", "system", "light", "dark"};
    private static final String[] TTS_ENGINE_KEYS = new String[]{"system", "mimo"};
    private static final String[] TTS_ENGINE_LABELS = new String[]{"系统 TTS", "小米 MiMo"};
    private static final String TTS_TEST_TEXT = "这是一段听书测试，用来确认当前朗读引擎可以正常播放。";
    private static final String[] VOLUME_KEY_ACTION_KEYS = new String[]{"system", "page_up", "page_down"};
    private static final String[] VOLUME_KEY_ACTION_LABELS = new String[]{"系统音量", "上一页", "下一页"};

    private final Activity activity;
    private final Host host;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ReaderDatabaseHelper databaseHelper;
    private final SettingsStore settingsStore;
    private final WebDavClient webDavClient;
    private final WebDavBackupManager backupManager;
    private final ReadingStatsSyncManager readingStatsSyncManager;
    private final BookImportService importService;
    private final MimoTtsClient testMimoTtsClient = new MimoTtsClient();

    private SettingsHomeNavigationController homeNavigationSettingsController;
    private SettingsReadingStatsController readingStatsController;
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
    private String selectedLightStyleVariant = ThemeModeHelper.LIGHT_STYLE_YUNBAI;
    private String selectedDarkStyleVariant = ThemeModeHelper.DARK_STYLE_YEMU;

    SettingsScreenController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.databaseHelper = ReaderDatabaseHelper.getInstance(activity);
        this.settingsStore = new SettingsStore(activity);
        this.webDavClient = new WebDavClient(settingsStore);
        this.backupManager = new WebDavBackupManager(activity, databaseHelper, settingsStore, webDavClient);
        this.readingStatsSyncManager = new ReadingStatsSyncManager(activity, databaseHelper, settingsStore, webDavClient);
        this.importService = new BookImportService(activity);

        bindViews();
        setupSharedControllers();
        setupThemeSpinners();
        setupStyleVariantButtons();
        bindCurrentValues();
        setupGlassOpacityControl();
        setupAutoSaveListeners();
        setupWebDavSyncButtons();
        refreshBackupLabels();
        setupActionButtons();
    }

    void bindCurrentValues() {
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

    void saveSettings() {
        String previousAppBucket = ThemeModeHelper.getResolvedAppBucket(activity);
        String previousAppStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(activity);
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
        if (host != null) {
            host.onSettingsSaved();
        }
        String nextAppBucket = ThemeModeHelper.getResolvedAppBucket(activity);
        String nextAppStyleVariant = ThemeModeHelper.getResolvedAppStyleVariant(activity);
        if ((!previousAppBucket.equals(nextAppBucket) || !previousAppStyleVariant.equals(nextAppStyleVariant))
                && host != null) {
            host.onThemeChanged();
        }
    }

    void onResume() {
        refreshReadingStatsSummary(true);
    }

    void onPause() {
        persistSettingsIfReady();
    }

    void onDestroy() {
        testMimoTtsClient.cancel();
        if (testSystemTtsClient != null) {
            testSystemTtsClient.shutdown();
        }
        executor.shutdownNow();
    }

    boolean onBookPicked(Uri uri) {
        if (uri == null) {
            return false;
        }
        importBook(uri);
        return true;
    }

    void refreshReadingStatsSummary(boolean syncFirst) {
        if (readingStatsController != null) {
            readingStatsController.refreshSummary(syncFirst);
        }
    }

    private void bindViews() {
        autoOpenCheck = activity.findViewById(R.id.check_auto_open);
        readerMenuAutoHideCheck = activity.findViewById(R.id.check_reader_menu_auto_hide);
        bookshelfShowAddEntryCheck = activity.findViewById(R.id.check_bookshelf_show_add_entry);
        webDavEnabledCheck = activity.findViewById(R.id.check_webdav_enabled);
        urlInput = activity.findViewById(R.id.input_webdav_url);
        dirInput = activity.findViewById(R.id.input_webdav_dir);
        settingsSubdirInput = activity.findViewById(R.id.input_webdav_settings_subdir);
        userInput = activity.findViewById(R.id.input_webdav_user);
        passwordInput = activity.findViewById(R.id.input_webdav_password);
        mimoApiKeyInput = activity.findViewById(R.id.input_mimo_api_key);
        appThemeSpinner = activity.findViewById(R.id.spinner_app_theme_mode);
        readerUiThemeSpinner = activity.findViewById(R.id.spinner_reader_ui_theme_mode);
        lightStyleYaobaiButton = activity.findViewById(R.id.button_light_style_yaobai);
        lightStyleYunbaiButton = activity.findViewById(R.id.button_light_style_yunbai);
        darkStyleYemuButton = activity.findViewById(R.id.button_dark_style_yemu);
        darkStyleJiyeButton = activity.findViewById(R.id.button_dark_style_jiye);
        ttsEngineSpinner = activity.findViewById(R.id.spinner_tts_engine);
        volumeKeyUpActionSpinner = activity.findViewById(R.id.spinner_volume_key_up_action);
        volumeKeyDownActionSpinner = activity.findViewById(R.id.spinner_volume_key_down_action);
        glassOpacitySeekBar = activity.findViewById(R.id.seek_glass_opacity);
        glassOpacityText = activity.findViewById(R.id.text_glass_opacity);
        statusText = activity.findViewById(R.id.text_status);
        fullBackupText = activity.findViewById(R.id.text_backup_full);
        liteBackupText = activity.findViewById(R.id.text_backup_lite);
        fullBackupButton = activity.findViewById(R.id.button_full_backup);
        fullRestoreButton = activity.findViewById(R.id.button_full_restore);
        liteBackupButton = activity.findViewById(R.id.button_lite_backup);
        liteRestoreButton = activity.findViewById(R.id.button_lite_restore);
        testButton = activity.findViewById(R.id.button_test_webdav);
        ttsTestButton = activity.findViewById(R.id.button_test_tts);
        webDavSyncOptionsLayout = activity.findViewById(R.id.layout_webdav_sync_options);
        webDavSyncBookshelfButton = activity.findViewById(R.id.button_webdav_sync_bookshelf);
        webDavSyncFilesButton = activity.findViewById(R.id.button_webdav_sync_files);
        webDavSyncUiButton = activity.findViewById(R.id.button_webdav_sync_ui);
        webDavSyncThemesButton = activity.findViewById(R.id.button_webdav_sync_themes);
        webDavSyncBackgroundsButton = activity.findViewById(R.id.button_webdav_sync_backgrounds);
        webDavSyncReadingStatsButton = activity.findViewById(R.id.button_webdav_sync_reading_stats);
        ttsMimoKeyLayout = activity.findViewById(R.id.layout_tts_mimo_key);
    }

    private void setupSharedControllers() {
        homeNavigationSettingsController = new SettingsHomeNavigationController(activity, settingsStore, this::handleSettingsChanged);
        readingStatsController = new SettingsReadingStatsController(
                activity,
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
                        SettingsScreenController.this.saveSettings();
                    }

                    @Override
                    public void setBusy(boolean busy) {
                        SettingsScreenController.this.setBusy(busy);
                    }

                    @Override
                    public void setStatusText(String text) {
                        if (statusText != null) {
                            statusText.setText(text);
                        }
                    }

                    @Override
                    public void showToast(String text) {
                        SettingsScreenController.this.showToast(text);
                    }
                }
        );
    }

    private void setupActionButtons() {
        testButton.setOnClickListener(v -> testWebDav());
        if (ttsTestButton != null) {
            ttsTestButton.setOnClickListener(v -> testTtsEngine());
        }
        fullBackupButton.setOnClickListener(v -> runWebDavAction("正在执行全量备份...", listener -> backupManager.fullBackup(listener)));
        liteBackupButton.setOnClickListener(v -> runWebDavAction("正在执行增量备份...", listener -> backupManager.incrementalBackup(listener)));
        fullRestoreButton.setOnClickListener(v -> confirmRestore("确定要从云端恢复吗？这将替换您当前的本地书架与设置。", listener -> backupManager.fullRestore(listener)));
        liteRestoreButton.setOnClickListener(v -> confirmRestore("确定要从云端增量恢复吗？这将覆盖您的书架列表与设置，但不会删除现有的本地缓存章节。", listener -> backupManager.incrementalRestore(listener)));
    }

    private void refreshBackupLabels() {
        if (fullBackupText != null) fullBackupText.setText("全量备份：最近一次 " + backupManager.lastFullBackupLabel());
        if (liteBackupText != null) liteBackupText.setText("增量备份：最近一次 " + backupManager.lastLiteBackupLabel());
    }

    private void setupThemeSpinners() {
        ArrayAdapter<String> appThemeAdapter = new ArrayAdapter<>(
                activity,
                R.layout.item_app_spinner_selected,
                new String[]{"跟随系统", "浅色", "深色"}
        );
        appThemeAdapter.setDropDownViewResource(R.layout.item_app_spinner_dropdown);
        appThemeSpinner.setAdapter(appThemeAdapter);

        ArrayAdapter<String> readerThemeAdapter = new ArrayAdapter<>(
                activity,
                R.layout.item_app_spinner_selected,
                new String[]{"跟随应用", "跟随系统", "浅色", "深色"}
        );
        readerThemeAdapter.setDropDownViewResource(R.layout.item_app_spinner_dropdown);
        readerUiThemeSpinner.setAdapter(readerThemeAdapter);

        if (ttsEngineSpinner != null) {
            ArrayAdapter<String> ttsEngineAdapter = new ArrayAdapter<>(
                    activity,
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
                    activity,
                    R.layout.item_app_spinner_selected,
                    VOLUME_KEY_ACTION_LABELS
            );
            volumeKeyUpAdapter.setDropDownViewResource(R.layout.item_app_spinner_dropdown);
            volumeKeyUpActionSpinner.setAdapter(volumeKeyUpAdapter);
            volumeKeyUpActionSpinner.setOnItemSelectedListener(autoSaveSpinnerListener);
        }
        if (volumeKeyDownActionSpinner != null) {
            ArrayAdapter<String> volumeKeyDownAdapter = new ArrayAdapter<>(
                    activity,
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
        if (host != null) {
            host.openBookPicker(intent, REQUEST_PICK_BOOK);
        }
    }

    private void importBook(Uri uri) {
        setBusy(true);
        statusText.setText("正在导入书籍...");
        executor.execute(() -> {
            try {
                long bookId = databaseHelper.insertImportedBook(importService.importFromUri(uri, false));
                activity.runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("书籍已导入");
                    showToast("导入成功");
                    if (host != null) {
                        host.openReader(bookId);
                    }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
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
                activity.runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("连接成功，HTTP " + response.code);
                    showToast("WebDAV 可用");
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
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
                activity.runOnUiThread(() -> {
                    setBusy(false);
                    restoreTtsTestButton();
                    showToast(engineLabel + " 测试朗读完成");
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    setBusy(false);
                    restoreTtsTestButton();
                    showToast(engineLabel + " 测试朗读失败: " + error.getMessage());
                });
            }
        });
    }

    private void confirmRestore(String message, BackgroundAction action) {
        new AlertDialog.Builder(activity)
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
                action.run(status -> activity.runOnUiThread(() -> statusText.setText(status)));
                activity.runOnUiThread(() -> {
                    setBusy(false);
                    bindCurrentValues();
                    refreshBackupLabels();
                    statusText.setText("操作完成，设置已自动保存");
                    showToast("WebDAV 操作已完成");
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
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
        AppUiUtils.showToast(activity, text);
    }

    private synchronized SystemTtsClient getTestSystemTtsClient() {
        if (testSystemTtsClient == null) {
            testSystemTtsClient = new SystemTtsClient(activity);
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
        AppUiUtils.styleToggleButton(activity, button, button.isSelected());
        handleSettingsChanged();
    }

    private void updateWebDavSyncButtons() {
        AppUiUtils.styleToggleButton(activity, webDavSyncBookshelfButton, settingsStore.isWebDavSyncBookshelfEnabled());
        AppUiUtils.styleToggleButton(activity, webDavSyncFilesButton, settingsStore.isWebDavSyncFilesEnabled());
        AppUiUtils.styleToggleButton(activity, webDavSyncUiButton, settingsStore.isWebDavSyncUiSettingsEnabled());
        AppUiUtils.styleToggleButton(activity, webDavSyncThemesButton, settingsStore.isWebDavSyncThemesEnabled());
        AppUiUtils.styleToggleButton(activity, webDavSyncBackgroundsButton, settingsStore.isWebDavSyncBackgroundsEnabled());
        AppUiUtils.styleToggleButton(activity, webDavSyncReadingStatsButton, settingsStore.isWebDavSyncReadingStatsEnabled());
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
        AppUiUtils.styleToggleButton(activity, lightStyleYaobaiButton, ThemeModeHelper.LIGHT_STYLE_YAOBAI.equals(selectedLightStyleVariant));
        AppUiUtils.styleToggleButton(activity, lightStyleYunbaiButton, ThemeModeHelper.LIGHT_STYLE_YUNBAI.equals(selectedLightStyleVariant));
        AppUiUtils.styleToggleButton(activity, darkStyleYemuButton, ThemeModeHelper.DARK_STYLE_YEMU.equals(selectedDarkStyleVariant));
        AppUiUtils.styleToggleButton(activity, darkStyleJiyeButton, ThemeModeHelper.DARK_STYLE_JIYE.equals(selectedDarkStyleVariant));
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
