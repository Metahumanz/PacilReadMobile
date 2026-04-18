package com.metahumanz.pacilreadmobile.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class JustifiedPageTextView extends TextView {
    private boolean fullJustifyEnabled = true;

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
        setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
            setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
        }
    }

    public void setFullJustifyEnabled(boolean enabled) {
        if (fullJustifyEnabled == enabled) {
            return;
        }
        fullJustifyEnabled = enabled;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (!fullJustifyEnabled || layout == null) {
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
            if (shouldJustify(layout, lineIndex, drawLine, paragraphEnd)) {
                drawJustifiedLine(canvas, drawLine, lineLeft, baseline, availableWidth, paint);
            } else {
                canvas.drawText(drawLine, lineLeft, baseline, paint);
            }
        }
        canvas.restoreToCount(saveCount);
    }

    private boolean shouldJustify(Layout layout, int lineIndex, String lineText, boolean paragraphEnd) {
        if (paragraphEnd) {
            return false;
        }
        return lineText.trim().length() > 1;
    }

    private void drawJustifiedLine(Canvas canvas, String lineText, float startX, float baseline, int availableWidth, TextPaint paint) {
        float x = startX;
        int indentEnd = 0;
        while (indentEnd < lineText.length()) {
            char c = lineText.charAt(indentEnd);
            if (c == ' ' || c == '\t' || c == '\u3000') {
                indentEnd++;
            } else {
                break;
            }
        }
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
        float naturalWidth = paint.measureText(content);
        float residualWidth = availableWidth - (x - startX) - naturalWidth;
        if (residualWidth <= 0.5f) {
            canvas.drawText(content, x, baseline, paint);
            return;
        }
        int spaceCount = 0;
        for (String unit : units) {
            if (" ".equals(unit)) {
                spaceCount++;
            }
        }
        if (spaceCount > 1) {
            float extraSpace = residualWidth / spaceCount;
            for (int i = 0; i < units.size(); i++) {
                String unit = units.get(i);
                canvas.drawText(unit, x, baseline, paint);
                x += paint.measureText(unit);
                if (" ".equals(unit) && i < units.size() - 1) {
                    x += extraSpace;
                }
            }
            return;
        }
        int gapCount = Math.max(units.size() - 1, 1);
        float extraGap = residualWidth / gapCount;
        for (int i = 0; i < units.size(); i++) {
            String unit = units.get(i);
            canvas.drawText(unit, x, baseline, paint);
            x += paint.measureText(unit);
            if (i < units.size() - 1) {
                x += extraGap;
            }
        }
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
