package com.metahumanz.pacilread.reader.modern.dialog;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.config.ReaderOptionCatalog;
import com.metahumanz.pacilread.reader.modern.content.ReaderContentController;
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderStyleController;

public final class ReaderOptionsDialogController {
    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderSessionState state;
    private final ReaderDialogSupport dialogSupport;
    private final ReaderContentController content;
    private final ReaderNavigationController navigation;
    private final ReaderStyleController style;
    private final ReaderChromeController chrome;

    public ReaderOptionsDialogController(
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
        this.dialogSupport = dialogSupport;
        this.content = content;
        this.navigation = navigation;
        this.style = style;
        this.chrome = chrome;
    }

    public void showReaderOptionsDialog() {
        View contentView = LayoutInflater.from(activity).inflate(R.layout.dialog_reader_options, null, false);
        OptionsDialogViews refs = OptionsDialogViews.bind(contentView);
        dialogSupport.applyTocStyleFullscreenInsets(contentView, refs.contentContainer);
        refs.titleInput.setText(state.book == null ? "" : state.book.title);
        refs.authorInput.setText(state.book == null ? "" : state.book.author);
        refs.showTitleCheck.setChecked(runtime.settingsStore.isChapterTitleVisible());
        refs.persistentActionsCheck.setChecked(runtime.settingsStore.isReaderMenuPersistentActionsEnabled());

        ArrayAdapter<String> flipAdapter = dialogSupport.buildSpinnerAdapter(new String[]{"覆盖", "平移", "仿真", "滚动", "无动画"});
        refs.flipSpinner.setAdapter(flipAdapter);
        refs.flipSpinner.setSelection(ReaderOptionCatalog.indexOf(ReaderOptionCatalog.FLIP_KEYS, runtime.settingsStore.getFlipMode(), 0), false);

        String[] speedKeys = new String[]{"fast", "medium", "slow"};
        ArrayAdapter<String> speedAdapter = dialogSupport.buildSpinnerAdapter(new String[]{"较快", "适中", "较慢"});
        refs.flipSpeedSpinner.setAdapter(speedAdapter);
        refs.flipSpeedSpinner.setSelection(ReaderOptionCatalog.indexOf(speedKeys, runtime.settingsStore.getFlipSpeed(), 1), false);

        final String[] sliderMode = new String[]{runtime.settingsStore.getReaderSliderMode()};
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(contentView).create();
        dialogSupport.addAlignedCloseButton(contentView, R.id.options_title, refs.contentContainer, dialog);
        chrome.styleThemeButton(refs.sliderBookButton, "book".equals(sliderMode[0]));
        chrome.styleThemeButton(refs.sliderChapterButton, "chapter".equals(sliderMode[0]));

        ArrayAdapter<String> hudAdapter = dialogSupport.buildSpinnerAdapter(
                new String[]{"无", "书名", "章节名", "书名 / 章节名", "现在时间", "系统电量", "本章页数进度", "全书进度", "页数及进度", "时间及电量"}
        );
        refs.topLeftSpinner.setAdapter(hudAdapter);
        refs.topLeftSpinner.setSelection(ReaderOptionCatalog.indexOf(ReaderOptionCatalog.HUD_KEYS, runtime.settingsStore.getHudTopLeft(), 0), false);
        refs.topCenterSpinner.setAdapter(hudAdapter);
        refs.topCenterSpinner.setSelection(ReaderOptionCatalog.indexOf(ReaderOptionCatalog.HUD_KEYS, runtime.settingsStore.getHudTopCenter(), 0), false);
        refs.topRightSpinner.setAdapter(hudAdapter);
        refs.topRightSpinner.setSelection(ReaderOptionCatalog.indexOf(ReaderOptionCatalog.HUD_KEYS, runtime.settingsStore.getHudTopRight(), 0), false);
        refs.bottomLeftSpinner.setAdapter(hudAdapter);
        refs.bottomLeftSpinner.setSelection(ReaderOptionCatalog.indexOf(ReaderOptionCatalog.HUD_KEYS, runtime.settingsStore.getHudBottomLeft(), 0), false);
        refs.bottomCenterSpinner.setAdapter(hudAdapter);
        refs.bottomCenterSpinner.setSelection(ReaderOptionCatalog.indexOf(ReaderOptionCatalog.HUD_KEYS, runtime.settingsStore.getHudBottomCenter(), 0), false);
        refs.bottomRightSpinner.setAdapter(hudAdapter);
        refs.bottomRightSpinner.setSelection(ReaderOptionCatalog.indexOf(ReaderOptionCatalog.HUD_KEYS, runtime.settingsStore.getHudBottomRight(), 0), false);

        refs.hudTopMarginSeek.setProgress(runtime.settingsStore.getHudTopMarginDp());
        refs.hudBottomMarginSeek.setProgress(runtime.settingsStore.getHudBottomMarginDp());
        updateHudMarginLabels(refs);

        Runnable autoApply = () -> {
            String title = refs.titleInput.getText().toString().trim();
            String author = refs.authorInput.getText().toString().trim();
            if (title.isEmpty()) {
                title = "未命名书籍";
            }
            String finalTitle = title;
            String finalAuthor = author;
            int anchorOffset = content.currentCharOffset();
            boolean chapterTitleVisibilityChanged = runtime.settingsStore.isChapterTitleVisible() != refs.showTitleCheck.isChecked();
            if (state.book != null) {
                state.book.title = finalTitle;
                state.book.author = finalAuthor;
            }
            runtime.settingsStore.setFlipMode(ReaderOptionCatalog.FLIP_KEYS[refs.flipSpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setFlipSpeed(speedKeys[refs.flipSpeedSpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setReaderSliderMode(sliderMode[0]);
            runtime.settingsStore.setChapterTitleVisible(refs.showTitleCheck.isChecked());
            runtime.settingsStore.setReaderMenuPersistentActionsEnabled(refs.persistentActionsCheck.isChecked());
            runtime.settingsStore.setHudTopLeft(ReaderOptionCatalog.HUD_KEYS[refs.topLeftSpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setHudTopCenter(ReaderOptionCatalog.HUD_KEYS[refs.topCenterSpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setHudTopRight(ReaderOptionCatalog.HUD_KEYS[refs.topRightSpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setHudBottomLeft(ReaderOptionCatalog.HUD_KEYS[refs.bottomLeftSpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setHudBottomCenter(ReaderOptionCatalog.HUD_KEYS[refs.bottomCenterSpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setHudBottomRight(ReaderOptionCatalog.HUD_KEYS[refs.bottomRightSpinner.getSelectedItemPosition()]);
            runtime.settingsStore.setHudTopMarginDp(refs.hudTopMarginSeek.getProgress());
            runtime.settingsStore.setHudBottomMarginDp(refs.hudBottomMarginSeek.getProgress());
            if (chapterTitleVisibilityChanged) {
                content.scheduleReflowAfterLayout(state.currentChapterIndex, anchorOffset);
            } else {
                chrome.updateReaderLayoutInsets();
                chrome.applyMenuLayoutMode();
                chrome.updateUiAfterPageChange();
            }
            runtime.executor.execute(() -> runtime.databaseHelper.updateBookInfo(state.bookId, finalTitle, finalAuthor));
        };

        TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                autoApply.run();
            }
        };
        refs.titleInput.addTextChangedListener(textWatcher);
        refs.authorInput.addTextChangedListener(textWatcher);
        refs.showTitleCheck.setOnCheckedChangeListener((buttonView, isChecked) -> autoApply.run());
        refs.persistentActionsCheck.setOnCheckedChangeListener((buttonView, isChecked) -> autoApply.run());
        SeekBar.OnSeekBarChangeListener hudMarginSeekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateHudMarginLabels(refs);
                if (fromUser) {
                    autoApply.run();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        refs.hudTopMarginSeek.setOnSeekBarChangeListener(hudMarginSeekListener);
        refs.hudBottomMarginSeek.setOnSeekBarChangeListener(hudMarginSeekListener);

        android.widget.AdapterView.OnItemSelectedListener flipListener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                autoApply.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        };
        refs.flipSpinner.setOnItemSelectedListener(flipListener);
        refs.flipSpeedSpinner.setOnItemSelectedListener(flipListener);

        final Spinner[] allHudSpinners = {
                refs.topLeftSpinner, refs.topCenterSpinner, refs.topRightSpinner,
                refs.bottomLeftSpinner, refs.bottomCenterSpinner, refs.bottomRightSpinner
        };
        final boolean[] isAdjustingHudSpinners = new boolean[]{false};

        android.widget.AdapterView.OnItemSelectedListener hudListener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position > 0 && !isAdjustingHudSpinners[0]) {
                    isAdjustingHudSpinners[0] = true;
                    for (Spinner spinner : allHudSpinners) {
                        if (spinner != parent && spinner.getSelectedItemPosition() == position) {
                            spinner.setSelection(0, false);
                        }
                    }
                    isAdjustingHudSpinners[0] = false;
                }
                autoApply.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        };
        refs.topLeftSpinner.setOnItemSelectedListener(hudListener);
        refs.topCenterSpinner.setOnItemSelectedListener(hudListener);
        refs.topRightSpinner.setOnItemSelectedListener(hudListener);
        refs.bottomLeftSpinner.setOnItemSelectedListener(hudListener);
        refs.bottomCenterSpinner.setOnItemSelectedListener(hudListener);
        refs.bottomRightSpinner.setOnItemSelectedListener(hudListener);

        refs.sliderBookButton.setOnClickListener(v -> {
            sliderMode[0] = "book";
            chrome.styleThemeButton(refs.sliderBookButton, true);
            chrome.styleThemeButton(refs.sliderChapterButton, false);
            autoApply.run();
        });
        refs.sliderChapterButton.setOnClickListener(v -> {
            sliderMode[0] = "chapter";
            chrome.styleThemeButton(refs.sliderBookButton, false);
            chrome.styleThemeButton(refs.sliderChapterButton, true);
            autoApply.run();
        });

        dialogSupport.showImmersiveFullscreenDialog(dialog, state.controlsVisible);
        contentView.requestApplyInsets();
    }

    private void updateHudMarginLabels(OptionsDialogViews refs) {
        refs.hudTopMarginValue.setText(refs.hudTopMarginSeek.getProgress() + " dp");
        refs.hudBottomMarginValue.setText(refs.hudBottomMarginSeek.getProgress() + " dp");
    }

    private static final class OptionsDialogViews {
        final EditText titleInput;
        final EditText authorInput;
        final View contentContainer;
        final CheckBox showTitleCheck;
        final CheckBox persistentActionsCheck;
        final Spinner flipSpinner;
        final Spinner flipSpeedSpinner;
        final Button sliderBookButton;
        final Button sliderChapterButton;
        final SeekBar hudTopMarginSeek;
        final SeekBar hudBottomMarginSeek;
        final TextView hudTopMarginValue;
        final TextView hudBottomMarginValue;
        final Spinner topLeftSpinner;
        final Spinner topCenterSpinner;
        final Spinner topRightSpinner;
        final Spinner bottomLeftSpinner;
        final Spinner bottomCenterSpinner;
        final Spinner bottomRightSpinner;

        private OptionsDialogViews(View root) {
            contentContainer = root.findViewById(R.id.options_content);
            titleInput = root.findViewById(R.id.options_input_title);
            authorInput = root.findViewById(R.id.options_input_author);
            showTitleCheck = root.findViewById(R.id.options_check_show_title);
            persistentActionsCheck = root.findViewById(R.id.options_check_persistent_actions);
            flipSpinner = root.findViewById(R.id.options_spinner_flip_mode);
            flipSpeedSpinner = root.findViewById(R.id.options_spinner_flip_speed);
            sliderBookButton = root.findViewById(R.id.options_button_slider_book);
            sliderChapterButton = root.findViewById(R.id.options_button_slider_chapter);
            hudTopMarginSeek = root.findViewById(R.id.options_seek_hud_top_margin);
            hudBottomMarginSeek = root.findViewById(R.id.options_seek_hud_bottom_margin);
            hudTopMarginValue = root.findViewById(R.id.options_text_hud_top_margin_value);
            hudBottomMarginValue = root.findViewById(R.id.options_text_hud_bottom_margin_value);
            topLeftSpinner = root.findViewById(R.id.options_spinner_hud_tl);
            topCenterSpinner = root.findViewById(R.id.options_spinner_hud_tc);
            topRightSpinner = root.findViewById(R.id.options_spinner_hud_tr);
            bottomLeftSpinner = root.findViewById(R.id.options_spinner_hud_bl);
            bottomCenterSpinner = root.findViewById(R.id.options_spinner_hud_bc);
            bottomRightSpinner = root.findViewById(R.id.options_spinner_hud_br);
        }

        static OptionsDialogViews bind(View root) {
            return new OptionsDialogViews(root);
        }
    }
}
