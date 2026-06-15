package com.metahumanz.pacilread.stats.annual;

import com.metahumanz.pacilread.stats.ReadingStatsUtils;

import java.util.ArrayList;
import java.util.List;

public final class AnnualReportData {
    public AnnualReportScope scope = AnnualReportScope.GLOBAL;
    public String periodKey = ReadingStatsUtils.PERIOD_YEAR;
    public String weekMode = ReadingStatsUtils.WEEK_MODE_NATURAL;
    public int year;
    public int totalSeconds;
    public int totalChars;
    public int readingDays;
    public int readingBooks;
    public int longestStreak;
    public int finishedBooks;
    public String startDate = "";
    public String endDate = "";
    public String periodTitle = "";
    public String periodRangeText = "";
    public String reportTitle = "";
    public String rangeTitle = "";
    public String bookTitle = "";
    public String bookAuthor = "";
    public String topBook = "";
    public String topAuthor = "";
    public String topTag = "";
    public String topSeries = "";
    public String statusText = "";
    public int activeMonths;
    public int peakMonth;
    public int peakMonthSeconds;
    public int activeRhythmSlots;
    public String activeRhythmUnit = "";
    public String peakRhythmLabel = "";
    public int peakRhythmSeconds;
    public int readingSpeedCharsPerMinute;
    public int[] monthlySeconds = new int[12];
    public int[] monthlyChars = new int[12];
    public int[] rhythmSeconds = new int[0];
    public int[] rhythmChars = new int[0];
    public String[] rhythmLabels = new String[0];
    public final List<BookStat> topBooks = new ArrayList<>();
    public final List<NamedStat> topAuthors = new ArrayList<>();
    public final List<NamedStat> topTags = new ArrayList<>();
    public final List<NamedStat> topSeriesStats = new ArrayList<>();

    public boolean hasReadingData() {
        return totalSeconds > 0 || totalChars > 0 || readingDays > 0;
    }

    public boolean isBookScope() {
        return scope == AnnualReportScope.BOOK;
    }

    public boolean isDayReport() {
        return ReadingStatsUtils.PERIOD_TODAY.equals(periodKey);
    }

    public boolean isWeekReport() {
        return ReadingStatsUtils.PERIOD_WEEK.equals(periodKey);
    }

    public boolean isYearReport() {
        return ReadingStatsUtils.PERIOD_YEAR.equals(periodKey);
    }

    public String reportKindLabel() {
        if (isDayReport()) {
            return "每日报告";
        }
        if (isWeekReport()) {
            return "周报";
        }
        return "年度报告";
    }

    public int averageSecondsPerReadingDay() {
        return readingDays <= 0 ? 0 : Math.round(totalSeconds / (float) readingDays);
    }

    public BookStat primaryBookStat() {
        return topBooks.isEmpty() ? null : topBooks.get(0);
    }

    public NamedStat primaryAuthorStat() {
        return topAuthors.isEmpty() ? null : topAuthors.get(0);
    }

    public NamedStat primaryTagStat() {
        return topTags.isEmpty() ? null : topTags.get(0);
    }

    public NamedStat primarySeriesStat() {
        return topSeriesStats.isEmpty() ? null : topSeriesStats.get(0);
    }

    public static final class BookStat {
        public final String title;
        public final String author;
        public final int totalSeconds;
        public final int totalChars;

        public BookStat(String title, String author, int totalSeconds, int totalChars) {
            this.title = title == null ? "" : title.trim();
            this.author = author == null ? "" : author.trim();
            this.totalSeconds = Math.max(totalSeconds, 0);
            this.totalChars = Math.max(totalChars, 0);
        }
    }

    public static final class NamedStat {
        public final String name;
        public final int totalSeconds;

        public NamedStat(String name, int totalSeconds) {
            this.name = name == null ? "" : name.trim();
            this.totalSeconds = Math.max(totalSeconds, 0);
        }
    }
}
