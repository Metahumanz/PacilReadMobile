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

import java.util.ArrayList;
import java.util.List;

public class JustifiedPageTextView extends AppCompatTextView {
    private static final float JUSTIFY_MIN_FILL_RATIO = 0.9f;
    private static final float JUSTIFY_MAX_RESIDUAL_EM = 2.2f;

    private boolean fullJustifyEnabled = true;
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
            boolean paragraphEnd = hasTrailingLineBreak(text, visibleEnd, end);
            String rawLine = text.subSequence(start, visibleEnd).toString();
            String drawLine = trimLineBreaks(rawLine);
            if (drawLine.isEmpty()) {
                continue;
            }
            float lineLeft = lineContentLeft(layout, lineIndex);
            float baseline = layout.getLineBaseline(lineIndex);
            float lineAvailableWidth = lineContentWidth(layout, lineIndex, availableWidth);

            if (shouldJustify(drawLine, paragraphEnd, lineAvailableWidth, paint)) {
                drawJustifiedLineWithHighlight(canvas, drawLine, lineLeft, baseline, lineAvailableWidth, paint, text, start, visibleEnd);
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
        if (trimmedLine.length() <= 1 || availableWidth <= 0f) {
            return false;
        }
        float naturalWidth = paint.measureText(lineText);
        float residualWidth = availableWidth - naturalWidth;
        if (residualWidth <= 0.5f) {
            return true;
        }
        float fillRatio = naturalWidth / availableWidth;
        float residualLimit = Math.max(paint.getTextSize() * JUSTIFY_MAX_RESIDUAL_EM, 1f);
        return fillRatio >= JUSTIFY_MIN_FILL_RATIO && residualWidth <= residualLimit;
    }

    private void drawJustifiedLine(Canvas canvas, String lineText, float startX, float baseline, float availableWidth, TextPaint paint) {
        int indentEnd = countIndent(lineText);
        float[] xPositions = computeJustifiedPositions(lineText, startX, availableWidth, paint);
        if (indentEnd > 0) {
            String indent = lineText.substring(0, indentEnd);
            canvas.drawText(indent, startX, baseline, paint);
        }
        String content = lineText.substring(indentEnd);
        if (content.length() <= 1) {
            float contentStartX = startX + (indentEnd > 0 ? paint.measureText(lineText.substring(0, indentEnd)) : 0f);
            canvas.drawText(content, contentStartX, baseline, paint);
            return;
        }
        List<TextUnit> units = splitToUnits(content);
        for (int i = 0; i < units.size(); i++) {
            canvas.drawText(units.get(i).text, xPositions[i], baseline, paint);
        }
    }

    private void drawJustifiedLineWithHighlight(Canvas canvas, String lineText, float lineLeft, float baseline, float lineAvailableWidth, TextPaint paint, CharSequence fullText, int lineStart, int lineEnd) {
        if (highlightStart < 0 || highlightEnd <= highlightStart) {
            drawJustifiedLine(canvas, lineText, lineLeft, baseline, lineAvailableWidth, paint);
            return;
        }

        float[] xPositions = computeJustifiedPositions(lineText, lineLeft, lineAvailableWidth, paint);
        int indentEnd = countIndent(lineText);
        String content = lineText.substring(indentEnd);
        List<TextUnit> units = splitToUnits(content);

        if (indentEnd > 0) {
            String indent = lineText.substring(0, indentEnd);
            canvas.drawText(indent, lineLeft, baseline, paint);
        }
        if (content.length() <= 1) {
            if (content.length() == 1) {
                float contentStartX = lineLeft + (indentEnd > 0 ? paint.measureText(lineText.substring(0, indentEnd)) : 0f);
                canvas.drawText(content, contentStartX, baseline, paint);
            }
            return;
        }

        float highlightTop = baseline - paint.ascent() - 4;
        float highlightBottom = baseline - paint.descent() + 4;
        int contentOffset = lineStart + indentEnd;

        // Draw highlight for indent portion if it overlaps
        int indentHlStart = Math.max(highlightStart, lineStart);
        int indentHlEnd = Math.min(highlightEnd, lineStart + indentEnd);
        if (indentHlStart < indentHlEnd) {
            float hlStartX = lineLeft + paint.measureText(fullText, lineStart, indentHlStart);
            float hlEndX = hlStartX + paint.measureText(fullText, indentHlStart, indentHlEnd);
            canvas.drawRect(hlStartX, highlightTop, hlEndX, highlightBottom, highlightPaint);
        }

        // Draw content units with per-unit highlight tracking
        for (int i = 0; i < units.size(); i++) {
            float unitX = xPositions[i];
            TextUnit unit = units.get(i);
            int unitCharStart = contentOffset + unit.start;
            int unitCharEnd = contentOffset + unit.end;

            int hlStart = Math.max(highlightStart, unitCharStart);
            int hlEnd = Math.min(highlightEnd, unitCharEnd);
            if (hlStart < hlEnd) {
                float partialStartOffset = measureRunAdvance(unit.text, hlStart - unitCharStart, paint);
                float partialEndOffset = measureRunAdvance(unit.text, hlEnd - unitCharStart, paint);
                canvas.drawRect(unitX + partialStartOffset, highlightTop, unitX + partialEndOffset, highlightBottom, highlightPaint);
            }

            canvas.drawText(unit.text, unitX, baseline, paint);
        }
    }

    private int countIndent(String lineText) {
        int indentEnd = 0;
        while (indentEnd < lineText.length()) {
            char c = lineText.charAt(indentEnd);
            if (c == ' ' || c == '\t' || c == '\u3000') {
                indentEnd++;
            } else {
                break;
            }
        }
        return indentEnd;
    }

    private float[] computeJustifiedPositions(String lineText, float startX, float availableWidth, TextPaint paint) {
        int indentEnd = countIndent(lineText);
        String content = lineText.substring(indentEnd);
        float indentWidth = indentEnd > 0 ? paint.measureText(lineText.substring(0, indentEnd)) : 0;
        float contentStartX = startX + indentWidth;

        if (content.length() <= 1) {
            return new float[]{ contentStartX };
        }
        List<TextUnit> units = splitToUnits(content);
        float naturalWidth = measureRunAdvance(content, content.length(), paint);
        float residualWidth = availableWidth - indentWidth - naturalWidth;

        float[] positions = new float[units.size()];
        int spaceCount = 0;
        for (TextUnit unit : units) {
            if (unit.isSpace()) {
                spaceCount++;
            }
        }

        float distributedExtra = 0f;
        float extraSpace = spaceCount > 1 ? residualWidth / spaceCount : 0f;
        int gapCount = Math.max(units.size() - 1, 1);
        float extraGap = spaceCount > 1 ? 0f : residualWidth / gapCount;
        for (int i = 0; i < units.size(); i++) {
            TextUnit unit = units.get(i);
            positions[i] = contentStartX + measureRunAdvance(content, unit.start, paint) + distributedExtra;
            if (residualWidth <= 0.5f) {
                continue;
            }
            if (spaceCount > 1) {
                if (unit.isSpace() && i < units.size() - 1) {
                    distributedExtra += extraSpace;
                }
            } else if (i < units.size() - 1) {
                distributedExtra += extraGap;
            }
        }
        return positions;
    }

    private List<TextUnit> splitToUnits(String text) {
        List<TextUnit> units = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int nextIndex = index + Character.charCount(codePoint);
            units.add(new TextUnit(new String(Character.toChars(codePoint)), index, nextIndex));
            index = nextIndex;
        }
        return units;
    }

    private float measureRunAdvance(CharSequence text, int offset, TextPaint paint) {
        if (text == null || text.length() == 0 || offset <= 0) {
            return 0f;
        }
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        return paint.getRunAdvance(text, 0, text.length(), 0, text.length(), false, safeOffset);
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

    private static final class TextUnit {
        final String text;
        final int start;
        final int end;

        TextUnit(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }

        boolean isSpace() {
            return " ".equals(text);
        }
    }
}
