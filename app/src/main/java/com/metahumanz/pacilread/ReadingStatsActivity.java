package com.metahumanz.pacilread;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReadingBookStatRecord;
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord;
import com.metahumanz.pacilread.stats.annual.AnnualReportBuilder;
import com.metahumanz.pacilread.stats.annual.AnnualReportData;
import com.metahumanz.pacilread.stats.annual.AnnualReportExportController;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
import com.metahumanz.pacilread.storage.JsonDatabase;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.theme.ThemedActivity;
import com.metahumanz.pacilread.theme.ThemeModeHelper;
import com.metahumanz.pacilread.ui.ActivityTransitionCompat;
import com.metahumanz.pacilread.ui.BookCoverViewHelper;
import com.metahumanz.pacilread.ui.LaunchSourceTransition;
import com.metahumanz.pacilread.ui.PredictiveBackScaleController;
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper;

import java.text.DateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ReadingStatsActivity extends ThemedActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DateFormat dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE);
    private final AtomicInteger loadGeneration = new AtomicInteger();

    private JsonDatabase databaseHelper;
    private SettingsStore settingsStore;
    private ReadingStatsSyncManager readingStatsSyncManager;

    private TextView pageTitleText;
    private TextView syncStatusText;
    private TextView scopeLabelText;
    private TextView scopeTotalText;
    private TextView scopeCharsText;
    private TextView bookTitleText;
    private TextView bookAuthorText;
    private TextView bookProgressText;
    private TextView bookLastReadText;
    private TextView bookSpeedText;
    private TextView bookEtaText;
    private TextView coverFallbackText;
    private TextView listEmptyText;
    private ImageView coverImage;
    private LinearLayout bookMetaLayout;
    private LinearLayout listCardLayout;
    private LinearLayout bookStatsListLayout;
    private LinearLayout readingCalendarLayout;
    private LinearLayout annualReportLayout;
    private Button periodTodayButton;
    private Button periodWeekButton;
    private Button periodYearButton;
    private Button shareAnnualReportButton;

    private String selectedPeriod = ReadingStatsUtils.PERIOD_TODAY;
    private long bookId = -1L;
    private LaunchSourceTransition.Source launchSource;
    private boolean finishingWithSource;
    private AnnualReportData currentAnnualReport;
    private AnnualReportExportController annualReportExportController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reading_stats);

        databaseHelper = JsonDatabase.getInstance(this);
        settingsStore = new SettingsStore(this);
        readingStatsSyncManager = new ReadingStatsSyncManager(this, databaseHelper, settingsStore, new WebDavClient(settingsStore));
        annualReportExportController = new AnnualReportExportController(this);

        bookId = getIntent().getLongExtra("book_id", -1L);
        launchSource = LaunchSourceTransition.fromIntentSource(getIntent());

        bindViews();
        setupControls();
        installPredictiveBack();
        updatePeriodButtons();
        renderModeShell();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStats(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (annualReportExportController != null
                && annualReportExportController.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void bindViews() {
        pageTitleText = findViewById(R.id.text_page_title);
        syncStatusText = findViewById(R.id.text_sync_status);
        scopeLabelText = findViewById(R.id.text_scope_label);
        scopeTotalText = findViewById(R.id.text_scope_total);
        scopeCharsText = findViewById(R.id.text_scope_chars);
        bookTitleText = findViewById(R.id.text_book_title);
        bookAuthorText = findViewById(R.id.text_book_author);
        bookProgressText = findViewById(R.id.text_book_progress);
        bookLastReadText = findViewById(R.id.text_book_last_read);
        bookSpeedText = findViewById(R.id.text_book_speed);
        bookEtaText = findViewById(R.id.text_book_eta);
        coverFallbackText = findViewById(R.id.text_cover_fallback);
        listEmptyText = findViewById(R.id.text_list_empty);
        coverImage = findViewById(R.id.image_cover);
        bookMetaLayout = findViewById(R.id.layout_book_meta);
        listCardLayout = findViewById(R.id.layout_list_card);
        bookStatsListLayout = findViewById(R.id.layout_book_stats_list);
        readingCalendarLayout = findViewById(R.id.layout_reading_calendar);
        annualReportLayout = findViewById(R.id.layout_annual_report);
        periodTodayButton = findViewById(R.id.button_period_today);
        periodWeekButton = findViewById(R.id.button_period_week);
        periodYearButton = findViewById(R.id.button_period_year);
        shareAnnualReportButton = findViewById(R.id.button_share_annual_report);
    }

    private void setupControls() {
        ImageButton backButton = findViewById(R.id.button_back);
        backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        periodTodayButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_TODAY));
        periodWeekButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_WEEK));
        periodYearButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_YEAR));
        if (shareAnnualReportButton != null) {
            shareAnnualReportButton.setOnClickListener(v -> shareAnnualReport());
        }
    }

    private void installPredictiveBack() {
        if (!TransitionMotionModeHelper.isFluidMode(settingsStore)) {
            return;
        }
        View root = findViewById(R.id.reading_stats_root);
        if (root == null) {
            return;
        }
        PredictiveBackScaleController.install(this, root, PredictiveBackScaleController.Profile.standard(),
                new PredictiveBackScaleController.Delegate() {
                    @Override
                    public boolean shouldAnimateBack() {
                        return true;
                    }

                    @Override
                    public boolean consumeBack() {
                        return false;
                    }

                    @Override
                    public void commitBack() {
                        finishWithSourceTransition();
                    }

                    @Override
                    public boolean commitBackFromGesture() {
                        return true;
                    }
                });
    }

    @Override
    public void onBackPressed() {
        if (!TransitionMotionModeHelper.isFluidMode(settingsStore)) {
            finishWithSourceTransition();
            return;
        }
        super.onBackPressed();
    }

    private void finishWithSourceTransition() {
        if (finishingWithSource) {
            return;
        }
        finishingWithSource = true;
        View root = findViewById(R.id.reading_stats_root);
        if (TransitionMotionModeHelper.isFluidMode(settingsStore)
                && LaunchSourceTransition.animateExitToSource(root, launchSource, 240L, this::finishNow)) {
            return;
        }
        animateExitToCenter(root);
    }

    private void animateExitToCenter(View root) {
        if (root == null) {
            finishNow();
            return;
        }
        root.animate().cancel();
        root.animate()
                .scaleX(PredictiveBackScaleController.STANDARD_MIN_SCALE)
                .scaleY(PredictiveBackScaleController.STANDARD_MIN_SCALE)
                .alpha(0f)
                .translationX(0f)
                .translationY(0f)
                .setDuration(160L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(this::finishNow)
                .start();
    }

    private void finishNow() {
        finish();
        ActivityTransitionCompat.overrideClose(this, 0, 0);
    }

    private void renderModeShell() {
        boolean singleBookMode = bookId > 0L;
        pageTitleText.setText(singleBookMode ? "本书阅读统计" : "阅读统计");
        bookMetaLayout.setVisibility(singleBookMode ? View.VISIBLE : View.GONE);
        listCardLayout.setVisibility(singleBookMode ? View.GONE : View.VISIBLE);
    }

    private void selectPeriod(String periodKey) {
        selectedPeriod = ReadingStatsUtils.normalizePeriodKey(periodKey);
        updatePeriodButtons();
        loadStats(false);
    }

    private void updatePeriodButtons() {
        stylePeriodButton(periodTodayButton, ReadingStatsUtils.PERIOD_TODAY.equals(selectedPeriod));
        stylePeriodButton(periodWeekButton, ReadingStatsUtils.PERIOD_WEEK.equals(selectedPeriod));
        stylePeriodButton(periodYearButton, ReadingStatsUtils.PERIOD_YEAR.equals(selectedPeriod));
    }

    private void stylePeriodButton(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setBackgroundResource(selected ? R.drawable.bg_app_primary_button : R.drawable.bg_app_outline_button);
        button.setTextColor(ThemeModeHelper.resolveColor(
                this,
                selected ? R.color.app_button_primary_text : R.color.app_button_outline_text
        ));
    }

    private void loadStats(boolean syncFirst) {
        int requestId = loadGeneration.incrementAndGet();
        String period = selectedPeriod;
        long scopedBookId = bookId;
        boolean shouldSync = syncFirst && settingsStore.isWebDavEnabled() && settingsStore.isWebDavSyncReadingStatsEnabled();
        syncStatusText.setText(shouldSync
                ? "正在同步云端阅读统计，本地数据已先显示"
                : "正在加载阅读统计...");
        executor.execute(() -> {
            StatsSnapshot localSnapshot = buildStatsSnapshot(
                    period,
                    scopedBookId,
                    shouldSync ? "正在同步云端阅读统计，本地数据已先显示" : "当前展示的是本地阅读统计"
            );
            postStatsSnapshot(requestId, localSnapshot);
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
            StatsSnapshot syncedSnapshot = buildStatsSnapshot(period, scopedBookId, syncMessage);
            postStatsSnapshot(requestId, syncedSnapshot);
        });
    }

    private StatsSnapshot buildStatsSnapshot(String period, long scopedBookId, String syncMessage) {
        ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(period, ZoneId.systemDefault());
        StatsSnapshot snapshot = new StatsSnapshot();
        snapshot.period = period;
        snapshot.bookId = scopedBookId;
        snapshot.syncMessage = syncMessage;
        snapshot.rangeRows = databaseHelper.getReadingStatsRows(range.startDateString(), range.endDateString());
        if (scopedBookId > 0L) {
            snapshot.book = databaseHelper.getBook(scopedBookId);
            if (snapshot.book != null) {
                snapshot.chapters = databaseHelper.getChapters(snapshot.book.id, false);
                snapshot.totalSeconds = databaseHelper.getReadingDurationSecondsForBook(
                        range.startDateString(),
                        range.endDateString(),
                        snapshot.book.readingStatsKey,
                        snapshot.book.title,
                        snapshot.book.author
                );
                snapshot.totalChars = databaseHelper.getReadingCharCountForBook(
                        range.startDateString(),
                        range.endDateString(),
                        snapshot.book.readingStatsKey,
                        snapshot.book.title,
                        snapshot.book.author
                );
                snapshot.bookEta = buildBookEta(snapshot.book, snapshot.chapters);
                snapshot.annualReport = AnnualReportBuilder.buildBook(databaseHelper, snapshot.book, ZoneId.systemDefault());
            }
        } else {
            snapshot.totalSeconds = databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null);
            snapshot.totalChars = databaseHelper.getReadingCharCount(range.startDateString(), range.endDateString(), null);
            snapshot.bookStats = databaseHelper.getReadingBookStats(range.startDateString(), range.endDateString());
            snapshot.annualReport = AnnualReportBuilder.buildGlobal(databaseHelper, ZoneId.systemDefault());
        }
        return snapshot;
    }

    private void postStatsSnapshot(int requestId, StatsSnapshot snapshot) {
        runOnUiThread(() -> {
            if (requestId != loadGeneration.get()) {
                return;
            }
            syncStatusText.setText(snapshot.syncMessage);
            scopeLabelText.setText(periodLabelPrefix(snapshot.period) + "阅读总时长");
            scopeTotalText.setText(ReadingStatsUtils.formatDuration(snapshot.totalSeconds));
            scopeCharsText.setText("阅读字数 " + formatNumber(snapshot.totalChars));
            renderCalendar(snapshot.rangeRows, snapshot.period);
            currentAnnualReport = snapshot.annualReport;
            renderAnnualReport(snapshot.annualReport);
            if (snapshot.bookId > 0L) {
                renderSingleBook(snapshot.book, snapshot.chapters, snapshot.totalSeconds, snapshot.bookEta);
            } else {
                renderGlobalList(snapshot.bookStats);
            }
        });
    }

    private static final class StatsSnapshot {
        String period;
        long bookId;
        int totalSeconds;
        int totalChars;
        String syncMessage;
        BookRecord book;
        List<ChapterRecord> chapters;
        List<ReadingBookStatRecord> bookStats;
        List<ReadingTimeEntryRecord> rangeRows;
        AnnualReportData annualReport;
        BookEta bookEta;
    }

    private void renderSingleBook(BookRecord book, List<ChapterRecord> chapters, int totalSeconds, BookEta bookEta) {
        if (book == null) {
            bookTitleText.setText("书籍不存在");
            bookAuthorText.setText("这本书已经不在当前设备的书架中");
            bookProgressText.setText("无法展示详细信息");
            bookLastReadText.setText("");
            bookSpeedText.setText("");
            bookEtaText.setText("");
            BookCoverViewHelper.bindCover(coverImage, coverFallbackText, null, null);
            return;
        }
        bookTitleText.setText(ReadingStatsUtils.safeBookTitle(book.title));
        bookAuthorText.setText(ReadingStatsUtils.safeBookAuthor(book.author));
        int chapterCount = chapters == null ? 0 : chapters.size();
        int chapterPosition = chapterCount <= 0 ? 0 : Math.min(book.progressIndex + 1, chapterCount);
        String progressText = chapterCount > 0
                ? String.format(Locale.SIMPLIFIED_CHINESE, "阅读进度：第 %d/%d 章 · 当前范围 %s", chapterPosition, chapterCount, ReadingStatsUtils.formatDuration(totalSeconds))
                : String.format(Locale.SIMPLIFIED_CHINESE, "阅读进度：当前范围 %s", ReadingStatsUtils.formatDuration(totalSeconds));
        bookProgressText.setText(progressText);
        bookLastReadText.setText(book.lastReadAt > 0
                ? "最近阅读：" + dateTimeFormat.format(new Date(book.lastReadAt))
                : "最近阅读：暂无");
        bookSpeedText.setText(bookEta == null || bookEta.charsPerMinute <= 0
                ? "阅读速度：暂无足够数据"
                : String.format(Locale.SIMPLIFIED_CHINESE, "阅读速度：约 %d 字/分", Math.round(bookEta.charsPerMinute)));
        bookEtaText.setText(bookEta == null || bookEta.etaSeconds <= 0
                ? "预计读完：暂无足够数据"
                : "预计读完：" + ReadingStatsUtils.formatDuration(bookEta.etaSeconds));
        bindCover(book.coverPath, book.title);
    }

    private void renderGlobalList(List<ReadingBookStatRecord> bookStats) {
        bookStatsListLayout.removeAllViews();
        if (bookStats == null || bookStats.isEmpty()) {
            listEmptyText.setVisibility(View.VISIBLE);
            return;
        }
        listEmptyText.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (ReadingBookStatRecord record : bookStats) {
            View row = inflater.inflate(R.layout.item_reading_book_stat, bookStatsListLayout, false);
            TextView titleText = row.findViewById(R.id.text_stat_row_title);
            TextView authorText = row.findViewById(R.id.text_stat_row_author);
            TextView metaText = row.findViewById(R.id.text_stat_row_meta);
            TextView durationText = row.findViewById(R.id.text_stat_row_duration);
            titleText.setText(ReadingStatsUtils.safeBookTitle(record.bookTitle));
            authorText.setText(ReadingStatsUtils.safeBookAuthor(record.bookAuthor));
            metaText.setText(record.localBookId > 0L ? "点击查看本书统计详情" : "当前设备没有这本书的本地副本");
            durationText.setText(ReadingStatsUtils.formatDuration(record.totalDurationSeconds)
                    + " · " + formatNumber(record.totalCharCount) + " 字");
            if (record.localBookId > 0L) {
                row.setOnClickListener(v -> {
                    android.content.Intent intent = new android.content.Intent(this, ReadingStatsActivity.class);
                    intent.putExtra("book_id", record.localBookId);
                    LaunchSourceTransition.attach(intent, v);
                    startActivity(intent);
                });
            } else {
                row.setEnabled(false);
                row.setAlpha(0.78f);
            }
            bookStatsListLayout.addView(row);
        }
    }

    private void renderCalendar(List<ReadingTimeEntryRecord> rows, String period) {
        if (readingCalendarLayout == null) return;
        readingCalendarLayout.removeAllViews();
        Map<String, int[]> byDate = new HashMap<>();
        if (rows != null) {
            for (ReadingTimeEntryRecord row : rows) {
                int[] values = byDate.computeIfAbsent(row.date, key -> new int[]{0, 0});
                values[0] += Math.max(row.durationSeconds, 0);
                values[1] += Math.max(row.charCount, 0);
            }
        }
        ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(period, ZoneId.systemDefault());
        LocalDate start = range.startDate;
        LocalDate end = range.endDate;
        if (ReadingStatsUtils.PERIOD_YEAR.equals(period)) {
            start = end.minusDays(29);
        }
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String key = ReadingStatsUtils.formatDate(date);
            int[] values = byDate.getOrDefault(key, new int[]{0, 0});
            TextView row = new TextView(this);
            row.setText(String.format(Locale.SIMPLIFIED_CHINESE,
                    "%s  %s  %s 字",
                    key,
                    ReadingStatsUtils.formatDuration(values[0]),
                    formatNumber(values[1])));
            row.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_secondary));
            row.setTextSize(13f);
            row.setPadding(0, AppUiUtils.dp(this, 4), 0, AppUiUtils.dp(this, 4));
            readingCalendarLayout.addView(row);
        }
    }

    private void renderAnnualReport(AnnualReportData report) {
        if (annualReportLayout == null) return;
        annualReportLayout.removeAllViews();
        if (report == null || !report.hasReadingData()) {
            addReportLine("今年还没有足够的阅读统计");
            if (shareAnnualReportButton != null) shareAnnualReportButton.setEnabled(false);
            return;
        }
        if (shareAnnualReportButton != null) shareAnnualReportButton.setEnabled(true);
        addReportLine(report.year + " 年 " + ReadingStatsUtils.formatDuration(report.totalSeconds)
                + " · " + formatNumber(report.totalChars) + " 字");
        addReportLine("阅读天数 " + report.readingDays + " 天 · 最长连续 " + report.longestStreak + " 天");
        if (report.isBookScope()) {
            addReportLine("书籍：" + report.bookTitle);
            addOptionalReportLine("作者：", report.bookAuthor);
            addOptionalReportLine("标签：", report.topTag);
            addOptionalReportLine("系列：", report.topSeries);
        } else {
            addReportLine("完成书籍 " + report.finishedBooks + " 本");
            addOptionalReportLine("年度 Top 书籍：", report.topBook);
            addOptionalReportLine("常读作者：", report.topAuthor);
            addOptionalReportLine("常读标签：", report.topTag);
            addOptionalReportLine("常读系列：", report.topSeries);
        }
    }

    private void addOptionalReportLine(String prefix, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        addReportLine(prefix + value.trim());
    }

    private void addReportLine(String text) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_secondary));
        row.setTextSize(14f);
        row.setPadding(0, AppUiUtils.dp(this, 4), 0, AppUiUtils.dp(this, 4));
        annualReportLayout.addView(row);
    }

    private BookEta buildBookEta(BookRecord book, List<ChapterRecord> chapters) {
        if (book == null) return null;
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String start = ReadingStatsUtils.formatDate(today.minusDays(6));
        String end = ReadingStatsUtils.formatDate(today);
        int seconds = databaseHelper.getReadingDurationSecondsForBook(start, end, book.readingStatsKey, book.title, book.author);
        int chars = databaseHelper.getReadingCharCountForBook(start, end, book.readingStatsKey, book.title, book.author);
        if (seconds <= 0 || chars <= 0) return null;
        int totalChars = estimateBookChars(chapters);
        int readChars = estimateReadChars(book, chapters);
        int remaining = Math.max(0, totalChars - readChars);
        BookEta eta = new BookEta();
        eta.charsPerMinute = chars * 60f / seconds;
        eta.etaSeconds = eta.charsPerMinute <= 0 ? 0 : Math.round(remaining / eta.charsPerMinute * 60f);
        return eta;
    }

    private int estimateBookChars(List<ChapterRecord> chapters) {
        if (chapters == null) return 0;
        int total = 0;
        for (ChapterRecord chapter : chapters) {
            total += estimateChapterChars(chapter);
        }
        return total;
    }

    private int estimateReadChars(BookRecord book, List<ChapterRecord> chapters) {
        if (book == null || chapters == null) return 0;
        int total = 0;
        for (ChapterRecord chapter : chapters) {
            if (chapter.orderIndex < book.progressIndex) {
                total += estimateChapterChars(chapter);
            } else if (chapter.orderIndex == book.progressIndex) {
                total += Math.max(book.progressOffset, 0);
            }
        }
        return total;
    }

    private int estimateChapterChars(ChapterRecord chapter) {
        if (chapter == null) return 0;
        if (chapter.bodyText != null && !chapter.bodyText.isEmpty()) return chapter.bodyText.length();
        if (chapter.bodyTextSize > 0) return (int) Math.max(1, chapter.bodyTextSize / 3);
        return 0;
    }

    private void shareAnnualReport() {
        if (currentAnnualReport == null || !currentAnnualReport.hasReadingData()) {
            AppUiUtils.showToast(this, "暂无可生成的年度报告");
            return;
        }
        annualReportExportController.showPreview(currentAnnualReport);
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

    private void bindCover(String path, String title) {
        BookCoverViewHelper.bindCover(coverImage, coverFallbackText, path, title);
    }

    private String periodLabelPrefix(String period) {
        if (ReadingStatsUtils.PERIOD_WEEK.equals(period)) {
            return "本周";
        }
        if (ReadingStatsUtils.PERIOD_YEAR.equals(period)) {
            return "本年";
        }
        return "本日";
    }

    private static final class BookEta {
        float charsPerMinute;
        int etaSeconds;
    }
}
