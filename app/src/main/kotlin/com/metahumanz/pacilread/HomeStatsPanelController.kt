package com.metahumanz.pacilread

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.metahumanz.pacilread.model.ReadingBookStatRecord
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import com.metahumanz.pacilread.stats.annual.AnnualReportBuilder
import com.metahumanz.pacilread.stats.annual.AnnualReportData
import com.metahumanz.pacilread.stats.annual.AnnualReportExportController
import com.metahumanz.pacilread.stats.annual.AnnualReportInsight
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager
import com.metahumanz.pacilread.sync.WebDavClient
import com.metahumanz.pacilread.ui.LaunchSourceTransition
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

class HomeStatsPanelController(
    private val activity: Activity,
    private val databaseHelper: JsonDatabase,
    private val settingsStore: SettingsStore,
    private val executor: ExecutorService,
) {
    private val readingStatsSyncManager = ReadingStatsSyncManager(activity, databaseHelper, settingsStore, WebDavClient(settingsStore))
    private val listLayout: LinearLayout? = activity.findViewById(R.id.layout_home_stats_list)
    private val statusText: TextView? = activity.findViewById(R.id.text_home_stats_status)
    private val totalText: TextView? = activity.findViewById(R.id.text_home_stats_total)
    private val totalMetaText: TextView? = activity.findViewById(R.id.text_home_stats_meta)
    private val emptyText: TextView? = activity.findViewById(R.id.text_home_stats_empty)
    private val reportCardTitleText: TextView? = activity.findViewById(R.id.text_home_report_card_title)
    private val annualReportSummaryText: TextView? = activity.findViewById(R.id.text_home_annual_report_summary)
    private val weekRangeModeLayout: LinearLayout? = activity.findViewById(R.id.layout_home_week_range_mode)
    private val todayButton: Button? = activity.findViewById(R.id.button_home_stats_today)
    private val weekButton: Button? = activity.findViewById(R.id.button_home_stats_week)
    private val yearButton: Button? = activity.findViewById(R.id.button_home_stats_year)
    private val weekNaturalButton: Button? = activity.findViewById(R.id.button_home_week_natural)
    private val weekRollingButton: Button? = activity.findViewById(R.id.button_home_week_rolling)
    private val annualReportButton: Button? = activity.findViewById(R.id.button_home_generate_annual_report)
    private val annualReportExportController = AnnualReportExportController(activity)
    private var selectedPeriod = ReadingStatsUtils.PERIOD_TODAY
    private var selectedWeekMode = ReadingStatsUtils.WEEK_MODE_NATURAL
    private var currentAnnualReport: AnnualReportData? = null
    private val loadGeneration = AtomicInteger()

    init { setupControls() }

    fun refreshIfVisible(currentPage: Int, syncFirst: Boolean) {
        if (currentPage != HomeNavigationController.PAGE_STATS || !settingsStore.isReadingTimeTrackingEnabled) return
        updatePeriodButtons()
        val requestId = loadGeneration.incrementAndGet()
        val period = selectedPeriod
        val weekMode = selectedWeekMode
        val shouldSync = syncFirst && readingStatsSyncManager.canAutoSync()
        statusText?.text = if (shouldSync) "正在同步云端阅读统计，本地数据已先显示" else "正在刷新阅读统计..."
        executor.execute {
            postSnapshot(requestId, buildSnapshot(period, weekMode,
                if (shouldSync) "正在同步云端阅读统计，本地数据已先显示" else "当前展示的是本地阅读统计"))
            if (!shouldSync) return@execute
            val syncMessage = try {
                readingStatsSyncManager.downloadAndMergeReadingStats()
                "已同步云端阅读统计"
            } catch (error: Exception) {
                "云端同步失败，当前展示本地统计：${readableError(error)}"
            }
            postSnapshot(requestId, buildSnapshot(period, weekMode, syncMessage))
        }
    }

    private fun buildSnapshot(period: String, weekMode: String, syncMessage: String): HomeStatsSnapshot {
        val range = ReadingStatsUtils.rangeForPeriod(period, ZoneId.systemDefault(), weekMode)
        return HomeStatsSnapshot(
            databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null),
            databaseHelper.getReadingBookStats(range.startDateString(), range.endDateString()),
            AnnualReportBuilder.buildGlobal(databaseHelper, ZoneId.systemDefault(), period, weekMode),
            syncMessage,
        )
    }

    private fun postSnapshot(requestId: Int, snapshot: HomeStatsSnapshot) {
        activity.runOnUiThread {
            if (requestId == loadGeneration.get()) render(snapshot.totalSeconds, snapshot.records, snapshot.annualReport, snapshot.syncMessage)
        }
    }

    private class HomeStatsSnapshot(
        val totalSeconds: Int,
        val records: List<ReadingBookStatRecord>?,
        val annualReport: AnnualReportData?,
        val syncMessage: String,
    )

    private fun setupControls() {
        todayButton?.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_TODAY) }
        weekButton?.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_WEEK) }
        yearButton?.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_YEAR) }
        weekNaturalButton?.setOnClickListener { selectWeekMode(ReadingStatsUtils.WEEK_MODE_NATURAL) }
        weekRollingButton?.setOnClickListener { selectWeekMode(ReadingStatsUtils.WEEK_MODE_ROLLING) }
        annualReportButton?.setOnClickListener {
            val report = currentAnnualReport
            if (report == null || !report.hasReadingData()) {
                AppUiUtils.showToast(activity, "暂无可生成的${reportCardTitleFor(selectedPeriod)}")
            } else annualReportExportController.showPreview(report)
        }
        updatePeriodButtons()
    }

    private fun selectPeriod(period: String) {
        selectedPeriod = ReadingStatsUtils.normalizePeriodKey(period)
        updatePeriodButtons()
        refreshIfVisible(HomeNavigationController.PAGE_STATS, false)
    }

    private fun selectWeekMode(weekMode: String) {
        selectedWeekMode = ReadingStatsUtils.normalizeWeekMode(weekMode)
        updatePeriodButtons()
        if (selectedPeriod == ReadingStatsUtils.PERIOD_WEEK) refreshIfVisible(HomeNavigationController.PAGE_STATS, false)
    }

    private fun updatePeriodButtons() {
        AppUiUtils.styleToggleButton(activity, todayButton, selectedPeriod == ReadingStatsUtils.PERIOD_TODAY)
        AppUiUtils.styleToggleButton(activity, weekButton, selectedPeriod == ReadingStatsUtils.PERIOD_WEEK)
        AppUiUtils.styleToggleButton(activity, yearButton, selectedPeriod == ReadingStatsUtils.PERIOD_YEAR)
        weekRangeModeLayout?.visibility = if (selectedPeriod == ReadingStatsUtils.PERIOD_WEEK) View.VISIBLE else View.GONE
        AppUiUtils.styleToggleButton(activity, weekNaturalButton, selectedWeekMode == ReadingStatsUtils.WEEK_MODE_NATURAL)
        AppUiUtils.styleToggleButton(activity, weekRollingButton, selectedWeekMode == ReadingStatsUtils.WEEK_MODE_ROLLING)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        annualReportExportController.onActivityResult(requestCode, resultCode, data)

    private fun render(totalSeconds: Int, records: List<ReadingBookStatRecord>?, annualReport: AnnualReportData?, syncMessage: String) {
        statusText?.text = syncMessage
        totalText?.text = ReadingStatsUtils.formatDuration(totalSeconds)
        totalMetaText?.text = formatStatsMeta(annualReport)
        renderAnnualReport(annualReport)
        val layout = listLayout ?: return
        val emptyView = emptyText ?: return
        layout.removeAllViews()
        val empty = records.isNullOrEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) return
        val inflater = LayoutInflater.from(activity)
        for (record in records) {
            val row = inflater.inflate(R.layout.item_reading_book_stat, layout, false)
            row.findViewById<TextView>(R.id.text_stat_row_title).text = ReadingStatsUtils.safeBookTitle(record.bookTitle)
            row.findViewById<TextView>(R.id.text_stat_row_author).text = ReadingStatsUtils.safeBookAuthor(record.bookAuthor)
            row.findViewById<TextView>(R.id.text_stat_row_meta).text =
                if (record.localBookId > 0L) "点击查看本书统计详情" else "当前设备没有这本书的本地副本"
            row.findViewById<TextView>(R.id.text_stat_row_duration).text = ReadingStatsUtils.formatDuration(record.totalDurationSeconds)
            if (record.localBookId > 0L) row.setOnClickListener { source ->
                val intent = Intent(activity, ReadingStatsActivity::class.java).putExtra("book_id", record.localBookId)
                if (TransitionMotionModeHelper.isFluidMode(settingsStore)) LaunchSourceTransition.attach(intent, source)
                activity.startActivity(intent)
            } else {
                row.isEnabled = false
                row.alpha = 0.78f
            }
            layout.addView(row)
        }
    }

    private fun renderAnnualReport(annualReport: AnnualReportData?) {
        currentAnnualReport = annualReport
        val hasData = annualReport?.hasReadingData() == true
        annualReportButton?.apply {
            isEnabled = hasData
            alpha = if (hasData) 1f else 0.55f
            text = "生成${annualReport?.reportKindLabel() ?: reportCardTitleFor(selectedPeriod)}"
        }
        reportCardTitleText?.text = annualReport?.reportKindLabel() ?: reportCardTitleFor(selectedPeriod)
        val summary = annualReportSummaryText ?: return
        if (!hasData) {
            summary.text = "当前范围还没有足够的阅读统计"
            return
        }
        val report = requireNotNull(annualReport)
        summary.text = "${report.periodTitle} · ${ReadingStatsUtils.formatDuration(report.totalSeconds)} · " +
            "${report.readingDays} 个阅读日\n${AnnualReportInsight.sentence(report)}"
    }

    private fun reportCardTitleFor(period: String): String = when (period) {
        ReadingStatsUtils.PERIOD_WEEK -> "周报"
        ReadingStatsUtils.PERIOD_YEAR -> "年度报告"
        else -> "每日报告"
    }

    private fun formatStatsMeta(report: AnnualReportData?): String =
        "阅读字数 ${formatNumber(Math.max(report?.totalChars ?: 0, 0))} 字" +
            " · 阅读天数 ${Math.max(report?.readingDays ?: 0, 0)} 天" +
            " · 最长连续 ${Math.max(report?.longestStreak ?: 0, 0)} 天"

    private fun formatNumber(value: Int): String = String.format(Locale.SIMPLIFIED_CHINESE, "%,d", Math.max(value, 0))
    private fun readableError(error: Throwable?): String {
        if (error == null) return "未知错误"
        var message = error.message
        if (message.isNullOrBlank()) message = error.cause?.message
        if (message.isNullOrBlank()) message = error.javaClass.simpleName
        return if (message.length > 120) message.substring(0, 120) + "..." else message
    }
}
