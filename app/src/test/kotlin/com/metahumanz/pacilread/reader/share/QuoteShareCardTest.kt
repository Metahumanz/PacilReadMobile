package com.metahumanz.pacilread.reader.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteShareCardTest {
    @Test
    fun extractsContextAroundSelection() {
        val excerpt = QuoteShareCard.contextExcerpt("前面的文字。这里是选中的文字。后面的文字。", 6, 14)
        assertEquals("面的文字。", excerpt.before)
        assertEquals("。后面的文", excerpt.after)
    }

    @Test
    fun omitsMissingContextAndKeepsOnlyNearbyLines() {
        val source = "选中" + "后".repeat(120)
        val atStart = QuoteShareCard.contextExcerpt(source, 0, 2)
        assertTrue(atStart.before.isEmpty())
        assertEquals(5, atStart.after.length)

        val longBefore = "前".repeat(120) + "选中"
        val atEnd = QuoteShareCard.contextExcerpt(longBefore, longBefore.length - 2, longBefore.length)
        assertEquals(5, atEnd.before.length)
        assertFalse(atEnd.before.isEmpty())
        assertTrue(atEnd.after.isEmpty())
    }
}
