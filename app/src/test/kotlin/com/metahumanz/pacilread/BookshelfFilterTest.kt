package com.metahumanz.pacilread

import com.metahumanz.pacilread.model.BookRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfFilterTest {
    @Test
    fun combinesQueryTagSeriesAndStatusWithAnd() {
        val book = BookRecord().apply {
            title = "银河帝国"
            author = "Isaac Asimov"
            tags = mutableListOf("科幻", "经典")
            series = "基地系列"
            readingStatus = BookRecord.STATUS_READING
        }

        assertTrue(BookshelfFilter.matches(book, "asimov", "科幻", "基地系列", BookRecord.STATUS_READING))
        assertFalse(BookshelfFilter.matches(book, "asimov", "历史", "基地系列", BookRecord.STATUS_READING))
        assertFalse(BookshelfFilter.matches(book, "银河", "科幻", "基地系列", BookRecord.STATUS_FINISHED))
    }
}
