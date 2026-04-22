package com.metahumanz.pacilread;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
import com.metahumanz.pacilread.storage.ReaderDatabaseHelper;
import com.metahumanz.pacilread.storage.SettingsStore;
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager;
import com.metahumanz.pacilread.sync.WebDavClient;
import com.metahumanz.pacilread.theme.ThemedActivity;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReadingStatsActivity extends ThemedActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DateFormat dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE);

    private ReaderDatabaseHelper databaseHelper;
    private SettingsStore settingsStore;
    private ReadingStatsSyncManager readingStatsSyncManager;

    private TextView pageTitleText;
    private TextView syncStatusText;
    private TextView scopeLabelText;
    private TextView scopeTotalText;
    private TextView bookTitleText;
    private TextView bookAuthorText;
    private TextView bookProgressText;
    private TextView bookLastReadText;
    private TextView coverFallbackText;
    private TextView listEmptyText;
    private ImageView coverImage;
    private LinearLayout bookMetaLayout;
    private LinearLayout listCardLayout;
    private LinearLayout bookStatsListLayout;
    private Button periodTodayButton;
    private Button periodWeekButton;
    private Button periodYearButton;

    private String selectedPeriod = ReadingStatsUtils.PERIOD_TODAY;
    private long bookId = -1L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reading_stats);

        databaseHelper = ReaderDatabaseHelper.getInstance(this);
        settingsStore = new SettingsStore(this);
        readingStatsSyncManager = new ReadingStatsSyncManager(this, databaseHelper, settingsStore, new WebDavClient(settingsStore));

        bookId = getIntent().getLongExtra("book_id", -1L);

        bindViews();
        setupControls();
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
        bookTitleText = findViewById(R.id.text_book_title);
        bookAuthorText = findViewById(R.id.text_book_author);
        bookProgressText = findViewById(R.id.text_book_progress);
        bookLastReadText = findViewById(R.id.text_book_last_read);
        coverFallbackText = findViewById(R.id.text_cover_fallback);
        listEmptyText = findViewById(R.id.text_list_empty);
        coverImage = findViewById(R.id.image_cover);
        bookMetaLayout = findViewById(R.id.layout_book_meta);
        listCardLayout = findViewById(R.id.layout_list_card);
        bookStatsListLayout = findViewById(R.id.layout_book_stats_list);
        periodTodayButton = findViewById(R.id.button_period_today);
        periodWeekButton = findViewById(R.id.button_period_week);
        periodYearButton = findViewById(R.id.button_period_year);
    }

    private void setupControls() {
        ImageButton backButton = findViewById(R.id.button_back);
        backButton.setOnClickListener(v -> onBackPressed());
        periodTodayButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_TODAY));
        periodWeekButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_WEEK));
        periodYearButton.setOnClickListener(v -> selectPeriod(ReadingStatsUtils.PERIOD_YEAR));
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
        button.setBackgroundResource(selected ? R.drawable.bg_primary_button : R.drawable.bg_outline_button);
        button.setTextColor(getColor(selected ? android.R.color.white : R.color.on_surface));
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
            BookRecord book = null;
            List<ChapterRecord> chapters = null;
            List<ReadingBookStatRecord> bookStats = null;
            if (bookId > 0L) {
                book = databaseHelper.getBook(bookId);
                if (book != null) {
                    chapters = databaseHelper.getChapters(book.id, false);
                    totalSeconds = databaseHelper.getReadingDurationSeconds(
                            range.startDateString(),
                            range.endDateString(),
                            book.readingStatsKey
                    );
                } else {
                    totalSeconds = 0;
                }
            } else {
                totalSeconds = databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null);
                bookStats = databaseHelper.getReadingBookStats(range.startDateString(), range.endDateString());
            }

            BookRecord finalBook = book;
            List<ChapterRecord> finalChapters = chapters;
            List<ReadingBookStatRecord> finalBookStats = bookStats;
            int finalTotalSeconds = totalSeconds;
            String finalSyncMessage = syncMessage;
            runOnUiThread(() -> {
                syncStatusText.setText(finalSyncMessage);
                scopeLabelText.setText(periodLabelPrefix() + "阅读总时长");
                scopeTotalText.setText(ReadingStatsUtils.formatDuration(finalTotalSeconds));
                if (bookId > 0L) {
                    renderSingleBook(finalBook, finalChapters, finalTotalSeconds);
                } else {
                    renderGlobalList(finalBookStats);
                }
            });
        });
    }

    private void renderSingleBook(BookRecord book, List<ChapterRecord> chapters, int totalSeconds) {
        if (book == null) {
            bookTitleText.setText("书籍不存在");
            bookAuthorText.setText("这本书已经不在当前设备的书架中");
            bookProgressText.setText("无法展示详细信息");
            bookLastReadText.setText("");
            coverImage.setImageDrawable(null);
            coverFallbackText.setText("PR");
            coverFallbackText.setVisibility(View.VISIBLE);
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
            durationText.setText(ReadingStatsUtils.formatDuration(record.totalDurationSeconds));
            if (record.localBookId > 0L) {
                row.setOnClickListener(v -> {
                    android.content.Intent intent = new android.content.Intent(this, ReadingStatsActivity.class);
                    intent.putExtra("book_id", record.localBookId);
                    startActivity(intent);
                });
            } else {
                row.setEnabled(false);
                row.setAlpha(0.78f);
            }
            bookStatsListLayout.addView(row);
        }
    }

    private void bindCover(String path, String title) {
        Bitmap bitmap = decodeCover(path);
        if (bitmap != null) {
            coverImage.setImageBitmap(bitmap);
            coverFallbackText.setVisibility(View.GONE);
            return;
        }
        coverImage.setImageDrawable(null);
        coverFallbackText.setText(initialsFor(title));
        coverFallbackText.setVisibility(View.VISIBLE);
    }

    private Bitmap decodeCover(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private String initialsFor(String title) {
        if (title == null || title.isBlank()) {
            return "PR";
        }
        String trimmed = title.trim();
        if (trimmed.length() == 1) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase(Locale.ROOT);
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
}
