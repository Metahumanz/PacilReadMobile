package com.metahumanz.pacilread

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.metahumanz.pacilread.model.BookRecord
import com.metahumanz.pacilread.model.ChapterRecord
import com.metahumanz.pacilread.model.ReadingBookStatRecord
import com.metahumanz.pacilread.model.ReadingTimeEntryRecord
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import com.metahumanz.pacilread.stats.annual.AnnualReportBuilder
import com.metahumanz.pacilread.stats.annual.AnnualReportData
import com.metahumanz.pacilread.stats.annual.AnnualReportExportController
import com.metahumanz.pacilread.stats.annual.AnnualReportInsight
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager
import com.metahumanz.pacilread.sync.WebDavClient
import com.metahumanz.pacilread.theme.ThemedActivity
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.ui.ActivityTransitionCompat
import com.metahumanz.pacilread.ui.BookCoverViewHelper
import com.metahumanz.pacilread.ui.LaunchSourceTransition
import com.metahumanz.pacilread.ui.PredictiveBackScaleController
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ReadingStatsActivity : ThemedActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE)
    private val loadGeneration = AtomicInteger()
    private lateinit var databaseHelper: JsonDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var readingStatsSyncManager: ReadingStatsSyncManager
    private lateinit var pageTitleText: TextView
    private lateinit var syncStatusText: TextView
    private lateinit var scopeLabelText: TextView
    private lateinit var scopeTotalText: TextView
    private lateinit var scopeCharsText: TextView
    private lateinit var bookTitleText: TextView
    private lateinit var bookAuthorText: TextView
    private lateinit var bookProgressText: TextView
    private lateinit var bookLastReadText: TextView
    private lateinit var bookSpeedText: TextView
    private lateinit var bookEtaText: TextView
    private lateinit var coverFallbackText: TextView
    private lateinit var listEmptyText: TextView
    private lateinit var coverImage: ImageView
    private lateinit var bookMetaLayout: LinearLayout
    private lateinit var listCardLayout: LinearLayout
    private lateinit var bookStatsListLayout: LinearLayout
    private var readingCalendarLayout: LinearLayout? = null
    private var annualReportLayout: LinearLayout? = null
    private var weekRangeModeLayout: LinearLayout? = null
    private var monthRangeModeLayout: LinearLayout? = null
    private var yearRangeModeLayout: LinearLayout? = null
    private var reportCardTitleText: TextView? = null
    private lateinit var periodTodayButton: Button
    private lateinit var periodWeekButton: Button
    private lateinit var periodMonthButton: Button
    private lateinit var periodYearButton: Button
    private var weekNaturalButton: Button? = null
    private var weekRollingButton: Button? = null
    private var monthNaturalButton: Button? = null
    private var monthRollingButton: Button? = null
    private var yearNaturalButton: Button? = null
    private var yearRollingButton: Button? = null
    private var shareAnnualReportButton: Button? = null
    private var selectedPeriod = ReadingStatsUtils.PERIOD_TODAY
    private var selectedWeekMode = ReadingStatsUtils.WEEK_MODE_NATURAL
    private var selectedMonthMode = ReadingStatsUtils.MONTH_MODE_NATURAL
    private var selectedYearMode = ReadingStatsUtils.YEAR_MODE_NATURAL
    private var bookId = -1L
    private var launchSource: LaunchSourceTransition.Source? = null
    private var finishingWithSource = false
    private var currentAnnualReport: AnnualReportData? = null
    private lateinit var annualReportExportController: AnnualReportExportController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reading_stats)
        databaseHelper = JsonDatabase.getInstance(this)
        settingsStore = SettingsStore(this)
        readingStatsSyncManager = ReadingStatsSyncManager(this, databaseHelper, settingsStore, WebDavClient(settingsStore))
        annualReportExportController = AnnualReportExportController(this)
        bookId = intent.getLongExtra("book_id", -1L)
        launchSource = LaunchSourceTransition.fromIntentSource(intent)
        bindViews()
        setupControls()
        installPredictiveBack()
        updatePeriodButtons()
        renderModeShell()
    }

    override fun onResume() {
        super.onResume()
        loadStats(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (annualReportExportController.onActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun bindViews() {
        pageTitleText = findViewById(R.id.text_page_title)
        syncStatusText = findViewById(R.id.text_sync_status)
        scopeLabelText = findViewById(R.id.text_scope_label)
        scopeTotalText = findViewById(R.id.text_scope_total)
        scopeCharsText = findViewById(R.id.text_scope_chars)
        bookTitleText = findViewById(R.id.text_book_title)
        bookAuthorText = findViewById(R.id.text_book_author)
        bookProgressText = findViewById(R.id.text_book_progress)
        bookLastReadText = findViewById(R.id.text_book_last_read)
        bookSpeedText = findViewById(R.id.text_book_speed)
        bookEtaText = findViewById(R.id.text_book_eta)
        coverFallbackText = findViewById(R.id.text_cover_fallback)
        listEmptyText = findViewById(R.id.text_list_empty)
        coverImage = findViewById(R.id.image_cover)
        bookMetaLayout = findViewById(R.id.layout_book_meta)
        listCardLayout = findViewById(R.id.layout_list_card)
        bookStatsListLayout = findViewById(R.id.layout_book_stats_list)
        readingCalendarLayout = findViewById(R.id.layout_reading_calendar)
        annualReportLayout = findViewById(R.id.layout_annual_report)
        weekRangeModeLayout = findViewById(R.id.layout_week_range_mode)
        monthRangeModeLayout = findViewById(R.id.layout_month_range_mode)
        yearRangeModeLayout = findViewById(R.id.layout_year_range_mode)
        reportCardTitleText = findViewById(R.id.text_report_card_title)
        periodTodayButton = findViewById(R.id.button_period_today)
        periodWeekButton = findViewById(R.id.button_period_week)
        periodMonthButton = findViewById(R.id.button_period_month)
        periodYearButton = findViewById(R.id.button_period_year)
        weekNaturalButton = findViewById(R.id.button_week_natural)
        weekRollingButton = findViewById(R.id.button_week_rolling)
        monthNaturalButton = findViewById(R.id.button_month_natural)
        monthRollingButton = findViewById(R.id.button_month_rolling)
        yearNaturalButton = findViewById(R.id.button_year_natural)
        yearRollingButton = findViewById(R.id.button_year_rolling)
        shareAnnualReportButton = findViewById(R.id.button_share_annual_report)
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.button_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        periodTodayButton.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_TODAY) }
        periodWeekButton.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_WEEK) }
        periodMonthButton.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_MONTH) }
        periodYearButton.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_YEAR) }
        weekNaturalButton?.setOnClickListener { selectWeekMode(ReadingStatsUtils.WEEK_MODE_NATURAL) }
        weekRollingButton?.setOnClickListener { selectWeekMode(ReadingStatsUtils.WEEK_MODE_ROLLING) }
        monthNaturalButton?.setOnClickListener { selectMonthMode(ReadingStatsUtils.MONTH_MODE_NATURAL) }
        monthRollingButton?.setOnClickListener { selectMonthMode(ReadingStatsUtils.MONTH_MODE_LAST_30_DAYS) }
        yearNaturalButton?.setOnClickListener { selectYearMode(ReadingStatsUtils.YEAR_MODE_NATURAL) }
        yearRollingButton?.setOnClickListener { selectYearMode(ReadingStatsUtils.YEAR_MODE_LAST_365_DAYS) }
        shareAnnualReportButton?.setOnClickListener { shareAnnualReport() }
    }

    private fun installPredictiveBack() {
        if (!TransitionMotionModeHelper.isFluidMode(settingsStore)) return
        val root = findViewById<View>(R.id.reading_stats_root) ?: return
        PredictiveBackScaleController.install(this, root, PredictiveBackScaleController.Profile.standard(),
            object : PredictiveBackScaleController.Delegate {
                override fun shouldAnimateBack() = true
                override fun consumeBack() = false
                override fun commitBack() = finishWithSourceTransition()
                override fun commitBackFromGesture() = true
            })
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (!TransitionMotionModeHelper.isFluidMode(settingsStore)) {
            finishWithSourceTransition()
            return
        }
        super.onBackPressed()
    }

    private fun finishWithSourceTransition() {
        if (finishingWithSource) return
        finishingWithSource = true
        val root = findViewById<View>(R.id.reading_stats_root)
        if (TransitionMotionModeHelper.isFluidMode(settingsStore) &&
            LaunchSourceTransition.animateExitToSource(root, launchSource, 240L, ::finishNow)) return
        animateExitToCenter(root)
    }

    private fun animateExitToCenter(root: View?) {
        if (root == null) {
            finishNow()
            return
        }
        root.animate().cancel()
        root.animate().scaleX(PredictiveBackScaleController.STANDARD_MIN_SCALE)
            .scaleY(PredictiveBackScaleController.STANDARD_MIN_SCALE).alpha(0f)
            .translationX(0f).translationY(0f).setDuration(160L)
            .setInterpolator(DecelerateInterpolator()).withEndAction(::finishNow).start()
    }

    private fun finishNow() {
        finish()
        ActivityTransitionCompat.overrideClose(this, 0, 0)
    }

    private fun renderModeShell() {
        val singleBookMode = bookId > 0L
        pageTitleText.text = if (singleBookMode) "本书阅读统计" else "阅读统计"
        bookMetaLayout.visibility = if (singleBookMode) View.VISIBLE else View.GONE
        listCardLayout.visibility = if (singleBookMode) View.GONE else View.VISIBLE
    }

    private fun selectPeriod(periodKey: String) {
        selectedPeriod = ReadingStatsUtils.normalizePeriodKey(periodKey)
        updatePeriodButtons()
        loadStats(false)
    }

    private fun selectWeekMode(weekMode: String) {
        selectedWeekMode = ReadingStatsUtils.normalizeWeekMode(weekMode)
        updatePeriodButtons()
        if (selectedPeriod == ReadingStatsUtils.PERIOD_WEEK) loadStats(false)
    }

    private fun selectMonthMode(monthMode: String) {
        selectedMonthMode = ReadingStatsUtils.normalizeMonthMode(monthMode)
        updatePeriodButtons()
        if (selectedPeriod == ReadingStatsUtils.PERIOD_MONTH) loadStats(false)
    }

    private fun selectYearMode(yearMode: String) {
        selectedYearMode = ReadingStatsUtils.normalizeYearMode(yearMode)
        updatePeriodButtons()
        if (selectedPeriod == ReadingStatsUtils.PERIOD_YEAR) loadStats(false)
    }

    private fun updatePeriodButtons() {
        stylePeriodButton(periodTodayButton, selectedPeriod == ReadingStatsUtils.PERIOD_TODAY)
        stylePeriodButton(periodWeekButton, selectedPeriod == ReadingStatsUtils.PERIOD_WEEK)
        stylePeriodButton(periodMonthButton, selectedPeriod == ReadingStatsUtils.PERIOD_MONTH)
        stylePeriodButton(periodYearButton, selectedPeriod == ReadingStatsUtils.PERIOD_YEAR)
        updateRangeModeControls()
    }

    private fun updateRangeModeControls() {
        val weekSelected = selectedPeriod == ReadingStatsUtils.PERIOD_WEEK
        weekRangeModeLayout?.visibility = if (weekSelected) View.VISIBLE else View.GONE
        stylePeriodButton(weekNaturalButton, selectedWeekMode == ReadingStatsUtils.WEEK_MODE_NATURAL)
        stylePeriodButton(weekRollingButton, selectedWeekMode == ReadingStatsUtils.WEEK_MODE_ROLLING)
        val monthSelected = selectedPeriod == ReadingStatsUtils.PERIOD_MONTH
        monthRangeModeLayout?.visibility = if (monthSelected) View.VISIBLE else View.GONE
        stylePeriodButton(monthNaturalButton, selectedMonthMode == ReadingStatsUtils.MONTH_MODE_NATURAL)
        stylePeriodButton(monthRollingButton, selectedMonthMode == ReadingStatsUtils.MONTH_MODE_LAST_30_DAYS)
        val yearSelected = selectedPeriod == ReadingStatsUtils.PERIOD_YEAR
        yearRangeModeLayout?.visibility = if (yearSelected) View.VISIBLE else View.GONE
        stylePeriodButton(yearNaturalButton, selectedYearMode == ReadingStatsUtils.YEAR_MODE_NATURAL)
        stylePeriodButton(yearRollingButton, selectedYearMode == ReadingStatsUtils.YEAR_MODE_LAST_365_DAYS)
    }

    private fun stylePeriodButton(button: Button?, selected: Boolean) {
        button ?: return
        button.setBackgroundResource(if (selected) R.drawable.bg_app_primary_button else R.drawable.bg_app_outline_button)
        button.setTextColor(ThemeModeHelper.resolveColor(this, if (selected) R.color.app_button_primary_text else R.color.app_button_outline_text))
    }

    private fun loadStats(syncFirst: Boolean) {
        val requestId = loadGeneration.incrementAndGet()
        val period = selectedPeriod
        val weekMode = selectedWeekMode
        val monthMode = selectedMonthMode
        val yearMode = selectedYearMode
        val scopedBookId = bookId
        val shouldSync = syncFirst && settingsStore.isWebDavEnabled && settingsStore.isWebDavSyncReadingStatsEnabled
        syncStatusText.text = if (shouldSync) "正在同步云端阅读统计，本地数据已先显示" else "正在加载阅读统计..."
        executor.execute {
            val local = buildStatsSnapshot(period, scopedBookId, weekMode, monthMode, yearMode,
                if (shouldSync) "正在同步云端阅读统计，本地数据已先显示" else "当前展示的是本地阅读统计")
            postStatsSnapshot(requestId, local)
            if (!shouldSync) return@execute
            val syncMessage = try {
                readingStatsSyncManager.downloadAndMergeReadingStats()
                "已同步云端阅读统计"
            } catch (error: Exception) {
                "云端同步失败，当前展示本地统计：${readableError(error)}"
            }
            postStatsSnapshot(requestId, buildStatsSnapshot(period, scopedBookId, weekMode, monthMode, yearMode, syncMessage))
        }
    }

    private fun buildStatsSnapshot(
        period: String,
        scopedBookId: Long,
        weekMode: String,
        monthMode: String,
        yearMode: String,
        syncMessage: String,
    ): StatsSnapshot {
        val range = ReadingStatsUtils.rangeForPeriod(period, ZoneId.systemDefault(), weekMode, monthMode, yearMode)
        val snapshot = StatsSnapshot(period = period, weekMode = weekMode, monthMode = monthMode, yearMode = yearMode, bookId = scopedBookId, syncMessage = syncMessage)
        snapshot.rangeRows = databaseHelper.getReadingStatsRows(range.startDateString(), range.endDateString())
        if (scopedBookId > 0L) {
            snapshot.book = databaseHelper.getBook(scopedBookId)
            snapshot.book?.let { book ->
                snapshot.chapters = databaseHelper.getChapters(book.id, false)
                snapshot.totalSeconds = databaseHelper.getReadingDurationSecondsForBook(range.startDateString(), range.endDateString(), book.readingStatsKey, book.title, book.author)
                snapshot.totalChars = databaseHelper.getReadingCharCountForBook(range.startDateString(), range.endDateString(), book.readingStatsKey, book.title, book.author)
                snapshot.bookEta = buildBookEta(book, snapshot.chapters)
                snapshot.annualReport = AnnualReportBuilder.buildBook(databaseHelper, book, ZoneId.systemDefault(), period, weekMode, monthMode, yearMode)
            }
        } else {
            snapshot.totalSeconds = databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null)
            snapshot.totalChars = databaseHelper.getReadingCharCount(range.startDateString(), range.endDateString(), null)
            snapshot.bookStats = databaseHelper.getReadingBookStats(range.startDateString(), range.endDateString())
            snapshot.annualReport = AnnualReportBuilder.buildGlobal(databaseHelper, ZoneId.systemDefault(), period, weekMode, monthMode, yearMode)
        }
        return snapshot
    }

    private fun postStatsSnapshot(requestId: Int, snapshot: StatsSnapshot) {
        runOnUiThread {
            if (requestId != loadGeneration.get()) return@runOnUiThread
            syncStatusText.text = snapshot.syncMessage
            scopeLabelText.text = "${periodLabelPrefix(snapshot.period, snapshot.weekMode, snapshot.monthMode, snapshot.yearMode)}阅读总时长"
            scopeTotalText.text = ReadingStatsUtils.formatDuration(snapshot.totalSeconds)
            scopeCharsText.text = formatStatsMeta(snapshot.totalChars, snapshot.annualReport)
            renderCalendar(snapshot.rangeRows, snapshot.period, snapshot.weekMode, snapshot.monthMode, snapshot.yearMode)
            currentAnnualReport = snapshot.annualReport
            renderAnnualReport(snapshot.annualReport)
            if (snapshot.bookId > 0L) renderSingleBook(snapshot.book, snapshot.chapters, snapshot.totalSeconds, snapshot.bookEta)
            else renderGlobalList(snapshot.bookStats)
        }
    }

    private class StatsSnapshot(
        var period: String,
        var weekMode: String,
        var monthMode: String,
        var yearMode: String,
        var bookId: Long,
        var totalSeconds: Int = 0,
        var totalChars: Int = 0,
        var syncMessage: String,
        var book: BookRecord? = null,
        var chapters: List<ChapterRecord>? = null,
        var bookStats: List<ReadingBookStatRecord>? = null,
        var rangeRows: List<ReadingTimeEntryRecord>? = null,
        var annualReport: AnnualReportData? = null,
        var bookEta: BookEta? = null,
    )

    private fun renderSingleBook(book: BookRecord?, chapters: List<ChapterRecord>?, totalSeconds: Int, bookEta: BookEta?) {
        if (book == null) {
            bookTitleText.text = "书籍不存在"
            bookAuthorText.text = "这本书已经不在当前设备的书架中"
            bookProgressText.text = "无法展示详细信息"
            bookLastReadText.text = ""
            bookSpeedText.text = ""
            bookEtaText.text = ""
            BookCoverViewHelper.bindCover(coverImage, coverFallbackText, null, null)
            return
        }
        bookTitleText.text = ReadingStatsUtils.safeBookTitle(book.title)
        bookAuthorText.text = ReadingStatsUtils.safeBookAuthor(book.author)
        val chapterCount = chapters?.size ?: 0
        val chapterPosition = if (chapterCount <= 0) 0 else min(book.progressIndex + 1, chapterCount)
        bookProgressText.text = if (chapterCount > 0)
            String.format(Locale.SIMPLIFIED_CHINESE, "阅读进度：第 %d/%d 章 · 当前范围 %s", chapterPosition, chapterCount, ReadingStatsUtils.formatDuration(totalSeconds))
        else String.format(Locale.SIMPLIFIED_CHINESE, "阅读进度：当前范围 %s", ReadingStatsUtils.formatDuration(totalSeconds))
        bookLastReadText.text = if (book.lastReadAt > 0) "最近阅读：${dateTimeFormat.format(Date(book.lastReadAt))}" else "最近阅读：暂无"
        bookSpeedText.text = if (bookEta == null || bookEta.charsPerMinute <= 0) "阅读速度：暂无足够数据"
        else String.format(Locale.SIMPLIFIED_CHINESE, "阅读速度：约 %d 字/分", bookEta.charsPerMinute.roundToInt())
        bookEtaText.text = if (bookEta == null || bookEta.etaSeconds <= 0) "预计读完：暂无足够数据"
        else "预计读完：${ReadingStatsUtils.formatDuration(bookEta.etaSeconds)}"
        bindCover(book.coverPath, book.title)
    }

    private fun renderGlobalList(bookStats: List<ReadingBookStatRecord>?) {
        bookStatsListLayout.removeAllViews()
        if (bookStats.isNullOrEmpty()) {
            listEmptyText.visibility = View.VISIBLE
            return
        }
        listEmptyText.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (record in bookStats) {
            val row = inflater.inflate(R.layout.item_reading_book_stat, bookStatsListLayout, false)
            row.findViewById<TextView>(R.id.text_stat_row_title).text = ReadingStatsUtils.safeBookTitle(record.bookTitle)
            row.findViewById<TextView>(R.id.text_stat_row_author).text = ReadingStatsUtils.safeBookAuthor(record.bookAuthor)
            row.findViewById<TextView>(R.id.text_stat_row_meta).text = if (record.localBookId > 0L) "点击查看本书统计详情" else "当前设备没有这本书的本地副本"
            row.findViewById<TextView>(R.id.text_stat_row_duration).text = "${ReadingStatsUtils.formatDuration(record.totalDurationSeconds)} · ${formatNumber(record.totalCharCount)} 字"
            if (record.localBookId > 0L) {
                row.setOnClickListener { source ->
                    val intent = Intent(this, ReadingStatsActivity::class.java).putExtra("book_id", record.localBookId)
                    LaunchSourceTransition.attach(intent, source)
                    startActivity(intent)
                }
            } else {
                row.isEnabled = false
                row.alpha = 0.78f
            }
            bookStatsListLayout.addView(row)
        }
    }

    private fun renderCalendar(rows: List<ReadingTimeEntryRecord>?, period: String, weekMode: String, monthMode: String, yearMode: String) {
        val calendar = readingCalendarLayout ?: return
        calendar.removeAllViews()
        val byDate = HashMap<String, IntArray>()
        rows?.forEach { entry ->
            val values = byDate.getOrPut(entry.date ?: "") { intArrayOf(0, 0) }
            values[0] += max(entry.durationSeconds, 0)
            values[1] += max(entry.charCount, 0)
        }
        val range = ReadingStatsUtils.rangeForPeriod(period, ZoneId.systemDefault(), weekMode, monthMode, yearMode)
        var start = range.startDate
        val end = range.endDate
        if (period == ReadingStatsUtils.PERIOD_YEAR) start = end.minusDays(29)
        var date = start
        while (!date.isAfter(end)) {
            val key = ReadingStatsUtils.formatDate(date)
            val values = byDate[key] ?: intArrayOf(0, 0)
            calendar.addView(TextView(this).apply {
                text = String.format(Locale.SIMPLIFIED_CHINESE, "%s  %s  %s 字", key, ReadingStatsUtils.formatDuration(values[0]), formatNumber(values[1]))
                setTextColor(ThemeModeHelper.resolveColor(this@ReadingStatsActivity, R.color.app_text_secondary))
                textSize = 13f
                setPadding(0, AppUiUtils.dp(this@ReadingStatsActivity, 4), 0, AppUiUtils.dp(this@ReadingStatsActivity, 4))
            })
            date = date.plusDays(1)
        }
    }

    private fun renderAnnualReport(report: AnnualReportData?) {
        val layout = annualReportLayout ?: return
        layout.removeAllViews()
        if (report == null || !report.hasReadingData()) {
            updateReportActions(null)
            addReportLine("当前范围还没有足够的阅读统计")
            shareAnnualReportButton?.isEnabled = false
            return
        }
        updateReportActions(report)
        shareAnnualReportButton?.isEnabled = true
        addReportLine("${report.periodTitle} · ${ReadingStatsUtils.formatDuration(report.totalSeconds)} · ${formatNumber(report.totalChars)} 字")
        addReportLine(AnnualReportInsight.sentence(report))
        addReportLine("阅读天数 ${report.readingDays} 天 · 最长连续 ${report.longestStreak} 天")
        if (report.isBookScope()) {
            addReportLine("书籍：${report.bookTitle}")
            addOptionalReportLine("作者：", report.bookAuthor)
            addOptionalReportLine("标签：", report.topTag)
            addOptionalReportLine("系列：", report.topSeries)
        } else {
            addReportLine(if (report.isYearReport()) "完成书籍 ${report.finishedBooks} 本" else "阅读书籍 ${report.readingBooks} 本")
            addTopBookReportLines(report)
            addNamedStatReportLine("常读作者：", report.topAuthors)
            addNamedStatReportLine("常读标签：", report.topTags)
            addNamedStatReportLine("常读系列：", report.topSeriesStats)
        }
    }

    private fun updateReportActions(report: AnnualReportData?) {
        val title = report?.reportKindLabel() ?: reportCardTitleFor(selectedPeriod)
        reportCardTitleText?.text = title
        shareAnnualReportButton?.text = "生成$title"
    }

    private fun addOptionalReportLine(prefix: String, value: String?) {
        if (!value.isNullOrBlank()) addReportLine(prefix + value.trim())
    }

    private fun addTopBookReportLines(report: AnnualReportData?) {
        if (report == null || report.topBooks.isEmpty()) {
            addOptionalReportLine("Top 书籍：", report?.topBook)
            return
        }
        for (i in 0 until min(3, report.topBooks.size)) {
            val stat = report.topBooks[i]
            val line = buildString {
                append("Top ").append(i + 1).append("：").append(stat.title)
                if (stat.author.isNotBlank()) append(" · ").append(stat.author)
                if (stat.totalSeconds > 0) append(" · ").append(ReadingStatsUtils.formatDuration(stat.totalSeconds))
                if (stat.totalChars > 0) append(" · ").append(formatNumber(stat.totalChars)).append(" 字")
            }
            addReportLine(line)
        }
    }

    private fun addNamedStatReportLine(prefix: String, stats: List<AnnualReportData.NamedStat?>?) {
        if (stats.isNullOrEmpty()) return
        val names = StringBuilder()
        var count = 0
        for (stat in stats) {
            if (stat == null || stat.name.isBlank()) continue
            if (names.isNotEmpty()) names.append(" / ")
            names.append(stat.name)
            if (++count >= 3) break
        }
        if (names.isNotEmpty()) addReportLine(prefix + names)
    }

    private fun addReportLine(text: String) {
        annualReportLayout?.addView(TextView(this).apply {
            this.text = text
            setTextColor(ThemeModeHelper.resolveColor(this@ReadingStatsActivity, R.color.app_text_secondary))
            textSize = 14f
            setPadding(0, AppUiUtils.dp(this@ReadingStatsActivity, 4), 0, AppUiUtils.dp(this@ReadingStatsActivity, 4))
        })
    }

    private fun buildBookEta(book: BookRecord?, chapters: List<ChapterRecord>?): BookEta? {
        book ?: return null
        val today = LocalDate.now(ZoneId.systemDefault())
        val start = ReadingStatsUtils.formatDate(today.minusDays(6))
        val end = ReadingStatsUtils.formatDate(today)
        val seconds = databaseHelper.getReadingDurationSecondsForBook(start, end, book.readingStatsKey, book.title, book.author)
        val chars = databaseHelper.getReadingCharCountForBook(start, end, book.readingStatsKey, book.title, book.author)
        if (seconds <= 0 || chars <= 0) return null
        val remaining = max(0, estimateBookChars(chapters) - estimateReadChars(book, chapters))
        val charsPerMinute = chars * 60f / seconds
        return BookEta(charsPerMinute, if (charsPerMinute <= 0) 0 else (remaining / charsPerMinute * 60f).roundToInt())
    }

    private fun estimateBookChars(chapters: List<ChapterRecord>?): Int {
        if (chapters == null) return 0
        var total = 0
        for (chapter in chapters) total += estimateChapterChars(chapter)
        return total
    }

    private fun estimateReadChars(book: BookRecord?, chapters: List<ChapterRecord>?): Int {
        if (book == null || chapters == null) return 0
        var total = 0
        for (chapter in chapters) {
            if (chapter.orderIndex < book.progressIndex) total += estimateChapterChars(chapter)
            else if (chapter.orderIndex == book.progressIndex) total += max(book.progressOffset, 0)
        }
        return total
    }

    private fun estimateChapterChars(chapter: ChapterRecord?): Int {
        chapter ?: return 0
        if (!chapter.bodyText.isNullOrEmpty()) return chapter.bodyText!!.length
        if (chapter.bodyTextSize > 0) return max(1L, chapter.bodyTextSize / 3).toInt()
        return 0
    }

    private fun shareAnnualReport() {
        val report = currentAnnualReport
        if (report == null || !report.hasReadingData()) {
            AppUiUtils.showToast(this, "暂无可生成的${reportCardTitleFor(selectedPeriod)}")
            return
        }
        annualReportExportController.showPreview(report)
    }

    private fun formatNumber(value: Int): String = String.format(Locale.SIMPLIFIED_CHINESE, "%,d", max(value, 0))

    private fun formatStatsMeta(totalChars: Int, report: AnnualReportData?): String =
        "阅读字数 ${formatNumber(totalChars)} 字 · 阅读天数 ${max(report?.readingDays ?: 0, 0)} 天 · 最长连续 ${max(report?.longestStreak ?: 0, 0)} 天"

    private fun readableError(error: Throwable?): String {
        if (error == null) return "未知错误"
        var message = error.message
        if (message.isNullOrBlank()) message = error.cause?.message
        if (message.isNullOrBlank()) message = error.javaClass.simpleName
        return if (message.length > 120) message.substring(0, 120) + "..." else message
    }

    private fun bindCover(path: String?, title: String?) = BookCoverViewHelper.bindCover(coverImage, coverFallbackText, path, title)

    private fun periodLabelPrefix(period: String, weekMode: String, monthMode: String, yearMode: String): String = when (period) {
        ReadingStatsUtils.PERIOD_WEEK -> if (weekMode == ReadingStatsUtils.WEEK_MODE_ROLLING) "过去七天" else "本周"
        ReadingStatsUtils.PERIOD_MONTH -> if (monthMode == ReadingStatsUtils.MONTH_MODE_LAST_30_DAYS) "过去30天" else "自然月"
        ReadingStatsUtils.PERIOD_YEAR -> if (yearMode == ReadingStatsUtils.YEAR_MODE_LAST_365_DAYS) "过去365天" else "本年"
        else -> "本日"
    }

    private fun reportCardTitleFor(period: String): String = when (period) {
        ReadingStatsUtils.PERIOD_WEEK -> "周报"
        ReadingStatsUtils.PERIOD_MONTH -> "月报"
        ReadingStatsUtils.PERIOD_YEAR -> "年度报告"
        else -> "每日报告"
    }

    private class BookEta(val charsPerMinute: Float, val etaSeconds: Int)
}
