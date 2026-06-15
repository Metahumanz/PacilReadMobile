package com.metahumanz.pacilread.stats.annual;

import com.metahumanz.pacilread.model.BookRecord;
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord;
import com.metahumanz.pacilread.stats.ReadingStatsUtils;
import com.metahumanz.pacilread.storage.JsonDatabase;

import java.time.LocalDate;
import java.time.ZoneId;
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
        return build(database, null, zoneId);
    }

    public static AnnualReportData buildBook(JsonDatabase database, BookRecord book, ZoneId zoneId) {
        return build(database, book, zoneId);
    }

    private static AnnualReportData build(JsonDatabase database, BookRecord scopedBook, ZoneId zoneId) {
        ZoneId safeZone = zoneId == null ? ZoneId.systemDefault() : zoneId;
        LocalDate today = LocalDate.now(safeZone);
        LocalDate start = today.withDayOfYear(1);
        String startDate = ReadingStatsUtils.formatDate(start);
        String endDate = ReadingStatsUtils.formatDate(today);

        List<ReadingTimeEntryRecord> rows = database.getReadingStatsRows(startDate, endDate);
        List<BookRecord> books = database.getBooks();

        AnnualReportData report = new AnnualReportData();
        report.scope = scopedBook == null ? AnnualReportScope.GLOBAL : AnnualReportScope.BOOK;
        report.year = today.getYear();
        report.rangeTitle = scopedBook == null ? "全部书籍" : safeBookTitle(scopedBook.title);
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

        for (ReadingTimeEntryRecord row : rows) {
            if (scopedBook != null && !matchesBook(row, scopedBook)) {
                continue;
            }

            int seconds = Math.max(row.durationSeconds, 0);
            int chars = Math.max(row.charCount, 0);
            report.totalSeconds += seconds;
            report.totalChars += chars;

            try {
                LocalDate date = ReadingStatsUtils.parseDate(row.date);
                readingDays.add(date);
                int month = date.getMonthValue();
                if (month >= 1 && month <= 12) {
                    report.monthlySeconds[month - 1] += seconds;
                }
            } catch (RuntimeException ignored) {
            }

            BookRecord localBook = scopedBook == null ? findBookForStats(books, row) : scopedBook;
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
        report.longestStreak = longestStreak(readingDays);
        report.readingSpeedCharsPerMinute = report.totalSeconds <= 0 || report.totalChars <= 0
                ? 0
                : Math.round(report.totalChars * 60f / report.totalSeconds);
        calculateMonthlyHighlights(report);

        if (scopedBook == null) {
            for (BookRecord book : books) {
                if (BookRecord.STATUS_FINISHED.equals(book.readingStatus)) {
                    report.finishedBooks++;
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

    private static void calculateMonthlyHighlights(AnnualReportData report) {
        if (report == null || report.monthlySeconds == null) {
            return;
        }
        int active = 0;
        int peakMonth = 0;
        int peakSeconds = 0;
        for (int i = 0; i < report.monthlySeconds.length && i < 12; i++) {
            int seconds = Math.max(report.monthlySeconds[i], 0);
            if (seconds > 0) {
                active++;
            }
            if (seconds > peakSeconds) {
                peakSeconds = seconds;
                peakMonth = i + 1;
            }
        }
        report.activeMonths = active;
        report.peakMonth = peakMonth;
        report.peakMonthSeconds = peakSeconds;
    }

    private static void addDuration(Map<String, Integer> values, String key, int seconds) {
        String safeKey = key == null ? "" : key.trim();
        if (safeKey.isEmpty() || seconds <= 0) {
            return;
        }
        values.put(safeKey, values.getOrDefault(safeKey, 0) + seconds);
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
