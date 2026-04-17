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

import com.metahumanz.pacilread.importer.BookImportService;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.WebDavBackupManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.theme.ThemedActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends ThemedActivity {
    private static final int REQUEST_PICK_BOOK = 3001;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final String[] APP_THEME_KEYS = new String[]{"system", "light", "dark"};
    private static final String[] READER_THEME_KEYS = new String[]{"follow_app", "system", "light", "dark"};

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private WebDavClient webDavClient;
    private WebDavBackupManager backupManager;
    private BookImportService importService;

    private TextView statusText;
    private TextView fullBackupText;
    private TextView liteBackupText;

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
    private Button testButton;
    private Button importBooksButton;
    private Button fullBackupButton;
    private Button fullRestoreButton;
    private Button liteBackupButton;
    private Button liteRestoreButton;
    private boolean bindingSettingsValues = false;
    private boolean settingsBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        databaseHelper = ReaderDatabaseHelper.getInstance(this);
        settingsStore = new SettingsStore(this);
        webDavClient = new WebDavClient(settingsStore);
        backupManager = new WebDavBackupManager(this, databaseHelper, settingsStore, webDavClient);
        importService = new BookImportService(this);


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
        statusText = findViewById(R.id.text_status);
        fullBackupText = findViewById(R.id.text_backup_full);
        liteBackupText = findViewById(R.id.text_backup_lite);
        importBooksButton = findViewById(R.id.button_import_books);
        fullBackupButton = findViewById(R.id.button_full_backup);
        fullRestoreButton = findViewById(R.id.button_full_restore);
        liteBackupButton = findViewById(R.id.button_lite_backup);
        liteRestoreButton = findViewById(R.id.button_lite_restore);
        testButton = findViewById(R.id.button_test_webdav);

        setupThemeSpinners();
        bindCurrentValues();
        setupGlassOpacityControl();
        setupAutoSaveListeners();
        refreshBackupLabels();

        importBooksButton.setOnClickListener(v -> openPicker());
        testButton.setOnClickListener(v -> testWebDav());
        fullBackupButton.setOnClickListener(v -> runWebDavAction("正在执行全量备份...", listener -> backupManager.fullBackup(listener)));
        liteBackupButton.setOnClickListener(v -> runWebDavAction("正在执行增量备份...", listener -> backupManager.incrementalBackup(listener)));
        fullRestoreButton.setOnClickListener(v -> confirmRestore("全量恢复会覆盖当前本地数据库和设置，确定继续吗？", listener -> backupManager.fullRestore(listener)));
        liteRestoreButton.setOnClickListener(v -> confirmRestore("增量恢复会合并云端基础数据并补全缺失资源，确定继续吗？", listener -> backupManager.incrementalRestore(listener)));
    }

    @Override
    public void onBackPressed() {
        persistSettingsIfReady();
        super.onBackPressed();
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
        bindingSettingsValues = false;
        refreshStatusSummary();
    }

    private void refreshBackupLabels() {
        fullBackupText.setText("全量备份：最近一次 " + backupManager.lastFullBackupLabel());
        liteBackupText.setText("增量备份：最近一次 " + backupManager.lastLiteBackupLabel());
    }

    private void saveSettings() {
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
        webDavEnabledCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleSettingsChanged());
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
        statusText.setText(settingsStore.isWebDavEnabled() ? "已启用自动进度同步" : "当前未启用云同步");
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
                long bookId = databaseHelper.insertImportedBook(importService.importFromUri(uri));
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
        importBooksButton.setEnabled(!busy);
        testButton.setEnabled(!busy);
        fullBackupButton.setEnabled(!busy);
        fullRestoreButton.setEnabled(!busy);
        liteBackupButton.setEnabled(!busy);
        liteRestoreButton.setEnabled(!busy);
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
