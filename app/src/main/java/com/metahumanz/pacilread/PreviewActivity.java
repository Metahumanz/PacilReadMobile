package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.metahumanz.pacilread.model.ReaderThemeRecord;
import com.metahumanz.pacilread.reader.JustifiedPageTextView;
import com.metahumanz.pacilread.reader.ReaderThemeConfig;
import com.metahumanz.pacilread.reader.modern.theme.ReaderThemePalette;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.theme.ThemedActivity;
import com.metahumanz.pacilread.ui.GlassUiHelper;
import com.metahumanz.pacilread.util.FileAssetHelper;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PreviewActivity extends ThemedActivity {

    private static final int REQUEST_PICK_READER_BACKGROUND = 1003;
    private static final String[] READER_THEME_KEYS = {"follow_app", "system", "light", "dark"};
    private static final String[] READER_FONT_FAMILY_KEYS = {"system_default", "sans-serif", "monospace"};
    private static final String[] READER_FONT_FAMILY_LABELS = {"系统默认", "无衬线", "等宽体"};
    private static final int[] READER_FONT_WEIGHT_VALUES = {250, 400, 700};
    private static final String[] READER_FONT_WEIGHT_LABELS = {"细体", "标准", "粗体"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private AppDrawerController drawerController;
    private View mainRoot;

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
    private SeekBar previewStyleLeftSeekBar;
    private SeekBar previewStyleRightSeekBar;
    private SeekBar previewStyleTopSeekBar;
    private SeekBar previewStyleBottomSeekBar;
    private TextView previewStyleFontText;
    private TextView previewStyleFontWeightText;
    private TextView previewStyleLineText;
    private TextView previewStyleLeftText;
    private TextView previewStyleRightText;
    private TextView previewStyleTopText;
    private TextView previewStyleBottomText;
    private CheckBox previewStyleKeepScreenOnCheck;
    private CheckBox previewStyleShowTitleCheck;
    private TextView previewStyleBackgroundText;
    private Button previewThemePaperButton;
    private Button previewThemeForestButton;
    private Button previewThemeNightButton;
    private LinearLayout previewStyleCustomThemeList;

    private boolean bindingPreviewStyleValues = false;
    private String previewSelectedReaderTheme = "paper";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        databaseHelper = ReaderDatabaseHelper.getInstance(this);
        settingsStore = new SettingsStore(this);

        mainRoot = findViewById(R.id.main_root);
        bindViews();
        setupPreviewStyleSection();
        configureDrawer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePreviewPanels();
    }

    @Override
    public void finish() {
        super.finish();
    }

    @Override
    public void onBackPressed() {
        if (drawerController != null && drawerController.onBackPressed()) return;
        super.onBackPressed();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (drawerController != null && drawerController.handleTouchEvent(event)) {
            if (drawerController.consumePendingChildTouchCancel()) {
                MotionEvent ce = MotionEvent.obtain(event);
                ce.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(ce);
                ce.recycle();
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && requestCode == REQUEST_PICK_READER_BACKGROUND) {
            attachPreviewBackground(data.getData());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void bindViews() {
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
        previewStyleLeftSeekBar = findViewById(R.id.preview_style_seek_left_padding);
        previewStyleRightSeekBar = findViewById(R.id.preview_style_seek_right_padding);
        previewStyleTopSeekBar = findViewById(R.id.preview_style_seek_top_padding);
        previewStyleBottomSeekBar = findViewById(R.id.preview_style_seek_bottom_padding);
        previewStyleFontText = findViewById(R.id.preview_style_text_font);
        previewStyleFontWeightText = findViewById(R.id.preview_style_text_font_weight);
        previewStyleLineText = findViewById(R.id.preview_style_text_line_spacing);
        previewStyleLeftText = findViewById(R.id.preview_style_text_left_padding);
        previewStyleRightText = findViewById(R.id.preview_style_text_right_padding);
        previewStyleTopText = findViewById(R.id.preview_style_text_top_padding);
        previewStyleBottomText = findViewById(R.id.preview_style_text_bottom_padding);
        previewStyleKeepScreenOnCheck = findViewById(R.id.preview_style_check_keep_screen_on);
        previewStyleShowTitleCheck = findViewById(R.id.preview_style_check_show_title);
        previewStyleBackgroundText = findViewById(R.id.preview_style_text_background);
        previewThemePaperButton = findViewById(R.id.preview_style_button_theme_paper);
        previewThemeForestButton = findViewById(R.id.preview_style_button_theme_forest);
        previewThemeNightButton = findViewById(R.id.preview_style_button_theme_night);
        previewStyleCustomThemeList = findViewById(R.id.preview_style_custom_theme_list);
    }

    private void configureDrawer() {
        drawerController = new AppDrawerController(this, mainRoot, destination -> {
            if (destination == AppDrawerController.SECTION_BOOKSHELF) {
                finish();
                return;
            }
            if (destination == AppDrawerController.SECTION_SETTINGS) {
                startActivity(new Intent(this, SettingsActivity.class));
                drawerController.closeDrawer();
                return;
            }
            // Already on preview
            drawerController.closeDrawer();
        });
        drawerController.bindMenuButton(R.id.button_open_drawer);
        drawerController.setCurrentSection(AppDrawerController.SECTION_PREVIEW);
    }

    private void setupPreviewStyleSection() {
        if (previewStyleUiThemeSpinner == null) return;
        bindingPreviewStyleValues = true;
        previewStyleUiThemeSpinner.setAdapter(buildSpinnerAdapter(new String[]{"跟随应用", "跟随系统", "浅色", "深色"}));
        previewStyleFontFamilySpinner.setAdapter(buildSpinnerAdapter(READER_FONT_FAMILY_LABELS));

        android.widget.AdapterView.OnItemSelectedListener spinnerListener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { applyPreviewStyleControls(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        };
        previewStyleUiThemeSpinner.setOnItemSelectedListener(spinnerListener);
        previewStyleFontFamilySpinner.setOnItemSelectedListener(spinnerListener);

        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                updatePreviewStyleLabels();
                if (fromUser) applyPreviewStyleControls();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { applyPreviewStyleControls(); }
        };
        previewStyleFontSeekBar.setOnSeekBarChangeListener(seekListener);
        previewStyleFontWeightSeekBar.setOnSeekBarChangeListener(seekListener);
        previewStyleLineSeekBar.setOnSeekBarChangeListener(seekListener);
        previewStyleLeftSeekBar.setOnSeekBarChangeListener(seekListener);
        previewStyleRightSeekBar.setOnSeekBarChangeListener(seekListener);
        previewStyleTopSeekBar.setOnSeekBarChangeListener(seekListener);
        previewStyleBottomSeekBar.setOnSeekBarChangeListener(seekListener);

        previewStyleKeepScreenOnCheck.setOnCheckedChangeListener((b, c) -> applyPreviewStyleControls());
        previewStyleShowTitleCheck.setOnCheckedChangeListener((b, c) -> applyPreviewStyleControls());

        previewThemePaperButton.setOnClickListener(v -> { previewSelectedReaderTheme = "paper"; updatePreviewThemeButtons(); applyPreviewStyleControls(); });
        previewThemeForestButton.setOnClickListener(v -> { previewSelectedReaderTheme = "forest"; updatePreviewThemeButtons(); applyPreviewStyleControls(); });
        previewThemeNightButton.setOnClickListener(v -> { previewSelectedReaderTheme = "night"; updatePreviewThemeButtons(); applyPreviewStyleControls(); });

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
        if (previewStyleUiThemeSpinner == null) return;
        bindingPreviewStyleValues = true;
        previewSelectedReaderTheme = settingsStore.getReaderTheme();
        previewStyleUiThemeSpinner.setSelection(indexOf(READER_THEME_KEYS, settingsStore.getReaderUiThemeMode(), 0), false);
        previewStyleFontFamilySpinner.setSelection(indexOf(READER_FONT_FAMILY_KEYS, settingsStore.getReaderFontFamily(), 0), false);
        previewStyleFontSeekBar.setProgress(Math.round(settingsStore.getFontSizeSp()) - 12);
        previewStyleFontWeightSeekBar.setProgress(fontWeightProgress(settingsStore.getReaderFontWeight()));
        previewStyleLineSeekBar.setProgress(Math.round(settingsStore.getLineSpacingExtraSp()));
        previewStyleLeftSeekBar.setProgress(settingsStore.getLeftPaddingDp());
        previewStyleRightSeekBar.setProgress(settingsStore.getRightPaddingDp());
        previewStyleTopSeekBar.setProgress(settingsStore.getTopPaddingDp());
        previewStyleBottomSeekBar.setProgress(settingsStore.getBottomPaddingDp());
        previewStyleKeepScreenOnCheck.setChecked(settingsStore.isKeepScreenOn());
        previewStyleShowTitleCheck.setChecked(settingsStore.isChapterTitleVisible());
        previewStyleBackgroundText.setText(currentPreviewBackgroundLabel());
        updatePreviewThemeButtons();
        updatePreviewStyleLabels();
        bindingPreviewStyleValues = false;
    }

    private void applyPreviewStyleControls() {
        if (bindingPreviewStyleValues) return;
        settingsStore.setReaderUiThemeMode(READER_THEME_KEYS[previewStyleUiThemeSpinner.getSelectedItemPosition()]);
        settingsStore.setReaderFontFamily(READER_FONT_FAMILY_KEYS[previewStyleFontFamilySpinner.getSelectedItemPosition()]);
        settingsStore.setFontSizeSp(previewStyleFontSeekBar.getProgress() + 12f);
        settingsStore.setReaderFontWeight(fontWeightValueForProgress(previewStyleFontWeightSeekBar.getProgress()));
        settingsStore.setLineSpacingExtraSp(previewStyleLineSeekBar.getProgress());
        settingsStore.setLeftPaddingDp(previewStyleLeftSeekBar.getProgress());
        settingsStore.setRightPaddingDp(previewStyleRightSeekBar.getProgress());
        settingsStore.setTopPaddingDp(previewStyleTopSeekBar.getProgress());
        settingsStore.setBottomPaddingDp(previewStyleBottomSeekBar.getProgress());
        settingsStore.setKeepScreenOn(previewStyleKeepScreenOnCheck.isChecked());
        settingsStore.setChapterTitleVisible(previewStyleShowTitleCheck.isChecked());
        settingsStore.setReaderTheme(previewSelectedReaderTheme);
        updatePreviewPanels();
    }

    private void updatePreviewReader() {
        ReaderThemePalette palette = ReaderThemePalette.from(settingsStore.getReaderTheme());
        Typeface bodyTypeface = buildReaderTypeface(settingsStore.getReaderFontFamily(), settingsStore.getReaderFontWeight());
        Typeface titleTypeface = buildReaderTypeface(settingsStore.getReaderFontFamily(),
                Math.max(600, Math.min(900, settingsStore.getReaderFontWeight() + 200)));
        previewReaderHeading.setVisibility(settingsStore.isChapterTitleVisible() ? View.VISIBLE : View.GONE);
        previewReaderHeading.setText("第一章 雨落书页时");
        previewReaderHeading.setIncludeFontPadding(false);
        previewReaderHeading.setTypeface(titleTypeface);
        previewReaderHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, settingsStore.getFontSizeSp() + 2f);
        previewReaderHeading.setTextColor(palette.textColor);
        previewReaderBody.setText("雨点敲在窗沿上时，旧书的纸页也跟着轻轻起伏。字距、行距、边距与字重会共同决定这页文字是松弛、沉稳，还是压迫。\n\n如果一段文字像现在这样安静地铺开，说明当前排版已经接近真实阅读状态。");
        previewReaderBody.setTypeface(bodyTypeface);
        previewReaderBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, settingsStore.getFontSizeSp());
        previewReaderBody.setTextColor(palette.textColor);
        previewReaderBody.setLineSpacing(settingsStore.getLineSpacingExtraSp(), 1f);
        previewReaderBody.setFullJustifyEnabled(settingsStore.isBodyTextJustified());
        previewReaderPage.setBackgroundColor(palette.pageColor);
        previewReaderScrim.setBackgroundColor(palette.overlayColor);
        int leftPadding = dp(settingsStore.getLeftPaddingDp());
        int rightPadding = dp(settingsStore.getRightPaddingDp());
        int topPadding = dp(settingsStore.getTopPaddingDp() + 24);
        int bottomPadding = dp(settingsStore.getBottomPaddingDp() + 24);
        previewReaderPage.setPadding(leftPadding, topPadding, rightPadding, bottomPadding);
        LinearLayout.LayoutParams bodyParams = (LinearLayout.LayoutParams) previewReaderBody.getLayoutParams();
        bodyParams.topMargin = settingsStore.isChapterTitleVisible() ? dp(14) : 0;
        previewReaderBody.setLayoutParams(bodyParams);
        String backgroundPath = settingsStore.getReaderBackgroundPath();
        boolean applied = false;
        if (backgroundPath != null && !backgroundPath.isBlank()) {
            File f = new File(backgroundPath);
            if (f.exists()) {
                Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm != null) { previewReaderBackground.setImageBitmap(bm); applied = true; }
            }
        }
        if (!applied) previewReaderBackground.setImageResource(palette.backgroundDrawableRes);
    }

    private void updatePreviewThemeButtons() {
        stylePreviewThemeButton(previewThemePaperButton, "paper".equals(previewSelectedReaderTheme));
        stylePreviewThemeButton(previewThemeForestButton, "forest".equals(previewSelectedReaderTheme));
        stylePreviewThemeButton(previewThemeNightButton, "night".equals(previewSelectedReaderTheme));
    }

    private void stylePreviewThemeButton(Button button, boolean active) {
        if (button == null) return;
        button.setBackgroundResource(active ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setTextColor(getColor(active ? android.R.color.white : R.color.on_surface));
        GlassUiHelper.applyToView(this, button, settingsStore.getGlassOpacityPercent());
    }

    private void updatePreviewStyleLabels() {
        previewStyleFontText.setText((previewStyleFontSeekBar.getProgress() + 12) + " sp");
        previewStyleFontWeightText.setText(readerFontWeightLabelForProgress(previewStyleFontWeightSeekBar.getProgress())
                + " (" + fontWeightValueForProgress(previewStyleFontWeightSeekBar.getProgress()) + ")");
        previewStyleLineText.setText(previewStyleLineSeekBar.getProgress() + " px");
        previewStyleLeftText.setText(previewStyleLeftSeekBar.getProgress() + " dp");
        previewStyleRightText.setText(previewStyleRightSeekBar.getProgress() + " dp");
        previewStyleTopText.setText(previewStyleTopSeekBar.getProgress() + " dp");
        previewStyleBottomText.setText(previewStyleBottomSeekBar.getProgress() + " dp");
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
                if (oldPath != null && !oldPath.isBlank()) FileAssetHelper.deleteIfExists(oldPath);
                settingsStore.setReaderBackgroundPath(newFile.getAbsolutePath());
                runOnUiThread(this::updatePreviewPanels);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "设置背景失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String currentPreviewBackgroundLabel() {
        String path = settingsStore.getReaderBackgroundPath();
        if (path == null || path.isBlank()) return "当前背景：使用" + labelForReaderPreset(settingsStore.getReaderTheme()) + "内置壁纸";
        return "当前背景：" + new File(path).getName();
    }

    private void renderPreviewThemeRows() {
        if (previewStyleCustomThemeList == null) return;
        previewStyleCustomThemeList.removeAllViews();
        executor.execute(() -> {
            List<ReaderThemeRecord> themes = databaseHelper.getCustomThemes();
            runOnUiThread(() -> {
                if (previewStyleCustomThemeList == null) return;
                previewStyleCustomThemeList.removeAllViews();
                for (ReaderThemeRecord theme : themes) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    Button applyButton = new Button(this);
                    applyButton.setText(theme.name);
                    applyButton.setAllCaps(false);
                    applyButton.setBackgroundResource(R.drawable.bg_outline_button);
                    applyButton.setTextColor(getColor(R.color.primary));
                    applyButton.setPadding(dp(16), dp(8), dp(16), dp(8));
                    GlassUiHelper.applyToView(this, applyButton, settingsStore.getGlassOpacityPercent());
                    LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    row.addView(applyButton, ap);
                    Button deleteButton = new Button(this);
                    deleteButton.setText("删除");
                    deleteButton.setAllCaps(false);
                    deleteButton.setBackgroundResource(R.drawable.bg_danger_button);
                    deleteButton.setPadding(dp(12), dp(8), dp(12), dp(8));
                    LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    dp2.leftMargin = dp(8);
                    row.addView(deleteButton, dp2);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    rp.bottomMargin = dp(8);
                    row.setLayoutParams(rp);
                    applyButton.setOnClickListener(v -> {
                        try {
                            ReaderThemeConfig.apply(settingsStore, new JSONObject(theme.configJson));
                            previewSelectedReaderTheme = settingsStore.getReaderTheme();
                            bindPreviewStyleValues();
                            updatePreviewPanels();
                        } catch (Exception e) {
                            Toast.makeText(this, "主题配置损坏", Toast.LENGTH_SHORT).show();
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
                .setTitle("保存当前主题").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) { Toast.makeText(this, "请输入主题名称", Toast.LENGTH_SHORT).show(); return; }
                    executor.execute(() -> {
                        databaseHelper.saveCustomTheme(name, ReaderThemeConfig.export(settingsStore).toString());
                        runOnUiThread(this::renderPreviewThemeRows);
                    });
                }).show();
    }

    // ==================== Helpers ====================

    private int fontWeightProgress(int weight) {
        for (int i = 0; i < READER_FONT_WEIGHT_VALUES.length; i++) {
            if (READER_FONT_WEIGHT_VALUES[i] == weight) return i;
        }
        return 1;
    }

    private int fontWeightValueForProgress(int progress) {
        return READER_FONT_WEIGHT_VALUES[Math.max(0, Math.min(progress, READER_FONT_WEIGHT_VALUES.length - 1))];
    }

    private String readerFontWeightLabelForProgress(int progress) {
        return READER_FONT_WEIGHT_LABELS[Math.max(0, Math.min(progress, READER_FONT_WEIGHT_LABELS.length - 1))];
    }

    private Typeface buildReaderTypeface(String familyKey, int weight) {
        Typeface base;
        switch (familyKey) {
            case "sans-serif": base = Typeface.SANS_SERIF; break;
            case "monospace": base = Typeface.MONOSPACE; break;
            default: base = Typeface.DEFAULT; break;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(base, weight, false);
        }
        return Typeface.create(base, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private ArrayAdapter<String> buildSpinnerAdapter(String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner_selected, items);
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        return adapter;
    }

    private int indexOf(String[] values, String target, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) return i;
        }
        return fallback;
    }

    private String labelForReaderUiTheme(String v) {
        if ("system".equals(v)) return "跟随系统";
        if ("light".equals(v)) return "浅色";
        if ("dark".equals(v)) return "深色";
        return "跟随应用";
    }

    private String labelForReaderPreset(String v) {
        if ("forest".equals(v)) return "护眼";
        if ("night".equals(v)) return "夜航";
        return "纸控";
    }

    private String labelForReaderFontFamily(String v) {
        if ("sans-serif".equals(v)) return "无衬线";
        if ("monospace".equals(v)) return "等宽体";
        return "系统默认";
    }

    private String labelForReaderFontWeight(int v) {
        if (v <= 325) return "细体";
        if (v >= 550) return "粗体";
        return "标准";
    }

}
