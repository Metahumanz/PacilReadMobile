package com.metahumanz.pacilread.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class BookDuplicateDetectorTest {
    @Test
    fun exactHashTakesPriorityOverMetadata() {
        val existing = BookDuplicateDetector.Candidate("old", "旧标题", "甲", "abc")
        val incoming = BookDuplicateDetector.Candidate("new", "新标题", "乙", "ABC")
        val result = BookDuplicateDetector.detect(listOf(existing), listOf(incoming))
        assertEquals(BookDuplicateDetector.MatchType.EXACT_CONTENT, result["new"])
    }

    @Test
    fun detectsMetadataAndDuplicatesInsideIncomingBatch() {
        val first = BookDuplicateDetector.Candidate("first", "同一本书", "作者", "one")
        val second = BookDuplicateDetector.Candidate("second", " 同一本书 ", "作者", "two")
        val third = BookDuplicateDetector.Candidate("third", "另一标题", "其他", "one")
        val result = BookDuplicateDetector.detect(emptyList(), listOf(first, second, third))
        assertEquals(BookDuplicateDetector.MatchType.SAME_TITLE_AUTHOR, result["second"])
        assertEquals(BookDuplicateDetector.MatchType.EXACT_CONTENT, result["third"])
    }
}
