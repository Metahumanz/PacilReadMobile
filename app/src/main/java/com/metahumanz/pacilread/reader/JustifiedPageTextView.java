package com.metahumanz.pacilread.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AlignmentSpan;
import android.text.style.LineHeightSpan;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

public class JustifiedPageTextView extends AppCompatTextView {
    private boolean fullJustifyEnabled = true;
    private boolean treatFinalLineAsParagraphEnd = true;
    private int highlightStart = -1;
    private int highlightEnd = -1;
    private final Paint highlightPaint = new Paint();

    public JustifiedPageTextView(Context context) {
        super(context);
        init();
    }

    public JustifiedPageTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JustifiedPageTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        setIncludeFontPadding(false);
        highlightPaint.setColor(0x40FFC107);
        highlightPaint.setStyle(Paint.Style.FILL);
    }

    public void setFullJustifyEnabled(boolean enabled) {
        if (fullJustifyEnabled == enabled) {
            return;
        }
        fullJustifyEnabled = enabled;
        invalidate();
    }

    public void setTreatFinalLineAsParagraphEnd(boolean enabled) {
        if (treatFinalLineAsParagraphEnd == enabled) {
            return;
        }
        treatFinalLineAsParagraphEnd = enabled;
        invalidate();
    }

    public void setHighlightRange(int start, int end) {
        this.highlightStart = start;
        this.highlightEnd = end;
        invalidate();
    }

    public void clearHighlight() {
        this.highlightStart = -1;
        this.highlightEnd = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (!fullJustifyEnabled || layout == null) {
            drawHighlight(canvas, layout);
            super.onDraw(canvas);
            return;
        }
        TextPaint paint = getPaint();
        paint.setColor(getCurrentTextColor());
        CharSequence text = getText();
        int lineCount = layout.getLineCount();
        int availableWidth = getWidth() - getTotalPaddingLeft() - getTotalPaddingRight();
        int saveCount = canvas.save();
        canvas.translate(getTotalPaddingLeft(), getExtendedPaddingTop());
        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            int start = layout.getLineStart(lineIndex);
            int end = layout.getLineEnd(lineIndex);
            int visibleEnd = layout.getLineVisibleEnd(lineIndex);
            if (start >= visibleEnd) {
                continue;
            }
            if (shouldUsePlatformLine(text, start, end)) {
                float platformLineLeft = layout.getLineLeft(lineIndex);
                drawLineHighlight(canvas, platformLineLeft, layout.getLineBaseline(lineIndex), paint, text, start, visibleEnd);
                drawPlatformLine(canvas, layout, lineIndex, availableWidth);
                continue;
            }
            boolean paragraphEnd = hasTrailingLineBreak(text, visibleEnd, end)
                    || isFinalLineParagraphEnd(text, visibleEnd, lineIndex, lineCount);
            String rawLine = text.subSequence(start, visibleEnd).toString();
            String drawLine = trimLineBreaks(rawLine);
            if (drawLine.isEmpty()) {
                continue;
            }
            float lineLeft = lineContentLeft(layout, lineIndex);
            float baseline = layout.getLineBaseline(lineIndex);
            float lineAvailableWidth = lineContentWidth(layout, lineIndex, availableWidth);

            ReaderLineJustifier.LineLayout lineLayout = ReaderLineJustifier.layout(
                    drawLine,
                    lineLeft,
                    lineAvailableWidth,
                    paint,
                    shouldJustify(drawLine, paragraphEnd, lineAvailableWidth, paint)
            );
            if (lineLayout.isJustified()) {
                drawLineLayoutWithHighlight(canvas, lineLayout, baseline, paint, start);
            } else {
                drawLineHighlight(canvas, lineLeft, baseline, paint, text, start, visibleEnd);
                canvas.drawText(drawLine, lineLeft, baseline, paint);
            }
        }
        canvas.restoreToCount(saveCount);
    }

    private boolean shouldUsePlatformLine(CharSequence text, int lineStart, int lineEnd) {
        if (!(text instanceof Spanned)) {
            return false;
        }
        Spanned spanned = (Spanned) text;
        return spanned.getSpans(lineStart, lineEnd, ReaderTitleSpan.class).length > 0
                || spanned.getSpans(lineStart, lineEnd, AlignmentSpan.class).length > 0
                || spanned.getSpans(lineStart, lineEnd, LineHeightSpan.class).length > 0;
    }

    private float lineContentLeft(Layout layout, int lineIndex) {
        return Math.max(0f, layout.getParagraphLeft(lineIndex));
    }

    private float lineContentWidth(Layout layout, int lineIndex, int availableWidth) {
        float paragraphLeft = lineContentLeft(layout, lineIndex);
        float paragraphRight = Math.min(availableWidth, layout.getParagraphRight(lineIndex));
        return Math.max(0f, paragraphRight - paragraphLeft);
    }

    private void drawPlatformLine(Canvas canvas, Layout layout, int lineIndex, int availableWidth) {
        int saveCount = canvas.save();
        canvas.clipRect(0, layout.getLineTop(lineIndex), availableWidth, layout.getLineBottom(lineIndex));
        layout.draw(canvas);
        canvas.restoreToCount(saveCount);
    }

    private void drawHighlight(Canvas canvas, Layout layout) {
        if (highlightStart < 0 || highlightEnd <= highlightStart || layout == null) {
            return;
        }
        TextPaint paint = getPaint();
        CharSequence text = getText();
        int saveCount = canvas.save();
        canvas.translate(getTotalPaddingLeft(), getExtendedPaddingTop());
        int lineCount = layout.getLineCount();
        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            int start = layout.getLineStart(lineIndex);
            int end = layout.getLineEnd(lineIndex);
            int visibleEnd = layout.getLineVisibleEnd(lineIndex);
            if (start >= visibleEnd) {
                continue;
            }
            float lineLeft = layout.getLineLeft(lineIndex);
            drawLineHighlight(canvas, lineLeft, layout.getLineBaseline(lineIndex), paint, text, start, visibleEnd);
        }
        canvas.restoreToCount(saveCount);
    }

    private void drawLineHighlight(Canvas canvas, float lineLeft, float baseline, Paint paint, CharSequence text, int lineStart, int lineEnd) {
        if (highlightStart < 0 || highlightEnd <= highlightStart) {
            return;
        }
        int lineHighlightStart = Math.max(highlightStart, lineStart);
        int lineHighlightEnd = Math.min(highlightEnd, lineEnd);
        if (lineHighlightStart < lineHighlightEnd) {
            float startX = lineLeft + paint.measureText(text, lineStart, lineHighlightStart);
            float endX = startX + paint.measureText(text, lineHighlightStart, lineHighlightEnd);
            float top = baseline - paint.ascent() - 4;
            float bottom = baseline - paint.descent() + 4;
            canvas.drawRect(startX, top, endX, bottom, highlightPaint);
        }
    }

    private boolean shouldJustify(String lineText, boolean paragraphEnd, float availableWidth, TextPaint paint) {
        if (paragraphEnd) {
            return false;
        }
        String trimmedLine = lineText.trim();
        return trimmedLine.length() > 1 && availableWidth > 0f;
    }

    private void drawLineLayoutWithHighlight(Canvas canvas, ReaderLineJustifier.LineLayout lineLayout, float baseline, TextPaint paint, int lineStart) {
        float highlightTop = baseline - paint.ascent() - 4;
        float highlightBottom = baseline - paint.descent() + 4;
        if (highlightStart >= 0 && highlightEnd > highlightStart) {
            int lineHighlightStart = Math.max(highlightStart - lineStart, 0);
            int lineHighlightEnd = Math.min(highlightEnd - lineStart, lineLayout.text().length());
            if (lineHighlightStart < lineHighlightEnd) {
                float startX = lineLayout.xForOffset(lineHighlightStart, paint);
                float endX = lineLayout.xForOffset(lineHighlightEnd, paint);
                canvas.drawRect(startX, highlightTop, endX, highlightBottom, highlightPaint);
            }
        }

        String text = lineLayout.text();
        for (int i = 0; i < lineLayout.unitCount(); i++) {
            ReaderLineJustifier.TextUnit unit = lineLayout.unitAt(i);
            canvas.drawText(text, unit.start, unit.end, lineLayout.unitX(i), baseline, paint);
        }
    }

    private String trimLineBreaks(String line) {
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c == '\n' || c == '\r') {
                end--;
            } else {
                break;
            }
        }
        return line.substring(0, end);
    }

    private boolean hasTrailingLineBreak(CharSequence text, int visibleEnd, int lineEnd) {
        int safeVisibleEnd = Math.max(0, Math.min(visibleEnd, text.length()));
        int safeLineEnd = Math.max(safeVisibleEnd, Math.min(lineEnd, text.length()));
        for (int i = safeVisibleEnd; i < safeLineEnd; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    private boolean isFinalLineParagraphEnd(CharSequence text, int visibleEnd, int lineIndex, int lineCount) {
        if (!treatFinalLineAsParagraphEnd || lineIndex != lineCount - 1) {
            return false;
        }
        int safeVisibleEnd = Math.max(0, Math.min(visibleEnd, text.length()));
        for (int i = safeVisibleEnd; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }
}
