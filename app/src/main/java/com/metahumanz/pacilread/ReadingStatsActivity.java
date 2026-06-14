package com.metahumanz.pacilread;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
        try {
            Bitmap bitmap = renderAnnualReportBitmap(currentAnnualReport);
            File dir = new File(getCacheDir(), "reports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "pacilread-annual-report-" + currentAnnualReport.year + ".png");
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

    private Bitmap renderAnnualReportBitmap(AnnualReport report) {
        int width = 1080;
        int height = 1600;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(247, 244, 236));
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.rgb(45, 43, 38));
        titlePaint.setTextSize(58f);
        titlePaint.setFakeBoldText(true);
        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(Color.rgb(72, 68, 58));
        bodyPaint.setTextSize(38f);
        Paint mutedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mutedPaint.setColor(Color.rgb(112, 105, 92));
        mutedPaint.setTextSize(30f);
        int y = 120;
        canvas.drawText("PacilRead " + report.year + " 年度报告", 72, y, titlePaint);
        y += 105;
        canvas.drawText("阅读 " + ReadingStatsUtils.formatDuration(report.totalSeconds)
                + " · " + formatNumber(report.totalChars) + " 字", 72, y, bodyPaint);
        y += 78;
        canvas.drawText("阅读天数 " + report.readingDays + " 天 · 最长连续 " + report.longestStreak + " 天", 72, y, bodyPaint);
        y += 78;
        canvas.drawText("完成书籍 " + report.finishedBooks + " 本", 72, y, bodyPaint);
        y += 96;
        canvas.drawText("Top 书籍  " + emptyDash(report.topBook), 72, y, bodyPaint);
        y += 68;
        canvas.drawText("常读作者  " + emptyDash(report.topAuthor), 72, y, bodyPaint);
        y += 68;
        canvas.drawText("常读标签  " + emptyDash(report.topTag), 72, y, bodyPaint);
        y += 68;
        canvas.drawText("常读系列  " + emptyDash(report.topSeries), 72, y, bodyPaint);
        y += 110;
        canvas.drawText("月度趋势", 72, y, titlePaint);
        y += 70;
        int max = 1;
        for (int value : report.monthlySeconds) max = Math.max(max, value);
        Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setColor(Color.rgb(88, 126, 116));
        for (int i = 0; i < 12; i++) {
            int barWidth = Math.max(6, Math.round(760f * report.monthlySeconds[i] / max));
            int rowY = y + i * 44;
            canvas.drawText((i + 1) + "月", 72, rowY + 28, mutedPaint);
            canvas.drawRect(160, rowY, 160 + barWidth, rowY + 28, barPaint);
        }
        canvas.drawText("由 PacilRead Mobile 生成", 72, height - 78, mutedPaint);
        return bitmap;
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
