package com.metahumanz.pacilread.stats.annual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.metahumanz.pacilread.stats.ReadingStatsUtils
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AnnualReportRenderer {
    fun render(report: AnnualReportData?, style: AnnualReportStyle?): Bitmap = render(report, style, AnnualReportTheme.LIGHT)

    fun render(report: AnnualReportData?, style: AnnualReportStyle?, theme: AnnualReportTheme?): Bitmap =
        render(report, style, theme, null)

    fun render(
        report: AnnualReportData?,
        style: AnnualReportStyle?,
        theme: AnnualReportTheme?,
        summaryMetrics: List<AnnualReportMetric?>?,
    ): Bitmap {
        val safeReport = report!!
        val bitmap = Bitmap.createBitmap(REPORT_WIDTH, REPORT_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val safeStyle = style ?: AnnualReportStyle.QUIET
        val safeTheme = theme ?: AnnualReportTheme.LIGHT
        val safeMetrics = AnnualReportMetric.sanitizeMetrics(safeReport, summaryMetrics)
        if (safeStyle == AnnualReportStyle.HIGHLIGHT) renderHighlight(canvas, safeReport, Palette.highlight(safeTheme), safeMetrics)
        else renderQuiet(canvas, safeReport, Palette.quiet(safeTheme), safeMetrics)
        return bitmap
    }

    private fun renderQuiet(canvas: Canvas, report: AnnualReportData, palette: Palette, summaryMetrics: List<AnnualReportMetric>) {
        drawBackground(canvas, palette)
        val brandPaint = textPaint(palette.mutedText, 28f, true)
        val titlePaint = textPaint(palette.primaryText, 64f, true)
        val subtitlePaint = textPaint(palette.secondaryText, 32f, false)
        val giantPaint = textPaint(palette.primaryText, 86f, true)
        val bodyPaint = textPaint(palette.secondaryText, 28f, false)
        val cardTitlePaint = textPaint(palette.primaryText, 36f, true)
        drawMultiline(canvas, "PacilRead Mobile", 72f, 76f, 500f, brandPaint, 1)
        drawMultiline(canvas, titleFor(report), 72f, 150f, 860f, titlePaint, 2)
        drawMultiline(canvas, AnnualReportInsight.sentence(report), 74f, 302f, 820f, subtitlePaint, 2)
        drawMultiline(canvas, ReadingStatsUtils.formatDuration(report.totalSeconds), 72f, 408f, 660f, giantPaint, 2)
        drawMultiline(canvas, "阅读总时长", 76f, 520f, 420f, bodyPaint, 1)
        drawMultiline(canvas, "累计 ${formatNumber(report.totalChars)} 字 · ${report.readingDays} 个阅读日", 76f, 570f, 820f, bodyPaint, 2)
        val summary = RectF(72f, 678f, 1008f, 966f)
        drawRoundRect(canvas, summary, 34f, palette.card, palette.line, 2f)
        drawMultiline(canvas, summaryTitleFor(report), 112f, 728f, 360f, cardTitlePaint, 1)
        drawMetric(canvas, 112f, 796f, 258f, summaryMetrics[0].label(report), summaryMetrics[0].value(report), palette, palette.accent)
        drawMetric(canvas, 394f, 796f, 258f, summaryMetrics[1].label(report), summaryMetrics[1].value(report), palette, palette.accent2)
        drawMetric(canvas, 676f, 796f, 258f, summaryMetrics[2].label(report), summaryMetrics[2].value(report), palette, palette.accent3)
        val dailyReport = report.isDayReport()
        val rows = infoRows(report)
        val rowHeight = if (dailyReport) 72f else 84f
        val infoHeaderHeight = if (dailyReport) 108f else 118f
        val infoTop = summary.bottom + if (dailyReport) 48f else 66f
        val infoHeight = infoHeaderHeight + max(1, rows.size) * rowHeight
        val info = RectF(72f, infoTop, 1008f, infoTop + infoHeight)
        drawRoundRect(canvas, info, 34f, palette.card, palette.line, 2f)
        drawMultiline(canvas, infoTitleFor(report, false), 112f, infoTop + 46f, 460f, cardTitlePaint, 1)
        drawInfoRows(canvas, rows, 112f, infoTop + infoHeaderHeight, 820f, rowHeight, palette)
        val rhythmTop = info.bottom + if (dailyReport) 46f else 66f
        val rhythm = RectF(72f, rhythmTop, 1008f, rhythmTop + if (dailyReport) 348f else 286f)
        drawRoundRect(canvas, rhythm, 34f, palette.card, palette.line, 2f)
        if (dailyReport) drawDailyReportVisual(canvas, report, palette, rhythm, false)
        else {
            drawMultiline(canvas, rhythmTitleFor(report, false), 112f, rhythmTop + 38f, 420f, cardTitlePaint, 1)
            drawRhythmBars(canvas, report, palette, 112f, rhythmTop + 114f, 856f, 104f, false)
        }
        val footerTop = min(max(rhythm.bottom + 26f, 1814f), 1864f)
        drawMultiline(canvas, "PacilRead Mobile", 72f, footerTop, 360f, textPaint(palette.mutedText, 26f, false), 1)
    }

    private fun renderHighlight(canvas: Canvas, report: AnnualReportData, palette: Palette, summaryMetrics: List<AnnualReportMetric>) {
        drawBackground(canvas, palette)
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentSoft }
        canvas.save()
        canvas.rotate(-8f, 840f, 160f)
        canvas.drawRoundRect(RectF(650f, 48f, 1200f, 250f), 42f, 42f, shapePaint)
        canvas.restore()
        shapePaint.color = palette.accent2Soft
        canvas.drawRoundRect(RectF(-110f, 610f, 340f, 806f), 44f, 44f, shapePaint)
        val brandPaint = textPaint(palette.mutedText, 28f, true)
        val titlePaint = textPaint(palette.primaryText, 58f, true)
        val giantPaint = textPaint(palette.primaryText, 104f, true)
        val bodyPaint = textPaint(palette.secondaryText, 32f, false)
        val cardTitlePaint = textPaint(palette.primaryText, 42f, true)
        drawPill(canvas, 72f, 76f, 354f, 130f, palette.accent, Color.TRANSPARENT, "PacilRead Mobile", palette.inverseText)
        drawMultiline(canvas, titleFor(report), 72f, 188f, 820f, titlePaint, 2)
        drawMultiline(canvas, formatHoursCompact(report.totalSeconds), 72f, 356f, 520f, giantPaint, 1)
        drawMultiline(canvas, "小时阅读", 78f, 474f, 420f, titlePaint, 1)
        drawMultiline(canvas, AnnualReportInsight.sentence(report), 78f, 548f, 820f, bodyPaint, 2)
        val summary = RectF(72f, 694f, 1008f, 936f)
        drawRoundRect(canvas, summary, 34f, palette.card, palette.line, 2f)
        drawHighlightStat(canvas, 118f, 742f, summaryMetrics[0].label(report), summaryMetrics[0].value(report), palette, palette.accent)
        drawHighlightStat(canvas, 396f, 742f, summaryMetrics[1].label(report), summaryMetrics[1].value(report), palette, palette.accent2)
        drawHighlightStat(canvas, 674f, 742f, summaryMetrics[2].label(report), summaryMetrics[2].value(report), palette, palette.accent3)
        val dailyReport = report.isDayReport()
        val rows = infoRows(report)
        val rowHeight = if (dailyReport) 68f else 76f
        val infoHeaderHeight = if (dailyReport) 110f else 126f
        val infoTop = summary.bottom + if (dailyReport) 50f else 66f
        val infoHeight = infoHeaderHeight + max(1, rows.size) * rowHeight
        drawRoundRect(canvas, RectF(72f, infoTop, 1008f, infoTop + infoHeight), 34f, palette.card, palette.line, 2f)
        drawMultiline(canvas, infoTitleFor(report, true), 116f, infoTop + 48f, 520f, cardTitlePaint, 1)
        drawInfoRows(canvas, rows, 116f, infoTop + infoHeaderHeight, 816f, rowHeight, palette)
        val rhythmTop = infoTop + infoHeight + if (dailyReport) 48f else 58f
        val rhythm = RectF(72f, rhythmTop, 1008f, rhythmTop + if (dailyReport) 368f else 310f)
        drawRoundRect(canvas, rhythm, 34f, palette.card, palette.line, 2f)
        if (dailyReport) drawDailyReportVisual(canvas, report, palette, rhythm, true)
        else {
            drawMultiline(canvas, rhythmTitleFor(report, true), 116f, rhythmTop + 44f, 560f, cardTitlePaint, 1)
            drawRhythmBars(canvas, report, palette, 116f, rhythmTop + 128f, 846f, 116f, true)
        }
        val footerTop = min(max(rhythm.bottom + 28f, 1814f), 1864f)
        drawMultiline(canvas, "PacilRead Mobile", 72f, footerTop, 360f, brandPaint, 1)
    }

    private fun infoRows(report: AnnualReportData): MutableList<Array<String>> {
        val rows = ArrayList<Array<String>>()
        if (report.isBookScope()) {
            addRow(rows, "书籍", report.bookTitle)
            addRow(rows, "作者", report.bookAuthor)
            addRow(rows, "标签", report.topTag)
            addRow(rows, "系列", report.topSeries)
            addRow(rows, "状态", report.statusText)
        } else {
            addTopBookRows(rows, report)
            addRow(rows, "阅读地图", readingMapText(report))
        }
        if (rows.isEmpty()) addRow(rows, "范围", report.rangeTitle)
        while (rows.size > 4) rows.removeAt(rows.size - 1)
        return rows
    }

    private fun addTopBookRows(rows: MutableList<Array<String>>, report: AnnualReportData?) {
        if (report == null || report.topBooks.isEmpty()) {
            addRow(rows, "Top 书籍", report?.topBook)
            return
        }
        for (i in 0 until min(3, report.topBooks.size)) addRow(rows, "Top ${i + 1}", bookStatText(report.topBooks[i]))
    }

    private fun addRow(rows: MutableList<Array<String>>, label: String, value: String?) {
        if (!value.isNullOrBlank()) rows.add(arrayOf(label, value.trim()))
    }

    private fun bookStatText(stat: AnnualReportData.BookStat?): String {
        if (stat == null) return ""
        return buildString {
            append(stat.title.ifBlank { "未命名书籍" })
            if (stat.author.isNotBlank()) append(" · ").append(stat.author)
            if (stat.totalSeconds > 0) append(" · ").append(ReadingStatsUtils.formatDuration(stat.totalSeconds))
            if (stat.totalChars > 0) append(" · ").append(formatNumber(stat.totalChars)).append(" 字")
        }
    }

    private fun readingMapText(report: AnnualReportData?): String {
        if (report == null) return ""
        val tags = joinNamedStats(report.topTags, 2)
        if (tags.isNotEmpty()) return "标签：$tags"
        val authors = joinNamedStats(report.topAuthors, 2)
        if (authors.isNotEmpty()) return "作者：$authors"
        val series = joinNamedStats(report.topSeriesStats, 2)
        if (series.isNotEmpty()) return "系列：$series"
        return if (report.readingBooks > 0) "覆盖 ${report.readingBooks} 本书" else ""
    }

    private fun joinNamedStats(stats: List<AnnualReportData.NamedStat?>?, limit: Int): String {
        if (stats.isNullOrEmpty() || limit <= 0) return ""
        val builder = StringBuilder()
        var count = 0
        for (stat in stats) {
            if (stat == null || stat.name.isBlank()) continue
            if (builder.isNotEmpty()) builder.append(" / ")
            builder.append(stat.name)
            if (++count >= limit) break
        }
        return builder.toString()
    }

    private fun drawInfoRows(canvas: Canvas, rows: List<Array<String>>, left: Float, top: Float, width: Float, rowHeight: Float, palette: Palette) {
        val labelPaint = textPaint(palette.mutedText, 24f, false)
        val valuePaint = textPaint(palette.primaryText, 31f, true)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.line; strokeWidth = 2f }
        for (i in rows.indices) {
            val rowTop = top + i * rowHeight
            drawMultiline(canvas, rows[i][0], left, rowTop + 6f, 170f, labelPaint, 1)
            drawMultiline(canvas, rows[i][1], left + 190f, rowTop, width - 190f, valuePaint, 2)
            if (i < rows.size - 1) canvas.drawLine(left, rowTop + rowHeight - 14f, left + width, rowTop + rowHeight - 14f, linePaint)
        }
    }

    private fun drawMetric(canvas: Canvas, left: Float, top: Float, width: Float, label: String?, value: String?, palette: Palette, accentColor: Int) {
        drawRoundRect(canvas, RectF(left, top, left + width, top + 122f), 24f, palette.cardAlt, palette.line, 1.4f)
        canvas.drawCircle(left + 26f, top + 30f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor })
        drawMultiline(canvas, label, left + 44f, top + 18f, width - 58f, textPaint(palette.mutedText, 22f, false), 1)
        drawMultiline(canvas, value, left + 24f, top + 58f, width - 48f, textPaint(palette.primaryText, if ((value?.length ?: 0) > 8) 28f else 34f, true), 2)
    }

    private fun drawHighlightStat(canvas: Canvas, left: Float, top: Float, label: String?, value: String?, palette: Palette, accentColor: Int) {
        canvas.drawCircle(left + 20f, top + 20f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor })
        drawMultiline(canvas, label, left, top + 54f, 220f, textPaint(palette.mutedText, 23f, false), 1)
        drawMultiline(canvas, value, left, top + 92f, 220f, textPaint(palette.primaryText, if ((value?.length ?: 0) > 8) 30f else 38f, true), 2)
    }

    private fun drawRhythmBars(canvas: Canvas, report: AnnualReportData, palette: Palette, left: Float, top: Float, width: Float, height: Float, bold: Boolean) {
        val values = rhythmValues(report)
        val labels = rhythmLabels(report, values.size)
        val count = max(1, values.size)
        val maximum = maxRhythmSeconds(values)
        val gap = if (count <= 1) 0f else if (bold) 10f else 12f
        val barWidth = if (count <= 1) min(160f, width * 0.36f) else (width - gap * (count - 1f)) / count
        val firstX = if (count <= 1) left + (width - barWidth) / 2f else left
        val monthPaint = textPaint(palette.mutedText, 20f, bold)
        val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.line; strokeWidth = 2f }
        canvas.drawLine(left, top + height + 1f, left + width, top + height + 1f, baselinePaint)
        for (i in 0 until count) {
            val seconds = max(values[i], 0)
            val ratio = if (seconds <= 0) 0.04f else seconds.toFloat() / maximum
            val barHeight = max(if (seconds <= 0) 7f else 12f, height * ratio)
            val x = firstX + i * (barWidth + gap)
            val color = when (i % 3) { 0 -> palette.accent; 1 -> palette.accent2; else -> palette.accent3 }
            drawRoundRect(canvas, RectF(x, top + height - barHeight, x + barWidth, top + height), if (bold) 8f else 9f, color, Color.TRANSPARENT, 0f)
            drawMultiline(canvas, labels[i], x - 8f, top + height + 24f, barWidth + 16f, monthPaint, 1)
        }
    }

    private fun drawDailyReportVisual(canvas: Canvas, report: AnnualReportData, palette: Palette, panel: RectF, bold: Boolean) {
        val left = panel.left + if (bold) 44f else 40f
        val width = panel.width() - if (bold) 88f else 80f
        val headerTop = panel.top + if (bold) 28f else 26f
        drawMultiline(canvas, "TODAY MIX", left, headerTop, 280f, textPaint(palette.mutedText, if (bold) 21f else 19f, true), 1)
        drawMultiline(canvas, "今日阅读构成", left, headerTop + if (bold) 30f else 28f, 430f, textPaint(palette.primaryText, if (bold) 38f else 34f, true), 1)
        drawMultiline(canvas, "${formatCharsCompact(report.totalChars)} 字", left + width - 230f, headerTop + if (bold) 34f else 31f, 230f, textPaint(palette.accent, if (bold) 25f else 23f, true), 1)
        val contextTop = panel.bottom - if (bold) 100f else 96f
        val bookTop = panel.top + if (bold) 108f else 100f
        drawDailyBookBars(canvas, report, palette, left, bookTop, width, contextTop - bookTop - 14f, bold)
        drawDailyContextBars(canvas, report, palette, left, contextTop, width, bold)
    }

    private fun drawDailyBookBars(canvas: Canvas, report: AnnualReportData?, palette: Palette, left: Float, top: Float, width: Float, height: Float, bold: Boolean) {
        val rowCount = if (report == null) 0 else min(3, report.topBooks.size)
        if (rowCount <= 0) {
            drawMultiline(canvas, "暂无今日书籍记录", left, top + 8f, width, textPaint(palette.mutedText, if (bold) 25f else 23f, false), 1)
            return
        }
        val stride = max(44f, height / rowCount)
        val rowHeight = max(42f, min(if (bold) 52f else 50f, stride - 2f))
        for (i in 0 until rowCount) drawDailyBookRow(canvas, report!!, report.topBooks[i], palette, left, top + i * stride, width, rowHeight, i, bold)
    }

    private fun drawDailyBookRow(canvas: Canvas, report: AnnualReportData?, book: AnnualReportData.BookStat?, palette: Palette, left: Float, top: Float, width: Float, height: Float, index: Int, bold: Boolean) {
        val totalSeconds = max(1, report?.totalSeconds ?: 0)
        val bookSeconds = max(book?.totalSeconds ?: 0, 0)
        val percent = if (bookSeconds <= 0) 0 else max(1, (bookSeconds * 100f / totalSeconds).roundToInt())
        val ratio = if (bookSeconds <= 0) 0f else min(1f, bookSeconds.toFloat() / totalSeconds)
        val amountWidth = if (bold) 104f else 96f
        val copyWidth = width - amountWidth - 20f
        val title = book?.title?.ifBlank { "未命名书籍" } ?: "未命名书籍"
        drawMultiline(canvas, title, left, top, copyWidth, textPaint(palette.primaryText, if (bold) 22f else 20f, true), 1)
        drawMultiline(canvas, dailyBookMeta(book), left, top + if (bold) 25f else 23f, copyWidth, textPaint(palette.mutedText, if (bold) 16f else 15f, false), 1)
        drawMultiline(canvas, "$percent%", left + width - amountWidth, top + 3f, amountWidth, textPaint(palette.accent, if (bold) 27f else 25f, true), 1)
        val track = RectF(left, top + height - 8f, left + width, top + height - 1f)
        drawRoundRect(canvas, track, 4f, palette.cardAlt, palette.line, 1f)
        if (ratio > 0f) {
            val fillColor = when (index % 3) { 0 -> palette.accent; 1 -> palette.accent2; else -> palette.accent3 }
            drawRoundRect(canvas, RectF(track.left, track.top, track.left + max(10f, track.width() * ratio), track.bottom), 4f, fillColor, Color.TRANSPARENT, 0f)
        }
    }

    private fun drawDailyContextBars(canvas: Canvas, report: AnnualReportData, palette: Palette, left: Float, top: Float, width: Float, bold: Boolean) {
        val values = dailyContextValues(report)
        val labels = dailyContextLabels(report, values.size)
        val count = max(1, values.size)
        val currentIndex = dailyContextCurrentIndex(report, count)
        val maximum = maxRhythmSeconds(values)
        drawMultiline(canvas, "最近 7 天", left, top, 220f, textPaint(palette.mutedText, if (bold) 19f else 18f, true), 1)
        drawMultiline(canvas, "今日高亮", left + width - 140f, top, 140f, textPaint(palette.accent2, if (bold) 19f else 18f, true), 1)
        val barTop = top + if (bold) 34f else 32f
        val barMaxHeight = if (bold) 42f else 40f
        val gap = if (count <= 1) 0f else if (bold) 11f else 10f
        val barWidth = if (count <= 1) min(120f, width * 0.2f) else (width - gap * (count - 1f)) / count
        val firstX = if (count <= 1) left + (width - barWidth) / 2f else left
        val labelPaint = textPaint(palette.mutedText, if (bold) 16f else 15f, false)
        for (i in 0 until count) {
            val seconds = max(values[i], 0)
            val ratio = if (seconds <= 0) 0.05f else seconds.toFloat() / maximum
            val barHeight = max(if (seconds <= 0) 5f else 8f, barMaxHeight * ratio)
            val x = firstX + i * (barWidth + gap)
            val y = barTop + barMaxHeight - barHeight
            val current = i == currentIndex
            val fillColor = if (current) palette.accent2 else if (seconds <= 0) withAlpha(palette.mutedText, 72) else palette.accent3
            drawRoundRect(canvas, RectF(x, y, x + barWidth, barTop + barMaxHeight), if (current) 6f else 5f, fillColor, Color.TRANSPARENT, 0f)
            if (current) {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = palette.line }
                canvas.drawRoundRect(RectF(x - 3f, y - 3f, x + barWidth + 3f, barTop + barMaxHeight + 3f), 8f, 8f, stroke)
            }
            drawMultiline(canvas, labels[i], x - 6f, barTop + barMaxHeight + 8f, barWidth + 12f, labelPaint, 1)
        }
    }

    private fun drawBackground(canvas: Canvas, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, REPORT_WIDTH.toFloat(), REPORT_HEIGHT.toFloat(), palette.backgroundTop, palette.backgroundBottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, REPORT_WIDTH.toFloat(), REPORT_HEIGHT.toFloat(), paint)
    }

    private fun drawPill(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, fillColor: Int, strokeColor: Int, text: String, textColor: Int) {
        drawRoundRect(canvas, RectF(left, top, right, bottom), (bottom - top) / 2f, fillColor, strokeColor, 1.3f)
        drawMultiline(canvas, text, left + 22f, top + 14f, max(1f, right - left - 44f), textPaint(textColor, 24f, true), 1)
    }

    private fun drawRoundRect(canvas: Canvas, rect: RectF, radius: Float, fillColor: Int, strokeColor: Int, strokeWidth: Float) {
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillColor })
        if (strokeWidth > 0f && strokeColor != Color.TRANSPARENT) {
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.strokeWidth = strokeWidth; color = strokeColor })
        }
    }

    private fun drawMultiline(canvas: Canvas, text: String?, x: Float, y: Float, width: Float, paint: TextPaint, maxLines: Int): Int {
        val safeText = text ?: ""
        val layout = StaticLayout.Builder.obtain(safeText, 0, safeText.length, paint, max(1, width.roundToInt()))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f, 1f)
            .setMaxLines(max(1, maxLines)).setEllipsize(TextUtils.TruncateAt.END).build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return (y + layout.height).roundToInt()
    }

    private fun textPaint(color: Int, textSize: Float, bold: Boolean): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun titleFor(report: AnnualReportData): String = report.reportTitle?.trim()?.takeIf { it.isNotEmpty() }
        ?: "${report.year}${if (report.isBookScope()) " 单书阅读报告" else " 年度阅读报告"}"
    private fun summaryTitleFor(report: AnnualReportData?): String = when { report == null -> "阅读摘要"; report.isDayReport() -> "每日摘要"; report.isWeekReport() -> "周报摘要"; else -> "年度摘要" }
    private fun infoTitleFor(report: AnnualReportData?, highlight: Boolean): String = when {
        report?.isBookScope() == true -> if (highlight) "本书高光" else "本书坐标"
        report?.isDayReport() == true -> if (highlight) "今日书单" else "今日阅读地图"
        report?.isWeekReport() == true -> if (highlight) "周报书单" else "本周阅读地图"
        else -> if (highlight) "年度书单" else "年度阅读地图"
    }
    private fun rhythmTitleFor(report: AnnualReportData?, highlight: Boolean): String = when {
        report == null -> "阅读节奏"
        report.isYearReport() -> if (highlight) "12 个月的节奏" else "月度节奏"
        report.isWeekReport() -> "${report.periodTitle}阅读节奏"
        else -> "今日阅读构成"
    }

    private fun formatHoursCompact(seconds: Int): String {
        val hours = max(0, seconds) / 3600.0
        return String.format(Locale.SIMPLIFIED_CHINESE, if (hours < 10) "%.1f" else "%.0f", hours)
    }
    private fun formatNumber(value: Int): String = String.format(Locale.SIMPLIFIED_CHINESE, "%,d", max(value, 0))
    private fun formatCharsCompact(value: Int): String {
        val safe = max(value, 0)
        if (safe >= 10000) {
            val wan = safe / 10000f
            return String.format(Locale.SIMPLIFIED_CHINESE, if (wan >= 100f) "%.0f万" else "%.1f万", wan)
        }
        return String.format(Locale.SIMPLIFIED_CHINESE, "%,d", safe)
    }
    private fun formatBookDurationCompact(seconds: Int): String {
        val safe = max(seconds, 0)
        if (safe <= 0) return "0 分钟"
        val hours = safe / 3600f
        return when { hours >= 10f -> String.format(Locale.SIMPLIFIED_CHINESE, "%.0f 小时", hours); hours >= 1f -> String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 小时", hours); else -> "${max(1, (safe / 60f).roundToInt())} 分钟" }
    }
    private fun dailyBookMeta(stat: AnnualReportData.BookStat?): String {
        if (stat == null) return ""
        return buildString {
            if (stat.author.isNotBlank()) append(stat.author).append(" · ")
            append(formatBookDurationCompact(stat.totalSeconds)).append(" · ").append(formatCharsCompact(stat.totalChars)).append(" 字")
        }
    }

    private fun rhythmValues(report: AnnualReportData?): IntArray = when {
        report != null && report.rhythmSeconds.isNotEmpty() -> report.rhythmSeconds
        report != null && report.monthlySeconds.isNotEmpty() -> report.monthlySeconds
        else -> intArrayOf(0)
    }
    private fun dailyContextValues(report: AnnualReportData?): IntArray = if (report != null && report.dailyContextSeconds.isNotEmpty()) report.dailyContextSeconds else rhythmValues(report)
    private fun rhythmLabels(report: AnnualReportData?, count: Int): Array<String> = Array(max(1, count)) { i -> report?.rhythmLabels?.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: (i + 1).toString() }
    private fun dailyContextLabels(report: AnnualReportData?, count: Int): Array<String> = Array(max(1, count)) { i -> report?.dailyContextLabels?.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: (i + 1).toString() }
    private fun dailyContextCurrentIndex(report: AnnualReportData?, count: Int): Int = report?.dailyContextCurrentIndex?.takeIf { it in 0 until count } ?: max(0, count - 1)
    private fun maxRhythmSeconds(values: IntArray?): Int {
        var maximum = 1
        if (values != null) for (value in values) maximum = max(maximum, max(0, value))
        return maximum
    }
    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private class Palette(
        val backgroundTop: Int, val backgroundBottom: Int, val card: Int, val cardAlt: Int,
        val primaryText: Int, val secondaryText: Int, val mutedText: Int, val inverseText: Int,
        val accent: Int, val accent2: Int, val accent3: Int, val accentSoft: Int, val accent2Soft: Int, val line: Int,
    ) {
        companion object {
            fun quiet(theme: AnnualReportTheme): Palette = if (theme == AnnualReportTheme.DARK) Palette(
                Color.rgb(23, 24, 23), Color.rgb(35, 38, 34), Color.rgb(43, 45, 41), Color.rgb(54, 56, 50),
                Color.rgb(249, 246, 235), Color.rgb(219, 214, 199), Color.rgb(158, 150, 132), Color.rgb(23, 24, 23),
                Color.rgb(115, 188, 151), Color.rgb(216, 168, 92), Color.rgb(126, 156, 214), Color.argb(42, 115, 188, 151),
                Color.argb(40, 216, 168, 92), Color.argb(42, 249, 246, 235),
            ) else Palette(
                Color.rgb(251, 247, 238), Color.rgb(238, 247, 244), Color.rgb(255, 253, 247), Color.rgb(247, 243, 232),
                Color.rgb(44, 42, 38), Color.rgb(83, 79, 68), Color.rgb(124, 116, 96), Color.rgb(255, 253, 247),
                Color.rgb(70, 132, 112), Color.rgb(188, 124, 66), Color.rgb(72, 104, 166), Color.argb(36, 70, 132, 112),
                Color.argb(34, 188, 124, 66), Color.rgb(223, 216, 198),
            )

            fun highlight(theme: AnnualReportTheme): Palette = if (theme == AnnualReportTheme.LIGHT) Palette(
                Color.rgb(255, 248, 232), Color.rgb(236, 247, 255), Color.rgb(255, 255, 255), Color.rgb(247, 250, 255),
                Color.rgb(34, 31, 47), Color.rgb(78, 76, 92), Color.rgb(122, 118, 132), Color.rgb(255, 255, 255),
                Color.rgb(88, 80, 224), Color.rgb(0, 155, 112), Color.rgb(238, 132, 48), Color.argb(42, 88, 80, 224),
                Color.argb(38, 0, 155, 112), Color.rgb(218, 224, 237),
            ) else Palette(
                Color.rgb(20, 21, 27), Color.rgb(38, 35, 49), Color.rgb(36, 38, 50), Color.rgb(46, 48, 62),
                Color.rgb(249, 248, 242), Color.rgb(218, 222, 231), Color.rgb(154, 163, 177), Color.rgb(20, 21, 27),
                Color.rgb(108, 100, 255), Color.rgb(49, 213, 146), Color.rgb(245, 142, 73), Color.argb(54, 108, 100, 255),
                Color.argb(48, 49, 213, 146), Color.argb(42, 255, 255, 255),
            )
        }
    }

    companion object {
        const val REPORT_WIDTH = 1080
        const val REPORT_HEIGHT = 1920
    }
}
