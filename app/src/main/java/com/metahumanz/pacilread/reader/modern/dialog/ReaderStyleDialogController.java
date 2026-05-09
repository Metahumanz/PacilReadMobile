package com.metahumanz.pacilread.reader.modern.dialog;

import android.app.AlertDialog;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.model.ReaderThemeRecord;
import com.metahumanz.pacilread.reader.ReaderThemeConfig;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.config.ReaderOptionCatalog;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController;
import com.metahumanz.pacilread.reader.modern.theme.ReaderDisplayModeHelper;
import com.metahumanz.pacilread.reader.modern.theme.ReaderThemePalette;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.ui.HsvColorPlaneView;
import com.metahumanz.pacilread.util.FileAssetHelper;

import java.util.List;

public final class ReaderStyleDialogController {
    private static final float LETTER_SPACING_STEP = 0.05f;

    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderSessionState state;
    private final ReaderUiUtils ui;
    private final ReaderDialogSupport dialogSupport;
    private final ReaderContentController content;
    private final ReaderNavigationController navigation;
    private final ReaderStyleController style;
    private final ReaderChromeController chrome;

    public ReaderStyleDialogController(
            ModernReaderActivity activity,
            ReaderRuntime runtime,
            ReaderSessionState state,
            ReaderUiUtils ui,
            ReaderDialogSupport dialogSupport,
            ReaderContentController content,
            ReaderNavigationController navigation,
            ReaderStyleController style,
            ReaderChromeController chrome
    ) {
        this.activity = activity;
        this.runtime = runtime;
        this.state = state;
        this.ui = ui;
        this.dialogSupport = dialogSupport;
        this.content = content;
        this.navigation = navigation;
        this.style = style;
        this.chrome = chrome;
    }

    public void showStyleDialog(int backgroundPickerRequestCode) {
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_reader_style, null, false);
        StyleDialogViews refs = StyleDialogViews.bind(contentView);
        dialogSupport.applyTocStyleFullscreenInsets(contentView, refs.contentContainer);
        ArrayAdapter<String> uiThemeAdapter = dialogSupport.buildSpinnerAdapter(new String[]{"跟随应用", "跟随系统", "浅色", "深色"});
        ArrayAdapter<String> doublePageModeAdapter = dialogSupport.buildSpinnerAdapter(ReaderOptionCatalog.DOUBLE_PAGE_MODE_LABELS);
        ArrayAdapter<String> fontFamilyAdapter = dialogSupport.buildSpinnerAdapter(ReaderOptionCatalog.READER_FONT_FAMILY_LABELS);
        ArrayAdapter<String> textColorAdapter = dialogSupport.buildSpinnerAdapter(ReaderOptionCatalog.READER_TEXT_COLOR_LABELS);
        refs.uiThemeSpinner.setAdapter(uiThemeAdapter);
        refs.doublePageModeSpinner.setAdapter(doublePageModeAdapter);
        refs.fontFamilySpinner.setAdapter(fontFamilyAdapter);
        refs.textColorSpinner.setAdapter(textColorAdapter);
        refs.fontFamilySpinner.setSelection(
                ReaderOptionCatalog.indexOf(ReaderOptionCatalog.READER_FONT_FAMILY_KEYS, runtime.settingsStore.getReaderFontFamily(), 0),
                false
        );
        refs.textColorSpinner.setSelection(
                ReaderOptionCatalog.indexOf(ReaderOptionCatalog.READER_TEXT_COLOR_KEYS, runtime.settingsStore.getReaderTextColor(), 0),
                false
        );
        refs.fontSeek.setProgress(Math.round(runtime.settingsStore.getFontSizeSp()) - 12);
        refs.fontWeightSeek.setProgress(ReaderOptionCatalog.fontWeightProgress(runtime.settingsStore.getReaderFontWeight()));
        refs.lineSeek.setProgress(Math.round(runtime.settingsStore.getLineSpacingExtraSp()));
        refs.leftSeek.setProgress(runtime.settingsStore.getLeftPaddingDp());
        refs.rightSeek.setProgress(runtime.settingsStore.getRightPaddingDp());
        refs.topSeek.setProgress(runtime.settingsStore.getTopPaddingDp());
        refs.bottomSeek.setProgress(runtime.settingsStore.getBottomPaddingDp());
        refs.letterSpacingSeek.setProgress(Math.round(runtime.settingsStore.getLetterSpacing() / LETTER_SPACING_STEP));
        refs.firstLineIndentSeek.setProgress(runtime.settingsStore.getFirstLineIndentDp());
        refs.paragraphSpacingSeek.setProgress(runtime.settingsStore.getParagraphSpacingDp());
        refs.backgroundBlurSeek.setProgress(runtime.settingsStore.getBackgroundBlurPercent());
        refs.keepScreenOn.setChecked(runtime.settingsStore.isKeepScreenOn());
        refs.showTitleCheck.setChecked(runtime.settingsStore.isChapterTitleVisible());
        refs.doublePageCheck.setChecked(runtime.settingsStore.isReaderDoublePageEnabled());
        refs.doublePageModeSpinner.setSelection(
                ReaderOptionCatalog.indexOf(
                        ReaderOptionCatalog.DOUBLE_PAGE_MODE_KEYS,
                        runtime.settingsStore.getReaderDoublePageMode(),
                        0
                ),
                false
        );
        applyDoublePageTurnStepButtons(refs, runtime.settingsStore.getReaderDoublePageTurnStep());
        updateDoublePageModeAvailability(refs);
        refs.backgroundText.setText(style.currentBackgroundLabel());
        refs.uiThemeSpinner.setSelection(
                ReaderOptionCatalog.indexOf(ReaderOptionCatalog.UI_THEME_KEYS, runtime.settingsStore.getReaderUiThemeMode(), 0),
                false
        );
        String[] selectedReaderTheme = new String[]{runtime.settingsStore.getReaderTheme()};
        chrome.updateReaderThemeButtons(
                refs.paperThemeButton,
                refs.forestThemeButton,
                refs.nightThemeButton,
                effectiveReaderThemeForDialog(selectedReaderTheme[0])
        );
        String chapterTitleAlignment = runtime.settingsStore.getChapterTitleAlignment();
        chrome.styleThemeButton(refs.titleLeftButton, "left".equals(chapterTitleAlignment));
        chrome.styleThemeButton(refs.titleCenterButton, "center".equals(chapterTitleAlignment));
        chrome.styleThemeButton(refs.bodyJustifyButton, runtime.settingsStore.isBodyTextJustified());
        chrome.styleThemeButton(refs.bodyLeftButton, !runtime.settingsStore.isBodyTextJustified());
        style.updateLetterSpacingLabel(refs.letterSpacingValue, refs.letterSpacingSeek);
        style.updateFirstLineIndentLabel(refs.firstLineIndentValue, refs.firstLineIndentSeek);
        style.updateParagraphSpacingLabel(refs.paragraphSpacingValue, refs.paragraphSpacingSeek);
        style.updateBackgroundBlurLabel(refs.backgroundBlurValue, refs.backgroundBlurSeek);

        Runnable refreshTextColorPreview = () -> style.updateTextColorPreview(
                refs.textColorValue,
                ReaderOptionCatalog.READER_TEXT_COLOR_KEYS[refs.textColorSpinner.getSelectedItemPosition()],
                ReaderThemePalette.from(effectiveReaderThemeForDialog(selectedReaderTheme[0]))
        );
        refreshTextColorPreview.run();
        Runnable autoApply = buildAutoApply(refs, selectedReaderTheme, refreshTextColorPreview);

        refs.paperThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "paper";
            chrome.updateReaderThemeButtons(refs.paperThemeButton, refs.forestThemeButton, refs.nightThemeButton, effectiveReaderThemeForDialog(selectedReaderTheme[0]));
            refreshTextColorPreview.run();
            autoApply.run();
        });
        refs.forestThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "forest";
            chrome.updateReaderThemeButtons(refs.paperThemeButton, refs.forestThemeButton, refs.nightThemeButton, effectiveReaderThemeForDialog(selectedReaderTheme[0]));
            refreshTextColorPreview.run();
            autoApply.run();
        });
        refs.nightThemeButton.setOnClickListener(v -> {
            selectedReaderTheme[0] = "night";
            chrome.updateReaderThemeButtons(refs.paperThemeButton, refs.forestThemeButton, refs.nightThemeButton, effectiveReaderThemeForDialog(selectedReaderTheme[0]));
            refreshTextColorPreview.run();
            autoApply.run();
        });

        updateStyleLabels(refs);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateStyleLabels(refs);
                if (fromUser) {
                    autoApply.run();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                autoApply.run();
            }
        };
        refs.fontWeightSeek.setOnSeekBarChangeListener(listener);
        refs.fontSeek.setOnSeekBarChangeListener(listener);
        refs.lineSeek.setOnSeekBarChangeListener(listener);
        refs.leftSeek.setOnSeekBarChangeListener(listener);
        refs.rightSeek.setOnSeekBarChangeListener(listener);
        refs.topSeek.setOnSeekBarChangeListener(listener);
        refs.bottomSeek.setOnSeekBarChangeListener(listener);

        SeekBar.OnSeekBarChangeListener simpleListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (seekBar == refs.letterSpacingSeek) {
                    style.updateLetterSpacingLabel(refs.letterSpacingValue, refs.letterSpacingSeek);
                } else if (seekBar == refs.firstLineIndentSeek) {
                    style.updateFirstLineIndentLabel(refs.firstLineIndentValue, refs.firstLineIndentSeek);
                } else if (seekBar == refs.paragraphSpacingSeek) {
                    style.updateParagraphSpacingLabel(refs.paragraphSpacingValue, refs.paragraphSpacingSeek);
                } else if (seekBar == refs.backgroundBlurSeek) {
                    style.updateBackgroundBlurLabel(refs.backgroundBlurValue, refs.backgroundBlurSeek);
                }
                if (fromUser) {
                    autoApply.run();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                autoApply.run();
            }
        };
        refs.letterSpacingSeek.setOnSeekBarChangeListener(simpleListener);
        refs.firstLineIndentSeek.setOnSeekBarChangeListener(simpleListener);
        refs.paragraphSpacingSeek.setOnSeekBarChangeListener(simpleListener);
        refs.backgroundBlurSeek.setOnSeekBarChangeListener(simpleListener);

        refs.keepScreenOn.setOnCheckedChangeListener((buttonView, isChecked) -> autoApply.run());
        refs.showTitleCheck.setOnCheckedChangeListener((buttonView, isChecked) -> autoApply.run());
        refs.doublePageCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateDoublePageModeAvailability(refs);
            autoApply.run();
        });
        refs.fontFamilySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                autoApply.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        refs.textColorSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                refreshTextColorPreview.run();
                autoApply.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        refs.uiThemeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                autoApply.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        refs.doublePageModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                autoApply.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        refs.doublePageTurnOneButton.setOnClickListener(v -> {
            applyDoublePageTurnStepButtons(refs, "one");
            autoApply.run();
        });
        refs.doublePageTurnTwoButton.setOnClickListener(v -> {
            applyDoublePageTurnStepButtons(refs, "two");
            autoApply.run();
        });

        refs.titleLeftButton.setOnClickListener(v -> {
            runtime.settingsStore.setChapterTitleAlignment("left");
            chrome.styleThemeButton(refs.titleLeftButton, true);
            chrome.styleThemeButton(refs.titleCenterButton, false);
            autoApply.run();
        });
        refs.titleCenterButton.setOnClickListener(v -> {
            runtime.settingsStore.setChapterTitleAlignment("center");
            chrome.styleThemeButton(refs.titleLeftButton, false);
            chrome.styleThemeButton(refs.titleCenterButton, true);
            autoApply.run();
        });
        refs.bodyJustifyButton.setOnClickListener(v -> {
            runtime.settingsStore.setBodyTextJustified(true);
            chrome.styleThemeButton(refs.bodyJustifyButton, true);
            chrome.styleThemeButton(refs.bodyLeftButton, false);
            autoApply.run();
        });
        refs.bodyLeftButton.setOnClickListener(v -> {
            runtime.settingsStore.setBodyTextJustified(false);
            chrome.styleThemeButton(refs.bodyJustifyButton, false);
            chrome.styleThemeButton(refs.bodyLeftButton, true);
            autoApply.run();
        });
        refs.customColorButton.setOnClickListener(v -> showCustomColorPickerDialog(() -> {
            refs.textColorSpinner.setSelection(
                    ReaderOptionCatalog.indexOf(ReaderOptionCatalog.READER_TEXT_COLOR_KEYS, "custom", 0),
                    false
            );
            refreshTextColorPreview.run();
            autoApply.run();
        }));

        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        dialogSupport.addAlignedCloseButton(contentView, R.id.style_title, refs.contentContainer, dialog);
        contentView.findViewById(R.id.style_button_pick_background).setOnClickListener(v -> style.openBackgroundPicker(backgroundPickerRequestCode));
        contentView.findViewById(R.id.style_button_clear_background).setOnClickListener(v -> {
            FileAssetHelper.deleteIfExists(runtime.settingsStore.getReaderBackgroundPath());
            runtime.settingsStore.setReaderBackgroundPath("");
            refs.backgroundText.setText(style.currentBackgroundLabel());
            style.applyReaderSettings();
        });
        contentView.findViewById(R.id.style_button_save_theme).setOnClickListener(v -> promptSaveTheme(() -> renderThemeRows(refs.customThemeList, dialog, refs, selectedReaderTheme)));
        dialogSupport.showImmersiveFullscreenDialog(dialog, state.controlsVisible);
        contentView.requestApplyInsets();
        renderThemeRows(refs.customThemeList, dialog, refs, selectedReaderTheme);
    }

    private Runnable buildAutoApply(StyleDialogViews refs, String[] selectedReaderTheme, Runnable refreshTextColorPreview) {
        return () -> {
            int anchorOffset = content.currentCharOffset();
            String previousResolvedAppearance = ThemeModeHelper.getResolvedReaderAppearanceLabel(activity);
            runtime.settingsStore.setReaderFontFamily(ReaderOptionCatalog.READER_FONT_FAMILY_KEYS[refs.fontFamilySpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setReaderTextColor(ReaderOptionCatalog.READER_TEXT_COLOR_KEYS[refs.textColorSpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setFontSizeSp(refs.fontSeek.getProgress() + 12);
            runtime.settingsStore.setReaderFontWeight(ReaderOptionCatalog.fontWeightValueForProgress(refs.fontWeightSeek.getProgress()));
            runtime.settingsStore.setLineSpacingExtraSp(refs.lineSeek.getProgress());
            runtime.settingsStore.setLeftPaddingDp(refs.leftSeek.getProgress());
            runtime.settingsStore.setRightPaddingDp(refs.rightSeek.getProgress());
            runtime.settingsStore.setTopPaddingDp(refs.topSeek.getProgress());
            runtime.settingsStore.setBottomPaddingDp(refs.bottomSeek.getProgress());
            runtime.settingsStore.setLetterSpacing(refs.letterSpacingSeek.getProgress() * LETTER_SPACING_STEP);
            runtime.settingsStore.setFirstLineIndentDp(refs.firstLineIndentSeek.getProgress());
            runtime.settingsStore.setParagraphSpacingDp(refs.paragraphSpacingSeek.getProgress());
            runtime.settingsStore.setBackgroundBlurPercent(refs.backgroundBlurSeek.getProgress());
            runtime.settingsStore.setKeepScreenOn(refs.keepScreenOn.isChecked());
            runtime.settingsStore.setChapterTitleVisible(refs.showTitleCheck.isChecked());
            runtime.settingsStore.setReaderDoublePageEnabled(refs.doublePageCheck.isChecked());
            runtime.settingsStore.setReaderDoublePageMode(ReaderOptionCatalog.DOUBLE_PAGE_MODE_KEYS[
                    refs.doublePageModeSpinner.getSelectedItemPosition()
            ]);
            runtime.settingsStore.setReaderDoublePageTurnStep(refs.doublePageTurnOneButton.isSelected() ? "one" : "two");
            runtime.settingsStore.setReaderTheme(selectedReaderTheme[0]);
            runtime.settingsStore.setReaderUiThemeMode(ReaderOptionCatalog.UI_THEME_KEYS[refs.uiThemeSpinner.getSelectedItemPosition()]);
            refreshTextColorPreview.run();
            String nextResolvedAppearance = ThemeModeHelper.getResolvedReaderAppearanceLabel(activity);
            if (!previousResolvedAppearance.equals(nextResolvedAppearance)) {
                activity.applyReaderUiThemeWithoutRecreate();
                content.scheduleReflowAfterLayout(state.currentChapterIndex, anchorOffset);
                return;
            }
            content.scheduleReflowAfterLayout(state.currentChapterIndex, anchorOffset);
        };
    }

    private void updateDoublePageModeAvailability(StyleDialogViews refs) {
        if (refs.doublePageModeSpinner == null) {
            return;
        }
        boolean enabled = refs.doublePageCheck != null && refs.doublePageCheck.isChecked();
        refs.doublePageModeSpinner.setEnabled(enabled);
        refs.doublePageModeSpinner.setAlpha(enabled ? 1f : 0.45f);
        setEnabledWithAlpha(refs.doublePageTurnStepLayout, enabled);
        setEnabledWithAlpha(refs.doublePageTurnOneButton, enabled);
        setEnabledWithAlpha(refs.doublePageTurnTwoButton, enabled);
    }

    private void applyDoublePageTurnStepButtons(StyleDialogViews refs, String step) {
        boolean onePage = "one".equals(step);
        refs.doublePageTurnOneButton.setSelected(onePage);
        refs.doublePageTurnTwoButton.setSelected(!onePage);
        chrome.styleThemeButton(refs.doublePageTurnOneButton, onePage);
        chrome.styleThemeButton(refs.doublePageTurnTwoButton, !onePage);
    }

    private void setEnabledWithAlpha(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
    }

    private void renderThemeRows(LinearLayout container, AlertDialog dialog, StyleDialogViews refs, String[] selectedReaderTheme) {
        container.removeAllViews();
        runtime.safeExecute(() -> {
            List<ReaderThemeRecord> themes = runtime.databaseHelper.getCustomThemes();
            activity.runOnReaderUiThread(() -> {
                if (!dialog.isShowing()) {
                    return;
                }
                container.removeAllViews();
                for (ReaderThemeRecord theme : themes) {
                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    Button applyButton = new Button(activity);
                    applyButton.setText(theme.name);
                    applyButton.setBackgroundResource(R.drawable.bg_outline_button);
                    applyButton.setTextColor(ThemeModeHelper.resolveColor(activity, R.color.primary));
                    com.metahumanz.pacilread.ui.GlassUiHelper.applyToView(activity, applyButton, runtime.settingsStore.getGlassOpacityPercent());
                    Button deleteButton = new Button(activity);
                    deleteButton.setText("删除");
                    deleteButton.setBackgroundResource(R.drawable.bg_danger_button);
                    deleteButton.setTextColor(0xFFFFFFFF);
                    row.addView(applyButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    deleteParams.leftMargin = ui.dp(8);
                    row.addView(deleteButton, deleteParams);
                    Runnable refreshTextColorPreview = () -> style.updateTextColorPreview(
                            refs.textColorValue,
                            ReaderOptionCatalog.READER_TEXT_COLOR_KEYS[refs.textColorSpinner.getSelectedItemPosition()],
                            ReaderThemePalette.from(effectiveReaderThemeForDialog(selectedReaderTheme[0]))
                    );
                    Runnable autoApply = buildAutoApply(refs, selectedReaderTheme, refreshTextColorPreview);
                    applyButton.setOnClickListener(v -> autoApply.run());
                    deleteButton.setOnClickListener(v -> runtime.safeExecute(() -> {
                        runtime.databaseHelper.deleteCustomTheme(theme.id);
                        activity.runOnReaderUiThread(() -> renderThemeRows(container, dialog, refs, selectedReaderTheme));
                    }, "delete reader theme"));
                    container.addView(row);
                }
            });
        }, "render reader themes");
    }

    private void promptSaveTheme(Runnable onSaved) {
        EditText input = new EditText(activity);
        input.setHint("主题名称");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("保存当前主题")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (unusedDialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        ui.showToast("请输入主题名称");
                        return;
                    }
                    runtime.safeExecute(() -> {
                        runtime.databaseHelper.saveCustomTheme(name, ReaderThemeConfig.export(runtime.settingsStore).toString());
                        activity.runOnReaderUiThread(onSaved);
                    }, "save reader theme");
                })
                .create();
        dialogSupport.showStyledDialog(dialog);
    }

    private String effectiveReaderThemeForDialog(String selectedReaderTheme) {
        if (ReaderDisplayModeHelper.isAutoNightActive(activity, runtime.settingsStore)) {
            return "night";
        }
        return selectedReaderTheme == null || selectedReaderTheme.isBlank() ? "paper" : selectedReaderTheme;
    }

    private void showCustomColorPickerDialog(Runnable onApply) {
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_color_picker, null, false);
        HsvColorPlaneView colorPlane = contentView.findViewById(R.id.color_plane);
        SeekBar hueSeek = contentView.findViewById(R.id.color_seek_hue);
        TextView rgbText = contentView.findViewById(R.id.color_text_rgb);
        TextView hexText = contentView.findViewById(R.id.color_text_hex);
        View colorPreview = contentView.findViewById(R.id.color_preview);
        Button applyButton = contentView.findViewById(R.id.color_button_apply);

        String customColor = runtime.settingsStore.getCustomTextColor();
        int currentColor = 0xFF374151;
        if (customColor != null && !customColor.isEmpty()) {
            try {
                currentColor = android.graphics.Color.parseColor(customColor);
            } catch (Exception ignore) {
            }
        }

        colorPlane.setColor(currentColor);
        hueSeek.setProgress(Math.round(colorPlane.getHue()) % 360);

        Runnable updatePreview = () -> {
            int color = colorPlane.getSelectedColor();
            int r = android.graphics.Color.red(color);
            int g = android.graphics.Color.green(color);
            int b = android.graphics.Color.blue(color);
            rgbText.setText("RGB: " + r + ", " + g + ", " + b);
            hexText.setText(String.format("HEX: #%02X%02X%02X", r, g, b));
            colorPreview.setBackgroundColor(color);
        };
        updatePreview.run();

        colorPlane.setOnColorChangeListener(color -> updatePreview.run());
        hueSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                colorPlane.setHue(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        applyButton.setOnClickListener(v -> {
            int color = colorPlane.getSelectedColor();
            int r = android.graphics.Color.red(color);
            int g = android.graphics.Color.green(color);
            int b = android.graphics.Color.blue(color);
            String hexColor = String.format("#%02X%02X%02X", r, g, b);
            runtime.settingsStore.setCustomTextColor(hexColor);
            runtime.settingsStore.setReaderTextColor("custom");
            dialog.dismiss();
            if (onApply != null) {
                onApply.run();
            }
        });
        dialogSupport.showStyledDialog(dialog);
    }

    private void updateStyleLabels(StyleDialogViews refs) {
        refs.fontValue.setText((refs.fontSeek.getProgress() + 12) + " sp");
        refs.fontWeightValue.setText(
                ReaderOptionCatalog.readerFontWeightLabelForProgress(refs.fontWeightSeek.getProgress())
                        + " ("
                        + ReaderOptionCatalog.fontWeightValueForProgress(refs.fontWeightSeek.getProgress())
                        + ")"
        );
        refs.lineValue.setText(refs.lineSeek.getProgress() + " px");
        refs.leftValue.setText(refs.leftSeek.getProgress() + " dp");
        refs.rightValue.setText(refs.rightSeek.getProgress() + " dp");
        refs.topValue.setText(refs.topSeek.getProgress() + " dp");
        refs.bottomValue.setText(refs.bottomSeek.getProgress() + " dp");
    }

    private static final class StyleDialogViews {
        final Spinner fontFamilySpinner;
        final Spinner textColorSpinner;
        final View contentContainer;
        final SeekBar fontSeek;
        final SeekBar fontWeightSeek;
        final SeekBar lineSeek;
        final SeekBar leftSeek;
        final SeekBar rightSeek;
        final SeekBar topSeek;
        final SeekBar bottomSeek;
        final SeekBar letterSpacingSeek;
        final SeekBar firstLineIndentSeek;
        final SeekBar paragraphSpacingSeek;
        final SeekBar backgroundBlurSeek;
        final TextView textColorValue;
        final TextView fontValue;
        final TextView fontWeightValue;
        final TextView lineValue;
        final TextView leftValue;
        final TextView rightValue;
        final TextView topValue;
        final TextView bottomValue;
        final TextView letterSpacingValue;
        final TextView firstLineIndentValue;
        final TextView paragraphSpacingValue;
        final TextView backgroundBlurValue;
        final Spinner uiThemeSpinner;
        final Spinner doublePageModeSpinner;
        final LinearLayout doublePageTurnStepLayout;
        final CheckBox keepScreenOn;
        final CheckBox showTitleCheck;
        final CheckBox doublePageCheck;
        final TextView backgroundText;
        final LinearLayout customThemeList;
        final Button paperThemeButton;
        final Button forestThemeButton;
        final Button nightThemeButton;
        final Button titleLeftButton;
        final Button titleCenterButton;
        final Button bodyJustifyButton;
        final Button bodyLeftButton;
        final Button customColorButton;
        final Button doublePageTurnOneButton;
        final Button doublePageTurnTwoButton;

        private StyleDialogViews(View root) {
            contentContainer = root.findViewById(R.id.style_content);
            fontFamilySpinner = root.findViewById(R.id.style_spinner_font_family);
            textColorSpinner = root.findViewById(R.id.style_spinner_text_color);
            fontSeek = root.findViewById(R.id.style_seek_font);
            fontWeightSeek = root.findViewById(R.id.style_seek_font_weight);
            lineSeek = root.findViewById(R.id.style_seek_line_spacing);
            leftSeek = root.findViewById(R.id.style_seek_left_padding);
            rightSeek = root.findViewById(R.id.style_seek_right_padding);
            topSeek = root.findViewById(R.id.style_seek_top_padding);
            bottomSeek = root.findViewById(R.id.style_seek_bottom_padding);
            letterSpacingSeek = root.findViewById(R.id.style_seek_letter_spacing);
            firstLineIndentSeek = root.findViewById(R.id.style_seek_first_line_indent);
            paragraphSpacingSeek = root.findViewById(R.id.style_seek_paragraph_spacing);
            backgroundBlurSeek = root.findViewById(R.id.style_seek_background_blur);
            textColorValue = root.findViewById(R.id.style_text_text_color);
            fontValue = root.findViewById(R.id.style_text_font);
            fontWeightValue = root.findViewById(R.id.style_text_font_weight);
            lineValue = root.findViewById(R.id.style_text_line_spacing);
            leftValue = root.findViewById(R.id.style_text_left_padding);
            rightValue = root.findViewById(R.id.style_text_right_padding);
            topValue = root.findViewById(R.id.style_text_top_padding);
            bottomValue = root.findViewById(R.id.style_text_bottom_padding);
            letterSpacingValue = root.findViewById(R.id.style_text_letter_spacing);
            firstLineIndentValue = root.findViewById(R.id.style_text_first_line_indent);
            paragraphSpacingValue = root.findViewById(R.id.style_text_paragraph_spacing);
            backgroundBlurValue = root.findViewById(R.id.style_text_background_blur);
            uiThemeSpinner = root.findViewById(R.id.style_spinner_ui_theme_mode);
            doublePageModeSpinner = root.findViewById(R.id.style_spinner_double_page_mode);
            doublePageTurnStepLayout = root.findViewById(R.id.style_layout_double_page_turn_step);
            keepScreenOn = root.findViewById(R.id.style_check_keep_screen_on);
            showTitleCheck = root.findViewById(R.id.style_check_show_title);
            doublePageCheck = root.findViewById(R.id.style_check_double_page);
            backgroundText = root.findViewById(R.id.style_text_background);
            customThemeList = root.findViewById(R.id.style_custom_theme_list);
            paperThemeButton = root.findViewById(R.id.style_button_theme_paper);
            forestThemeButton = root.findViewById(R.id.style_button_theme_forest);
            nightThemeButton = root.findViewById(R.id.style_button_theme_night);
            titleLeftButton = root.findViewById(R.id.style_button_title_left);
            titleCenterButton = root.findViewById(R.id.style_button_title_center);
            bodyJustifyButton = root.findViewById(R.id.style_button_body_justify);
            bodyLeftButton = root.findViewById(R.id.style_button_body_left);
            customColorButton = root.findViewById(R.id.style_button_custom_color);
            doublePageTurnOneButton = root.findViewById(R.id.style_button_double_page_turn_one);
            doublePageTurnTwoButton = root.findViewById(R.id.style_button_double_page_turn_two);
        }

        static StyleDialogViews bind(View root) {
            return new StyleDialogViews(root);
        }
    }
}
