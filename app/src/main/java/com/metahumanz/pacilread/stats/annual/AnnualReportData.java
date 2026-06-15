package com.metahumanz.pacilread.stats.annual;

public final class AnnualReportData {
    public AnnualReportScope scope = AnnualReportScope.GLOBAL;
    public int year;
    public int totalSeconds;
    public int totalChars;
    public int readingDays;
    public int longestStreak;
    public int finishedBooks;
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
    public int readingSpeedCharsPerMinute;
    public int[] monthlySeconds = new int[12];

    public boolean hasReadingData() {
        return totalSeconds > 0 || totalChars > 0 || readingDays > 0;
    }

    public boolean isBookScope() {
        return scope == AnnualReportScope.BOOK;
    }

    public int averageSecondsPerReadingDay() {
        return readingDays <= 0 ? 0 : Math.round(totalSeconds / (float) readingDays);
    }
}
