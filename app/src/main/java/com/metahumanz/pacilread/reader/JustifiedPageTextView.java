package com.metahumanz.pacilread.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class JustifiedPageTextView extends TextView {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
            setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
        }
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
            if (start >= end) {
                continue;
            }
            String rawLine = text.subSequence(start, end).toString();
            boolean paragraphEnd = rawLine.endsWith("\n") || rawLine.endsWith("\r");
            String drawLine = trimLineBreaks(rawLine);
            if (drawLine.isEmpty()) {
                continue;
            }
            float lineLeft = layout.getLineLeft(lineIndex);
            float baseline = layout.getLineBaseline(lineIndex);
            float lineAvailableWidth = Math.max(0f, availableWidth - Math.max(0f, lineLeft));

            if (shouldJustify(drawLine, paragraphEnd, lineAvailableWidth, paint)) {
                drawJustifiedLineWithHighlight(canvas, drawLine, lineLeft, baseline, lineAvailableWidth, paint, text, start, end);
            } else {
                drawLineHighlight(canvas, lineLeft, baseline, paint, text, start, end);
                canvas.drawText(drawLine, lineLeft, baseline, paint);
            }
        }
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
            drawLineHighlight(canvas, layout.getLineLeft(lineIndex), layout.getLineBaseline(lineIndex), paint, text, start, end);
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
        float[] xPositions = computeJustifiedPositions(lineText, startX, availableWidth, paint);
        int indentEnd = countIndent(lineText);
        float x = startX;
        if (indentEnd > 0) {
            String indent = lineText.substring(0, indentEnd);
            canvas.drawText(indent, x, baseline, paint);
            x += paint.measureText(indent);
        }
        String content = lineText.substring(indentEnd);
        if (content.length() <= 1) {
            canvas.drawText(content, x, baseline, paint);
            return;
        }
        List<String> units = splitToUnits(content);
        for (int i = 0; i < units.size(); i++) {
            canvas.drawText(units.get(i), xPositions[i], baseline, paint);
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
        List<String> units = splitToUnits(content);

        float x = lineLeft;
        float indentWidth = 0;
        if (indentEnd > 0) {
            String indent = lineText.substring(0, indentEnd);
            canvas.drawText(indent, x, baseline, paint);
            indentWidth = paint.measureText(indent);
            x += indentWidth;
        }
        if (content.length() <= 1) {
            if (content.length() == 1) {
                canvas.drawText(content, x, baseline, paint);
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
            int unitCharStart = contentOffset + unitOffsetInContent(content, i);
            int unitCharEnd = unitCharStart + units.get(i).length();

            int hlStart = Math.max(highlightStart, unitCharStart);
            int hlEnd = Math.min(highlightEnd, unitCharEnd);
            if (hlStart < hlEnd) {
                float partialStartOffset = (hlStart == unitCharStart) ? 0 : paint.measureText(fullText, unitCharStart, hlStart);
                float partialWidth = paint.measureText(fullText, hlStart, hlEnd);
                canvas.drawRect(unitX + partialStartOffset, highlightTop, unitX + partialStartOffset + partialWidth, highlightBottom, highlightPaint);
            }

            canvas.drawText(units.get(i), unitX, baseline, paint);
        }
    }

    private int unitOffsetInContent(String content, int unitIndex) {
        List<String> units = splitToUnits(content);
        int offset = 0;
        for (int i = 0; i < unitIndex && i < units.size(); i++) {
            offset += units.get(i).length();
        }
        return offset;
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
        List<String> units = splitToUnits(content);
        float naturalWidth = paint.measureText(content);
        float residualWidth = availableWidth - indentWidth - naturalWidth;

        float[] positions = new float[units.size()];
        float x = contentStartX;

        if (residualWidth <= 0.5f) {
            for (int i = 0; i < units.size(); i++) {
                positions[i] = x;
                x += paint.measureText(units.get(i));
            }
            return positions;
        }

        int spaceCount = 0;
        for (String unit : units) {
            if (" ".equals(unit)) spaceCount++;
        }

        if (spaceCount > 1) {
            float extraSpace = residualWidth / spaceCount;
            for (int i = 0; i < units.size(); i++) {
                positions[i] = x;
                x += paint.measureText(units.get(i));
                if (" ".equals(units.get(i)) && i < units.size() - 1) {
                    x += extraSpace;
                }
            }
            return positions;
        }

        int gapCount = Math.max(units.size() - 1, 1);
        float extraGap = residualWidth / gapCount;
        for (int i = 0; i < units.size(); i++) {
            positions[i] = x;
            x += paint.measureText(units.get(i));
            if (i < units.size() - 1) {
                x += extraGap;
            }
        }
        return positions;
    }

    private List<String> splitToUnits(String text) {
        List<String> units = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            units.add(new String(Character.toChars(codePoint)));
            index += Character.charCount(codePoint);
        }
        return units;
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
}