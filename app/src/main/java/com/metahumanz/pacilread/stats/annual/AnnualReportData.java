package com.metahumanz.pacilread.stats.annual;

import com.metahumanz.pacilread.stats.ReadingStatsUtils;

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
    public int[] rhythmSeconds = new int[0];
    public String[] rhythmLabels = new String[0];

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
}
