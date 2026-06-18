package com.metahumanz.pacilread;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.metahumanz.pacilread.model.BookRecord;

import org.junit.Test;

import java.util.Arrays;

public class BookshelfFilterTest {
    @Test
    public void combinesQueryTagSeriesAndStatusWithAnd() {
        BookRecord book = new BookRecord();
        book.title = "银河帝国";
        book.author = "Isaac Asimov";
        book.tags = Arrays.asList("科幻", "经典");
        book.series = "基地系列";
        book.readingStatus = BookRecord.STATUS_READING;

        assertTrue(BookshelfFilter.matches(
                book, "asimov", "科幻", "基地系列", BookRecord.STATUS_READING));
        assertFalse(BookshelfFilter.matches(
                book, "asimov", "历史", "基地系列", BookRecord.STATUS_READING));
        assertFalse(BookshelfFilter.matches(
                book, "银河", "科幻", "基地系列", BookRecord.STATUS_FINISHED));
    }
}
