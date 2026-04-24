package com.metahumanz.pacilread.reader;

import android.text.TextPaint;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ReaderLineJustifierInstrumentedTest {
    @Test
    public void layout_appliesBaseLetterSpacingBeforeJustificationExtra() {
        TextPaint paint = newPaint();
        String text = "一二三四";
        float naturalWithoutSpacing = ReaderLineJustifier.layout(text, 0f, 400f, paint, false).naturalWidth();

        paint.setLetterSpacing(0.2f);
        ReaderLineJustifier.LineLayout natural = ReaderLineJustifier.layout(text, 0f, 400f, paint, false);
        ReaderLineJustifier.LineLayout justified = ReaderLineJustifier.layout(text, 0f, natural.naturalWidth() + 36f, paint, true);

        assertTrue(natural.naturalWidth() > naturalWithoutSpacing);
        assertTrue(justified.isJustified());
        assertEquals(36f, justified.residualWidth(), 1f);
        assertTrue(justified.extraGap() > 0f);
    }

    @Test
    public void layout_doesNotAddExtraGapWhenJustifyDisabled() {
        TextPaint paint = newPaint();
        ReaderLineJustifier.LineLayout layout = ReaderLineJustifier.layout("一二三四", 0f, 600f, paint, false);

        assertFalse(layout.isJustified());
        assertEquals(0f, layout.extraGap(), 0.01f);
    }

    @Test
    public void layout_distributesChineseLineAcrossCharacterGaps() {
        TextPaint paint = newPaint();
        String text = "一二三";
        ReaderLineJustifier.LineLayout natural = ReaderLineJustifier.layout(text, 0f, 300f, paint, false);
        ReaderLineJustifier.LineLayout justified = ReaderLineJustifier.layout(text, 0f, natural.naturalWidth() + 30f, paint, true);

        float naturalFirstGap = natural.unitX(1) - natural.unitX(0);
        float justifiedFirstGap = justified.unitX(1) - justified.unitX(0);

        assertTrue(justified.isJustified());
        assertFalse(justified.usesSpaceGaps());
        assertTrue(justifiedFirstGap > naturalFirstGap);
    }

    @Test
    public void layout_prefersMultipleOrdinarySpacesOverCharacterGaps() {
        TextPaint paint = newPaint();
        String text = "a b c";
        ReaderLineJustifier.LineLayout natural = ReaderLineJustifier.layout(text, 0f, 300f, paint, false);
        ReaderLineJustifier.LineLayout justified = ReaderLineJustifier.layout(text, 0f, natural.naturalWidth() + 40f, paint, true);

        float gapAfterLetter = justified.unitX(1) - justified.unitX(0) - unitAdvance(justified, 0, paint);
        float gapAfterSpace = justified.unitX(2) - justified.unitX(1) - unitAdvance(justified, 1, paint);

        assertTrue(justified.isJustified());
        assertTrue(justified.usesSpaceGaps());
        assertTrue(gapAfterSpace > gapAfterLetter);
    }

    @Test
    public void layout_keepsGraphemeClustersTogether() {
        TextPaint paint = newPaint();
        String text = "A\u0301\uD83D\uDC69\u200D\uD83D\uDD25B";
        ReaderLineJustifier.LineLayout layout = ReaderLineJustifier.layout(text, 0f, 500f, paint, true);

        assertEquals(3, layout.unitCount());
        assertEquals("A\u0301", layout.unitAt(0).text);
        assertEquals("\uD83D\uDC69\u200D\uD83D\uDD25", layout.unitAt(1).text);
        assertEquals("B", layout.unitAt(2).text);
    }

    private float unitAdvance(ReaderLineJustifier.LineLayout layout, int unitIndex, TextPaint paint) {
        ReaderLineJustifier.TextUnit unit = layout.unitAt(unitIndex);
        return ReaderLineJustifier.measureRunAdvance(unit.text, unit.length(), paint);
    }

    private TextPaint newPaint() {
        TextPaint paint = new TextPaint();
        paint.setTextSize(32f);
        return paint;
    }
}
