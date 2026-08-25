package com.metahumanz.pacilread.importer

import com.metahumanz.pacilread.reader.ReaderDisplayTextNormalizer
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TxtChapterParserTest {
    @Test
    fun importedParagraphIndentDoesNotRemainVisibleInDisplayText() {
        val source = "\u3000\u3000第一段\n\u3000\u3000第二段"
        val chapter = TxtChapterParser.parse(
            ByteArrayInputStream(source.toByteArray(StandardCharsets.UTF_8)),
        ).single()

        val display = ReaderDisplayTextNormalizer.maskParagraphLeadingWhitespace(chapter.bodyText)
        val lines = display.split('\n')

        assertEquals("第一段", lines[0].replace("\u2060", ""))
        assertEquals("第二段", lines[1].replace("\u2060", ""))
        assertFalse(ReaderDisplayTextNormalizer.isParagraphWhitespace(lines[0][0]))
        assertFalse(ReaderDisplayTextNormalizer.isParagraphWhitespace(lines[1][0]))
    }
}
