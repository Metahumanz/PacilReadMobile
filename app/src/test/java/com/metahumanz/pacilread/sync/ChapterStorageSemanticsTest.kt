package com.metahumanz.pacilread.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterStorageSemanticsTest {
    private val path = "chapter_text/book_12/chapter_3.txt.gz"

    @Test fun explicitFileGzipIsExternal() = assertTrue(isFileGzipChapterStorage("file_gzip", path))
    @Test fun missingStorageWithPathIsExternal() = assertTrue(isFileGzipChapterStorage(null, path))
    @Test fun explicitDbWithPathIsNotExternal() = assertFalse(isFileGzipChapterStorage("db", path))
    @Test fun emptyPathIsNotExternal() = assertFalse(isFileGzipChapterStorage("file_gzip", ""))
    @Test fun normalizeMissingStorageWithPath() = assertEquals("file_gzip", normalizeChapterStorage(null, path))
}
