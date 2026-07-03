package com.metahumanz.pacilread.stats.annual

import com.metahumanz.pacilread.stats.ReadingStatsUtils
import java.util.Locale

enum class AnnualReportMetric(@JvmField val key: String) {
    TOTAL_DURATION("total_duration"),
    TOTAL_CHARS("total_chars"),
    READING_BOOKS("reading_books"),
    READING_DAYS("reading_days"),
    LONGEST_STREAK("longest_streak"),
    FINISHED_BOOKS("finished_books"),
    TOP_BOOK("top_book"),
    TOP_AUTHOR("top_author"),
    TOP_TAG("top_tag"),
    TOP_SERIES("top_series"),
    PEAK_MONTH("peak_month"),
    ACTIVE_MONTHS("active_months"),
    DAILY_AVERAGE("daily_average"),
    BOOK_STATUS("book_status"),
    BOOK_SPEED("book_speed");

    fun label(report: AnnualReportData?): String {
        val bookScope = report?.isBookScope() == true
        return when (this) {
            TOTAL_DURATION -> if (bookScope) "本书时长" else "阅读总时长"
            TOTAL_CHARS -> if (bookScope) "本书字数" else "阅读字数"
            READING_BOOKS -> "阅读书籍"
            READING_DAYS -> "阅读天数"
            LONGEST_STREAK -> "最长连续"
            FINISHED_BOOKS -> "完成书籍"
            TOP_BOOK -> "Top 书籍"
            TOP_AUTHOR -> if (bookScope) "作者" else "常读作者"
            TOP_TAG -> if (bookScope) "标签" else "常读标签"
            TOP_SERIES -> if (bookScope) "系列" else "常读系列"
            PEAK_MONTH -> if (report?.isYearReport() == true) "最活跃月份" else "最活跃日"
            ACTIVE_MONTHS -> if (report?.isYearReport() == true) "活跃月份" else "活跃天数"
            DAILY_AVERAGE -> "日均阅读"
            BOOK_STATUS -> "完成状态"
            BOOK_SPEED -> "阅读速度"
        }
    }

    fun value(report: AnnualReportData?): String {
        if (report == null) return ""
        return when (this) {
            TOTAL_DURATION -> ReadingStatsUtils.formatDuration(report.totalSeconds)
            TOTAL_CHARS -> "${formatNumber(report.totalChars)} 字"
            READING_BOOKS -> "${report.readingBooks} 本"
            READING_DAYS -> "${report.readingDays} 天"
            LONGEST_STREAK -> "${report.longestStreak} 天"
            FINISHED_BOOKS -> "${report.finishedBooks} 本"
            TOP_BOOK -> clean(report.topBook)
            TOP_AUTHOR -> clean(if (report.isBookScope()) report.bookAuthor else report.topAuthor)
            TOP_TAG -> clean(report.topTag)
            TOP_SERIES -> clean(report.topSeries)
            PEAK_MONTH -> if (report.isYearReport()) {
                if (report.isLast365DaysReport()) clean(report.peakRhythmLabel)
                else if (report.peakMonth > 0) "${report.peakMonth} 月" else ""
            } else clean(report.peakRhythmLabel)
            ACTIVE_MONTHS -> {
                val active = if (report.isYearReport()) report.activeMonths else report.activeRhythmSlots
                "$active ${if (report.isYearReport()) "个月" else "天"}"
            }
            DAILY_AVERAGE -> ReadingStatsUtils.formatDuration(report.averageSecondsPerReadingDay())
            BOOK_STATUS -> clean(report.statusText)
            BOOK_SPEED -> if (report.readingSpeedCharsPerMinute > 0) "${report.readingSpeedCharsPerMinute} 字/分" else ""
        }
    }

    fun supports(report: AnnualReportData?): Boolean {
        if (report == null) return false
        val bookScope = report.isBookScope()
        return when (this) {
            FINISHED_BOOKS -> !bookScope && report.isYearReport()
            READING_BOOKS, TOP_BOOK, DAILY_AVERAGE -> !bookScope
            BOOK_STATUS, BOOK_SPEED -> bookScope
            else -> true
        }
    }

    fun isAvailable(report: AnnualReportData?): Boolean {
        if (!supports(report) || report == null) return false
        return when (this) {
            TOTAL_DURATION -> report.totalSeconds > 0
            TOTAL_CHARS -> report.totalChars > 0
            READING_BOOKS -> report.readingBooks > 0
            READING_DAYS -> report.readingDays > 0
            LONGEST_STREAK -> report.longestStreak > 0
            FINISHED_BOOKS -> true
            TOP_BOOK -> hasText(report.topBook)
            TOP_AUTHOR -> hasText(if (report.isBookScope()) report.bookAuthor else report.topAuthor)
            TOP_TAG -> hasText(report.topTag)
            TOP_SERIES -> hasText(report.topSeries)
            PEAK_MONTH -> if (report.isYearReport()) {
                if (report.isLast365DaysReport()) hasText(report.peakRhythmLabel) else report.peakMonth > 0
            } else hasText(report.peakRhythmLabel)
            ACTIVE_MONTHS -> (if (report.isYearReport()) report.activeMonths else report.activeRhythmSlots) > 0
            DAILY_AVERAGE -> report.averageSecondsPerReadingDay() > 0
            BOOK_STATUS -> hasText(report.statusText)
            BOOK_SPEED -> report.readingSpeedCharsPerMinute > 0
        }
    }

    companion object {
        @JvmStatic
        fun fromKey(key: String?): AnnualReportMetric? {
            if (key == null) return null
            val safeKey = key.trim()
            for (metric in entries) if (metric.key == safeKey) return metric
            return null
        }

        @JvmStatic
        fun parseKeys(serialized: String?): List<String> {
            val keys = ArrayList<String>()
            if (serialized.isNullOrBlank()) return keys
            for (part in serialized.split(',')) part.trim().takeIf { it.isNotEmpty() }?.let(keys::add)
            return keys
        }

        @JvmStatic
        fun serialize(metrics: List<AnnualReportMetric?>?): String {
            if (metrics.isNullOrEmpty()) return ""
            val builder = StringBuilder()
            for (metric in metrics) {
                if (metric == null) continue
                if (builder.isNotEmpty()) builder.append(',')
                builder.append(metric.key)
            }
            return builder.toString()
        }

        @JvmStatic
        fun selectionFromKeys(report: AnnualReportData?, keys: List<String>?): List<AnnualReportMetric> {
            val selected = ArrayList<AnnualReportMetric>()
            if (keys != null) for (key in keys) fromKey(key)?.let { if (!selected.contains(it)) selected.add(it) }
            return sanitizeMetrics(report, selected)
        }

        @JvmStatic
        fun sanitizeMetrics(report: AnnualReportData?, metrics: List<AnnualReportMetric?>?): List<AnnualReportMetric> {
            val available = availableFor(report)
            val selected = ArrayList<AnnualReportMetric>()
            if (metrics != null) for (metric in metrics) {
                if (metric != null && available.contains(metric) && !selected.contains(metric)) selected.add(metric)
                if (selected.size == 3) return selected
            }
            for (metric in defaultOrder(report)) {
                if (available.contains(metric) && !selected.contains(metric)) selected.add(metric)
                if (selected.size == 3) return selected
            }
            for (metric in available) {
                if (!selected.contains(metric)) selected.add(metric)
                if (selected.size == 3) break
            }
            return selected
        }

        @JvmStatic
        fun availableFor(report: AnnualReportData?): List<AnnualReportMetric> {
            val available = ArrayList<AnnualReportMetric>()
            if (report == null) return available
            for (metric in if (report.isBookScope()) bookOrder() else globalOrder()) {
                if (metric.isAvailable(report) && !available.contains(metric)) available.add(metric)
            }
            for (metric in defaultOrder(report)) {
                if (metric.supports(report) && !available.contains(metric)) available.add(metric)
                if (available.size >= 3) break
            }
            return available
        }

        private fun defaultOrder(report: AnnualReportData?): Array<AnnualReportMetric> = if (report?.isBookScope() == true) {
            arrayOf(READING_DAYS, LONGEST_STREAK, BOOK_STATUS, TOTAL_DURATION, TOTAL_CHARS)
        } else {
            arrayOf(READING_DAYS, LONGEST_STREAK, if (report?.isYearReport() == true) FINISHED_BOOKS else READING_BOOKS, TOTAL_DURATION, TOTAL_CHARS)
        }

        private fun globalOrder() = arrayOf(
            TOTAL_DURATION, TOTAL_CHARS, READING_BOOKS, READING_DAYS, LONGEST_STREAK, FINISHED_BOOKS,
            TOP_BOOK, TOP_AUTHOR, TOP_TAG, TOP_SERIES, PEAK_MONTH, ACTIVE_MONTHS, DAILY_AVERAGE,
        )

        private fun bookOrder() = arrayOf(
            TOTAL_DURATION, TOTAL_CHARS, READING_DAYS, LONGEST_STREAK, BOOK_STATUS, BOOK_SPEED,
            PEAK_MONTH, ACTIVE_MONTHS, TOP_AUTHOR, TOP_TAG, TOP_SERIES,
        )

        private fun formatNumber(value: Int): String = String.format(Locale.SIMPLIFIED_CHINESE, "%,d", Math.max(value, 0))
        private fun hasText(value: String?): Boolean = !value.isNullOrBlank()
        private fun clean(value: String?): String = value?.trim().orEmpty()
    }
}
