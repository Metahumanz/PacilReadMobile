package com.metahumanz.pacilread;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.metahumanz.pacilread.model.ReadingBookStatRecord;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
import com.metahumanz.pacilread.stats.annual.AnnualReportBuilder;
import com.metahumanz.pacilread.stats.annual.AnnualReportData;
import com.metahumanz.pacilread.stats.annual.AnnualReportExportController;
import com.metahumanz.pacilread.stats.annual.AnnualReportInsight;
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.ui.LaunchSourceTransition;
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public final class HomeStatsPanelController {
    private final Activity activity;
    private final JsonDatabase databaseHelper;
    private final SettingsStore settingsStore;
    private final ExecutorService executor;
    private final ReadingStatsSyncManager readingStatsSyncManager;

    private final LinearLayout listLayout;
    private final TextView statusText;
    private final TextView totalText;
    private final TextView totalMetaText;
    private final TextView emptyText;
    private final TextView reportCardTitleText;
    private final TextView annualReportSummaryText;
    private final LinearLayout weekRangeModeLayout;
    private final Button todayButton;
    private final Button weekButton;
    private final Button yearButton;
    private final Button weekNaturalButton;
    private final Button weekRollingButton;
    private final Button annualReportButton;
    private final AnnualReportExportController annualReportExportController;

    private String selectedPeriod = ReadingStatsUtils.PERIOD_TODAY;
    private String selectedWeekMode = ReadingStatsUtils.WEEK_MODE_NATURAL;
    private AnnualReportData currentAnnualReport;
    private final AtomicInteger loadGeneration = new AtomicInteger();

    public HomeStatsPanelController(
            Activity activity,
            JsonDatabase databaseHelper,
            SettingsStore settingsStore,
            ExecutorService executor
    ) {
        this.activity = activity;
        this.databaseHelper = databaseHelper;
        this.settingsStore = settingsStore;
        this.executor = executor;
        this.readingStatsSyncManager = new ReadingStatsSyncManager(
                activity,
                databaseHelper,
                settingsStore,
                new WebDavClient(settingsStore)
        );
        this.listLayout = activity.findViewById(R.id.layout_home_stats_list);
        this.statusText = activity.findViewById(R.id.text_home_stats_status);
        this.totalText = activity.findViewById(R.id.text_home_stats_total);
        this.totalMetaText = activity.findViewById(R.id.text_home_stats_meta);
        this.emptyText = activity.findViewById(R.id.text_home_stats_empty);
        this.reportCardTitleText = activity.findViewById(R.id.text_home_report_card_title);
        this.annualReportSummaryText = activity.findViewById(R.id.text_home_annual_report_summary);
        this.weekRangeModeLayout = activity.findViewById(R.id.layout_home_week_range_mode);
        this.todayButton = activity.findViewById(R.id.button_home_stats_today);
        this.weekButton = activity.findViewById(R.id.button_home_stats_week);
        this.yearButton = activity.findViewById(R.id.button_home_stats_year);
        this.weekNaturalButton = activity.findViewById(R.id.button_home_week_natural);
        this.weekRollingButton = activity.findViewById(R.id.button_home_week_rolling);
        this.annualReportButton = activity.findViewById(R.id.button_home_generate_annual_report);
        this.annualReportExportController = new AnnualReportExportController(activity);
        setupControls();
    }

    public void refreshIfVisible(int currentPage, boolean syncFirst) {
        if (currentPage != HomeNavigationController.PAGE_STATS || !settingsStore.isReadingTimeTrackingEnabled()) {
            return;
        }
        updatePeriodButtons();
        int requestId = loadGeneration.incrementAndGet();
        String period = selectedPeriod;
        String weekMode = selectedWeekMode;
        boolean shouldSync = syncFirst && readingStatsSyncManager.canAutoSync();
        if (statusText != null) {
            statusText.setText(shouldSync
                    ? "正在同步云端阅读统计，本地数据已先显示"
                    : "正在刷新阅读统计...");
        }
        executor.execute(() -> {
            HomeStatsSnapshot localSnapshot = buildSnapshot(
                    period,
                    weekMode,
                    shouldSync ? "正在同步云端阅读统计，本地数据已先显示" : "当前展示的是本地阅读统计"
            );
            postSnapshot(requestId, localSnapshot);
            if (!shouldSync) {
                return;
            }
            String syncMessage;
            try {
                readingStatsSyncManager.downloadAndMergeReadingStats();
                syncMessage = "已同步云端阅读统计";
            } catch (Exception error) {
                syncMessage = "云端同步失败，当前展示本地统计：" + readableError(error);
            }
            HomeStatsSnapshot syncedSnapshot = buildSnapshot(period, weekMode, syncMessage);
            postSnapshot(requestId, syncedSnapshot);
        });
    }

    private HomeStatsSnapshot buildSnapshot(String period, String weekMode, String syncMessage) {
        ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(
                period,
                java.time.ZoneId.systemDefault(),
                weekMode
        );
        HomeStatsSnapshot snapshot = new HomeStatsSnapshot();
        snapshot.syncMessage = syncMessage;
        snapshot.totalSeconds = databaseHelper.getReadingDurationSeconds(
                range.startDateString(),
                range.endDateString(),
                null
        );
        snapshot.records = databaseHelper.getReadingBookStats(range.startDateString(), range.endDateString());
        snapshot.annualReport = AnnualReportBuilder.buildGlobal(databaseHelper, ZoneId.systemDefault(), period, weekMode);
        return snapshot;
    }

    private void postSnapshot(int requestId, HomeStatsSnapshot snapshot) {
        activity.runOnUiThread(() -> {
            if (requestId != loadGeneration.get()) {
                return;
            }
            render(snapshot.totalSeconds, snapshot.records, snapshot.annualReport, snapshot.syncMessage);
        });
    }

    private static final class HomeStatsSnapshot {
        int totalSeconds;
        List<ReadingBookStatRecord> records;
        AnnualReportData annualReport;
        String syncMessage;
    }

    private void setupControls() {
        if (todayButton != null) {
            todayButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_TODAY));
        }
        if (weekButton != null) {
            weekButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_WEEK));
        }
        if (yearButton != null) {
            yearButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_YEAR));
        }
        if (weekNaturalButton != null) {
            weekNaturalButton.setOnClickListener(v -> selectWeekMode(ReadingStatsUtils.WEEK_MODE_NATURAL));
        }
        if (weekRollingButton != null) {
            weekRollingButton.setOnClickListener(v -> selectWeekMode(ReadingStatsUtils.WEEK_MODE_ROLLING));
        }
        if (annualReportButton != null) {
            annualReportButton.setOnClickListener(v -> {
                if (currentAnnualReport == null || !currentAnnualReport.hasReadingData()) {
                    AppUiUtils.showToast(activity, "暂无可生成的" + reportCardTitleFor(selectedPeriod));
                    return;
                }
                annualReportExportController.showPreview(currentAnnualReport);
            });
        }
        updatePeriodButtons();
    }

    private void selectPeriod(String period) {
        selectedPeriod = ReadingStatsUtils.normalizePeriodKey(period);
        updatePeriodButtons();
        refreshIfVisible(HomeNavigationController.PAGE_STATS, false);
    }

    private void selectWeekMode(String weekMode) {
        selectedWeekMode = ReadingStatsUtils.normalizeWeekMode(weekMode);
        updatePeriodButtons();
        if (ReadingStatsUtils.PERIOD_WEEK.equals(selectedPeriod)) {
            refreshIfVisible(HomeNavigationController.PAGE_STATS, false);
        }
    }

    private void updatePeriodButtons() {
        AppUiUtils.styleToggleButton(activity, todayButton, ReadingStatsUtils.PERIOD_TODAY.equals(selectedPeriod));
        AppUiUtils.styleToggleButton(activity, weekButton, ReadingStatsUtils.PERIOD_WEEK.equals(selectedPeriod));
        AppUiUtils.styleToggleButton(activity, yearButton, ReadingStatsUtils.PERIOD_YEAR.equals(selectedPeriod));
        if (weekRangeModeLayout != null) {
            weekRangeModeLayout.setVisibility(ReadingStatsUtils.PERIOD_WEEK.equals(selectedPeriod) ? View.VISIBLE : View.GONE);
        }
        AppUiUtils.styleToggleButton(activity, weekNaturalButton, ReadingStatsUtils.WEEK_MODE_NATURAL.equals(selectedWeekMode));
        AppUiUtils.styleToggleButton(activity, weekRollingButton, ReadingStatsUtils.WEEK_MODE_ROLLING.equals(selectedWeekMode));
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return annualReportExportController.onActivityResult(requestCode, resultCode, data);
    }

    private void render(int totalSeconds, List<ReadingBookStatRecord> records,
                        AnnualReportData annualReport, String syncMessage) {
        if (statusText != null) {
            statusText.setText(syncMessage);
        }
        if (totalText != null) {
            totalText.setText(ReadingStatsUtils.formatDuration(totalSeconds));
        }
        if (totalMetaText != null) {
            totalMetaText.setText(formatStatsMeta(annualReport));
        }
        renderAnnualReport(annualReport);
        if (listLayout == null || emptyText == null) {
            return;
        }
        listLayout.removeAllViews();
        boolean empty = records == null || records.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (ReadingBookStatRecord record : records) {
            View row = inflater.inflate(R.layout.item_reading_book_stat, listLayout, false);
            TextView titleText = row.findViewById(R.id.text_stat_row_title);
            TextView authorText = row.findViewById(R.id.text_stat_row_author);
            TextView metaText = row.findViewById(R.id.text_stat_row_meta);
            TextView durationText = row.findViewById(R.id.text_stat_row_duration);
            titleText.setText(ReadingStatsUtils.safeBookTitle(record.bookTitle));
            authorText.setText(ReadingStatsUtils.safeBookAuthor(record.bookAuthor));
            metaText.setText(record.localBookId > 0L ? "点击查看本书统计详情" : "当前设备没有这本书的本地副本");
            durationText.setText(ReadingStatsUtils.formatDuration(record.totalDurationSeconds));
            if (record.localBookId > 0L) {
                row.setOnClickListener(v -> {
                    Intent intent = new Intent(activity, ReadingStatsActivity.class);
                    intent.putExtra("book_id", record.localBookId);
                    if (TransitionMotionModeHelper.isFluidMode(settingsStore)) {
                        LaunchSourceTransition.attach(intent, v);
                    }
                    activity.startActivity(intent);
                });
            } else {
                row.setEnabled(false);
                row.setAlpha(0.78f);
            }
            listLayout.addView(row);
        }
    }

    private void renderAnnualReport(AnnualReportData annualReport) {
        currentAnnualReport = annualReport;
        boolean hasData = annualReport != null && annualReport.hasReadingData();
        if (annualReportButton != null) {
            annualReportButton.setEnabled(hasData);
            annualReportButton.setAlpha(hasData ? 1f : 0.55f);
            annualReportButton.setText("生成" + (annualReport == null ? reportCardTitleFor(selectedPeriod) : annualReport.reportKindLabel()));
        }
        if (reportCardTitleText != null) {
            reportCardTitleText.setText(annualReport == null ? reportCardTitleFor(selectedPeriod) : annualReport.reportKindLabel());
        }
        if (annualReportSummaryText == null) {
            return;
        }
        if (!hasData) {
            annualReportSummaryText.setText("当前范围还没有足够的阅读统计");
            return;
        }
        annualReportSummaryText.setText(annualReport.periodTitle + " · "
                + ReadingStatsUtils.formatDuration(annualReport.totalSeconds)
                + " · " + annualReport.readingDays + " 个阅读日\n"
                + AnnualReportInsight.sentence(annualReport));
    }

    private String reportCardTitleFor(String period) {
        if (ReadingStatsUtils.PERIOD_WEEK.equals(period)) {
            return "周报";
        }
        if (ReadingStatsUtils.PERIOD_YEAR.equals(period)) {
            return "年度报告";
        }
        return "每日报告";
    }

    private String formatStatsMeta(AnnualReportData report) {
        int totalChars = report == null ? 0 : Math.max(report.totalChars, 0);
        int readingDays = report == null ? 0 : Math.max(report.readingDays, 0);
        int longestStreak = report == null ? 0 : Math.max(report.longestStreak, 0);
        return "阅读字数 " + formatNumber(totalChars) + " 字"
                + " · 阅读天数 " + readingDays + " 天"
                + " · 最长连续 " + longestStreak + " 天";
    }

    private String formatNumber(int value) {
        return String.format(Locale.SIMPLIFIED_CHINESE, "%,d", Math.max(value, 0));
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
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}
