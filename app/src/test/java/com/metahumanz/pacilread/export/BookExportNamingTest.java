package com.metahumanz.pacilread.export;

import static org.junit.Assert.assertEquals;

import com.metahumanz.pacilread.model.BookRecord;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class BookExportNamingTest {
    @Test
    public void preservesOriginalNameAndAddsCollisionSuffix() {
        BookRecord book = new BookRecord();
        book.sourceDisplayName = "小说.epub";
        book.bookType = "epub";
        book.localPath = "C:/books/random.epub";
        Set<String> used = new HashSet<>();
        used.add("小说.epub");

        assertEquals("小说 (2).epub", BookExportNaming.uniqueFileName(book, used));
    }

    @Test
    public void buildsSafeFallbackForLegacyBook() {
        BookRecord book = new BookRecord();
        book.title = "标题:一";
        book.author = "作者";
        book.bookType = "pdf";

        assertEquals("标题_一 - 作者.pdf", BookExportNaming.uniqueFileName(book, new HashSet<>()));
    }
}
