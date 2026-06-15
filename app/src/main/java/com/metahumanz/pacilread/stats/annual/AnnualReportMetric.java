package com.metahumanz.pacilread.stats.annual;

import com.metahumanz.pacilread.stats.ReadingStatsUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public enum AnnualReportMetric {
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

    public final String key;

    AnnualReportMetric(String key) {
        this.key = key;
    }

    public String label(AnnualReportData report) {
        boolean bookScope = report != null && report.isBookScope();
        switch (this) {
            case TOTAL_DURATION:
                return bookScope ? "本书时长" : "阅读总时长";
            case TOTAL_CHARS:
                return bookScope ? "本书字数" : "阅读字数";
            case READING_BOOKS:
                return "阅读书籍";
            case READING_DAYS:
                return "阅读天数";
            case LONGEST_STREAK:
                return "最长连续";
            case FINISHED_BOOKS:
                return "完成书籍";
            case TOP_BOOK:
                return "Top 书籍";
            case TOP_AUTHOR:
                return bookScope ? "作者" : "常读作者";
            case TOP_TAG:
                return bookScope ? "标签" : "常读标签";
            case TOP_SERIES:
                return bookScope ? "系列" : "常读系列";
            case PEAK_MONTH:
                return report != null && report.isYearReport() ? "最活跃月份" : "最活跃日";
            case ACTIVE_MONTHS:
                return report != null && report.isYearReport() ? "活跃月份" : "活跃天数";
            case DAILY_AVERAGE:
                return "日均阅读";
            case BOOK_STATUS:
                return "完成状态";
            case BOOK_SPEED:
                return "阅读速度";
            default:
                return "";
        }
    }

    public String value(AnnualReportData report) {
        if (report == null) {
            return "";
        }
        switch (this) {
            case TOTAL_DURATION:
                return ReadingStatsUtils.formatDuration(report.totalSeconds);
            case TOTAL_CHARS:
                return formatNumber(report.totalChars) + " 字";
            case READING_BOOKS:
                return report.readingBooks + " 本";
            case READING_DAYS:
                return report.readingDays + " 天";
            case LONGEST_STREAK:
                return report.longestStreak + " 天";
            case FINISHED_BOOKS:
                return report.finishedBooks + " 本";
            case TOP_BOOK:
                return clean(report.topBook);
            case TOP_AUTHOR:
                return report.isBookScope() ? clean(report.bookAuthor) : clean(report.topAuthor);
            case TOP_TAG:
                return clean(report.topTag);
            case TOP_SERIES:
                return clean(report.topSeries);
            case PEAK_MONTH:
                return report.isYearReport()
                        ? (report.peakMonth > 0 ? report.peakMonth + " 月" : "")
                        : clean(report.peakRhythmLabel);
            case ACTIVE_MONTHS:
                int active = report.isYearReport() ? report.activeMonths : report.activeRhythmSlots;
                String unit = report.isYearReport() ? "个月" : "天";
                return active + " " + unit;
            case DAILY_AVERAGE:
                return ReadingStatsUtils.formatDuration(report.averageSecondsPerReadingDay());
            case BOOK_STATUS:
                return clean(report.statusText);
            case BOOK_SPEED:
                return report.readingSpeedCharsPerMinute > 0
                        ? report.readingSpeedCharsPerMinute + " 字/分"
                        : "";
            default:
                return "";
        }
    }

    public boolean supports(AnnualReportData report) {
        if (report == null) {
            return false;
        }
        boolean bookScope = report.isBookScope();
        switch (this) {
            case FINISHED_BOOKS:
                return !bookScope && report.isYearReport();
            case READING_BOOKS:
            case TOP_BOOK:
            case DAILY_AVERAGE:
                return !bookScope;
            case BOOK_STATUS:
            case BOOK_SPEED:
                return bookScope;
            default:
                return true;
        }
    }

    public boolean isAvailable(AnnualReportData report) {
        if (!supports(report)) {
            return false;
        }
        switch (this) {
            case TOTAL_DURATION:
                return report.totalSeconds > 0;
            case TOTAL_CHARS:
                return report.totalChars > 0;
            case READING_BOOKS:
                return report.readingBooks > 0;
            case READING_DAYS:
                return report.readingDays > 0;
            case LONGEST_STREAK:
                return report.longestStreak > 0;
            case FINISHED_BOOKS:
                return true;
            case TOP_BOOK:
                return hasText(report.topBook);
            case TOP_AUTHOR:
                return hasText(report.isBookScope() ? report.bookAuthor : report.topAuthor);
            case TOP_TAG:
                return hasText(report.topTag);
            case TOP_SERIES:
                return hasText(report.topSeries);
            case PEAK_MONTH:
                return report.isYearReport() ? report.peakMonth > 0 : hasText(report.peakRhythmLabel);
            case ACTIVE_MONTHS:
                return (report.isYearReport() ? report.activeMonths : report.activeRhythmSlots) > 0;
            case DAILY_AVERAGE:
                return report.averageSecondsPerReadingDay() > 0;
            case BOOK_STATUS:
                return hasText(report.statusText);
            case BOOK_SPEED:
                return report.readingSpeedCharsPerMinute > 0;
            default:
                return false;
        }
    }

    public static AnnualReportMetric fromKey(String key) {
        if (key == null) {
            return null;
        }
        String safeKey = key.trim();
        for (AnnualReportMetric metric : values()) {
            if (metric.key.equals(safeKey)) {
                return metric;
            }
        }
        return null;
    }

    public static List<String> parseKeys(String serialized) {
        List<String> keys = new ArrayList<>();
        if (serialized == null || serialized.trim().isEmpty()) {
            return keys;
        }
        String[] parts = serialized.split(",");
        for (String part : parts) {
            String key = part == null ? "" : part.trim();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    public static String serialize(List<AnnualReportMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (AnnualReportMetric metric : metrics) {
            if (metric == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(metric.key);
        }
        return builder.toString();
    }

    public static List<AnnualReportMetric> selectionFromKeys(AnnualReportData report, List<String> keys) {
        List<AnnualReportMetric> selected = new ArrayList<>();
        if (keys != null) {
            for (String key : keys) {
                AnnualReportMetric metric = fromKey(key);
                if (metric != null && !selected.contains(metric)) {
                    selected.add(metric);
                }
            }
        }
        return sanitizeMetrics(report, selected);
    }

    public static List<AnnualReportMetric> sanitizeMetrics(AnnualReportData report, List<AnnualReportMetric> metrics) {
        List<AnnualReportMetric> available = availableFor(report);
        List<AnnualReportMetric> selected = new ArrayList<>();
        if (metrics != null) {
            for (AnnualReportMetric metric : metrics) {
                if (metric != null && available.contains(metric) && !selected.contains(metric)) {
                    selected.add(metric);
                }
                if (selected.size() == 3) {
                    return selected;
                }
            }
        }
        for (AnnualReportMetric metric : defaultOrder(report)) {
            if (available.contains(metric) && !selected.contains(metric)) {
                selected.add(metric);
            }
            if (selected.size() == 3) {
                return selected;
            }
        }
        for (AnnualReportMetric metric : available) {
            if (!selected.contains(metric)) {
                selected.add(metric);
            }
            if (selected.size() == 3) {
                break;
            }
        }
        return selected;
    }

    public static List<AnnualReportMetric> availableFor(AnnualReportData report) {
        List<AnnualReportMetric> available = new ArrayList<>();
        if (report == null) {
            return available;
        }
        AnnualReportMetric[] order = report.isBookScope() ? bookOrder() : globalOrder();
        for (AnnualReportMetric metric : order) {
            if (metric.isAvailable(report) && !available.contains(metric)) {
                available.add(metric);
            }
        }
        for (AnnualReportMetric metric : defaultOrder(report)) {
            if (metric.supports(report) && !available.contains(metric)) {
                available.add(metric);
            }
            if (available.size() >= 3) {
                break;
            }
        }
        return available;
    }

    private static AnnualReportMetric[] defaultOrder(AnnualReportData report) {
        if (report != null && report.isBookScope()) {
            return new AnnualReportMetric[]{
                    READING_DAYS,
                    LONGEST_STREAK,
                    BOOK_STATUS,
                    TOTAL_DURATION,
                    TOTAL_CHARS
            };
        }
        return new AnnualReportMetric[]{
                TOTAL_DURATION,
                TOTAL_CHARS,
                report != null && report.isYearReport() ? FINISHED_BOOKS : READING_BOOKS,
                READING_DAYS,
                LONGEST_STREAK
        };
    }

    private static AnnualReportMetric[] globalOrder() {
        return new AnnualReportMetric[]{
                TOTAL_DURATION,
                TOTAL_CHARS,
                READING_BOOKS,
                READING_DAYS,
                LONGEST_STREAK,
                FINISHED_BOOKS,
                TOP_BOOK,
                TOP_AUTHOR,
                TOP_TAG,
                TOP_SERIES,
                PEAK_MONTH,
                ACTIVE_MONTHS,
                DAILY_AVERAGE
        };
    }

    private static AnnualReportMetric[] bookOrder() {
        return new AnnualReportMetric[]{
                TOTAL_DURATION,
                TOTAL_CHARS,
                READING_DAYS,
                LONGEST_STREAK,
                BOOK_STATUS,
                BOOK_SPEED,
                PEAK_MONTH,
                ACTIVE_MONTHS,
                TOP_AUTHOR,
                TOP_TAG,
                TOP_SERIES
        };
    }

    private static String formatNumber(int value) {
        return String.format(Locale.SIMPLIFIED_CHINESE, "%,d", Math.max(value, 0));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
