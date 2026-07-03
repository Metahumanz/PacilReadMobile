package com.metahumanz.pacilread.stats

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ReadingStatsUtils {
    const val PERIOD_TODAY = "today"
    const val PERIOD_WEEK = "week"
    const val PERIOD_MONTH = "month"
    const val PERIOD_YEAR = "year"
    const val WEEK_MODE_NATURAL = "natural"
    const val WEEK_MODE_ROLLING = "rolling"
    const val MONTH_MODE_NATURAL = "natural"
    const val MONTH_MODE_LAST_30_DAYS = "last30Days"
    const val YEAR_MODE_NATURAL = "natural"
    const val YEAR_MODE_LAST_365_DAYS = "last365Days"
    const val LEGACY_BOOK_IDENTITY = "__legacy_total__"
    const val LEGACY_DEVICE_ID = "__legacy_device__"
    const val LEGACY_BOOK_TITLE = "历史阅读总时长"
    const val IDLE_TIMEOUT_MS = 60_000L
    const val CHECKPOINT_INTERVAL_MS = 60_000L
    private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

    @JvmStatic
    fun rangeForPeriod(periodKey: String?, zoneId: ZoneId): Range = rangeForPeriod(periodKey, zoneId, WEEK_MODE_NATURAL)

    @JvmStatic
    fun rangeForPeriod(periodKey: String?, zoneId: ZoneId, weekMode: String?): Range =
        rangeForPeriod(periodKey, zoneId, weekMode, MONTH_MODE_NATURAL, YEAR_MODE_NATURAL)

    @JvmStatic
    fun rangeForPeriod(
        periodKey: String?,
        zoneId: ZoneId,
        weekMode: String?,
        monthMode: String?,
        yearMode: String?,
    ): Range {
        val today = LocalDate.now(zoneId)
        if (periodKey == PERIOD_WEEK) {
            val start = if (normalizeWeekMode(weekMode) == WEEK_MODE_ROLLING) {
                today.minusDays(6)
            } else {
                today.minusDays(today.dayOfWeek.value - 1L)
            }
            return Range(PERIOD_WEEK, start, today)
        }
        if (periodKey == PERIOD_MONTH) {
            val start = if (normalizeMonthMode(monthMode) == MONTH_MODE_LAST_30_DAYS) today.minusDays(29)
            else today.withDayOfMonth(1)
            return Range(PERIOD_MONTH, start, today)
        }
        if (periodKey == PERIOD_YEAR) {
            val start = if (normalizeYearMode(yearMode) == YEAR_MODE_LAST_365_DAYS) today.minusDays(364)
            else today.withDayOfYear(1)
            return Range(PERIOD_YEAR, start, today)
        }
        return Range(PERIOD_TODAY, today, today)
    }

    @JvmStatic fun formatDate(date: LocalDate): String = DATE_FORMATTER.format(date)
    @JvmStatic fun parseDate(value: String): LocalDate = LocalDate.parse(value, DATE_FORMATTER)

    @JvmStatic
    fun formatWallDate(wallTimeMillis: Long, zoneId: ZoneId): String =
        formatDate(Instant.ofEpochMilli(wallTimeMillis).atZone(zoneId).toLocalDate())

    @JvmStatic
    fun startOfNextDayMillis(wallTimeMillis: Long, zoneId: ZoneId): Long =
        Instant.ofEpochMilli(wallTimeMillis).atZone(zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    @JvmStatic fun safeBookTitle(title: String?): String = if (title.isNullOrBlank()) "未命名书籍" else title.trim()
    @JvmStatic fun safeBookAuthor(author: String?): String = if (author.isNullOrBlank()) "未知作者" else author.trim()

    @JvmStatic
    fun buildBookIdentity(title: String?, author: String?): String {
        var normalized = buildTitleAuthorKey(title, author)
        if (normalized.isBlank()) normalized = "untitled::unknown"
        return sha256(normalized)
    }

    @JvmStatic
    fun buildTitleAuthorKey(title: String?, author: String?): String =
        "${normalizeIdentityText(title)}::${normalizeIdentityText(author)}"

    @JvmStatic
    fun normalizeIdentityText(value: String?): String =
        (value?.trim()?.lowercase(Locale.ROOT) ?: "").replace(Regex("\\s+"), " ")

    @JvmStatic
    fun normalizePeriodKey(value: String?): String = if (value == PERIOD_WEEK || value == PERIOD_MONTH || value == PERIOD_YEAR) value else PERIOD_TODAY

    @JvmStatic
    fun normalizeWeekMode(value: String?): String = if (value == WEEK_MODE_ROLLING) WEEK_MODE_ROLLING else WEEK_MODE_NATURAL

    @JvmStatic
    fun normalizeMonthMode(value: String?): String =
        if (value == MONTH_MODE_LAST_30_DAYS) MONTH_MODE_LAST_30_DAYS else MONTH_MODE_NATURAL

    @JvmStatic
    fun normalizeYearMode(value: String?): String =
        if (value == YEAR_MODE_LAST_365_DAYS) YEAR_MODE_LAST_365_DAYS else YEAR_MODE_NATURAL

    @JvmStatic
    fun formatDuration(totalSeconds: Int): String {
        val safeSeconds = totalSeconds.coerceAtLeast(0)
        val hours = safeSeconds / 3600
        val minutes = safeSeconds % 3600 / 60
        val seconds = safeSeconds % 60
        if (hours > 0) {
            return if (minutes > 0) String.format(Locale.SIMPLIFIED_CHINESE, "%d 小时 %d 分", hours, minutes)
            else String.format(Locale.SIMPLIFIED_CHINESE, "%d 小时", hours)
        }
        if (minutes > 0) {
            return if (seconds > 0) String.format(Locale.SIMPLIFIED_CHINESE, "%d 分 %d 秒", minutes, seconds)
            else String.format(Locale.SIMPLIFIED_CHINESE, "%d 分", minutes)
        }
        return String.format(Locale.SIMPLIFIED_CHINESE, "%d 秒", seconds)
    }

    private fun sha256(value: String): String = try {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(bytes.size * 2)
        for (current in bytes) builder.append(String.format(Locale.ROOT, "%02x", current))
        builder.toString()
    } catch (_: Exception) {
        Integer.toHexString(value.hashCode())
    }

    class Range(
        @JvmField val periodKey: String,
        @JvmField val startDate: LocalDate,
        @JvmField val endDate: LocalDate,
    ) {
        fun startDateString(): String = formatDate(startDate)
        fun endDateString(): String = formatDate(endDate)
    }
}
