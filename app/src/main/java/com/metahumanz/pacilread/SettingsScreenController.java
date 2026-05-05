package com.metahumanz.pacilread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ReplacementRuleRecord;
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager;
import com.metahumanz.pacilread.sync.WebDavBackupManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.tts.MimoTtsClient;
import com.metahumanz.pacilread.tts.SystemTtsClient;
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SettingsScreenController {
    private static final String TAG = "SettingsScreen";
    static final int REQUEST_PICK_BOOK = 3001;

    interface Host {
        void openBookPicker(Intent intent, int requestCode);
        void openReader(long bookId);
        void onSettingsSaved();
        void onLibraryDataRestored();
        void onThemeChanged();
    }

    private static final String[] APP_THEME_KEYS = new String[]{"system", "light", "dark"};
    private static final String[] READER_THEME_KEYS = new String[]{"follow_app", "system", "light", "dark"};
    private static final String[] TTS_ENGINE_KEYS = new String[]{"system", "mimo"};
    private static final String[] TTS_ENGINE_LABELS = new String[]{"系统 TTS", "小米 MiMo"};
    private static final String TTS_TEST_TEXT = "这是一段听书测试，用来确认当前朗读引擎可以正常播放。";
    private static final String[] VOLUME_KEY_ACTION_KEYS = new String[]{"system", "page_up", "page_down"};
    private static final String[] VOLUME_KEY_ACTION_LABELS = new String[]{"系统音量", "上一页", "下一页"};
    private static final String READER_ORIENTATION_SYSTEM = "system";
    private static final String READER_ORIENTATION_PORTRAIT = "portrait";
    private static final String READER_ORIENTATION_LANDSCAPE = "landscape";

    private final Activity activity;
    private final Host host;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final JsonDatabase databaseHelper;
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
    private TextView databaseSizeText;
    private TextView maintenanceSummaryText;
    private Button optimizeDatabaseButton;
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
    private Button readerOrientationSystemButton;
    private Button readerOrientationPortraitButton;
    private Button readerOrientationLandscapeButton;
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
    private CheckBox webDavCleanRemoteOrphansCheck;
    private View webDavSyncOptionsLayout;
    private View ttsMimoKeyLayout;
    private Button transitionFluidButton;
    private Button transitionSimpleButton;
    private TextView transitionMotionDescriptionText;

    private String ruleFilter = "all";
    private LinearLayout rulesListContainer;
    private TextView rulesEmptyText;
    private Button filterAllButton;
    private Button filterGlobalButton;
    private Button filterBookButton;

    private boolean bindingSettingsValues = false;
    private boolean settingsBusy = false;
    private String selectedLightStyleVariant = ThemeModeHelper.LIGHT_STYLE_YUNBAI;
    private String selectedDarkStyleVariant = ThemeModeHelper.DARK_STYLE_YEMU;
    private String selectedReaderOrientationMode = READER_ORIENTATION_SYSTEM;
    private String selectedTransitionMotionMode = TransitionMotionModeHelper.MODE_FLUID;

    SettingsScreenController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.databaseHelper = JsonDatabase.getInstance(activity);
        this.settingsStore = new SettingsStore(activity);
        this.webDavClient = new WebDavClient(settingsStore);
        this.backupManager = new WebDavBackupManager(activity, databaseHelper, settingsStore, webDavClient);
        this.readingStatsSyncManager = new ReadingStatsSyncManager(activity, databaseHelper, settingsStore, webDavClient);
        this.importService = new BookImportService(activity);

        bindViews();
        setupSharedControllers();
        setupThemeSpinners();
        setupStyleVariantButtons();
        setupReaderOrientationButtons();
        setupTransitionMotionButtons();
        bindCurrentValues();
        setupGlassOpacityControl();
        setupAutoSaveListeners();
        setupWebDavSyncButtons();
        refreshBackupLabels();
        setupActionButtons();
        setupRuleFilterButtons();
    }

    void bindCurrentValues() {
        bindingSettingsValues = true;
        autoOpenCheck.setChecked(settingsStore.isAutoOpenLastBook());
        readerMenuAutoHideCheck.setChecked(settingsStore.isReaderMenuAutoHideEnabled());
        bookshelfShowAddEntryCheck.setChecked(settingsStore.isBookshelfAddEntryVisible());
        webDavEnabledCheck.setChecked(settingsStore.isWebDavEnabled());
        if (webDavCleanRemoteOrphansCheck != null) {
            webDavCleanRemoteOrphansCheck.setChecked(settingsStore.isWebDavCleanRemoteOrphansEnabled());
        }
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
        selectedReaderOrientationMode = settingsStore.getReaderOrientationMode();
        updateStyleVariantButtons();
        updateReaderOrientationButtons();
        selectedTransitionMotionMode = TransitionMotionModeHelper.resolveMode(settingsStore);
        updateTransitionMotionButtons();
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
        refreshRulesList();
        bindingSettingsValues = false;
        refreshStatusSummary();
        refreshDatabaseSizeLabel();
        refreshMaintenanceSummary();
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
        settingsStore.setReaderOrientationMode(selectedReaderOrientationMode);
        settingsStore.setTransitionMotionMode(TransitionMotionModeHelper.isFluidAvailable()
                ? selectedTransitionMotionMode
                : TransitionMotionModeHelper.MODE_SIMPLE);
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
        if (webDavCleanRemoteOrphansCheck != null) {
            settingsStore.setWebDavCleanRemoteOrphansEnabled(webDavCleanRemoteOrphansCheck.isChecked());
        }
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
        readerOrientationSystemButton = activity.findViewById(R.id.button_reader_orientation_system);
        readerOrientationPortraitButton = activity.findViewById(R.id.button_reader_orientation_portrait);
        readerOrientationLandscapeButton = activity.findViewById(R.id.button_reader_orientation_landscape);
        ttsEngineSpinner = activity.findViewById(R.id.spinner_tts_engine);
        volumeKeyUpActionSpinner = activity.findViewById(R.id.spinner_volume_key_up_action);
        volumeKeyDownActionSpinner = activity.findViewById(R.id.spinner_volume_key_down_action);
        transitionFluidButton = activity.findViewById(R.id.button_transition_fluid);
        transitionSimpleButton = activity.findViewById(R.id.button_transition_simple);
        transitionMotionDescriptionText = activity.findViewById(R.id.text_transition_motion_description);
        glassOpacitySeekBar = activity.findViewById(R.id.seek_glass_opacity);
        glassOpacityText = activity.findViewById(R.id.text_glass_opacity);
        statusText = activity.findViewById(R.id.text_status);
        databaseSizeText = activity.findViewById(R.id.text_database_size);
        maintenanceSummaryText = activity.findViewById(R.id.text_maintenance_summary);
        optimizeDatabaseButton = activity.findViewById(R.id.button_optimize_database);
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
        webDavCleanRemoteOrphansCheck = activity.findViewById(R.id.check_webdav_clean_remote_orphans);
        ttsMimoKeyLayout = activity.findViewById(R.id.layout_tts_mimo_key);
        rulesListContainer = activity.findViewById(R.id.layout_rules_list);
        rulesEmptyText = activity.findViewById(R.id.text_rules_empty);
        filterAllButton = activity.findViewById(R.id.button_rule_filter_all);
        filterGlobalButton = activity.findViewById(R.id.button_rule_filter_global);
        filterBookButton = activity.findViewById(R.id.button_rule_filter_book);
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
        if (optimizeDatabaseButton != null) {
            optimizeDatabaseButton.setOnClickListener(v -> startDatabaseOptimization());
        }
        fullBackupButton.setOnClickListener(v -> runWebDavAction("正在执行全量备份...", listener -> backupManager.fullBackup(listener)));
        liteBackupButton.setOnClickListener(v -> runWebDavAction("正在执行增量备份...", listener -> backupManager.incrementalBackup(listener)));
        fullRestoreButton.setOnClickListener(v -> confirmRestore("确定要从云端恢复吗？这会恢复公共书架数据和 Android 私有设置；WebDAV 连接信息与阅读统计设备 ID 会保留本机，TTS API Key 会随 Android 设置恢复。", listener -> backupManager.fullRestore(listener), true));
        liteRestoreButton.setOnClickListener(v -> confirmRestore("确定要从云端增量恢复吗？这会合并公共书架数据并恢复 Android 私有设置；WebDAV 连接信息与阅读统计设备 ID 会保留本机，TTS API Key 会随 Android 设置恢复。", listener -> backupManager.incrementalRestore(listener), true));
    }

    private void refreshBackupLabels() {
        if (fullBackupText != null) fullBackupText.setText("全量备份：最近一次 " + backupManager.lastFullBackupLabel());
        if (liteBackupText != null) liteBackupText.setText("增量备份：最近一次 " + backupManager.lastLiteBackupLabel());
    }

    private void refreshDatabaseSizeLabel() {
        if (databaseSizeText == null) return;
        try {
            databaseSizeText.setText("本地存储占用：" + databaseHelper.getDatabaseSizeInfo());
        } catch (Exception ignored) {
        }
    }

    private String readCurrentDatabaseSize() {
        try {
            return databaseHelper.getDatabaseSizeInfo();
        } catch (Exception e) {
            return "未知";
        }
    }

    private void refreshMaintenanceSummary() {
        if (maintenanceSummaryText == null) return;
        try {
            String summary = databaseHelper.getPendingMaintenanceSummary();
            maintenanceSummaryText.setText("维护任务：" + summary);
            if (optimizeDatabaseButton != null) {
                optimizeDatabaseButton.setEnabled(!settingsBusy && databaseHelper.hasPendingMaintenanceWork());
            }
        } catch (Exception ignored) {
            if (optimizeDatabaseButton != null) {
                optimizeDatabaseButton.setEnabled(false);
            }
        }
    }

    private void startDatabaseOptimization() {
        final String currentSize = readCurrentDatabaseSize();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("优化数据库存储");
        builder.setCancelable(false);

        LinearLayout dialogLayout = new LinearLayout(activity);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(24), dp(20), dp(24), dp(12));

        TextView warningText = new TextView(activity);
        warningText.setText("请勿退出应用，优化过程中需要保持数据库锁定。");
        warningText.setTextSize(13f);
        warningText.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_danger));
        warningText.setPadding(0, 0, 0, dp(12));
        dialogLayout.addView(warningText);

        ProgressBar progressBar = new ProgressBar(activity);
        progressBar.setIndeterminate(true);
        progressBar.setPadding(0, 0, 0, dp(12));
        dialogLayout.addView(progressBar);

        TextView phaseText = new TextView(activity);
        phaseText.setText("准备中...");
        phaseText.setTextSize(14f);
        phaseText.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary));
        phaseText.setPadding(0, dp(4), 0, dp(4));
        dialogLayout.addView(phaseText);

        builder.setView(dialogLayout);
        AlertDialog dialog = builder.create();
        dialog.show();

        setBusy(true);
        setAllButtonsEnabled(false);

        executor.execute(() -> {
            databaseHelper.runStorageMaintenanceWithProgress(new JsonDatabase.MaintenanceProgressListener() {
                @Override
                public void onPhaseStart(String phaseName) {
                    activity.runOnUiThread(() -> phaseText.setText("正在" + phaseName + "…"));
                }

                @Override
                public void onPhaseDone(String phaseName) {
                    activity.runOnUiThread(() -> phaseText.setText(phaseName + " 完成"));
                }

                @Override
                public void onAllDone() {
                    final String afterSize = readCurrentDatabaseSize();
                    activity.runOnUiThread(() -> {
                        dialog.dismiss();
                        setBusy(false);
                        setAllButtonsEnabled(true);
                        refreshDatabaseSizeLabel();
                        refreshMaintenanceSummary();
                        showOptimizationResultDialog(currentSize, afterSize);
                    });
                }

                @Override
                public void onError(String errorMessage) {
                    activity.runOnUiThread(() -> {
                        dialog.dismiss();
                        setBusy(false);
                        setAllButtonsEnabled(true);
                        refreshDatabaseSizeLabel();
                        refreshMaintenanceSummary();
                        showToast("优化失败: " + errorMessage);
                    });
                }
            });
        });
    }

    private void showOptimizationResultDialog(String before, String after) {
        AlertDialog resultDialog = new AlertDialog.Builder(activity)
                .setTitle("优化完成")
                .setMessage("优化前：\n" + before + "\n\n优化后：\n" + after)
                .setPositiveButton("知道了", null)
                .create();
        resultDialog.setOnShowListener(unused -> {
            if (resultDialog.getWindow() != null) {
                resultDialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_app_dialog);
            }
        });
        resultDialog.show();
    }

    private int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private void setAllButtonsEnabled(boolean enabled) {
        if (optimizeDatabaseButton != null) optimizeDatabaseButton.setEnabled(enabled && hasPendingMaintenanceWork());
        fullBackupButton.setEnabled(enabled);
        fullRestoreButton.setEnabled(enabled);
        liteBackupButton.setEnabled(enabled);
        liteRestoreButton.setEnabled(enabled);
        testButton.setEnabled(enabled);
        if (ttsTestButton != null) ttsTestButton.setEnabled(enabled);
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

    private void setupReaderOrientationButtons() {
        if (readerOrientationSystemButton != null) {
            readerOrientationSystemButton.setOnClickListener(v -> selectReaderOrientationMode(READER_ORIENTATION_SYSTEM));
        }
        if (readerOrientationPortraitButton != null) {
            readerOrientationPortraitButton.setOnClickListener(v -> selectReaderOrientationMode(READER_ORIENTATION_PORTRAIT));
        }
        if (readerOrientationLandscapeButton != null) {
            readerOrientationLandscapeButton.setOnClickListener(v -> selectReaderOrientationMode(READER_ORIENTATION_LANDSCAPE));
        }
    }

    private void setupTransitionMotionButtons() {
        if (transitionFluidButton != null) {
            transitionFluidButton.setOnClickListener(v -> selectTransitionMotionMode(TransitionMotionModeHelper.MODE_FLUID));
        }
        if (transitionSimpleButton != null) {
            transitionSimpleButton.setOnClickListener(v -> selectTransitionMotionMode(TransitionMotionModeHelper.MODE_SIMPLE));
        }
    }

    private void selectTransitionMotionMode(String mode) {
        if (TransitionMotionModeHelper.MODE_FLUID.equals(mode)
                && !TransitionMotionModeHelper.isFluidAvailable()) {
            selectedTransitionMotionMode = TransitionMotionModeHelper.MODE_SIMPLE;
            updateTransitionMotionButtons();
            return;
        }
        selectedTransitionMotionMode = mode;
        updateTransitionMotionButtons();
        handleSettingsChanged();
    }

    private void updateTransitionMotionButtons() {
        boolean fluidAvailable = TransitionMotionModeHelper.isFluidAvailable();
        if (!fluidAvailable) {
            selectedTransitionMotionMode = TransitionMotionModeHelper.MODE_SIMPLE;
        }
        boolean fluid = TransitionMotionModeHelper.MODE_FLUID.equals(selectedTransitionMotionMode);
        AppUiUtils.styleToggleButton(activity, transitionFluidButton, fluid);
        AppUiUtils.styleToggleButton(activity, transitionSimpleButton, !fluid);
        if (transitionFluidButton != null) {
            transitionFluidButton.setEnabled(fluidAvailable);
            transitionFluidButton.setAlpha(fluidAvailable ? 1f : 0.55f);
        }
        if (transitionMotionDescriptionText != null) {
            transitionMotionDescriptionText.setText(fluidAvailable
                    ? "流动会使用来源贴合和预测返回动画；简洁使用更稳定的基础转场。"
                    : "当前系统低于 Android 14，默认使用简洁转场；流动动效需要 Android 14 及以上的预测返回支持。");
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
        if (webDavCleanRemoteOrphansCheck != null) {
            webDavCleanRemoteOrphansCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsChanged());
        }
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
                + "\nAndroid 设置快照：" + buildWebDavSettingsSnapshotSummary()
                + "\n阅读时长累计：" + (settingsStore.isWebDavSyncReadingStatsEnabled() ? "已启用" : "已关闭")
                + "\n远端清理：" + (settingsStore.isWebDavCleanRemoteOrphansEnabled() ? "备份后执行" : "已关闭"));
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
        if ("mimo".equals(engine)) {
            executor.execute(() -> {
                try {
                    testMimoTtsClient.speak(
                            TTS_TEST_TEXT,
                            settingsStore.getTtsMimoApiKey(),
                            settingsStore.getTtsMimoVoice(),
                            settingsStore.getTtsRate()
                    );
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
        } else {
            SystemTtsClient testClient = getTestSystemTtsClient();
            float rate = settingsStore.getTtsRate();
            testClient.speak(TTS_TEST_TEXT, rate, new SystemTtsClient.SpeakCallback() {
                @Override
                public void onStart() {
                }

                @Override
                public void onDone() {
                    activity.runOnUiThread(() -> {
                        setBusy(false);
                        restoreTtsTestButton();
                        showToast(engineLabel + " 测试朗读完成");
                    });
                }

                @Override
                public void onError(String message) {
                    activity.runOnUiThread(() -> {
                        setBusy(false);
                        restoreTtsTestButton();
                        showToast(engineLabel + " 测试朗读失败: " + message);
                    });
                }
            });
        }
    }

    private void confirmRestore(String message, BackgroundAction action, boolean refreshLibraryOnSuccess) {
        new AlertDialog.Builder(activity)
                .setTitle("确认恢复")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> runWebDavAction("正在恢复数据...", action, refreshLibraryOnSuccess))
                .show();
    }

    private void runWebDavAction(String startMessage, BackgroundAction action) {
        runWebDavAction(startMessage, action, false);
    }

    private void runWebDavAction(String startMessage, BackgroundAction action, boolean refreshLibraryOnSuccess) {
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
                    if (refreshLibraryOnSuccess && host != null) {
                        host.onLibraryDataRestored();
                    }
                    showToast("WebDAV 操作已完成");
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    String message = readableError(error);
                    Log.w(TAG, "WebDAV 操作失败", error);
                    setBusy(false);
                    statusText.setText("操作失败: " + message);
                    showToast("操作失败: " + message);
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
        if (optimizeDatabaseButton != null) optimizeDatabaseButton.setEnabled(!busy && hasPendingMaintenanceWork());
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
        if (webDavCleanRemoteOrphansCheck != null) webDavCleanRemoteOrphansCheck.setEnabled(!busy);
        if (readingStatsController != null) {
            readingStatsController.setBusy(busy);
        }
    }

    private boolean hasPendingMaintenanceWork() {
        try {
            return databaseHelper != null && databaseHelper.hasPendingMaintenanceWork();
        } catch (Exception ignored) {
            return false;
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

    private String readableError(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if ((message == null || message.isBlank()) && error.getCause() != null) {
            message = error.getCause().getMessage();
        }
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
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

    private void selectReaderOrientationMode(String mode) {
        String normalized = SettingsStore.normalizeReaderOrientationMode(mode);
        if (normalized.equals(selectedReaderOrientationMode)) {
            return;
        }
        selectedReaderOrientationMode = normalized;
        updateReaderOrientationButtons();
        handleSettingsChanged();
    }

    private void updateStyleVariantButtons() {
        AppUiUtils.styleToggleButton(activity, lightStyleYaobaiButton, ThemeModeHelper.LIGHT_STYLE_YAOBAI.equals(selectedLightStyleVariant));
        AppUiUtils.styleToggleButton(activity, lightStyleYunbaiButton, ThemeModeHelper.LIGHT_STYLE_YUNBAI.equals(selectedLightStyleVariant));
        AppUiUtils.styleToggleButton(activity, darkStyleYemuButton, ThemeModeHelper.DARK_STYLE_YEMU.equals(selectedDarkStyleVariant));
        AppUiUtils.styleToggleButton(activity, darkStyleJiyeButton, ThemeModeHelper.DARK_STYLE_JIYE.equals(selectedDarkStyleVariant));
    }

    private void updateReaderOrientationButtons() {
        AppUiUtils.styleToggleButton(activity, readerOrientationSystemButton, READER_ORIENTATION_SYSTEM.equals(selectedReaderOrientationMode));
        AppUiUtils.styleToggleButton(activity, readerOrientationPortraitButton, READER_ORIENTATION_PORTRAIT.equals(selectedReaderOrientationMode));
        AppUiUtils.styleToggleButton(activity, readerOrientationLandscapeButton, READER_ORIENTATION_LANDSCAPE.equals(selectedReaderOrientationMode));
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
        return settingsStore.getWebDavDir() + settingsStore.getWebDavSettingsSubdir() + "android-settings.json";
    }

    private void setupRuleFilterButtons() {
        if (filterAllButton == null || filterGlobalButton == null || filterBookButton == null) return;
        filterAllButton.setOnClickListener(v -> selectRuleFilter("all"));
        filterGlobalButton.setOnClickListener(v -> selectRuleFilter("global"));
        filterBookButton.setOnClickListener(v -> selectRuleFilter("book"));
    }

    private void selectRuleFilter(String filter) {
        ruleFilter = filter;
        AppUiUtils.styleToggleButton(activity, filterAllButton, "all".equals(filter));
        AppUiUtils.styleToggleButton(activity, filterGlobalButton, "global".equals(filter));
        AppUiUtils.styleToggleButton(activity, filterBookButton, "book".equals(filter));
        refreshRulesList();
    }

    private void refreshRulesList() {
        if (rulesListContainer == null || rulesEmptyText == null) return;
        rulesListContainer.removeAllViews();
        List<ReplacementRuleRecord> rules = new ArrayList<>(databaseHelper.getRulesMutable());

        Map<Long, String> bookTitles = new HashMap<>();
        for (BookRecord book : databaseHelper.getBooks()) {
            bookTitles.put(book.id, book.title);
        }

        List<ReplacementRuleRecord> filtered = new ArrayList<>();
        for (ReplacementRuleRecord rule : rules) {
            if ("all".equals(ruleFilter)) {
                filtered.add(rule);
            } else if ("global".equals(ruleFilter) && "global".equals(rule.scope)) {
                filtered.add(rule);
            } else if ("book".equals(ruleFilter) && "book".equals(rule.scope)) {
                filtered.add(rule);
            }
        }

        if (filtered.isEmpty()) {
            rulesEmptyText.setVisibility(View.VISIBLE);
            return;
        }
        rulesEmptyText.setVisibility(View.GONE);

        for (int i = 0; i < filtered.size(); i++) {
            ReplacementRuleRecord rule = filtered.get(i);
            View row = buildRuleRow(rule, bookTitles, i > 0);
            row.setTag(rule);
            row.setOnClickListener(v -> {
                ReplacementRuleRecord clickedRule = (ReplacementRuleRecord) v.getTag();
                toggleRuleActive(clickedRule);
            });
            row.setOnLongClickListener(v -> {
                ReplacementRuleRecord clickedRule = (ReplacementRuleRecord) v.getTag();
                confirmDeleteRule(clickedRule);
                return true;
            });
            row.setAlpha(rule.active ? 1.0f : 0.5f);
            rulesListContainer.addView(row);
        }
    }

    private View buildRuleRow(ReplacementRuleRecord rule, Map<Long, String> bookTitles, boolean showDivider) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (showDivider) {
            View divider = new View(activity);
            divider.setBackgroundColor(ThemeModeHelper.resolveColor(activity, R.color.app_border));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            dividerParams.topMargin = dp(10);
            row.addView(divider, 0);
        }

        String replacement = rule.replacement == null || rule.replacement.isEmpty() ? "(删除)" : rule.replacement;
        String replacementSuffix = rule.regex ? "  [正则]" : "";

        TextView patternText = new TextView(activity);
        patternText.setTextSize(13f);
        patternText.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_primary));
        patternText.setText(rule.pattern + "  →  " + replacement + replacementSuffix);
        row.addView(patternText);

        TextView metaText = new TextView(activity);
        metaText.setTextSize(11f);
        metaText.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_secondary));
        String scopeLabel = "global".equals(rule.scope) ? "全局" : "单书";
        StringBuilder metaBuilder = new StringBuilder(scopeLabel);
        if ("book".equals(rule.scope) && rule.bookId != null) {
            String title = bookTitles.get(rule.bookId);
            metaBuilder.append(" - ");
            metaBuilder.append(title != null ? title : "#" + rule.bookId);
        }
        if (rule.regex) {
            metaBuilder.append(" - 正则");
        }
        metaBuilder.append(rule.active ? " - 已启用" : " - 已停用");
        metaText.setText(metaBuilder.toString());
        row.addView(metaText);

        return row;
    }

    private void toggleRuleActive(ReplacementRuleRecord rule) {
        databaseHelper.toggleReplacementRule(rule.id, !rule.active);
        refreshRulesList();
    }

    private void confirmDeleteRule(ReplacementRuleRecord rule) {
        String pattern = rule.pattern.length() > 30 ? rule.pattern.substring(0, 30) + "..." : rule.pattern;
        new AlertDialog.Builder(activity)
                .setTitle("删除替换规则")
                .setMessage("确定要删除规则 \"" + pattern + "\" 吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    databaseHelper.deleteReplacementRule(rule.id);
                    refreshRulesList();
                })
                .show();
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
