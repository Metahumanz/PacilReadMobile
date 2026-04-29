package com.metahumanz.pacilread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import com.metahumanz.pacilread.stats.ReadingStatsUtils;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager;
import com.metahumanz.pacilread.ui.LaunchSourceTransition;

import java.util.concurrent.ExecutorService;

final class SettingsReadingStatsController {
    interface Callback {
        boolean isSettingsBusy();
        void saveSettings();
        void setBusy(boolean busy);
        void setStatusText(String text);
        void showToast(String text);
    }

    private final Activity activity;
    private final ReaderDatabaseHelper databaseHelper;
    private final SettingsStore settingsStore;
    private final ReadingStatsSyncManager readingStatsSyncManager;
    private final ExecutorService executor;
    private final Callback callback;
    private final CheckBox readingTimeTrackingCheck;
    private final Button statsPeriodTodayButton;
    private final Button statsPeriodWeekButton;
    private final Button statsPeriodYearButton;
    private final Button openReadingStatsButton;
    private final View readingStatsContentLayout;
    private final TextView readingStatsHintText;
    private final TextView readingStatsTotalText;

    private boolean bindingValues = false;
    private String selectedPeriod = ReadingStatsUtils.PERIOD_TODAY;

    SettingsReadingStatsController(
            Activity activity,
            ReaderDatabaseHelper databaseHelper,
            SettingsStore settingsStore,
            ReadingStatsSyncManager readingStatsSyncManager,
            ExecutorService executor,
            Callback callback
    ) {
        this.activity = activity;
        this.databaseHelper = databaseHelper;
        this.settingsStore = settingsStore;
        this.readingStatsSyncManager = readingStatsSyncManager;
        this.executor = executor;
        this.callback = callback;
        this.readingTimeTrackingCheck = activity.findViewById(R.id.check_reading_time_tracking);
        this.statsPeriodTodayButton = activity.findViewById(R.id.button_stats_period_today);
        this.statsPeriodWeekButton = activity.findViewById(R.id.button_stats_period_week);
        this.statsPeriodYearButton = activity.findViewById(R.id.button_stats_period_year);
        this.openReadingStatsButton = activity.findViewById(R.id.button_open_reading_stats);
        this.readingStatsContentLayout = activity.findViewById(R.id.layout_reading_stats_content);
        this.readingStatsHintText = activity.findViewById(R.id.text_reading_stats_hint);
        this.readingStatsTotalText = activity.findViewById(R.id.text_reading_stats_total);
        setupControls();
    }

    void bindValues() {
        bindingValues = true;
        if (readingTimeTrackingCheck != null) {
            readingTimeTrackingCheck.setChecked(settingsStore.isReadingTimeTrackingEnabled());
        }
        updatePeriodButtons();
        updateVisibility();
        bindingValues = false;
    }

    void saveValues() {
        if (readingTimeTrackingCheck != null) {
            settingsStore.setReadingTimeTrackingEnabled(readingTimeTrackingCheck.isChecked());
        }
        updateVisibility();
    }

    void refreshSummary(boolean syncFirst) {
        updateVisibility();
        if (readingStatsTotalText == null || !isTrackingChecked()) {
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
            ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(selectedPeriod, java.time.ZoneId.systemDefault());
            int totalSeconds = databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null);
            String finalSyncError = syncError;
            activity.runOnUiThread(() -> {
                if (readingStatsHintText != null) {
                    String label = ReadingStatsUtils.PERIOD_WEEK.equals(selectedPeriod)
                            ? "本周阅读总时长"
                            : ReadingStatsUtils.PERIOD_YEAR.equals(selectedPeriod)
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

    void setBusy(boolean busy) {
        if (readingTimeTrackingCheck != null) {
            readingTimeTrackingCheck.setEnabled(!busy);
        }
        if (openReadingStatsButton != null) {
            openReadingStatsButton.setEnabled(!busy);
        }
    }

    private void setupControls() {
        if (statsPeriodTodayButton != null) {
            statsPeriodTodayButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_TODAY));
        }
        if (statsPeriodWeekButton != null) {
            statsPeriodWeekButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_WEEK));
        }
        if (statsPeriodYearButton != null) {
            statsPeriodYearButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_YEAR));
        }
        if (openReadingStatsButton != null) {
            openReadingStatsButton.setOnClickListener(v -> {
                Intent intent = new Intent(activity, ReadingStatsActivity.class);
                LaunchSourceTransition.attach(intent, v);
                activity.startActivity(intent);
            });
        }
        if (readingTimeTrackingCheck != null) {
            readingTimeTrackingCheck.setOnCheckedChangeListener((buttonView, isChecked) -> handleTrackingToggle(isChecked));
        }
    }

    private void selectPeriod(String periodKey) {
        selectedPeriod = ReadingStatsUtils.normalizePeriodKey(periodKey);
        updatePeriodButtons();
        refreshSummary(false);
    }

    private void updatePeriodButtons() {
        AppUiUtils.styleToggleButton(activity, statsPeriodTodayButton, ReadingStatsUtils.PERIOD_TODAY.equals(selectedPeriod));
        AppUiUtils.styleToggleButton(activity, statsPeriodWeekButton, ReadingStatsUtils.PERIOD_WEEK.equals(selectedPeriod));
        AppUiUtils.styleToggleButton(activity, statsPeriodYearButton, ReadingStatsUtils.PERIOD_YEAR.equals(selectedPeriod));
    }

    private void updateVisibility() {
        if (readingStatsContentLayout != null) {
            readingStatsContentLayout.setVisibility(isTrackingChecked() ? View.VISIBLE : View.GONE);
        }
    }

    private void handleTrackingToggle(boolean enabled) {
        if (bindingValues || callback.isSettingsBusy()) {
            return;
        }
        if (enabled) {
            callback.saveSettings();
            refreshSummary(true);
            return;
        }
        callback.setBusy(true);
        executor.execute(() -> {
            boolean hasStats = databaseHelper.hasAnyReadingStats();
            activity.runOnUiThread(() -> {
                callback.setBusy(false);
                if (!hasStats) {
                    callback.saveSettings();
                    updateVisibility();
                    return;
                }
                showDisableDialog();
            });
        });
    }

    private void showDisableDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("关闭阅读时长记录")
                .setMessage("已有阅读统计数据。你可以只隐藏历史，或同时清空本地与云端统计。")
                .setNegativeButton("取消", (dialog, which) -> revertTrackingToggle(true))
                .setNeutralButton("只隐藏", (dialog, which) -> {
                    callback.saveSettings();
                    updateVisibility();
                })
                .setPositiveButton("清空历史", (dialog, which) -> clearHistory())
                .show();
    }

    private void clearHistory() {
        callback.setBusy(true);
        callback.setStatusText("正在清理阅读统计...");
        executor.execute(() -> {
            try {
                readingStatsSyncManager.clearRemoteReadingStats();
                databaseHelper.clearReadingStats();
                activity.runOnUiThread(() -> {
                    callback.setBusy(false);
                    callback.saveSettings();
                    updateVisibility();
                    refreshSummary(false);
                    callback.setStatusText("阅读统计已清空");
                    callback.showToast("已清空阅读统计");
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    callback.setBusy(false);
                    revertTrackingToggle(true);
                    callback.setStatusText("清理失败: " + error.getMessage());
                    callback.showToast("清理阅读统计失败");
                });
            }
        });
    }

    private void revertTrackingToggle(boolean checked) {
        bindingValues = true;
        if (readingTimeTrackingCheck != null) {
            readingTimeTrackingCheck.setChecked(checked);
        }
        bindingValues = false;
        updateVisibility();
    }

    private boolean isTrackingChecked() {
        return readingTimeTrackingCheck != null && readingTimeTrackingCheck.isChecked();
    }
}
