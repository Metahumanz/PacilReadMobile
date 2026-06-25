package com.metahumanz.pacilread.reader.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTextMatcherTest {
    @Test
    fun returnsEveryChineseAndCaseInsensitiveMatch() {
        assertEquals(listOf(0, 7), SearchTextMatcher.findAll("星河 abc 星河 ABC", "星河"))
        assertEquals(listOf(3, 10), SearchTextMatcher.findAll("星河 abc 星河 ABC", "ABC"))
    }

    @Test
    fun includesOverlappingMatches() {
        assertEquals(listOf(0, 1, 2), SearchTextMatcher.findAll("aaaa", "aa"))
    }
}
