package com.metahumanz.pacilread.reader.search;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class SearchTextMatcherTest {
    @Test
    public void returnsEveryChineseAndCaseInsensitiveMatch() {
        assertEquals(Arrays.asList(0, 7), SearchTextMatcher.findAll("星河 abc 星河 ABC", "星河"));
        assertEquals(Arrays.asList(3, 10), SearchTextMatcher.findAll("星河 abc 星河 ABC", "ABC"));
    }

    @Test
    public void includesOverlappingMatches() {
        assertEquals(Arrays.asList(0, 1, 2), SearchTextMatcher.findAll("aaaa", "aa"));
    }
}
