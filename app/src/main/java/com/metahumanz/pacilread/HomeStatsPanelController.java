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
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.ui.LaunchSourceTransition;
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper;

import java.util.List;
import java.util.concurrent.ExecutorService;

public final class HomeStatsPanelController {
    private final Activity activity;
    private final JsonDatabase databaseHelper;
    private final SettingsStore settingsStore;
    private final ExecutorService executor;

    private final LinearLayout listLayout;
    private final TextView statusText;
    private final TextView totalText;
    private final TextView emptyText;
    private final Button todayButton;
    private final Button weekButton;
    private final Button yearButton;

    private String selectedPeriod = ReadingStatsUtils.PERIOD_TODAY;

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
        this.listLayout = activity.findViewById(R.id.layout_home_stats_list);
        this.statusText = activity.findViewById(R.id.text_home_stats_status);
        this.totalText = activity.findViewById(R.id.text_home_stats_total);
        this.emptyText = activity.findViewById(R.id.text_home_stats_empty);
        this.todayButton = activity.findViewById(R.id.button_home_stats_today);
        this.weekButton = activity.findViewById(R.id.button_home_stats_week);
        this.yearButton = activity.findViewById(R.id.button_home_stats_year);
        setupControls();
    }

    public void refreshIfVisible(int currentPage, boolean syncFirst) {
        if (currentPage != HomeNavigationController.PAGE_STATS || !settingsStore.isReadingTimeTrackingEnabled()) {
            return;
        }
        updatePeriodButtons();
        if (statusText != null) {
            statusText.setText(syncFirst ? "正在加载本地阅读统计..." : "正在刷新阅读统计...");
        }
        if (totalText != null) {
            totalText.setText("...");
        }
        executor.execute(() -> {
            ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(
                    selectedPeriod,
                    java.time.ZoneId.systemDefault()
            );
            int totalSeconds = databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null);
            List<ReadingBookStatRecord> records = databaseHelper.getReadingBookStats(range.startDateString(), range.endDateString());
            activity.runOnUiThread(() -> render(totalSeconds, records));
        });
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
        updatePeriodButtons();
    }

    private void selectPeriod(String period) {
        selectedPeriod = ReadingStatsUtils.normalizePeriodKey(period);
        updatePeriodButtons();
        refreshIfVisible(HomeNavigationController.PAGE_STATS, false);
    }

    private void updatePeriodButtons() {
        AppUiUtils.styleToggleButton(activity, todayButton, ReadingStatsUtils.PERIOD_TODAY.equals(selectedPeriod));
        AppUiUtils.styleToggleButton(activity, weekButton, ReadingStatsUtils.PERIOD_WEEK.equals(selectedPeriod));
        AppUiUtils.styleToggleButton(activity, yearButton, ReadingStatsUtils.PERIOD_YEAR.equals(selectedPeriod));
    }

    private void render(int totalSeconds, List<ReadingBookStatRecord> records) {
        if (statusText != null) {
            statusText.setText("当前展示的是本地阅读统计");
        }
        if (totalText != null) {
            totalText.setText(ReadingStatsUtils.formatDuration(totalSeconds));
        }
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
}
