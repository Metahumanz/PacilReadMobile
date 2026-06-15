package com.metahumanz.pacilread.stats.annual;

import com.metahumanz.pacilread.stats.ReadingStatsUtils;

import java.util.Locale;

public final class AnnualReportInsight {
    private AnnualReportInsight() {
    }

    public static String sentence(AnnualReportData report) {
        if (report == null) {
            return "";
        }
        if (report.isBookScope()) {
            return bookSentence(report);
        }
        return globalSentence(report);
    }

    private static String globalSentence(AnnualReportData report) {
        if (isStrongStreak(report)) {
            return "最长连续 " + report.longestStreak + " 天，是你 " + report.readingDays
                    + " 个阅读日里最稳定的一段。";
        }
        if (isStrongPeakPeriod(report)) {
            if (!report.isYearReport() && hasText(report.peakRhythmLabel)) {
                return report.periodTitle + "里 " + report.peakRhythmLabel + " 最活跃，读了 "
                        + ReadingStatsUtils.formatDuration(report.peakRhythmSeconds) + "。";
            }
            return report.peakMonth + " 月是阅读高峰，单月读了 "
                    + ReadingStatsUtils.formatDuration(report.peakMonthSeconds) + "。";
        }
        if (hasText(report.topTag) && hasText(report.topBook)) {
            return periodSubject(report) + "最常读“" + trimForInsight(report.topTag, 10) + "”，代表书是《"
                    + trimForInsight(report.topBook, 14) + "》。";
        }
        if (hasText(report.topAuthor)) {
            return periodSubject(report) + "的阅读偏好很清楚，常读作者是 "
                    + trimForInsight(report.topAuthor, 14) + "。";
        }
        if (hasText(report.topBook)) {
            return "《" + trimForInsight(report.topBook, 16) + "》占据了" + periodSubject(report) + "最多阅读时间。";
        }
        if (report.isYearReport() && report.activeMonths >= 8 && report.readingDays >= 30) {
            return "全年有 " + report.activeMonths + " 个月留下阅读记录，累计 "
                    + report.readingDays + " 个阅读日。";
        }
        if (report.totalSeconds > 0 && report.readingDays > 0) {
            return "你" + periodSubject(report) + "读了 " + ReadingStatsUtils.formatDuration(report.totalSeconds)
                    + "，分布在 " + report.readingDays + " 个阅读日。";
        }
        if (report.totalChars > 0) {
            return "你" + periodSubject(report) + "读过 " + formatNumber(report.totalChars) + " 字，留下了可见的阅读记录。";
        }
        return "你" + periodSubject(report) + "留下 " + report.readingDays + " 个阅读日。";
    }

    private static String bookSentence(AnnualReportData report) {
        if (isStrongStreak(report)) {
            return "这本书最长连续读了 " + report.longestStreak + " 天，占 "
                    + report.readingDays + " 个本书阅读日的核心节奏。";
        }
        if (isStrongPeakPeriod(report)) {
            if (!report.isYearReport() && hasText(report.peakRhythmLabel)) {
                return report.periodTitle + "里 " + report.peakRhythmLabel + " 是这本书的阅读高峰，读了 "
                        + ReadingStatsUtils.formatDuration(report.peakRhythmSeconds) + "。";
            }
            return report.peakMonth + " 月是这本书的阅读高峰，读了 "
                    + ReadingStatsUtils.formatDuration(report.peakMonthSeconds) + "。";
        }
        if (report.readingSpeedCharsPerMinute > 0 && report.totalChars > 0) {
            return "你按约 " + report.readingSpeedCharsPerMinute + " 字/分读这本书，累计 "
                    + formatNumber(report.totalChars) + " 字。";
        }
        if (hasText(report.statusText) && report.totalSeconds > 0) {
            return "这本书状态是“" + report.statusText + "”，" + periodSubject(report) + "投入 "
                    + ReadingStatsUtils.formatDuration(report.totalSeconds) + "。";
        }
        if (report.totalSeconds > 0 && report.readingDays > 0) {
            return "这本书" + periodSubject(report) + "读了 " + ReadingStatsUtils.formatDuration(report.totalSeconds)
                    + "，覆盖 " + report.readingDays + " 个阅读日。";
        }
        if (report.totalChars > 0) {
            return "这本书" + periodSubject(report) + "读过 " + formatNumber(report.totalChars) + " 字。";
        }
        return "这本书" + periodSubject(report) + "留下 " + report.readingDays + " 个阅读日。";
    }

    private static boolean isStrongStreak(AnnualReportData report) {
        if (report == null || report.longestStreak <= 0 || report.readingDays <= 0) {
            return false;
        }
        if (report.longestStreak >= 7) {
            return true;
        }
        return report.readingDays >= 3 && report.longestStreak >= Math.ceil(report.readingDays * 0.6f);
    }

    private static boolean isStrongPeakPeriod(AnnualReportData report) {
        if (report == null || report.totalSeconds <= 0) {
            return false;
        }
        if (report.isDayReport()) {
            return false;
        }
        if (!report.isYearReport()) {
            return report.peakRhythmSeconds >= 300
                    && (report.peakRhythmSeconds >= report.totalSeconds * 0.42f
                    || report.activeRhythmSlots <= 2);
        }
        if (report.peakMonth <= 0 || report.peakMonthSeconds <= 0) {
            return false;
        }
        int activeMonths = Math.max(report.activeMonths, 1);
        float averageActiveMonthSeconds = report.totalSeconds / (float) activeMonths;
        return report.peakMonthSeconds >= 1800
                && (report.peakMonthSeconds >= averageActiveMonthSeconds * 1.45f
                || report.peakMonthSeconds >= report.totalSeconds * 0.42f);
    }

    private static String periodSubject(AnnualReportData report) {
        if (report == null) {
            return "这段时间";
        }
        if (report.isDayReport()) {
            return "今天";
        }
        if (report.isWeekReport()) {
            return report.periodTitle == null || report.periodTitle.isBlank()
                    ? "这一周"
                    : report.periodTitle;
        }
        return "今年";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String trimForInsight(String value, int maxChars) {
        String safe = value == null ? "" : value.trim();
        if (safe.length() <= maxChars) {
            return safe;
        }
        return safe.substring(0, Math.max(1, maxChars)) + "...";
    }

    private static String formatNumber(int value) {
        return String.format(Locale.SIMPLIFIED_CHINESE, "%,d", Math.max(value, 0));
    }
}
