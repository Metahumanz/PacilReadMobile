package com.metahumanz.pacilread.reader.modern.playback;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.metahumanz.pacilread.R;
import com.metahumanz.pacilread.reader.modern.ModernReaderActivity;
import com.metahumanz.pacilread.reader.modern.ReaderRuntime;
import com.metahumanz.pacilread.reader.modern.ReaderSessionState;
import com.metahumanz.pacilread.reader.modern.ReaderUiUtils;
import com.metahumanz.pacilread.reader.modern.ReaderViewRefs;
import com.metahumanz.pacilread.reader.modern.dialog.ReaderDialogSupport;
import com.metahumanz.pacilread.reader.modern.paging.ReaderNavigationController;
import com.metahumanz.pacilread.reader.modern.ui.ReaderChromeController;

import java.util.Locale;

public final class ReaderAutoPageController {
    private final ModernReaderActivity activity;
    private final ReaderRuntime runtime;
    private final ReaderViewRefs views;
    private final ReaderSessionState state;
    private final ReaderDialogSupport dialogSupport;
    private final Runnable autoPageRunnable = this::onAutoPageTick;

    private ReaderNavigationController navigation;
    private ReaderChromeController chrome;

    public ReaderAutoPageController(
            ModernReaderActivity activity,
            ReaderRuntime runtime,
            ReaderViewRefs views,
            ReaderSessionState state,
            ReaderUiUtils ui,
            ReaderDialogSupport dialogSupport
    ) {
        this.activity = activity;
        this.runtime = runtime;
        this.views = views;
        this.state = state;
        this.dialogSupport = dialogSupport;
    }

    public void attachControllers(ReaderNavigationController navigation, ReaderChromeController chrome) {
        this.navigation = navigation;
        this.chrome = chrome;
    }

    public boolean isActive() {
        return state.autoPageActive;
    }

    public void stopAutoPage() {
        state.autoPageActive = false;
        runtime.mainHandler.removeCallbacks(autoPageRunnable);
        chrome.styleReaderMenuButton(views.autoPageButton, false);
    }

    public void startAutoPage() {
        state.autoPageActive = true;
        chrome.styleReaderMenuButton(views.autoPageButton, true);
        scheduleNextAutoPageTick();
    }

    public void scheduleNextAutoPageTick() {
        runtime.mainHandler.removeCallbacks(autoPageRunnable);
        if (!state.autoPageActive) {
            return;
        }
        runtime.mainHandler.postDelayed(autoPageRunnable, runtime.settingsStore.getAutoPageSeconds() * 1000L);
    }

    public void showAutoPageDialog() {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_auto_page, null, false);
        SeekBar seekBar = content.findViewById(R.id.auto_page_seek);
        TextView valueText = content.findViewById(R.id.auto_page_value);
        Button toggleButton = content.findViewById(R.id.auto_page_button_toggle);
        seekBar.setProgress(runtime.settingsStore.getAutoPageSeconds() - 1);
        valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", runtime.settingsStore.getAutoPageSeconds()));
        seekBar.setOnSeekBarChangeListener(new ReaderDialogSupport.SimpleSeekListener(() -> {
            int seconds = seekBar.getProgress() + 1;
            valueText.setText(String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", seconds));
            runtime.settingsStore.setAutoPageSeconds(seconds);
            if (state.autoPageActive) {
                scheduleNextAutoPageTick();
            }
        }));
        toggleButton.setText(state.autoPageActive ? "停止自动翻页" : "开始自动翻页");
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(content).create();
        toggleButton.setOnClickListener(v -> {
            if (state.autoPageActive) {
                stopAutoPage();
            } else {
                startAutoPage();
            }
            dialog.dismiss();
        });
        dialogSupport.showStyledDialog(dialog);
    }

    private void onAutoPageTick() {
        if (!state.autoPageActive) {
            return;
        }
        if (!state.controlsVisible && !state.isAnimating && !state.interactivePaging) {
            navigation.pageDown();
        }
        scheduleNextAutoPageTick();
    }
}
