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
import android.widget.Toast;
import android.widget.ImageButton;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
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
    private final MimoTtsClient testMimoTtsClient = new MimoTtsClient();
    private SystemTtsClient testSystemTtsClient;

    private TextView statusText;
    private TextView fullBackupText;
    private TextView liteBackupText;

    private CheckBox autoOpenCheck;
    private CheckBox readerMenuAutoHideCheck;
    private CheckBox bookshelfShowAddEntryCheck;
    private CheckBox readingTimeTrackingCheck;
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
    private Button homeNavPresetAutoButton;
    private Button homeNavPresetBottomButton;
    private Button homeNavPresetSidebarButton;
    private Button homeNavPresetCustomButton;
    private View homeNavCustomLayout;
    private Button homeNavPortraitAutoButton;
    private Button homeNavPortraitBottomButton;
    private Button homeNavPortraitSidebarButton;
    private Button homeNavLandscapeAutoButton;
    private Button homeNavLandscapeBottomButton;
    private Button homeNavLandscapeSidebarButton;
    private Button homeSidebarSlideButton;
    private Button homeSidebarFixedButton;
    private Button homeFixedSidebarFullButton;
    private Button homeFixedSidebarIconsButton;
    private Button homeNavIconsButton;
    private Button homeNavTextButton;
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
    private Button statsPeriodTodayButton;
    private Button statsPeriodWeekButton;
    private Button statsPeriodYearButton;
    private Button openReadingStatsButton;
    private View webDavSyncOptionsLayout;
    private View ttsMimoKeyLayout;
    private View readingStatsContentLayout;
    private TextView readingStatsHintText;
    private TextView readingStatsTotalText;
    private boolean bindingSettingsValues = false;
    private boolean settingsBusy = false;
    private boolean homeNavCustomExpanded = false;
    private String selectedReadingStatsPeriod = ReadingStatsUtils.PERIOD_TODAY;
    private String selectedLightStyleVariant = ThemeModeHelper.LIGHT_STYLE_YUNBAI;
    private String selectedDarkStyleVariant = ThemeModeHelper.DARK_STYLE_YEMU;
    private String selectedHomeBottomNavStyle = "icons";
    private String selectedPortraitHomeNavigationMode = "auto";
    private String selectedLandscapeHomeNavigationMode = "auto";
    private String selectedHomeSidebarPresentation = "slide";
    private String selectedHomeFixedSidebarStyle = "full";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Modern Windows 11 transition: incoming page slides forward
        overridePendingTransition(R.anim.activity_slide_forward, R.anim.activity_recede);
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
        readingTimeTrackingCheck = findViewById(R.id.check_reading_time_tracking);
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
        homeNavPresetAutoButton = findViewById(R.id.button_home_nav_preset_auto);
        homeNavPresetBottomButton = findViewById(R.id.button_home_nav_preset_bottom);
        homeNavPresetSidebarButton = findViewById(R.id.button_home_nav_preset_sidebar);
        homeNavPresetCustomButton = findViewById(R.id.button_home_nav_preset_custom);
        homeNavCustomLayout = findViewById(R.id.layout_home_nav_custom);
        homeNavPortraitAutoButton = findViewById(R.id.button_home_nav_portrait_auto);
        homeNavPortraitBottomButton = findViewById(R.id.button_home_nav_portrait_bottom);
        homeNavPortraitSidebarButton = findViewById(R.id.button_home_nav_portrait_sidebar);
        homeNavLandscapeAutoButton = findViewById(R.id.button_home_nav_landscape_auto);
        homeNavLandscapeBottomButton = findViewById(R.id.button_home_nav_landscape_bottom);
        homeNavLandscapeSidebarButton = findViewById(R.id.button_home_nav_landscape_sidebar);
        homeSidebarSlideButton = findViewById(R.id.button_home_sidebar_slide);
        homeSidebarFixedButton = findViewById(R.id.button_home_sidebar_fixed);
        homeFixedSidebarFullButton = findViewById(R.id.button_home_fixed_sidebar_full);
        homeFixedSidebarIconsButton = findViewById(R.id.button_home_fixed_sidebar_icons);
        homeNavIconsButton = findViewById(R.id.button_home_nav_icons);
        homeNavTextButton = findViewById(R.id.button_home_nav_text);
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
        statsPeriodTodayButton = findViewById(R.id.button_stats_period_today);
        statsPeriodWeekButton = findViewById(R.id.button_stats_period_week);
        statsPeriodYearButton = findViewById(R.id.button_stats_period_year);
        openReadingStatsButton = findViewById(R.id.button_open_reading_stats);
        ttsMimoKeyLayout = findViewById(R.id.layout_tts_mimo_key);
        readingStatsContentLayout = findViewById(R.id.layout_reading_stats_content);
        readingStatsHintText = findViewById(R.id.text_reading_stats_hint);
        readingStatsTotalText = findViewById(R.id.text_reading_stats_total);

        setupThemeSpinners();
        setupStyleVariantButtons();
        setupHomeNavigationModeButtons();
        setupHomeNavStyleButtons();
        bindCurrentValues();
        setupGlassOpacityControl();
        setupAutoSaveListeners();
        setupReadingStatsControls();
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
        // Modern Windows 11 transition: outgoing page slides backward, previous page returns from recede
        overridePendingTransition(R.anim.activity_return_from_recede, R.anim.activity_slide_backward);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshReadingStatsSummary(true);
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
        readingTimeTrackingCheck.setChecked(settingsStore.isReadingTimeTrackingEnabled());
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
        selectedHomeBottomNavStyle = settingsStore.getHomeBottomNavStyle();
        selectedPortraitHomeNavigationMode = settingsStore.getPortraitHomeNavigationMode();
        selectedLandscapeHomeNavigationMode = settingsStore.getLandscapeHomeNavigationMode();
        selectedHomeSidebarPresentation = settingsStore.getHomeSidebarPresentation();
        selectedHomeFixedSidebarStyle = settingsStore.getHomeFixedSidebarStyle();
        homeNavCustomExpanded = !selectedPortraitHomeNavigationMode.equals(selectedLandscapeHomeNavigationMode);
        updateStyleVariantButtons();
        updateHomeNavigationModeButtons();
        updateHomeNavStyleButtons();
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
        updateReadingStatsPeriodButtons();
        updateReadingStatsVisibility();
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
        settingsStore.setReadingTimeTrackingEnabled(readingTimeTrackingCheck.isChecked());
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
        settingsStore.setHomeBottomNavStyle(selectedHomeBottomNavStyle);
        settingsStore.setPortraitHomeNavigationMode(selectedPortraitHomeNavigationMode);
        settingsStore.setLandscapeHomeNavigationMode(selectedLandscapeHomeNavigationMode);
        settingsStore.setHomeSidebarPresentation(selectedHomeSidebarPresentation);
        settingsStore.setHomeFixedSidebarStyle(selectedHomeFixedSidebarStyle);
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
        updateReadingStatsVisibility();
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

    private void setupHomeNavigationModeButtons() {
        if (homeNavPresetAutoButton != null) {
            homeNavPresetAutoButton.setOnClickListener(v -> selectHomeNavPreset("auto"));
        }
        if (homeNavPresetBottomButton != null) {
            homeNavPresetBottomButton.setOnClickListener(v -> selectHomeNavPreset("bottom"));
        }
        if (homeNavPresetSidebarButton != null) {
            homeNavPresetSidebarButton.setOnClickListener(v -> selectHomeNavPreset("sidebar"));
        }
        if (homeNavPresetCustomButton != null) {
            homeNavPresetCustomButton.setOnClickListener(v -> {
                homeNavCustomExpanded = true;
                updateHomeNavigationModeButtons();
            });
        }
        if (homeNavPortraitAutoButton != null) {
            homeNavPortraitAutoButton.setOnClickListener(v -> selectHomeNavigationMode(true, "auto"));
        }
        if (homeNavPortraitBottomButton != null) {
            homeNavPortraitBottomButton.setOnClickListener(v -> selectHomeNavigationMode(true, "bottom"));
        }
        if (homeNavPortraitSidebarButton != null) {
            homeNavPortraitSidebarButton.setOnClickListener(v -> selectHomeNavigationMode(true, "sidebar"));
        }
        if (homeNavLandscapeAutoButton != null) {
            homeNavLandscapeAutoButton.setOnClickListener(v -> selectHomeNavigationMode(false, "auto"));
        }
        if (homeNavLandscapeBottomButton != null) {
            homeNavLandscapeBottomButton.setOnClickListener(v -> selectHomeNavigationMode(false, "bottom"));
        }
        if (homeNavLandscapeSidebarButton != null) {
            homeNavLandscapeSidebarButton.setOnClickListener(v -> selectHomeNavigationMode(false, "sidebar"));
        }
        if (homeSidebarSlideButton != null) {
            homeSidebarSlideButton.setOnClickListener(v -> selectHomeSidebarPresentation("slide"));
        }
        if (homeSidebarFixedButton != null) {
            homeSidebarFixedButton.setOnClickListener(v -> selectHomeSidebarPresentation("fixed_wide"));
        }
        if (homeFixedSidebarFullButton != null) {
            homeFixedSidebarFullButton.setOnClickListener(v -> selectHomeFixedSidebarStyle("full"));
        }
        if (homeFixedSidebarIconsButton != null) {
            homeFixedSidebarIconsButton.setOnClickListener(v -> selectHomeFixedSidebarStyle("icons"));
        }
    }

    private void setupHomeNavStyleButtons() {
        if (homeNavIconsButton != null) {
            homeNavIconsButton.setOnClickListener(v -> selectHomeBottomNavStyle("icons"));
        }
        if (homeNavTextButton != null) {
            homeNavTextButton.setOnClickListener(v -> selectHomeBottomNavStyle("text"));
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
        readingTimeTrackingCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleReadingTimeTrackingToggle(isChecked));
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

    private void setupReadingStatsControls() {
        if (statsPeriodTodayButton != null) {
            statsPeriodTodayButton.setOnClickListener(v -> selectReadingStatsPeriod(ReadingStatsUtils.PERIOD_TODAY));
        }
        if (statsPeriodWeekButton != null) {
            statsPeriodWeekButton.setOnClickListener(v -> selectReadingStatsPeriod(ReadingStatsUtils.PERIOD_WEEK));
        }
        if (statsPeriodYearButton != null) {
            statsPeriodYearButton.setOnClickListener(v -> selectReadingStatsPeriod(ReadingStatsUtils.PERIOD_YEAR));
        }
        if (openReadingStatsButton != null) {
            openReadingStatsButton.setOnClickListener(v -> startActivity(new Intent(this, ReadingStatsActivity.class)));
        }
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
        readingTimeTrackingCheck.setEnabled(!busy);
        if (openReadingStatsButton != null) {
            openReadingStatsButton.setEnabled(!busy);
        }
    }

    private void persistSettingsIfReady() {
        if (settingsStore == null || autoOpenCheck == null) {
            return;
        }
        saveSettings();
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
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
        styleToggleButton(button, button.isSelected());
        handleSettingsChanged();
    }

    private void updateWebDavSyncButtons() {
        styleToggleButton(webDavSyncBookshelfButton, settingsStore.isWebDavSyncBookshelfEnabled());
        styleToggleButton(webDavSyncFilesButton, settingsStore.isWebDavSyncFilesEnabled());
        styleToggleButton(webDavSyncUiButton, settingsStore.isWebDavSyncUiSettingsEnabled());
        styleToggleButton(webDavSyncThemesButton, settingsStore.isWebDavSyncThemesEnabled());
        styleToggleButton(webDavSyncBackgroundsButton, settingsStore.isWebDavSyncBackgroundsEnabled());
        styleToggleButton(webDavSyncReadingStatsButton, settingsStore.isWebDavSyncReadingStatsEnabled());
    }

    private void styleToggleButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        button.setBackgroundResource(selected ? R.drawable.bg_app_primary_button : R.drawable.bg_app_outline_button);
        button.setTextColor(ThemeModeHelper.resolveColor(
                this,
                selected ? R.color.app_button_primary_text : R.color.app_button_outline_text
        ));
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

    private void selectHomeBottomNavStyle(String style) {
        String normalized = SettingsStore.normalizeHomeBottomNavStyle(style);
        if (normalized.equals(selectedHomeBottomNavStyle)) {
            return;
        }
        selectedHomeBottomNavStyle = normalized;
        updateHomeNavStyleButtons();
        handleSettingsChanged();
    }

    private void selectHomeNavPreset(String mode) {
        String normalized = SettingsStore.normalizeHomeNavigationMode(mode);
        selectedPortraitHomeNavigationMode = normalized;
        selectedLandscapeHomeNavigationMode = normalized;
        homeNavCustomExpanded = false;
        updateHomeNavigationModeButtons();
        handleSettingsChanged();
    }

    private void selectHomeNavigationMode(boolean portrait, String mode) {
        String normalized = SettingsStore.normalizeHomeNavigationMode(mode);
        if (portrait) {
            selectedPortraitHomeNavigationMode = normalized;
        } else {
            selectedLandscapeHomeNavigationMode = normalized;
        }
        homeNavCustomExpanded = true;
        updateHomeNavigationModeButtons();
        handleSettingsChanged();
    }

    private void selectHomeSidebarPresentation(String presentation) {
        String normalized = SettingsStore.normalizeHomeSidebarPresentation(presentation);
        if (normalized.equals(selectedHomeSidebarPresentation)) {
            return;
        }
        selectedHomeSidebarPresentation = normalized;
        updateHomeNavigationModeButtons();
        handleSettingsChanged();
    }

    private void selectHomeFixedSidebarStyle(String style) {
        String normalized = SettingsStore.normalizeHomeFixedSidebarStyle(style);
        if (normalized.equals(selectedHomeFixedSidebarStyle)) {
            return;
        }
        selectedHomeFixedSidebarStyle = normalized;
        updateHomeNavigationModeButtons();
        handleSettingsChanged();
    }

    private void updateStyleVariantButtons() {
        styleToggleButton(lightStyleYaobaiButton, ThemeModeHelper.LIGHT_STYLE_YAOBAI.equals(selectedLightStyleVariant));
        styleToggleButton(lightStyleYunbaiButton, ThemeModeHelper.LIGHT_STYLE_YUNBAI.equals(selectedLightStyleVariant));
        styleToggleButton(darkStyleYemuButton, ThemeModeHelper.DARK_STYLE_YEMU.equals(selectedDarkStyleVariant));
        styleToggleButton(darkStyleJiyeButton, ThemeModeHelper.DARK_STYLE_JIYE.equals(selectedDarkStyleVariant));
    }

    private void updateHomeNavStyleButtons() {
        styleToggleButton(homeNavIconsButton, "icons".equals(selectedHomeBottomNavStyle));
        styleToggleButton(homeNavTextButton, "text".equals(selectedHomeBottomNavStyle));
    }

    private void updateHomeNavigationModeButtons() {
        boolean allAuto = "auto".equals(selectedPortraitHomeNavigationMode)
                && "auto".equals(selectedLandscapeHomeNavigationMode);
        boolean allBottom = "bottom".equals(selectedPortraitHomeNavigationMode)
                && "bottom".equals(selectedLandscapeHomeNavigationMode);
        boolean allSidebar = "sidebar".equals(selectedPortraitHomeNavigationMode)
                && "sidebar".equals(selectedLandscapeHomeNavigationMode);
        boolean custom = homeNavCustomExpanded || (!allAuto && !allBottom && !allSidebar);
        styleToggleButton(homeNavPresetAutoButton, allAuto && !custom);
        styleToggleButton(homeNavPresetBottomButton, allBottom && !custom);
        styleToggleButton(homeNavPresetSidebarButton, allSidebar && !custom);
        styleToggleButton(homeNavPresetCustomButton, custom);
        if (homeNavCustomLayout != null) {
            homeNavCustomLayout.setVisibility(custom ? View.VISIBLE : View.GONE);
        }
        styleToggleButton(homeNavPortraitAutoButton, "auto".equals(selectedPortraitHomeNavigationMode));
        styleToggleButton(homeNavPortraitBottomButton, "bottom".equals(selectedPortraitHomeNavigationMode));
        styleToggleButton(homeNavPortraitSidebarButton, "sidebar".equals(selectedPortraitHomeNavigationMode));
        styleToggleButton(homeNavLandscapeAutoButton, "auto".equals(selectedLandscapeHomeNavigationMode));
        styleToggleButton(homeNavLandscapeBottomButton, "bottom".equals(selectedLandscapeHomeNavigationMode));
        styleToggleButton(homeNavLandscapeSidebarButton, "sidebar".equals(selectedLandscapeHomeNavigationMode));
        styleToggleButton(homeSidebarSlideButton, "slide".equals(selectedHomeSidebarPresentation));
        styleToggleButton(homeSidebarFixedButton, "fixed_wide".equals(selectedHomeSidebarPresentation));
        styleToggleButton(homeFixedSidebarFullButton, "full".equals(selectedHomeFixedSidebarStyle));
        styleToggleButton(homeFixedSidebarIconsButton, "icons".equals(selectedHomeFixedSidebarStyle));
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

    private void selectReadingStatsPeriod(String periodKey) {
        selectedReadingStatsPeriod = ReadingStatsUtils.normalizePeriodKey(periodKey);
        updateReadingStatsPeriodButtons();
        refreshReadingStatsSummary(false);
    }

    private void updateReadingStatsPeriodButtons() {
        styleToggleButton(statsPeriodTodayButton, ReadingStatsUtils.PERIOD_TODAY.equals(selectedReadingStatsPeriod));
        styleToggleButton(statsPeriodWeekButton, ReadingStatsUtils.PERIOD_WEEK.equals(selectedReadingStatsPeriod));
        styleToggleButton(statsPeriodYearButton, ReadingStatsUtils.PERIOD_YEAR.equals(selectedReadingStatsPeriod));
    }

    private void updateReadingStatsVisibility() {
        if (readingStatsContentLayout == null) {
            return;
        }
        boolean enabled = readingTimeTrackingCheck != null && readingTimeTrackingCheck.isChecked();
        readingStatsContentLayout.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void refreshReadingStatsSummary(boolean syncFirst) {
        updateReadingStatsVisibility();
        if (readingStatsTotalText == null || !settingsStore.isReadingTimeTrackingEnabled()) {
            return;
        }
        readingStatsTotalText.setText("正在加载...");
        executor.execute(() -> {
            String syncError = null;
            if (syncFirst && settingsStore.isWebDavEnabled() && settingsStore.isWebDavSyncReadingStatsEnabled()) {
                try {
                    readingStatsSyncManager.downloadAndMergeReadingStats();
                } catch (Exception error) {
                    syncError = error.getMessage();
                }
            }
            ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(selectedReadingStatsPeriod, java.time.ZoneId.systemDefault());
            int totalSeconds = databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null);
            String finalSyncError = syncError;
            runOnUiThread(() -> {
                if (readingStatsHintText != null) {
                    String label = ReadingStatsUtils.PERIOD_WEEK.equals(selectedReadingStatsPeriod)
                            ? "本周阅读总时长"
                            : ReadingStatsUtils.PERIOD_YEAR.equals(selectedReadingStatsPeriod)
                            ? "本年阅读总时长"
                            : "本日阅读总时长";
                    if (finalSyncError != null && !finalSyncError.isBlank()) {
                        label += " · 云端同步失败";
                    }
                    readingStatsHintText.setText(label);
                }
                readingStatsTotalText.setText(ReadingStatsUtils.formatDuration(totalSeconds));
            });
        });
    }

    private void handleReadingTimeTrackingToggle(boolean enabled) {
        if (bindingSettingsValues || settingsBusy) {
            return;
        }
        if (enabled) {
            saveSettings();
            refreshReadingStatsSummary(true);
            return;
        }
        setBusy(true);
        executor.execute(() -> {
            boolean hasStats = databaseHelper.hasAnyReadingStats();
            runOnUiThread(() -> {
                setBusy(false);
                if (!hasStats) {
                    saveSettings();
                    updateReadingStatsVisibility();
                    return;
                }
                showDisableReadingStatsDialog();
            });
        });
    }

    private void showDisableReadingStatsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("关闭阅读时长记录")
                .setMessage("已有阅读统计数据。你可以只隐藏历史，或同时清空本地与云端统计。")
                .setNegativeButton("取消", (dialog, which) -> revertReadingTrackingToggle(true))
                .setNeutralButton("只隐藏", (dialog, which) -> {
                    saveSettings();
                    updateReadingStatsVisibility();
                })
                .setPositiveButton("清空历史", (dialog, which) -> clearReadingStatsHistory())
                .show();
    }

    private void clearReadingStatsHistory() {
        setBusy(true);
        statusText.setText("正在清理阅读统计...");
        executor.execute(() -> {
            try {
                readingStatsSyncManager.clearRemoteReadingStats();
                databaseHelper.clearReadingStats();
                runOnUiThread(() -> {
                    setBusy(false);
                    saveSettings();
                    updateReadingStatsVisibility();
                    refreshReadingStatsSummary(false);
                    statusText.setText("阅读统计已清空");
                    showToast("已清空阅读统计");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    revertReadingTrackingToggle(true);
                    statusText.setText("清理失败: " + error.getMessage());
                    showToast("清理阅读统计失败");
                });
            }
        });
    }

    private void revertReadingTrackingToggle(boolean checked) {
        bindingSettingsValues = true;
        readingTimeTrackingCheck.setChecked(checked);
        bindingSettingsValues = false;
        updateReadingStatsVisibility();
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
