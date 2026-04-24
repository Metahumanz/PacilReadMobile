package com.metahumanz.pacilread.reader;

import android.text.TextPaint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ReaderLineJustifier {
    private static final float MIN_RESIDUAL_PX = 0.5f;

    private ReaderLineJustifier() {
    }

    static LineLayout layout(String lineText, float startX, float availableWidth, TextPaint paint, boolean allowJustify) {
        String safeText = lineText == null ? "" : lineText;
        List<TextUnit> units = splitToUnits(safeText);
        float naturalWidth = measureRunAdvance(safeText, safeText.length(), paint);
        float residualWidth = availableWidth - naturalWidth;
        float[] positions = new float[units.size()];
        int contentStartUnit = firstContentUnit(units);
        int contentUnitCount = units.size() - contentStartUnit;
        int spaceGapCount = countSpaceGaps(units, contentStartUnit);
        boolean useSpaceGaps = allowJustify
                && residualWidth > MIN_RESIDUAL_PX
                && contentUnitCount > 1
                && spaceGapCount > 1;
        int gapCount = useSpaceGaps ? spaceGapCount : Math.max(contentUnitCount - 1, 0);
        boolean justified = allowJustify && residualWidth > MIN_RESIDUAL_PX && gapCount > 0;
        float extraGap = justified ? residualWidth / gapCount : 0f;
        float distributedExtra = 0f;

        for (int i = 0; i < units.size(); i++) {
            TextUnit unit = units.get(i);
            positions[i] = startX + measureRunAdvance(safeText, unit.start, paint) + distributedExtra;
            if (!justified || i < contentStartUnit || i >= units.size() - 1) {
                continue;
            }
            if (useSpaceGaps) {
                if (unit.isOrdinarySpace()) {
                    distributedExtra += extraGap;
                }
            } else {
                distributedExtra += extraGap;
            }
        }

        float endX = units.isEmpty()
                ? startX
                : positions[units.size() - 1] + measureRunAdvance(safeText.substring(units.get(units.size() - 1).start, units.get(units.size() - 1).end), units.get(units.size() - 1).length(), paint);
        return new LineLayout(
                safeText,
                units,
                positions,
                startX,
                naturalWidth,
                residualWidth,
                endX,
                justified,
                useSpaceGaps,
                extraGap
        );
    }

    private static int firstContentUnit(List<TextUnit> units) {
        int index = 0;
        while (index < units.size() && units.get(index).isIndent()) {
            index++;
        }
        return index;
    }

    private static int countSpaceGaps(List<TextUnit> units, int contentStartUnit) {
        int count = 0;
        for (int i = Math.max(contentStartUnit, 0); i < units.size() - 1; i++) {
            if (units.get(i).isOrdinarySpace()) {
                count++;
            }
        }
        return count;
    }

    private static List<TextUnit> splitToUnits(String text) {
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        List<TextUnit> units = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            int start = index;
            index = consumeCluster(text, index);
            units.add(new TextUnit(text.substring(start, index), start, index));
        }
        return units;
    }

    private static int consumeCluster(String text, int start) {
        int index = start + Character.charCount(text.codePointAt(start));
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (isCombiningMark(codePoint) || isVariationSelector(codePoint) || isZeroWidthFormat(codePoint)) {
                index += Character.charCount(codePoint);
                if (isZeroWidthJoiner(codePoint) && index < text.length()) {
                    index += Character.charCount(text.codePointAt(index));
                }
                continue;
            }
            break;
        }
        return index;
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isVariationSelector(int codePoint) {
        return (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    }

    private static boolean isZeroWidthFormat(int codePoint) {
        return codePoint == 0x200C
                || codePoint == 0x200D
                || codePoint == 0x2060
                || codePoint == 0xFEFF;
    }

    private static boolean isZeroWidthJoiner(int codePoint) {
        return codePoint == 0x200D;
    }

    static float measureRunAdvance(CharSequence text, int offset, TextPaint paint) {
        if (text == null || text.length() == 0 || offset <= 0) {
            return 0f;
        }
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        return paint.getRunAdvance(text, 0, text.length(), 0, text.length(), false, safeOffset);
    }

    static final class LineLayout {
        private final String text;
        private final List<TextUnit> units;
        private final float[] positions;
        private final float startX;
        private final float naturalWidth;
        private final float residualWidth;
        private final float endX;
        private final boolean justified;
        private final boolean spaceGaps;
        private final float extraGap;

        private LineLayout(
                String text,
                List<TextUnit> units,
                float[] positions,
                float startX,
                float naturalWidth,
                float residualWidth,
                float endX,
                boolean justified,
                boolean spaceGaps,
                float extraGap
        ) {
            this.text = text;
            this.units = units;
            this.positions = positions;
            this.startX = startX;
            this.naturalWidth = naturalWidth;
            this.residualWidth = residualWidth;
            this.endX = endX;
            this.justified = justified;
            this.spaceGaps = spaceGaps;
            this.extraGap = extraGap;
        }

        String text() {
            return text;
        }

        int unitCount() {
            return units.size();
        }

        TextUnit unitAt(int index) {
            return units.get(index);
        }

        float unitX(int index) {
            return positions[index];
        }

        boolean isJustified() {
            return justified;
        }

        boolean usesSpaceGaps() {
            return spaceGaps;
        }

        float naturalWidth() {
            return naturalWidth;
        }

        float residualWidth() {
            return residualWidth;
        }

        float extraGap() {
            return extraGap;
        }

        float xForOffset(int offset, TextPaint paint) {
            int safeOffset = Math.max(0, Math.min(offset, text.length()));
            if (safeOffset <= 0 || units.isEmpty()) {
                return startX;
            }
            for (int i = 0; i < units.size(); i++) {
                TextUnit unit = units.get(i);
                if (safeOffset <= unit.start) {
                    return positions[i];
                }
                if (safeOffset < unit.end) {
                    return positions[i] + measureRunAdvance(unit.text, safeOffset - unit.start, paint);
                }
            }
            return endX;
        }
    }

    static final class TextUnit {
        final String text;
        final int start;
        final int end;

        TextUnit(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }

        int length() {
            return end - start;
        }

        boolean isOrdinarySpace() {
            return " ".equals(text);
        }

        boolean isIndent() {
            return " ".equals(text) || "\t".equals(text) || "\u3000".equals(text);
        }
    }
}
