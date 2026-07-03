package com.metahumanz.pacilread.stats.annual

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.metahumanz.pacilread.AppUiUtils
import com.metahumanz.pacilread.R
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import com.metahumanz.pacilread.storage.SettingsStore
import com.metahumanz.pacilread.theme.ThemeModeHelper
import com.metahumanz.pacilread.ui.PredictiveDialogDismissController
import com.metahumanz.pacilread.ui.TransitionMotionModeHelper
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashMap
import java.util.Locale

class AnnualReportExportController(private val activity: Activity) {
    private val renderer = AnnualReportRenderer()
    private val settingsStore = SettingsStore(activity)

    private var pendingReport: AnnualReportData? = null
    private var selectedStyle = AnnualReportStyle.QUIET
    private var selectedTheme = AnnualReportTheme.LIGHT
    private val selectedMetrics = ArrayList<AnnualReportMetric>()
    private val metricButtons = LinkedHashMap<AnnualReportMetric, Button>()
    private var pendingBitmap: Bitmap? = null
    private var previewImage: ImageView? = null
    private var quietButton: Button? = null
    private var highlightButton: Button? = null
    private var lightButton: Button? = null
    private var darkButton: Button? = null
    private var metricGrid: LinearLayout? = null

    fun showPreview(report: AnnualReportData?) {
        if (report == null || !report.hasReadingData()) {
            AppUiUtils.showToast(activity, "暂无可生成的阅读报告")
            return
        }
        pendingReport = report
        selectedStyle = AnnualReportStyle.QUIET
        selectedTheme = if (ThemeModeHelper.MODE_DARK == ThemeModeHelper.getResolvedAppBucket(activity)) {
            AnnualReportTheme.DARK
        } else {
            AnnualReportTheme.LIGHT
        }
        selectedMetrics.clear()
        selectedMetrics.addAll(
            AnnualReportMetric.selectionFromKeys(
                report,
                AnnualReportMetric.parseKeys(settingsStore.getAnnualReportMetricSelection(report.isBookScope())),
            ),
        )
        saveSelectedMetrics()
        pendingBitmap = renderer.render(report, selectedStyle, selectedTheme, selectedMetrics)

        val scrollView = ScrollView(activity).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val pad = AppUiUtils.dp(activity, 18)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_app_dialog)
            setPadding(pad, pad, pad, pad)
        }
        scrollView.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        content.addView(title(previewTitle()))

        val styleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val styleRowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, AppUiUtils.dp(activity, 18), 0, 0) }
        quietButton = styleButton(AnnualReportStyle.QUIET)
        highlightButton = styleButton(AnnualReportStyle.HIGHLIGHT)
        styleRow.addView(quietButton, weightedButtonParams(0))
        styleRow.addView(highlightButton, weightedButtonParams(AppUiUtils.dp(activity, 10)))
        content.addView(styleRow, styleRowParams)

        val themeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val themeRowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, AppUiUtils.dp(activity, 10), 0, 0) }
        lightButton = themeButton(AnnualReportTheme.LIGHT)
        darkButton = themeButton(AnnualReportTheme.DARK)
        themeRow.addView(lightButton, weightedButtonParams(0))
        themeRow.addView(darkButton, weightedButtonParams(AppUiUtils.dp(activity, 10)))
        content.addView(themeRow, themeRowParams)
        syncOptionButtons()

        val metricTitleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, AppUiUtils.dp(activity, 16), 0, 0) }
        content.addView(sectionTitle(summaryTitle()), metricTitleParams)

        metricGrid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val metricGridParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, AppUiUtils.dp(activity, 8), 0, 0) }
        content.addView(metricGrid, metricGridParams)
        rebuildMetricButtons()
        syncMetricButtons()

        val previewFrame = FrameLayout(activity).apply {
            val inset = AppUiUtils.dp(activity, 10)
            setPadding(inset, inset, inset, inset)
            setBackgroundResource(R.drawable.bg_app_card)
        }
        val previewParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            AppUiUtils.dp(activity, 480),
        ).apply { setMargins(0, AppUiUtils.dp(activity, 16), 0, 0) }
        previewImage = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(pendingBitmap)
        }
        previewFrame.addView(previewImage, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        ))
        content.addView(previewFrame, previewParams)

        val actionRow = LinearLayout(activity).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
        }
        val actionRowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, AppUiUtils.dp(activity, 16), 0, 0) }
        val saveButton = actionButton("保存到本地", false)
        val shareButton = actionButton("分享", true)
        actionRow.addView(saveButton, actionButtonParams(0))
        actionRow.addView(shareButton, actionButtonParams(AppUiUtils.dp(activity, 10)))
        content.addView(actionRow, actionRowParams)

        val closeButton = actionButton("关闭", false)
        val closeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, AppUiUtils.dp(activity, 12), 0, 0) }
        content.addView(closeButton, closeParams)

        val dialog = AlertDialog.Builder(activity).setView(scrollView).create()
        saveButton.setOnClickListener { saveCurrent() }
        shareButton.setOnClickListener { shareCurrent() }
        closeButton.setOnClickListener { dialog.dismiss() }
        applyDialogMotion(dialog)
        dialog.show()
        applyDialogMotion(dialog)
        val window = dialog.window
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val backRegistration = PredictiveDialogDismissController.install(
            dialog,
            window,
            TransitionMotionModeHelper.isFluidMode(settingsStore),
            null,
        )
        dialog.setOnDismissListener { backRegistration.unregister() }
    }

    private fun applyDialogMotion(dialog: AlertDialog?) {
        dialog?.window?.setWindowAnimations(R.style.AppPopDialogAnimation)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_SAVE_ANNUAL_REPORT) return false
        val uri = data?.data
        if (resultCode == Activity.RESULT_OK && uri != null) {
            try {
                writeBitmap(uri)
                AppUiUtils.showToast(activity, "${reportKind()}已保存")
            } catch (error: Exception) {
                AppUiUtils.showToast(activity, "保存失败: ${error.message}")
            }
        }
        return true
    }

    private fun styleButton(style: AnnualReportStyle): Button = Button(activity).apply {
        text = style.label
        isAllCaps = false
        styleOptionButton(this)
        setOnClickListener {
            selectedStyle = style
            refreshPreviewBitmap()
            syncOptionButtons()
        }
    }

    private fun themeButton(theme: AnnualReportTheme): Button = Button(activity).apply {
        text = theme.label
        isAllCaps = false
        styleOptionButton(this)
        setOnClickListener {
            selectedTheme = theme
            refreshPreviewBitmap()
            syncOptionButtons()
        }
    }

    private fun metricButton(metric: AnnualReportMetric): Button = Button(activity).apply {
        text = "${metric.label(pendingReport)}\n${metric.value(pendingReport)}"
        isAllCaps = false
        gravity = Gravity.CENTER
        isSingleLine = false
        includeFontPadding = true
        textSize = 13f
        minHeight = AppUiUtils.dp(activity, 62)
        minimumHeight = AppUiUtils.dp(activity, 62)
        setPadding(
            AppUiUtils.dp(activity, 8),
            AppUiUtils.dp(activity, 7),
            AppUiUtils.dp(activity, 8),
            AppUiUtils.dp(activity, 7),
        )
        setOnClickListener { selectMetric(metric) }
    }

    private fun actionButton(text: String, primary: Boolean): Button = Button(activity).apply {
        this.text = text
        isAllCaps = false
        minHeight = AppUiUtils.dp(activity, 52)
        minimumHeight = AppUiUtils.dp(activity, 52)
        textSize = 15f
        gravity = Gravity.CENTER
        isSingleLine = false
        includeFontPadding = true
        setPadding(
            AppUiUtils.dp(activity, 12),
            AppUiUtils.dp(activity, 8),
            AppUiUtils.dp(activity, 12),
            AppUiUtils.dp(activity, 8),
        )
        setBackgroundResource(if (primary) R.drawable.bg_app_primary_button else R.drawable.bg_app_outline_button)
        setTextColor(ThemeModeHelper.resolveColor(
            activity,
            if (primary) R.color.app_button_primary_text else R.color.app_button_outline_text,
        ))
    }

    private fun styleOptionButton(button: Button) {
        button.minHeight = AppUiUtils.dp(activity, 54)
        button.minimumHeight = AppUiUtils.dp(activity, 54)
        button.textSize = 16f
        button.gravity = Gravity.CENTER
        button.isSingleLine = false
        button.includeFontPadding = true
        button.setPadding(
            AppUiUtils.dp(activity, 10),
            AppUiUtils.dp(activity, 8),
            AppUiUtils.dp(activity, 10),
            AppUiUtils.dp(activity, 8),
        )
    }

    private fun title(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary))
        textSize = 24f
        setTypeface(null, Typeface.BOLD)
        includeFontPadding = true
    }

    private fun sectionTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(ThemeModeHelper.resolveColor(activity, R.color.app_text_primary))
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        includeFontPadding = true
    }

    private fun weightedButtonParams(marginStart: Int) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f,
    ).apply { setMargins(marginStart, 0, 0, 0) }

    private fun actionButtonParams(marginStart: Int) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f,
    ).apply { setMargins(marginStart, 0, 0, 0) }

    private fun syncOptionButtons() {
        AppUiUtils.styleToggleButton(activity, quietButton, selectedStyle == AnnualReportStyle.QUIET)
        AppUiUtils.styleToggleButton(activity, highlightButton, selectedStyle == AnnualReportStyle.HIGHLIGHT)
        AppUiUtils.styleToggleButton(activity, lightButton, selectedTheme == AnnualReportTheme.LIGHT)
        AppUiUtils.styleToggleButton(activity, darkButton, selectedTheme == AnnualReportTheme.DARK)
    }

    private fun rebuildMetricButtons() {
        val grid = metricGrid ?: return
        metricButtons.clear()
        grid.removeAllViews()
        val metrics = AnnualReportMetric.availableFor(pendingReport)
        var row: LinearLayout? = null
        for (i in metrics.indices) {
            if (i % 2 == 0) {
                row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                val rowParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                if (i > 0) rowParams.setMargins(0, AppUiUtils.dp(activity, 8), 0, 0)
                grid.addView(row, rowParams)
            }
            val metric = metrics[i]
            val button = metricButton(metric)
            metricButtons[metric] = button
            row!!.addView(button, weightedButtonParams(if (i % 2 == 0) 0 else AppUiUtils.dp(activity, 8)))
        }
    }

    private fun selectMetric(metric: AnnualReportMetric?) {
        if (metric == null || selectedMetrics.contains(metric)) return
        if (selectedMetrics.size >= 3) selectedMetrics.removeAt(0)
        selectedMetrics.add(metric)
        val normalized = AnnualReportMetric.sanitizeMetrics(pendingReport, selectedMetrics)
        selectedMetrics.clear()
        selectedMetrics.addAll(normalized)
        saveSelectedMetrics()
        syncMetricButtons()
        refreshPreviewBitmap()
    }

    private fun syncMetricButtons() {
        for ((metric, button) in metricButtons) {
            AppUiUtils.styleToggleButton(activity, button, selectedMetrics.contains(metric))
        }
    }

    private fun saveSelectedMetrics() {
        val report = pendingReport ?: return
        settingsStore.setAnnualReportMetricSelection(report.isBookScope(), AnnualReportMetric.serialize(selectedMetrics))
    }

    private fun refreshPreviewBitmap() {
        pendingBitmap = renderer.render(pendingReport, selectedStyle, selectedTheme, selectedMetrics)
        previewImage?.setImageBitmap(pendingBitmap)
    }

    @Suppress("DEPRECATION")
    private fun saveCurrent() {
        if (!ensureBitmap()) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, fileName())
        }
        activity.startActivityForResult(intent, REQUEST_SAVE_ANNUAL_REPORT)
    }

    private fun shareCurrent() {
        if (!ensureBitmap()) return
        try {
            val dir = File(activity.cacheDir, "reports")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName())
            FileOutputStream(file).use { output ->
                pendingBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "分享${reportKind()}"))
        } catch (error: Exception) {
            AppUiUtils.showToast(activity, "分享失败: ${error.message}")
        }
    }

    private fun ensureBitmap(): Boolean {
        val report = pendingReport
        if (report == null || !report.hasReadingData()) {
            AppUiUtils.showToast(activity, "暂无可生成的阅读报告")
            return false
        }
        if (pendingBitmap == null) pendingBitmap = renderer.render(report, selectedStyle, selectedTheme, selectedMetrics)
        return true
    }

    private fun writeBitmap(uri: Uri) {
        if (!ensureBitmap()) return
        activity.contentResolver.openOutputStream(uri).use { output ->
            if (output == null) throw IllegalStateException("无法打开保存位置")
            pendingBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun fileName(): String {
        val report = pendingReport
        val year = report?.year ?: 0
        val scope = if (report?.isBookScope() == true) "book" else "global"
        val period = report?.let(::periodSlug) ?: "report"
        return String.format(
            Locale.ROOT,
            "pacilread-%s-report-%d-%s-%s-%s.png",
            period,
            year,
            scope,
            selectedStyle.slug,
            selectedTheme.slug,
        )
    }

    private fun previewTitle(): String {
        val title = pendingReport?.reportTitle
        return if (!title.isNullOrBlank()) "${title.trim()}预览" else "${reportKind()}预览"
    }

    private fun summaryTitle(): String = when {
        pendingReport == null -> "阅读摘要"
        pendingReport!!.isDayReport() -> "每日摘要"
        pendingReport!!.isWeekReport() -> "周报摘要"
        pendingReport!!.isMonthReport() -> "月报摘要"
        else -> "年度摘要"
    }

    private fun reportKind(): String = pendingReport?.reportKindLabel() ?: "阅读报告"

    private fun periodSlug(report: AnnualReportData): String = when {
        report.isDayReport() -> "daily"
        report.isWeekReport() -> if (ReadingStatsUtils.WEEK_MODE_ROLLING == report.weekMode) {
            "weekly-rolling"
        } else {
            "weekly-natural"
        }
        report.isMonthReport() -> if (ReadingStatsUtils.MONTH_MODE_LAST_30_DAYS == report.monthMode) {
            "monthly-last30"
        } else {
            "monthly-natural"
        }
        report.isLast365DaysReport() -> "annual-last365"
        else -> "annual-natural"
    }

    companion object {
        const val REQUEST_SAVE_ANNUAL_REPORT = 1204
    }
}
