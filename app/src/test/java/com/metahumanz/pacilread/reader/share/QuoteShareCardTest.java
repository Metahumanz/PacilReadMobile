package com.metahumanz.pacilread.reader.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuoteShareCardTest {
    @Test
    public void extractsContextAroundSelection() {
        QuoteShareCard.ContextExcerpt excerpt = QuoteShareCard.contextExcerpt(
                "前面的文字。这里是选中的文字。后面的文字。", 6, 14);

        assertEquals("前面的文字。", excerpt.before);
        assertEquals("。后面的文字。", excerpt.after);
    }

    @Test
    public void omitsMissingContextAndKeepsOnlyNearbyLines() {
        String source = "选中" + "后".repeat(120);
        QuoteShareCard.ContextExcerpt atStart = QuoteShareCard.contextExcerpt(source, 0, 2);
        assertTrue(atStart.before.isEmpty());
        assertEquals(40, atStart.after.length());

        String longBefore = "前".repeat(120) + "选中";
        QuoteShareCard.ContextExcerpt atEnd = QuoteShareCard.contextExcerpt(
                longBefore, longBefore.length() - 2, longBefore.length());
        assertEquals(40, atEnd.before.length());
        assertFalse(atEnd.before.isEmpty());
        assertTrue(atEnd.after.isEmpty());
    }
}
