package com.metahumanz.pacilread.stats.annual;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
import com.metahumanz.pacilread.storage.JsonDatabase;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AnnualReportBuilder {
    private AnnualReportBuilder() {
    }

    public static AnnualReportData buildGlobal(JsonDatabase database, ZoneId zoneId) {
        return buildGlobal(database, zoneId, ReadingStatsUtils.PERIOD_YEAR, ReadingStatsUtils.WEEK_MODE_NATURAL);
    }

    public static AnnualReportData buildGlobal(JsonDatabase database, ZoneId zoneId, String periodKey, String weekMode) {
        return build(database, null, zoneId, periodKey, weekMode);
    }

    public static AnnualReportData buildBook(JsonDatabase database, BookRecord book, ZoneId zoneId) {
        return buildBook(database, book, zoneId, ReadingStatsUtils.PERIOD_YEAR, ReadingStatsUtils.WEEK_MODE_NATURAL);
    }

    public static AnnualReportData buildBook(JsonDatabase database, BookRecord book, ZoneId zoneId, String periodKey, String weekMode) {
        return build(database, book, zoneId, periodKey, weekMode);
    }

    private static AnnualReportData build(
            JsonDatabase database,
            BookRecord scopedBook,
            ZoneId zoneId,
            String periodKey,
            String weekMode
    ) {
        ZoneId safeZone = zoneId == null ? ZoneId.systemDefault() : zoneId;
        String safePeriod = ReadingStatsUtils.normalizePeriodKey(periodKey);
        String safeWeekMode = ReadingStatsUtils.normalizeWeekMode(weekMode);
        ReadingStatsUtils.Range range = ReadingStatsUtils.rangeForPeriod(safePeriod, safeZone, safeWeekMode);

        List<ReadingTimeEntryRecord> rows = database.getReadingStatsRows(range.startDateString(), range.endDateString());
        List<BookRecord> books = database.getBooks();

        AnnualReportData report = new AnnualReportData();
        configureReportShell(report, scopedBook, range, safeWeekMode);
        if (scopedBook != null) {
            report.bookTitle = safeBookTitle(scopedBook.title);
            report.bookAuthor = safeAuthorForDisplay(scopedBook.author);
            report.statusText = BookRecord.STATUS_FINISHED.equals(scopedBook.readingStatus) ? "已读完" : "阅读中";
            report.topBook = report.bookTitle;
            report.topAuthor = report.bookAuthor;
            report.topTag = firstText(scopedBook.tags);
            report.topSeries = scopedBook.series == null ? "" : scopedBook.series.trim();
            report.finishedBooks = BookRecord.STATUS_FINISHED.equals(scopedBook.readingStatus) ? 1 : 0;
        }

        Map<String, Integer> durationByBook = new HashMap<>();
        Map<String, Integer> durationByAuthor = new HashMap<>();
        Map<String, Integer> durationByTag = new HashMap<>();
        Map<String, Integer> durationBySeries = new HashMap<>();
        Set<LocalDate> readingDays = new HashSet<>();
        Set<String> readingBookKeys = new HashSet<>();
        Map<LocalDate, Integer> rhythmDayIndex = rhythmDayIndex(report, range);

        for (ReadingTimeEntryRecord row : rows) {
            if (scopedBook != null && !matchesBook(row, scopedBook)) {
                continue;
            }

            int seconds = Math.max(row.durationSeconds, 0);
            int chars = Math.max(row.charCount, 0);
            report.totalSeconds += seconds;
            report.totalChars += chars;

            LocalDate date = parseDate(row.date);
            if (date != null) {
                readingDays.add(date);
                addRhythmSeconds(report, rhythmDayIndex, date, seconds);
            }

            BookRecord localBook = scopedBook == null ? findBookForStats(books, row) : scopedBook;
            if (seconds > 0 || chars > 0) {
                readingBookKeys.add(bookKey(row, localBook));
            }
            String bookTitle = localBook == null ? safeBookTitle(row.bookTitle) : safeBookTitle(localBook.title);
            String author = localBook == null ? safeAuthorForAggregation(row.bookAuthor) : safeAuthorForAggregation(localBook.author);
            addDuration(durationByBook, bookTitle, seconds);
            addDuration(durationByAuthor, author, seconds);

            if (localBook != null) {
                if (localBook.tags != null) {
                    for (String tag : localBook.tags) {
                        addDuration(durationByTag, tag, seconds);
                    }
                }
                addDuration(durationBySeries, localBook.series, seconds);
            }
        }

        report.readingDays = readingDays.size();
        report.readingBooks = readingBookKeys.size();
        report.longestStreak = longestStreak(readingDays);
        report.readingSpeedCharsPerMinute = report.totalSeconds <= 0 || report.totalChars <= 0
                ? 0
                : Math.round(report.totalChars * 60f / report.totalSeconds);
        calculateRhythmHighlights(report);

        if (scopedBook == null) {
            if (report.isYearReport()) {
                for (BookRecord book : books) {
                    if (BookRecord.STATUS_FINISHED.equals(book.readingStatus)) {
                        report.finishedBooks++;
                    }
                }
            }
            report.topBook = topKey(durationByBook);
            report.topAuthor = topKey(durationByAuthor);
            report.topTag = topKey(durationByTag);
            report.topSeries = topKey(durationBySeries);
        } else {
            if (report.topAuthor.isEmpty()) {
                report.topAuthor = topKey(durationByAuthor);
            }
            if (report.topTag.isEmpty()) {
                report.topTag = topKey(durationByTag);
            }
            if (report.topSeries.isEmpty()) {
                report.topSeries = topKey(durationBySeries);
            }
        }

        return report;
    }

    private static void configureReportShell(
            AnnualReportData report,
            BookRecord scopedBook,
            ReadingStatsUtils.Range range,
            String weekMode
    ) {
        report.scope = scopedBook == null ? AnnualReportScope.GLOBAL : AnnualReportScope.BOOK;
        report.periodKey = range.periodKey;
        report.weekMode = weekMode;
        report.year = range.endDate.getYear();
        report.startDate = range.startDateString();
        report.endDate = range.endDateString();
        report.periodTitle = periodTitle(range, weekMode);
        report.periodRangeText = range.startDate.equals(range.endDate)
                ? range.startDateString()
                : range.startDateString() + " 至 " + range.endDateString();
        report.rangeTitle = scopedBook == null ? "全部书籍" : safeBookTitle(scopedBook.title);
        report.reportTitle = reportTitle(report, scopedBook != null);
        configureRhythmSlots(report, range);
    }

    private static void configureRhythmSlots(AnnualReportData report, ReadingStatsUtils.Range range) {
        if (report.isYearReport()) {
            report.monthlySeconds = new int[12];
            report.rhythmSeconds = report.monthlySeconds;
            report.rhythmLabels = new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};
            report.activeRhythmUnit = "个月";
            return;
        }
        int days = (int) Math.max(1, ChronoUnit.DAYS.between(range.startDate, range.endDate) + 1);
        report.rhythmSeconds = new int[days];
        report.rhythmLabels = new String[days];
        for (int i = 0; i < days; i++) {
            LocalDate date = range.startDate.plusDays(i);
            report.rhythmLabels[i] = days == 1 ? "今日" : shortDate(date);
        }
        report.activeRhythmUnit = "天";
    }

    private static Map<LocalDate, Integer> rhythmDayIndex(AnnualReportData report, ReadingStatsUtils.Range range) {
        Map<LocalDate, Integer> values = new HashMap<>();
        if (report.isYearReport() || report.rhythmSeconds == null) {
            return values;
        }
        for (int i = 0; i < report.rhythmSeconds.length; i++) {
            values.put(range.startDate.plusDays(i), i);
        }
        return values;
    }

    private static void addRhythmSeconds(
            AnnualReportData report,
            Map<LocalDate, Integer> rhythmDayIndex,
            LocalDate date,
            int seconds
    ) {
        if (report == null || date == null || seconds <= 0) {
            return;
        }
        if (report.isYearReport()) {
            int month = date.getMonthValue();
            if (month >= 1 && month <= 12) {
                report.monthlySeconds[month - 1] += seconds;
            }
            return;
        }
        Integer index = rhythmDayIndex.get(date);
        if (index != null && index >= 0 && index < report.rhythmSeconds.length) {
            report.rhythmSeconds[index] += seconds;
        }
    }

    private static void calculateRhythmHighlights(AnnualReportData report) {
        if (report == null || report.rhythmSeconds == null) {
            return;
        }
        int active = 0;
        int peakIndex = -1;
        int peakSeconds = 0;
        for (int i = 0; i < report.rhythmSeconds.length; i++) {
            int seconds = Math.max(report.rhythmSeconds[i], 0);
            if (seconds > 0) {
                active++;
            }
            if (seconds > peakSeconds) {
                peakSeconds = seconds;
                peakIndex = i;
            }
        }
        report.activeRhythmSlots = active;
        report.peakRhythmSeconds = peakSeconds;
        report.peakRhythmLabel = peakIndex >= 0 && report.rhythmLabels != null && peakIndex < report.rhythmLabels.length
                ? report.rhythmLabels[peakIndex]
                : "";
        if (report.isYearReport()) {
            report.activeMonths = active;
            report.peakMonth = peakIndex >= 0 ? peakIndex + 1 : 0;
            report.peakMonthSeconds = peakSeconds;
        }
    }

    private static String periodTitle(ReadingStatsUtils.Range range, String weekMode) {
        if (ReadingStatsUtils.PERIOD_WEEK.equals(range.periodKey)) {
            return ReadingStatsUtils.WEEK_MODE_ROLLING.equals(weekMode) ? "过去七天" : "本周";
        }
        if (ReadingStatsUtils.PERIOD_YEAR.equals(range.periodKey)) {
            return range.endDate.getYear() + " 年";
        }
        return "今日";
    }

    private static String reportTitle(AnnualReportData report, boolean bookScope) {
        if (report.isYearReport()) {
            return report.year + (bookScope ? " 单书阅读报告" : " 年度阅读报告");
        }
        return report.periodTitle + (bookScope ? "单书阅读报告" : "阅读报告");
    }

    private static LocalDate parseDate(String value) {
        try {
            return ReadingStatsUtils.parseDate(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String shortDate(LocalDate date) {
        return String.format(Locale.SIMPLIFIED_CHINESE, "%d/%d", date.getMonthValue(), date.getDayOfMonth());
    }

    private static void addDuration(Map<String, Integer> values, String key, int seconds) {
        String safeKey = key == null ? "" : key.trim();
        if (safeKey.isEmpty() || seconds <= 0) {
            return;
        }
        values.put(safeKey, values.getOrDefault(safeKey, 0) + seconds);
    }

    private static String bookKey(ReadingTimeEntryRecord row, BookRecord book) {
        if (book != null) {
            String statsKey = book.readingStatsKey == null ? "" : book.readingStatsKey.trim();
            if (!statsKey.isEmpty()) {
                return statsKey;
            }
            return ReadingStatsUtils.buildTitleAuthorKey(book.title, book.author);
        }
        String rowIdentity = row == null || row.bookIdentity == null ? "" : row.bookIdentity.trim();
        if (!rowIdentity.isEmpty()) {
            return rowIdentity;
        }
        return ReadingStatsUtils.buildTitleAuthorKey(row == null ? "" : row.bookTitle, row == null ? "" : row.bookAuthor);
    }

    private static BookRecord findBookForStats(List<BookRecord> books, ReadingTimeEntryRecord row) {
        if (books == null || row == null) {
            return null;
        }
        for (BookRecord book : books) {
            if (matchesBook(row, book)) {
                return book;
            }
        }
        return null;
    }

    private static boolean matchesBook(ReadingTimeEntryRecord row, BookRecord book) {
        if (row == null || book == null) {
            return false;
        }
        String statsKey = book.readingStatsKey == null ? "" : book.readingStatsKey.trim();
        String rowIdentity = row.bookIdentity == null ? "" : row.bookIdentity.trim();
        if (!statsKey.isEmpty() && statsKey.equals(rowIdentity)) {
            return true;
        }
        return ReadingStatsUtils.buildTitleAuthorKey(book.title, book.author)
                .equals(ReadingStatsUtils.buildTitleAuthorKey(row.bookTitle, row.bookAuthor));
    }

    private static String topKey(Map<String, Integer> values) {
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

    private static int longestStreak(Set<LocalDate> days) {
        if (days == null || days.isEmpty()) {
            return 0;
        }
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

    private static String safeBookTitle(String value) {
        return ReadingStatsUtils.safeBookTitle(value);
    }

    private static String safeAuthorForAggregation(String value) {
        return value == null || value.trim().isEmpty() ? "" : ReadingStatsUtils.safeBookAuthor(value);
    }

    private static String safeAuthorForDisplay(String value) {
        return value == null || value.trim().isEmpty() ? "" : ReadingStatsUtils.safeBookAuthor(value);
    }

    private static String firstText(List<String> values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
