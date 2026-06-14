package com.metahumanz.pacilread;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ChapterRecord;
import com.metahumanz.pacilread.model.ReadingBookStatRecord;
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord;
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

import java.io.File;
import java.io.FileOutputStream;
import java.text.DateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReadingStatsActivity extends ThemedActivity {
    private static final int REPORT_WIDTH = 1080;
    private static final int REPORT_HEIGHT = 1920;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DateFormat dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE);

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
    private AnnualReport currentAnnualReport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reading_stats);

        databaseHelper = JsonDatabase.getInstance(this);
        settingsStore = new SettingsStore(this);
        readingStatsSyncManager = new ReadingStatsSyncManager(this, databaseHelper, settingsStore, new WebDavClient(settingsStore));

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
        syncStatusText.setText(syncFirst && settingsStore.isWebDavEnabled() && settingsStore.isWebDavSyncReadingStatsEnabled()
                ? "正在同步云端阅读统计..."
                : "正在加载阅读统计...");
        scopeTotalText.setText("...");
        executor.execute(() -> {
            String syncMessage = "当前展示的是本地阅读统计";
            if (syncFirst && settingsStore.isWebDavEnabled() && settingsStore.isWebDavSyncReadingStatsEnabled()) {
                try {
                    readingStatsSyncManager.downloadAndMergeReadingStats();
                    syncMessage = "已同步本地与云端阅读统计";
                } catch (Exception error) {
                    syncMessage = "云端同步失败，当前展示本地统计：" + error.getMessage();
                }
            }

            ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(selectedPeriod, java.time.ZoneId.systemDefault());
            int totalSeconds;
            int totalChars;
            BookRecord book = null;
            List<ChapterRecord> chapters = null;
            List<ReadingBookStatRecord> bookStats = null;
            List<ReadingTimeEntryRecord> rangeRows = databaseHelper.getReadingStatsRows(range.startDateString(), range.endDateString());
            AnnualReport annualReport = buildAnnualReport();
            BookEta bookEta = null;
            if (bookId > 0L) {
                book = databaseHelper.getBook(bookId);
                if (book != null) {
                    chapters = databaseHelper.getChapters(book.id, false);
                    totalSeconds = databaseHelper.getReadingDurationSecondsForBook(
                            range.startDateString(),
                            range.endDateString(),
                            book.readingStatsKey,
                            book.title,
                            book.author
                    );
                    totalChars = databaseHelper.getReadingCharCountForBook(
                            range.startDateString(),
                            range.endDateString(),
                            book.readingStatsKey,
                            book.title,
                            book.author
                    );
                    bookEta = buildBookEta(book, chapters);
                } else {
                    totalSeconds = 0;
                    totalChars = 0;
                }
            } else {
                totalSeconds = databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null);
                totalChars = databaseHelper.getReadingCharCount(range.startDateString(), range.endDateString(), null);
                bookStats = databaseHelper.getReadingBookStats(range.startDateString(), range.endDateString());
            }

            BookRecord finalBook = book;
            List<ChapterRecord> finalChapters = chapters;
            List<ReadingBookStatRecord> finalBookStats = bookStats;
            List<ReadingTimeEntryRecord> finalRangeRows = rangeRows;
            AnnualReport finalAnnualReport = annualReport;
            BookEta finalBookEta = bookEta;
            int finalTotalSeconds = totalSeconds;
            int finalTotalChars = totalChars;
            String finalSyncMessage = syncMessage;
            runOnUiThread(() -> {
                syncStatusText.setText(finalSyncMessage);
                scopeLabelText.setText(periodLabelPrefix() + "阅读总时长");
                scopeTotalText.setText(ReadingStatsUtils.formatDuration(finalTotalSeconds));
                scopeCharsText.setText("阅读字数 " + formatNumber(finalTotalChars));
                renderCalendar(finalRangeRows);
                currentAnnualReport = finalAnnualReport;
                renderAnnualReport(finalAnnualReport);
                if (bookId > 0L) {
                    renderSingleBook(finalBook, finalChapters, finalTotalSeconds, finalBookEta);
                } else {
                    renderGlobalList(finalBookStats);
                }
            });
        });
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

    private void renderCalendar(List<ReadingTimeEntryRecord> rows) {
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
        ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(selectedPeriod, ZoneId.systemDefault());
        LocalDate start = range.startDate;
        LocalDate end = range.endDate;
        if (ReadingStatsUtils.PERIOD_YEAR.equals(selectedPeriod)) {
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

    private AnnualReport buildAnnualReport() {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zoneId);
        ReadingStatsUtils.Range yearRange = new ReadingStatsUtils.Range(
                ReadingStatsUtils.PERIOD_YEAR,
                today.withDayOfYear(1),
                today
        );
        List<ReadingTimeEntryRecord> rows = databaseHelper.getReadingStatsRows(yearRange.startDateString(), yearRange.endDateString());
        List<BookRecord> books = databaseHelper.getBooks();
        AnnualReport report = new AnnualReport();
        report.year = today.getYear();
        Map<String, Integer> durationByBook = new HashMap<>();
        Map<String, Integer> durationByAuthor = new HashMap<>();
        Map<String, Integer> durationByTag = new HashMap<>();
        Map<String, Integer> durationBySeries = new HashMap<>();
        Map<Integer, Integer> durationByMonth = new HashMap<>();
        Set<LocalDate> readingDays = new HashSet<>();
        for (ReadingTimeEntryRecord row : rows) {
            int seconds = Math.max(row.durationSeconds, 0);
            int chars = Math.max(row.charCount, 0);
            report.totalSeconds += seconds;
            report.totalChars += chars;
            try {
                LocalDate date = ReadingStatsUtils.parseDate(row.date);
                readingDays.add(date);
                durationByMonth.put(date.getMonthValue(), durationByMonth.getOrDefault(date.getMonthValue(), 0) + seconds);
            } catch (Exception ignored) {
            }
            String bookName = ReadingStatsUtils.safeBookTitle(row.bookTitle);
            durationByBook.put(bookName, durationByBook.getOrDefault(bookName, 0) + seconds);
            String author = ReadingStatsUtils.safeBookAuthor(row.bookAuthor);
            durationByAuthor.put(author, durationByAuthor.getOrDefault(author, 0) + seconds);
            BookRecord localBook = findBookForStats(books, row);
            if (localBook != null) {
                if (localBook.tags != null) {
                    for (String tag : localBook.tags) {
                        if (tag == null || tag.isBlank()) continue;
                        durationByTag.put(tag, durationByTag.getOrDefault(tag, 0) + seconds);
                    }
                }
                if (localBook.series != null && !localBook.series.isBlank()) {
                    durationBySeries.put(localBook.series, durationBySeries.getOrDefault(localBook.series, 0) + seconds);
                }
            }
        }
        for (BookRecord book : books) {
            if (BookRecord.STATUS_FINISHED.equals(book.readingStatus)) {
                report.finishedBooks++;
            }
        }
        report.readingDays = readingDays.size();
        report.longestStreak = longestStreak(readingDays);
        report.topBook = topKey(durationByBook);
        report.topAuthor = topKey(durationByAuthor);
        report.topTag = topKey(durationByTag);
        report.topSeries = topKey(durationBySeries);
        for (int month = 1; month <= 12; month++) {
            report.monthlySeconds[month - 1] = durationByMonth.getOrDefault(month, 0);
        }
        return report;
    }

    private void renderAnnualReport(AnnualReport report) {
        if (annualReportLayout == null) return;
        annualReportLayout.removeAllViews();
        if (report == null || report.totalSeconds <= 0) {
            addReportLine("今年还没有足够的阅读统计");
            if (shareAnnualReportButton != null) shareAnnualReportButton.setEnabled(false);
            return;
        }
        if (shareAnnualReportButton != null) shareAnnualReportButton.setEnabled(true);
        addReportLine(report.year + " 年已读 " + ReadingStatsUtils.formatDuration(report.totalSeconds)
                + " · " + formatNumber(report.totalChars) + " 字");
        addReportLine("阅读天数 " + report.readingDays + " 天 · 最长连续 " + report.longestStreak + " 天");
        addReportLine("完成书籍 " + report.finishedBooks + " 本");
        addReportLine("年度 Top 书籍：" + emptyDash(report.topBook));
        addReportLine("常读作者：" + emptyDash(report.topAuthor));
        addReportLine("常读标签：" + emptyDash(report.topTag));
        addReportLine("常读系列：" + emptyDash(report.topSeries));
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

    private BookRecord findBookForStats(List<BookRecord> books, ReadingTimeEntryRecord row) {
        if (books == null || row == null) return null;
        for (BookRecord book : books) {
            if (book.readingStatsKey != null && book.readingStatsKey.equals(row.bookIdentity)) return book;
            if (ReadingStatsUtils.buildTitleAuthorKey(book.title, book.author)
                    .equals(ReadingStatsUtils.buildTitleAuthorKey(row.bookTitle, row.bookAuthor))) return book;
        }
        return null;
    }

    private String topKey(Map<String, Integer> values) {
        String best = "";
        int bestValue = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (entry.getValue() > bestValue) {
                best = entry.getKey();
                bestValue = entry.getValue();
            }
        }
        return best;
    }

    private void shareAnnualReport() {
        if (currentAnnualReport == null || currentAnnualReport.totalSeconds <= 0) {
            AppUiUtils.showToast(this, "暂无可分享的年度报告");
            return;
        }
        showAnnualReportExportDialog();
    }

    private void showAnnualReportExportDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = AppUiUtils.dp(this, 4);
        content.setPadding(padding, padding, padding, 0);

        TextView templateLabel = annualDialogLabel("选择视觉模板");
        content.addView(templateLabel);
        RadioGroup templateGroup = new RadioGroup(this);
        templateGroup.setOrientation(RadioGroup.VERTICAL);
        int magazineId = View.generateViewId();
        int wrappedId = View.generateViewId();
        templateGroup.addView(annualDialogRadio(magazineId, "阅读杂志感", "安静、留白、适合阅读回顾"));
        templateGroup.addView(annualDialogRadio(wrappedId, "Wrapped 风格", "大数字、强对比、更适合社交分享"));
        templateGroup.check(magazineId);
        content.addView(templateGroup);

        TextView themeLabel = annualDialogLabel("选择图片主题");
        themeLabel.setPadding(0, AppUiUtils.dp(this, 14), 0, AppUiUtils.dp(this, 6));
        content.addView(themeLabel);
        RadioGroup themeGroup = new RadioGroup(this);
        themeGroup.setOrientation(RadioGroup.VERTICAL);
        int lightId = View.generateViewId();
        int darkId = View.generateViewId();
        themeGroup.addView(annualDialogRadio(lightId, "浅色", "纸张感或明亮对比背景"));
        themeGroup.addView(annualDialogRadio(darkId, "深色", "深墨背景，适合深色主题分享"));
        AnnualReportExportTheme defaultTheme = ThemeModeHelper.MODE_DARK.equals(ThemeModeHelper.getResolvedAppBucket(this))
                ? AnnualReportExportTheme.DARK
                : AnnualReportExportTheme.LIGHT;
        themeGroup.check(defaultTheme == AnnualReportExportTheme.DARK ? darkId : lightId);
        content.addView(themeGroup);

        new AlertDialog.Builder(this)
                .setTitle("导出年度报告图片")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("生成并分享", (dialog, which) -> {
                    AnnualReportTemplate template = templateGroup.getCheckedRadioButtonId() == wrappedId
                            ? AnnualReportTemplate.WRAPPED
                            : AnnualReportTemplate.MAGAZINE;
                    AnnualReportExportTheme theme = themeGroup.getCheckedRadioButtonId() == darkId
                            ? AnnualReportExportTheme.DARK
                            : AnnualReportExportTheme.LIGHT;
                    exportAnnualReportImage(template, theme);
                })
                .show();
    }

    private TextView annualDialogLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_primary));
        label.setTextSize(14f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(0, 0, 0, AppUiUtils.dp(this, 6));
        return label;
    }

    private RadioButton annualDialogRadio(int id, String title, String summary) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(title + "\n" + summary);
        button.setTextColor(ThemeModeHelper.resolveColor(this, R.color.app_text_secondary));
        button.setTextSize(13f);
        button.setPadding(0, AppUiUtils.dp(this, 5), 0, AppUiUtils.dp(this, 5));
        return button;
    }

    private void exportAnnualReportImage(AnnualReportTemplate template, AnnualReportExportTheme theme) {
        if (currentAnnualReport == null || currentAnnualReport.totalSeconds <= 0) {
            AppUiUtils.showToast(this, "暂无可分享的年度报告");
            return;
        }
        try {
            Bitmap bitmap = renderAnnualReportBitmap(currentAnnualReport, template, theme);
            File dir = new File(getCacheDir(), "reports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, String.format(Locale.ROOT,
                    "pacilread-annual-report-%d-%s-%s.png",
                    currentAnnualReport.year,
                    template.slug,
                    theme.slug));
            try (FileOutputStream output = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "分享年度报告"));
        } catch (Exception error) {
            AppUiUtils.showToast(this, "分享失败: " + error.getMessage());
        }
    }

    private Bitmap renderAnnualReportBitmap(AnnualReport report, AnnualReportTemplate template, AnnualReportExportTheme theme) {
        Bitmap bitmap = Bitmap.createBitmap(REPORT_WIDTH, REPORT_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        AnnualReportPalette palette = AnnualReportPalette.forTemplate(template, theme);
        drawReportBackground(canvas, palette);
        if (template == AnnualReportTemplate.WRAPPED) {
            renderWrappedAnnualReport(canvas, report, palette);
        } else {
            renderMagazineAnnualReport(canvas, report, palette);
        }
        return bitmap;
    }

    private void renderMagazineAnnualReport(Canvas canvas, AnnualReport report, AnnualReportPalette palette) {
        TextPaint titlePaint = reportTextPaint(palette.primaryText, 68f, true);
        TextPaint subtitlePaint = reportTextPaint(palette.secondaryText, 34f, false);
        TextPaint bodyPaint = reportTextPaint(palette.primaryText, 32f, true);
        TextPaint mutedPaint = reportTextPaint(palette.mutedText, 26f, false);

        drawPill(canvas, 72, 72, 388, 124, palette.accentSoft, palette.line, "PACILREAD · YEAR IN READING", palette.accent);
        drawMultiline(canvas, report.year + " 年度阅读报告", 72, 166, 720, titlePaint, 2);
        drawMultiline(canvas,
                "这一年，你在书页里停留了 " + ReadingStatsUtils.formatDuration(report.totalSeconds)
                        + "，读过约 " + formatNumber(report.totalChars) + " 字。",
                74, 330, 820, subtitlePaint, 3);

        drawRoundRect(canvas, 72, 472, 1008, 760, 36, palette.card, palette.line, 2f);
        drawMultiline(canvas, "年度摘要", 112, 518, 300, bodyPaint, 1);
        drawMetricCard(canvas, 112, 584, 400, 126, "阅读天数", report.readingDays + " 天", palette, palette.accent);
        drawMetricCard(canvas, 554, 584, 414, 126, "最长连续", report.longestStreak + " 天", palette, palette.accent2);
        drawMetricCard(canvas, 112, 728, 400, 126, "完成书籍", report.finishedBooks + " 本", palette, palette.accent3);
        drawMetricCard(canvas, 554, 728, 414, 126, "阅读字数", formatNumber(report.totalChars), palette, palette.accent);

        drawRoundRect(canvas, 72, 896, 1008, 1234, 36, palette.card, palette.line, 2f);
        drawMultiline(canvas, "年度书页坐标", 112, 942, 420, bodyPaint, 1);
        int y = 1012;
        y = drawInfoRow(canvas, "Top 书籍", emptyDash(report.topBook), 112, y, 820, palette);
        y = drawInfoRow(canvas, "常读作者", emptyDash(report.topAuthor), 112, y + 14, 820, palette);
        y = drawInfoRow(canvas, "常读标签", emptyDash(report.topTag), 112, y + 14, 820, palette);
        drawInfoRow(canvas, "常读系列", emptyDash(report.topSeries), 112, y + 14, 820, palette);

        drawRoundRect(canvas, 72, 1374, 1008, 1716, 36, palette.card, palette.line, 2f);
        drawMultiline(canvas, "月度趋势", 112, 1420, 320, bodyPaint, 1);
        drawMagazineMonthlyBars(canvas, report, palette, 112, 1500, 856, 152);

        drawMultiline(canvas, "由 PacilRead Mobile 生成", 72, 1810, 480, mutedPaint, 1);
        drawMultiline(canvas, themeFooter(palette), 650, 1810, 330, mutedPaint, 1);
    }

    private void renderWrappedAnnualReport(Canvas canvas, AnnualReport report, AnnualReportPalette palette) {
        Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shapePaint.setColor(palette.accentSoft);
        canvas.save();
        canvas.rotate(-10f, REPORT_WIDTH * 0.72f, 184f);
        canvas.drawRoundRect(new RectF(520, 86, 1180, 342), 48, 48, shapePaint);
        canvas.restore();
        shapePaint.setColor(palette.accent2Soft);
        canvas.save();
        canvas.rotate(13f, 160, 660);
        canvas.drawRoundRect(new RectF(-90, 556, 380, 812), 46, 46, shapePaint);
        canvas.restore();

        TextPaint bigPaint = reportTextPaint(palette.primaryText, 96f, true);
        TextPaint titlePaint = reportTextPaint(palette.primaryText, 54f, true);
        TextPaint bodyPaint = reportTextPaint(palette.secondaryText, 32f, false);
        TextPaint cardValuePaint = reportTextPaint(palette.primaryText, 44f, true);
        TextPaint mutedPaint = reportTextPaint(palette.mutedText, 26f, false);

        drawPill(canvas, 72, 80, 398, 134, palette.accent, Color.TRANSPARENT, "PACILREAD WRAPPED", palette.inverseText);
        drawMultiline(canvas, report.year + " 的阅读声纹", 72, 180, 750, titlePaint, 2);
        drawMultiline(canvas, formatHoursCompact(report.totalSeconds), 72, 332, 550, bigPaint, 1);
        drawMultiline(canvas, "小时阅读", 76, 444, 360, titlePaint, 1);
        drawMultiline(canvas,
                "你用 " + report.readingDays + " 个阅读日，积累了约 " + formatNumber(report.totalChars) + " 字。",
                76, 516, 820, bodyPaint, 3);

        drawRoundRect(canvas, 72, 684, 1008, 920, 38, palette.card, palette.line, 2f);
        drawWrappedStat(canvas, 118, 734, "最长连续", report.longestStreak + " 天", palette, palette.accent);
        drawWrappedStat(canvas, 392, 734, "完成书籍", report.finishedBooks + " 本", palette, palette.accent2);
        drawWrappedStat(canvas, 666, 734, "阅读天数", report.readingDays + " 天", palette, palette.accent3);

        drawRoundRect(canvas, 72, 986, 1008, 1308, 38, palette.card, palette.line, 2f);
        drawMultiline(canvas, "你的年度 Top", 116, 1036, 420, titlePaint, 1);
        drawMultiline(canvas, emptyDash(report.topBook), 116, 1110, 800, cardValuePaint, 2);
        drawPill(canvas, 116, 1240, 360, 1294, palette.accentSoft, palette.line, "作者 · " + emptyDash(report.topAuthor), palette.accent);
        drawPill(canvas, 500, 1240, 872, 1294, palette.accent2Soft, palette.line, "标签 · " + emptyDash(report.topTag), palette.accent2);

        drawRoundRect(canvas, 72, 1396, 1008, 1734, 38, palette.card, palette.line, 2f);
        drawMultiline(canvas, "12 个月的阅读节奏", 116, 1446, 560, titlePaint, 1);
        drawWrappedMonthlyBars(canvas, report, palette, 116, 1538, 846, 118);
        drawMultiline(canvas, "常读系列 · " + emptyDash(report.topSeries), 116, 1668, 760, mutedPaint, 1);

        drawMultiline(canvas, "PacilRead Mobile", 72, 1814, 360, mutedPaint, 1);
        drawMultiline(canvas, "年度报告图片", 760, 1814, 240, mutedPaint, 1);
    }

    private void drawReportBackground(Canvas canvas, AnnualReportPalette palette) {
        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setShader(new LinearGradient(
                0, 0, REPORT_WIDTH, REPORT_HEIGHT,
                palette.backgroundTop,
                palette.backgroundBottom,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0, 0, REPORT_WIDTH, REPORT_HEIGHT, backgroundPaint);
    }

    private void drawMetricCard(Canvas canvas, float left, float top, float width, float height,
                                String label, String value, AnnualReportPalette palette, int accentColor) {
        drawRoundRect(canvas, left, top, left + width, top + height, 26, palette.cardAlt, palette.line, 1.5f);
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(accentColor);
        canvas.drawCircle(left + 28, top + 34, 8, dotPaint);
        drawMultiline(canvas, label, left + 48, top + 18, width - 76, reportTextPaint(palette.mutedText, 24f, false), 1);
        drawMultiline(canvas, value, left + 28, top + 58, width - 56, reportTextPaint(palette.primaryText, 36f, true), 1);
    }

    private int drawInfoRow(Canvas canvas, String label, String value, float left, int top, float width, AnnualReportPalette palette) {
        TextPaint labelPaint = reportTextPaint(palette.mutedText, 24f, false);
        TextPaint valuePaint = reportTextPaint(palette.primaryText, 33f, true);
        drawMultiline(canvas, label, left, top, 160, labelPaint, 1);
        int bottom = drawMultiline(canvas, value, left + 178, top - 4, width - 178, valuePaint, 2);
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(palette.line);
        linePaint.setStrokeWidth(2f);
        canvas.drawLine(left, bottom + 18, left + width, bottom + 18, linePaint);
        return bottom + 38;
    }

    private void drawMagazineMonthlyBars(Canvas canvas, AnnualReport report, AnnualReportPalette palette,
                                         float left, float top, float width, float height) {
        int max = maxMonthlySeconds(report);
        float gap = 12f;
        float barWidth = (width - gap * 11) / 12f;
        TextPaint monthPaint = reportTextPaint(palette.mutedText, 20f, false);
        for (int i = 0; i < 12; i++) {
            float ratio = Math.max(0.04f, report.monthlySeconds[i] / (float) max);
            float barHeight = Math.max(8f, height * ratio);
            float x = left + i * (barWidth + gap);
            float y = top + height - barHeight;
            int color = i % 3 == 0 ? palette.accent : (i % 3 == 1 ? palette.accent2 : palette.accent3);
            drawRoundRect(canvas, x, y, x + barWidth, top + height, 10, color, Color.TRANSPARENT, 0);
            drawMultiline(canvas, String.valueOf(i + 1), x, top + height + 22, barWidth, monthPaint, 1);
        }
    }

    private void drawWrappedStat(Canvas canvas, float left, float top, String label, String value,
                                 AnnualReportPalette palette, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        canvas.drawCircle(left + 22, top + 22, 12, paint);
        drawMultiline(canvas, label, left, top + 54, 230, reportTextPaint(palette.mutedText, 24f, false), 1);
        drawMultiline(canvas, value, left, top + 92, 230, reportTextPaint(palette.primaryText, 40f, true), 1);
    }

    private void drawWrappedMonthlyBars(Canvas canvas, AnnualReport report, AnnualReportPalette palette,
                                        float left, float top, float width, float height) {
        int max = maxMonthlySeconds(report);
        float gap = 10f;
        float barWidth = (width - gap * 11) / 12f;
        TextPaint monthPaint = reportTextPaint(palette.mutedText, 20f, true);
        for (int i = 0; i < 12; i++) {
            float ratio = Math.max(0.05f, report.monthlySeconds[i] / (float) max);
            float barHeight = Math.max(10f, height * ratio);
            float x = left + i * (barWidth + gap);
            float y = top + height - barHeight;
            int color = i % 4 == 0 ? palette.accent : (i % 4 == 1 ? palette.accent2 : (i % 4 == 2 ? palette.accent3 : palette.primaryText));
            drawRoundRect(canvas, x, y, x + barWidth, top + height, 8, color, Color.TRANSPARENT, 0);
            drawMultiline(canvas, String.valueOf(i + 1), x - 2, top + height + 22, barWidth + 4, monthPaint, 1);
        }
    }

    private int maxMonthlySeconds(AnnualReport report) {
        int max = 1;
        if (report == null || report.monthlySeconds == null) return max;
        for (int value : report.monthlySeconds) {
            max = Math.max(max, Math.max(0, value));
        }
        return max;
    }

    private void drawPill(Canvas canvas, float left, float top, float right, float bottom,
                          int fillColor, int strokeColor, String text, int textColor) {
        drawRoundRect(canvas, left, top, right, bottom, (bottom - top) / 2f, fillColor, strokeColor, strokeColor == Color.TRANSPARENT ? 0 : 1.5f);
        TextPaint paint = reportTextPaint(textColor, 22f, true);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float y = top + (bottom - top - metrics.ascent - metrics.descent) / 2f;
        canvas.drawText(ellipsizeForWidth(text, paint, right - left - 40), left + 20, y, paint);
    }

    private void drawRoundRect(Canvas canvas, float left, float top, float right, float bottom,
                               float radius, int fillColor, int strokeColor, float strokeWidth) {
        RectF rect = new RectF(left, top, right, bottom);
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(fillColor);
        fillPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(rect, radius, radius, fillPaint);
        if (strokeWidth > 0f && strokeColor != Color.TRANSPARENT) {
            Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setColor(strokeColor);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeWidth);
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        }
    }

    private int drawMultiline(Canvas canvas, String text, float x, float y, float width, TextPaint paint, int maxLines) {
        String safeText = text == null ? "" : text;
        StaticLayout.Builder builder = StaticLayout.Builder.obtain(safeText, 0, safeText.length(), paint, Math.max(1, Math.round(width)))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.0f)
                .setMaxLines(Math.max(1, maxLines))
                .setEllipsize(TextUtils.TruncateAt.END);
        StaticLayout layout = builder.build();
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
        return Math.round(y + layout.getHeight());
    }

    private TextPaint reportTextPaint(int color, float textSize, boolean bold) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(color);
        paint.setTextSize(textSize);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        return paint;
    }

    private String ellipsizeForWidth(String value, Paint paint, float width) {
        String safe = value == null ? "" : value;
        if (paint.measureText(safe) <= width) return safe;
        String ellipsis = "...";
        int end = safe.length();
        while (end > 0 && paint.measureText(safe.substring(0, end) + ellipsis) > width) {
            end--;
        }
        return end <= 0 ? ellipsis : safe.substring(0, end) + ellipsis;
    }

    private String formatHoursCompact(int seconds) {
        double hours = Math.max(0, seconds) / 3600.0;
        if (hours < 10) {
            return String.format(Locale.SIMPLIFIED_CHINESE, "%.1f", hours);
        }
        return String.format(Locale.SIMPLIFIED_CHINESE, "%.0f", hours);
    }

    private String themeFooter(AnnualReportPalette palette) {
        return palette.dark ? "深色版" : "浅色版";
    }

    private String formatNumber(int value) {
        return String.format(Locale.SIMPLIFIED_CHINESE, "%,d", Math.max(value, 0));
    }

    private String emptyDash(String value) {
        return value == null || value.isBlank() ? "暂无" : value;
    }

    private void bindCover(String path, String title) {
        BookCoverViewHelper.bindCover(coverImage, coverFallbackText, path, title);
    }

    private String periodLabelPrefix() {
        if (ReadingStatsUtils.PERIOD_WEEK.equals(selectedPeriod)) {
            return "本周";
        }
        if (ReadingStatsUtils.PERIOD_YEAR.equals(selectedPeriod)) {
            return "本年";
        }
        return "本日";
    }

    private int longestStreak(Set<LocalDate> days) {
        if (days == null || days.isEmpty()) return 0;
        List<LocalDate> sorted = new ArrayList<>(days);
        sorted.sort(LocalDate::compareTo);
        int best = 1;
        int current = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).plusDays(1).equals(sorted.get(i))) {
                current++;
            } else {
                best = Math.max(best, current);
                current = 1;
            }
        }
        return Math.max(best, current);
    }

    private enum AnnualReportTemplate {
        MAGAZINE("magazine"),
        WRAPPED("wrapped");

        final String slug;

        AnnualReportTemplate(String slug) {
            this.slug = slug;
        }
    }

    private enum AnnualReportExportTheme {
        LIGHT("light"),
        DARK("dark");

        final String slug;

        AnnualReportExportTheme(String slug) {
            this.slug = slug;
        }
    }

    private static final class AnnualReportPalette {
        final boolean dark;
        final int backgroundTop;
        final int backgroundBottom;
        final int card;
        final int cardAlt;
        final int primaryText;
        final int secondaryText;
        final int mutedText;
        final int inverseText;
        final int accent;
        final int accent2;
        final int accent3;
        final int accentSoft;
        final int accent2Soft;
        final int line;

        private AnnualReportPalette(
                boolean dark,
                int backgroundTop,
                int backgroundBottom,
                int card,
                int cardAlt,
                int primaryText,
                int secondaryText,
                int mutedText,
                int inverseText,
                int accent,
                int accent2,
                int accent3,
                int accentSoft,
                int accent2Soft,
                int line
        ) {
            this.dark = dark;
            this.backgroundTop = backgroundTop;
            this.backgroundBottom = backgroundBottom;
            this.card = card;
            this.cardAlt = cardAlt;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.mutedText = mutedText;
            this.inverseText = inverseText;
            this.accent = accent;
            this.accent2 = accent2;
            this.accent3 = accent3;
            this.accentSoft = accentSoft;
            this.accent2Soft = accent2Soft;
            this.line = line;
        }

        static AnnualReportPalette forTemplate(AnnualReportTemplate template, AnnualReportExportTheme theme) {
            boolean dark = theme == AnnualReportExportTheme.DARK;
            if (template == AnnualReportTemplate.WRAPPED) {
                return dark
                        ? new AnnualReportPalette(
                        true,
                        Color.rgb(12, 13, 20),
                        Color.rgb(28, 22, 42),
                        Color.rgb(30, 32, 48),
                        Color.rgb(40, 42, 62),
                        Color.rgb(248, 248, 255),
                        Color.rgb(216, 219, 239),
                        Color.rgb(158, 164, 190),
                        Color.rgb(12, 13, 20),
                        Color.rgb(139, 116, 255),
                        Color.rgb(65, 230, 151),
                        Color.rgb(255, 178, 82),
                        Color.argb(56, 139, 116, 255),
                        Color.argb(52, 65, 230, 151),
                        Color.argb(44, 255, 255, 255)
                )
                        : new AnnualReportPalette(
                        false,
                        Color.rgb(255, 248, 231),
                        Color.rgb(236, 247, 255),
                        Color.rgb(255, 255, 255),
                        Color.rgb(247, 250, 255),
                        Color.rgb(35, 31, 48),
                        Color.rgb(80, 78, 95),
                        Color.rgb(122, 118, 132),
                        Color.rgb(255, 255, 255),
                        Color.rgb(90, 82, 225),
                        Color.rgb(0, 155, 112),
                        Color.rgb(238, 132, 48),
                        Color.argb(42, 90, 82, 225),
                        Color.argb(38, 0, 155, 112),
                        Color.rgb(218, 224, 237)
                );
            }
            return dark
                    ? new AnnualReportPalette(
                    true,
                    Color.rgb(24, 24, 22),
                    Color.rgb(39, 35, 31),
                    Color.rgb(45, 42, 38),
                    Color.rgb(57, 53, 47),
                    Color.rgb(248, 245, 237),
                    Color.rgb(218, 211, 198),
                    Color.rgb(158, 149, 132),
                    Color.rgb(24, 24, 22),
                    Color.rgb(118, 188, 156),
                    Color.rgb(216, 174, 106),
                    Color.rgb(126, 157, 212),
                    Color.argb(42, 118, 188, 156),
                    Color.argb(40, 216, 174, 106),
                    Color.argb(42, 248, 245, 237)
            )
                    : new AnnualReportPalette(
                    false,
                    Color.rgb(250, 246, 235),
                    Color.rgb(238, 244, 238),
                    Color.rgb(255, 252, 245),
                    Color.rgb(247, 243, 232),
                    Color.rgb(47, 45, 39),
                    Color.rgb(84, 80, 69),
                    Color.rgb(122, 113, 94),
                    Color.rgb(255, 252, 245),
                    Color.rgb(75, 132, 113),
                    Color.rgb(178, 123, 72),
                    Color.rgb(75, 105, 158),
                    Color.argb(34, 75, 132, 113),
                    Color.argb(34, 178, 123, 72),
                    Color.rgb(222, 214, 196)
            );
        }
    }

    private static final class BookEta {
        float charsPerMinute;
        int etaSeconds;
    }

    private static final class AnnualReport {
        int year;
        int totalSeconds;
        int totalChars;
        int readingDays;
        int longestStreak;
        int finishedBooks;
        String topBook;
        String topAuthor;
        String topTag;
        String topSeries;
        int[] monthlySeconds = new int[12];
    }
}
