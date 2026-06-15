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
        if (!report.hasReadingData()) {
            return report.isBookScope()
                    ? "这本书在当前周期还没有形成可分析的阅读轨迹。"
                    : "当前周期还没有形成可分析的阅读轨迹。";
        }
        if (report.isBookScope()) {
            return bookSentence(report);
        }
        return globalSentence(report);
    }

    private static String globalSentence(AnnualReportData report) {
        String durationText = ReadingStatsUtils.formatDuration(report.totalSeconds);
        String charsText = formatCompactNumber(report.totalChars) + " 字";
        String focusText = topBookFocus(report);
        String tasteText = tasteText(report);
        String streakText = streakText(report);

        if (report.isDayReport()) {
            return "这一天你累计阅读 " + durationText + "、" + charsText + "；"
                    + focusText + "，" + tasteText + "。";
        }
        if (report.isWeekReport()) {
            return periodSubject(report) + "你读了 " + report.readingDays + " 天，累计 "
                    + durationText + "、" + charsText + "；" + focusText
                    + (hasText(streakText) ? "，" + streakText : "，" + tasteText) + "。";
        }

        String rhythmText = rhythmText(report);
        String finishedText = report.finishedBooks > 0
                ? "，读完 " + report.finishedBooks + " 本"
                : "";
        return "这一年你" + rhythmText + "；" + focusText + "，" + tasteText + finishedText + "。";
    }

    private static String bookSentence(AnnualReportData report) {
        String bookTitle = quoteBook(hasText(report.bookTitle) ? report.bookTitle : report.topBook);
        String durationText = ReadingStatsUtils.formatDuration(report.totalSeconds);
        String charsText = formatCompactNumber(report.totalChars) + " 字";
        String speedText = report.readingSpeedCharsPerMinute > 0
                ? "，平均约 " + report.readingSpeedCharsPerMinute + " 字/分"
                : "";
        String rhythmText = rhythmText(report);

        if ("已读完".equals(report.statusText)) {
            return "你读完了" + bookTitle + "，" + rhythmText + "；累计 "
                    + durationText + "、" + report.readingDays + " 个阅读日" + speedText + "。";
        }
        if (isStrongStreak(report)) {
            return bookTitle + periodSubject(report) + "累计 " + durationText + "、" + charsText
                    + "；最长连续 " + report.longestStreak + " 天，" + rhythmText + speedText + "。";
        }
        if (isStrongPeakPeriod(report)) {
            return bookTitle + periodSubject(report) + "累计 " + durationText + "、" + charsText
                    + "；" + rhythmText + speedText + "。";
        }
        return bookTitle + periodSubject(report) + "累计 " + durationText + "、" + charsText
                + "；记录了 " + report.readingDays + " 个阅读日，" + rhythmText + speedText + "。";
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

    private static String rhythmText(AnnualReportData report) {
        if (report == null) {
            return "留下阅读记录";
        }
        if (report.isYearReport()) {
            String peak = report.peakMonth > 0 ? report.peakMonth + " 月" : "全年";
            if (report.activeMonths > 1) {
                return "在 " + report.activeMonths + " 个月留下阅读记录，" + peak + "最集中";
            }
            return "阅读集中在 " + peak;
        }
        if (hasText(report.peakRhythmLabel) && report.peakRhythmSeconds > 0 && !report.isDayReport()) {
            return report.peakRhythmLabel + " 最活跃，读了 "
                    + ReadingStatsUtils.formatDuration(report.peakRhythmSeconds);
        }
        return "覆盖 " + report.readingDays + " 个阅读日";
    }

    private static String topBookFocus(AnnualReportData report) {
        AnnualReportData.BookStat topBook = report == null ? null : report.primaryBookStat();
        if (topBook == null || !hasText(topBook.title)) {
            return "累计 " + ReadingStatsUtils.formatDuration(report == null ? 0 : report.totalSeconds);
        }
        return quoteBook(topBook.title) + "投入最多（" + ReadingStatsUtils.formatDuration(topBook.totalSeconds) + "）";
    }

    private static String tasteText(AnnualReportData report) {
        if (report == null) {
            return "留下阅读记录";
        }
        AnnualReportData.NamedStat tag = report.primaryTagStat();
        if (tag != null && hasText(tag.name)) {
            return "常读标签是“" + trimForInsight(tag.name, 10) + "”";
        }
        AnnualReportData.NamedStat author = report.primaryAuthorStat();
        if (author != null && hasText(author.name)) {
            return "常读作者是 " + trimForInsight(author.name, 10);
        }
        if (report.readingBooks > 0) {
            return "覆盖 " + report.readingBooks + " 本书";
        }
        return "累计 " + formatCompactNumber(report.totalChars) + " 字";
    }

    private static String streakText(AnnualReportData report) {
        if (!isStrongStreak(report)) {
            return "";
        }
        return "最长连续 " + report.longestStreak + " 天";
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

    private static String quoteBook(String title) {
        return "《" + trimForInsight(hasText(title) ? title : "未命名书籍", 14) + "》";
    }

    private static String formatCompactNumber(int value) {
        int safeValue = Math.max(0, value);
        if (safeValue >= 10000) {
            float tenThousands = safeValue / 10000f;
            if (tenThousands >= 100f) {
                return String.valueOf(Math.round(tenThousands)) + "万";
            }
            return String.format(Locale.SIMPLIFIED_CHINESE, "%.1f万", tenThousands);
        }
        return formatNumber(safeValue);
    }

    private static String formatNumber(int value) {
        return String.format(Locale.SIMPLIFIED_CHINESE, "%,d", Math.max(value, 0));
    }
}
