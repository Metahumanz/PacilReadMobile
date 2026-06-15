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
        drawMultiline(canvas, quietSubtitle(report), 74, 302, 820, subtitlePaint, 2);

        drawMultiline(canvas, ReadingStatsUtils.formatDuration(report.totalSeconds), 72, 408, 660, giantPaint, 2);
        drawMultiline(canvas, "阅读总时长", 76, 520, 420, bodyPaint, 1);
        drawMultiline(canvas,
                "累计 " + formatNumber(report.totalChars) + " 字 · " + report.readingDays + " 个阅读日",
                76, 570, 820, bodyPaint, 2);

        RectF summary = new RectF(72, 678, 1008, 966);
        drawRoundRect(canvas, summary, 34, palette.card, palette.line, 2f);
        drawMultiline(canvas, "年度摘要", 112, 728, 360, cardTitlePaint, 1);
        drawMetric(canvas, 112, 796, 258, summaryMetrics.get(0).label(report),
                summaryMetrics.get(0).value(report), palette, palette.accent);
        drawMetric(canvas, 394, 796, 258, summaryMetrics.get(1).label(report),
                summaryMetrics.get(1).value(report), palette, palette.accent2);
        drawMetric(canvas, 676, 796, 258, summaryMetrics.get(2).label(report),
                summaryMetrics.get(2).value(report), palette, palette.accent3);

        List<String[]> rows = infoRows(report);
        float rowHeight = 84f;
        float infoTop = summary.bottom + 66f;
        float infoHeight = 118f + Math.max(1, rows.size()) * rowHeight;
        RectF info = new RectF(72, infoTop, 1008, infoTop + infoHeight);
        drawRoundRect(canvas, info, 34, palette.card, palette.line, 2f);
        drawMultiline(canvas, report.isBookScope() ? "本书坐标" : "年度书页坐标", 112, infoTop + 46, 460, cardTitlePaint, 1);
        drawInfoRows(canvas, rows, 112, infoTop + 118, 820, rowHeight, palette);

        float rhythmTop = info.bottom + 66f;
        RectF rhythm = new RectF(72, rhythmTop, 1008, rhythmTop + 286f);
        drawRoundRect(canvas, rhythm, 34, palette.card, palette.line, 2f);
        drawMultiline(canvas, "月度节奏", 112, rhythmTop + 38f, 360, cardTitlePaint, 1);
        drawMonthlyBars(canvas, report, palette, 112, rhythmTop + 114f, 856, 104, false);

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
        drawMultiline(canvas,
                report.readingDays + " 个阅读日 · " + formatNumber(report.totalChars) + " 字",
                78, 548, 820, bodyPaint, 2);

        RectF summary = new RectF(72, 694, 1008, 936);
        drawRoundRect(canvas, summary, 34, palette.card, palette.line, 2f);
        drawHighlightStat(canvas, 118, 742, summaryMetrics.get(0).label(report),
                summaryMetrics.get(0).value(report), palette, palette.accent);
        drawHighlightStat(canvas, 396, 742, summaryMetrics.get(1).label(report),
                summaryMetrics.get(1).value(report), palette, palette.accent2);
        drawHighlightStat(canvas, 674, 742, summaryMetrics.get(2).label(report),
                summaryMetrics.get(2).value(report), palette, palette.accent3);

        List<String[]> rows = infoRows(report);
        float rowHeight = 76f;
        float infoTop = summary.bottom + 66f;
        float infoHeight = 126f + Math.max(1, rows.size()) * rowHeight;
        drawRoundRect(canvas, new RectF(72, infoTop, 1008, infoTop + infoHeight), 34, palette.card, palette.line, 2f);
        drawMultiline(canvas, report.isBookScope() ? "本书高光" : "年度高光", 116, infoTop + 48, 520, cardTitlePaint, 1);
        drawInfoRows(canvas, rows, 116, infoTop + 126, 816, rowHeight, palette);

        float rhythmTop = infoTop + infoHeight + 58f;
        RectF rhythm = new RectF(72, rhythmTop, 1008, rhythmTop + 310f);
        drawRoundRect(canvas, rhythm, 34, palette.card, palette.line, 2f);
        drawMultiline(canvas, "12 个月的节奏", 116, rhythmTop + 44f, 560, cardTitlePaint, 1);
        drawMonthlyBars(canvas, report, palette, 116, rhythmTop + 128f, 846, 116, true);

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
        } else {
            addRow(rows, "Top 书籍", report.topBook);
            addRow(rows, "常读作者", report.topAuthor);
            addRow(rows, "常读标签", report.topTag);
            addRow(rows, "常读系列", report.topSeries);
        }
        if (rows.isEmpty()) {
            addRow(rows, "范围", report.rangeTitle);
        }
        return rows;
    }

    private void addRow(List<String[]> rows, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        rows.add(new String[]{label, value.trim()});
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

    private void drawMonthlyBars(Canvas canvas, AnnualReportData report, Palette palette, float left, float top,
                                 float width, float height, boolean bold) {
        int max = maxMonthlySeconds(report);
        float gap = bold ? 10f : 12f;
        float barWidth = (width - gap * 11f) / 12f;
        TextPaint monthPaint = textPaint(palette.mutedText, 20f, bold);
        Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baselinePaint.setColor(palette.line);
        baselinePaint.setStrokeWidth(2f);
        canvas.drawLine(left, top + height + 1, left + width, top + height + 1, baselinePaint);
        for (int i = 0; i < 12; i++) {
            int seconds = report.monthlySeconds == null ? 0 : Math.max(report.monthlySeconds[i], 0);
            float ratio = seconds <= 0 ? 0.04f : seconds / (float) max;
            float barHeight = Math.max(seconds <= 0 ? 7f : 12f, height * ratio);
            float x = left + i * (barWidth + gap);
            float y = top + height - barHeight;
            int color = i % 3 == 0 ? palette.accent : (i % 3 == 1 ? palette.accent2 : palette.accent3);
            drawRoundRect(canvas, new RectF(x, y, x + barWidth, top + height), bold ? 8 : 9, color, Color.TRANSPARENT, 0);
            drawMultiline(canvas, String.valueOf(i + 1), x - 4, top + height + 24, barWidth + 8, monthPaint, 1);
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
        return report.year + (report.isBookScope() ? " 单书阅读报告" : " 年度阅读报告");
    }

    private String quietSubtitle(AnnualReportData report) {
        if (report.isBookScope()) {
            return "这一年，你和这本书留下了清晰的阅读轨迹。";
        }
        return "这一年，你在书页里留下了清晰的阅读轨迹。";
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

    private int maxMonthlySeconds(AnnualReportData report) {
        int max = 1;
        if (report == null || report.monthlySeconds == null) {
            return max;
        }
        for (int value : report.monthlySeconds) {
            max = Math.max(max, Math.max(0, value));
        }
        return max;
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
