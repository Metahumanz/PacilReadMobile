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
import android.view.MenuItem;

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.WebDavBackupManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.theme.ThemedActivity;

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
    private static final String[] VOLUME_KEY_ACTION_KEYS = new String[]{"system", "page_up", "page_down"};
    private static final String[] VOLUME_KEY_ACTION_LABELS = new String[]{"系统音量", "上一页", "下一页"};

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private WebDavClient webDavClient;
    private WebDavBackupManager backupManager;
    private BookImportService importService;

    private TextView statusText;
    private TextView fullBackupText;
    private TextView liteBackupText;

    private CheckBox autoOpenCheck;
    private CheckBox readerMenuAutoHideCheck;
    private CheckBox webDavEnabledCheck;
    private EditText urlInput;
    private EditText dirInput;
    private EditText userInput;
    private EditText passwordInput;
    private EditText mimoApiKeyInput;
    private Spinner appThemeSpinner;
    private Spinner readerUiThemeSpinner;
    private Spinner ttsEngineSpinner;
    private Spinner volumeKeyUpActionSpinner;
    private Spinner volumeKeyDownActionSpinner;
    private SeekBar glassOpacitySeekBar;
    private TextView glassOpacityText;
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
    private View ttsMimoKeyLayout;
    private boolean bindingSettingsValues = false;
    private boolean settingsBusy = false;

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
        importService = new BookImportService(this);


        autoOpenCheck = findViewById(R.id.check_auto_open);
        readerMenuAutoHideCheck = findViewById(R.id.check_reader_menu_auto_hide);
        webDavEnabledCheck = findViewById(R.id.check_webdav_enabled);
        urlInput = findViewById(R.id.input_webdav_url);
        dirInput = findViewById(R.id.input_webdav_dir);
        userInput = findViewById(R.id.input_webdav_user);
        passwordInput = findViewById(R.id.input_webdav_password);
        mimoApiKeyInput = findViewById(R.id.input_mimo_api_key);
        appThemeSpinner = findViewById(R.id.spinner_app_theme_mode);
        readerUiThemeSpinner = findViewById(R.id.spinner_reader_ui_theme_mode);
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
        webDavSyncOptionsLayout = findViewById(R.id.layout_webdav_sync_options);
        webDavSyncBookshelfButton = findViewById(R.id.button_webdav_sync_bookshelf);
        webDavSyncFilesButton = findViewById(R.id.button_webdav_sync_files);
        webDavSyncUiButton = findViewById(R.id.button_webdav_sync_ui);
        webDavSyncThemesButton = findViewById(R.id.button_webdav_sync_themes);
        webDavSyncBackgroundsButton = findViewById(R.id.button_webdav_sync_backgrounds);
        ttsMimoKeyLayout = findViewById(R.id.layout_tts_mimo_key);

        setupThemeSpinners();
        bindCurrentValues();
        setupGlassOpacityControl();
        setupAutoSaveListeners();
        setupWebDavSyncButtons();
        refreshBackupLabels();

        testButton.setOnClickListener(v -> testWebDav());
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
    protected void onPause() {
        persistSettingsIfReady();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        webDavEnabledCheck.setChecked(settingsStore.isWebDavEnabled());
        urlInput.setText(settingsStore.getWebDavUrl());
        dirInput.setText(settingsStore.getWebDavDir());
        userInput.setText(settingsStore.getWebDavUser());
        passwordInput.setText(settingsStore.getWebDavPassword());
        mimoApiKeyInput.setText(settingsStore.getTtsMimoApiKey());
        appThemeSpinner.setSelection(indexOf(APP_THEME_KEYS, settingsStore.getAppThemeMode(), 0));
        readerUiThemeSpinner.setSelection(indexOf(READER_THEME_KEYS, settingsStore.getReaderUiThemeMode(), 0));
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
        updateTtsSettingsVisibility();
        bindingSettingsValues = false;
        refreshStatusSummary();
    }

    private void refreshBackupLabels() {
        if (fullBackupText != null) fullBackupText.setText("全量备份：最近一次 " + backupManager.lastFullBackupLabel());
        if (liteBackupText != null) liteBackupText.setText("增量备份：最近一次 " + backupManager.lastLiteBackupLabel());
    }

    private void saveSettings() {
        String previousAppThemeMode = settingsStore.getAppThemeMode();
        settingsStore.setAutoOpenLastBook(autoOpenCheck.isChecked());
        settingsStore.setReaderMenuAutoHideEnabled(readerMenuAutoHideCheck.isChecked());
        settingsStore.setWebDavEnabled(webDavEnabledCheck.isChecked());
        settingsStore.setWebDavUrl(urlInput.getText().toString());
        settingsStore.setWebDavDir(dirInput.getText().toString());
        settingsStore.setWebDavUser(userInput.getText().toString());
        settingsStore.setWebDavPassword(passwordInput.getText().toString());
        if (ttsEngineSpinner != null) {
            settingsStore.setTtsEngine(TTS_ENGINE_KEYS[ttsEngineSpinner.getSelectedItemPosition()]);
        }
        settingsStore.setTtsMimoApiKey(mimoApiKeyInput.getText().toString());
        settingsStore.setAppThemeMode(APP_THEME_KEYS[appThemeSpinner.getSelectedItemPosition()]);
        settingsStore.setReaderUiThemeMode(READER_THEME_KEYS[readerUiThemeSpinner.getSelectedItemPosition()]);
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
        updateTtsSettingsVisibility();
        refreshStatusSummary();
        if (!previousAppThemeMode.equals(settingsStore.getAppThemeMode())) {
            recreate();
        }
    }

    private void setupThemeSpinners() {
        ArrayAdapter<String> appThemeAdapter = new ArrayAdapter<>(
                this,
                R.layout.item_spinner_selected,
                new String[]{"跟随系统", "浅色", "深色"}
        );
        appThemeAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        appThemeSpinner.setAdapter(appThemeAdapter);

        ArrayAdapter<String> readerThemeAdapter = new ArrayAdapter<>(
                this,
                R.layout.item_spinner_selected,
                new String[]{"跟随应用", "跟随系统", "浅色", "深色"}
        );
        readerThemeAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        readerUiThemeSpinner.setAdapter(readerThemeAdapter);

        if (ttsEngineSpinner != null) {
            ArrayAdapter<String> ttsEngineAdapter = new ArrayAdapter<>(
                    this,
                    R.layout.item_spinner_selected,
                    TTS_ENGINE_LABELS
            );
            ttsEngineAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
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
                    R.layout.item_spinner_selected,
                    VOLUME_KEY_ACTION_LABELS
            );
            volumeKeyUpAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
            volumeKeyUpActionSpinner.setAdapter(volumeKeyUpAdapter);
            volumeKeyUpActionSpinner.setOnItemSelectedListener(autoSaveSpinnerListener);
        }
        if (volumeKeyDownActionSpinner != null) {
            ArrayAdapter<String> volumeKeyDownAdapter = new ArrayAdapter<>(
                    this,
                    R.layout.item_spinner_selected,
                    VOLUME_KEY_ACTION_LABELS
            );
            volumeKeyDownAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
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
        userInput.addTextChangedListener(autoSaveTextWatcher);
        passwordInput.addTextChangedListener(autoSaveTextWatcher);
        mimoApiKeyInput.addTextChangedListener(autoSaveTextWatcher);
        autoOpenCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsChanged());
        readerMenuAutoHideCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsChanged());
        webDavEnabledCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsChanged());
    }

    private void setupWebDavSyncButtons() {
        if (webDavSyncBookshelfButton != null) webDavSyncBookshelfButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncBookshelfButton));
        if (webDavSyncFilesButton != null) webDavSyncFilesButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncFilesButton));
        if (webDavSyncUiButton != null) webDavSyncUiButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncUiButton));
        if (webDavSyncThemesButton != null) webDavSyncThemesButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncThemesButton));
        if (webDavSyncBackgroundsButton != null) webDavSyncBackgroundsButton.setOnClickListener(v -> toggleWebDavSyncButton(webDavSyncBackgroundsButton));
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
        statusText.setText("已启用自动进度同步\n手动备份范围：" + buildWebDavScopeSummary());
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
    private void persistSettingsIfReady() {
        if (settingsStore == null || autoOpenCheck == null) {
            return;
        }
        saveSettings();
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void updateGlassOpacityLabel(int opacityPercent) {
        glassOpacityText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "阅读菜单与弹窗当前不透明度 %d%%", opacityPercent));
    }

    private void toggleWebDavSyncButton(Button button) {
        button.setSelected(!button.isSelected());
        styleWebDavSyncButton(button, button.isSelected());
        handleSettingsChanged();
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
