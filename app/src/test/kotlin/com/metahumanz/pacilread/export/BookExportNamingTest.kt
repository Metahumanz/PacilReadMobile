package com.metahumanz.pacilread.export

import com.metahumanz.pacilread.model.BookRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class BookExportNamingTest {
    @Test
    fun preservesOriginalNameAndAddsCollisionSuffix() {
        val book = BookRecord().apply {
            sourceDisplayName = "小说.epub"
            bookType = "epub"
            localPath = "C:/books/random.epub"
        }
        assertEquals("小说 (2).epub", BookExportNaming.uniqueFileName(book, hashSetOf("小说.epub")))
    }

    @Test
    fun buildsSafeFallbackForLegacyBook() {
        val book = BookRecord().apply {
            title = "标题:一"
            author = "作者"
            bookType = "pdf"
        }
        assertEquals("标题_一 - 作者.pdf", BookExportNaming.uniqueFileName(book, hashSetOf()))
    }
}
