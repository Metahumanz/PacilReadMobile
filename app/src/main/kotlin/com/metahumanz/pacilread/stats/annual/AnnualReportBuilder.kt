package com.metahumanz.pacilread.stats.annual

import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import com.metahumanz.pacilread.storage.JsonDatabase
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object AnnualReportBuilder {
    @JvmStatic
    fun buildGlobal(database: JsonDatabase, zoneId: ZoneId?): AnnualReportData =
        buildGlobal(
            database,
            zoneId,
            ReadingStatsUtils.PERIOD_YEAR,
            ReadingStatsUtils.WEEK_MODE_NATURAL,
            ReadingStatsUtils.MONTH_MODE_NATURAL,
            ReadingStatsUtils.YEAR_MODE_NATURAL,
        )

    @JvmStatic
    fun buildGlobal(database: JsonDatabase, zoneId: ZoneId?, periodKey: String?, weekMode: String?): AnnualReportData =
        buildGlobal(
            database,
            zoneId,
            periodKey,
            weekMode,
            ReadingStatsUtils.MONTH_MODE_NATURAL,
            ReadingStatsUtils.YEAR_MODE_NATURAL,
        )

    @JvmStatic
    fun buildGlobal(
        database: JsonDatabase,
        zoneId: ZoneId?,
        periodKey: String?,
        weekMode: String?,
        monthMode: String?,
        yearMode: String?,
    ): AnnualReportData = build(database, null, zoneId, periodKey, weekMode, monthMode, yearMode)

    @JvmStatic
    fun buildBook(database: JsonDatabase, book: BookRecord?, zoneId: ZoneId?): AnnualReportData =
        buildBook(
            database,
            book,
            zoneId,
            ReadingStatsUtils.PERIOD_YEAR,
            ReadingStatsUtils.WEEK_MODE_NATURAL,
            ReadingStatsUtils.MONTH_MODE_NATURAL,
            ReadingStatsUtils.YEAR_MODE_NATURAL,
        )

    @JvmStatic
    fun buildBook(
        database: JsonDatabase,
        book: BookRecord?,
        zoneId: ZoneId?,
        periodKey: String?,
        weekMode: String?,
    ): AnnualReportData = buildBook(
        database,
        book,
        zoneId,
        periodKey,
        weekMode,
        ReadingStatsUtils.MONTH_MODE_NATURAL,
        ReadingStatsUtils.YEAR_MODE_NATURAL,
    )

    @JvmStatic
    fun buildBook(
        database: JsonDatabase,
        book: BookRecord?,
        zoneId: ZoneId?,
        periodKey: String?,
        weekMode: String?,
        monthMode: String?,
        yearMode: String?,
    ): AnnualReportData = build(database, book, zoneId, periodKey, weekMode, monthMode, yearMode)

    private fun build(
        database: JsonDatabase,
        scopedBook: BookRecord?,
        zoneId: ZoneId?,
        periodKey: String?,
        weekMode: String?,
        monthMode: String?,
        yearMode: String?,
    ): AnnualReportData {
        val safeZone = zoneId ?: ZoneId.systemDefault()
        val safePeriod = ReadingStatsUtils.normalizePeriodKey(periodKey)
        val safeWeekMode = ReadingStatsUtils.normalizeWeekMode(weekMode)
        val safeMonthMode = ReadingStatsUtils.normalizeMonthMode(monthMode)
        val safeYearMode = ReadingStatsUtils.normalizeYearMode(yearMode)
        val range = ReadingStatsUtils.rangeForPeriod(safePeriod, safeZone, safeWeekMode, safeMonthMode, safeYearMode)
        val rows = database.getReadingStatsRows(range.startDateString(), range.endDateString())
        var dailyContextRows: List<ReadingTimeEntryRecord> = emptyList()
        var dailyContextStartDate: LocalDate? = null
        if (safePeriod == ReadingStatsUtils.PERIOD_TODAY) {
            dailyContextStartDate = range.endDate.minusDays(6)
            dailyContextRows = database.getReadingStatsRows(
                ReadingStatsUtils.formatDate(dailyContextStartDate),
                range.endDateString(),
            )
        }
        val books = database.books
        val report = AnnualReportData()
        configureReportShell(report, scopedBook, range, safeWeekMode, safeMonthMode, safeYearMode)
        if (scopedBook != null) {
            report.bookTitle = safeBookTitle(scopedBook.title)
            report.bookAuthor = safeAuthorForDisplay(scopedBook.author)
            report.statusText = if (scopedBook.readingStatus == BookRecord.STATUS_FINISHED) "已读完" else "阅读中"
            report.topBook = report.bookTitle
            report.topAuthor = report.bookAuthor
            report.topTag = firstText(scopedBook.tags)
            report.topSeries = scopedBook.series?.trim().orEmpty()
            report.finishedBooks = if (scopedBook.readingStatus == BookRecord.STATUS_FINISHED) 1 else 0
        }

        val booksByKey = HashMap<String, BookAggregate>()
        val durationByAuthor = HashMap<String, Int>()
        val durationByTag = HashMap<String, Int>()
        val durationBySeries = HashMap<String, Int>()
        val readingDays = HashSet<LocalDate>()
        val readingBookKeys = HashSet<String>()
        val rhythmDayIndex = rhythmDayIndex(report, range)

        for (row in rows) {
            if (scopedBook != null && !matchesBook(row, scopedBook)) continue
            val seconds = max(row.durationSeconds, 0)
            val chars = max(row.charCount, 0)
            report.totalSeconds += seconds
            report.totalChars += chars
            val date = parseDate(row.date)
            if (date != null) {
                readingDays.add(date)
                addRhythmSeconds(report, rhythmDayIndex, date, seconds)
                addRhythmChars(report, rhythmDayIndex, date, chars)
            }
            val localBook = scopedBook ?: findBookForStats(books, row)
            val statsKey = bookKey(row, localBook)
            if (seconds > 0 || chars > 0) readingBookKeys.add(statsKey)
            val bookTitle = safeBookTitle(localBook?.title ?: row.bookTitle)
            val author = safeAuthorForAggregation(localBook?.author ?: row.bookAuthor)
            addBookAggregate(booksByKey, statsKey, bookTitle, author, seconds, chars)
            addDuration(durationByAuthor, author, seconds)
            if (localBook != null) {
                localBook.tags?.let { tags ->
                    for (tag in tags) addDuration(durationByTag, tag, seconds)
                }
                addDuration(durationBySeries, localBook.series, seconds)
            }
        }

        if (scopedBook != null && !booksByKey.containsKey(bookKey(null, scopedBook))) {
            addBookAggregate(
                booksByKey,
                bookKey(null, scopedBook),
                safeBookTitle(scopedBook.title),
                safeAuthorForDisplay(scopedBook.author),
                0,
                0,
            )
        }
        report.readingDays = readingDays.size
        report.readingBooks = readingBookKeys.size
        report.longestStreak = longestStreak(readingDays)
        report.readingSpeedCharsPerMinute = if (report.totalSeconds <= 0 || report.totalChars <= 0) {
            0
        } else {
            (report.totalChars * 60f / report.totalSeconds).roundToInt()
        }
        calculateRhythmHighlights(report)
        report.topBooks.addAll(topBooksFromMap(booksByKey, if (scopedBook == null) 8 else 1))
        report.topAuthors.addAll(topNamedStats(durationByAuthor, 5))
        report.topTags.addAll(topNamedStats(durationByTag, 5))
        report.topSeriesStats.addAll(topNamedStats(durationBySeries, 5))

        if (scopedBook == null) {
            if (report.isYearReport()) {
                for (book in books) {
                    if (book.readingStatus == BookRecord.STATUS_FINISHED && readingBookKeys.contains(bookKey(null, book))) {
                        report.finishedBooks++
                    }
                }
            }
            report.topBook = firstBookTitle(report)
            report.topAuthor = firstStatName(report.topAuthors)
            report.topTag = firstStatName(report.topTags)
            report.topSeries = firstStatName(report.topSeriesStats)
        } else {
            if (report.topAuthor.isNullOrEmpty()) report.topAuthor = firstStatName(report.topAuthors)
            if (report.topTag.isNullOrEmpty()) report.topTag = firstStatName(report.topTags)
            if (report.topSeries.isNullOrEmpty()) report.topSeries = firstStatName(report.topSeriesStats)
        }
        populateDailyContext(report, dailyContextRows, scopedBook, dailyContextStartDate)
        return report
    }

    private fun configureReportShell(
        report: AnnualReportData,
        scopedBook: BookRecord?,
        range: ReadingStatsUtils.Range,
        weekMode: String,
        monthMode: String,
        yearMode: String,
    ) {
        report.scope = if (scopedBook == null) AnnualReportScope.GLOBAL else AnnualReportScope.BOOK
        report.periodKey = range.periodKey
        report.weekMode = weekMode
        report.monthMode = monthMode
        report.yearMode = yearMode
        report.year = range.endDate.year
        report.startDate = range.startDateString()
        report.endDate = range.endDateString()
        report.periodTitle = periodTitle(range, weekMode, monthMode, yearMode)
        report.periodRangeText = if (range.startDate == range.endDate) {
            range.startDateString()
        } else {
            "${range.startDateString()} 至 ${range.endDateString()}"
        }
        report.rangeTitle = if (scopedBook == null) "全部书籍" else safeBookTitle(scopedBook.title)
        report.reportTitle = reportTitle(report, scopedBook != null)
        configureRhythmSlots(report, range)
        configureDailyContextSlots(report, range)
    }

    private fun configureRhythmSlots(report: AnnualReportData, range: ReadingStatsUtils.Range) {
        if (report.isYearReport()) {
            val startMonth = YearMonth.from(range.startDate)
            val monthCount = if (report.isLast365DaysReport()) {
                max(1L, ChronoUnit.MONTHS.between(startMonth.atDay(1), YearMonth.from(range.endDate).atDay(1)) + 1).toInt()
            } else {
                12
            }
            report.monthlySeconds = IntArray(monthCount)
            report.monthlyChars = IntArray(monthCount)
            report.rhythmSeconds = report.monthlySeconds
            report.rhythmChars = report.monthlyChars
            report.rhythmLabels = if (report.isLast365DaysReport()) {
                Array(monthCount) { i -> monthLabel(startMonth.plusMonths(i.toLong())) }
            } else {
                arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12")
            }
            report.activeRhythmUnit = "个月"
            return
        }
        val days = max(1L, ChronoUnit.DAYS.between(range.startDate, range.endDate) + 1).toInt()
        report.rhythmSeconds = IntArray(days)
        report.rhythmChars = IntArray(days)
        report.rhythmLabels = Array(days) { i ->
            val date = range.startDate.plusDays(i.toLong())
            if (days == 1) "今日" else shortDate(date)
        }
        report.activeRhythmUnit = "天"
    }

    private fun configureDailyContextSlots(report: AnnualReportData, range: ReadingStatsUtils.Range) {
        if (!report.isDayReport()) return
        report.dailyContextSeconds = IntArray(7)
        report.dailyContextChars = IntArray(7)
        val start = range.endDate.minusDays(6)
        report.dailyContextLabels = Array(7) { i ->
            val date = start.plusDays(i.toLong())
            if (i == 6) "今日" else shortDate(date)
        }
        report.dailyContextCurrentIndex = 6
    }

    private fun rhythmDayIndex(report: AnnualReportData, range: ReadingStatsUtils.Range): Map<LocalDate, Int> {
        val values = HashMap<LocalDate, Int>()
        if (report.isYearReport()) {
            if (report.isLast365DaysReport()) {
                val startMonth = YearMonth.from(range.startDate)
                for (i in report.rhythmSeconds.indices) values[startMonth.plusMonths(i.toLong()).atDay(1)] = i
            }
            return values
        }
        for (i in report.rhythmSeconds.indices) values[range.startDate.plusDays(i.toLong())] = i
        return values
    }

    private fun addRhythmSeconds(
        report: AnnualReportData,
        rhythmDayIndex: Map<LocalDate, Int>,
        date: LocalDate,
        seconds: Int,
    ) {
        if (seconds <= 0) return
        if (report.isYearReport()) {
            val index = if (report.isLast365DaysReport()) rhythmDayIndex[YearMonth.from(date).atDay(1)] else date.monthValue - 1
            if (index != null && index >= 0 && index < report.monthlySeconds.size) report.monthlySeconds[index] += seconds
            return
        }
        val index = rhythmDayIndex[date]
        if (index != null && index >= 0 && index < report.rhythmSeconds.size) report.rhythmSeconds[index] += seconds
    }

    private fun addRhythmChars(
        report: AnnualReportData,
        rhythmDayIndex: Map<LocalDate, Int>,
        date: LocalDate,
        chars: Int,
    ) {
        if (chars <= 0) return
        if (report.isYearReport()) {
            val index = if (report.isLast365DaysReport()) rhythmDayIndex[YearMonth.from(date).atDay(1)] else date.monthValue - 1
            if (index != null && index >= 0 && index < report.monthlyChars.size) report.monthlyChars[index] += chars
            return
        }
        val index = rhythmDayIndex[date]
        if (index != null && index >= 0 && index < report.rhythmChars.size) report.rhythmChars[index] += chars
    }

    private fun calculateRhythmHighlights(report: AnnualReportData) {
        var active = 0
        var peakIndex = -1
        var peakSeconds = 0
        for (i in report.rhythmSeconds.indices) {
            val seconds = max(report.rhythmSeconds[i], 0)
            if (seconds > 0) active++
            if (seconds > peakSeconds) {
                peakSeconds = seconds
                peakIndex = i
            }
        }
        report.activeRhythmSlots = active
        report.peakRhythmSeconds = peakSeconds
        report.peakRhythmLabel = if (peakIndex >= 0 && peakIndex < report.rhythmLabels.size) report.rhythmLabels[peakIndex] else ""
        if (report.isYearReport()) {
            report.activeMonths = active
            report.peakMonth = if (!report.isLast365DaysReport() && peakIndex >= 0) peakIndex + 1 else 0
            report.peakMonthSeconds = peakSeconds
        }
    }

    private fun populateDailyContext(
        report: AnnualReportData,
        rows: List<ReadingTimeEntryRecord>?,
        scopedBook: BookRecord?,
        contextStartDate: LocalDate?,
    ) {
        if (!report.isDayReport() || rows == null || contextStartDate == null) return
        for (row in rows) {
            if (scopedBook != null && !matchesBook(row, scopedBook)) continue
            val date = parseDate(row.date) ?: continue
            val index = ChronoUnit.DAYS.between(contextStartDate, date).toInt()
            if (index < 0 || index >= report.dailyContextSeconds.size) continue
            report.dailyContextSeconds[index] += max(row.durationSeconds, 0)
            report.dailyContextChars[index] += max(row.charCount, 0)
        }
    }

    private fun periodTitle(
        range: ReadingStatsUtils.Range,
        weekMode: String?,
        monthMode: String?,
        yearMode: String?,
    ): String = when (range.periodKey) {
        ReadingStatsUtils.PERIOD_WEEK -> if (weekMode == ReadingStatsUtils.WEEK_MODE_ROLLING) "过去七天" else "本周"
        ReadingStatsUtils.PERIOD_MONTH -> if (monthMode == ReadingStatsUtils.MONTH_MODE_LAST_30_DAYS) "过去30天" else "自然月"
        ReadingStatsUtils.PERIOD_YEAR -> if (yearMode == ReadingStatsUtils.YEAR_MODE_LAST_365_DAYS) "过去365天" else "${range.endDate.year} 年"
        else -> "今日"
    }

    private fun reportTitle(report: AnnualReportData, bookScope: Boolean): String = when {
        report.isYearReport() && report.isLast365DaysReport() ->
            "${report.periodTitle}${if (bookScope) "单书阅读年报" else "阅读年报"}"
        report.isYearReport() ->
            "${report.year}${if (bookScope) " 单书阅读报告" else " 年度阅读报告"}"
        report.isWeekReport() ->
            "${report.periodTitle}${if (bookScope) "单书周报" else "阅读周报"}"
        report.isMonthReport() ->
            "${report.periodTitle}${if (bookScope) "单书月报" else "阅读月报"}"
        else -> "${report.periodTitle}${if (bookScope) "单书阅读报告" else "阅读报告"}"
    }

    private fun parseDate(value: String?): LocalDate? = try {
        ReadingStatsUtils.parseDate(value!!)
    } catch (_: RuntimeException) {
        null
    }

    private fun shortDate(date: LocalDate): String =
        String.format(Locale.SIMPLIFIED_CHINESE, "%d/%d", date.monthValue, date.dayOfMonth)

    private fun monthLabel(month: YearMonth): String =
        String.format(Locale.SIMPLIFIED_CHINESE, "%02d/%d", month.year % 100, month.monthValue)

    private fun addDuration(values: MutableMap<String, Int>, key: String?, seconds: Int) {
        val safeKey = key?.trim().orEmpty()
        if (safeKey.isEmpty() || seconds <= 0) return
        values[safeKey] = values.getOrDefault(safeKey, 0) + seconds
    }

    private fun addBookAggregate(
        values: MutableMap<String, BookAggregate>?,
        key: String?,
        title: String?,
        author: String?,
        seconds: Int,
        chars: Int,
    ) {
        values ?: return
        val safeKey = key?.trim().takeUnless { it.isNullOrEmpty() }
            ?: ReadingStatsUtils.buildTitleAuthorKey(title, author)
        var aggregate = values[safeKey]
        if (aggregate == null) {
            aggregate = BookAggregate(safeBookTitle(title), safeAuthorForDisplay(author))
            values[safeKey] = aggregate
        }
        aggregate.totalSeconds += max(seconds, 0)
        aggregate.totalChars += max(chars, 0)
    }

    private fun topBooksFromMap(values: Map<String, BookAggregate>?, limit: Int): List<AnnualReportData.BookStat> {
        val result = ArrayList<AnnualReportData.BookStat>()
        if (values.isNullOrEmpty()) return result
        val aggregates = ArrayList(values.values)
        aggregates.sortWith { left, right ->
            val secondsCompare = right.totalSeconds.compareTo(left.totalSeconds)
            if (secondsCompare != 0) secondsCompare else {
                val charsCompare = right.totalChars.compareTo(left.totalChars)
                if (charsCompare != 0) charsCompare else left.title.compareTo(right.title)
            }
        }
        val safeLimit = max(0, limit)
        for (aggregate in aggregates) {
            if (result.size >= safeLimit) break
            result.add(AnnualReportData.BookStat(aggregate.title, aggregate.author, aggregate.totalSeconds, aggregate.totalChars))
        }
        return result
    }

    private fun topNamedStats(values: Map<String, Int>?, limit: Int): List<AnnualReportData.NamedStat> {
        val result = ArrayList<AnnualReportData.NamedStat>()
        if (values.isNullOrEmpty()) return result
        val stats = ArrayList<AnnualReportData.NamedStat>()
        for ((rawName, seconds) in values) {
            val name = rawName.trim()
            if (name.isNotEmpty()) stats.add(AnnualReportData.NamedStat(name, seconds))
        }
        stats.sortWith { left, right ->
            val secondsCompare = right.totalSeconds.compareTo(left.totalSeconds)
            if (secondsCompare != 0) secondsCompare else left.name.compareTo(right.name)
        }
        val safeLimit = max(0, limit)
        for (stat in stats) {
            if (result.size >= safeLimit) break
            result.add(stat)
        }
        return result
    }

    private fun firstBookTitle(report: AnnualReportData?): String = report?.primaryBookStat()?.title.orEmpty()

    private fun firstStatName(stats: List<AnnualReportData.NamedStat>?): String =
        if (stats.isNullOrEmpty()) "" else stats[0].name

    private fun bookKey(row: ReadingTimeEntryRecord?, book: BookRecord?): String {
        if (book != null) {
            val statsKey = book.readingStatsKey?.trim().orEmpty()
            if (statsKey.isNotEmpty()) return statsKey
            return ReadingStatsUtils.buildTitleAuthorKey(book.title, book.author)
        }
        val rowIdentity = row?.bookIdentity?.trim().orEmpty()
        if (rowIdentity.isNotEmpty()) return rowIdentity
        return ReadingStatsUtils.buildTitleAuthorKey(row?.bookTitle.orEmpty(), row?.bookAuthor.orEmpty())
    }

    private fun findBookForStats(books: List<BookRecord>?, row: ReadingTimeEntryRecord?): BookRecord? {
        if (books == null || row == null) return null
        for (book in books) if (matchesBook(row, book)) return book
        return null
    }

    private fun matchesBook(row: ReadingTimeEntryRecord?, book: BookRecord?): Boolean {
        if (row == null || book == null) return false
        val statsKey = book.readingStatsKey?.trim().orEmpty()
        val rowIdentity = row.bookIdentity?.trim().orEmpty()
        if (statsKey.isNotEmpty() && statsKey == rowIdentity) return true
        return ReadingStatsUtils.buildTitleAuthorKey(book.title, book.author) ==
            ReadingStatsUtils.buildTitleAuthorKey(row.bookTitle, row.bookAuthor)
    }

    private fun longestStreak(days: Set<LocalDate>?): Int {
        if (days.isNullOrEmpty()) return 0
        val sorted = ArrayList(days)
        sorted.sort()
        var best = 1
        var current = 1
        for (i in 1 until sorted.size) {
            if (sorted[i - 1].plusDays(1) == sorted[i]) {
                current++
            } else {
                best = max(best, current)
                current = 1
            }
        }
        return max(best, current)
    }

    private fun safeBookTitle(value: String?): String = ReadingStatsUtils.safeBookTitle(value)

    private fun safeAuthorForAggregation(value: String?): String =
        if (value.isNullOrBlank()) "" else ReadingStatsUtils.safeBookAuthor(value)

    private fun safeAuthorForDisplay(value: String?): String =
        if (value.isNullOrBlank()) "" else ReadingStatsUtils.safeBookAuthor(value)

    private fun firstText(values: List<String>?): String {
        values ?: return ""
        for (value in values) if (value.isNotBlank()) return value.trim()
        return ""
    }

    private class BookAggregate(title: String?, author: String?) {
        val title = title?.trim().orEmpty()
        val author = author?.trim().orEmpty()
        var totalSeconds = 0
        var totalChars = 0
    }
}
