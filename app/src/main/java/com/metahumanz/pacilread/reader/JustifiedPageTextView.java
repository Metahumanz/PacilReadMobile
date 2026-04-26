package com.metahumanz.pacilread.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
    private int selectionHighlightStart = -1;
    private int selectionHighlightEnd = -1;
    private final Paint highlightPaint = new Paint();
    private final Paint selectionHighlightPaint = new Paint();
    private final Paint handleFillPaint = new Paint();
    private final Paint handleStrokePaint = new Paint();
    private float handleRadius;
    private float handleTouchRadius;
    private final RectF startHandleBounds = new RectF();
    private final RectF endHandleBounds = new RectF();

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
        selectionHighlightPaint.setColor(0x663B82F6);
        selectionHighlightPaint.setStyle(Paint.Style.FILL);
        float density = getResources().getDisplayMetrics().density;
        handleRadius = density * 5f;
        handleTouchRadius = density * 14f;
        handleFillPaint.setColor(0xFF3B82F6);
        handleFillPaint.setStyle(Paint.Style.FILL);
        handleFillPaint.setAntiAlias(true);
        handleStrokePaint.setColor(0xFFFFFFFF);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(density * 1.5f);
        handleStrokePaint.setAntiAlias(true);
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

    public void setSelectionHighlightRange(int start, int end) {
        this.selectionHighlightStart = start;
        this.selectionHighlightEnd = end;
        invalidate();
    }

    public void clearSelectionHighlight() {
        this.selectionHighlightStart = -1;
        this.selectionHighlightEnd = -1;
        invalidate();
    }

    public int offsetForTouch(float x, float y) {
        Layout layout = getLayout();
        CharSequence text = getText();
        if (layout == null || text == null || text.length() == 0) {
            return 0;
        }
        float contentX = x - getTotalPaddingLeft();
        float contentY = y - getExtendedPaddingTop();
        int lineIndex = Math.max(0, Math.min(layout.getLineForVertical(Math.round(contentY)), layout.getLineCount() - 1));
        int start = layout.getLineStart(lineIndex);
        int end = layout.getLineEnd(lineIndex);
        int visibleEnd = layout.getLineVisibleEnd(lineIndex);
        if (start >= visibleEnd) {
            return Math.max(0, Math.min(start, text.length()));
        }
        if (!fullJustifyEnabled || shouldUsePlatformLine(text, start, end)) {
            return Math.max(start, Math.min(layout.getOffsetForHorizontal(lineIndex, contentX), visibleEnd));
        }
        boolean paragraphEnd = hasTrailingLineBreak(text, visibleEnd, end)
                || isFinalLineParagraphEnd(text, visibleEnd, lineIndex, layout.getLineCount());
        String rawLine = text.subSequence(start, visibleEnd).toString();
        String drawLine = trimLineBreaks(rawLine);
        if (drawLine.isEmpty()) {
            return start;
        }
        TextPaint paint = getPaint();
        int availableWidth = getWidth() - getTotalPaddingLeft() - getTotalPaddingRight();
        float lineLeft = lineContentLeft(layout, lineIndex);
        float lineAvailableWidth = lineContentWidth(layout, lineIndex, availableWidth);
        ReaderLineJustifier.LineLayout lineLayout = ReaderLineJustifier.layout(
                drawLine,
                lineLeft,
                lineAvailableWidth,
                paint,
                shouldJustify(drawLine, paragraphEnd, lineAvailableWidth, paint)
        );
        return Math.max(start, Math.min(start + lineLayout.offsetForX(contentX, paint), visibleEnd));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (!fullJustifyEnabled || layout == null) {
            drawHighlight(canvas, layout);
            super.onDraw(canvas);
            if (hasSelectionHighlight()) {
                int save = canvas.save();
                canvas.translate(getTotalPaddingLeft(), getExtendedPaddingTop());
                drawSelectionHandles(canvas, layout);
                canvas.restoreToCount(save);
            }
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
                drawLineHighlight(canvas, layout, lineIndex, layout.getLineBaseline(lineIndex), paint, text, start, visibleEnd);
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
                drawLineLayoutWithHighlight(canvas, lineLayout, layout, lineIndex, baseline, paint, start);
            } else {
                drawLineHighlightManual(canvas, lineLeft, layout, lineIndex, paint, text, start, visibleEnd);
                canvas.drawText(drawLine, lineLeft, baseline, paint);
            }
        }
        if (hasSelectionHighlight()) {
            drawSelectionHandles(canvas, layout);
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
        if (layout == null || !hasAnyHighlight()) {
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
            drawLineHighlight(canvas, layout, lineIndex, layout.getLineBaseline(lineIndex), paint, text, start, visibleEnd);
        }
        canvas.restoreToCount(saveCount);
    }

    // For platform-drawn lines (layout.draw): use getPrimaryHorizontal
    private void drawLineHighlight(Canvas canvas, Layout layout, int lineIndex, float baseline, Paint paint, CharSequence text, int lineStart, int lineEnd) {
        drawLineHighlightRangePlatform(canvas, layout, lineIndex, paint, text, lineStart, lineEnd, highlightStart, highlightEnd, highlightPaint);
        drawLineHighlightRangePlatform(canvas, layout, lineIndex, paint, text, lineStart, lineEnd, selectionHighlightStart, selectionHighlightEnd, selectionHighlightPaint);
    }

    private void drawLineHighlightRangePlatform(
            Canvas canvas, Layout layout, int lineIndex,
            Paint paint, CharSequence text, int lineStart, int lineEnd,
            int rangeStart, int rangeEnd, Paint rangePaint) {
        if (rangeStart < 0 || rangeEnd <= rangeStart) return;
        int lineHighlightStart = Math.max(rangeStart, lineStart);
        int lineHighlightEnd = Math.min(rangeEnd, lineEnd);
        if (lineHighlightStart < lineHighlightEnd) {
            float startX = layout.getPrimaryHorizontal(lineHighlightStart);
            float endX = layout.getPrimaryHorizontal(lineHighlightEnd);
            float top = layout.getLineTop(lineIndex);
            float bottom = layout.getLineBottom(lineIndex);
            canvas.drawRect(startX, top, endX, bottom, rangePaint);
        }
    }

    // For manually-drawn non-justified lines (canvas.drawText): use lineLeft + measureText
    private void drawLineHighlightManual(Canvas canvas, float lineLeft, Layout layout, int lineIndex,
            Paint paint, CharSequence text, int lineStart, int lineEnd) {
        drawLineHighlightRangeManual(canvas, lineLeft, layout, lineIndex, paint, text, lineStart, lineEnd,
                highlightStart, highlightEnd, highlightPaint);
        drawLineHighlightRangeManual(canvas, lineLeft, layout, lineIndex, paint, text, lineStart, lineEnd,
                selectionHighlightStart, selectionHighlightEnd, selectionHighlightPaint);
    }

    private void drawLineHighlightRangeManual(
            Canvas canvas, float lineLeft, Layout layout, int lineIndex,
            Paint paint, CharSequence text, int lineStart, int lineEnd,
            int rangeStart, int rangeEnd, Paint rangePaint) {
        if (rangeStart < 0 || rangeEnd <= rangeStart) return;
        int lineHighlightStart = Math.max(rangeStart, lineStart);
        int lineHighlightEnd = Math.min(rangeEnd, lineEnd);
        if (lineHighlightStart < lineHighlightEnd) {
            float startX = lineLeft + paint.measureText(text, lineStart, lineHighlightStart);
            float endX = lineLeft + paint.measureText(text, lineStart, lineHighlightEnd);
            float top = layout.getLineTop(lineIndex);
            float bottom = layout.getLineBottom(lineIndex);
            canvas.drawRect(startX, top, endX, bottom, rangePaint);
        }
    }

    private boolean shouldJustify(String lineText, boolean paragraphEnd, float availableWidth, TextPaint paint) {
        if (paragraphEnd) {
            return false;
        }
        String trimmedLine = lineText.trim();
        return trimmedLine.length() > 1 && availableWidth > 0f;
    }

    private void drawLineLayoutWithHighlight(Canvas canvas, ReaderLineJustifier.LineLayout lineLayout, Layout layout, int lineIndex, float baseline, TextPaint paint, int lineStart) {
        float highlightTop = layout.getLineTop(lineIndex);
        float highlightBottom = layout.getLineBottom(lineIndex);
        drawLineLayoutHighlightRange(canvas, lineLayout, highlightTop, highlightBottom, paint, lineStart, highlightStart, highlightEnd, highlightPaint);
        drawLineLayoutHighlightRange(canvas, lineLayout, highlightTop, highlightBottom, paint, lineStart, selectionHighlightStart, selectionHighlightEnd, selectionHighlightPaint);

        String text = lineLayout.text();
        for (int i = 0; i < lineLayout.unitCount(); i++) {
            ReaderLineJustifier.TextUnit unit = lineLayout.unitAt(i);
            canvas.drawText(text, unit.start, unit.end, lineLayout.unitX(i), baseline, paint);
        }
    }

    private void drawLineLayoutHighlightRange(
            Canvas canvas,
            ReaderLineJustifier.LineLayout lineLayout,
            float top,
            float bottom,
            TextPaint paint,
            int lineStart,
            int rangeStart,
            int rangeEnd,
            Paint rangePaint
    ) {
        if (rangeStart < 0 || rangeEnd <= rangeStart) {
            return;
        }
        int lineHighlightStart = Math.max(rangeStart - lineStart, 0);
        int lineHighlightEnd = Math.min(rangeEnd - lineStart, lineLayout.text().length());
        if (lineHighlightStart < lineHighlightEnd) {
            float startX = lineLayout.xForOffset(lineHighlightStart, paint);
            float endX = lineLayout.xForOffset(lineHighlightEnd, paint);
            canvas.drawRect(startX, top, endX, bottom, rangePaint);
        }
    }

    private boolean hasAnyHighlight() {
        return (highlightStart >= 0 && highlightEnd > highlightStart)
                || (selectionHighlightStart >= 0 && selectionHighlightEnd > selectionHighlightStart);
    }

    private boolean hasSelectionHighlight() {
        return selectionHighlightStart >= 0 && selectionHighlightEnd > selectionHighlightStart;
    }

    private void drawSelectionHandles(Canvas canvas, Layout layout) {
        if (layout == null) return;
        CharSequence text = getText();
        if (text == null) return;

        int[] screenPos = new int[2];
        getLocationOnScreen(screenPos);
        float padLeft = getTotalPaddingLeft();
        float padTop = getExtendedPaddingTop();

        // Start handle
        drawHandleAtOffset(canvas, layout, text, screenPos, padLeft, padTop, selectionHighlightStart, startHandleBounds);

        // End handle
        drawHandleAtOffset(canvas, layout, text, screenPos, padLeft, padTop, selectionHighlightEnd, endHandleBounds);
    }

    private void drawHandleAtOffset(Canvas canvas, Layout layout, CharSequence text,
            int[] screenPos, float padLeft, float padTop, int offset, RectF outBounds) {
        float canvasX = canvasXForOffset(layout, text, offset);
        int lineIndex;
        if (offset <= 0) {
            lineIndex = 0;
        } else if (offset >= text.length()) {
            lineIndex = layout.getLineCount() - 1;
        } else {
            lineIndex = layout.getLineForOffset(offset);
        }
        float canvasY = layout.getLineBottom(lineIndex);

        // Draw the handle circle
        canvas.drawCircle(canvasX, canvasY, handleRadius, handleStrokePaint);
        canvas.drawCircle(canvasX, canvasY, handleRadius - handleStrokePaint.getStrokeWidth(), handleFillPaint);

        // Store screen-space bounds for hit testing
        float screenX = screenPos[0] + padLeft + canvasX;
        float screenY = screenPos[1] + padTop + canvasY;
        outBounds.set(screenX - handleTouchRadius, screenY - handleTouchRadius,
                screenX + handleTouchRadius, screenY + handleTouchRadius);
    }

    private float canvasXForOffset(Layout layout, CharSequence text, int offset) {
        if (offset < 0 || offset > text.length()) return 0;
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int lineIndex;
        if (safeOffset >= text.length()) {
            lineIndex = Math.max(0, layout.getLineCount() - 1);
        } else {
            lineIndex = layout.getLineForOffset(safeOffset);
        }
        int lineStart = layout.getLineStart(lineIndex);
        int lineEnd = layout.getLineEnd(lineIndex);

        // Platform-drawn lines: getPrimaryHorizontal matches layout.draw()
        if (!fullJustifyEnabled || shouldUsePlatformLine(text, lineStart, lineEnd)) {
            return layout.getPrimaryHorizontal(offset);
        }

        // Manual line: compute using same logic as onDraw
        int visibleEnd = layout.getLineVisibleEnd(lineIndex);
        if (lineStart >= visibleEnd) return 0;

        boolean paragraphEnd = hasTrailingLineBreak(text, visibleEnd, lineEnd)
                || isFinalLineParagraphEnd(text, visibleEnd, lineIndex, layout.getLineCount());
        String rawLine = text.subSequence(lineStart, visibleEnd).toString();
        String drawLine = trimLineBreaks(rawLine);
        if (drawLine.isEmpty()) return 0;

        float lineLeft = lineContentLeft(layout, lineIndex);
        TextPaint paint = getPaint();
        int availableWidth = getWidth() - getTotalPaddingLeft() - getTotalPaddingRight();
        float lineAvailableWidth = lineContentWidth(layout, lineIndex, availableWidth);

        ReaderLineJustifier.LineLayout lineLayout = ReaderLineJustifier.layout(
                drawLine, lineLeft, lineAvailableWidth, paint,
                shouldJustify(drawLine, paragraphEnd, lineAvailableWidth, paint));

        if (lineLayout.isJustified()) {
            int offsetInLine = Math.max(0, Math.min(offset - lineStart, drawLine.length()));
            return lineLayout.xForOffset(offsetInLine, paint);
        }
        return lineLeft + paint.measureText(text, lineStart, offset);
    }

    public RectF getSelectionHandleScreenBounds(int offset) {
        if (offset == selectionHighlightStart) {
            return startHandleBounds.isEmpty() ? null : new RectF(startHandleBounds);
        }
        if (offset == selectionHighlightEnd) {
            return endHandleBounds.isEmpty() ? null : new RectF(endHandleBounds);
        }
        return null;
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
