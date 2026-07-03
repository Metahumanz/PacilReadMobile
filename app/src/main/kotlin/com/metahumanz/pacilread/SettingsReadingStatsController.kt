package com.metahumanz.pacilread

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import com.metahumanz.pacilread.storage.JsonDatabase
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.sync.ReadingStatsSyncManager
import com.metahumanz.pacilread.ui.LaunchSourceTransition
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper
import java.time.ZoneId
import java.util.concurrent.ExecutorService

class SettingsReadingStatsController(
    private val activity: Activity,
    private val databaseHelper: JsonDatabase,
    private val settingsStore: SettingsStore,
    private val readingStatsSyncManager: ReadingStatsSyncManager,
    private val executor: ExecutorService,
    private val callback: Callback,
) {
    interface Callback {
        fun isSettingsBusy(): Boolean
        fun saveSettings()
        fun setBusy(busy: Boolean)
        fun setStatusText(text: String)
        fun showToast(text: String)
    }

    private val readingTimeTrackingCheck: CheckBox? = activity.findViewById(R.id.check_reading_time_tracking)
    private val statsPeriodTodayButton: Button? = activity.findViewById(R.id.button_stats_period_today)
    private val statsPeriodWeekButton: Button? = activity.findViewById(R.id.button_stats_period_week)
    private val statsPeriodMonthButton: Button? = activity.findViewById(R.id.button_stats_period_month)
    private val statsPeriodYearButton: Button? = activity.findViewById(R.id.button_stats_period_year)
    private val openReadingStatsButton: Button? = activity.findViewById(R.id.button_open_reading_stats)
    private val readingStatsContentLayout: View? = activity.findViewById(R.id.layout_reading_stats_content)
    private val readingStatsHintText: TextView? = activity.findViewById(R.id.text_reading_stats_hint)
    private val readingStatsTotalText: TextView? = activity.findViewById(R.id.text_reading_stats_total)
    private var bindingValues = false
    private var selectedPeriod = ReadingStatsUtils.PERIOD_TODAY

    init {
        setupControls()
    }

    fun bindValues() {
        bindingValues = true
        readingTimeTrackingCheck?.isChecked = settingsStore.isReadingTimeTrackingEnabled
        updatePeriodButtons()
        updateVisibility()
        bindingValues = false
    }

    fun saveValues() {
        readingTimeTrackingCheck?.let { settingsStore.isReadingTimeTrackingEnabled = it.isChecked }
        updateVisibility()
    }

    fun refreshSummary(syncFirst: Boolean) {
        updateVisibility()
        val totalText = readingStatsTotalText
        if (totalText == null || !isTrackingChecked()) return
        totalText.text = "正在加载..."
        executor.execute {
            var syncError: String? = null
            if (syncFirst && settingsStore.isWebDavEnabled && settingsStore.isWebDavSyncReadingStatsEnabled) {
                try {
                    readingStatsSyncManager.downloadAndMergeReadingStats()
                } catch (error: Exception) {
                    syncError = error.message
                }
            }
            val range = ReadingStatsUtils.rangeForPeriod(selectedPeriod, ZoneId.systemDefault())
            val totalSeconds = databaseHelper.getReadingDurationSeconds(range.startDateString(), range.endDateString(), null)
            val finalSyncError = syncError
            activity.runOnUiThread {
                readingStatsHintText?.text = buildString {
                    append(when (selectedPeriod) {
                        ReadingStatsUtils.PERIOD_WEEK -> "本周阅读总时长"
                        ReadingStatsUtils.PERIOD_MONTH -> "自然月阅读总时长"
                        ReadingStatsUtils.PERIOD_YEAR -> "本年阅读总时长"
                        else -> "本日阅读总时长"
                    })
                    if (!finalSyncError.isNullOrBlank()) append(" · 云端同步失败")
                }
                totalText.text = ReadingStatsUtils.formatDuration(totalSeconds)
            }
        }
    }

    fun setBusy(busy: Boolean) {
        readingTimeTrackingCheck?.isEnabled = !busy
        openReadingStatsButton?.isEnabled = !busy
    }

    private fun setupControls() {
        statsPeriodTodayButton?.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_TODAY) }
        statsPeriodWeekButton?.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_WEEK) }
        statsPeriodMonthButton?.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_MONTH) }
        statsPeriodYearButton?.setOnClickListener { selectPeriod(ReadingStatsUtils.PERIOD_YEAR) }
        openReadingStatsButton?.setOnClickListener { source ->
            val intent = Intent(activity, ReadingStatsActivity::class.java)
            if (TransitionMotionModeHelper.isFluidMode(settingsStore)) LaunchSourceTransition.attach(intent, source)
            activity.startActivity(intent)
        }
        readingTimeTrackingCheck?.setOnCheckedChangeListener { _, isChecked -> handleTrackingToggle(isChecked) }
    }

    private fun selectPeriod(periodKey: String) {
        selectedPeriod = ReadingStatsUtils.normalizePeriodKey(periodKey)
        updatePeriodButtons()
        refreshSummary(false)
    }

    private fun updatePeriodButtons() {
        AppUiUtils.styleToggleButton(activity, statsPeriodTodayButton, selectedPeriod == ReadingStatsUtils.PERIOD_TODAY)
        AppUiUtils.styleToggleButton(activity, statsPeriodWeekButton, selectedPeriod == ReadingStatsUtils.PERIOD_WEEK)
        AppUiUtils.styleToggleButton(activity, statsPeriodMonthButton, selectedPeriod == ReadingStatsUtils.PERIOD_MONTH)
        AppUiUtils.styleToggleButton(activity, statsPeriodYearButton, selectedPeriod == ReadingStatsUtils.PERIOD_YEAR)
    }

    private fun updateVisibility() {
        readingStatsContentLayout?.visibility = if (isTrackingChecked()) View.VISIBLE else View.GONE
    }

    private fun handleTrackingToggle(enabled: Boolean) {
        if (bindingValues || callback.isSettingsBusy()) return
        if (enabled) {
            callback.saveSettings()
            refreshSummary(true)
            return
        }
        callback.setBusy(true)
        executor.execute {
            val hasStats = databaseHelper.hasAnyReadingStats()
            activity.runOnUiThread {
                callback.setBusy(false)
                if (!hasStats) {
                    callback.saveSettings()
                    updateVisibility()
                } else {
                    showDisableDialog()
                }
            }
        }
    }

    private fun showDisableDialog() {
        AlertDialog.Builder(activity)
            .setTitle("关闭阅读时长记录")
            .setMessage("已有阅读统计数据。你可以只隐藏历史，或同时清空本地与云端统计。")
            .setNegativeButton("取消") { _, _ -> revertTrackingToggle(true) }
            .setNeutralButton("只隐藏") { _, _ ->
                callback.saveSettings()
                updateVisibility()
            }
            .setPositiveButton("清空历史") { _, _ -> clearHistory() }
            .show()
    }

    private fun clearHistory() {
        callback.setBusy(true)
        callback.setStatusText("正在清理阅读统计...")
        executor.execute {
            try {
                readingStatsSyncManager.clearRemoteReadingStats()
                databaseHelper.clearReadingStats()
                activity.runOnUiThread {
                    callback.setBusy(false)
                    callback.saveSettings()
                    updateVisibility()
                    refreshSummary(false)
                    callback.setStatusText("阅读统计已清空")
                    callback.showToast("已清空阅读统计")
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    callback.setBusy(false)
                    revertTrackingToggle(true)
                    callback.setStatusText("清理失败: ${error.message}")
                    callback.showToast("清理阅读统计失败")
                }
            }
        }
    }

    private fun revertTrackingToggle(checked: Boolean) {
        bindingValues = true
        readingTimeTrackingCheck?.isChecked = checked
        bindingValues = false
        updateVisibility()
    }

    private fun isTrackingChecked(): Boolean = readingTimeTrackingCheck?.isChecked == true
}
