package com.metahumanz.pacilread.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDisplayTextNormalizerTest {
    @Test
    fun masksParagraphIndentWithoutChangingOffsets() {
        val source = "\u3000\u3000第一段\n  第二段\n\t第三段"

        val display = ReaderDisplayTextNormalizer.maskParagraphLeadingWhitespace(source)

        assertEquals("\u2060\u2060第一段\n\u2060\u2060第二段\n\u2060第三段", display)
        assertEquals(source.length, display.length)
        assertEquals(source.indexOf('\n'), display.indexOf('\n'))
        assertEquals(source.lastIndexOf('\n'), display.lastIndexOf('\n'))
    }

    @Test
    fun masksUnicodeIndentAndPreservesInternalWhitespace() {
        val source = "\u00A0\u2003\uFEFF正文 中间  \n下一段"

        val display = ReaderDisplayTextNormalizer.maskParagraphLeadingWhitespace(source)

        assertEquals("\u2060\u2060\u2060正文 中间  \n下一段", display)
    }

    @Test
    fun preservesWhitespaceOnlyLinesAndIsIdempotent() {
        val source = "正文\n\u3000\u3000\n   \n\t"

        val first = ReaderDisplayTextNormalizer.maskParagraphLeadingWhitespace(source)
        val second = ReaderDisplayTextNormalizer.maskParagraphLeadingWhitespace(first)

        assertEquals(source, first)
        assertSame(first, second)
    }

    @Test
    fun returnsOriginalInstanceWhenNoMaskingIsNeeded() {
        val source = "第一段\n第二段"

        assertSame(source, ReaderDisplayTextNormalizer.maskParagraphLeadingWhitespace(source))
    }

    @Test
    fun maskedIndentIsExcludedFromJustificationContent() {
        val unit = ReaderLineJustifier.TextUnit("\u2060\u2060", 0, 2)

        assertTrue(unit.isIndent())
    }
}
