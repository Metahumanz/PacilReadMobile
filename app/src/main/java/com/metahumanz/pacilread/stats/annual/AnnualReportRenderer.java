package com.metahumanz.pacilread.stats.annual;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import com.metahumanz.pacilread.stats.ReadingStatsUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AnnualReportRenderer {
    public static final int REPORT_WIDTH = 1080;
    public static final int REPORT_HEIGHT = 1920;

    public Bitmap render(AnnualReportData report, AnnualReportStyle style) {
        return render(report, style, AnnualReportTheme.LIGHT);
    }

    public Bitmap render(AnnualReportData report, AnnualReportStyle style, AnnualReportTheme theme) {
        return render(report, style, theme, null);
    }

    public Bitmap render(AnnualReportData report, AnnualReportStyle style, AnnualReportTheme theme,
                         List<AnnualReportMetric> summaryMetrics) {
        Bitmap bitmap = Bitmap.createBitmap(REPORT_WIDTH, REPORT_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        AnnualReportStyle safeStyle = style == null ? AnnualReportStyle.QUIET : style;
        AnnualReportTheme safeTheme = theme == null ? AnnualReportTheme.LIGHT : theme;
        List<AnnualReportMetric> safeMetrics = AnnualReportMetric.sanitizeMetrics(report, summaryMetrics);
        if (safeStyle == AnnualReportStyle.HIGHLIGHT) {
            renderHighlight(canvas, report, Palette.highlight(safeTheme), safeMetrics);
        } else {
            renderQuiet(canvas, report, Palette.quiet(safeTheme), safeMetrics);
        }
        return bitmap;
    }

    private void renderQuiet(Canvas canvas, AnnualReportData report, Palette palette,
                             List<AnnualReportMetric> summaryMetrics) {
        drawBackground(canvas, palette);

        TextPaint brandPaint = textPaint(palette.mutedText, 28f, true);
        TextPaint titlePaint = textPaint(palette.primaryText, 64f, true);
        TextPaint subtitlePaint = textPaint(palette.secondaryText, 32f, false);
        TextPaint giantPaint = textPaint(palette.primaryText, 86f, true);
        TextPaint bodyPaint = textPaint(palette.secondaryText, 28f, false);
        TextPaint cardTitlePaint = textPaint(palette.primaryText, 36f, true);

        drawMultiline(canvas, "PacilRead Mobile", 72, 76, 500, brandPaint, 1);
        drawMultiline(canvas, titleFor(report), 72, 150, 860, titlePaint, 2);
        drawMultiline(canvas, AnnualReportInsight.sentence(report), 74, 302, 820, subtitlePaint, 2);

        drawMultiline(canvas, ReadingStatsUtils.formatDuration(report.totalSeconds), 72, 408, 660, giantPaint, 2);
        drawMultiline(canvas, "阅读总时长", 76, 520, 420, bodyPaint, 1);
        drawMultiline(canvas,
                "累计 " + formatNumber(report.totalChars) + " 字 · " + report.readingDays + " 个阅读日",
                76, 570, 820, bodyPaint, 2);

        RectF summary = new RectF(72, 678, 1008, 966);
        drawRoundRect(canvas, summary, 34, palette.card, palette.line, 2f);
        drawMultiline(canvas, summaryTitleFor(report), 112, 728, 360, cardTitlePaint, 1);
        drawMetric(canvas, 112, 796, 258, summaryMetrics.get(0).label(report),
                summaryMetrics.get(0).value(report), palette, palette.accent);
        drawMetric(canvas, 394, 796, 258, summaryMetrics.get(1).label(report),
                summaryMetrics.get(1).value(report), palette, palette.accent2);
        drawMetric(canvas, 676, 796, 258, summaryMetrics.get(2).label(report),
                summaryMetrics.get(2).value(report), palette, palette.accent3);

        boolean dailyReport = report != null && report.isDayReport();
        List<String[]> rows = infoRows(report);
        float rowHeight = dailyReport ? 72f : 84f;
        float infoHeaderHeight = dailyReport ? 108f : 118f;
        float infoTop = summary.bottom + (dailyReport ? 48f : 66f);
        float infoHeight = infoHeaderHeight + Math.max(1, rows.size()) * rowHeight;
        RectF info = new RectF(72, infoTop, 1008, infoTop + infoHeight);
        drawRoundRect(canvas, info, 34, palette.card, palette.line, 2f);
        drawMultiline(canvas, infoTitleFor(report, false), 112, infoTop + 46, 460, cardTitlePaint, 1);
        drawInfoRows(canvas, rows, 112, infoTop + infoHeaderHeight, 820, rowHeight, palette);

        float rhythmTop = info.bottom + (dailyReport ? 46f : 66f);
        RectF rhythm = new RectF(72, rhythmTop, 1008, rhythmTop + (dailyReport ? 348f : 286f));
        drawRoundRect(canvas, rhythm, 34, palette.card, palette.line, 2f);
        if (dailyReport) {
            drawDailyReportVisual(canvas, report, palette, rhythm, false);
        } else {
            drawMultiline(canvas, rhythmTitleFor(report, false), 112, rhythmTop + 38f, 420, cardTitlePaint, 1);
            drawRhythmBars(canvas, report, palette, 112, rhythmTop + 114f, 856, 104, false);
        }

        float footerTop = Math.min(Math.max(rhythm.bottom + 26f, 1814f), 1864f);
        drawMultiline(canvas, "PacilRead Mobile", 72, footerTop, 360, textPaint(palette.mutedText, 26f, false), 1);
    }

    private void renderHighlight(Canvas canvas, AnnualReportData report, Palette palette,
                                 List<AnnualReportMetric> summaryMetrics) {
        drawBackground(canvas, palette);

        Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shapePaint.setColor(palette.accentSoft);
        canvas.save();
        canvas.rotate(-8f, 840, 160);
        canvas.drawRoundRect(new RectF(650, 48, 1200, 250), 42, 42, shapePaint);
        canvas.restore();
        shapePaint.setColor(palette.accent2Soft);
        canvas.drawRoundRect(new RectF(-110, 610, 340, 806), 44, 44, shapePaint);

        TextPaint brandPaint = textPaint(palette.mutedText, 28f, true);
        TextPaint titlePaint = textPaint(palette.primaryText, 58f, true);
        TextPaint giantPaint = textPaint(palette.primaryText, 104f, true);
        TextPaint bodyPaint = textPaint(palette.secondaryText, 32f, false);
        TextPaint cardTitlePaint = textPaint(palette.primaryText, 42f, true);

        drawPill(canvas, 72, 76, 354, 130, palette.accent, Color.TRANSPARENT, "PacilRead Mobile", palette.inverseText);
        drawMultiline(canvas, titleFor(report), 72, 188, 820, titlePaint, 2);
        drawMultiline(canvas, formatHoursCompact(report.totalSeconds), 72, 356, 520, giantPaint, 1);
        drawMultiline(canvas, "小时阅读", 78, 474, 420, titlePaint, 1);
        drawMultiline(canvas, AnnualReportInsight.sentence(report), 78, 548, 820, bodyPaint, 2);

        RectF summary = new RectF(72, 694, 1008, 936);
        drawRoundRect(canvas, summary, 34, palette.card, palette.line, 2f);
        drawHighlightStat(canvas, 118, 742, summaryMetrics.get(0).label(report),
                summaryMetrics.get(0).value(report), palette, palette.accent);
        drawHighlightStat(canvas, 396, 742, summaryMetrics.get(1).label(report),
                summaryMetrics.get(1).value(report), palette, palette.accent2);
        drawHighlightStat(canvas, 674, 742, summaryMetrics.get(2).label(report),
                summaryMetrics.get(2).value(report), palette, palette.accent3);

        boolean dailyReport = report != null && report.isDayReport();
        List<String[]> rows = infoRows(report);
        float rowHeight = dailyReport ? 68f : 76f;
        float infoHeaderHeight = dailyReport ? 110f : 126f;
        float infoTop = summary.bottom + (dailyReport ? 50f : 66f);
        float infoHeight = infoHeaderHeight + Math.max(1, rows.size()) * rowHeight;
        drawRoundRect(canvas, new RectF(72, infoTop, 1008, infoTop + infoHeight), 34, palette.card, palette.line, 2f);
        drawMultiline(canvas, infoTitleFor(report, true), 116, infoTop + 48, 520, cardTitlePaint, 1);
        drawInfoRows(canvas, rows, 116, infoTop + infoHeaderHeight, 816, rowHeight, palette);

        float rhythmTop = infoTop + infoHeight + (dailyReport ? 48f : 58f);
        RectF rhythm = new RectF(72, rhythmTop, 1008, rhythmTop + (dailyReport ? 368f : 310f));
        drawRoundRect(canvas, rhythm, 34, palette.card, palette.line, 2f);
        if (dailyReport) {
            drawDailyReportVisual(canvas, report, palette, rhythm, true);
        } else {
            drawMultiline(canvas, rhythmTitleFor(report, true), 116, rhythmTop + 44f, 560, cardTitlePaint, 1);
            drawRhythmBars(canvas, report, palette, 116, rhythmTop + 128f, 846, 116, true);
        }

        float footerTop = Math.min(Math.max(rhythm.bottom + 28f, 1814f), 1864f);
        drawMultiline(canvas, "PacilRead Mobile", 72, footerTop, 360, brandPaint, 1);
    }

    private List<String[]> infoRows(AnnualReportData report) {
        List<String[]> rows = new ArrayList<>();
        if (report.isBookScope()) {
            addRow(rows, "书籍", report.bookTitle);
            addRow(rows, "作者", report.bookAuthor);
            addRow(rows, "标签", report.topTag);
            addRow(rows, "系列", report.topSeries);
            addRow(rows, "状态", report.statusText);
        } else {
            addTopBookRows(rows, report);
            addRow(rows, "阅读地图", readingMapText(report));
        }
        if (rows.isEmpty()) {
            addRow(rows, "范围", report.rangeTitle);
        }
        while (rows.size() > 4) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }

    private void addTopBookRows(List<String[]> rows, AnnualReportData report) {
        if (report == null || report.topBooks.isEmpty()) {
            addRow(rows, "Top 书籍", report == null ? "" : report.topBook);
            return;
        }
        int count = Math.min(3, report.topBooks.size());
        for (int i = 0; i < count; i++) {
            addRow(rows, "Top " + (i + 1), bookStatText(report.topBooks.get(i)));
        }
    }

    private void addRow(List<String[]> rows, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        rows.add(new String[]{label, value.trim()});
    }

    private String bookStatText(AnnualReportData.BookStat stat) {
        if (stat == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(stat.title == null || stat.title.trim().isEmpty() ? "未命名书籍" : stat.title.trim());
        if (stat.author != null && !stat.author.trim().isEmpty()) {
            builder.append(" · ").append(stat.author.trim());
        }
        if (stat.totalSeconds > 0) {
            builder.append(" · ").append(ReadingStatsUtils.formatDuration(stat.totalSeconds));
        }
        if (stat.totalChars > 0) {
            builder.append(" · ").append(formatNumber(stat.totalChars)).append(" 字");
        }
        return builder.toString();
    }

    private String readingMapText(AnnualReportData report) {
        if (report == null) {
            return "";
        }
        String tags = joinNamedStats(report.topTags, 2);
        if (!tags.isEmpty()) {
            return "标签：" + tags;
        }
        String authors = joinNamedStats(report.topAuthors, 2);
        if (!authors.isEmpty()) {
            return "作者：" + authors;
        }
        String series = joinNamedStats(report.topSeriesStats, 2);
        if (!series.isEmpty()) {
            return "系列：" + series;
        }
        return report.readingBooks > 0 ? "覆盖 " + report.readingBooks + " 本书" : "";
    }

    private String joinNamedStats(List<AnnualReportData.NamedStat> stats, int limit) {
        if (stats == null || stats.isEmpty() || limit <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (AnnualReportData.NamedStat stat : stats) {
            if (stat == null || stat.name == null || stat.name.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(stat.name.trim());
            count++;
            if (count >= limit) {
                break;
            }
        }
        return builder.toString();
    }

    private void drawInfoRows(Canvas canvas, List<String[]> rows, float left, float top, float width,
                              float rowHeight, Palette palette) {
        TextPaint labelPaint = textPaint(palette.mutedText, 24f, false);
        TextPaint valuePaint = textPaint(palette.primaryText, 31f, true);
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(palette.line);
        linePaint.setStrokeWidth(2f);
        for (int i = 0; i < rows.size(); i++) {
            float rowTop = top + i * rowHeight;
            drawMultiline(canvas, rows.get(i)[0], left, rowTop + 6, 170, labelPaint, 1);
            drawMultiline(canvas, rows.get(i)[1], left + 190, rowTop, width - 190, valuePaint, 2);
            if (i < rows.size() - 1) {
                float lineY = rowTop + rowHeight - 14;
                canvas.drawLine(left, lineY, left + width, lineY, linePaint);
            }
        }
    }

    private void drawMetric(Canvas canvas, float left, float top, float width, String label, String value,
                            Palette palette, int accentColor) {
        drawRoundRect(canvas, new RectF(left, top, left + width, top + 122), 24, palette.cardAlt, palette.line, 1.4f);
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(accentColor);
        canvas.drawCircle(left + 26, top + 30, 8, dotPaint);
        drawMultiline(canvas, label, left + 44, top + 18, width - 58, textPaint(palette.mutedText, 22f, false), 1);
        float valueSize = value != null && value.length() > 8 ? 28f : 34f;
        drawMultiline(canvas, value, left + 24, top + 58, width - 48,
                textPaint(palette.primaryText, valueSize, true), 2);
    }

    private void drawHighlightStat(Canvas canvas, float left, float top, String label, String value,
                                   Palette palette, int accentColor) {
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(accentColor);
        canvas.drawCircle(left + 20, top + 20, 12, dotPaint);
        drawMultiline(canvas, label, left, top + 54, 220, textPaint(palette.mutedText, 23f, false), 1);
        float valueSize = value != null && value.length() > 8 ? 30f : 38f;
        drawMultiline(canvas, value, left, top + 92, 220,
                textPaint(palette.primaryText, valueSize, true), 2);
    }

    private void drawRhythmBars(Canvas canvas, AnnualReportData report, Palette palette, float left, float top,
                                float width, float height, boolean bold) {
        int[] values = rhythmValues(report);
        String[] labels = rhythmLabels(report, values.length);
        int count = Math.max(1, values.length);
        int max = maxRhythmSeconds(values);
        float gap = count <= 1 ? 0f : (bold ? 10f : 12f);
        float barWidth = count <= 1
                ? Math.min(160f, width * 0.36f)
                : (width - gap * (count - 1f)) / count;
        float firstX = count <= 1 ? left + (width - barWidth) / 2f : left;
        TextPaint monthPaint = textPaint(palette.mutedText, 20f, bold);
        Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baselinePaint.setColor(palette.line);
        baselinePaint.setStrokeWidth(2f);
        canvas.drawLine(left, top + height + 1, left + width, top + height + 1, baselinePaint);
        for (int i = 0; i < count; i++) {
            int seconds = Math.max(values[i], 0);
            float ratio = seconds <= 0 ? 0.04f : seconds / (float) max;
            float barHeight = Math.max(seconds <= 0 ? 7f : 12f, height * ratio);
            float x = firstX + i * (barWidth + gap);
            float y = top + height - barHeight;
            int color = i % 3 == 0 ? palette.accent : (i % 3 == 1 ? palette.accent2 : palette.accent3);
            drawRoundRect(canvas, new RectF(x, y, x + barWidth, top + height), bold ? 8 : 9, color, Color.TRANSPARENT, 0);
            drawMultiline(canvas, labels[i], x - 8, top + height + 24, barWidth + 16, monthPaint, 1);
        }
    }

    private void drawDailyReportVisual(Canvas canvas, AnnualReportData report, Palette palette, RectF panel, boolean bold) {
        float left = panel.left + (bold ? 44f : 40f);
        float width = panel.width() - (bold ? 88f : 80f);
        float headerTop = panel.top + (bold ? 28f : 26f);
        TextPaint kickerPaint = textPaint(palette.mutedText, bold ? 21f : 19f, true);
        TextPaint titlePaint = textPaint(palette.primaryText, bold ? 38f : 34f, true);
        TextPaint charsPaint = textPaint(palette.accent, bold ? 25f : 23f, true);

        drawMultiline(canvas, "TODAY MIX", left, headerTop, 280, kickerPaint, 1);
        drawMultiline(canvas, "今日阅读构成", left, headerTop + (bold ? 30f : 28f), 430, titlePaint, 1);
        drawMultiline(canvas, formatCharsCompact(report.totalChars) + " 字",
                left + width - 230f, headerTop + (bold ? 34f : 31f), 230, charsPaint, 1);

        float contextTop = panel.bottom - (bold ? 100f : 96f);
        float bookTop = panel.top + (bold ? 108f : 100f);
        drawDailyBookBars(canvas, report, palette, left, bookTop, width, contextTop - bookTop - 14f, bold);
        drawDailyContextBars(canvas, report, palette, left, contextTop, width, bold);
    }

    private void drawDailyBookBars(Canvas canvas, AnnualReportData report, Palette palette, float left, float top,
                                   float width, float height, boolean bold) {
        int rowCount = report == null ? 0 : Math.min(3, report.topBooks.size());
        if (rowCount <= 0) {
            drawMultiline(canvas, "暂无今日书籍记录", left, top + 8f, width,
                    textPaint(palette.mutedText, bold ? 25f : 23f, false), 1);
            return;
        }
        float stride = Math.max(44f, height / rowCount);
        float rowHeight = Math.max(42f, Math.min(bold ? 52f : 50f, stride - 2f));
        for (int i = 0; i < rowCount; i++) {
            AnnualReportData.BookStat book = report.topBooks.get(i);
            drawDailyBookRow(canvas, report, book, palette, left, top + i * stride, width, rowHeight, i, bold);
        }
    }

    private void drawDailyBookRow(Canvas canvas, AnnualReportData report, AnnualReportData.BookStat book,
                                  Palette palette, float left, float top, float width, float height, int index,
                                  boolean bold) {
        int totalSeconds = Math.max(1, report == null ? 0 : report.totalSeconds);
        int bookSeconds = book == null ? 0 : Math.max(book.totalSeconds, 0);
        int percent = bookSeconds <= 0 ? 0 : Math.max(1, Math.round(bookSeconds * 100f / totalSeconds));
        float ratio = bookSeconds <= 0 ? 0f : Math.min(1f, bookSeconds / (float) totalSeconds);
        float amountWidth = bold ? 104f : 96f;
        float copyWidth = width - amountWidth - 20f;
        TextPaint titlePaint = textPaint(palette.primaryText, bold ? 22f : 20f, true);
        TextPaint metaPaint = textPaint(palette.mutedText, bold ? 16f : 15f, false);
        TextPaint percentPaint = textPaint(palette.accent, bold ? 27f : 25f, true);
        String title = book == null || book.title == null || book.title.trim().isEmpty()
                ? "未命名书籍"
                : book.title.trim();
        drawMultiline(canvas, title, left, top, copyWidth, titlePaint, 1);
        drawMultiline(canvas, dailyBookMeta(book), left, top + (bold ? 25f : 23f), copyWidth, metaPaint, 1);
        drawMultiline(canvas, percent + "%", left + width - amountWidth, top + 3f, amountWidth, percentPaint, 1);

        float trackTop = top + height - 8f;
        RectF track = new RectF(left, trackTop, left + width, trackTop + 7f);
        drawRoundRect(canvas, track, 4f, palette.cardAlt, palette.line, 1f);
        if (ratio > 0f) {
            int fillColor = index % 3 == 0 ? palette.accent : (index % 3 == 1 ? palette.accent2 : palette.accent3);
            float fillWidth = Math.max(10f, track.width() * ratio);
            drawRoundRect(canvas, new RectF(track.left, track.top, track.left + fillWidth, track.bottom),
                    4f, fillColor, Color.TRANSPARENT, 0f);
        }
    }

    private void drawDailyContextBars(Canvas canvas, AnnualReportData report, Palette palette, float left, float top,
                                      float width, boolean bold) {
        int[] values = dailyContextValues(report);
        String[] labels = dailyContextLabels(report, values.length);
        int count = Math.max(1, values.length);
        int currentIndex = dailyContextCurrentIndex(report, count);
        int max = maxRhythmSeconds(values);
        TextPaint headingPaint = textPaint(palette.mutedText, bold ? 19f : 18f, true);
        TextPaint currentPaint = textPaint(palette.accent2, bold ? 19f : 18f, true);
        TextPaint labelPaint = textPaint(palette.mutedText, bold ? 16f : 15f, false);
        drawMultiline(canvas, "最近 7 天", left, top, 220, headingPaint, 1);
        drawMultiline(canvas, "今日高亮", left + width - 140f, top, 140, currentPaint, 1);

        float barTop = top + (bold ? 34f : 32f);
        float barMaxHeight = bold ? 42f : 40f;
        float gap = count <= 1 ? 0f : (bold ? 11f : 10f);
        float barWidth = count <= 1 ? Math.min(120f, width * 0.2f) : (width - gap * (count - 1f)) / count;
        float firstX = count <= 1 ? left + (width - barWidth) / 2f : left;
        for (int i = 0; i < count; i++) {
            int seconds = Math.max(values[i], 0);
            float ratio = seconds <= 0 ? 0.05f : seconds / (float) max;
            float barHeight = Math.max(seconds <= 0 ? 5f : 8f, barMaxHeight * ratio);
            float x = firstX + i * (barWidth + gap);
            float y = barTop + barMaxHeight - barHeight;
            boolean current = i == currentIndex;
            int fillColor = current
                    ? palette.accent2
                    : (seconds <= 0 ? withAlpha(palette.mutedText, 72) : palette.accent3);
            drawRoundRect(canvas, new RectF(x, y, x + barWidth, barTop + barMaxHeight),
                    current ? 6f : 5f, fillColor, Color.TRANSPARENT, 0f);
            if (current) {
                Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(2f);
                strokePaint.setColor(palette.line);
                canvas.drawRoundRect(new RectF(x - 3f, y - 3f, x + barWidth + 3f, barTop + barMaxHeight + 3f),
                        8f, 8f, strokePaint);
            }
            drawMultiline(canvas, labels[i], x - 6f, barTop + barMaxHeight + 8f, barWidth + 12f, labelPaint, 1);
        }
    }

    private void drawBackground(Canvas canvas, Palette palette) {
        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setShader(new LinearGradient(
                0, 0, REPORT_WIDTH, REPORT_HEIGHT,
                palette.backgroundTop,
                palette.backgroundBottom,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0, 0, REPORT_WIDTH, REPORT_HEIGHT, backgroundPaint);
    }

    private void drawPill(Canvas canvas, float left, float top, float right, float bottom,
                          int fillColor, int strokeColor, String text, int textColor) {
        drawRoundRect(canvas, new RectF(left, top, right, bottom), (bottom - top) / 2f, fillColor, strokeColor, 1.3f);
        drawMultiline(canvas, text, left + 22, top + 14, Math.max(1, right - left - 44),
                textPaint(textColor, 24f, true), 1);
    }

    private void drawRoundRect(Canvas canvas, RectF rect, float radius, int fillColor, int strokeColor, float strokeWidth) {
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);
        canvas.drawRoundRect(rect, radius, radius, fillPaint);
        if (strokeWidth > 0f && strokeColor != Color.TRANSPARENT) {
            Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeWidth);
            strokePaint.setColor(strokeColor);
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        }
    }

    private int drawMultiline(Canvas canvas, String text, float x, float y, float width, TextPaint paint, int maxLines) {
        String safeText = text == null ? "" : text;
        int safeWidth = Math.max(1, Math.round(width));
        StaticLayout layout = StaticLayout.Builder.obtain(safeText, 0, safeText.length(), paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.0f)
                .setMaxLines(Math.max(1, maxLines))
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
        return Math.round(y + layout.getHeight());
    }

    private TextPaint textPaint(int color, float textSize, boolean bold) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(color);
        paint.setTextSize(textSize);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        return paint;
    }

    private String titleFor(AnnualReportData report) {
        if (report != null && report.reportTitle != null && !report.reportTitle.trim().isEmpty()) {
            return report.reportTitle.trim();
        }
        return report.year + (report.isBookScope() ? " 单书阅读报告" : " 年度阅读报告");
    }

    private String summaryTitleFor(AnnualReportData report) {
        if (report == null) {
            return "阅读摘要";
        }
        if (report.isDayReport()) {
            return "每日摘要";
        }
        if (report.isWeekReport()) {
            return "周报摘要";
        }
        return "年度摘要";
    }

    private String infoTitleFor(AnnualReportData report, boolean highlight) {
        if (report != null && report.isBookScope()) {
            return highlight ? "本书高光" : "本书坐标";
        }
        if (report != null && report.isDayReport()) {
            return highlight ? "今日书单" : "今日阅读地图";
        }
        if (report != null && report.isWeekReport()) {
            return highlight ? "周报书单" : "本周阅读地图";
        }
        return highlight ? "年度书单" : "年度阅读地图";
    }

    private String rhythmTitleFor(AnnualReportData report, boolean highlight) {
        if (report == null) {
            return "阅读节奏";
        }
        if (report.isYearReport()) {
            return highlight ? "12 个月的节奏" : "月度节奏";
        }
        if (report.isWeekReport()) {
            return report.periodTitle + "阅读节奏";
        }
        return "今日阅读构成";
    }

    private String formatHoursCompact(int seconds) {
        double hours = Math.max(0, seconds) / 3600.0;
        if (hours < 10) {
            return String.format(Locale.SIMPLIFIED_CHINESE, "%.1f", hours);
        }
        return String.format(Locale.SIMPLIFIED_CHINESE, "%.0f", hours);
    }

    private String formatNumber(int value) {
        return String.format(Locale.SIMPLIFIED_CHINESE, "%,d", Math.max(value, 0));
    }

    private String formatCharsCompact(int value) {
        int safeValue = Math.max(value, 0);
        if (safeValue >= 10000) {
            float wan = safeValue / 10000f;
            return wan >= 100f
                    ? String.format(Locale.SIMPLIFIED_CHINESE, "%.0f万", wan)
                    : String.format(Locale.SIMPLIFIED_CHINESE, "%.1f万", wan);
        }
        return String.format(Locale.SIMPLIFIED_CHINESE, "%,d", safeValue);
    }

    private String formatBookDurationCompact(int seconds) {
        int safeSeconds = Math.max(seconds, 0);
        if (safeSeconds <= 0) {
            return "0 分钟";
        }
        float hours = safeSeconds / 3600f;
        if (hours >= 10f) {
            return String.format(Locale.SIMPLIFIED_CHINESE, "%.0f 小时", hours);
        }
        if (hours >= 1f) {
            return String.format(Locale.SIMPLIFIED_CHINESE, "%.1f 小时", hours);
        }
        return Math.max(1, Math.round(safeSeconds / 60f)) + " 分钟";
    }

    private String dailyBookMeta(AnnualReportData.BookStat stat) {
        if (stat == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (stat.author != null && !stat.author.trim().isEmpty()) {
            builder.append(stat.author.trim()).append(" · ");
        }
        builder.append(formatBookDurationCompact(stat.totalSeconds));
        builder.append(" · ").append(formatCharsCompact(stat.totalChars)).append(" 字");
        return builder.toString();
    }

    private int[] rhythmValues(AnnualReportData report) {
        if (report != null && report.rhythmSeconds != null && report.rhythmSeconds.length > 0) {
            return report.rhythmSeconds;
        }
        if (report != null && report.monthlySeconds != null && report.monthlySeconds.length > 0) {
            return report.monthlySeconds;
        }
        return new int[]{0};
    }

    private int[] dailyContextValues(AnnualReportData report) {
        if (report != null && report.dailyContextSeconds != null && report.dailyContextSeconds.length > 0) {
            return report.dailyContextSeconds;
        }
        return rhythmValues(report);
    }

    private String[] rhythmLabels(AnnualReportData report, int count) {
        String[] labels = new String[Math.max(1, count)];
        for (int i = 0; i < labels.length; i++) {
            if (report != null && report.rhythmLabels != null && i < report.rhythmLabels.length
                    && report.rhythmLabels[i] != null && !report.rhythmLabels[i].trim().isEmpty()) {
                labels[i] = report.rhythmLabels[i].trim();
            } else {
                labels[i] = String.valueOf(i + 1);
            }
        }
        return labels;
    }

    private String[] dailyContextLabels(AnnualReportData report, int count) {
        String[] labels = new String[Math.max(1, count)];
        for (int i = 0; i < labels.length; i++) {
            if (report != null && report.dailyContextLabels != null && i < report.dailyContextLabels.length
                    && report.dailyContextLabels[i] != null && !report.dailyContextLabels[i].trim().isEmpty()) {
                labels[i] = report.dailyContextLabels[i].trim();
            } else {
                labels[i] = String.valueOf(i + 1);
            }
        }
        return labels;
    }

    private int dailyContextCurrentIndex(AnnualReportData report, int count) {
        if (report != null && report.dailyContextCurrentIndex >= 0 && report.dailyContextCurrentIndex < count) {
            return report.dailyContextCurrentIndex;
        }
        return Math.max(0, count - 1);
    }

    private int maxRhythmSeconds(int[] values) {
        int max = 1;
        if (values == null) {
            return max;
        }
        for (int value : values) {
            max = Math.max(max, Math.max(0, value));
        }
        return max;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }

    private static final class Palette {
        final int backgroundTop;
        final int backgroundBottom;
        final int card;
        final int cardAlt;
        final int primaryText;
        final int secondaryText;
        final int mutedText;
        final int inverseText;
        final int accent;
        final int accent2;
        final int accent3;
        final int accentSoft;
        final int accent2Soft;
        final int line;

        private Palette(
                int backgroundTop,
                int backgroundBottom,
                int card,
                int cardAlt,
                int primaryText,
                int secondaryText,
                int mutedText,
                int inverseText,
                int accent,
                int accent2,
                int accent3,
                int accentSoft,
                int accent2Soft,
                int line
        ) {
            this.backgroundTop = backgroundTop;
            this.backgroundBottom = backgroundBottom;
            this.card = card;
            this.cardAlt = cardAlt;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.mutedText = mutedText;
            this.inverseText = inverseText;
            this.accent = accent;
            this.accent2 = accent2;
            this.accent3 = accent3;
            this.accentSoft = accentSoft;
            this.accent2Soft = accent2Soft;
            this.line = line;
        }

        static Palette quiet(AnnualReportTheme theme) {
            if (theme == AnnualReportTheme.DARK) {
                return new Palette(
                        Color.rgb(23, 24, 23),
                        Color.rgb(35, 38, 34),
                        Color.rgb(43, 45, 41),
                        Color.rgb(54, 56, 50),
                        Color.rgb(249, 246, 235),
                        Color.rgb(219, 214, 199),
                        Color.rgb(158, 150, 132),
                        Color.rgb(23, 24, 23),
                        Color.rgb(115, 188, 151),
                        Color.rgb(216, 168, 92),
                        Color.rgb(126, 156, 214),
                        Color.argb(42, 115, 188, 151),
                        Color.argb(40, 216, 168, 92),
                        Color.argb(42, 249, 246, 235)
                );
            }
            return new Palette(
                    Color.rgb(251, 247, 238),
                    Color.rgb(238, 247, 244),
                    Color.rgb(255, 253, 247),
                    Color.rgb(247, 243, 232),
                    Color.rgb(44, 42, 38),
                    Color.rgb(83, 79, 68),
                    Color.rgb(124, 116, 96),
                    Color.rgb(255, 253, 247),
                    Color.rgb(70, 132, 112),
                    Color.rgb(188, 124, 66),
                    Color.rgb(72, 104, 166),
                    Color.argb(36, 70, 132, 112),
                    Color.argb(34, 188, 124, 66),
                    Color.rgb(223, 216, 198)
            );
        }

        static Palette highlight(AnnualReportTheme theme) {
            if (theme == AnnualReportTheme.LIGHT) {
                return new Palette(
                        Color.rgb(255, 248, 232),
                        Color.rgb(236, 247, 255),
                        Color.rgb(255, 255, 255),
                        Color.rgb(247, 250, 255),
                        Color.rgb(34, 31, 47),
                        Color.rgb(78, 76, 92),
                        Color.rgb(122, 118, 132),
                        Color.rgb(255, 255, 255),
                        Color.rgb(88, 80, 224),
                        Color.rgb(0, 155, 112),
                        Color.rgb(238, 132, 48),
                        Color.argb(42, 88, 80, 224),
                        Color.argb(38, 0, 155, 112),
                        Color.rgb(218, 224, 237)
                );
            }
            return new Palette(
                    Color.rgb(20, 21, 27),
                    Color.rgb(38, 35, 49),
                    Color.rgb(36, 38, 50),
                    Color.rgb(46, 48, 62),
                    Color.rgb(249, 248, 242),
                    Color.rgb(218, 222, 231),
                    Color.rgb(154, 163, 177),
                    Color.rgb(20, 21, 27),
                    Color.rgb(108, 100, 255),
                    Color.rgb(49, 213, 146),
                    Color.rgb(245, 142, 73),
                    Color.argb(54, 108, 100, 255),
                    Color.argb(48, 49, 213, 146),
                    Color.argb(42, 255, 255, 255)
            );
        }
    }
}
