package com.metahumanz.pacilread.stats.annual

import com.metahumanz.pacilread.stats.ReadingStatsUtils

class AnnualReportData {
    @JvmField var scope: AnnualReportScope? = AnnualReportScope.GLOBAL
    @JvmField var periodKey: String? = ReadingStatsUtils.PERIOD_YEAR
    @JvmField var weekMode: String? = ReadingStatsUtils.WEEK_MODE_NATURAL
    @JvmField var year = 0
    @JvmField var totalSeconds = 0
    @JvmField var totalChars = 0
    @JvmField var readingDays = 0
    @JvmField var readingBooks = 0
    @JvmField var longestStreak = 0
    @JvmField var finishedBooks = 0
    @JvmField var startDate: String? = ""
    @JvmField var endDate: String? = ""
    @JvmField var periodTitle: String? = ""
    @JvmField var periodRangeText: String? = ""
    @JvmField var reportTitle: String? = ""
    @JvmField var rangeTitle: String? = ""
    @JvmField var bookTitle: String? = ""
    @JvmField var bookAuthor: String? = ""
    @JvmField var topBook: String? = ""
    @JvmField var topAuthor: String? = ""
    @JvmField var topTag: String? = ""
    @JvmField var topSeries: String? = ""
    @JvmField var statusText: String? = ""
    @JvmField var activeMonths = 0
    @JvmField var peakMonth = 0
    @JvmField var peakMonthSeconds = 0
    @JvmField var activeRhythmSlots = 0
    @JvmField var activeRhythmUnit: String? = ""
    @JvmField var peakRhythmLabel: String? = ""
    @JvmField var peakRhythmSeconds = 0
    @JvmField var readingSpeedCharsPerMinute = 0
    @JvmField var monthlySeconds = IntArray(12)
    @JvmField var monthlyChars = IntArray(12)
    @JvmField var rhythmSeconds = IntArray(0)
    @JvmField var rhythmChars = IntArray(0)
    @JvmField var rhythmLabels = emptyArray<String>()
    @JvmField var dailyContextSeconds = IntArray(0)
    @JvmField var dailyContextChars = IntArray(0)
    @JvmField var dailyContextLabels = emptyArray<String>()
    @JvmField var dailyContextCurrentIndex = -1
    @JvmField val topBooks: MutableList<BookStat> = ArrayList()
    @JvmField val topAuthors: MutableList<NamedStat> = ArrayList()
    @JvmField val topTags: MutableList<NamedStat> = ArrayList()
    @JvmField val topSeriesStats: MutableList<NamedStat> = ArrayList()

    fun hasReadingData(): Boolean = totalSeconds > 0 || totalChars > 0 || readingDays > 0
    fun isBookScope(): Boolean = scope == AnnualReportScope.BOOK
    fun isDayReport(): Boolean = periodKey == ReadingStatsUtils.PERIOD_TODAY
    fun isWeekReport(): Boolean = periodKey == ReadingStatsUtils.PERIOD_WEEK
    fun isYearReport(): Boolean = periodKey == ReadingStatsUtils.PERIOD_YEAR

    fun reportKindLabel(): String = when {
        isDayReport() -> "每日报告"
        isWeekReport() -> "周报"
        else -> "年度报告"
    }

    fun averageSecondsPerReadingDay(): Int = if (readingDays <= 0) 0 else Math.round(totalSeconds / readingDays.toFloat())
    fun primaryBookStat(): BookStat? = topBooks.firstOrNull()
    fun primaryAuthorStat(): NamedStat? = topAuthors.firstOrNull()
    fun primaryTagStat(): NamedStat? = topTags.firstOrNull()
    fun primarySeriesStat(): NamedStat? = topSeriesStats.firstOrNull()

    class BookStat(title: String?, author: String?, totalSeconds: Int, totalChars: Int) {
        @JvmField val title: String = title?.trim() ?: ""
        @JvmField val author: String = author?.trim() ?: ""
        @JvmField val totalSeconds: Int = totalSeconds.coerceAtLeast(0)
        @JvmField val totalChars: Int = totalChars.coerceAtLeast(0)
    }

    class NamedStat(name: String?, totalSeconds: Int) {
        @JvmField val name: String = name?.trim() ?: ""
        @JvmField val totalSeconds: Int = totalSeconds.coerceAtLeast(0)
    }
}
