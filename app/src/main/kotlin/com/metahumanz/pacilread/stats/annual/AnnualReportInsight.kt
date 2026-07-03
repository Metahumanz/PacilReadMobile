package com.metahumanz.pacilread.stats.annual

import com.metahumanz.pacilread.stats.ReadingStatsUtils
import java.util.Locale

object AnnualReportInsight {
    @JvmStatic
    fun sentence(report: AnnualReportData?): String {
        if (report == null) return ""
        if (!report.hasReadingData()) {
            return if (report.isBookScope()) "这本书在当前周期还没有形成可分析的阅读轨迹。" else "当前周期还没有形成可分析的阅读轨迹。"
        }
        return if (report.isBookScope()) bookSentence(report) else globalSentence(report)
    }

    private fun globalSentence(report: AnnualReportData): String {
        val durationText = ReadingStatsUtils.formatDuration(report.totalSeconds)
        val charsText = "${formatCompactNumber(report.totalChars)} 字"
        val focusText = topBookFocus(report)
        val tasteText = tasteText(report)
        val streakText = streakText(report)
        if (report.isDayReport()) return "这一天你累计阅读 $durationText、$charsText；$focusText，$tasteText。"
        if (report.isWeekReport()) {
            return "${periodSubject(report)}你读了 ${report.readingDays} 天，累计 $durationText、$charsText；$focusText" +
                if (hasText(streakText)) "，$streakText。" else "，$tasteText。"
        }
        if (report.isMonthReport()) {
            return "${periodSubject(report)}你读了 ${report.readingDays} 天，累计 $durationText、$charsText；$focusText" +
                if (hasText(streakText)) "，$streakText。" else "，$tasteText。"
        }
        if (report.isLast365DaysReport()) {
            return "过去365天你读了 ${report.readingDays} 天，累计 $durationText、$charsText；$focusText，$tasteText。"
        }
        val finishedText = if (report.finishedBooks > 0) "，读完 ${report.finishedBooks} 本" else ""
        return "这一年你${rhythmText(report)}；$focusText，$tasteText$finishedText。"
    }

    private fun bookSentence(report: AnnualReportData): String {
        val bookTitle = quoteBook(if (hasText(report.bookTitle)) report.bookTitle else report.topBook)
        val durationText = ReadingStatsUtils.formatDuration(report.totalSeconds)
        val charsText = "${formatCompactNumber(report.totalChars)} 字"
        val speedText = if (report.readingSpeedCharsPerMinute > 0) "，平均约 ${report.readingSpeedCharsPerMinute} 字/分" else ""
        val rhythmText = rhythmText(report)
        if (report.statusText == "已读完") {
            return "你读完了$bookTitle，$rhythmText；累计 $durationText、${report.readingDays} 个阅读日$speedText。"
        }
        if (isStrongStreak(report)) {
            return "$bookTitle${periodSubject(report)}累计 $durationText、$charsText；最长连续 ${report.longestStreak} 天，$rhythmText$speedText。"
        }
        if (isStrongPeakPeriod(report)) return "$bookTitle${periodSubject(report)}累计 $durationText、$charsText；$rhythmText$speedText。"
        return "$bookTitle${periodSubject(report)}累计 $durationText、$charsText；记录了 ${report.readingDays} 个阅读日，$rhythmText$speedText。"
    }

    private fun isStrongStreak(report: AnnualReportData?): Boolean {
        if (report == null || report.longestStreak <= 0 || report.readingDays <= 0) return false
        return report.longestStreak >= 7 || report.readingDays >= 3 &&
            report.longestStreak >= Math.ceil((report.readingDays * 0.6f).toDouble()).toInt()
    }

    private fun isStrongPeakPeriod(report: AnnualReportData?): Boolean {
        if (report == null || report.totalSeconds <= 0 || report.isDayReport()) return false
        if (!report.isYearReport()) {
            return report.peakRhythmSeconds >= 300 &&
                (report.peakRhythmSeconds >= report.totalSeconds * 0.42f || report.activeRhythmSlots <= 2)
        }
        if (report.peakMonth <= 0 || report.peakMonthSeconds <= 0) return false
        val averageActiveMonthSeconds = report.totalSeconds / Math.max(report.activeMonths, 1).toFloat()
        return report.peakMonthSeconds >= 1800 &&
            (report.peakMonthSeconds >= averageActiveMonthSeconds * 1.45f || report.peakMonthSeconds >= report.totalSeconds * 0.42f)
    }

    private fun periodSubject(report: AnnualReportData?): String = when {
        report == null -> "这段时间"
        report.isDayReport() -> "今天"
        report.isWeekReport() -> report.periodTitle?.takeUnless { it.isBlank() } ?: "这一周"
        report.isMonthReport() -> report.periodTitle?.takeUnless { it.isBlank() } ?: "这个月"
        report.isLast365DaysReport() -> "过去365天"
        else -> "今年"
    }

    private fun rhythmText(report: AnnualReportData?): String {
        if (report == null) return "留下阅读记录"
        if (report.isYearReport()) {
            val peak = if (hasText(report.peakRhythmLabel)) {
                report.peakRhythmLabel
            } else if (report.peakMonth > 0) {
                "${report.peakMonth} 月"
            } else {
                "全年"
            }
            return if (report.activeMonths > 1) "在 ${report.activeMonths} 个月留下阅读记录，${peak}最集中" else "阅读集中在 $peak"
        }
        if (hasText(report.peakRhythmLabel) && report.peakRhythmSeconds > 0 && !report.isDayReport()) {
            return "${report.peakRhythmLabel} 最活跃，读了 ${ReadingStatsUtils.formatDuration(report.peakRhythmSeconds)}"
        }
        return "覆盖 ${report.readingDays} 个阅读日"
    }

    private fun topBookFocus(report: AnnualReportData?): String {
        val topBook = report?.primaryBookStat()
        if (topBook == null || !hasText(topBook.title)) {
            return "累计 ${ReadingStatsUtils.formatDuration(report?.totalSeconds ?: 0)}"
        }
        return "${quoteBook(topBook.title)}投入最多（${ReadingStatsUtils.formatDuration(topBook.totalSeconds)}）"
    }

    private fun tasteText(report: AnnualReportData?): String {
        if (report == null) return "留下阅读记录"
        val tag = report.primaryTagStat()
        if (tag != null && hasText(tag.name)) return "常读标签是“${trimForInsight(tag.name, 10)}”"
        val author = report.primaryAuthorStat()
        if (author != null && hasText(author.name)) return "常读作者是 ${trimForInsight(author.name, 10)}"
        if (report.readingBooks > 0) return "覆盖 ${report.readingBooks} 本书"
        return "累计 ${formatCompactNumber(report.totalChars)} 字"
    }

    private fun streakText(report: AnnualReportData): String = if (isStrongStreak(report)) "最长连续 ${report.longestStreak} 天" else ""
    private fun hasText(value: String?): Boolean = !value.isNullOrBlank()
    private fun trimForInsight(value: String?, maxChars: Int): String {
        val safe = value?.trim().orEmpty()
        return if (safe.length <= maxChars) safe else safe.substring(0, Math.max(1, maxChars)) + "..."
    }
    private fun quoteBook(title: String?): String = "《${trimForInsight(if (hasText(title)) title else "未命名书籍", 14)}》"
    private fun formatCompactNumber(value: Int): String {
        val safeValue = Math.max(0, value)
        if (safeValue >= 10000) {
            val tenThousands = safeValue / 10000f
            return if (tenThousands >= 100f) "${Math.round(tenThousands)}万" else String.format(Locale.SIMPLIFIED_CHINESE, "%.1f万", tenThousands)
        }
        return formatNumber(safeValue)
    }
    private fun formatNumber(value: Int): String = String.format(Locale.SIMPLIFIED_CHINESE, "%,d", Math.max(value, 0))
}
