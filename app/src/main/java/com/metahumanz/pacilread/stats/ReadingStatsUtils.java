package com.metahumanz.pacilread.stats;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class ReadingStatsUtils {
    public static final String PERIOD_TODAY = "today";
    public static final String PERIOD_WEEK = "week";
    public static final String PERIOD_YEAR = "year";
    public static final String WEEK_MODE_NATURAL = "natural";
    public static final String WEEK_MODE_ROLLING = "rolling";
    public static final String LEGACY_BOOK_IDENTITY = "__legacy_total__";
    public static final String LEGACY_DEVICE_ID = "__legacy_device__";
    public static final String LEGACY_BOOK_TITLE = "历史阅读总时长";
    public static final long IDLE_TIMEOUT_MS = 60_000L;
    public static final long CHECKPOINT_INTERVAL_MS = 60_000L;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private ReadingStatsUtils() {
    }

    public static Range rangeForPeriod(String periodKey, ZoneId zoneId) {
        return rangeForPeriod(periodKey, zoneId, WEEK_MODE_NATURAL);
    }

    public static Range rangeForPeriod(String periodKey, ZoneId zoneId, String weekMode) {
        LocalDate today = LocalDate.now(zoneId);
        if (PERIOD_WEEK.equals(periodKey)) {
            LocalDate start = WEEK_MODE_ROLLING.equals(normalizeWeekMode(weekMode))
                    ? today.minusDays(6)
                    : today.minusDays(today.getDayOfWeek().getValue() - 1L);
            return new Range(PERIOD_WEEK, start, today);
        }
        if (PERIOD_YEAR.equals(periodKey)) {
            return new Range(PERIOD_YEAR, today.withDayOfYear(1), today);
        }
        return new Range(PERIOD_TODAY, today, today);
    }

    public static String formatDate(LocalDate date) {
        return DATE_FORMATTER.format(date);
    }

    public static LocalDate parseDate(String value) {
        return LocalDate.parse(value, DATE_FORMATTER);
    }

    public static String formatWallDate(long wallTimeMillis, ZoneId zoneId) {
        return formatDate(Instant.ofEpochMilli(wallTimeMillis).atZone(zoneId).toLocalDate());
    }

    public static long startOfNextDayMillis(long wallTimeMillis, ZoneId zoneId) {
        ZonedDateTime dateTime = Instant.ofEpochMilli(wallTimeMillis).atZone(zoneId);
        return dateTime.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli();
    }

    public static String safeBookTitle(String title) {
        return title == null || title.isBlank() ? "未命名书籍" : title.trim();
    }

    public static String safeBookAuthor(String author) {
        return author == null || author.isBlank() ? "未知作者" : author.trim();
    }

    public static String buildBookIdentity(String title, String author) {
        String normalized = buildTitleAuthorKey(title, author);
        if (normalized.isBlank()) {
            normalized = "untitled::unknown";
        }
        return sha256(normalized);
    }

    public static String buildTitleAuthorKey(String title, String author) {
        return normalizeIdentityText(title) + "::" + normalizeIdentityText(author);
    }

    public static String normalizeIdentityText(String value) {
        String trimmed = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return trimmed.replaceAll("\\s+", " ");
    }

    public static String normalizePeriodKey(String value) {
        if (PERIOD_WEEK.equals(value) || PERIOD_YEAR.equals(value)) {
            return value;
        }
        return PERIOD_TODAY;
    }

    public static String normalizeWeekMode(String value) {
        return WEEK_MODE_ROLLING.equals(value) ? WEEK_MODE_ROLLING : WEEK_MODE_NATURAL;
    }

    public static String formatDuration(int totalSeconds) {
        int safeSeconds = Math.max(totalSeconds, 0);
        int hours = safeSeconds / 3600;
        int minutes = (safeSeconds % 3600) / 60;
        int seconds = safeSeconds % 60;
        if (hours > 0) {
            return minutes > 0
                    ? String.format(Locale.SIMPLIFIED_CHINESE, "%d 小时 %d 分", hours, minutes)
                    : String.format(Locale.SIMPLIFIED_CHINESE, "%d 小时", hours);
        }
        if (minutes > 0) {
            return seconds > 0
                    ? String.format(Locale.SIMPLIFIED_CHINESE, "%d 分 %d 秒", minutes, seconds)
                    : String.format(Locale.SIMPLIFIED_CHINESE, "%d 分", minutes);
        }
        return String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", seconds);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                builder.append(String.format(Locale.ROOT, "%02x", current));
            }
            return builder.toString();
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public static final class Range {
        public final String periodKey;
        public final LocalDate startDate;
        public final LocalDate endDate;

        public Range(String periodKey, LocalDate startDate, LocalDate endDate) {
            this.periodKey = periodKey;
            this.startDate = startDate;
            this.endDate = endDate;
        }

        public String startDateString() {
            return formatDate(startDate);
        }

        public String endDateString() {
            return formatDate(endDate);
        }
    }
}
